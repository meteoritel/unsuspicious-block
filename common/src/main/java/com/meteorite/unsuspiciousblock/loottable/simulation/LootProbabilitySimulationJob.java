package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulatedValue;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 可续跑的**单个输入**概率模拟任务。
 * <p>
 * 与旧实现的关键区别：一次任务只跑一个 {@link SimulationInput}（即一个条件赋值 + 一组参数旋钮），
 * 产出的是原始测量值 {@link SimulationMeasurement} 而不是填好展示概率的表定义。展示值由服务端
 * 按当前输入与静态结构派生，因此"从缓存恢复"与"刚算完"共用同一条派生路径。
 * <p>
 * 每个 {@link #advance(long)} 只执行有限批次，并保留计数与动态签名状态供下个 tick 继续。
 */
final class LootProbabilitySimulationJob {
    private static final int TIME_CHECK_BATCH_SIZE = 32;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ResourceLocation tableId;
    private final TableDefinition rawTable;
    private final LootTable lootTable;
    private final ServerLevel level;
    private final SimulationScenario scenario;
    private final SimulationInput input;
    /** 场景条件 + 输入参数的合成 profile——模拟真正使用的那一份。 */
    private final SimulationProfile effectiveProfile;
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

    private int completedRolls;
    private boolean scenarioPrepared;
    private Map<LootResultSignature, CandidateCounter> candidateCounters = Map.of();
    private Map<Item, CandidateIndex> candidatesByItem = Map.of();
    private Map<ResourceLocation, RollCounter> childCounters = Map.of();
    private LootParams lootParams;
    private SimulationMeasurement result;

    LootProbabilitySimulationJob(ResourceLocation tableId, TableDefinition rawTable,
                                 LootTable lootTable, ServerLevel level,
                                 SimulationScenario scenario, SimulationInput input) {
        this.tableId = tableId;
        this.rawTable = rawTable;
        this.lootTable = lootTable;
        this.level = level;
        this.scenario = scenario;
        this.input = input;
        // 条件赋值来自场景、参数旋钮来自输入：两者合成的 profile 才是这一份模拟的输入身份。
        // 注入场景与普通场景使用同一套输入参数。
        this.effectiveProfile = input.params().applyTo(
                scenario.profile(), level.registryAccess());
        this.allRawCandidatesByItem = indexCandidatesByItem(
                rawTable.items().stream().map(ItemDefinition::signature).toList());
        this.directChildTables = Set.copyOf(rawTable.childTables());
    }

    // 抽样次数取自输入身份（决策 38/39），不再是全局常量
    private int sampleCount() {
        return this.input.params().sampleCount();
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
        if (isComplete()) {
            return true;
        }
        boolean advanced = false;
        if (!this.scenarioPrepared) {
            long start = LootSimulationMetrics.now();
            prepareScenario();
            this.metrics.end(Stage.PREPARE, start);
            this.metrics.add(Count.SCENARIOS, 1);
        }
        try (LootSimulationScope.Scope scope = LootSimulationScope.open(
                this.effectiveProfile, this.directChildTables)) {
            try {
                while (this.completedRolls < sampleCount()) {
                    int batchEnd = Math.min(sampleCount(), this.completedRolls + TIME_CHECK_BATCH_SIZE);
                    while (this.completedRolls < batchEnd) {
                        simulateRoll();
                        this.completedRolls++;
                        advanced = true;
                    }
                    if (this.completedRolls < sampleCount()
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
        finishScenario();
        this.metrics.end(Stage.FINISH, start);
        start = LootSimulationMetrics.now();
        this.result = buildMeasurement();
        this.metrics.end(Stage.RESULT, start);
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

    SimulationMeasurement result() {
        if (this.result == null) {
            throw new IllegalStateException("模拟任务尚未完成: " + this.tableId);
        }
        return this.result;
    }

    private void prepareScenario() {
        this.candidateCounters = new LinkedHashMap<>();
        this.candidatesByItem = new HashMap<>();
        this.childCounters = new LinkedHashMap<>();
        for (ItemDefinition item : this.rawTable.items()) {
            String storedKey = storedKey(item.signature());
            if (this.scenario.applicableSignatures().contains(storedKey)) {
                addCandidate(item.signature(), storedKey);
            }
        }
        for (ResourceLocation childTable : this.rawTable.childTables()) {
            this.childCounters.put(childTable, new RollCounter());
        }
        this.lootParams = LootContextParamFiller.createForSimulation(
                this.level, this.lootTable.getParamSet(), this.effectiveProfile);
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
            java.util.List<LootResultSignature> signatures) {
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
        java.util.List<ItemStack> drops = this.lootTable.getRandomItems(this.lootParams);
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

    private void finishScenario() {
        Map<String, Integer> appearanceCounts = new LinkedHashMap<>();
        for (CandidateCounter counter : this.candidateCounters.values()) {
            appearanceCounts.put(counter.storedKey, counter.count());
        }
        Map<ResourceLocation, Integer> childAppearanceCounts = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, RollCounter> entry : this.childCounters.entrySet()) {
            childAppearanceCounts.put(entry.getKey(), entry.getValue().count());
        }
        this.countsByScenario.put(this.scenario.key(), appearanceCounts);
        this.childCountsByScenario.put(this.scenario.key(), childAppearanceCounts);
        this.completedRolls = 0;
        this.candidateCounters = Map.of();
        this.candidatesByItem = Map.of();
        this.childCounters = Map.of();
        this.lootParams = null;
        this.scenarioPrepared = false;
    }

    // 原始测量值：只记"多少签名测到了多少"，把展示派生留给服务端的统一入口。
    // 这样"刚算完"与"从缓存恢复"不可能是两套口径——这是 P1 消除重复实现的关键一步。
    private SimulationMeasurement buildMeasurement() {
        Map<String, Integer> counts = this.countsByScenario.getOrDefault(this.scenario.key(), Map.of());
        Map<String, SimulatedValue> itemProbabilities = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            itemProbabilities.put(entry.getKey(), formatProbability(entry.getValue()));
        }
        Map<ResourceLocation, SimulatedValue> childProbabilities = new LinkedHashMap<>();
        this.childCountsByScenario.getOrDefault(this.scenario.key(), Map.of())
                .forEach((childTable, appearances) ->
                        childProbabilities.put(childTable, formatProbability(appearances)));
        logUncoveredConditions();
        return new SimulationMeasurement(itemProbabilities, childProbabilities,
                this.discovered, this.discoveredDirectly, this.discoveredChildSources);
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

    // 抽样零出现只记为测量结果 0.0（展示为「未命中」）：条目在该输入下已被静态判定可达，
    // 剩下的零出现是真实的抽样事实，不再是"模拟可能没覆盖到"。0% 只留给静态可证明的不可达。
    private SimulatedValue formatProbability(int appearances) {
        if (appearances == 0) {
            return new SimulatedValue.Measured(0.0, java.util.OptionalDouble.empty());
        }
        return new SimulatedValue.Measured((double) appearances / sampleCount(),
                java.util.OptionalDouble.empty());
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
