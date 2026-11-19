package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;


/**
 * 目录同步处理器——管理目录的哈希校验按需同步与全量下发
 */
public final class JournalCatalogHandler {
    private JournalCatalogHandler() {
    }

    // 向玩家发送目录哈希（用于按需同步比对）
    public static void syncCatalogHash(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        if (!server.isSameThread()) {
            server.execute(() -> syncCatalogHash(player));
            return;
        }
        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        String hash = ArchaeologyJournalServerCatalog.computeCatalogHash();
        if (hash.isEmpty()) return;

        Services.NETWORK.sendToPlayer(player, new SyncCatalogHashPayload(hash));
    }

    // 向玩家发送完整目录（客户端请求后调用）
    public static void syncFullCatalog(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        if (!server.isSameThread()) {
            server.execute(() -> syncFullCatalog(player));
            return;
        }
        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        var snapshot = ArchaeologyJournalServerCatalog.readSnapshot();
        Services.NETWORK.sendToPlayer(player, snapshot.payload());
    }

    // 处理客户端请求完整目录
    public static void handleRequestCatalog(ServerPlayer player) {
        syncFullCatalog(player);
    }
}
