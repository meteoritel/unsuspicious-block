package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootTable;
import org.slf4j.Logger;

/**
 * 战利品概率模拟引擎 —— 对**单个模拟输入**执行模拟抽取，
 * 统计各条目在单次抽取中至少出现一次的比例，产出原始测量值。
 * <p>
 * 抽样次数不再是这里的全局常量：它随 {@link SimulationInput} 一起进来（决策 38/39），
 * 因此同一个参数组合可以有不同的精度，且两种精度共用同一份缓存结构。
 * <p>
 * 注意：{@code LootTable} 内部的 {@code LegacyRandomSource} 不是线程安全的，因此模拟采用顺序执行，
 * 并由 {@link LootProbabilitySimulationWorker} 在主线程 tick 末尾分片驱动。
 */
public final class LootProbabilitySimulator {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LootProbabilitySimulator() {
    }

    /**
     * 对单个输入执行完整模拟——仅供同步回退与命令路径使用；正常路径走 worker 分片。
     * <p>
     * 线程安全说明：本方法会触碰 {@code level} 关联的 LegacyRandomSource，
     * {@link net.minecraft.util.ThreadingDetector} 会检测跨线程访问，因此<b>必须在主线程调用</b>。
     */
    public static SimResult simulateOne(ResourceLocation tableId, TableDefinition rawTable,
                                        ServerLevel level, SimulationInput input,
                                        SimulationScenario scenario) {
        try {
            // 使用数据包重载后注册表中的最终表，保留 Fabric LootTableEvents.MODIFY 等加载期注入。
            // 普通 getRandomItems 会在 NeoForge 端继续应用 GLM；不能改用 getRandomItemsRaw。
            LootTable lootTable = resolveRuntimeTable(tableId, level);
            if (lootTable == LootTable.EMPTY) {
                LOGGER.warn("战利品表 {} 为空，跳过概率缓存", tableId);
                return SimResult.failure(tableId, input);
            }
            LootProbabilitySimulationJob job =
                    new LootProbabilitySimulationJob(tableId, rawTable, lootTable, level, scenario, input);
            do {
                // 预算为无限，因此每轮至少推进一个批次；循环条件只是防御性重试
                job.advance(Long.MAX_VALUE);
            } while (!job.isComplete());
            return new SimResult(tableId, input, job.result(), true);
        } catch (Exception e) {
            LOGGER.warn("模拟战利品表 {} 时出错，不写入缓存", tableId, e);
            return SimResult.failure(tableId, input);
        }
    }

    // 数据包重载后的运行时表；注册表未提供有效表时返回 LootTable.EMPTY
    private static LootTable resolveRuntimeTable(ResourceLocation tableId, ServerLevel level) {
        return level.getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, tableId));
    }

    static LootProbabilitySimulationJob createJob(ResourceLocation tableId, TableDefinition rawTable,
                                                  ServerLevel level, SimulationInput input,
                                                  SimulationScenario scenario) {
        LootTable lootTable = resolveRuntimeTable(tableId, level);
        if (lootTable == LootTable.EMPTY) {
            throw new IllegalStateException("战利品表为空: " + tableId);
        }
        return new LootProbabilitySimulationJob(tableId, rawTable, lootTable, level, scenario, input);
    }

    /**
     * 模拟结果：只有 {@code successful} 为真时才允许写入缓存。
     * <p>
     * 失败结果**保留输入**（而不是退回原始表定义）：worker 需要按
     * {@code (表, 输入)} 记录失败原因，也要能在 UI 上把这一条标成
     * {@code Unknown(SIMULATION_FAILED)} 而不是整表未知。
     */
    public record SimResult(ResourceLocation tableId, SimulationInput input,
                            SimulationMeasurement measurement, boolean successful) {
        static SimResult failure(ResourceLocation tableId, SimulationInput input) {
            return new SimResult(tableId, input, SimulationMeasurement.empty(), false);
        }
    }
}
