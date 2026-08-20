package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.injection.ArchaeologyLootInjectors;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultMatcher;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    private final ResourceLocation tableId;
    private final TableDefinition rawTable;
    private final LootTable lootTable;
    private final ServerLevel level;
    private final List<SimulationScenario> scenarios;
    private final Map<Item, List<LootResultSignature>> allRawCandidatesByItem;
    private final Map<String, LootResultSignature> discovered = new LinkedHashMap<>();
    private final Map<String, ResourceLocation> discoveredChildSources = new LinkedHashMap<>();
    private final Set<String> discoveredDirectly = new HashSet<>();
    private final Map<String, Map<String, Integer>> countsByScenario = new LinkedHashMap<>();
    private final Map<String, Map<ResourceLocation, Integer>> childCountsByScenario = new LinkedHashMap<>();
    private final Map<LootResultSignature, ItemStack> previewCache = new HashMap<>();
    private final Map<LootResultSignature, String> storedKeyCache = new HashMap<>();
    private final Function<LootResultSignature, ItemStack> previewProvider =
            signature -> this.previewCache.computeIfAbsent(signature, LootResultSignature::createPreviewStack);
    private final RandomSource injectionRandom = RandomSource.create();
    private final Set<ResourceLocation> directChildTables;
    private final Consumer<ResourceLocation> childDropRecorder = this::recordChildTableAppearance;

    private int scenarioIndex;
    private int completedRolls;
    private boolean scenarioPrepared;
    private Map<LootResultSignature, CandidateCounter> candidateCounters = Map.of();
    private Map<Item, List<LootResultSignature>> candidatesByItem = Map.of();
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
        boolean advanced = false;
        while (!isComplete()) {
            if (!this.scenarioPrepared) {
                prepareScenario();
            }
            SimulationScenario scenario = this.scenarios.get(this.scenarioIndex);
            try (LootSimulationScope.Scope ignored = LootSimulationScope.open(
                    scenario.profile(), this.directChildTables)) {
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
            }
            finishScenario(scenario);
            if (this.scenarioIndex >= this.scenarios.size()) {
                this.result = buildResult();
                return true;
            }
            if (advanced && System.nanoTime() >= deadlineNanos) {
                return false;
            }
        }
        return true;
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
        this.candidatesByItem.computeIfAbsent(item, ignored -> new ArrayList<>()).add(signature);
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

    private static Map<Item, List<LootResultSignature>> indexCandidatesByItem(
            List<LootResultSignature> signatures) {
        Map<Item, List<LootResultSignature>> result = new HashMap<>();
        for (LootResultSignature signature : signatures) {
            Item item = BuiltInRegistries.ITEM.get(signature.itemId());
            List<LootResultSignature> candidates = result.computeIfAbsent(
                    item, ignored -> new ArrayList<>());
            if (!candidates.contains(signature)) {
                candidates.add(signature);
            }
        }
        result.replaceAll((item, candidates) -> List.copyOf(candidates));
        return Map.copyOf(result);
    }

    private void simulateRoll() {
        LootSimulationScope.beginRoll();
        List<ItemStack> drops = this.lootTable.getRandomItems(this.lootParams);
        ArchaeologyLootInjectors.get().maybeReplace(this.tableId, drops, this.injectionRandom);
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) {
                continue;
            }
            List<LootResultSignature> itemCandidates = this.candidatesByItem.get(stack.getItem());
            LootResultSignature matched = LootResultMatcher.resolve(
                    stack, itemCandidates != null ? itemCandidates : List.of(), this.previewProvider);
            if (matched != null) {
                this.candidateCounters.get(matched).mark(this.completedRolls);
                continue;
            }
            List<LootResultSignature> rawCandidates = this.allRawCandidatesByItem.get(stack.getItem());
            if (rawCandidates != null
                    && LootResultMatcher.resolve(stack, rawCandidates, this.previewProvider) != null) {
                continue;
            }
            LootResultSignature derived = deriveSignature(stack);
            String derivedKey = storedKey(derived);
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
        }
        LootSimulationScope.forEachChildTableWithDrops(this.childDropRecorder);
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

    private LootProbabilitySimulator.SimResult buildResult() {
        List<ItemDefinition> simulatedItems = new ArrayList<>(
                this.rawTable.items().size() + this.discovered.size());
        for (ItemDefinition item : this.rawTable.items()) {
            String storedKey = storedKey(item.signature());
            List<ScenarioProbability> probabilities = scenarioProbabilities(
                    storedKey, item.hasConditions());
            simulatedItems.add(new ItemDefinition(
                    item.id(), item.displayName(), item.tooltipHint(), summarize(probabilities),
                    item.signature(), item.acquisitionPaths(), item.injected(), probabilities));
        }
        for (Map.Entry<String, LootResultSignature> entry : this.discovered.entrySet()) {
            List<ScenarioProbability> probabilities = discoveredScenarioProbabilities(entry.getKey());
            ItemDefinition discoveredItem = LootTableCatalog.buildDiscoveredDefinition(
                    entry.getValue(), summarize(probabilities), true, probabilities);
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
            List<ScenarioProbability> probabilities = new ArrayList<>();
            for (SimulationScenario scenario : this.scenarios) {
                int appearances = this.childCountsByScenario.getOrDefault(scenario.key(), Map.of())
                        .getOrDefault(childTable, 0);
                probabilities.add(new ScenarioProbability(scenario.key(),
                        scenario.applicableChildTables().contains(childTable)
                                ? formatProbability(appearances, false) : "0",
                        scenario.assumptions()));
            }
            childProbabilities.add(new ChildTableProbability(
                    childTable, summarize(probabilities), probabilities));
        }
        TableDefinition table = new TableDefinition(
                this.tableId, this.rawTable.displayName(), this.rawTable.type(), simulatedItems,
                LootProbabilitySimulator.getSimulationCount(), this.rawTable.childTables(), childProbabilities);
        return LootProbabilitySimulator.SimResult.success(this.tableId, table);
    }

    private List<ScenarioProbability> scenarioProbabilities(String storedKey, boolean uncertainWhenAbsent) {
        List<ScenarioProbability> probabilities = new ArrayList<>();
        for (SimulationScenario scenario : this.scenarios) {
            if (!scenario.applicableSignatures().contains(storedKey)) {
                probabilities.add(new ScenarioProbability(
                        scenario.key(), "0", scenario.assumptions()));
                continue;
            }
            Map<String, Integer> counts = this.countsByScenario.getOrDefault(scenario.key(), Map.of());
            if (!counts.containsKey(storedKey)) {
                continue;
            }
            probabilities.add(new ScenarioProbability(scenario.key(),
                    formatProbability(counts.get(storedKey), uncertainWhenAbsent), scenario.assumptions()));
        }
        return List.copyOf(probabilities);
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
                    formatProbability(counts.get(storedKey), false), scenario.assumptions()));
        }
        return List.copyOf(probabilities);
    }

    private static String formatProbability(int appearances, boolean uncertainWhenAbsent) {
        if (appearances == 0) {
            return uncertainWhenAbsent ? "?" : "<0.01%";
        }
        return ProbabilityFormat.formatPercent((double) appearances
                / LootProbabilitySimulator.getSimulationCount());
    }

    private static String summarize(List<ScenarioProbability> probabilities) {
        if (probabilities.isEmpty()) {
            return "?";
        }
        String first = probabilities.getFirst().probability();
        if (probabilities.stream().allMatch(value -> value.probability().equals(first))) {
            return first;
        }
        if (probabilities.stream().anyMatch(value -> value.probability().equals("?"))) {
            return "?";
        }
        ScenarioProbability minimum = probabilities.stream()
                .min(java.util.Comparator.comparingDouble(
                        value -> ProbabilityFormat.parsePercentToFraction(value.probability())))
                .orElseThrow();
        ScenarioProbability maximum = probabilities.stream()
                .max(java.util.Comparator.comparingDouble(
                        value -> ProbabilityFormat.parsePercentToFraction(value.probability())))
                .orElseThrow();
        return minimum.probability() + "-" + maximum.probability();
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
