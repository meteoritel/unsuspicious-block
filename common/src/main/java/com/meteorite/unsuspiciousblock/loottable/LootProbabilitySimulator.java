package com.meteorite.unsuspiciousblock.loottable;

import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalCatalog;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 战利品概率模拟引擎 —— 对目录中的每个战利品表执行模拟抽取，
 * 统计各条目的出现概率，替换 ItemDefinition 中占位符 "?" 为格式化后的概率字符串。
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
     * 对目录中每个战利品表执行模拟抽取，将 ItemDefinition 中的概率占位符 "?" 替换为真实概率。
     * hasConditions 的条目如果模拟零出现，概率设为 "?"；否则按实际出现次数计算。
     *
     * @param rawCatalog 原始目录（概率字段为 "?" 占位符）
     * @param level      服务端级别，用于构建 LootParams
     * @return 新的目录 Map，概率字段已替换为模拟结果
     */
    public static Map<ResourceLocation, TableDefinition> simulate(
            Map<ResourceLocation, TableDefinition> rawCatalog, ServerLevel level) {
        Map<ResourceLocation, TableDefinition> result = new LinkedHashMap<>();
        LootParams.Builder paramsBuilder = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .withLuck(0.0F);

        for (Map.Entry<ResourceLocation, TableDefinition> entry : rawCatalog.entrySet()) {
            ResourceLocation tableId = entry.getKey();
            TableDefinition rawTable = entry.getValue();

            try {
                TableDefinition simulated = simulateTable(tableId, rawTable, level, paramsBuilder);
                result.put(tableId, simulated);
            } catch (Exception e) {
                LOGGER.warn("模拟战利品表 {} 时出错，保留原始占位符", tableId, e);
                result.put(tableId, rawTable);
            }
        }

        LOGGER.info("已完成 {} 个考古战利品表的概率模拟", result.size());
        return result;
    }

    private static TableDefinition simulateTable(
            ResourceLocation tableId, TableDefinition rawTable,
            ServerLevel level, LootParams.Builder paramsBuilder) {

        LootTable lootTable = level.getServer().reloadableRegistries()
                .getLootTable(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.LOOT_TABLE, tableId));

        // 空表或内置空表直接返回原始
        if (lootTable == LootTable.EMPTY) {
            return rawTable;
        }

        LootParams lootParams = paramsBuilder.create(LootContextParamSets.EMPTY);
        List<ItemDefinition> rawItems = rawTable.items();

        // 收集所有候选签名用于匹配
        List<LootResultSignature> candidates = rawItems.stream()
                .map(ItemDefinition::signature)
                .toList();

        // 统计每个签名的出现次数
        Map<String, Integer> appearanceCounts = new LinkedHashMap<>();
        for (ItemDefinition item : rawItems) {
            appearanceCounts.put(item.signature().toStoredKey(), 0);
        }

        // 模拟抽取
        for (int i = 0; i < SIMULATION_COUNT; i++) {
            List<ItemStack> drops = lootTable.getRandomItems(lootParams);
            for (ItemStack stack : drops) {
                if (stack.isEmpty()) continue;
                LootResultSignature matched = LootResultMatcher.resolve(stack, candidates);
                if (matched != null) {
                    String key = matched.toStoredKey();
                    appearanceCounts.merge(key, 1, Integer::sum);
                }
            }
        }

        // 构建新的 ItemDefinition 列表，替换概率字段
        List<ItemDefinition> simulatedItems = new ArrayList<>(rawItems.size());
        for (ItemDefinition item : rawItems) {
            String storedKey = item.signature().toStoredKey();
            int appearances = appearanceCounts.getOrDefault(storedKey, 0);

            String probability;
            if (appearances == 0) {
                if (ArchaeologyJournalCatalog.hasConditions(item)) {
                    // 有条件但零出现 → 条件可能未满足，概率不确定
                    probability = "?";
                } else {
                    // 无条件但零出现 → 极低概率
                    probability = "<0.01%";
                }
            } else {
                double fraction = (double) appearances / SIMULATION_COUNT;
                probability = ProbabilityFormat.formatPercent(fraction);
            }

            simulatedItems.add(new ItemDefinition(
                    item.id(), item.displayName(), item.tooltipHint(),
                    probability, item.signature()));
        }

        return new TableDefinition(tableId, rawTable.displayName(), simulatedItems, SIMULATION_COUNT);
    }
}