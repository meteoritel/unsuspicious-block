package com.meteorite.unsuspiciousblock.journal;

import com.meteorite.unsuspiciousblock.api.ArchaeologyLootTableNames;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalCatalog;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalCatalog.TableDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 服务端懒加载目录：解析命中统一考古路径前缀规则的所有战利品表并缓存 */
public final class ArchaeologyJournalServerCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, TableDefinition> catalog = new LinkedHashMap<>();
    private static boolean loaded;

    private ArchaeologyJournalServerCatalog() {
    }

    // 确保目录已加载
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

    /** 使缓存失效（数据包重载后调用） */
    public static void invalidate() {
        catalog.clear();
        loaded = false;
    }

    private static void logLoadedTables() {
        for (TableDefinition table : catalog.values()) {
            ResourceLocation tableId = table.id();
            LOGGER.debug("考古战利品表: {} | key={} | fallback={} | display={}",
                    tableId,
                    ArchaeologyLootTableNames.translationKey(tableId),
                    ArchaeologyLootTableNames.fallbackName(tableId),
                    table.displayName().getString());
        }
    }

    /** 获取目录（只读） */
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        return Collections.unmodifiableMap(catalog);
    }
}
