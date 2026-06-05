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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 战利品概率模拟引擎 —— 对目录中的每个战利品表执行模拟抽取，
 * 统计各条目的出现概率，替换 ItemDefinition 中占位符 "?" 为格式化后的概率字符串。
 * 使用并行线程池加速模拟过程。
 */
public final class LootProbabilitySimulator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SIMULATION_COUNT = 10_000;
    // 守护线程池，核心线程数为 CPU 核数 - 1（至少 1），避免阻塞服务器主线程
    private static final ExecutorService SIMULATION_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            r -> {
                Thread t = new Thread(r, "LootProbabilitySimulator");
                t.setDaemon(true);
                return t;
            }
    );

    // 供外部引用模拟次数
    public static int getSimulationCount() {
        return SIMULATION_COUNT;
    }

    // 关闭线程池（在服务端 catalog 失效时调用）
    public static void shutdown() {
        SIMULATION_EXECUTOR.shutdownNow();
    }

    private LootProbabilitySimulator() {
    }

    /**
     * 对目录中每个战利品表并行执行模拟抽取，将 ItemDefinition 中的概率占位符 "?" 替换为真实概率。
     * hasConditions 的条目如果模拟零出现，概率设为 "?"；否则按实际出现次数计算。
     *
     * @param rawCatalog 原始目录（概率字段为 "?" 占位符）
     * @param level      服务端级别，用于构建 LootParams
     * @return 新的目录 Map，概率字段已替换为模拟结果
     */
    public static Map<ResourceLocation, TableDefinition> simulate(
            Map<ResourceLocation, TableDefinition> rawCatalog, ServerLevel level) {
        LootParams.Builder paramsBuilder = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .withLuck(0.0F);

        // 在服务端线程上预收集 LootTable 引用和 LootParams
        List<SimTask> tasks = new ArrayList<>(rawCatalog.size());
        for (Map.Entry<ResourceLocation, TableDefinition> entry : rawCatalog.entrySet()) {
            ResourceLocation tableId = entry.getKey();
            TableDefinition rawTable = entry.getValue();

            try {
                LootTable lootTable = level.getServer().reloadableRegistries()
                        .getLootTable(net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.LOOT_TABLE, tableId));
                // 空表或内置空表直接跳过并行，保留原始
                if (lootTable == LootTable.EMPTY) {
                    tasks.add(new SimTask(tableId, rawTable, null, null));
                } else {
                    LootParams lootParams = paramsBuilder.create(LootContextParamSets.EMPTY);
                    tasks.add(new SimTask(tableId, rawTable, lootTable, lootParams));
                }
            } catch (Exception e) {
                LOGGER.warn("准备战利品表 {} 时出错，保留原始占位符", tableId, e);
                tasks.add(new SimTask(tableId, rawTable, null, null));
            }
        }

        // 并行模拟每个表
        List<CompletableFuture<SimResult>> futures = new ArrayList<>(tasks.size());
        for (SimTask task : tasks) {
            if (task.lootTable == null) {
                // 空表：直接返回原始结果
                futures.add(CompletableFuture.completedFuture(
                        new SimResult(task.tableId, task.rawTable)));
            } else {
                futures.add(CompletableFuture.supplyAsync(
                        () -> simulateTable(task.tableId, task.rawTable, task.lootTable, task.lootParams),
                        SIMULATION_EXECUTOR));
            }
        }

        // 等待所有模拟完成并收集结果
        Map<ResourceLocation, TableDefinition> result = new LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                SimResult sr = futures.get(i).join();
                result.put(sr.tableId, sr.result);
            } catch (Exception e) {
                SimTask task = tasks.get(i);
                LOGGER.warn("模拟战利品表 {} 时出错，保留原始占位符", task.tableId, e);
                result.put(task.tableId, task.rawTable);
            }
        }

        LOGGER.info("已完成 {} 个考古战利品表的概率模拟", result.size());
        return result;
    }

    private static SimResult simulateTable(
            ResourceLocation tableId, TableDefinition rawTable,
            LootTable lootTable, LootParams lootParams) {
        try {
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
                        probability, item.signature()));
            }

            return new SimResult(tableId, new TableDefinition(tableId, rawTable.displayName(), simulatedItems, SIMULATION_COUNT));
        } catch (Exception e) {
            LOGGER.warn("模拟战利品表 {} 时出错，保留原始占位符", tableId, e);
            return new SimResult(tableId, rawTable);
        }
    }

    // 并行模拟任务数据
    private record SimTask(ResourceLocation tableId, TableDefinition rawTable,
                           LootTable lootTable, LootParams lootParams) {
    }

    // 模拟结果数据
    private record SimResult(ResourceLocation tableId, TableDefinition result) {
    }
}