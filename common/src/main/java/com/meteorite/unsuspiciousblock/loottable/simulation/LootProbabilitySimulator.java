package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootTable;
import org.slf4j.Logger;

import java.util.List;

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
            LootProbabilitySimulationJob job = createJob(tableId, rawTable, level, lootTable);
            while (!job.advance(Long.MAX_VALUE)) {
                // 同步回退路径有意运行到完成。
            }
            return job.result();
        } catch (Exception e) {
            LOGGER.warn("模拟战利品表 {} 时出错，保留原始占位符", tableId, e);
            return SimResult.failure(tableId, rawTable);
        }
    }

    static LootProbabilitySimulationJob createJob(ResourceLocation tableId, TableDefinition rawTable,
                                                   ServerLevel level) {
        LootTable lootTable = level.getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, tableId));
        if (lootTable == LootTable.EMPTY) {
            throw new IllegalStateException("战利品表为空: " + tableId);
        }
        return createJob(tableId, rawTable, level, lootTable);
    }

    private static LootProbabilitySimulationJob createJob(
            ResourceLocation tableId, TableDefinition rawTable,
            ServerLevel level, LootTable lootTable) {
        List<SimulationScenario> scenarios = SimulationScenarioPlanner.plan(tableId, rawTable, level);
        return new LootProbabilitySimulationJob(tableId, rawTable, lootTable, level, scenarios);
    }

    /** 模拟结果数据，只有 successful=true 的结果允许写入概率缓存。 */
    public record SimResult(ResourceLocation tableId, TableDefinition result, boolean successful) {
        // 创建可提交的成功结果
        static SimResult success(ResourceLocation tableId, TableDefinition result) {
            return new SimResult(tableId, result, true);
        }

        // 创建仅用于保留原始占位数据的失败结果
        static SimResult failure(ResourceLocation tableId, TableDefinition result) {
            return new SimResult(tableId, result, false);
        }
    }
}
