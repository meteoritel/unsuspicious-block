package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.injection.ArchaeologyLootInjectors;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultMatcher;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 战利品概率模拟引擎 —— 对目录中的每个战利品表执行模拟抽取，
 * 统计各条目在单次抽取中至少出现一次的概率，替换 ItemDefinition 中占位符 "?" 为格式化后的概率字符串。
 * 注意：LootTable 内部的 LegacyRandomSource 不是线程安全的，因此模拟采用顺序执行。
 */
public final class LootProbabilitySimulator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SIMULATION_COUNT = 10_000;

    // 供外部引用模拟次数
    public static int getSimulationCount() {
        return SIMULATION_COUNT;
    }

    private LootProbabilitySimulator() {
    }

    /**
     * 对单个战利品表执行模拟抽取，将 ItemDefinition 中的概率占位符 "?" 替换为真实概率。
     * hasConditions 的条目如果模拟零出现，概率设为 "?"；否则按实际出现次数计算。
     * <p>
     * 线程安全说明：本方法会触碰 {@code level} 关联的 LegacyRandomSource，
     * {@link net.minecraft.util.ThreadingDetector} 会检测跨线程访问，因此
     * <b>必须在主线程调用</b>。由 {@link LootProbabilitySimulationWorker#tick} 在
     * 服务端 tick 末尾分片驱动。
     *
     * @param tableId  战利品表 id
     * @param rawTable 原始表定义（概率字段为 "?" 占位符）
     * @param level    服务端级别，用于构建 LootParams
     * @return 模拟结果（包含 tableId 与填充了概率的 TableDefinition）
     */
    public static SimResult simulateOne(
            ResourceLocation tableId, TableDefinition rawTable, ServerLevel level) {
        try {
            // 使用数据包重载后注册表中的最终表，保留 Fabric LootTableEvents.MODIFY 等加载期注入。
            // 普通 getRandomItems 会在 NeoForge 端继续应用 GLM；不能改用 getRandomItemsRaw。
            LootTable lootTable = level.getServer().reloadableRegistries()
                    .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, tableId));
            // 注册表未提供有效表时保留占位结果，但标记为失败，禁止写入模拟缓存
            if (lootTable == LootTable.EMPTY) {
                LOGGER.warn("战利品表 {} 为空，跳过概率缓存", tableId);
                return SimResult.failure(tableId, rawTable);
            }
            List<SimulationScenario> scenarios = SimulationScenarioPlanner.plan(tableId, rawTable, level);
            return simulateTable(tableId, rawTable, lootTable, level, scenarios);
        } catch (Exception e) {
            LOGGER.warn("模拟战利品表 {} 时出错，保留原始占位符", tableId, e);
            return SimResult.failure(tableId, rawTable);
        }
    }

    private static SimResult simulateTable(
            ResourceLocation tableId, TableDefinition rawTable,
            LootTable lootTable, ServerLevel level, List<SimulationScenario> scenarios) {
        List<ItemDefinition> rawItems = rawTable.items();
        LootContextParamSet paramSet = lootTable.getParamSet();
        List<LootResultSignature> allRawCandidates = rawItems.stream()
                .map(ItemDefinition::signature).toList();
        Map<String, LootResultSignature> discovered = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> countsByScenario = new LinkedHashMap<>();
        Map<LootResultSignature, ItemStack> previewCache = new HashMap<>();
        java.util.function.Function<LootResultSignature, ItemStack> previewProvider =
                signature -> previewCache.computeIfAbsent(signature, LootResultSignature::createPreviewStack);
        RandomSource injectionRandom = RandomSource.create();
        Set<String> appearedThisRoll = new HashSet<>();

        for (SimulationScenario scenario : scenarios) {
            List<LootResultSignature> candidates = new ArrayList<>();
            Map<String, Integer> appearanceCounts = new LinkedHashMap<>();
            for (ItemDefinition item : rawItems) {
                String storedKey = item.signature().toStoredKey();
                if (scenario.applicableSignatures().contains(storedKey)) {
                    candidates.add(item.signature());
                    appearanceCounts.put(storedKey, 0);
                }
            }
            LootParams lootParams = LootContextParamFiller.createForSimulation(
                    level, paramSet, scenario.profile());
            try (LootSimulationScope.Scope ignored = LootSimulationScope.open(scenario.profile())) {
                for (int i = 0; i < SIMULATION_COUNT; i++) {
                    appearedThisRoll.clear();
                    List<ItemStack> drops = lootTable.getRandomItems(lootParams);
                    ArchaeologyLootInjectors.get().maybeReplace(tableId, drops, injectionRandom);
                    for (ItemStack stack : drops) {
                        if (stack.isEmpty()) {
                            continue;
                        }
                        LootResultSignature matched = LootResultMatcher.resolve(stack, candidates, previewProvider);
                        if (matched != null) {
                            appearedThisRoll.add(matched.toStoredKey());
                            continue;
                        }
                        if (LootResultMatcher.resolve(stack, allRawCandidates, previewProvider) != null) {
                            continue;
                        }
                        LootResultSignature derived = deriveSignature(stack);
                        String derivedKey = derived.toStoredKey();
                        if (appearanceCounts.containsKey(derivedKey)) {
                            continue;
                        }
                        discovered.putIfAbsent(derivedKey, derived);
                        candidates.add(derived);
                        appearanceCounts.put(derivedKey, 0);
                        appearedThisRoll.add(derivedKey);
                    }
                    for (String appearedKey : appearedThisRoll) {
                        appearanceCounts.merge(appearedKey, 1, Integer::sum);
                    }
                }
            }
            countsByScenario.put(scenario.key(), appearanceCounts);
        }

        List<ItemDefinition> simulatedItems = new ArrayList<>(rawItems.size() + discovered.size());
        for (ItemDefinition item : rawItems) {
            String storedKey = item.signature().toStoredKey();
            List<ScenarioProbability> scenarioProbabilities = new ArrayList<>();
            for (SimulationScenario scenario : scenarios) {
                if (!scenario.applicableSignatures().contains(storedKey)) {
                    continue;
                }
                int appearances = countsByScenario.getOrDefault(scenario.key(), Map.of())
                        .getOrDefault(storedKey, 0);
                scenarioProbabilities.add(new ScenarioProbability(scenario.key(),
                        formatProbability(appearances, item.hasConditions()), scenario.assumptions()));
            }
            String probability = summarize(scenarioProbabilities);
            simulatedItems.add(new ItemDefinition(
                    item.id(), item.displayName(), item.tooltipHint(),
                    probability, item.signature(), item.acquisitionPaths(), item.injected(), scenarioProbabilities));
        }

        for (Map.Entry<String, LootResultSignature> entry : discovered.entrySet()) {
            List<ScenarioProbability> scenarioProbabilities = new ArrayList<>();
            for (SimulationScenario scenario : scenarios) {
                Map<String, Integer> counts = countsByScenario.getOrDefault(scenario.key(), Map.of());
                if (!counts.containsKey(entry.getKey())) {
                    continue;
                }
                scenarioProbabilities.add(new ScenarioProbability(scenario.key(),
                        formatProbability(counts.get(entry.getKey()), false), scenario.assumptions()));
            }
            simulatedItems.add(LootTableCatalog.buildDiscoveredDefinition(entry.getValue(),
                    summarize(scenarioProbabilities), true, scenarioProbabilities));
        }

        return SimResult.success(tableId, new TableDefinition(tableId, rawTable.displayName(), rawTable.type(),
                simulatedItems, SIMULATION_COUNT, rawTable.childTables()));
    }

    private static String formatProbability(int appearances, boolean uncertainWhenAbsent) {
        if (appearances == 0) {
            return uncertainWhenAbsent ? "?" : "<0.01%";
        }
        return ProbabilityFormat.formatPercent((double) appearances / SIMULATION_COUNT);
    }

    private static String summarize(List<ScenarioProbability> probabilities) {
        if (probabilities.isEmpty()) {
            return "?";
        }
        String first = probabilities.getFirst().probability();
        return probabilities.stream().allMatch(value -> value.probability().equals(first)) ? first : "?";
    }

    // 从运行时掉落派生用于匹配/展示的签名；附魔物折叠为近似附魔签名，其余按普通物品签名（保守，避免签名爆炸）
    private static LootResultSignature deriveSignature(ItemStack stack) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        boolean enchanted = LootResultSignature.isActuallyEnchanted(stack);
        return enchanted ? LootResultSignature.enchantedApprox(itemId) : LootResultSignature.plain(itemId);
    }

    /** 模拟结果数据，只有 successful=true 的结果允许写入概率缓存。 */
    public record SimResult(ResourceLocation tableId, TableDefinition result, boolean successful) {
        // 创建可提交的成功结果
        private static SimResult success(ResourceLocation tableId, TableDefinition result) {
            return new SimResult(tableId, result, true);
        }

        // 创建仅用于保留原始占位数据的失败结果
        private static SimResult failure(ResourceLocation tableId, TableDefinition result) {
            return new SimResult(tableId, result, false);
        }
    }
}
