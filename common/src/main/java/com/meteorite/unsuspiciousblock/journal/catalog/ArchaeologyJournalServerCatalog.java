package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogQueryIndex;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulator;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationScenario;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationScenarioPlanner;
import com.meteorite.unsuspiciousblock.loottable.source.LootTableSourceSnapshot;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.world.LootProbabilityData;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private static final String SIMULATION_CACHE_VERSION = "loot-analysis-v13";

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

        // 3. 计算哈希（吃子树内每张表的资源栈摘要与编译产物摘要）
        Map<ResourceLocation, String> tableHashes =
                computeTableHashes(loadResult.session().referenceGraph(), staticTables);
        CatalogGeneration catalogGeneration = new CatalogGeneration(
                loadResult.session(), loadResult.structure(), staticTables, tableHashes);

        // 4. 从 SavedData 恢复已缓存表，写入当代 overlay
        ServerLevel level = server.overworld();
        LootProbabilityData probabilityData = LootProbabilityData.get(level);
        List<ResourceLocation> uncached = new ArrayList<>();
        for (Map.Entry<ResourceLocation, TableDefinition> entry : staticTables.entrySet()) {
            ResourceLocation tableId = entry.getKey();
            String hash = tableHashes.getOrDefault(tableId, "");
            if (!probabilityData.needsResimulation(tableId, hash) && probabilityData.hasData(tableId)) {
                catalogGeneration.publishSimulated(restoreFromCache(
                        entry.getValue(), tableId, probabilityData, level, staticTables));
            } else {
                uncached.add(tableId);
            }
        }
        LOGGER.info("从缓存恢复 {} 个表，{} 个待模拟",
                catalogGeneration.simulatedTables().size(), uncached.size());
        return new BuildResult(catalogGeneration, List.copyOf(uncached));
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
            LOGGER.warn("忽略战利品表 {} 的失败模拟结果，保留待重试状态", result.tableId());
            return;
        }
        ResourceLocation tableId = result.tableId();
        TableDefinition table = result.result();
        String hash = generation.tableHash(tableId);

        // 写入 SavedData
        Map<String, LootProbabilityData.CachedItemProbability> probabilities = new LinkedHashMap<>();
        for (ItemDefinition item : table.items()) {
            Map<String, Probability> scenarioProbabilities = new LinkedHashMap<>();
            for (ScenarioProbability scenario : item.scenarioProbabilities()) {
                scenarioProbabilities.put(scenario.scenarioKey(), scenario.probability());
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
                            item.probability(), scenarioProbabilities,
                            hasDirectSource, sourceChildTables));
        }
        for (ChildTableProbability child : table.childTableProbabilities()) {
            Map<String, Probability> scenarioProbabilities = new LinkedHashMap<>();
            for (ScenarioProbability scenario : child.scenarioProbabilities()) {
                scenarioProbabilities.put(scenario.scenarioKey(), scenario.probability());
            }
            probabilities.put(CHILD_CACHE_PREFIX + child.tableId(),
                    new LootProbabilityData.CachedItemProbability(
                            child.probability(), scenarioProbabilities, false, List.of()));
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

    /** 释放当代目录并标记为未加载；下次 ensureLoaded 会重新构建（含资源快照与投影一并释放） */
    public static void invalidate() {
        currentGeneration = null;
        loaded = false;
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

    // 从 SavedData 恢复概率到原始目录定义中
    private static TableDefinition restoreFromCache(
            TableDefinition rawTable, ResourceLocation tableId, LootProbabilityData probabilityData,
            ServerLevel level, Map<ResourceLocation, TableDefinition> staticTables) {
        Map<String, LootProbabilityData.CachedItemProbability> cachedProbabilities =
                probabilityData.getProbabilities(tableId);
        List<ItemDefinition> restoredItems = new ArrayList<>(cachedProbabilities.size());
        Map<String, SimulationScenario> scenarios = new LinkedHashMap<>();
        for (SimulationScenario scenario : SimulationScenarioPlanner.plan(tableId, rawTable, level)) {
            scenarios.put(scenario.key(), scenario);
        }

        // 1. 恢复 JSON 解析出的原始条目概率
        for (ItemDefinition item : rawTable.items()) {
            LootProbabilityData.CachedItemProbability cached =
                    cachedProbabilities.get(item.signature().toStoredKey());
            Probability probability = cached != null ? cached.probability() : item.probability();
            List<ScenarioProbability> scenarioProbabilities = restoreScenarioProbabilities(cached, scenarios);
            restoredItems.add(new ItemDefinition(
                    item.id(), item.displayName(), item.tooltipHint(),
                    probability, item.signature(), item.acquisitionPaths(), item.injected(), scenarioProbabilities));
        }

        // 2. 重建缓存中存在但 JSON 里没有的"注入条目"（GLM / LootTableEvents.MODIFY 模拟期发现）
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
                        cached.getValue().probability(), true,
                        restoreScenarioProbabilities(cached.getValue(), scenarios));
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
            childProbabilities.add(cached == null
                    ? ChildTableProbability.pending(childTable)
                    : new ChildTableProbability(childTable, cached.probability(),
                    restoreScenarioProbabilities(cached, scenarios)));
        }

        return new TableDefinition(
                rawTable.id(), rawTable.displayName(), rawTable.type(), restoredItems,
                LootProbabilitySimulator.getSimulationCount(), rawTable.childTables(), childProbabilities);
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

    private static List<ScenarioProbability> restoreScenarioProbabilities(
            LootProbabilityData.CachedItemProbability cached,
            Map<String, SimulationScenario> scenarios) {
        if (cached == null || cached.scenarioProbabilities().isEmpty()) {
            return List.of();
        }
        List<ScenarioProbability> result = new ArrayList<>();
        for (Map.Entry<String, Probability> entry : cached.scenarioProbabilities().entrySet()) {
            SimulationScenario scenario = scenarios.get(entry.getKey());
            result.add(new ScenarioProbability(entry.getKey(), entry.getValue(),
                    scenario != null ? scenario.assumptions() : List.of()));
        }
        return List.copyOf(result);
    }

    // 对每个表计算 SHA-256 哈希：缓存版本 + 模拟次数 + 该表子树的资源栈摘要与编译产物摘要。
    // 摘要按 descendantsInclusive 覆盖后代每一张表，因此子表引用的 item tag 成员变化
    // （子表 JSON 文本不变、只有 tag 展开结果变）同样会让父表失效。
    private static Map<ResourceLocation, String> computeTableHashes(
            LootTableReferenceGraph graph, Map<ResourceLocation, TableDefinition> tables) {
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
                LootTableSourceSnapshot.updateDigest(digest,
                        Integer.toString(LootProbabilitySimulator.getSimulationCount()));
                graph.updateSubtreeDigest(tableId, digest,
                        (node, nodeDigest) -> updateCompiledProductDigest(nodeDigest, tables.get(node)));
                hashes.put(tableId, HexFormat.of().formatHex(digest.digest()));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("计算战利品表 {} 哈希失败，将触发重新模拟", tableId, e);
                hashes.put(tableId, "");
            }
        }
        return hashes;
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

    /** 一轮构建的产物：可发布的当代目录，以及需要入队模拟的未缓存表。 */
    private record BuildResult(CatalogGeneration generation, List<ResourceLocation> uncachedTables) {
    }
}
