package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** 目录同步处理器 */
public final class JournalCatalogHandler {
    private JournalCatalogHandler() {}

    // 向玩家同步全量战利品目录
    public static void syncCatalog(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getCatalog();
        if (catalog.isEmpty()) return;

        Services.NETWORK.sendToPlayer(player, new SyncArchaeologyCatalogPayload(catalog));
    }

    /** 数据包重载时使缓存失效并重新同步目录给所有在线玩家 */
    public static void onDataPackReload(MinecraftServer server) {
        ArchaeologyJournalServerCatalog.invalidate();
        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncCatalog(player);
        }
    }
}