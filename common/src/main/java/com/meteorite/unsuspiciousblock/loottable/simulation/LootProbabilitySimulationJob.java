package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.diagnostics.LootSimulationMetrics;
import com.meteorite.unsuspiciousblock.loottable.diagnostics.LootSimulationMetrics.Count;
import com.meteorite.unsuspiciousblock.loottable.diagnostics.LootSimulationMetrics.Stage;
import com.meteorite.unsuspiciousblock.loottable.injection.ArchaeologyLootInjectors;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultMatcher;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultMatcher.CandidateIndex;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 可续跑的单表概率模拟任务。
 * 每次 {@link #advance(long)} 只执行有限批次，并保留场景、计数和动态签名状态供下个 tick 继续。
 */
final class LootProbabilitySimulationJob {
    private static final int TIME_CHECK_BATCH_SIZE = 32;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ResourceLocation tableId;
    private final TableDefinition rawTable;
    private final LootTable lootTable;
    private final ServerLevel level;
    private final List<SimulationScenario> scenarios;
    private final Map<Item, CandidateIndex> allRawCandidatesByItem;
    private final Map<String, LootResultSignature> discovered = new LinkedHashMap<>();
    private final Map<String, ResourceLocation> discoveredChildSources = new LinkedHashMap<>();
    private final Set<String> discoveredDirectly = new HashSet<>();
    private final Map<String, Map<String, Integer>> countsByScenario = new LinkedHashMap<>();
    private final Map<String, Map<ResourceLocation, Integer>> childCountsByScenario = new LinkedHashMap<>();
    /** 本表模拟中只按真实逻辑求值的场景控制类型条件（指纹未命中），用于完成时集中提示。 */
    private final Set<String> uncoveredConditions = new LinkedHashSet<>();
    private final Map<LootResultSignature, ItemStack> previewCache = new HashMap<>();
    private final Map<LootResultSignature, String> storedKeyCache = new HashMap<>();
    private final LootSimulationMetrics metrics = new LootSimulationMetrics();
    private final Function<LootResultSignature, ItemStack> previewFactory = this::createMeasuredPreview;
    private final Function<LootResultSignature, ItemStack> previewProvider =
            signature -> this.previewCache.computeIfAbsent(signature, this.previewFactory);
    private final RandomSource injectionRandom = RandomSource.create();
    private final Set<ResourceLocation> directChildTables;
    private final Consumer<ResourceLocation> childDropRecorder = this::recordChildTableAppearance;

    private int scenarioIndex;
    private int completedRolls;
    private boolean scenarioPrepared;
    private Map<LootResultSignature, CandidateCounter> candidateCounters = Map.of();
    private Map<Item, CandidateIndex> candidatesByItem = Map.of();
    private Map<ResourceLocation, RollCounter> childCounters = Map.of();
    private LootParams lootParams;
    private LootProbabilitySimulator.SimResult result;

    LootProbabilitySimulationJob(ResourceLocation tableId, TableDefinition rawTable,
                                 LootTable lootTable, ServerLevel level,
                                 List<SimulationScenario> scenarios) {
        this.tableId = tableId;
        this.rawTable = rawTable;
        this.lootTable = lootTable;
        this.level = level;
        this.scenarios = scenarios;
        this.allRawCandidatesByItem = indexCandidatesByItem(
                rawTable.items().stream().map(ItemDefinition::signature).toList());
        this.directChildTables = Set.copyOf(rawTable.childTables());
    }

    // 推进到时间预算耗尽或任务完成；至少执行一个固定批次，避免极短预算导致无进展。
    boolean advance(long deadlineNanos) {
        LootSimulationMetrics previous = this.metrics.attach();
        try {
            return advanceWithinSlice(deadlineNanos);
        } finally {
            LootSimulationMetrics.restore(previous);
        }
    }

    // 观测仅包围既有时间片，不调整截止时间、批次大小或抽取次数。
    private boolean advanceWithinSlice(long deadlineNanos) {
        boolean advanced = false;
        while (!isComplete()) {
            if (!this.scenarioPrepared) {
                long start = LootSimulationMetrics.now();
                prepareScenario();
                this.metrics.end(Stage.PREPARE, start);
                this.metrics.add(Count.SCENARIOS, 1);
            }
            SimulationScenario scenario = this.scenarios.get(this.scenarioIndex);
            try (LootSimulationScope.Scope scope = LootSimulationScope.open(
                    scenario.profile(), this.directChildTables)) {
                try {
                    while (this.completedRolls < LootProbabilitySimulator.getSimulationCount()) {
                        int batchEnd = Math.min(LootProbabilitySimulator.getSimulationCount(),
                                this.completedRolls + TIME_CHECK_BATCH_SIZE);
                        while (this.completedRolls < batchEnd) {
                            simulateRoll();
                            this.completedRolls++;
                            advanced = true;
                        }
                        if (this.completedRolls < LootProbabilitySimulator.getSimulationCount()
                                && advanced && System.nanoTime() >= deadlineNanos) {
                            return false;
                        }
                    }
                } finally {
                    // 作用域关闭前收集本片内未被场景覆盖的条件（提前返回同样需要收集）
                    this.uncoveredConditions.addAll(scope.uncoveredConditions());
                }
            }
            long start = LootSimulationMetrics.now();
            finishScenario(scenario);
            this.metrics.end(Stage.FINISH, start);
            if (this.scenarioIndex >= this.scenarios.size()) {
                start = LootSimulationMetrics.now();
                this.result = buildResult();
                this.metrics.end(Stage.RESULT, start);
                return true;
            }
            if (advanced && System.nanoTime() >= deadlineNanos) {
                return false;
            }
        }
        return true;
    }

    // 完成时输出本任务累计值，不包含结果发布、存档和广播的耗时。
    String metricsSummary() {
        return this.metrics.summary();
    }

    // 只对预览缓存未命中计时，作为 MATCH / RAW_MATCH 等父段的嵌套明细。
    private ItemStack createMeasuredPreview(LootResultSignature signature) {
        long start = LootSimulationMetrics.now();
        try {
            return signature.createPreviewStack();
        } finally {
            this.metrics.add(Count.PREVIEW_BUILDS, 1);
            this.metrics.end(Stage.PREVIEW_DETAIL, start);
        }
    }

    boolean isComplete() {
        return this.result != null;
    }

    LootProbabilitySimulator.SimResult result() {
        if (this.result == null) {
            throw new IllegalStateException("模拟任务尚未完成: " + this.tableId);
        }
        return this.result;
    }

    private void prepareScenario() {
        SimulationScenario scenario = this.scenarios.get(this.scenarioIndex);
        this.candidateCounters = new LinkedHashMap<>();
        this.candidatesByItem = new HashMap<>();
        this.childCounters = new LinkedHashMap<>();
        for (ItemDefinition item : this.rawTable.items()) {
            String storedKey = storedKey(item.signature());
            if (scenario.applicableSignatures().contains(storedKey)) {
                addCandidate(item.signature(), storedKey);
            }
        }
        for (ResourceLocation childTable : this.rawTable.childTables()) {
            this.childCounters.put(childTable, new RollCounter());
        }
        this.lootParams = LootContextParamFiller.createForSimulation(
                this.level, this.lootTable.getParamSet(), scenario.profile());
        this.scenarioPrepared = true;
    }

    private CandidateCounter addCandidate(LootResultSignature signature, String storedKey) {
        CandidateCounter existing = this.candidateCounters.get(signature);
        if (existing != null) {
            return existing;
        }
        CandidateCounter counter = new CandidateCounter(storedKey);
        this.candidateCounters.put(signature, counter);
        Item item = BuiltInRegistries.ITEM.get(signature.itemId());
        this.candidatesByItem.computeIfAbsent(item, ignored -> new CandidateIndex())
                .add(signature, this.previewProvider);
        this.metrics.add(Count.CANDIDATES_ADDED, 1);
        return counter;
    }

    private String storedKey(LootResultSignature signature) {
        return this.storedKeyCache.computeIfAbsent(signature, LootResultSignature::toStoredKey);
    }

    private void recordChildTableAppearance(ResourceLocation childTable) {
        RollCounter counter = this.childCounters.get(childTable);
        if (counter != null) {
            counter.mark(this.completedRolls);
        }
    }

    // 原始候选与场景候选使用同一匹配语义；原始索引供不适用路径的回退判断。
    private Map<Item, CandidateIndex> indexCandidatesByItem(
            List<LootResultSignature> signatures) {
        Map<Item, CandidateIndex> result = new HashMap<>();
        for (LootResultSignature signature : new LinkedHashSet<>(signatures)) {
            Item item = BuiltInRegistries.ITEM.get(signature.itemId());
            result.computeIfAbsent(item, ignored -> new CandidateIndex()).add(signature, this.previewProvider);
        }
        return Map.copyOf(result);
    }

    private void simulateRoll() {
        long start = LootSimulationMetrics.now();
        LootSimulationScope.beginRoll();
        this.metrics.end(Stage.BEGIN_ROLL, start);
        start = LootSimulationMetrics.now();
        List<ItemStack> drops = this.lootTable.getRandomItems(this.lootParams);
        this.metrics.end(Stage.GENERATE, start);
        this.metrics.add(Count.ROLLS, 1);
        start = LootSimulationMetrics.now();
        ArchaeologyLootInjectors.get().maybeReplace(this.tableId, drops, this.injectionRandom);
        this.metrics.end(Stage.INJECT, start);
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) {
                continue;
            }
            this.metrics.add(Count.DROPS, 1);
            start = LootSimulationMetrics.now();
            CandidateIndex itemCandidates = this.candidatesByItem.get(stack.getItem());
            LootResultSignature matched = itemCandidates != null
                    ? LootResultMatcher.resolve(stack, itemCandidates, this.previewProvider) : null;
            this.metrics.end(Stage.MATCH, start);
            if (matched != null) {
                start = LootSimulationMetrics.now();
                this.candidateCounters.get(matched).mark(this.completedRolls);
                this.metrics.end(Stage.RECORD, start);
                this.metrics.add(Count.MATCHED, 1);
                continue;
            }
            start = LootSimulationMetrics.now();
            CandidateIndex rawCandidates = this.allRawCandidatesByItem.get(stack.getItem());
            boolean rawMatched = rawCandidates != null
                    && LootResultMatcher.resolve(stack, rawCandidates, this.previewProvider) != null;
            this.metrics.end(Stage.RAW_MATCH, start);
            if (rawMatched) {
                this.metrics.add(Count.RAW_SKIPPED, 1);
                continue;
            }
            start = LootSimulationMetrics.now();
            LootResultSignature derived = deriveSignature(stack);
            this.metrics.end(Stage.DERIVE, start);
            this.metrics.add(Count.DERIVED, 1);
            start = LootSimulationMetrics.now();
            String derivedKey = storedKey(derived);
            this.metrics.end(Stage.KEY_LOOKUP, start);
            start = LootSimulationMetrics.now();
            ResourceLocation childSource = LootSimulationScope.sourceChildTable(stack);
            CandidateCounter counter = this.candidateCounters.get(derived);
            if (counter == null) {
                this.discovered.putIfAbsent(derivedKey, derived);
                counter = addCandidate(derived, derivedKey);
            }
            if (childSource == null) {
                this.discoveredDirectly.add(derivedKey);
            } else {
                this.discoveredChildSources.putIfAbsent(derivedKey, childSource);
            }
            counter.mark(this.completedRolls);
            this.metrics.end(Stage.RECORD, start);
        }
        start = LootSimulationMetrics.now();
        LootSimulationScope.forEachChildTableWithDrops(this.childDropRecorder);
        this.metrics.end(Stage.CHILD_RECORD, start);
    }

    private void finishScenario(SimulationScenario scenario) {
        Map<String, Integer> appearanceCounts = new LinkedHashMap<>();
        for (CandidateCounter counter : this.candidateCounters.values()) {
            appearanceCounts.put(counter.storedKey, counter.count());
        }
        Map<ResourceLocation, Integer> childAppearanceCounts = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, RollCounter> entry : this.childCounters.entrySet()) {
            childAppearanceCounts.put(entry.getKey(), entry.getValue().count());
        }
        this.countsByScenario.put(scenario.key(), appearanceCounts);
        this.childCountsByScenario.put(scenario.key(), childAppearanceCounts);
        this.scenarioIndex++;
        this.completedRolls = 0;
        this.candidateCounters = Map.of();
        this.candidatesByItem = Map.of();
        this.childCounters = Map.of();
        this.lootParams = null;
        this.scenarioPrepared = false;
    }

    // 基准场景 key——条件全部不成立的那个场景（决策 2）。网格上的数字只来自它。
    // 找不到显式标记时退回第一个场景：宁可展示一个有据可查的场景，也不留空。
    private String baselineScenarioKey() {
        for (SimulationScenario scenario : this.scenarios) {
            if (scenario.baseline()) {
                return scenario.key();
            }
        }
        return this.scenarios.isEmpty() ? "" : this.scenarios.getFirst().key();
    }

    private LootProbabilitySimulator.SimResult buildResult() {
        String baselineKey = baselineScenarioKey();
        List<ItemDefinition> simulatedItems = new ArrayList<>(
                this.rawTable.items().size() + this.discovered.size());
        for (ItemDefinition item : this.rawTable.items()) {
            List<ScenarioProbability> probabilities = scenarioProbabilities(item);
            Probability display = PathHintAnalyzer.deriveDisplay(
                    baselineMeasurement(baselineKey, probabilities), item.acquisitionPaths());
            simulatedItems.add(new ItemDefinition(
                    item.id(), item.displayName(), item.tooltipHint(), display,
                    item.signature(), item.acquisitionPaths(), item.injected(), probabilities));
        }
        for (Map.Entry<String, LootResultSignature> entry : this.discovered.entrySet()) {
            List<ScenarioProbability> probabilities = discoveredScenarioProbabilities(entry.getKey());
            // 动态条目没有可供静态判定的获取路径，因此"需要条件"无从陈述；基准下没观测到就是未覆盖
            Probability display = baselineMeasurement(baselineKey, probabilities);
            ItemDefinition discoveredItem = LootTableCatalog.buildDiscoveredDefinition(
                    entry.getValue(), display, true, probabilities,
                    this.previewProvider.apply(entry.getValue()).copy());
            ResourceLocation childSource = this.discoveredDirectly.contains(entry.getKey())
                    ? null : this.discoveredChildSources.get(entry.getKey());
            if (childSource == null) {
                simulatedItems.add(discoveredItem);
            } else {
                simulatedItems.add(new ItemDefinition(
                        discoveredItem.id(), discoveredItem.displayName(), discoveredItem.tooltipHint(),
                        discoveredItem.probability(), discoveredItem.signature(),
                        List.of(new LootAcquisitionPath(childSource, List.of(), List.of())),
                        true, discoveredItem.scenarioProbabilities()));
            }
        }

        List<ChildTableProbability> childProbabilities = new ArrayList<>();
        for (ResourceLocation childTable : this.rawTable.childTables()) {
            List<ScenarioProbability> probabilities = childScenarioProbabilities(childTable);
            childProbabilities.add(new ChildTableProbability(childTable,
                    baselineMeasurement(baselineKey, probabilities), probabilities));
        }
        TableDefinition table = new TableDefinition(
                this.tableId, this.rawTable.displayName(), this.rawTable.type(), simulatedItems,
                LootProbabilitySimulator.getSimulationCount(), this.rawTable.childTables(), childProbabilities);
        logUncoveredConditions();
        return LootProbabilitySimulator.SimResult.success(this.tableId, table);
    }

    // 基准场景下的测量值；基准场景未覆盖该签名时说明"该输入下没有可用路径"，
    // 由 PathHintAnalyzer 决定它该显示为「需要条件」还是「未覆盖」。
    private static Probability baselineMeasurement(String baselineKey,
                                                    List<ScenarioProbability> probabilities) {
        for (ScenarioProbability scenario : probabilities) {
            if (scenario.scenarioKey().equals(baselineKey)) {
                return scenario.probability();
            }
        }
        return Probability.uncovered();
    }

    // 未命中场景覆盖的场景控制类型条件只能按真实逻辑求值，数值可能偏离场景估算，完成时汇总提示一次
    private void logUncoveredConditions() {
        if (this.uncoveredConditions.isEmpty()) {
            return;
        }
        LOGGER.warn("战利品表 {} 有 {} 个场景控制类型条件未被代表场景覆盖，已按真实逻辑求值，"
                        + "结果可能偏离场景估算（常见成因：条件指纹不可用，或条件来自运行时注入的路径）：{}",
                this.tableId, this.uncoveredConditions.size(), String.join(", ", this.uncoveredConditions));
    }

    private List<ScenarioProbability> scenarioProbabilities(ItemDefinition item) {
        String storedKey = storedKey(item.signature());
        // 代表场景数量受 MAX_SCENARIOS 限制。被截断的条件组合不会有任何场景覆盖它，此时条目在
        // 每个场景里都是"不适用"，逐个写 "0" 会把"未覆盖"显示成"不可达"；返回空场景列表让汇总
        // 落到 "?"（未知）。
        if (!isApplicableInAnyScenario(storedKey)) {
            return List.of();
        }
        List<PathHint> hints = PathHintAnalyzer.hintsFor(item.acquisitionPaths());
        List<ScenarioProbability> probabilities = new ArrayList<>();
        for (SimulationScenario scenario : this.scenarios) {
            if (!scenario.applicableSignatures().contains(storedKey)) {
                // D2 的拆分：某条路径在该场景下不可用**不是**"静态不可达"，它只是在这个场景的
                // 布尔赋值下不成立。展示为「需要条件」并逐条列出引用到的条件，而不是 0%。
                probabilities.add(new ScenarioProbability(scenario.key(),
                        PathHintAnalyzer.inapplicableScenarioDisplay(hints),
                        scenario.assumptions()));
                continue;
            }
            Map<String, Integer> counts = this.countsByScenario.getOrDefault(scenario.key(), Map.of());
            if (!counts.containsKey(storedKey)) {
                continue;
            }
            probabilities.add(new ScenarioProbability(scenario.key(),
                    formatProbability(counts.get(storedKey)), scenario.assumptions()));
        }
        return List.copyOf(probabilities);
    }

    // 子表入口的分场景概率；不可用时与物品同一口径记为「需要条件」，不用 0% 冒充"不可达"
    private List<ScenarioProbability> childScenarioProbabilities(ResourceLocation childTable) {
        if (!isChildApplicableInAnyScenario(childTable)) {
            return List.of();
        }
        List<LootConditionInfo> childConditions = conditionsOfChildTable(childTable);
        boolean hasHints = !childConditions.isEmpty();
        List<ScenarioProbability> probabilities = new ArrayList<>();
        for (SimulationScenario scenario : this.scenarios) {
            int appearances = this.childCountsByScenario.getOrDefault(scenario.key(), Map.of())
                    .getOrDefault(childTable, 0);
            if (!scenario.applicableChildTables().contains(childTable)) {
                probabilities.add(new ScenarioProbability(scenario.key(),
                        PathHintAnalyzer.inapplicableScenarioDisplay(hasHints
                                ? List.of(new PathHint.ReferencesScenario(childConditions))
                                : List.of()),
                        scenario.assumptions()));
                continue;
            }
            probabilities.add(new ScenarioProbability(scenario.key(),
                    formatProbability(appearances), scenario.assumptions()));
        }
        return List.copyOf(probabilities);
    }

    // 该子表入口在父表里出现过的条件；用于"这个场景下为什么拿不到"的静态陈述
    private List<LootConditionInfo> conditionsOfChildTable(ResourceLocation childTable) {
        LinkedHashMap<String, LootConditionInfo> conditions = new LinkedHashMap<>();
        for (ItemDefinition item : this.rawTable.items()) {
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                if (!childTable.equals(path.sourceChildTable())) {
                    continue;
                }
                for (LootConditionInfo condition : path.allConditions()) {
                    conditions.putIfAbsent(condition.conditionType() + "|" + condition.description().getString(),
                            condition);
                }
            }
        }
        return List.copyOf(conditions.values());
    }

    // 判断签名是否至少在一个代表场景中被静态判定可达；全为否说明场景集未覆盖该条目
    private boolean isApplicableInAnyScenario(String storedKey) {
        for (SimulationScenario scenario : this.scenarios) {
            if (scenario.applicableSignatures().contains(storedKey)) {
                return true;
            }
        }
        return false;
    }

    // 判断子表是否至少在一个代表场景中被静态判定可达
    private boolean isChildApplicableInAnyScenario(ResourceLocation childTable) {
        for (SimulationScenario scenario : this.scenarios) {
            if (scenario.applicableChildTables().contains(childTable)) {
                return true;
            }
        }
        return false;
    }

    // 动态条目没有可供静态判定的获取路径，只展示实际观测到该签名的代表场景。
    private List<ScenarioProbability> discoveredScenarioProbabilities(String storedKey) {
        List<ScenarioProbability> probabilities = new ArrayList<>();
        for (SimulationScenario scenario : this.scenarios) {
            Map<String, Integer> counts = this.countsByScenario.getOrDefault(scenario.key(), Map.of());
            if (!counts.containsKey(storedKey)) {
                continue;
            }
            probabilities.add(new ScenarioProbability(scenario.key(),
                    formatProbability(counts.get(storedKey)), scenario.assumptions()));
        }
        return List.copyOf(probabilities);
    }

    // 抽样零出现只记为测量结果 0.0（展示为「未命中」）：条目在该场景下已被静态判定可达，
    // 剩下的零出现是真实的抽样事实，不再是"模拟可能没覆盖到"。0% 只留给静态可证明的不可达。
    private static Probability formatProbability(int appearances) {
        if (appearances == 0) {
            return Probability.measured(0.0);
        }
        return Probability.measured((double) appearances
                / LootProbabilitySimulator.getSimulationCount());
    }

    // 附魔结果继续折叠，其他动态结果保留组件，避免药水等物品在缓存和同步后丢失变体。
    private static LootResultSignature deriveSignature(ItemStack stack) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return LootResultSignature.isActuallyEnchanted(stack)
                ? LootResultSignature.enchantedApprox(itemId)
                : LootResultSignature.componentExact(stack);
    }

    /** 单个场景内按轮次去重的原始计数器。 */
    private static class RollCounter {
        private int count;
        private int lastRoll = -1;

        // 同一轮无论出现多少次都只累计一次。
        final void mark(int roll) {
            if (this.lastRoll != roll) {
                this.lastRoll = roll;
                this.count++;
            }
        }

        final int count() {
            return this.count;
        }
    }

    /** 绑定稳定存储键的物品候选计数器。 */
    private static final class CandidateCounter extends RollCounter {
        private final String storedKey;

        private CandidateCounter(String storedKey) {
            this.storedKey = storedKey;
        }
    }
}
