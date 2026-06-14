package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** 目录同步处理器——管理目录的哈希校验按需同步与全量下发 */
public final class JournalCatalogHandler {
    private JournalCatalogHandler() {}

    // 向玩家发送目录哈希（用于按需同步比对）
    public static void syncCatalogHash(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        String hash = ArchaeologyJournalServerCatalog.computeCatalogHash();
        if (hash.isEmpty()) return;

        Services.NETWORK.sendToPlayer(player, new SyncCatalogHashPayload(hash));
    }

    // 向玩家发送完整目录（客户端请求后调用）
    public static void syncFullCatalog(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getCatalog();
        if (catalog.isEmpty()) return;

        Services.NETWORK.sendToPlayer(player, new SyncArchaeologyCatalogPayload(catalog));
    }

    // 处理客户端请求完整目录
    public static void handleRequestCatalog(ServerPlayer player, RequestCatalogPayload payload) {
        syncFullCatalog(player);
    }

    /** 数据包重载时使缓存失效并重新同步目录哈希给所有在线玩家 */
    public static void onDataPackReload(MinecraftServer server) {
        ArchaeologyJournalServerCatalog.invalidate();
        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncCatalogHash(player);
        }
    }
}