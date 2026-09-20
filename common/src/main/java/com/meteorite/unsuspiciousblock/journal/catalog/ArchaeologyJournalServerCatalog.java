package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.analysis.CompiledLootTable;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootMechanismSupport;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogQueryIndex;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulatedValue;
import com.meteorite.unsuspiciousblock.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultPreviewCache;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulator;
import com.meteorite.unsuspiciousblock.loottable.simulation.PathHintAnalyzer;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationScenario;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationScenarioPlanner;
import com.meteorite.unsuspiciousblock.loottable.source.LootTableSourceSnapshot;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.world.LootProbabilityData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端目录——解析所有考古战利品表，按需通过主线程 tick 工作器填充概率。
 * <p>
 * 状态模型：整轮重载的静态部分（快照 / 引用图 / 编译产物 / 静态投影 / 每表哈希 / 分类结构）
 * 全部在局部对象上构建完成，再通过单个 volatile 引用（{@link #currentGeneration}）
 * <b>原子发布</b>，读取方只会看到上一代的完整状态或新一代的完整静态部分。模拟结果作为该代的
 * overlay 随进度增长；提交时校验 generation，旧代结果不会写入新代。
 * <p>
 * 生命周期：
 * <ul>
 *   <li>{@link #ensureLoaded(MinecraftServer)}：非阻塞，构建并发布新一代目录 + 从 SavedData 恢复已缓存表 +
 *       将未缓存表入队分 tick 模拟。构建失败时保留上一代完整状态；无上一代可用时进入明确的空状态。</li>
 *   <li>{@link #commitSimulatedTable(LootProbabilitySimulator.SimResult, MinecraftServer)}：由工作器在主线程调用，
 *       将单表模拟结果写入当代 overlay 与 SavedData；整批任务结束后统一广播哈希。</li>
 *   <li>{@link #invalidate()}：释放当代目录（含资源快照与投影），下次 ensureLoaded 重新构建。</li>
 * </ul>
 * <p>
 * 线程安全：读取走当前 {@link CatalogGeneration}——静态部分不可变、模拟 overlay 使用
 * ConcurrentHashMap，读路径无锁；写路径仅在主线程发生（ensureLoaded / commitSimulatedTable）。
 */
public final class ArchaeologyJournalServerCatalog {
    private static final String CHILD_CACHE_PREFIX = "child_table:";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SIMULATION_CACHE_VERSION = "loot-analysis-v16";

    /** 唯一发布点：整代目录状态一次成型后整体替换。 */
    private static volatile CatalogGeneration currentGeneration;
    /** 代次计数器；仅在主线程递增。 */
    private static long generationCounter;
    private static volatile boolean loaded;

    private ArchaeologyJournalServerCatalog() {
    }

    /**
     * 非阻塞加载：构建新一代目录 → 从 SavedData 恢复缓存 → 未缓存表入队后台模拟。
     * 调用后目录立即可用（仅含缓存表），未缓存表的概率为 "?" 占位符，后台模拟完成后渐进填充。
     */
    public static void ensureLoaded(MinecraftServer server) {
        if (loaded) return;

        List<ResourceLocation> uncached = List.of();
        try {
            BuildResult buildResult = buildGeneration(server);
            currentGeneration = buildResult.generation();
            uncached = buildResult.uncachedTables();
        } catch (Exception e) {
            LOGGER.error("加载考古战利品表目录失败", e);
            // 构建失败时进入明确的空状态而不是把半成品留在引用上；
            // invalidate 与失败路径都会让 loaded=false 与 currentGeneration=null 成对出现，
            // 因此这里不存在"上一代可保留"的情形。
            currentGeneration = CatalogGeneration.empty(++generationCounter);
        }
        loaded = true;

        CatalogGeneration generation = currentGeneration;
        if (generation != null && !uncached.isEmpty()) {
            enqueueSimulation(server, generation, uncached);
        }
    }

    /**
     * 在局部对象上构建整代目录状态；全部成功后才由调用方发布。
     * 失败时抛出，由 {@link #ensureLoaded} 决定保留上一代还是进入空状态。
     */
    private static BuildResult buildGeneration(MinecraftServer server) {
        long generation = ++generationCounter;

        // 1. 捕获本轮资源快照（全表有效原文 + 完整资源栈），编译与哈希共用同一次读盘
        LootTableSourceSnapshot sourceSnapshot = LootTableSourceSnapshot.capture(server.getResourceManager());

        // 2. 建图 → 编译 → 投影 → 组装静态读模型
        ArchaeologyJournalCatalog.LoadResult loadResult = ArchaeologyJournalCatalog.load(
                generation, sourceSnapshot, server.getResourceManager(), server.registryAccess());
        Map<ResourceLocation, TableDefinition> staticTables = loadResult.staticTables();
        LOGGER.info("解析到 {} 个考古战利品表原始目录", staticTables.size());

        // 3. 先判不可用机制（决策 31）：这类表不能入队模拟，否则会在 getRandomItems 里抛异常后
        //    静默失败，玩家只看到满屏问号。判定结果同时决定它们的条目展示为「规则未解析」。
        Map<ResourceLocation, String> unavailableReasons = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, TableDefinition> entry : staticTables.entrySet()) {
            String diagnosis = LootMechanismSupport.diagnoseUnavailable(
                    entry.getValue().type(),
                    loadResult.session().sourceSnapshot().effectiveJson(entry.getKey()));
            if (diagnosis != null) {
                unavailableReasons.put(entry.getKey(), diagnosis);
            }
        }
        if (!unavailableReasons.isEmpty()) {
            Map<ResourceLocation, TableDefinition> marked = new LinkedHashMap<>(staticTables);
            unavailableReasons.keySet().forEach(tableId ->
                    marked.put(tableId, markUnparsed(marked.get(tableId))));
            staticTables = Map.copyOf(marked);
            LOGGER.warn("{} 张战利品表无法被本模组模拟，已按不可用上报（不做猜测）：{}",
                    unavailableReasons.size(), unavailableReasons);
        }

        // 4. 计算哈希（吃子树内每张表的资源栈摘要、编译产物摘要与被引用附魔定义摘要）
        Map<ResourceLocation, String> tableHashes = computeTableHashes(
                loadResult.session().referenceGraph(), staticTables,
                loadResult.session().compiledTables(), server.registryAccess());
        CatalogGeneration catalogGeneration = new CatalogGeneration(
                loadResult.session(), loadResult.structure(), staticTables, tableHashes);

        // 5. 从 SavedData 恢复已缓存表，写入当代 overlay；不可用的表既不入队也不读缓存
        ServerLevel level = server.overworld();
        LootProbabilityData probabilityData = LootProbabilityData.get(level);
        List<ResourceLocation> uncached = new ArrayList<>();
        int restored = 0;
        for (Map.Entry<ResourceLocation, TableDefinition> entry : staticTables.entrySet()) {
            ResourceLocation tableId = entry.getKey();
            if (unavailableReasons.containsKey(tableId)) {
                // 不可用表也要发布：否则它在客户端**整表消失**，玩家连"这张表读不了"都看不到。
                // 发布的是已标记为 UNPARSED 的版本——条目概率为「规则未解析」，tooltip 直接说明原因，
                // 因此这里发布的是"结论"，不是"测量值"；它也不会再被入队或从缓存恢复。
                catalogGeneration.publishSimulated(entry.getValue());
                continue;
            }
            String hash = tableHashes.getOrDefault(tableId, "");
            if (!probabilityData.needsResimulation(tableId, hash) && probabilityData.hasData(tableId)) {
                catalogGeneration.publishSimulated(restoreFromCache(
                        entry.getValue(), tableId, probabilityData, level, staticTables));
                restored++;
            } else {
                uncached.add(tableId);
            }
        }
        // 三个数字必须各算各的：不可用表也走 publishSimulated，直接读 overlay 大小会把它们算成"从缓存恢复"
        LOGGER.info("概率缓存命中 {} 个表，{} 个待模拟，{} 个不可用（共 {} 个表）",
                restored, uncached.size(), unavailableReasons.size(), staticTables.size());
        return new BuildResult(catalogGeneration, List.copyOf(uncached),
                Set.copyOf(unavailableReasons.keySet()));
    }

    // 不可用表的条目一律标记为「规则未解析」：这是与"未覆盖""尚未计算"都不同的失败原因，
    // 玩家据此知道该表的规则本模组读不了，而不是自己的处境问题
    private static TableDefinition markUnparsed(TableDefinition table) {
        List<ItemDefinition> items = table.items().stream()
                .map(item -> new ItemDefinition(item.id(), item.displayName(), item.tooltipHint(),
                        Probability.unknown(UnknownReason.UNPARSED), item.signature(),
                        item.acquisitionPaths(), item.injected(), List.of()))
                .toList();
        List<ChildTableProbability> children = table.childTableProbabilities().stream()
                .map(child -> new ChildTableProbability(child.tableId(),
                        Probability.unknown(UnknownReason.UNPARSED), List.of()))
                .toList();
        return new TableDefinition(table.id(), table.displayName(), table.type(), items,
                table.simulationCount(), table.childTables(), children);
    }

    // 未缓存表入队分 tick 模拟；提交与排空回调都捕获本代 generation，提交时校验。
    // 这里依赖既有 reload 协议：数据包重载走 pause → clearQueue → invalidate → ensureLoaded → resume，
    // 队列与在跑任务都会被丢弃，且暂停期间不会产出结果，因此旧代结果不可能落到新代处理器上；
    // 若将来出现"不清队列就换代"的调用路径，需要改为由工作项自身携带 generation。
    private static void enqueueSimulation(MinecraftServer server, CatalogGeneration generation,
                                         List<ResourceLocation> uncached) {
        long generationId = generation.generation();
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) {
            // 工作线程未启动（异常情况）：回退到主线程同步模拟，避免功能缺失
            LOGGER.warn("模拟工作线程未启动，回退到主线程同步模拟 {} 个表", uncached.size());
            simulateSynchronously(server, generation, uncached);
            return;
        }

        worker.setResultHandler((result, srv) -> commitSimulatedTable(generationId, result, srv));
        worker.setQueueDrainedHandler(srv -> broadcastCatalogHash(generationId, srv));

        Map<ResourceLocation, TableDefinition> uncachedMap = new LinkedHashMap<>();
        for (ResourceLocation tableId : uncached) {
            TableDefinition raw = generation.staticTable(tableId);
            if (raw != null) {
                uncachedMap.put(tableId, raw);
            }
        }
        worker.enqueueBatch(uncachedMap);
    }

    // 同步回退模拟（仅在 worker 未启动时使用）
    private static void simulateSynchronously(MinecraftServer server, CatalogGeneration generation,
                                             List<ResourceLocation> tableIds) {
        LootProbabilityData probabilityData = LootProbabilityData.get(server.overworld());
        for (ResourceLocation tableId : tableIds) {
            TableDefinition rawTable = generation.staticTable(tableId);
            if (rawTable == null) continue;
            LootProbabilitySimulator.SimResult result =
                    LootProbabilitySimulator.simulateOne(tableId, rawTable, server.overworld());
            commitSimulatedTable(generation, result, probabilityData);
        }
        broadcastCatalogHash(generation.generation(), server);
    }

    /**
     * 由工作器在主线程调用：提交单表模拟结果到当代 overlay 与 SavedData，并广播哈希。
     */
    public static void commitSimulatedTable(LootProbabilitySimulator.SimResult result, MinecraftServer server) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null) {
            return;
        }
        commitSimulatedTable(generation.generation(), result, server);
    }

    // 带 generation 校验的提交入口：旧代结果直接丢弃
    private static void commitSimulatedTable(long generationId, LootProbabilitySimulator.SimResult result,
                                             MinecraftServer server) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null || generation.generation() != generationId) {
            LOGGER.warn("丢弃来自旧 generation {} 的战利品表 {} 模拟结果（当前 generation {}）",
                    generationId, result.tableId(),
                    generation == null ? "无" : generation.generation());
            return;
        }
        commitSimulatedTable(generation, result, LootProbabilityData.get(server.overworld()));
    }

    // 实际提交逻辑（不广播，供同步回退批量调用）
    private static void commitSimulatedTable(CatalogGeneration generation,
                                            LootProbabilitySimulator.SimResult result,
                                            LootProbabilityData probabilityData) {
        if (!result.successful()) {
            LOGGER.warn("忽略战利品表 {} 的失败模拟结果；其概率保持未知，不写入缓存，"
                    + "将在下次数据包重载时重新尝试", result.tableId());
            return;
        }
        ResourceLocation tableId = result.tableId();
        TableDefinition table = result.result();
        String hash = generation.tableHash(tableId);

        // 写入 SavedData：只落测量事实（SimulatedValue），派生结论（需要条件 / 不可达）不落盘
        Map<String, LootProbabilityData.CachedItemProbability> probabilities = new LinkedHashMap<>();
        for (ItemDefinition item : table.items()) {
            Map<String, SimulatedValue> scenarioProbabilities = new LinkedHashMap<>();
            for (ScenarioProbability scenario : item.scenarioProbabilities()) {
                // 分场景只持久化"测到了多少"；不可用场景的「需要条件」由读取时的场景规划重新派生
                if (scenario.probability() instanceof Probability.Measured) {
                    scenarioProbabilities.put(scenario.scenarioKey(),
                            SimulatedValue.from(scenario.probability()));
                }
            }
            boolean hasDirectSource = item.acquisitionPaths().isEmpty();
            List<ResourceLocation> sourceChildTables = new ArrayList<>();
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                ResourceLocation source = path.sourceChildTable();
                if (source == null) {
                    hasDirectSource = true;
                } else if (!sourceChildTables.contains(source)) {
                    sourceChildTables.add(source);
                }
            }
            probabilities.put(item.signature().toStoredKey(),
                    new LootProbabilityData.CachedItemProbability(
                            SimulatedValue.from(item.probability()), scenarioProbabilities,
                            hasDirectSource, sourceChildTables));
        }
        for (ChildTableProbability child : table.childTableProbabilities()) {
            Map<String, SimulatedValue> scenarioProbabilities = new LinkedHashMap<>();
            for (ScenarioProbability scenario : child.scenarioProbabilities()) {
                if (scenario.probability() instanceof Probability.Measured) {
                    scenarioProbabilities.put(scenario.scenarioKey(),
                            SimulatedValue.from(scenario.probability()));
                }
            }
            probabilities.put(CHILD_CACHE_PREFIX + child.tableId(),
                    new LootProbabilityData.CachedItemProbability(
                            SimulatedValue.from(child.probability()), scenarioProbabilities, false, List.of()));
        }
        probabilityData.putSimulationResult(tableId, hash, probabilities);

        // 写入当代 overlay，并失效本代目录哈希缓存
        generation.publishSimulated(table);
    }


    // 带 generation 校验的广播入口：旧代排空事件不再触发同步
    private static void broadcastCatalogHash(long generationId, MinecraftServer server) {
        CatalogGeneration generation = currentGeneration;
        if (generation == null || generation.generation() != generationId) {
            return;
        }
        String hash = generation.catalogHash();
        if (hash.isEmpty()) return;
        SyncCatalogHashPayload payload = new SyncCatalogHashPayload(hash);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Services.NETWORK.sendToPlayer(player, payload);
        }
    }

    // 计算整个目录内容的 SHA-256 哈希（用于按需同步比对）；缓存与失效都在当代内完成
    public static String computeCatalogHash() {
        if (!loaded) return "";
        CatalogGeneration generation = currentGeneration;
        return generation == null ? "" : generation.catalogHash();
    }

    /** 是否已完成首轮加载；平台重载监听器用它区分"启动时的首次资源加载"与真正的重载。 */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 释放当代目录并标记为未加载；下次 ensureLoaded 会重新构建（含资源快照与投影一并释放） */
    public static void invalidate() {
        currentGeneration = null;
        loaded = false;
        // 预览栈按签名内容缓存，跨代仍然有效；此处只做释放以限制内存
        LootResultPreviewCache.clear();
    }

    /** 获取已填充概率的目录只读视图（随模拟完成渐进增长） */
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? Map.of() : generation.simulatedTables();
    }

    public static CatalogStructure getCatalogStructure() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? CatalogStructure.empty() : generation.structure();
    }

    /** 获取原始目录的只读视图（概率为 "?" 占位符，但物品列表完整）。
     *  ensureLoaded 后立即可用，不受渐进模拟影响；供成就判定等需要完整表集合的场景使用 */
    public static Map<ResourceLocation, TableDefinition> getRawCatalog() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? Map.of() : generation.staticTables();
    }

    /** 获取当代查询索引（子树物品等跨表聚合的唯一入口）；目录尚未加载时返回空索引 */
    public static CatalogQueryIndex getQueryIndex() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? CatalogQueryIndex.EMPTY : generation.queryIndex();
    }

    /** 获取原始表定义（概率为占位符），供工作线程模拟时查询 */
    @Nullable
    public static TableDefinition getRawTable(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? null : generation.staticTable(tableId);
    }

    // 判断指定表是否已被当前服务端的原始目录收录；不受概率模拟进度影响
    public static boolean isTrackedTable(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration;
        return generation != null && generation.isTracked(tableId);
    }

    /** 原始目录中的表总数（已 ensureLoaded 后可用） */
    public static int getRawCatalogCount() {
        CatalogGeneration generation = currentGeneration;
        return generation == null ? 0 : generation.trackedTableCount();
    }

    /** 判断指定表是否已有模拟结果（overlay 或 SavedData 任一命中即可） */
    public static boolean hasSimulatedData(ResourceLocation tableId) {
        CatalogGeneration generation = currentGeneration;
        return generation != null && generation.hasSimulated(tableId);
    }

    // 从 SavedData 恢复概率到原始目录定义中。
    // 存档里只有测量事实，因此这里必须重新派生展示状态：可适用性（需要条件 / 未覆盖）来自
    // 重新规划出的场景与路径静态结构，与模拟完成时用的是同一份判定，避免两处各写一套。
    private static TableDefinition restoreFromCache(
            TableDefinition rawTable, ResourceLocation tableId, LootProbabilityData probabilityData,
            ServerLevel level, Map<ResourceLocation, TableDefinition> staticTables) {
        Map<String, LootProbabilityData.CachedItemProbability> cachedProbabilities =
                probabilityData.getProbabilities(tableId);
        List<ItemDefinition> restoredItems = new ArrayList<>(cachedProbabilities.size());
        List<SimulationScenario> plannedScenarios = SimulationScenarioPlanner.plan(tableId, rawTable, level);
        String baselineKey = baselineScenarioKey(plannedScenarios);

        // 1. 恢复 JSON 解析出的原始条目：展示值按缓存中的分场景测量值重新派生
        for (ItemDefinition item : rawTable.items()) {
            LootProbabilityData.CachedItemProbability cached =
                    cachedProbabilities.get(item.signature().toStoredKey());
            List<ScenarioProbability> scenarioProbabilities =
                    rebuildItemScenarioProbabilities(item, plannedScenarios, cached);
            Probability display = PathHintAnalyzer.deriveDisplay(
                    baselineMeasurement(baselineKey, scenarioProbabilities), item.acquisitionPaths());
            restoredItems.add(new ItemDefinition(
                    item.id(), item.displayName(), item.tooltipHint(),
                    display, item.signature(), item.acquisitionPaths(), item.injected(), scenarioProbabilities));
        }

        // 2. 重建缓存中存在但 JSON 里没有的"注入条目"（GLM / LootTableEvents.MODIFY 模拟期发现）
        //    动态条目没有静态路径，无法陈述"需要什么条件"，因此直接沿用缓存中的测量值。
        Set<String> rawKeys = new HashSet<>();
        for (ItemDefinition item : rawTable.items()) {
            rawKeys.add(item.signature().toStoredKey());
        }
        Map<ResourceLocation, Set<String>> childSignatureIndex = buildCachedChildSignatureIndex(
                rawTable, probabilityData, staticTables);
        for (Map.Entry<String, LootProbabilityData.CachedItemProbability> cached : cachedProbabilities.entrySet()) {
            if (rawKeys.contains(cached.getKey())) {
                continue;
            }
            LootResultSignature signature = LootResultSignature.fromStoredKey(cached.getKey());
            if (signature != null) {
                ItemDefinition discoveredItem = LootTableCatalog.buildDiscoveredDefinition(signature,
                        cached.getValue().probability().toProbability(UnknownReason.UNCOVERED), true,
                        restoreDiscoveredScenarioProbabilities(cached.getValue(), plannedScenarios));
                boolean hasDirectSource = cached.getValue().hasDirectSource();
                List<ResourceLocation> childSources = cached.getValue().sourceChildTables();
                if (!hasDirectSource && childSources.isEmpty()) {
                    childSources = findCachedChildSources(cached.getKey(), childSignatureIndex);
                }
                if (hasDirectSource && childSources.isEmpty()) {
                    restoredItems.add(discoveredItem);
                    continue;
                }
                List<LootAcquisitionPath> acquisitionPaths = new ArrayList<>(
                        childSources.size() + (hasDirectSource ? 1 : 0));
                if (hasDirectSource) {
                    acquisitionPaths.add(new LootAcquisitionPath(null, List.of(), List.of()));
                }
                for (ResourceLocation childSource : childSources) {
                    acquisitionPaths.add(new LootAcquisitionPath(childSource, List.of(), List.of()));
                }
                if (acquisitionPaths.isEmpty()) {
                    restoredItems.add(discoveredItem);
                    continue;
                }
                restoredItems.add(new ItemDefinition(
                        discoveredItem.id(), discoveredItem.displayName(), discoveredItem.tooltipHint(),
                        discoveredItem.probability(), discoveredItem.signature(), acquisitionPaths,
                        discoveredItem.injected(), discoveredItem.scenarioProbabilities()));
            }
        }

        List<ChildTableProbability> childProbabilities = new ArrayList<>();
        for (ResourceLocation childTable : rawTable.childTables()) {
            LootProbabilityData.CachedItemProbability cached =
                    cachedProbabilities.get(CHILD_CACHE_PREFIX + childTable);
            List<ScenarioProbability> probabilities = rebuildChildScenarioProbabilities(
                    childTable, childTableConditions(rawTable, childTable), plannedScenarios, cached);
            childProbabilities.add(new ChildTableProbability(childTable,
                    baselineMeasurement(baselineKey, probabilities), probabilities));
        }

        return new TableDefinition(
                rawTable.id(), rawTable.displayName(), rawTable.type(), restoredItems,
                LootProbabilitySimulator.getSimulationCount(), rawTable.childTables(), childProbabilities);
    }

    // 基准场景 key——条件全部不成立的那个场景（与模拟完成时的判定同源）
    private static String baselineScenarioKey(List<SimulationScenario> scenarios) {
        for (SimulationScenario scenario : scenarios) {
            if (scenario.baseline()) {
                return scenario.key();
            }
        }
        return scenarios.isEmpty() ? "" : scenarios.getFirst().key();
    }

    // 基准场景的测量值；基准场景未覆盖该签名时说明"该输入下没有可用路径"
    private static Probability baselineMeasurement(String baselineKey,
                                                   List<ScenarioProbability> probabilities) {
        for (ScenarioProbability scenario : probabilities) {
            if (scenario.scenarioKey().equals(baselineKey)) {
                return scenario.probability();
            }
        }
        return Probability.uncovered();
    }

    // 用缓存里的测量值与重新规划出的场景，重建"每场景一个值"的展示列表：
    // 可适用场景用缓存的测量值（缺失即尚未计算），不可用场景按静态结构报「需要条件」或未覆盖。
    // 与模拟完成时的判定同源——两者都读同一份场景规划与同一份 PathHintAnalyzer。
    private static List<ScenarioProbability> rebuildItemScenarioProbabilities(
            ItemDefinition item, List<SimulationScenario> plannedScenarios,
            @Nullable LootProbabilityData.CachedItemProbability cached) {
        String storedKey = item.signature().toStoredKey();
        List<PathHint> hints = PathHintAnalyzer.hintsFor(item.acquisitionPaths());
        List<ScenarioProbability> result = new ArrayList<>();
        for (SimulationScenario scenario : plannedScenarios) {
            if (!scenario.applicableSignatures().contains(storedKey)) {
                result.add(new ScenarioProbability(scenario.key(),
                        PathHintAnalyzer.inapplicableScenarioDisplay(hints), scenario.assumptions()));
                continue;
            }
            result.add(measuredScenarioProbability(scenario, cached));
        }
        return List.copyOf(result);
    }

    private static List<ScenarioProbability> rebuildChildScenarioProbabilities(
            ResourceLocation childTable, List<LootConditionInfo> childConditions,
            List<SimulationScenario> plannedScenarios,
            @Nullable LootProbabilityData.CachedItemProbability cached) {
        List<PathHint> hints = childConditions.isEmpty()
                ? List.of()
                : List.of(new PathHint.ReferencesScenario(childConditions));
        List<ScenarioProbability> result = new ArrayList<>();
        for (SimulationScenario scenario : plannedScenarios) {
            if (!scenario.applicableChildTables().contains(childTable)) {
                result.add(new ScenarioProbability(scenario.key(),
                        PathHintAnalyzer.inapplicableScenarioDisplay(hints), scenario.assumptions()));
                continue;
            }
            result.add(measuredScenarioProbability(scenario, cached));
        }
        return List.copyOf(result);
    }

    // 适用场景的展示值：有测量值就用它，没有就是"尚未计算"——不是"不可能"
    private static ScenarioProbability measuredScenarioProbability(
            SimulationScenario scenario, @Nullable LootProbabilityData.CachedItemProbability cached) {
        SimulatedValue measured = cached == null
                ? null : cached.scenarioProbabilities().get(scenario.key());
        return new ScenarioProbability(scenario.key(),
                measured == null
                        ? Probability.unknown(UnknownReason.NOT_SIMULATED)
                        : measured.toProbability(UnknownReason.UNCOVERED),
                scenario.assumptions());
    }

    // 动态条目只保留缓存里实际测到过的场景；其余场景不进列表，避免为"没观测到"编造状态
    private static List<ScenarioProbability> restoreDiscoveredScenarioProbabilities(
            LootProbabilityData.CachedItemProbability cached,
            List<SimulationScenario> plannedScenarios) {
        List<ScenarioProbability> result = new ArrayList<>();
        for (SimulationScenario scenario : plannedScenarios) {
            SimulatedValue measured = cached.scenarioProbabilities().get(scenario.key());
            if (measured == null) {
                continue;
            }
            result.add(new ScenarioProbability(scenario.key(),
                    measured.toProbability(UnknownReason.UNCOVERED), scenario.assumptions()));
        }
        return List.copyOf(result);
    }

    // 该子表入口在父表里出现过的条件；用于"这个场景下为什么拿不到"的静态陈述
    private static List<LootConditionInfo> childTableConditions(
            TableDefinition parent, ResourceLocation childTable) {
        LinkedHashMap<String, LootConditionInfo> conditions = new LinkedHashMap<>();
        for (ItemDefinition item : parent.items()) {
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                if (!childTable.equals(path.sourceChildTable())) {
                    continue;
                }
                for (LootConditionInfo condition : path.allConditions()) {
                    conditions.putIfAbsent(
                            condition.conditionType() + "|" + condition.description().getString(), condition);
                }
            }
        }
        return List.copyOf(conditions.values());
    }

    // 每张父表只构建一次临时子树签名索引，避免按动态物品重复递归。
    private static Map<ResourceLocation, Set<String>> buildCachedChildSignatureIndex(
            TableDefinition parent, LootProbabilityData probabilityData,
            Map<ResourceLocation, TableDefinition> staticTables) {
        Map<ResourceLocation, Set<String>> result = new LinkedHashMap<>();
        for (ResourceLocation childId : parent.childTables()) {
            Set<String> signatures = new HashSet<>();
            collectCachedSubtreeSignatures(childId, probabilityData, staticTables, signatures, new HashSet<>());
            result.put(childId, signatures);
        }
        return result;
    }

    // 使用子表已有签名恢复动态条目的直接来源，避免为父表额外持久化整份来源映射。
    private static List<ResourceLocation> findCachedChildSources(
            String signatureKey, Map<ResourceLocation, Set<String>> childSignatureIndex) {
        List<ResourceLocation> result = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Set<String>> entry : childSignatureIndex.entrySet()) {
            if (entry.getValue().contains(signatureKey)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    // 同时收集解析期静态条目和模拟期动态条目；visited 防止数据包循环引用。
    private static void collectCachedSubtreeSignatures(
            ResourceLocation tableId, LootProbabilityData probabilityData,
            Map<ResourceLocation, TableDefinition> staticTables,
            Set<String> output, Set<ResourceLocation> visited) {
        if (!visited.add(tableId)) {
            return;
        }
        TableDefinition table = staticTables.get(tableId);
        if (table == null) {
            return;
        }
        for (String key : probabilityData.getProbabilities(tableId).keySet()) {
            if (!key.startsWith(CHILD_CACHE_PREFIX)) {
                output.add(key);
            }
        }
        for (ItemDefinition item : table.items()) {
            output.add(item.signature().toStoredKey());
        }
        for (ResourceLocation childId : table.childTables()) {
            collectCachedSubtreeSignatures(childId, probabilityData, staticTables, output, visited);
        }
    }

    // 对每个表计算 SHA-256 哈希：缓存版本 + 该表子树的资源栈摘要、编译产物摘要与被引用附魔定义摘要。
    // 摘要按 descendantsInclusive 覆盖后代每一张表，因此子表引用的 item tag 成员变化
    // （子表 JSON 文本不变、只有 tag 展开结果变）同样会让父表失效。
    //
    // 保证范围拆成两句（决策 35），不要读成一句更大的保证：
    //   1) "表 JSON 变化必然失效"——由资源栈摘要承担，现在成立；
    //   2) "影响概率的所有数据变化必然失效"——加上被引用附魔的定义摘要后成立。
    // 已知残余（不列入本摘要的外部注册表依赖）见 docs/dev/loottable.md。
    private static Map<ResourceLocation, String> computeTableHashes(
            LootTableReferenceGraph graph, Map<ResourceLocation, TableDefinition> tables,
            Map<ResourceLocation, CompiledLootTable> compiledTables, HolderLookup.Provider registries) {
        Map<ResourceLocation, String> hashes = new LinkedHashMap<>();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            LOGGER.warn("SHA-256 算法不可用，所有表将触发重新模拟", e);
            for (ResourceLocation tableId : tables.keySet()) {
                hashes.put(tableId, "");
            }
            return hashes;
        }

        for (ResourceLocation tableId : tables.keySet()) {
            try {
                digest.reset();
                LootTableSourceSnapshot.updateDigest(digest, SIMULATION_CACHE_VERSION);
                graph.updateSubtreeDigest(tableId, digest,
                        (node, nodeDigest) -> {
                            updateCompiledProductDigest(nodeDigest, tables.get(node));
                            // 附魔定义不在任何战利品表 JSON 里，却决定模拟用的满级工具与等级控件范围
                            updateEnchantmentDigest(nodeDigest, registries,
                                    referencedEnchantments(compiledTables.get(node)));
                        });
                hashes.put(tableId, HexFormat.of().formatHex(digest.digest()));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("计算战利品表 {} 哈希失败，将触发重新模拟", tableId, e);
                hashes.put(tableId, "");
            }
        }
        return hashes;
    }

    // 编译产物记录的"本表引用到的附魔"；表未被编译时写入固定标记，
    // 使"从无引用变为有引用"同样能改变父表摘要
    private static Set<ResourceLocation> referencedEnchantments(@Nullable CompiledLootTable compiled) {
        return compiled == null ? Set.of() : compiled.referencedEnchantments();
    }

    /**
     * 被引用附魔的定义摘要——只取 {@code max_level}。
     * <p>
     * 取这个字段而不是整份定义：决定模拟行为的是等级上限（它决定模拟用的满级工具与等级控件范围），
     * 其余字段（anvil 花费、权重等）不影响任何概率。因此本摘要**不覆盖**附魔定义的其它字段——
     * 这一条残余与其余外部注册表依赖一起写进 {@code docs/dev/loottable.md}。
     */
    private static void updateEnchantmentDigest(MessageDigest digest, HolderLookup.Provider registries,
                                                Set<ResourceLocation> enchantments) {
        if (enchantments.isEmpty()) {
            LootTableSourceSnapshot.updateDigest(digest, "no_enchantment");
            return;
        }
        List<ResourceLocation> sorted = enchantments.stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
        HolderLookup.RegistryLookup<Enchantment> lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
        for (ResourceLocation enchantmentId : sorted) {
            LootTableSourceSnapshot.updateDigest(digest, enchantmentId.toString());
            var holder = lookup.get(ResourceKey.create(Registries.ENCHANTMENT, enchantmentId));
            if (holder.isEmpty()) {
                LootTableSourceSnapshot.updateDigest(digest, "missing_definition");
                continue;
            }
            LootTableSourceSnapshot.updateDigest(digest,
                    Integer.toString(holder.get().value().definition().maxLevel()));
        }
    }

    // 编译产物摘要——tag 展开后的物品签名与物品 id，是"JSON 不变但解析结果变"的唯一失效信号。
    // 表未被解析（无物品）时写入固定标记，使"空表变为有物品"同样能改变父表摘要。
    private static void updateCompiledProductDigest(MessageDigest digest, @Nullable TableDefinition table) {
        if (table == null) {
            LootTableSourceSnapshot.updateDigest(digest, "no_compiled_product");
            return;
        }
        List<ItemDefinition> items = table.items().stream()
                .sorted(Comparator.comparing(item -> item.signature().toStoredKey()))
                .toList();
        LootTableSourceSnapshot.updateDigest(digest, Integer.toString(items.size()));
        for (ItemDefinition item : items) {
            LootTableSourceSnapshot.updateDigest(digest, item.signature().toStoredKey());
            LootTableSourceSnapshot.updateDigest(digest, item.id().toString());
        }
    }

    /** 一轮构建的产物：可发布的当代目录、待入队模拟的表，以及无法模拟的表。 */
    private record BuildResult(CatalogGeneration generation, List<ResourceLocation> uncachedTables,
                               Set<ResourceLocation> unavailableTables) {
    }
}
