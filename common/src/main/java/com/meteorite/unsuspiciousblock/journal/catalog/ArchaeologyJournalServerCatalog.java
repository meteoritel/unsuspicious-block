package com.meteorite.unsuspiciousblock.journal.catalog;

import com.meteorite.unsuspiciousblock.loottable.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端懒加载目录——在服务端解析并缓存所有考古战利品表。
 * 在数据包重载或服务器启动时懒加载，通过 ensureLoaded / invalidate 控制生命周期。
 */
public final class ArchaeologyJournalServerCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, TableDefinition> catalog = new LinkedHashMap<>();
    private static boolean loaded;

    private ArchaeologyJournalServerCatalog() {
    }

    // 确保服务端目录已加载（懒加载，只加载一次）
    public static void ensureLoaded(MinecraftServer server) {
        if (loaded) return;

        try {
            catalog.clear();
            catalog.putAll(ArchaeologyJournalCatalog.load(server.getResourceManager()));
            loaded = true;
            LOGGER.info("已加载 {} 个考古战利品表到服务端目录。", catalog.size());
            logLoadedTables();
        } catch (Exception e) {
            LOGGER.error("加载考古战利品表目录失败。", e);
        }
    }

    // 使缓存失效（数据包重载后调用，下次 ensureLoaded 会重新加载）
    public static void invalidate() {
        catalog.clear();
        loaded = false;
    }

    private static void logLoadedTables() {
        for (TableDefinition table : catalog.values()) {
            ResourceLocation tableId = table.id();
            LOGGER.debug("考古战利品表: {} | key={} | fallback={} | display={}",
                    tableId,
                    LootTableNames.translationKey(tableId),
                    LootTableNames.fallbackName(tableId),
                    table.displayName().getString());
        }
    }

    // 获取缓存目录的只读视图
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        return Collections.unmodifiableMap(catalog);
    }
}
