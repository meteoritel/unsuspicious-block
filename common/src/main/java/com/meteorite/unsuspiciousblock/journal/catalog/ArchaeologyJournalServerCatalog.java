package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.LootProbabilitySimulator;
import com.meteorite.unsuspiciousblock.loottable.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端目录——解析所有考古战利品表，按需通过后台工作线程填充概率。
 * <p>
 * 生命周期：
 * <ul>
 *   <li>{@link #ensureLoaded(MinecraftServer)}：非阻塞，解析原始目录 + 从 SavedData 恢复已缓存表 +
 *       将未缓存表入队后台模拟。立即返回，catalog 会随模拟完成渐进填充。</li>
 *   <li>{@link #commitSimulatedTable(LootProbabilitySimulator.SimResult, MinecraftServer)}：由工作器在主线程调用，
 *       将单表模拟结果写入 catalog 与 SavedData，并广播哈希给在线玩家。</li>
 *   <li>{@link #invalidate()}：清空内存目录与哈希缓存，下次 ensureLoaded 重新解析。</li>
 * </ul>
 * <p>
 * 线程安全：{@link #catalog} 与 {@link #rawCatalog} 使用 ConcurrentHashMap，
 * 读路径（getCatalog/getRawTable）无锁；写路径仅在主线程发生（ensureLoaded / commitSimulatedTable）。
 */
public final class ArchaeologyJournalServerCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");

    /** 已填充概率的目录（随模拟完成渐进增长） */
    private static final Map<ResourceLocation, TableDefinition> catalog = new ConcurrentHashMap<>();
    /** 原始目录（概率为 "?" 占位符），ensureLoaded 后填充，供 worker 查询 */
    private static final Map<ResourceLocation, TableDefinition> rawCatalog = new ConcurrentHashMap<>();
    /** 每个 tableId 对应的 JSON 内容哈希，用于判断是否需要重新模拟 */
    private static final Map<ResourceLocation, String> tableHashes = new ConcurrentHashMap<>();
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
            Map<ResourceLocation, TableDefinition> parsed =
                    ArchaeologyJournalCatalog.load(server.getResourceManager());
            rawCatalog.putAll(parsed);
            LOGGER.info("解析到 {} 个考古战利品表原始目录", parsed.size());

            // 2. 计算每个表的 JSON 内容哈希
            tableHashes.putAll(computeTableHashes(server.getResourceManager(), parsed.keySet()));

            // 3. 从 SavedData 恢复已缓存表
            ServerLevel level = server.overworld();
            LootProbabilityData probabilityData = LootProbabilityData.get(level);
            List<ResourceLocation> uncached = new ArrayList<>();

            for (Map.Entry<ResourceLocation, TableDefinition> entry : parsed.entrySet()) {
                ResourceLocation tableId = entry.getKey();
                String hash = tableHashes.getOrDefault(tableId, "");

                if (!probabilityData.needsResimulation(tableId, hash) && probabilityData.hasData(tableId)) {
                    catalog.put(tableId, restoreFromCache(entry.getValue(), tableId, probabilityData));
                } else {
                    uncached.add(tableId);
                }
            }
            LOGGER.info("从缓存恢复 {} 个表，{} 个待模拟", catalog.size(), uncached.size());

            loaded = true;

            // 4. 未缓存表入队后台模拟
            if (!uncached.isEmpty()) {
                LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
                if (worker != null) {
                    worker.enqueueBatch(uncached);
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
        broadcastCatalogHash(server);
    }

    // 实际提交逻辑（不广播，供同步回退批量调用）
    private static void commitSimulatedTable(LootProbabilitySimulator.SimResult result, LootProbabilityData probabilityData) {
        ResourceLocation tableId = result.tableId();
        TableDefinition table = result.result();
        String hash = tableHashes.getOrDefault(tableId, "");

        // 写入 SavedData
        Map<String, String> probabilities = new LinkedHashMap<>();
        for (ItemDefinition item : table.items()) {
            probabilities.put(item.signature().toStoredKey(), item.probability());
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
            for (Map.Entry<ResourceLocation, TableDefinition> entry : catalog.entrySet()) {
                digest.update(entry.getKey().toString().getBytes(StandardCharsets.UTF_8));
                for (ItemDefinition item : entry.getValue().items()) {
                    digest.update(item.signature().toStoredKey().getBytes(StandardCharsets.UTF_8));
                }
            }
            cached = HexFormat.of().formatHex(digest.digest());
            cachedCatalogHash = cached;
            return cached;
        } catch (NoSuchAlgorithmException e) {
            LOGGER.warn("SHA-256 算法不可用，目录哈希将返回空字符串", e);
            return "";
        }
    }

    /** 使内存缓存失效（数据包重载后调用，下次 ensureLoaded 会重新加载） */
    public static void invalidate() {
        catalog.clear();
        rawCatalog.clear();
        tableHashes.clear();
        cachedCatalogHash = null;
        loaded = false;
    }

    /** 获取已填充目录的只读视图 */
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        return Collections.unmodifiableMap(catalog);
    }

    /** 获取原始表定义（概率为占位符），供工作线程模拟时查询 */
    public static TableDefinition getRawTable(ResourceLocation tableId) {
        return rawCatalog.get(tableId);
    }

    /** 原始目录中的表总数（已 ensureLoaded 后可用） */
    public static int getRawCatalogCount() {
        return rawCatalog.size();
    }

    /** 判断指定表是否已有模拟结果（catalog 或 SavedData 任一命中即可） */
    public static boolean hasSimulatedData(ResourceLocation tableId) {
        return catalog.containsKey(tableId);
    }

    /** 当前是否已加载完成（注意：已加载不等于所有表已模拟，仅表示初始解析完成） */
    public static boolean isLoaded() {
        return loaded;
    }

    // 从 SavedData 恢复概率到原始目录定义中
    private static TableDefinition restoreFromCache(
            TableDefinition rawTable, ResourceLocation tableId, LootProbabilityData probabilityData) {
        Map<String, String> cachedProbabilities = probabilityData.getProbabilities(tableId);
        List<ItemDefinition> restoredItems = new ArrayList<>(cachedProbabilities.size());

        // 1. 恢复 JSON 解析出的原始条目概率
        for (ItemDefinition item : rawTable.items()) {
            String cachedProb = cachedProbabilities.get(item.signature().toStoredKey());
            String probability = cachedProb != null ? cachedProb : item.probability();
            restoredItems.add(new ItemDefinition(
                    item.id(), item.displayName(), item.tooltipHint(),
                    probability, item.signature(), item.sourceChildTable()));
        }

        // 2. 重建缓存中存在但 JSON 里没有的"注入条目"（GLM / LootTableEvents.MODIFY 模拟期发现）
        Set<String> rawKeys = new HashSet<>();
        for (ItemDefinition item : rawTable.items()) {
            rawKeys.add(item.signature().toStoredKey());
        }
        for (Map.Entry<String, String> cached : cachedProbabilities.entrySet()) {
            if (rawKeys.contains(cached.getKey())) {
                continue;
            }
            LootResultSignature signature = LootResultSignature.fromStoredKey(cached.getKey());
            if (signature != null) {
                restoredItems.add(ArchaeologyJournalCatalog.buildDiscoveredDefinition(signature, cached.getValue()));
            }
        }

        return new TableDefinition(
                rawTable.id(), rawTable.displayName(), rawTable.type(), restoredItems,
                LootProbabilitySimulator.getSimulationCount());
    }

    // 对每个表的 JSON 资源内容计算 SHA-256 哈希
    private static Map<ResourceLocation, String> computeTableHashes(
            ResourceManager resourceManager, Iterable<ResourceLocation> tableIds) {
        Map<ResourceLocation, String> hashes = new LinkedHashMap<>();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            LOGGER.warn("SHA-256 算法不可用，所有表将触发重新模拟", e);
            for (ResourceLocation tableId : tableIds) {
                hashes.put(tableId, "");
            }
            return hashes;
        }

        for (ResourceLocation tableId : tableIds) {
            ResourceLocation filePath = LOOT_TABLES.idToFile(tableId);
            try {
                digest.reset();
                // 遍历资源栈（含数据包覆盖层），拼接所有层内容做哈希
                for (Resource resource : resourceManager.getResourceStack(filePath)) {
                    try (BufferedReader reader = resource.openAsReader()) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            digest.update(line.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
                hashes.put(tableId, HexFormat.of().formatHex(digest.digest()));
            } catch (IOException e) {
                LOGGER.warn("计算战利品表 {} 哈希失败，将触发重新模拟", tableId, e);
                hashes.put(tableId, "");
            }
        }
        return hashes;
    }
}
