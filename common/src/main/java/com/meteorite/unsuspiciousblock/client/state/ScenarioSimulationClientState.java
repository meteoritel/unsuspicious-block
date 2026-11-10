package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.*;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.*;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.*;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.*;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.*;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import java.util.*;

/** 当前选择、计算状态和结果按输入隔离；晚到答复只入缓存，不改玩家的新选择。 */
public final class ScenarioSimulationClientState {
    /** 一次只读查询的版本凭据与当前参数，不存跨玩家进度。 */
    private record CacheQuery(long id, String input, ScenarioParams params, long started,
                              Set<String> available, boolean complete, String error) {}
    private static State state = new State();

    private static final class State {
        private final Map<ResourceLocation, CatalogTableDto> TABLES = new LinkedHashMap<>();
        private final Map<ResourceLocation, SimulationPreferenceStore.Selection> SELECTIONS = new HashMap<>();
        private final Map<String, CatalogTableDto> RESULTS = new LinkedHashMap<>();
        private final Map<String, Long> PENDING = new HashMap<>();
        private final Map<String, String> FAILURES = new LinkedHashMap<>();
        private final Map<ResourceLocation, CacheQuery> CACHE_QUERIES = new LinkedHashMap<>();
        private long assistId;
        private String assistInput = "";
        private SyncSimulationAssistPayload assist;
        private long assistStarted;
        private List<Component> notes = List.of();
    }

    private static long revision;
    private static long sequence;
    private ScenarioSimulationClientState() {}
    public static String keyOf(ResourceLocation table, String input) { return table + "#" + input; }
    public static CatalogTableDto table(ResourceLocation id) { return state.TABLES.get(id); }
    public static SimulationPreferenceStore.Selection selection(ResourceLocation id) { return state.SELECTIONS.get(id); }
    public static String inputKey(SimulationPreferenceStore.Selection selection) {
        return new SimulationInput(selection.scene(), Map.of(), selection.params()).key();
    }
    public static void catalog(List<CatalogTableDto> tables) {
        boolean changedGeneration = tables.stream().anyMatch(t -> {
            var old = state.TABLES.get(t.id());
            return old != null && old.options() != null && t.options() != null
                    && old.options().generation() != t.options().generation();
        });
        if (changedGeneration) { state.RESULTS.clear(); state.PENDING.clear(); state.FAILURES.clear(); state.CACHE_QUERIES.clear(); cancelAssist(); }
        Set<ResourceLocation> ids = new HashSet<>();
        for (CatalogTableDto table : tables) {
            ids.add(table.id());
            var old = state.TABLES.put(table.id(), table);
            if (old != null && !old.hash().equals(table.hash())) {
                state.CACHE_QUERIES.remove(table.id());
                cancelAssist();
                String prefix = table.id() + "#";
                state.RESULTS.keySet().removeIf(k -> k.startsWith(prefix));
                state.PENDING.keySet().removeIf(k -> k.startsWith(prefix));
                state.FAILURES.keySet().removeIf(k -> k.startsWith(prefix));
            }
            var options = table.options();
            if (options == null || options.tools().isEmpty() || options.scenes().isEmpty()) continue;
            var choice = state.SELECTIONS.get(table.id());
            if (choice == null) choice = SimulationPreferenceStore.get(table.id());
            if (choice == null || options.rejects(choice.scene(), choice.params()))
                choice = new SimulationPreferenceStore.Selection("baseline",
                        ScenarioParams.baseline(options.tools().getFirst().id()));
            state.SELECTIONS.put(table.id(), choice);
        }
        state.TABLES.keySet().retainAll(ids); state.SELECTIONS.keySet().retainAll(ids);
        state.CACHE_QUERIES.keySet().retainAll(ids);
    }
    public static void select(ResourceLocation table, String scene, ScenarioParams params) {
        var dto = state.TABLES.get(table);
        if (dto == null || dto.options() == null || dto.options().rejects(scene, params)) return;
        var choice = new SimulationPreferenceStore.Selection(scene, params);
        if (choice.equals(state.SELECTIONS.put(table, choice))) return;
        SimulationPreferenceStore.put(table, choice); cancelAssist();
        revision++;
        queryCache(table, true);
    }
    public static void queryCache(ResourceLocation table, boolean force) {
        var request = requestOf(table);
        var selected = selection(table);
        if (request == null || selected == null) return;
        String input = inputKey(selected);
        CacheQuery old = state.CACHE_QUERIES.get(table);
        long now = System.currentTimeMillis();
        if (!force && old != null && old.input().equals(input) && now - old.started() < 15_000) return;
        state.CACHE_QUERIES.put(table, new CacheQuery(++sequence, input, selected.params(), now, Set.of(), false, ""));
        while (state.CACHE_QUERIES.size() > 64) state.CACHE_QUERIES.remove(state.CACHE_QUERIES.keySet().iterator().next());
        ClientNetwork.sendToServer(new RequestScenarioCachePayload(sequence, force || result(table, input) == null, request));
        revision++;
    }
    public static void ensureCacheQuery(ResourceLocation table) {
        var choice = selection(table);
        var query = state.CACHE_QUERIES.get(table);
        if (choice != null && (query == null || !query.input().equals(inputKey(choice)))) queryCache(table, false);
        else if (query != null && query.error().equals("busy") && System.currentTimeMillis() - query.started() >= 250)
            queryCache(table, true);
    }
    public static void receiveCache(SyncScenarioCachePayload payload) {
        var table = state.TABLES.get(payload.tableId());
        var current = selection(payload.tableId());
        CacheQuery query = state.CACHE_QUERIES.get(payload.tableId());
        if (table == null || table.options() == null || current == null || query == null
                || query.id() != payload.requestId() || !query.input().equals(payload.inputKey())
                || !inputKey(current).equals(payload.inputKey()) || !table.hash().equals(payload.tableHash())
                || table.options().generation() != payload.generation()) return;
        state.CACHE_QUERIES.put(table.id(), new CacheQuery(query.id(), query.input(), query.params(), query.started(),
                Set.copyOf(payload.availableScenes()), true, payload.error()));
        if (payload.error().equals("stale_hash")) ClientNetwork.sendToServer(new RequestCatalogPayload());
        revision++;
    }
    public static RequestScenarioSimulationPayload requestOf(ResourceLocation table) {
        var dto = state.TABLES.get(table); var selection = state.SELECTIONS.get(table);
        if (dto == null || dto.options() == null || selection == null) return null;
        var p = selection.params();
        return new RequestScenarioSimulationPayload(dto.options().generation(), table, dto.hash(),
                selection.scene(), p.luck(), p.toolId(), p.toolEnchantments(), p.sampleCount());
    }
    public static void request(ResourceLocation table, boolean manual) {
        var request = requestOf(table);
        if (request == null) return;
        String input = inputKey(state.SELECTIONS.get(table)), status = status(table, input);
        if (status.equals("pending") || status.equals("cached") || !manual && status.equals("failed")) return;
        String key = keyOf(table, input);
        state.PENDING.put(key, System.currentTimeMillis()); state.FAILURES.remove(key);
        ClientNetwork.sendToServer(request);
        revision++;
    }
    public static String status(ResourceLocation table, String input) {
        String key = keyOf(table, input);
        if (result(table, input) != null) return "cached";
        Long start = state.PENDING.get(key);
        if (start != null) {
            if (System.currentTimeMillis() - start <= 120_000) return "pending";
            return "failed";
        }
        if (state.FAILURES.containsKey(key)) return "failed";
        CacheQuery query = state.CACHE_QUERIES.get(table);
        var structure = state.TABLES.get(table);
        if (query != null && structure != null && structure.options() != null) {
            for (var scene : structure.options().scenes()) {
                if (!new SimulationInput(scene.scenarioKey(), Map.of(), query.params()).key().equals(input)) continue;
                if (!query.complete()) return System.currentTimeMillis() - query.started() < 15_000 ? "querying" : "query_failed";
                if (!query.error().isEmpty()) return "query_failed";
                if (query.available().contains(scene.scenarioKey()))
                    return query.input().equals(input) ? System.currentTimeMillis() - query.started() > 15_000
                            ? "query_failed" : "retrieving" : "available";
            }
        }
        return "uncomputed";
    }
    public static String failure(ResourceLocation table, String input) {
        return state.FAILURES.getOrDefault(keyOf(table, input), "timeout");
    }
    public static void receive(SyncScenarioResultPayload payload) {
        var table = state.TABLES.get(payload.table().id());
        if (table == null || table.options() == null || table.options().generation() != payload.generation()
                || !table.hash().equals(payload.tableHash())) return;
        String key = keyOf(table.id(), payload.inputKey());
        boolean failed = payload.table().items().stream().anyMatch(item ->
                item.probability() instanceof Probability.Unknown(var reason)
                        && reason == UnknownReason.SIMULATION_FAILED);
        if (failed) {
            state.PENDING.remove(key); state.FAILURES.put(key, "simulation_failed");
            revision++;
            return;
        }
        state.RESULTS.put(key, payload.table());
        mergeStructure(table, payload.table());
        while (state.RESULTS.size() > 64) state.RESULTS.remove(state.RESULTS.keySet().iterator().next());
        state.PENDING.remove(key); state.FAILURES.remove(key); revision++;
    }

    private static void mergeStructure(CatalogTableDto baseline, CatalogTableDto incoming) {
        Map<LootResultSignature, CatalogTableDto.ItemEntry> items = new LinkedHashMap<>();
        baseline.items().forEach(item -> items.put(item.signature(), item));
        // 结构合并只补"条目存在性"：概率置未知，来源集合必须原样保留（否则来源分型会退化），但**观测必须清空**。
        // 判定口径：观测是输入级数据，只有精确命中当前 input key 的 DTO（overlay 中 resolved.exactInput() 为 true、
        // 走 source.toTableDefinition() 的那条路径）才允许携带 observedFunctions；公共结构 state.TABLES 由任意输入共享，
        // 携带观测就等于把别的输入的观测冒充成"本次模拟观测"。
        for (var item : incoming.items()) items.putIfAbsent(item.signature(), new CatalogTableDto.ItemEntry(
                item.id(), item.displayName(), item.tooltipHint(), Probability.unknown(UnknownReason.NOT_SIMULATED),
                item.signature(), item.acquisitionPaths(), item.injected(), List.of(), item.origins(),
                null));
        Set<ScenarioBranch> branches = new LinkedHashSet<>(baseline.branches());
        branches.addAll(incoming.branches());
        Set<String> identified = new HashSet<>();
        for (var branch : branches) if (!branch.injectionSource().isEmpty() && !branch.injectionSource().equals("observed"))
            identified.add(branch.kind() + "#" + branch.target());
        branches.removeIf(branch -> branch.injectionSource().equals("observed")
                && identified.contains(branch.kind() + "#" + branch.target()));
        state.TABLES.put(baseline.id(), new CatalogTableDto(baseline.id(), baseline.hash(), baseline.displayName(), baseline.type(),
                baseline.simulationCount(), baseline.childTables(), baseline.scenarios(), List.copyOf(items.values()),
                baseline.childProbabilities(), baseline.options(), List.copyOf(branches)));
    }
    public static void receiveRejection(ScenarioRequestRejectedPayload payload) {
        var table = state.TABLES.get(payload.tableId());
        if (table == null || table.options() == null || table.options().generation() != payload.generation()) return;
        String key = keyOf(payload.tableId(), payload.inputKey());
        if (!state.PENDING.containsKey(key)) return;
        state.PENDING.remove(key); state.FAILURES.put(key, payload.reason().name().toLowerCase(Locale.ROOT));
        while (state.FAILURES.size() > 64) state.FAILURES.remove(state.FAILURES.keySet().iterator().next());
        if (payload.reason() == ScenarioRequestRejectedPayload.Reason.STALE_HASH)
            ClientNetwork.sendToServer(new RequestCatalogPayload());
        revision++;
    }
    public static CatalogTableDto result(ResourceLocation id, String input) {
        var found = state.RESULTS.get(keyOf(id, input));
        if (found != null) return found;
        var base = state.TABLES.get(id);
        if (base != null && base.options() != null && base.simulationCount() > 0) {
            String baseline = new SimulationInput("baseline", Map.of(),
                    ScenarioParams.baseline(base.options().tools().getFirst().id())).key();
            if (baseline.equals(input)) return base;
        }
        return null;
    }

    /** 同参数的已缓存结果可包含其他场景的明确概率引用；此处统一详情页、列表与网格的来源。 */
    public record SceneSource(@Nullable CatalogTableDto source, boolean exactInput, String status) {}

    public static SceneSource sceneSource(ResourceLocation id, String scene, ScenarioParams params) {
        String input = new SimulationInput(scene, Map.of(), params).key();
        CatalogTableDto direct = result(id, input);
        if (direct != null) return new SceneSource(direct, true, "cached");
        CatalogTableDto structure = table(id);
        if (structure != null && structure.options() != null) {
            for (var candidate : structure.options().scenes()) {
                String key = new SimulationInput(candidate.scenarioKey(), Map.of(), params).key();
                CatalogTableDto other = result(id, key);
                if (other != null && hasSceneReference(other, scene)) {
                    return new SceneSource(other, false, "cached");
                }
            }
        }
        return new SceneSource(null, false, status(id, input));
    }

    private static boolean hasSceneReference(CatalogTableDto source, String scene) {
        return source.items().stream().anyMatch(item -> item.scenarioProbabilities().stream()
                .anyMatch(ref -> ref.scenarioKey().equals(scene)))
                || source.childProbabilities().stream().anyMatch(child -> child.scenarioProbabilities().stream()
                .anyMatch(ref -> ref.scenarioKey().equals(scene)));
    }

    public static Map<ResourceLocation, TableDefinition> overlay(Map<ResourceLocation, TableDefinition> base) {
        Map<ResourceLocation, TableDefinition> output = new LinkedHashMap<>(base);
        state.SELECTIONS.forEach((id, selection) -> {
            if (!base.containsKey(id)) return;
            SceneSource resolved = sceneSource(id, selection.scene(), selection.params());
            if (resolved.source() != null) {
                output.put(id, resolved.exactInput()
                        ? resolved.source().toTableDefinition()
                        : projectScene(base.get(id), resolved.source(), selection.scene(), selection.params().sampleCount()));
            }
            else {
                var structure = state.TABLES.get(id);
                var table = structure == null ? base.get(id) : structure.toTableDefinition();
                var unknown = new Probability.Unknown(resolved.status().equals("failed")
                        ? UnknownReason.SIMULATION_FAILED : UnknownReason.NOT_SIMULATED);
                // 当前输入未计算：概率置未知，观测同样必须置 null。table 取自公共结构 state.TABLES，
                // 它携带的观测属于别的输入，冒充"本次模拟观测"就是 C1 的那个缺陷。
                output.put(id, new TableDefinition(id, table.displayName(), table.type(),
                        table.items().stream().map(i -> new ItemDefinition(i.id(), i.displayName(),
                                i.tooltipHint(), unknown, i.signature(), i.acquisitionPaths(), i.origins(),
                                List.of(), null)).toList(),
                        selection.params().sampleCount(), table.childTables(),
                        table.childTableProbabilities().stream().map(ch -> new ChildTableProbability(
                                ch.tableId(), unknown, List.of(), ch.conditions())).toList()));
            }
        });
        return output;
    }

    // 非当前输入的缓存只借用明确的分场景引用；总概率仍属于它原本的场景，绝不复制过来。
    private static TableDefinition projectScene(TableDefinition base, CatalogTableDto source,
                                                String scene, int sampleCount) {
        TableDefinition measured = source.toTableDefinition();
        Map<LootResultSignature, ItemDefinition> bySignature = new HashMap<>();
        for (ItemDefinition item : measured.items()) bySignature.putIfAbsent(item.signature(), item);
        Map<ResourceLocation, ChildTableProbability> byChild = new HashMap<>();
        for (ChildTableProbability child : measured.childTableProbabilities()) {
            byChild.putIfAbsent(child.tableId(), child);
        }
        Probability unknown = new Probability.Unknown(UnknownReason.NOT_SIMULATED);
        List<ItemDefinition> items = base.items().stream().map(original -> {
            ItemDefinition candidate = bySignature.get(original.signature());
            Probability probability = candidate == null ? unknown
                    : sceneProbability(candidate.scenarioProbabilities(), scene, unknown);
            // 跨场景只借明确的分场景引用；candidate 来自别的场景、original 来自公共结构 state.TABLES，
            // 两者的观测都属于别的输入，一律置 null（只有精确命中当前 input key 的 DTO 可携带观测）。
            return new ItemDefinition(original.id(), original.displayName(), original.tooltipHint(), probability,
                    original.signature(), original.acquisitionPaths(), original.origins(),
                    candidate == null ? original.scenarioProbabilities() : candidate.scenarioProbabilities(),
                    null);
        }).toList();
        List<ChildTableProbability> children = base.childTableProbabilities().stream().map(original -> {
            ChildTableProbability candidate = byChild.get(original.tableId());
            Probability probability = candidate == null ? unknown
                    : sceneProbability(candidate.scenarioProbabilities(), scene, unknown);
            return new ChildTableProbability(original.tableId(), probability,
                    candidate == null ? original.scenarioProbabilities() : candidate.scenarioProbabilities(),
                    original.conditions());
        }).toList();
        return new TableDefinition(base.id(), base.displayName(), base.type(), items, sampleCount,
                base.childTables(), children);
    }

    private static Probability sceneProbability(List<ScenarioProbability> probabilities, String scene,
                                                 Probability fallback) {
        for (ScenarioProbability probability : probabilities) {
            if (probability.scenarioKey().equals(scene)) return probability.probability();
        }
        return fallback;
    }
    public static void requestAssist(ResourceLocation table, SimulationAssistTarget target) {
        var request = requestOf(table);
        if (request == null) return;
        state.assistId = ++sequence; state.assistInput = keyOf(table, inputKey(state.SELECTIONS.get(table)));
        state.assistStarted = System.currentTimeMillis();
        state.assist = null; state.notes = List.of(text("assist_pending"));
        ClientNetwork.sendToServer(new RequestSimulationAssistPayload(state.assistId, target, request));
    }
    public static void receiveAssist(SyncSimulationAssistPayload payload) {
        var p = payload.selection(); var current = requestOf(p.tableId());
        if (payload.requestId() != state.assistId || current == null || current.generation() != p.generation()
                || !current.tableHash().equals(p.tableHash())
                || !state.assistInput.equals(keyOf(p.tableId(), inputKey(state.SELECTIONS.get(p.tableId()))))) return;
        state.notes = payload.notes(); state.assist = payload;
        state.assistStarted = 0;
        revision++;
        if (!payload.recommendation() && payload.found()) {
            apply(payload);
            state.notes = payload.notes().isEmpty() ? List.of(text("probe_done")) : payload.notes();
        }
    }
    public static boolean hasRecommendation(ResourceLocation table) {
        var current = requestOf(table);
        return state.assist != null && state.assist.recommendation() && state.assist.found() && current != null
                && state.assist.selection().tableId().equals(table)
                && state.assist.selection().generation() == current.generation()
                && state.assist.selection().tableHash().equals(current.tableHash())
                && state.assistInput.equals(keyOf(table, inputKey(state.SELECTIONS.get(table))));
    }
    @Nullable
    public static RequestScenarioSimulationPayload recommendation(ResourceLocation table) {
        return hasRecommendation(table) ? state.assist.selection() : null;
    }
    public static boolean assistPending() {
        return state.assistStarted != 0;
    }

    public static long revision() {
        return revision;
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (state.PENDING.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= 120_000) return false;
            state.FAILURES.put(entry.getKey(), "timeout");
            return true;
        })) revision++;
        if (state.assistStarted != 0 && now - state.assistStarted > 15_000) {
            cancelAssist();
            state.notes = List.of(text("assist_timeout"));
            revision++;
        }
    }

    public static void applyRecommendation(ResourceLocation table) { if (hasRecommendation(table)) apply(state.assist); }
    private static void apply(SyncSimulationAssistPayload payload) {
        var p = payload.selection();
        select(p.tableId(), p.scenarioKey(), new ScenarioParams(p.luck(), p.toolId(), p.toolEnchantments(), p.sampleCount()));
        // 应用推荐只改变选择；用户点计算后才请求测量。
    }
    public static List<Component> notes() { return state.notes; }
    public static void cancelAssist() {
        state.assistId = ++sequence; state.assist = null; state.assistStarted = 0; state.assistInput = ""; state.notes = List.of();
    }
    public static Component text(String key, Object... args) {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.simulation." + key, args);
    }
    public static void clear() {
        state = new State();
        state.assistId = ++sequence;
        revision++;
    }
}
