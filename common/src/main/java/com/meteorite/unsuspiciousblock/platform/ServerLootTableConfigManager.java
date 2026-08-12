package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableTranslationStore;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * 服务端战利品配置生命周期协调器——加载世界配置并在规则变化后重建目录。
 */
public final class ServerLootTableConfigManager {
    private static final int CHECK_INTERVAL_TICKS = 20;

    private static Snapshot snapshot;

    private ServerLootTableConfigManager() {
    }

    // 在首次构建目录前加载当前世界配置
    public static void start(MinecraftServer server) {
        Services.LOOT_TABLE_CONFIG.loadForServer(server);
        LootTableTranslationStore.load(server);
        snapshot = Snapshot.capture();
    }

    // /reload 时重新读取平台配置文件，并更新变化检测基线
    public static void reload(MinecraftServer server) {
        Services.LOOT_TABLE_CONFIG.loadForServer(server);
        LootTableTranslationStore.load(server);
        snapshot = Snapshot.capture();
    }

    // 低频检查配置变化；只有追踪规则变化需要重建目录和概率任务
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        Snapshot current = Snapshot.capture();
        Snapshot previous = snapshot;
        snapshot = current;
        if (previous != null && (!previous.archaeologyPathPrefixes().equals(current.archaeologyPathPrefixes())
                || !previous.excludedLootTables().equals(current.excludedLootTables()))) {
            Constants.LOG.info("Server loot table tracking rules changed; rebuilding archaeology catalog.");
            JournalCatalogHandler.onDataPackReload(server);
        }
    }

    // 清除当前世界绑定，确保下一次启动重新加载对应世界配置
    public static void stop() {
        snapshot = null;
        LootTableTranslationStore.unload();
        Services.LOOT_TABLE_CONFIG.unloadServer();
    }

    private record Snapshot(List<String> archaeologyPathPrefixes,
                            List<String> excludedLootTables,
                            int maxLogEntriesPerTable,
                            long trackingTimeoutTicks) {
        private static Snapshot capture() {
            return new Snapshot(
                    List.copyOf(Services.LOOT_TABLE_CONFIG.getArchaeologyPathPrefixes()),
                    List.copyOf(Services.LOOT_TABLE_CONFIG.getExcludedLootTables()),
                    Services.LOOT_TABLE_CONFIG.getMaxLogEntriesPerTable(),
                    Services.LOOT_TABLE_CONFIG.getTrackingTimeoutTicks());
        }
    }
}
