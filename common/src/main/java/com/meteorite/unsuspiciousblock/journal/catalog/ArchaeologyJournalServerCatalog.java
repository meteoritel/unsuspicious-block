package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.LootProbabilitySimulator;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import com.meteorite.unsuspiciousblock.world.LootProbabilityData;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端懒加载目录——在服务端解析并缓存所有考古战利品表，
 * 然后通过模拟抽取替换概率占位符为真实概率。
 * 在数据包重载或服务器启动时懒加载，通过 ensureLoaded / invalidate 控制生命周期。
 */
public final class ArchaeologyJournalServerCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");
    private static final Map<ResourceLocation, TableDefinition> catalog = new LinkedHashMap<>();
    private static boolean loaded;

    private ArchaeologyJournalServerCatalog() {
    }

    // 确保服务端目录已加载（懒加载，只加载一次）
    public static void ensureLoaded(MinecraftServer server) {
        if (loaded) return;

        try {
            // 1. 解析原始目录（概率字段为 "?" 占位符）
            catalog.clear();
            Map<ResourceLocation, TableDefinition> rawCatalog = ArchaeologyJournalCatalog.load(server.getResourceManager());
            LOGGER.info("1. 解析原始目录（概率字段为 \"?\" 占位符）");
            // 2. 获取服务端级别用于模拟
            ServerLevel level = server.overworld();
            LootProbabilityData probabilityData = LootProbabilityData.get(level);
            LOGGER.info("2. 获取服务端级别用于模拟");
            // 3. 计算每个表的 JSON 内容哈希，判断是否需要重新模拟
            Map<ResourceLocation, String> tableHashes = computeTableHashes(server.getResourceManager(), rawCatalog.keySet());
            Map<ResourceLocation, TableDefinition> tablesToSimulate = new LinkedHashMap<>();
            Map<ResourceLocation, TableDefinition> cachedResults = new LinkedHashMap<>();

            for (Map.Entry<ResourceLocation, TableDefinition> entry : rawCatalog.entrySet()) {
                ResourceLocation tableId = entry.getKey();
                String hash = tableHashes.getOrDefault(tableId, "");

                if (!probabilityData.needsResimulation(tableId, hash) && probabilityData.hasData(tableId)) {
                    // 使用缓存的概率数据替换占位符
                    cachedResults.put(tableId, restoreFromCache(entry.getValue(), tableId, probabilityData));
                } else {
                    tablesToSimulate.put(tableId, entry.getValue());
                }
            }
            LOGGER.info("3. 计算每个表的 JSON 内容哈希，判断是否需要重新模拟");
            // 4. 对需要模拟的表执行模拟
            Map<ResourceLocation, TableDefinition> simulatedCatalog =
                    tablesToSimulate.isEmpty() ? Collections.emptyMap()
                            : LootProbabilitySimulator.simulate(tablesToSimulate, level);

            LOGGER.info("4. 对需要模拟的表执行模拟");
            // 5. 将模拟结果写入 SavedData
            for (Map.Entry<ResourceLocation, TableDefinition> entry : simulatedCatalog.entrySet()) {
                ResourceLocation tableId = entry.getKey();
                TableDefinition table = entry.getValue();
                String hash = tableHashes.getOrDefault(tableId, "");

                Map<String, String> probabilities = new LinkedHashMap<>();
                for (ItemDefinition item : table.items()) {
                    probabilities.put(item.signature().toStoredKey(), item.probability());
                }
                probabilityData.putSimulationResult(tableId, hash, probabilities);
            }
            LOGGER.info("5. 将模拟结果写入 SavedData");
            // 6. 合并缓存与模拟结果
            catalog.clear();
            catalog.putAll(cachedResults);
            catalog.putAll(simulatedCatalog);
            loaded = true;
            LOGGER.info("6. 已加载 {} 个考古战利品表到服务端目录（缓存 {} 个，模拟 {} 个）。",
                    catalog.size(), cachedResults.size(), simulatedCatalog.size());
            logLoadedTables();
        } catch (Exception e) {
            LOGGER.error("6. 加载考古战利品表目录失败。", e);
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
            // 按表的注册顺序拼接所有表 ID 和条目签名，计算整体哈希
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

    // 使缓存失效（数据包重载后调用，下次 ensureLoaded 会重新加载）
    public static void invalidate() {
        catalog.clear();
        cachedCatalogHash = null;
        loaded = false;
    }

    // 获取缓存目录的只读视图
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        return Collections.unmodifiableMap(catalog);
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
                    probability, item.signature()));
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

    private static void logLoadedTables() {
        for (TableDefinition table : catalog.values()) {
            ResourceLocation tableId = table.id();
            LOGGER.debug("考古战利品表: {} | items={} | simCount={}",
                    tableId, table.items().size(), table.simulationCount());
        }
    }
}