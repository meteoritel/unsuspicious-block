package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableTranslationStore;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * 服务端战利品配置生命周期协调器——加载世界配置并在规则变化后重建目录。
 * <p>
 * 同时也是**数据包重载脏标记**的汇聚点：平台侧的重载监听器只置标记（见
 * {@link #markDataPackReload()}），真正的重建仍在 tick 路径上做（D9）——在重载回调里
 * 同步跑全量构建会拖住重载本身，而两个平台的重载事件时序并不一致，只置标记能把差异压到最小。
 */
public final class ServerLootTableConfigManager {
    private static final int CHECK_INTERVAL_TICKS = 20;

    private static Snapshot snapshot;
    /** 数据包重载待处理标记；由平台重载监听器置位、由 {@link #tick} 在主线程消费。 */
    private static volatile boolean dataPackReloadPending;

    private ServerLootTableConfigManager() {
    }

    /**
     * 标记"数据包已重载，需要重建目录"。
     * <p>
     * 平台重载监听器在资源重载期间调用它：此时只置位，不做任何读取——重载尚未结束，
     * 此刻读资源会读到半成品。目录尚未加载时忽略（服务端启动时的首次资源加载不属于
     * "重载"，由启动路径自己构建目录）。
     */
    public static void markDataPackReload() {
        if (!ArchaeologyJournalServerCatalog.isLoaded()) {
            return;
        }
        dataPackReloadPending = true;
    }

    // 在首次构建目录前加载当前世界配置
    public static void start(MinecraftServer server) {
        Services.LOOT_TABLE_CONFIG.loadForServer(server);
        LootTableTranslationStore.load(server);
        snapshot = Snapshot.capture();
        dataPackReloadPending = false;
    }

    // /reload 时重新读取平台配置文件，并更新变化检测基线
    public static void reload(MinecraftServer server) {
        Services.LOOT_TABLE_CONFIG.loadForServer(server);
        LootTableTranslationStore.load(server);
        snapshot = Snapshot.capture();
        dataPackReloadPending = false;
    }

    // 低频检查配置变化与数据包重载标记；两者都会重建目录与概率任务
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        if (dataPackReloadPending) {
            // 先清标记再重建：重建过程本身不该被自己的重载事件再触发一次
            dataPackReloadPending = false;
            Constants.LOG.info("Detected a data pack reload; rebuilding archaeology catalog.");
            JournalCatalogHandler.onDataPackReload(server);
            return;
        }
        Snapshot current = Snapshot.capture();
        Snapshot previous = snapshot;
        snapshot = current;
        if (previous != null && (!previous.archaeologyPathPrefixes().equals(current.archaeologyPathPrefixes())
                || !previous.excludedLootTables().equals(current.excludedLootTables())
                || !previous.signatureExcludedComponents().equals(current.signatureExcludedComponents()))) {
            Constants.LOG.info("Server loot table tracking rules changed; rebuilding archaeology catalog.");
            JournalCatalogHandler.onDataPackReload(server);
        }
    }

    // 清除当前世界绑定，确保下一次启动重新加载对应世界配置
    public static void stop() {
        snapshot = null;
        dataPackReloadPending = false;
        LootTableTranslationStore.unload();
        // 同步清空名称注册与缺失收集，避免跨世界累积旧条目
        LootTableNames.clear();
        Services.LOOT_TABLE_CONFIG.unloadServer();
    }

    private record Snapshot(List<String> archaeologyPathPrefixes,
                            List<String> excludedLootTables,
                            List<String> signatureExcludedComponents,
                            int maxLogEntriesPerTable,
                            long trackingTimeoutTicks) {
        private static Snapshot capture() {
            return new Snapshot(
                    List.copyOf(Services.LOOT_TABLE_CONFIG.getArchaeologyPathPrefixes()),
                    List.copyOf(Services.LOOT_TABLE_CONFIG.getExcludedLootTables()),
                    List.copyOf(Services.LOOT_TABLE_CONFIG.getSignatureExcludedComponents()),
                    Services.LOOT_TABLE_CONFIG.getMaxLogEntriesPerTable(),
                    Services.LOOT_TABLE_CONFIG.getTrackingTimeoutTicks());
        }
    }
}
