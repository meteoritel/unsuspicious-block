package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranch;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInputKey;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestScenarioCachePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestScenarioSimulationPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestSimulationAssistPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.ScenarioRequestRejectedPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioCachePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncSimulationAssistPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 当前条件勾选、计算状态和结果按输入隔离；晚到答复只入缓存，不改玩家的新选择。 */
public final class ScenarioSimulationClientState {
    /** 一次只读缓存查询的版本凭据与当前参数。 */
    private record CacheQuery(long id, String input, ScenarioParams params, long started,
                              boolean cached, boolean complete, String error) {
    }

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
        @Nullable private ResourceLocation assistTable;
        @Nullable private SyncSimulationAssistPayload assist;
        private long assistStarted;
        private List<Component> notes = List.of();
        @Nullable private ResourceLocation notesTable;
    }

    private static long revision;
    private static long sequence;

    private ScenarioSimulationClientState() {
    }

    public static String keyOf(ResourceLocation table, String input) {
        return table + "#" + input;
    }

    public static @Nullable CatalogTableDto table(ResourceLocation id) {
        return state.TABLES.get(id);
    }

    public static @Nullable SimulationPreferenceStore.Selection selection(ResourceLocation id) {
        return state.SELECTIONS.get(id);
    }

    public static String inputKey(SimulationPreferenceStore.Selection selection) {
        return SimulationInputKey.of(selection.conditions(), selection.params());
    }

    /** 接收完整目录并初始化或校验各表的持久化选择。 */
    public static void catalog(List<CatalogTableDto> tables) {
        boolean changedGeneration = tables.stream().anyMatch(t -> {
            var old = state.TABLES.get(t.id());
            return old != null && old.options() != null && t.options() != null
                    && old.options().generation() != t.options().generation();
        });
        if (changedGeneration) {
            state.RESULTS.clear();
            state.PENDING.clear();
            state.FAILURES.clear();
            state.CACHE_QUERIES.clear();
            cancelAssist();
        }
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
            if (options == null || options.tools().isEmpty()) {
                continue;
            }
            var choice = state.SELECTIONS.get(table.id());
            if (choice == null) {
                choice = SimulationPreferenceStore.get(table.id());
            }
            if (choice == null || options.rejects(choice.conditions(), choice.params())) {
                choice = new SimulationPreferenceStore.Selection(List.of(),
                        ScenarioParams.baseline(options.tools().getFirst().id()));
            }
            state.SELECTIONS.put(table.id(), choice);
        }
        state.TABLES.keySet().retainAll(ids);
        state.SELECTIONS.keySet().retainAll(ids);
        state.CACHE_QUERIES.keySet().retainAll(ids);
    }

    /** 更新指定表的勾选条件集合与参数，并立即触发一次服务端缓存状态查询。 */
    public static void select(ResourceLocation table, List<String> conditions, ScenarioParams params) {
        var dto = state.TABLES.get(table);
        if (dto == null || dto.options() == null || dto.options().rejects(conditions, params)) {
            return;
        }
        var choice = new SimulationPreferenceStore.Selection(conditions, params);
        if (choice.equals(state.SELECTIONS.put(table, choice))) {
            return;
        }
        SimulationPreferenceStore.put(table, choice);
        cancelAssist();
        revision++;
        queryCache(table, true);
    }

    /** 切换单条环境条件的勾选状态。 */
    public static void toggleCondition(ResourceLocation table, String conditionId) {
        var current = selection(table);
        if (current == null) {
            return;
        }
        Set<String> next = new LinkedHashSet<>(current.conditions());
        if (!next.add(conditionId)) {
            next.remove(conditionId);
        }
        select(table, List.copyOf(next), current.params());
    }

    /** 重置为基准状态（取消全部条件勾选并恢复默认工具/幸运/附魔）。 */
    public static void resetToBaseline(ResourceLocation table) {
        var dto = table(table);
        if (dto == null || dto.options() == null || dto.options().tools().isEmpty()) {
            return;
        }
        select(table, List.of(), ScenarioParams.baseline(dto.options().tools().getFirst().id()));
    }

    /** 向服务端查询当前选择的缓存状态。 */
    public static void queryCache(ResourceLocation table, boolean force) {
        var request = requestOf(table);
        var selected = selection(table);
        if (request == null || selected == null) {
            return;
        }
        String input = inputKey(selected);
        CacheQuery old = state.CACHE_QUERIES.get(table);
        long now = System.currentTimeMillis();
        if (!force && old != null && old.input().equals(input) && now - old.started() < 15_000) {
            return;
        }
        state.CACHE_QUERIES.put(table, new CacheQuery(++sequence, input, selected.params(), now, false, false, ""));
        while (state.CACHE_QUERIES.size() > 64) {
            state.CACHE_QUERIES.remove(state.CACHE_QUERIES.keySet().iterator().next());
        }
        ClientNetwork.sendToServer(new RequestScenarioCachePayload(sequence,
                force || result(table, input) == null, request));
        revision++;
    }

    /** 若当前选择尚未查询缓存（或上次因忙退避），补发只读缓存查询。 */
    public static void ensureCacheQuery(ResourceLocation table) {
        var choice = selection(table);
        var query = state.CACHE_QUERIES.get(table);
        if (choice != null && (query == null || !query.input().equals(inputKey(choice)))) {
            queryCache(table, false);
        } else if (query != null && query.error().equals("busy") && System.currentTimeMillis() - query.started() >= 250) {
            queryCache(table, true);
        }
    }

    /** 接收服务端缓存可用性答复。 */
    public static void receiveCache(SyncScenarioCachePayload payload) {
        var table = state.TABLES.get(payload.tableId());
        var current = selection(payload.tableId());
        CacheQuery query = state.CACHE_QUERIES.get(payload.tableId());
        if (table == null || table.options() == null || current == null || query == null
                || query.id() != payload.requestId() || !query.input().equals(payload.inputKey())
                || !inputKey(current).equals(payload.inputKey()) || !table.hash().equals(payload.tableHash())
                || table.options().generation() != payload.generation()) {
            return;
        }
        state.CACHE_QUERIES.put(table.id(), new CacheQuery(query.id(), query.input(), query.params(), query.started(),
                payload.selectedCached(), true, payload.error()));
        if (payload.error().equals("stale_hash")) {
            ClientNetwork.sendToServer(new RequestCatalogPayload());
        }
        revision++;
    }

    /** 构造与当前选择对应的服务端模拟请求包。 */
    public static @Nullable RequestScenarioSimulationPayload requestOf(ResourceLocation table) {
        var dto = state.TABLES.get(table);
        var selection = state.SELECTIONS.get(table);
        if (dto == null || dto.options() == null || selection == null) {
            return null;
        }
        var p = selection.params();
        return new RequestScenarioSimulationPayload(dto.options().generation(), table, dto.hash(),
                selection.conditions(), p.luck(), p.toolId(), p.toolEnchantments(), p.sampleCount());
    }

    /** 触发一次按需模拟请求。 */
    public static void request(ResourceLocation table, boolean manual) {
        var request = requestOf(table);
        var current = selection(table);
        if (request == null || current == null) {
            return;
        }
        String input = inputKey(current);
        String status = status(table, input);
        if (status.equals("pending") || status.equals("cached") || (!manual && status.equals("failed"))) {
            return;
        }
        String key = keyOf(table, input);
        state.PENDING.put(key, System.currentTimeMillis());
        state.FAILURES.remove(key);
        ClientNetwork.sendToServer(request);
        revision++;
    }

    /** 返回指定表和输入键的当前状态（{@code cached / pending / failed / querying / retrieving / query_failed / uncomputed}）。 */
    public static String status(ResourceLocation table, String input) {
        String key = keyOf(table, input);
        if (result(table, input) != null) {
            return "cached";
        }
        Long start = state.PENDING.get(key);
        if (start != null) {
            return System.currentTimeMillis() - start <= 120_000 ? "pending" : "failed";
        }
        if (state.FAILURES.containsKey(key)) {
            return "failed";
        }
        CacheQuery query = state.CACHE_QUERIES.get(table);
        if (query != null && query.input().equals(input)) {
            if (!query.complete()) {
                return System.currentTimeMillis() - query.started() < 15_000 ? "querying" : "query_failed";
            }
            if (!query.error().isEmpty()) {
                return "query_failed";
            }
            if (query.cached()) {
                return System.currentTimeMillis() - query.started() > 15_000 ? "query_failed" : "retrieving";
            }
        }
        return "uncomputed";
    }

    public static String failure(ResourceLocation table, String input) {
        return state.FAILURES.getOrDefault(keyOf(table, input), "timeout");
    }

    /** 接收服务端按需模拟结果并写入本地有限缓存。 */
    public static void receive(SyncScenarioResultPayload payload) {
        var table = state.TABLES.get(payload.table().id());
        if (table == null || table.options() == null || table.options().generation() != payload.generation()
                || !table.hash().equals(payload.tableHash())) {
            return;
        }
        String key = keyOf(table.id(), payload.inputKey());
        boolean failed = payload.table().items().stream().anyMatch(item ->
                item.probability() instanceof Probability.Unknown(var reason)
                        && reason == UnknownReason.SIMULATION_FAILED);
        if (failed) {
            state.PENDING.remove(key);
            state.FAILURES.put(key, "simulation_failed");
            revision++;
            return;
        }
        state.RESULTS.put(key, payload.table());
        mergeStructure(table, payload.table());
        while (state.RESULTS.size() > 64) {
            state.RESULTS.remove(state.RESULTS.keySet().iterator().next());
        }
        state.PENDING.remove(key);
        state.FAILURES.remove(key);
        revision++;
    }

    private static void mergeStructure(CatalogTableDto baseline, CatalogTableDto incoming) {
        Map<LootResultSignature, CatalogTableDto.ItemEntry> items = new LinkedHashMap<>();
        baseline.items().forEach(item -> items.put(item.signature(), item));
        for (var item : incoming.items()) {
            items.putIfAbsent(item.signature(), new CatalogTableDto.ItemEntry(
                    item.id(), item.displayName(), item.tooltipHint(), Probability.unknown(UnknownReason.NOT_SIMULATED),
                    item.signature(), item.acquisitionPaths(), item.injected(), List.of(), item.origins(),
                    null));
        }
        Set<ScenarioBranch> branches = new LinkedHashSet<>(baseline.branches());
        branches.addAll(incoming.branches());
        Set<String> identified = new HashSet<>();
        for (var branch : branches) {
            if (!branch.injectionSource().isEmpty() && !branch.injectionSource().equals("observed")) {
                identified.add(branch.kind() + "#" + branch.target());
            }
        }
        branches.removeIf(branch -> branch.injectionSource().equals("observed")
                && identified.contains(branch.kind() + "#" + branch.target()));
        state.TABLES.put(baseline.id(), new CatalogTableDto(baseline.id(), baseline.hash(), baseline.displayName(),
                baseline.type(), baseline.simulationCount(), baseline.childTables(), List.copyOf(items.values()),
                baseline.childProbabilities(), baseline.options(), List.copyOf(branches)));
    }

    /** 接收服务端按需模拟拒绝回执。 */
    public static void receiveRejection(ScenarioRequestRejectedPayload payload) {
        var table = state.TABLES.get(payload.tableId());
        if (table == null || table.options() == null || table.options().generation() != payload.generation()) {
            return;
        }
        String key = keyOf(payload.tableId(), payload.inputKey());
        if (!state.PENDING.containsKey(key)) {
            return;
        }
        state.PENDING.remove(key);
        state.FAILURES.put(key, payload.reason().name().toLowerCase(Locale.ROOT));
        while (state.FAILURES.size() > 64) {
            state.FAILURES.remove(state.FAILURES.keySet().iterator().next());
        }
        if (payload.reason() == ScenarioRequestRejectedPayload.Reason.STALE_HASH) {
            ClientNetwork.sendToServer(new RequestCatalogPayload());
        }
        revision++;
    }

    /** 读取精确命中 `(table, inputKey)` 的本地结果（未勾选条件且基准参数时回落到随目录同步的基准表）。 */
    public static @Nullable CatalogTableDto result(ResourceLocation id, String input) {
        var found = state.RESULTS.get(keyOf(id, input));
        if (found != null) {
            return found;
        }
        var base = state.TABLES.get(id);
        if (base != null && base.options() != null && !base.options().tools().isEmpty()
                && base.simulationCount() > 0) {
            String baseline = SimulationInputKey.of(List.of(),
                    ScenarioParams.baseline(base.options().tools().getFirst().id()));
            if (baseline.equals(input)) {
                return base;
            }
        }
        return null;
    }

    /** 按当前各表选择覆写展示目录，未计算的输入概率保持未知占位。 */
    public static Map<ResourceLocation, TableDefinition> overlay(Map<ResourceLocation, TableDefinition> base) {
        Map<ResourceLocation, TableDefinition> output = new LinkedHashMap<>(base);
        state.SELECTIONS.forEach((id, selection) -> {
            if (!base.containsKey(id)) {
                return;
            }
            String input = inputKey(selection);
            CatalogTableDto direct = result(id, input);
            if (direct != null) {
                output.put(id, direct.toTableDefinition());
            } else {
                var structure = state.TABLES.get(id);
                var table = structure == null ? base.get(id) : structure.toTableDefinition();
                String currentStatus = status(id, input);
                var unknown = new Probability.Unknown("failed".equals(currentStatus)
                        ? UnknownReason.SIMULATION_FAILED : UnknownReason.NOT_SIMULATED);
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

    /** 请求服务端读取玩家当前自身状态并自动填入可勾选条件与装备参数。 */
    public static void requestPlayerProbe(ResourceLocation table) {
        requestAssist(table, new SimulationAssistTarget(SimulationAssistTarget.Kind.PLAYER_STATE, ""));
    }

    /** 发起辅助推荐或状态读取请求。 */
    public static void requestAssist(ResourceLocation table, SimulationAssistTarget target) {
        var request = requestOf(table);
        var selection = selection(table);
        if (request == null || selection == null) {
            return;
        }
        state.assistId = ++sequence;
        state.assistTable = table;
        state.assistInput = keyOf(table, inputKey(selection));
        state.assistStarted = System.currentTimeMillis();
        state.assist = null;
        state.notes = List.of(text("assist_pending"));
        state.notesTable = table;
        ClientNetwork.sendToServer(new RequestSimulationAssistPayload(state.assistId, target, request));
        revision++;
    }

    /** 接收服务端辅助/状态读取答复。 */
    public static void receiveAssist(SyncSimulationAssistPayload payload) {
        var p = payload.selection();
        var current = requestOf(p.tableId());
        var selection = selection(p.tableId());
        if (payload.requestId() != state.assistId || current == null || selection == null
                || current.generation() != p.generation()
                || !current.tableHash().equals(p.tableHash())
                || !state.assistInput.equals(keyOf(p.tableId(), inputKey(selection)))) {
            return;
        }
        state.notes = payload.notes();
        state.notesTable = p.tableId();
        state.assist = payload;
        state.assistStarted = 0;
        if (!payload.recommendation() && payload.found()) {
            List<Component> savedNotes = payload.notes().isEmpty()
                    ? List.of(text("probe_done")) : payload.notes();
            apply(payload);
            state.notes = savedNotes;
            state.notesTable = p.tableId();
        }
        revision++;
    }

    public static boolean hasRecommendation(ResourceLocation table) {
        var current = requestOf(table);
        var selection = selection(table);
        return state.assist != null && state.assist.recommendation() && state.assist.found()
                && current != null && selection != null
                && state.assist.selection().tableId().equals(table)
                && state.assist.selection().generation() == current.generation()
                && state.assist.selection().tableHash().equals(current.tableHash())
                && state.assistInput.equals(keyOf(table, inputKey(selection)));
    }

    public static @Nullable RequestScenarioSimulationPayload recommendation(ResourceLocation table) {
        return hasRecommendation(table) ? state.assist.selection() : null;
    }

    public static boolean assistPending() {
        return state.assistStarted != 0;
    }

    public static long revision() {
        return revision;
    }

    /** 客户端 tick 驱动超时清理。 */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (state.PENDING.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= 120_000) {
                return false;
            }
            state.FAILURES.put(entry.getKey(), "timeout");
            return true;
        })) {
            revision++;
        }
        if (state.assistStarted != 0 && now - state.assistStarted > 15_000) {
            ResourceLocation table = state.assistTable;
            cancelAssist();
            state.notes = List.of(text("assist_timeout"));
            state.notesTable = table;
            revision++;
        }
    }

    public static void applyRecommendation(ResourceLocation table) {
        if (hasRecommendation(table)) {
            apply(state.assist);
        }
    }

    private static void apply(SyncSimulationAssistPayload payload) {
        var p = payload.selection();
        select(p.tableId(), p.enabledConditions(),
                new ScenarioParams(p.luck(), p.toolId(), p.toolEnchantments(), p.sampleCount()));
    }

    public static List<Component> notes() {
        return state.notes;
    }

    /** 返回属于指定表的最新提示消息。 */
    public static List<Component> notesFor(ResourceLocation table) {
        return table.equals(state.notesTable) ? state.notes : List.of();
    }

    /** 清除当前提示条。 */
    public static void clearNotes() {
        state.notes = List.of();
        state.notesTable = null;
        revision++;
    }

    public static void cancelAssist() {
        state.assistId = ++sequence;
        state.assist = null;
        state.assistStarted = 0;
        state.assistTable = null;
        state.assistInput = "";
        state.notes = List.of();
        state.notesTable = null;
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
