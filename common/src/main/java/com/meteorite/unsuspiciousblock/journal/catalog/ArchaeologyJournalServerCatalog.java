package com.meteorite.unsuspiciousblock.journal.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulator;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationScenario;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationScenarioPlanner;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.world.LootProbabilityData;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端目录——解析所有考古战利品表，按需通过主线程 tick 工作器填充概率。
 * <p>
 * 生命周期：
 * <ul>
 *   <li>{@link #ensureLoaded(MinecraftServer)}：非阻塞，解析原始目录 + 从 SavedData 恢复已缓存表 +
 *       将未缓存表入队分 tick 模拟。立即返回，catalog 会随模拟完成渐进填充。</li>
 *   <li>{@link #commitSimulatedTable(LootProbabilitySimulator.SimResult, MinecraftServer)}：由工作器在主线程调用，
 *       将单表模拟结果写入 catalog 与 SavedData；整批任务结束后统一广播哈希。</li>
 *   <li>{@link #invalidate()}：清空内存目录与哈希缓存，下次 ensureLoaded 重新解析。</li>
 * </ul>
 * <p>
 * 线程安全：{@link #catalog} 与 {@link #rawCatalog} 使用 ConcurrentHashMap，
 * 读路径（getCatalog/getRawTable）无锁；写路径仅在主线程发生（ensureLoaded / commitSimulatedTable）。
 */
public final class ArchaeologyJournalServerCatalog {
    private static final String CHILD_CACHE_PREFIX = "child_table:";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");
    private static final String SIMULATION_CACHE_VERSION = "loot-analysis-v11";

    /** 已填充概率的目录（随模拟完成渐进增长） */
    private static final Map<ResourceLocation, TableDefinition> catalog = new ConcurrentHashMap<>();
    /** 原始目录（概率为 "?" 占位符），ensureLoaded 后填充，供 worker 查询 */
    private static final Map<ResourceLocation, TableDefinition> rawCatalog = new ConcurrentHashMap<>();
    /** 每个 tableId 对应的 JSON 内容哈希，用于判断是否需要重新模拟 */
    private static final Map<ResourceLocation, String> tableHashes = new ConcurrentHashMap<>();
    private static volatile CatalogStructure catalogStructure = CatalogStructure.empty();
    private static volatile boolean loaded;

    private ArchaeologyJournalServerCatalog() {
    }

    /**
     * 非阻塞加载：解析原始目录 → 从 SavedData 恢复缓存 → 未缓存表入队后台模拟。
     * 调用后 catalog 立即可用（仅含缓存表），未缓存表的概率为 "?" 占位符，
     * 后台模拟完成后渐进填充。
     */
    public static void ensureLoaded(MinecraftServer server) {
        if (loaded) return;

        try {
            rawCatalog.clear();
            catalog.clear();
            tableHashes.clear();
            cachedCatalogHash = null;

            // 1. 解析原始目录（概率字段为 "?" 占位符）
            ArchaeologyJournalCatalog.LoadResult loadResult =
                    ArchaeologyJournalCatalog.load(server.getResourceManager(), server.registryAccess());
            Map<ResourceLocation, TableDefinition> parsed = loadResult.tables();
            catalogStructure = loadResult.structure();
            rawCatalog.putAll(parsed);
            LOGGER.info("解析到 {} 个考古战利品表原始目录", parsed.size());

            // 2. 计算每个表的 JSON 内容哈希
            tableHashes.putAll(computeTableHashes(server.getResourceManager(), parsed));

            // 3. 从 SavedData 恢复已缓存表
            ServerLevel level = server.overworld();
            LootProbabilityData probabilityData = LootProbabilityData.get(level);
            List<ResourceLocation> uncached = new ArrayList<>();

            for (Map.Entry<ResourceLocation, TableDefinition> entry : parsed.entrySet()) {
                ResourceLocation tableId = entry.getKey();
                String hash = tableHashes.getOrDefault(tableId, "");

                if (!probabilityData.needsResimulation(tableId, hash) && probabilityData.hasData(tableId)) {
                    catalog.put(tableId, restoreFromCache(entry.getValue(), tableId, probabilityData, level));
                } else {
                    uncached.add(tableId);
                }
            }
            LOGGER.info("从缓存恢复 {} 个表，{} 个待模拟", catalog.size(), uncached.size());

            loaded = true;

            // 4. 未缓存表入队分 tick 模拟
            if (!uncached.isEmpty()) {
                LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
                if (worker != null) {
                    worker.setResultHandler(ArchaeologyJournalServerCatalog::commitSimulatedTable);
                    worker.setQueueDrainedHandler(ArchaeologyJournalServerCatalog::broadcastCatalogHash);
                    // 构建仅含未缓存表的子 map
                    Map<ResourceLocation, TableDefinition> uncachedMap = new LinkedHashMap<>();
                    for (ResourceLocation id : uncached) {
                        TableDefinition raw = rawCatalog.get(id);
                        if (raw != null) {
                            uncachedMap.put(id, raw);
                        }
                    }
                    worker.enqueueBatch(uncachedMap);
                } else {
                    // 工作线程未启动（异常情况）：回退到主线程同步模拟，避免功能缺失
                    LOGGER.warn("模拟工作线程未启动，回退到主线程同步模拟 {} 个表", uncached.size());
                    simulateSynchronously(server, uncached);
                }
            }
        } catch (Exception e) {
            LOGGER.error("加载考古战利品表目录失败", e);
            loaded = true;
        }
    }

    // 同步回退模拟（仅在 worker 未启动时使用）
    private static void simulateSynchronously(MinecraftServer server, List<ResourceLocation> tableIds) {
        ServerLevel level = server.overworld();
        LootProbabilityData probabilityData = LootProbabilityData.get(level);
        for (ResourceLocation tableId : tableIds) {
            TableDefinition rawTable = rawCatalog.get(tableId);
            if (rawTable == null) continue;
            LootProbabilitySimulator.SimResult result = LootProbabilitySimulator.simulateOne(tableId, rawTable, level);
            commitSimulatedTable(result, probabilityData);
        }
        broadcastCatalogHash(server);
    }

    /**
     * 由工作器在主线程调用：提交单表模拟结果到 catalog 与 SavedData，并广播哈希。
     */
    public static void commitSimulatedTable(LootProbabilitySimulator.SimResult result, MinecraftServer server) {
        LootProbabilityData probabilityData = LootProbabilityData.get(server.overworld());
        commitSimulatedTable(result, probabilityData);
    }

    // 实际提交逻辑（不广播，供同步回退批量调用）
    private static void commitSimulatedTable(LootProbabilitySimulator.SimResult result, LootProbabilityData probabilityData) {
        if (!result.successful()) {
            LOGGER.warn("忽略战利品表 {} 的失败模拟结果，保留待重试状态", result.tableId());
            return;
        }
        ResourceLocation tableId = result.tableId();
        TableDefinition table = result.result();
        String hash = tableHashes.getOrDefault(tableId, "");

        // 写入 SavedData
        Map<String, LootProbabilityData.CachedItemProbability> probabilities = new LinkedHashMap<>();
        for (ItemDefinition item : table.items()) {
            Map<String, String> scenarioProbabilities = new LinkedHashMap<>();
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
            Map<String, String> scenarioProbabilities = new LinkedHashMap<>();
            for (ScenarioProbability scenario : child.scenarioProbabilities()) {
                scenarioProbabilities.put(scenario.scenarioKey(), scenario.probability());
            }
            probabilities.put(CHILD_CACHE_PREFIX + child.tableId(),
                    new LootProbabilityData.CachedItemProbability(
                            child.probability(), scenarioProbabilities, false, List.of()));
        }
        probabilityData.putSimulationResult(tableId, hash, probabilities);

        // 写入 catalog，失效哈希缓存
        catalog.put(tableId, table);
        cachedCatalogHash = null;
    }

    /** 向所有在线玩家广播目录哈希，客户端比对不一致时会主动请求全量目录 */
    public static void broadcastCatalogHash(MinecraftServer server) {
        String hash = computeCatalogHash();
        if (hash.isEmpty()) return;
        SyncCatalogHashPayload payload = new SyncCatalogHashPayload(hash);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Services.NETWORK.sendToPlayer(player, payload);
        }
    }

    // 缓存目录的 SHA-256 哈希（invalidate 时清除）
    @SuppressWarnings("VolatileArrayField")
    private static volatile String cachedCatalogHash;

    // 计算整个目录内容的 SHA-256 哈希（用于按需同步比对）
    public static String computeCatalogHash() {
        if (!loaded) return "";
        String cached = cachedCatalogHash;
        if (cached != null) return cached;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<TableDefinition> tables = catalog.values().stream()
                    .sorted(Comparator.comparing(table -> table.id().toString()))
                    .toList();
            for (TableDefinition table : tables) {
                updateDigest(digest, table.id().toString());
                updateDigest(digest, table.displayName().toString());
                updateDigest(digest, table.type());
                updateDigest(digest, Integer.toString(table.simulationCount()));
                table.childTables().forEach(child -> updateDigest(digest, child.toString()));
                for (ChildTableProbability child : table.childTableProbabilities()) {
                    updateDigest(digest, child.tableId().toString());
                    updateDigest(digest, child.probability());
                    for (ScenarioProbability scenario : child.scenarioProbabilities()) {
                        updateDigest(digest, scenario.scenarioKey());
                        updateDigest(digest, scenario.probability());
                        updateConditionListDigest(digest, scenario.conditions());
                    }
                }
                List<ItemDefinition> items = table.items().stream()
                        .sorted(Comparator.comparing(item -> item.signature().toStoredKey()))
                        .toList();
                for (ItemDefinition item : items) {
                    updateDigest(digest, item.id().toString());
                    updateDigest(digest, item.displayName().toString());
                    updateDigest(digest, item.tooltipHint() != null ? item.tooltipHint().toString() : "");
                    updateDigest(digest, item.probability());
                    updateDigest(digest, item.signature().toStoredKey());
                    updateDigest(digest, Boolean.toString(item.injected()));
                    for (ScenarioProbability scenario : item.scenarioProbabilities()) {
                        updateDigest(digest, scenario.scenarioKey());
                        updateDigest(digest, scenario.probability());
                        updateConditionListDigest(digest, scenario.conditions());
                    }
                    for (LootAcquisitionPath path : item.acquisitionPaths()) {
                        updateDigest(digest, path.sourceChildTable() != null
                                ? path.sourceChildTable().toString() : "");
                        updateDigest(digest, path.sourceItemTag() != null
                                ? path.sourceItemTag().toString() : "");
                        updateConditionListDigest(digest, path.entryConditions());
                        updateConditionListDigest(digest, path.inheritedConditions());
                    }
                }
            }
            catalogStructure.categories().forEach(category -> {
                updateDigest(digest, category.id().toString());
                updateDigest(digest, category.translationKey());
                updateDigest(digest, category.fallbackName());
                updateDigest(digest, category.descriptionKey());
                updateDigest(digest, category.descriptionFallback());
                updateDigest(digest, category.iconItem().toString());
                updateDigest(digest, Integer.toString(category.order()));
            });
            catalogStructure.rootCategories().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                    .forEach(entry -> {
                        updateDigest(digest, entry.getKey().toString());
                        updateDigest(digest, entry.getValue().toString());
                    });
            cached = HexFormat.of().formatHex(digest.digest());
            cachedCatalogHash = cached;
            return cached;
        } catch (NoSuchAlgorithmException e) {
            LOGGER.warn("SHA-256 算法不可用，目录哈希将返回空字符串", e);
            return "";
        }
    }

    private static void updateConditionListDigest(MessageDigest digest, List<LootConditionInfo> conditions) {
        updateDigest(digest, Integer.toString(conditions.size()));
        for (LootConditionInfo condition : conditions) {
            updateDigest(digest, condition.conditionType().toString());
            updateDigest(digest, condition.description().toString());
            updateDigest(digest, condition.probability() != null
                    ? Float.toString(condition.probability()) : "");
            condition.metadata().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        updateDigest(digest, entry.getKey());
                        updateDigest(digest, entry.getValue());
                    });
            updateConditionListDigest(digest, condition.children());
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    /** 使内存缓存失效（数据包重载后调用，下次 ensureLoaded 会重新加载） */
    public static void invalidate() {
        catalog.clear();
        rawCatalog.clear();
        tableHashes.clear();
        catalogStructure = CatalogStructure.empty();
        cachedCatalogHash = null;
        loaded = false;
    }

    /** 获取已填充目录的只读视图 */
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        return Collections.unmodifiableMap(catalog);
    }

    public static CatalogStructure getCatalogStructure() {
        return catalogStructure;
    }

    /** 获取原始目录的只读视图（概率为 "?" 占位符，但物品列表完整）。
     *  ensureLoaded 后立即可用，不受渐进模拟影响；供成就判定等需要完整表集合的场景使用 */
    public static Map<ResourceLocation, TableDefinition> getRawCatalog() {
        return Collections.unmodifiableMap(rawCatalog);
    }

    /** 获取原始表定义（概率为占位符），供工作线程模拟时查询 */
    public static TableDefinition getRawTable(ResourceLocation tableId) {
        return rawCatalog.get(tableId);
    }

    // 判断指定表是否已被当前服务端的原始目录收录；不受概率模拟进度影响
    public static boolean isTrackedTable(ResourceLocation tableId) {
        return rawCatalog.containsKey(tableId);
    }

    /** 原始目录中的表总数（已 ensureLoaded 后可用） */
    public static int getRawCatalogCount() {
        return rawCatalog.size();
    }

    /** 判断指定表是否已有模拟结果（catalog 或 SavedData 任一命中即可） */
    public static boolean hasSimulatedData(ResourceLocation tableId) {
        return catalog.containsKey(tableId);
    }

    // 从 SavedData 恢复概率到原始目录定义中
    private static TableDefinition restoreFromCache(
            TableDefinition rawTable, ResourceLocation tableId, LootProbabilityData probabilityData,
            ServerLevel level) {
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
            String probability = cached != null ? cached.probability() : item.probability();
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
                rawTable, probabilityData);
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
            TableDefinition parent, LootProbabilityData probabilityData) {
        Map<ResourceLocation, Set<String>> result = new LinkedHashMap<>();
        for (ResourceLocation childId : parent.childTables()) {
            Set<String> signatures = new HashSet<>();
            collectCachedSubtreeSignatures(childId, probabilityData, signatures, new HashSet<>());
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
            Set<String> output, Set<ResourceLocation> visited) {
        if (!visited.add(tableId)) {
            return;
        }
        TableDefinition table = rawCatalog.get(tableId);
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
            collectCachedSubtreeSignatures(childId, probabilityData, output, visited);
        }
    }

    private static List<ScenarioProbability> restoreScenarioProbabilities(
            LootProbabilityData.CachedItemProbability cached,
            Map<String, SimulationScenario> scenarios) {
        if (cached == null || cached.scenarioProbabilities().isEmpty()) {
            return List.of();
        }
        List<ScenarioProbability> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : cached.scenarioProbabilities().entrySet()) {
            SimulationScenario scenario = scenarios.get(entry.getKey());
            result.add(new ScenarioProbability(entry.getKey(), entry.getValue(),
                    scenario != null ? scenario.assumptions() : List.of()));
        }
        return List.copyOf(result);
    }

    // 对每个表的 JSON 资源内容计算 SHA-256 哈希
    private static Map<ResourceLocation, String> computeTableHashes(
            ResourceManager resourceManager, Map<ResourceLocation, TableDefinition> tables) {
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

        for (Map.Entry<ResourceLocation, TableDefinition> entry : tables.entrySet()) {
            ResourceLocation tableId = entry.getKey();
            try {
                digest.reset();
                updateDigest(digest, SIMULATION_CACHE_VERSION);
                updateDigest(digest, Integer.toString(LootProbabilitySimulator.getSimulationCount()));
                Set<ResourceLocation> visited = new HashSet<>();
                updateTableResourceDigest(resourceManager, tableId, digest, visited);
                for (ResourceLocation childTable : entry.getValue().childTables()) {
                    updateTableResourceDigest(resourceManager, childTable, digest, visited);
                }
                updateRawDefinitionDigest(digest, entry.getValue());
                hashes.put(tableId, HexFormat.of().formatHex(digest.digest()));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("计算战利品表 {} 哈希失败，将触发重新模拟", tableId, e);
                hashes.put(tableId, "");
            }
        }
        return hashes;
    }

    // 将解析后的物品签名纳入哈希，覆盖 loot table 中 item tag 成员变化等间接依赖
    private static void updateRawDefinitionDigest(MessageDigest digest, TableDefinition table) {
        table.childTables().stream().sorted(Comparator.comparing(ResourceLocation::toString))
                .forEach(child -> updateDigest(digest, child.toString()));
        List<ItemDefinition> items = table.items().stream()
                .sorted(Comparator.comparing(item -> item.signature().toStoredKey()))
                .toList();
        updateDigest(digest, Integer.toString(items.size()));
        for (ItemDefinition item : items) {
            updateDigest(digest, item.signature().toStoredKey());
            updateDigest(digest, item.id().toString());
            updateDigest(digest, Boolean.toString(item.injected()));
        }
    }

    private static void updateTableResourceDigest(ResourceManager resourceManager, ResourceLocation tableId,
                                                  MessageDigest digest, Set<ResourceLocation> visited)
            throws IOException {
        if (!visited.add(tableId)) {
            return;
        }
        updateDigest(digest, tableId.toString());
        ResourceLocation filePath = LOOT_TABLES.idToFile(tableId);
        for (Resource resource : resourceManager.getResourceStack(filePath)) {
            StringBuilder json = new StringBuilder();
            try (BufferedReader reader = resource.openAsReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line).append('\n');
                }
            }
            String content = json.toString();
            updateDigest(digest, content);
            collectReferencedTables(JsonParser.parseString(content), resourceManager, digest, visited);
        }
    }

    private static void collectReferencedTables(JsonElement element, ResourceManager resourceManager,
                                                MessageDigest digest, Set<ResourceLocation> visited)
            throws IOException {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectReferencedTables(child, resourceManager, digest, visited);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        JsonElement typeElement = object.get("type");
        if (typeElement != null && typeElement.isJsonPrimitive()
                && typeElement.getAsJsonPrimitive().isString()
                && isLootTableEntry(typeElement.getAsString())) {
            JsonElement valueElement = object.has("value") ? object.get("value") : object.get("name");
            if (valueElement != null && valueElement.isJsonPrimitive()
                    && valueElement.getAsJsonPrimitive().isString()) {
                ResourceLocation referencedId = ResourceLocation.tryParse(valueElement.getAsString());
                if (referencedId != null) {
                    updateTableResourceDigest(resourceManager, referencedId, digest, visited);
                }
            }
        }
        for (Map.Entry<String, JsonElement> child : object.entrySet()) {
            collectReferencedTables(child.getValue(), resourceManager, digest, visited);
        }
    }

    private static boolean isLootTableEntry(String type) {
        return type.equals("loot_table") || type.equals("minecraft:loot_table");
    }
}
