package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.injection.ArchaeologyLootInjectors;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultMatcher;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 战利品概率模拟引擎 —— 对目录中的每个战利品表执行模拟抽取，
 * 统计各条目的出现概率，替换 ItemDefinition 中占位符 "?" 为格式化后的概率字符串。
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
            LootTable lootTable = level.getServer().reloadableRegistries()
                    .getLootTable(net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.LOOT_TABLE, tableId));
            // 空表或内置空表直接返回原始
            if (lootTable == LootTable.EMPTY) {
                return new SimResult(tableId, rawTable);
            }
            // 按战利品表声明的 paramSet 动态构建 LootParams；
            // 若 required 参数无法全部满足，LootContextParamFiller 内部回退到宽松 paramSet
            LootContextParamSet paramSet = lootTable.getParamSet();
            LootParams lootParams = LootContextParamFiller.createForSimulation(level, paramSet);
            return simulateTable(tableId, rawTable, lootTable, lootParams);
        } catch (Exception e) {
            LOGGER.warn("模拟战利品表 {} 时出错，保留原始占位符", tableId, e);
            return new SimResult(tableId, rawTable);
        }
    }

    private static SimResult simulateTable(
            ResourceLocation tableId, TableDefinition rawTable,
            LootTable lootTable, LootParams lootParams) {
        List<ItemDefinition> rawItems = rawTable.items();

        // 候选签名（可变）：初始来自 JSON 解析，模拟期可追加 GLM/事件注入的新签名
        List<LootResultSignature> candidates = new ArrayList<>(rawItems.size());
        for (ItemDefinition item : rawItems) {
            candidates.add(item.signature());
        }

        // 统计每个签名的出现次数（含模拟期发现的注入签名）
        Map<String, Integer> appearanceCounts = new LinkedHashMap<>();
        for (ItemDefinition item : rawItems) {
            appearanceCounts.put(item.signature().toStoredKey(), 0);
        }

        // 模拟期发现的注入签名（JSON 中不存在，来自 GLM / LootTableEvents.MODIFY）
        Map<String, LootResultSignature> discovered = new LinkedHashMap<>();

        // 模拟抽取
        for (int i = 0; i < SIMULATION_COUNT; i++) {
            List<ItemStack> drops = lootTable.getRandomItems(lootParams);
            // Fabric 端注入器在模拟期显式调用，确保模组物品被纳入概率统计与签名派生；
            // NeoForge 端注入由 GLM 在 getRandomItems 内部完成，此处注入器为空实现
            ArchaeologyLootInjectors.get().maybeReplace(tableId, drops, RandomSource.create(i));
            for (ItemStack stack : drops) {
                if (stack.isEmpty()) continue;
                LootResultSignature matched = LootResultMatcher.resolve(stack, candidates);
                if (matched != null) {
                    appearanceCounts.merge(matched.toStoredKey(), 1, Integer::sum);
                    continue;
                }

                // 未匹配任何已知候选：派生签名。若已是已知签名则视为歧义掉落，保守跳过不计数；
                // 否则作为注入条目登记并计数。
                LootResultSignature derived = deriveSignature(stack);
                String derivedKey = derived.toStoredKey();
                if (appearanceCounts.containsKey(derivedKey)) {
                    continue;
                }
                discovered.put(derivedKey, derived);
                candidates.add(derived);
                appearanceCounts.put(derivedKey, 1);
            }
        }

        // 构建新的 ItemDefinition 列表，替换概率字段
        List<ItemDefinition> simulatedItems = new ArrayList<>(rawItems.size() + discovered.size());
        for (ItemDefinition item : rawItems) {
            String storedKey = item.signature().toStoredKey();
            int appearances = appearanceCounts.getOrDefault(storedKey, 0);

            String probability;
            if (appearances == 0) {
                if (item.hasConditions()) {
                    probability = "?";
                } else {
                    probability = "<0.01%";
                }
            } else {
                double fraction = (double) appearances / SIMULATION_COUNT;
                probability = ProbabilityFormat.formatPercent(fraction);
            }

            simulatedItems.add(new ItemDefinition(
                    item.id(), item.displayName(), item.tooltipHint(),
                    probability, item.signature(), item.sourceChildTable(), item.conditions()));
        }

        // 追加模拟期发现的注入条目
        for (Map.Entry<String, LootResultSignature> entry : discovered.entrySet()) {
            int appearances = appearanceCounts.getOrDefault(entry.getKey(), 0);
            String probability = appearances == 0
                    ? "<0.01%"
                    : ProbabilityFormat.formatPercent((double) appearances / SIMULATION_COUNT);
            simulatedItems.add(LootTableCatalog.buildDiscoveredDefinition(entry.getValue(), probability, true));
        }

        return new SimResult(tableId, new TableDefinition(tableId, rawTable.displayName(), rawTable.type(), simulatedItems, SIMULATION_COUNT));
    }

    // 从运行时掉落派生用于匹配/展示的签名；附魔物折叠为近似附魔签名，其余按普通物品签名（保守，避免签名爆炸）
    private static LootResultSignature deriveSignature(ItemStack stack) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        boolean enchanted = LootResultSignature.isActuallyEnchanted(stack);
        return enchanted ? LootResultSignature.enchantedApprox(itemId) : LootResultSignature.plain(itemId);
    }

    // 模拟结果数据（供 worker 在主线程提交时携带）
    public record SimResult(ResourceLocation tableId, TableDefinition result) {
    }
}