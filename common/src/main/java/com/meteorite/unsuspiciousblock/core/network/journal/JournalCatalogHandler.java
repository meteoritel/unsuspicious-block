package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.journal.catalog.CatalogReadSnapshot;
import com.meteorite.unsuspiciousblock.core.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 按已发布目录身份应答，重载期间只公告可交付的新版本。 */
public final class JournalCatalogHandler {
    private JournalCatalogHandler() {}

    public static void announce(ServerPlayer player) {
        ArchaeologyJournalServerCatalog.ensureLoaded(player.server);
        announce(player, ArchaeologyJournalServerCatalog.readSnapshot());
    }

    public static void broadcast(MinecraftServer server, long generation) {
        if (generation != ArchaeologyJournalServerCatalog.currentGenerationId()) return;
        CatalogReadSnapshot snapshot = ArchaeologyJournalServerCatalog.readSnapshot();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) announce(player, snapshot);
    }

    private static void announce(ServerPlayer player, CatalogReadSnapshot snapshot) {
        if (Services.NETWORK.supportsJournal(player)) {
            Services.NETWORK.sendToPlayer(player, new SyncCatalogHashPayload(snapshot.identity()));
        }
    }

    public static void handleRequestCatalog(ServerPlayer player, RequestCatalogPayload request) {
        if (!(player instanceof ArchaeologyJournalLogSyncSessionHolder holder)) return;
        var session = holder.unsuspiciousblock$getArchaeologyJournalLogSyncSession();
        if (!session.catalogTickets().advance(request.ticket())) return;
        ArchaeologyJournalServerCatalog.ensureLoaded(player.server);
        CatalogReadSnapshot snapshot = ArchaeologyJournalServerCatalog.readSnapshot();
        if (!snapshot.identity().equals(request.expected())) {
            announce(player, snapshot);
            return;
        }
        snapshot.enqueue(session.catalog(), request.ticket());
    }

    public static void tick(ServerPlayer player) {
        if (player instanceof ArchaeologyJournalLogSyncSessionHolder holder) {
            holder.unsuspiciousblock$getArchaeologyJournalLogSyncSession().catalog().tick(player);
        }
    }
}
