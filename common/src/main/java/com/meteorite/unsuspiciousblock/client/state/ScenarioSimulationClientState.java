package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.loottable.catalog.*;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.*;
import com.meteorite.unsuspiciousblock.loottable.simulation.*;
import com.meteorite.unsuspiciousblock.network.payload.c2s.*;
import com.meteorite.unsuspiciousblock.network.payload.s2c.*;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** 当前选择、计算状态和结果按输入隔离；晚到答复只入缓存，不改玩家的新选择。 */
public final class ScenarioSimulationClientState {
    private static final Map<ResourceLocation, CatalogTableDto> TABLES = new LinkedHashMap<>();
    private static final Map<ResourceLocation, SimulationPreferenceStore.Selection> SELECTIONS = new HashMap<>();
    private static final Map<String, CatalogTableDto> RESULTS = new LinkedHashMap<>();
    private static final Map<String, Long> PENDING = new HashMap<>();
    private static final Map<String, String> FAILURES = new LinkedHashMap<>();
    private static long sequence, assistId;
    private static String assistInput = "";
    private static SyncSimulationAssistPayload assist;
    private static List<Component> notes = List.of();
    private ScenarioSimulationClientState() {}
    public static String keyOf(ResourceLocation table, String input) { return table + "#" + input; }
    public static CatalogTableDto table(ResourceLocation id) { return TABLES.get(id); }
    public static SimulationPreferenceStore.Selection selection(ResourceLocation id) { return SELECTIONS.get(id); }
    public static String inputKey(SimulationPreferenceStore.Selection selection) {
        return new SimulationInput(selection.scene(), Map.of(), selection.params()).key();
    }
    public static void catalog(List<CatalogTableDto> tables) {
        boolean changedGeneration = tables.stream().anyMatch(t -> {
            var old = TABLES.get(t.id());
            return old != null && old.options() != null && t.options() != null
                    && old.options().generation() != t.options().generation();
        });
        if (changedGeneration) { RESULTS.clear(); PENDING.clear(); FAILURES.clear(); cancelAssist(); }
        Set<ResourceLocation> ids = new HashSet<>();
        for (CatalogTableDto table : tables) {
            ids.add(table.id());
            var old = TABLES.put(table.id(), table);
            if (old != null && !old.hash().equals(table.hash())) {
                String prefix = table.id() + "#";
                RESULTS.keySet().removeIf(k -> k.startsWith(prefix));
                PENDING.keySet().removeIf(k -> k.startsWith(prefix));
                FAILURES.keySet().removeIf(k -> k.startsWith(prefix));
            }
            var options = table.options();
            if (options == null || options.tools().isEmpty() || options.scenes().isEmpty()) continue;
            var choice = SELECTIONS.get(table.id());
            if (choice == null) choice = SimulationPreferenceStore.get(table.id());
            if (choice == null || options.rejects(choice.scene(), choice.params()))
                choice = new SimulationPreferenceStore.Selection("baseline",
                        ScenarioParams.baseline(options.tools().getFirst().id()));
            SELECTIONS.put(table.id(), choice);
        }
        TABLES.keySet().retainAll(ids); SELECTIONS.keySet().retainAll(ids);
    }
    public static void select(ResourceLocation table, String scene, ScenarioParams params) {
        var dto = TABLES.get(table);
        if (dto == null || dto.options() == null || dto.options().rejects(scene, params)) return;
        var choice = new SimulationPreferenceStore.Selection(scene, params);
        if (choice.equals(SELECTIONS.put(table, choice))) return;
        SimulationPreferenceStore.put(table, choice); cancelAssist();
        ArchaeologyJournalClientState.simulationChanged();
    }
    public static RequestScenarioSimulationPayload requestOf(ResourceLocation table) {
        var dto = TABLES.get(table); var selection = SELECTIONS.get(table);
        if (dto == null || dto.options() == null || selection == null) return null;
        var p = selection.params();
        return new RequestScenarioSimulationPayload(dto.options().generation(), table, dto.hash(),
                selection.scene(), p.luck(), p.toolId(), p.toolEnchantments(), p.sampleCount());
    }
    public static void request(ResourceLocation table, boolean manual) {
        var request = requestOf(table);
        if (request == null) return;
        String input = inputKey(SELECTIONS.get(table)), status = status(table, input);
        if (status.equals("pending") || status.equals("cached") || !manual && status.equals("failed")) return;
        String key = keyOf(table, input);
        PENDING.put(key, System.currentTimeMillis()); FAILURES.remove(key);
        Services.NETWORK.sendToServer(request); SimulationPreferenceStore.flush();
        ArchaeologyJournalClientState.simulationChanged();
    }
    public static String status(ResourceLocation table, String input) {
        String key = keyOf(table, input);
        if (result(table, input) != null) return "cached";
        Long start = PENDING.get(key);
        if (start != null) {
            if (System.currentTimeMillis() - start <= 120_000) return "pending";
            PENDING.remove(key); FAILURES.put(key, "timeout");
        }
        return FAILURES.containsKey(key) ? "failed" : "uncomputed";
    }
    public static String failure(ResourceLocation table, String input) {
        return FAILURES.getOrDefault(keyOf(table, input), "timeout");
    }
    public static void receive(SyncScenarioResultPayload payload) {
        var table = TABLES.get(payload.table().id());
        if (table == null || table.options() == null || table.options().generation() != payload.generation()
                || !table.hash().equals(payload.tableHash())) return;
        String key = keyOf(table.id(), payload.inputKey());
        boolean failed = payload.table().items().stream().anyMatch(item ->
                item.probability() instanceof Probability.Unknown(var reason)
                        && reason == UnknownReason.SIMULATION_FAILED);
        if (failed) {
            PENDING.remove(key); FAILURES.put(key, "simulation_failed");
            ArchaeologyJournalClientState.simulationChanged();
            return;
        }
        RESULTS.put(key, payload.table());
        while (RESULTS.size() > 64) RESULTS.remove(RESULTS.keySet().iterator().next());
        PENDING.remove(key); FAILURES.remove(key); ArchaeologyJournalClientState.simulationChanged();
    }
    public static void receiveRejection(ScenarioRequestRejectedPayload payload) {
        var table = TABLES.get(payload.tableId());
        if (table == null || table.options() == null || table.options().generation() != payload.generation()) return;
        String key = keyOf(payload.tableId(), payload.inputKey());
        if (!PENDING.containsKey(key)) return;
        PENDING.remove(key); FAILURES.put(key, payload.reason().name().toLowerCase(Locale.ROOT));
        while (FAILURES.size() > 64) FAILURES.remove(FAILURES.keySet().iterator().next());
        if (payload.reason() == ScenarioRequestRejectedPayload.Reason.STALE_HASH)
            Services.NETWORK.sendToServer(new RequestCatalogPayload());
        ArchaeologyJournalClientState.simulationChanged();
    }
    public static CatalogTableDto result(ResourceLocation id, String input) {
        var found = RESULTS.get(keyOf(id, input));
        if (found != null) return found;
        var base = TABLES.get(id);
        if (base != null && base.options() != null && base.simulationCount() > 0) {
            String baseline = new SimulationInput("baseline", Map.of(),
                    ScenarioParams.baseline(base.options().tools().getFirst().id())).key();
            if (baseline.equals(input)) return base;
        }
        return null;
    }
    public static Map<ResourceLocation, TableDefinition> overlay(Map<ResourceLocation, TableDefinition> base) {
        Map<ResourceLocation, TableDefinition> output = new LinkedHashMap<>(base);
        SELECTIONS.forEach((id, selection) -> {
            if (!base.containsKey(id)) return;
            String input = inputKey(selection);
            var measured = result(id, input);
            if (measured != null) output.put(id, measured.toTableDefinition());
            else {
                var table = base.get(id);
                var unknown = new Probability.Unknown(status(id, input).equals("failed")
                        ? UnknownReason.SIMULATION_FAILED : UnknownReason.NOT_SIMULATED);
                output.put(id, new TableDefinition(id, table.displayName(), table.type(),
                        table.items().stream().map(i -> new ItemDefinition(i.id(), i.displayName(),
                                i.tooltipHint(), unknown, i.signature(), i.acquisitionPaths(), i.injected(), List.of())).toList(),
                        selection.params().sampleCount(), table.childTables(),
                        table.childTableProbabilities().stream().map(ch -> new ChildTableProbability(
                                ch.tableId(), unknown, List.of(), ch.conditions())).toList()));
            }
        });
        return output;
    }
    public static void requestAssist(ResourceLocation table, String target) {
        var request = requestOf(table);
        if (request == null) return;
        assistId = ++sequence; assistInput = keyOf(table, inputKey(SELECTIONS.get(table)));
        assist = null; notes = List.of(text("assist_pending"));
        Services.NETWORK.sendToServer(new RequestSimulationAssistPayload(assistId, target, request));
    }
    public static void receiveAssist(SyncSimulationAssistPayload payload) {
        var p = payload.selection(); var current = requestOf(p.tableId());
        if (payload.requestId() != assistId || current == null || current.generation() != p.generation()
                || !current.tableHash().equals(p.tableHash())
                || !assistInput.equals(keyOf(p.tableId(), inputKey(SELECTIONS.get(p.tableId()))))) return;
        notes = payload.notes(); assist = payload;
        ArchaeologyJournalClientState.simulationChanged();
        if (!payload.recommendation() && payload.found()) {
            apply(payload);
            notes = payload.notes().isEmpty() ? List.of(text("probe_done")) : payload.notes();
        }
    }
    public static boolean hasRecommendation(ResourceLocation table) {
        return assist != null && assist.recommendation() && assist.found() && assist.selection().tableId().equals(table);
    }
    public static void applyRecommendation(ResourceLocation table) { if (hasRecommendation(table)) apply(assist); }
    private static void apply(SyncSimulationAssistPayload payload) {
        var p = payload.selection();
        select(p.tableId(), p.scenarioKey(), new ScenarioParams(p.luck(), p.toolId(), p.toolEnchantments(), p.sampleCount()));
        // 应用推荐只改变选择；用户点计算后才请求测量。
    }
    public static List<Component> notes() { return notes; }
    private static void cancelAssist() { assistId = ++sequence; assist = null; notes = List.of(); }
    public static Component text(String key, Object... args) {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.simulation." + key, args);
    }
    public static void clear() {
        SimulationPreferenceStore.flush();
        TABLES.clear(); SELECTIONS.clear(); RESULTS.clear(); PENDING.clear(); FAILURES.clear(); cancelAssist();
    }
}
