package com.meteorite.unsuspiciousblock.client.state;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Recommendation.Witness;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranch;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.RecommendationSolver.Outcome;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInputKey;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestScenarioCachePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.ScenarioSelection;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestScenarioSimulationPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestSimulationAssistPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.ScenarioRequestRejectedPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioCachePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncSimulationAssistPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;

/**
 * 当前条件勾选、计算状态和结果按输入隔离；晚到答复只入缓存，不改玩家的新选择。
 */
public final class ScenarioSession {
    /**
     * 一次只读缓存查询的版本凭据与当前参数。
     */
    private record CacheQuery(long id, String input, ScenarioParams params, long started,
                              boolean cached, boolean complete, String error) {
    }

    private record Pending(long id, ScenarioSelection selection, long started) {
    }

    private State state = new State();

    private static final class State {
        private final Map<ResourceLocation, CatalogTableDto> TABLES = new LinkedHashMap<>();
        private final Map<ResourceLocation, SimulationPreferenceStore.Selection> SELECTIONS = new HashMap<>();
        private final Map<String, CatalogTableDto> RESULTS = new LinkedHashMap<>();
        private final Map<String, Pending> PENDING = new HashMap<>();
        private final Map<String, String> FAILURES = new LinkedHashMap<>();
        private final Map<ResourceLocation, CacheQuery> CACHE_QUERIES = new LinkedHashMap<>();
        private long assistId;
        @Nullable
        private SimulationAssistTarget assistTarget;
        @Nullable
        private Draft assistDraft;
        private String assistInput = "";
        @Nullable
        private ResourceLocation assistTable;
        @Nullable
        private SyncSimulationAssistPayload assist;
        private long assistStarted;
        private List<Component> notes = List.of();
        @Nullable
        private ResourceLocation notesTable;
    }

    private long projectionRevision;
    private final Map<ResourceLocation, Long> presentationRevisions = new HashMap<>();
    private final Map<ResourceLocation, TableDefinition> projections = new HashMap<>();
    private final Map<ResourceLocation, Map<String, ScenarioTableSnapshot.Target>> targets = new HashMap<>();
    private final Map<ResourceLocation, ScenarioTableSnapshot> snapshots = new HashMap<>();

    private void presentationChanged(ResourceLocation table) {
        if (table != null) presentationRevisions.merge(table, 1L, Long::sum);
    }

    private void projectionChanged(ResourceLocation table) {
        projections.remove(table);
        projectionRevision++;
        presentationChanged(table);
    }

    /**
     * 目录消费者只观察选择或当前测值变化，提示状态拥有独立的单表版本。
     */
    public long projectionRevision() {
        return projectionRevision;
    }

    /**
     * 接收页面装配处按玩家进度派生的目标展示，供网格和场景页共用。
     */
    public void setTargets(ResourceLocation table, Map<String, ScenarioTableSnapshot.Target> value) {
        Map<String, ScenarioTableSnapshot.Target> frozen = Map.copyOf(value);
        if (!frozen.equals(targets.put(table, frozen))) presentationChanged(table);
    }

    /**
     * 读取当前已提交选择的单表快照，未变化时复用对象身份。
     */
    public @Nullable ScenarioTableSnapshot snapshot(ResourceLocation table) {
        var choice = selection(table);
        if (choice == null) return null;
        return snapshot(table, choice);
    }

    /**
     * 为独立草稿派生预览，不替换已提交选择的快照。
     */
    public @Nullable ScenarioTableSnapshot snapshot(ResourceLocation table, SimulationPreferenceStore.Selection choice) {
        var structure = table(table);
        if (structure == null) return null;
        String input = inputKey(choice);
        String status = status(table, input);
        long version = presentationRevisions.getOrDefault(table, 0L);
        var old = snapshots.get(table);
        if (old != null && old.structure() == structure && old.choice().equals(choice)
                && old.revision() == version && old.status().equals(status)) return old;
        var snapshot = new ScenarioTableSnapshot(structure, choice, result(table, input), input, status,
                failure(table, input), notesFor(table), table.equals(state.assistTable) && assistPending(),
                recommendation(table), targets.getOrDefault(table, Map.of()), version);
        if (choice.equals(selection(table))) snapshots.put(table, snapshot);
        return snapshot;
    }

    private static long sequence;

    private final Consumer<CustomPacketPayload> send;
    private final LongSupplier clock;
    private final Runnable refreshCatalog;
    private final long identity = ++sequence;
    private boolean active = true;
    private final Map<ResourceLocation, Long> selectionRevisions = new HashMap<>();

    /**
     * 连接负责提供发送入口与毫秒时钟，退役时停止提交动作。
     */
    public ScenarioSession(Consumer<CustomPacketPayload> send, LongSupplier clock, Runnable refreshCatalog) {
        this.send = send;
        this.clock = clock;
        this.refreshCatalog = refreshCatalog;
    }

    /**
     * 草稿携带建立时的内容与选择身份，参数浮层切换时保留同一基础版本。
     */
    public record Draft(long session, ResourceLocation table, long generation, String hash,
                        long selectionRevision, SimulationInput input) {
        /**
         * 编辑输入时沿用草稿创建时的内容与选择版本。
         */
        public Draft withInput(SimulationInput value) {
            return new Draft(session, table, generation, hash, selectionRevision, value);
        }
    }

    /**
     * 本地提交结果；网络拒绝属于已提交选择的请求状态。
     */
    public record CommitResult(boolean applied, @Nullable Component error) {
    }

    /**
     * 捕获当前会话、目录和选择版本，并绑定完整模拟输入。
     */
    public Draft draft(ResourceLocation table, List<String> conditions, ScenarioParams params) {
        var dto = table(table);
        return new Draft(identity, table, dto == null || dto.options() == null ? 0 : dto.options().generation(),
                dto == null ? "" : dto.hash(), selectionRevisions.getOrDefault(table, 0L),
                new SimulationInput(conditions, params));
    }

    private CommitResult commit(Draft draft) {
        var dto = table(draft.table());
        if (!active || draft.session() != identity) return new CommitResult(false, text("draft.connection_changed"));
        if (dto == null || dto.options() == null) return new CommitResult(false, text("draft.unavailable"));
        if (dto.options().generation() != draft.generation() || !dto.hash().equals(draft.hash()))
            return new CommitResult(false, text("draft.content_changed"));
        if (selectionRevisions.getOrDefault(draft.table(), 0L) != draft.selectionRevision())
            return new CommitResult(false, text("draft.selection_changed"));
        if (dto.options().rejects(draft.input().enabledConditions(), draft.input().params()))
            return new CommitResult(false, text("failure.rejected_input"));
        var choice = new SimulationPreferenceStore.Selection(draft.input().enabledConditions(), draft.input().params());
        if (!choice.equals(state.SELECTIONS.get(draft.table()))) {
            state.SELECTIONS.put(draft.table(), choice);
            selectionRevisions.merge(draft.table(), 1L, Long::sum);
            SimulationPreferenceStore.put(draft.table(), choice);
            cancelAssist();
            projectionChanged(draft.table());
        }
        return new CommitResult(true, null);
    }

    /**
     * 校验并保存草稿，成功后查询对应输入缓存。
     */
    public CommitResult applyDraft(Draft draft) {
        CommitResult result = commit(draft);
        if (result.applied()) queryCache(draft.table(), true);
        return result;
    }

    /**
     * 校验并保存草稿，成功后只计算这份已提交输入。
     */
    public CommitResult applyDraftAndSimulate(Draft draft) {
        CommitResult result = commit(draft);
        if (result.applied()) request(draft.table(), true);
        return result;
    }

    /**
     * 退役会话仍可供关闭中的 UI 读取，其动作不再提交。
     */
    public void retire() {
        active = false;
        clear();
    }


    public static String keyOf(ResourceLocation table, String input) {
        return table + "#" + input;
    }

    public @Nullable CatalogTableDto table(ResourceLocation id) {
        return state.TABLES.get(id);
    }

    public @Nullable SimulationPreferenceStore.Selection selection(ResourceLocation id) {
        return state.SELECTIONS.get(id);
    }

    public static String inputKey(SimulationPreferenceStore.Selection selection) {
        return SimulationInputKey.of(selection.conditions(), selection.params());
    }

    /**
     * 接收完整目录并初始化或校验各表的持久化选择。
     */
    public void catalog(List<CatalogTableDto> tables) {
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
                choice = restoreSelection(table);
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
        state.PENDING.values().removeIf(pending -> !ids.contains(pending.selection().tableId()));
        projections.clear();
        snapshots.clear();
        projectionRevision++;
        ids.forEach(this::presentationChanged);
        targets.keySet().retainAll(ids);
    }

    private static @Nullable SimulationPreferenceStore.Selection restoreSelection(CatalogTableDto table) {
        var saved = SimulationPreferenceStore.get(table.id());
        if (saved == null || saved.params().luck() == ScenarioParams.DEFAULT_LUCK || !isLuckAffected(table)) {
            return saved;
        }
        ScenarioParams params = saved.params();
        return new SimulationPreferenceStore.Selection(saved.conditions(),
                new ScenarioParams(ScenarioParams.DEFAULT_LUCK, params.toolId(), params.toolEnchantments(), params.sampleCount()));
    }

    private static boolean isLuckAffected(CatalogTableDto table) {
        return table.items().stream()
                .flatMap(item -> item.acquisitionPaths().stream())
                .anyMatch(LootAcquisitionPath::luckAffected);
    }

    /**
     * 基于当前版本更新选择并查询缓存；独立草稿通过 {@link #applyDraft(Draft)} 获取提交结果。
     */
    public void select(ResourceLocation table, List<String> conditions, ScenarioParams params) {
        applyDraft(draft(table, conditions, params));
    }

    /**
     * 切换单条环境条件的勾选状态。
     */
    public void toggleCondition(ResourceLocation table, String conditionId) {
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

    /**
     * 向服务端查询当前选择的缓存状态。
     */
    public void queryCache(ResourceLocation table, boolean force) {
        if (!active) return;
        var request = requestOf(table);
        var selected = selection(table);
        if (request == null || selected == null) {
            return;
        }
        String input = inputKey(selected);
        CacheQuery old = state.CACHE_QUERIES.get(table);
        long now = clock.getAsLong();
        if (!force && old != null && old.input().equals(input) && now - old.started() < 15_000) {
            return;
        }
        state.CACHE_QUERIES.put(table, new CacheQuery(++sequence, input, selected.params(), now, false, false, ""));
        while (state.CACHE_QUERIES.size() > 64) {
            state.CACHE_QUERIES.remove(state.CACHE_QUERIES.keySet().iterator().next());
        }
        send.accept(new RequestScenarioCachePayload(sequence,
                force || result(table, input) == null, request));
        presentationChanged(table);
    }

    /**
     * 若当前选择尚未查询缓存（或上次因忙退避），补发只读缓存查询。
     */
    public void ensureCacheQuery(ResourceLocation table) {
        var choice = selection(table);
        var query = state.CACHE_QUERIES.get(table);
        if (choice != null && (query == null || !query.input().equals(inputKey(choice)))) {
            queryCache(table, false);
        } else if (query != null && query.error().equals("busy") && clock.getAsLong() - query.started() >= 250) {
            queryCache(table, true);
        }
    }

    /**
     * 接收服务端缓存可用性答复。
     */
    public void receiveCache(SyncScenarioCachePayload payload) {
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
            refreshCatalog.run();
        }
        presentationChanged(payload.tableId());
    }

    /**
     * 构造与当前选择对应的共享请求选择值。
     */
    public @Nullable ScenarioSelection requestOf(ResourceLocation table) {
        var dto = state.TABLES.get(table);
        var selection = state.SELECTIONS.get(table);
        if (dto == null || dto.options() == null || selection == null) {
            return null;
        }
        var p = selection.params();
        return new ScenarioSelection(dto.options().generation(), table, dto.hash(),
                selection.conditions(), p.luck(), p.toolId(), p.toolEnchantments(), p.sampleCount());
    }

    /**
     * 为正在查看的表取回基准结果，或自动计算首次恢复的受幸运影响的输入。
     */
    public void requestVisibleDefault(ResourceLocation table) {
        var base = state.TABLES.get(table);
        var choice = selection(table);
        if (base == null || base.options() == null || choice == null) {
            return;
        }

        if (!isBaselineInput(base, inputKey(choice))) {
            if (!selectionRevisions.containsKey(table) && choice.params().luck() == ScenarioParams.DEFAULT_LUCK
                    && isLuckAffected(base)) {
                request(table, false);
            }
            return;
        }

        if (base.simulationCount() == 0
                && (base.items().stream().anyMatch(item -> isWaitingForSimulation(item.probability()))
                || base.childProbabilities().stream().anyMatch(child -> isWaitingForSimulation(child.probability())))) {
            request(table, false);
        }
    }

    private static boolean isWaitingForSimulation(Probability probability) {
        return probability instanceof Probability.Unknown(var reason) && reason == UnknownReason.NOT_SIMULATED;
    }

    /**
     * 触发一次按需模拟请求。
     */
    public void request(ResourceLocation table, boolean manual) {
        if (!active) return;
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
        long requestId = ++sequence;
        state.PENDING.put(key, new Pending(requestId, request, clock.getAsLong()));
        state.FAILURES.remove(key);
        send.accept(new RequestScenarioSimulationPayload(requestId, request));
        presentationChanged(table);
    }

    /**
     * 返回指定表和输入键的当前状态（{@code cached / pending / failed / querying / retrieving / query_failed / uncomputed}）。
     */
    public String status(ResourceLocation table, String input) {
        String key = keyOf(table, input);
        if (result(table, input) != null) {
            return "cached";
        }
        Pending start = state.PENDING.get(key);
        if (start != null) {
            return clock.getAsLong() - start.started() <= 120_000 ? "pending" : "failed";
        }
        if (state.FAILURES.containsKey(key)) {
            return "failed";
        }
        CacheQuery query = state.CACHE_QUERIES.get(table);
        if (query != null && query.input().equals(input)) {
            if (!query.complete()) {
                return clock.getAsLong() - query.started() < 15_000 ? "querying" : "query_failed";
            }
            if (!query.error().isEmpty()) {
                return "query_failed";
            }
            if (query.cached()) {
                return clock.getAsLong() - query.started() > 15_000 ? "query_failed" : "retrieving";
            }
        }
        return "uncomputed";
    }

    public String failure(ResourceLocation table, String input) {
        return state.FAILURES.getOrDefault(keyOf(table, input), "timeout");
    }

    /**
     * 接收服务端按需模拟结果并写入本地有限缓存。
     */
    public void receive(SyncScenarioResultPayload payload) {
        var table = state.TABLES.get(payload.table().id());
        if (table == null || table.options() == null || table.options().generation() != payload.generation()
                || !table.hash().equals(payload.tableHash())) {
            return;
        }
        String key = keyOf(table.id(), payload.inputKey());
        Pending pending = state.PENDING.get(key);
        boolean completes = payload.simulation() && pending != null && pending.id() == payload.requestId();
        boolean failed = payload.table().items().stream().anyMatch(item ->
                item.probability() instanceof Probability.Unknown(var reason)
                        && reason == UnknownReason.SIMULATION_FAILED);
        if (failed) {
            if (completes) {
                state.PENDING.remove(key);
                state.FAILURES.put(key, "simulation_failed");
            }
            presentationChanged(table.id());
            return;
        }
        state.RESULTS.put(key, payload.table());
        boolean structureChanged = mergeStructure(table, payload.table());
        while (state.RESULTS.size() > 64) {
            String evicted = state.RESULTS.keySet().iterator().next();
            state.RESULTS.remove(evicted);
            ResourceLocation evictedTable = ResourceLocation.parse(evicted.substring(0, evicted.indexOf('#')));
            var evictedChoice = selection(evictedTable);
            if (evictedChoice != null && evicted.equals(keyOf(evictedTable, inputKey(evictedChoice))))
                projectionChanged(evictedTable);
        }
        if (completes) {
            state.PENDING.remove(key);
            state.FAILURES.remove(key);
        }
        var selected = selection(table.id());
        if (structureChanged || selected != null && inputKey(selected).equals(payload.inputKey()))
            projectionChanged(table.id());
        else presentationChanged(table.id());
    }

    private boolean mergeStructure(CatalogTableDto baseline, CatalogTableDto incoming) {
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
        if (items.size() == baseline.items().size() && branches.equals(new LinkedHashSet<>(baseline.branches())))
            return false;
        state.TABLES.put(baseline.id(), new CatalogTableDto(baseline.id(), baseline.hash(), baseline.displayName(),
                baseline.type(), baseline.simulationCount(), baseline.childTables(), List.copyOf(items.values()),
                baseline.childProbabilities(), baseline.options(), List.copyOf(branches)));
        return true;
    }

    /**
     * 接收服务端按需模拟拒绝回执。
     */
    public void receiveRejection(ScenarioRequestRejectedPayload payload) {
        var entry = state.PENDING.entrySet().stream().filter(candidate -> {
            Pending pending = candidate.getValue();
            return pending.id() == payload.requestId() && pending.selection().tableId().equals(payload.tableId())
                    && pending.selection().generation() == payload.generation()
                    && pending.selection().tableHash().equals(payload.tableHash());
        }).findFirst().orElse(null);
        if (entry == null) return;
        String key = entry.getKey();
        state.PENDING.remove(key);
        state.FAILURES.put(key, payload.reason().name().toLowerCase(Locale.ROOT));
        while (state.FAILURES.size() > 64) {
            state.FAILURES.remove(state.FAILURES.keySet().iterator().next());
        }
        if (payload.reason() == ScenarioRequestRejectedPayload.Reason.STALE_HASH) {
            refreshCatalog.run();
        }
        presentationChanged(payload.tableId());
    }

    /**
     * 读取精确命中 `(table, inputKey)` 的本地结果（未勾选条件且基准参数时回落到随目录同步的基准表）。
     */
    public @Nullable CatalogTableDto result(ResourceLocation id, String input) {
        var found = state.RESULTS.get(keyOf(id, input));
        if (found != null) {
            return found;
        }
        var base = state.TABLES.get(id);
        return base != null && base.simulationCount() > 0 && isBaselineInput(base, input) ? base : null;
    }

    private static boolean isBaselineInput(CatalogTableDto table, String input) {
        var options = table.options();
        return options != null && !options.tools().isEmpty() && input.equals(SimulationInputKey.of(List.of(),
                ScenarioParams.baseline(options.tools().getFirst().id())));
    }

    /**
     * 按当前各表选择覆写展示目录，未计算的输入概率保持未知占位。
     */
    public Map<ResourceLocation, TableDefinition> overlay(Map<ResourceLocation, TableDefinition> base) {
        Map<ResourceLocation, TableDefinition> output = new LinkedHashMap<>(base);
        state.SELECTIONS.forEach((id, selection) -> {
            if (!base.containsKey(id)) {
                return;
            }
            TableDefinition reused = projections.get(id);
            if (reused != null) {
                output.put(id, reused);
                return;
            }
            String input = inputKey(selection);
            CatalogTableDto direct = result(id, input);
            if (direct != null) {
                output.put(id, direct.toTableDefinition());
            } else {
                var structure = state.TABLES.get(id);
                var table = structure == null ? base.get(id) : structure.toTableDefinition();
                output.put(id, structure != null && isBaselineInput(structure, input)
                        ? table : table.withUnknownProbabilities(UnknownReason.NOT_SIMULATED));
            }
            projections.put(id, output.get(id));
        });
        return output;
    }

    /**
     * 请求服务端读取玩家当前自身状态并自动填入可勾选条件与装备参数。
     */
    public void requestPlayerProbe(ResourceLocation table) {
        requestAssist(table, new SimulationAssistTarget(SimulationAssistTarget.Kind.PLAYER_STATE, ""));
    }

    /**
     * 发起辅助推荐或状态读取请求。
     */
    public void requestAssist(ResourceLocation table, SimulationAssistTarget target) {
        if (!active) return;
        var request = requestOf(table);
        var selection = selection(table);
        if (request == null || selection == null) {
            return;
        }
        state.assistId = ++sequence;
        state.assistTarget = target;
        state.assistDraft = draft(table, selection.conditions(), selection.params());
        state.assistTable = table;
        state.assistInput = keyOf(table, inputKey(selection));
        state.assistStarted = clock.getAsLong();
        state.assist = null;
        state.notes = List.of(text("assist_pending"));
        state.notesTable = table;
        send.accept(new RequestSimulationAssistPayload(state.assistId, target, request));
        presentationChanged(table);
    }

    /**
     * 接收服务端辅助/状态读取答复。
     */
    public void receiveAssist(SyncSimulationAssistPayload payload) {
        var p = payload.selection();
        var current = requestOf(p.tableId());
        var selection = selection(p.tableId());
        if (!active || payload.requestId() != state.assistId || !payload.target().equals(state.assistTarget)
                || current == null || selection == null
                || current.generation() != p.generation()
                || !current.tableHash().equals(p.tableHash())
                || !state.assistInput.equals(keyOf(p.tableId(), inputKey(selection)))) {
            return;
        }
        if (payload.found() && !validAssist(payload)) {
            cancelAssist();
            state.notes = List.of(text("assist_invalid"));
            state.notesTable = p.tableId();
            presentationChanged(p.tableId());
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
        presentationChanged(p.tableId());
    }

    private boolean validAssist(SyncSimulationAssistPayload payload) {
        var p = payload.selection();
        var dto = table(p.tableId());
        ScenarioParams params = new ScenarioParams(p.luck(), p.toolId(), p.toolEnchantments(), p.sampleCount());
        if (dto == null || dto.options() == null || dto.options().rejects(p.enabledConditions(), params)) return false;
        if (!payload.recommendation()) return payload.witness() == null;
        Witness witness = payload.witness();
        if (witness == null || witness.pathId().isBlank()
                || payload.outcome() != Outcome.FOUND) return false;
        ScenarioBranch branch = dto.branches().stream().filter(value -> value.reference().equals(witness.branchReference())).findFirst().orElse(null);
        if (branch == null) return false;
        return switch (payload.target().kind()) {
            case ITEM -> payload.target().value().equals(witness.itemSignature());
            case CHILD_TABLE ->
                    witness.targetKind().equals("table") && payload.target().value().equals(witness.target());
            case BRANCH -> branch.recommendable() && payload.target().value().equals(witness.branchReference())
                    && branch.kind().equals(witness.targetKind()) && branch.target().equals(witness.target());
            case PLAYER_STATE -> false;
        };
    }

    /**
     * 返回已验证的同代路径归属，供预览显示实际目标与路径。
     */
    public @Nullable Witness recommendationWitness(ResourceLocation table) {
        return hasRecommendation(table) ? Objects.requireNonNull(state.assist).witness() : null;
    }

    public boolean hasRecommendation(ResourceLocation table) {
        var current = requestOf(table);
        var selection = selection(table);
        return state.assist != null && state.assist.recommendation() && state.assist.found()
                && current != null && selection != null
                && Objects.requireNonNull(state.assist).selection().tableId().equals(table)
                && Objects.requireNonNull(state.assist).selection().generation() == current.generation()
                && Objects.requireNonNull(state.assist).selection().tableHash().equals(current.tableHash())
                && state.assistInput.equals(keyOf(table, inputKey(selection)));
    }

    public @Nullable ScenarioSelection recommendation(ResourceLocation table) {
        return hasRecommendation(table) ? Objects.requireNonNull(state.assist).selection() : null;
    }

    public boolean assistPending() {
        return state.assistStarted != 0;
    }

    /**
     * 客户端 tick 驱动超时清理。
     */
    public void tick() {
        long now = clock.getAsLong();
        state.PENDING.entrySet().removeIf(entry -> {
            if (now - entry.getValue().started() <= 120_000) {
                return false;
            }
            state.FAILURES.put(entry.getKey(), "timeout");
            presentationChanged(entry.getValue().selection().tableId());
            return true;
        });
        if (state.assistStarted != 0 && now - state.assistStarted > 15_000) {
            ResourceLocation table = state.assistTable;
            cancelAssist();
            state.notes = List.of(text("assist_timeout"));
            state.notesTable = table;
            presentationChanged(table);
        }
    }

    /**
     * 基于推荐请求时捕获的草稿版本应用见证输入。
     */
    public CommitResult applyRecommendation(ResourceLocation table) {
        if (!hasRecommendation(table)) return new CommitResult(false, text("assist_stale"));
        var p = Objects.requireNonNull(state.assist).selection();
        return applyDraft(Objects.requireNonNull(state.assistDraft).withInput(new SimulationInput(p.enabledConditions(),
                new ScenarioParams(p.luck(), p.toolId(), p.toolEnchantments(), p.sampleCount()))));
    }

    private void apply(SyncSimulationAssistPayload payload) {
        var p = payload.selection();
        select(p.tableId(), p.enabledConditions(),
                new ScenarioParams(p.luck(), p.toolId(), p.toolEnchantments(), p.sampleCount()));
    }

    public List<Component> notes() {
        return state.notes;
    }

    /**
     * 返回属于指定表的最新提示消息。
     */
    public List<Component> notesFor(ResourceLocation table) {
        return table.equals(state.notesTable) ? state.notes : List.of();
    }

    /**
     * 清除当前提示条。
     */
    public void clearNotes() {
        ResourceLocation table = state.notesTable;
        state.notes = List.of();
        state.notesTable = null;
        presentationChanged(table);
    }

    public void cancelAssist() {
        ResourceLocation table = state.notesTable;
        state.assistId = ++sequence;
        state.assist = null;
        state.assistTarget = null;
        state.assistDraft = null;
        state.assistStarted = 0;
        state.assistTable = null;
        state.assistInput = "";
        state.notes = List.of();
        state.notesTable = null;
        presentationChanged(table);
    }

    public static Component text(String key, Object... args) {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.simulation." + key, args);
    }

    public void clear() {
        state = new State();
        state.assistId = ++sequence;
        projections.clear();
        snapshots.clear();
        targets.clear();
        presentationRevisions.clear();
        selectionRevisions.clear();
        projectionRevision++;
    }
}
