package com.meteorite.unsuspiciousblock.network;

import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSession;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

public final class ArchaeologyJournalNetwork {

    private ArchaeologyJournalNetwork() {
    }

    public static void syncOnJoin(ServerPlayer player) {
        resetLogSession(player);
        syncCatalog(player);
        syncState(player);
    }

    public static void syncCatalog(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getCatalog();
        if (catalog.isEmpty()) return;

        Services.NETWORK.sendToPlayer(player, new SyncArchaeologyCatalogPayload(catalog));
    }

    public static void syncState(ServerPlayer player) {
        if (!(player instanceof ArchaeologyJournalStateHolder holder)) return;

        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        Services.NETWORK.sendToPlayer(player, new SyncJournalStatePayload(state.toTag()));
    }

    public static void handleUploadedLogSnapshot(ServerPlayer player, UploadJournalLogSnapshotPayload payload) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        ArchaeologyJournalLogState uploadedState = new ArchaeologyJournalLogState();
        uploadedState.readFrom(payload.state());
        session.seedFromClient(payload.sessionId(), uploadedState);
        syncLogSnapshot(player);
    }

    public static void recordFirstUnlock(ServerPlayer player, ResourceLocation tableId, long gameTime, long dayTime) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        long normalizedGameTime = Math.max(0L, gameTime);
        long normalizedDayTime = Math.max(0L, dayTime);
        if (!session.isSeeded()) {
            session.queueFirstUnlock(tableId, normalizedGameTime, normalizedDayTime);
            return;
        }
        if (!session.mirroredState().setFirstUnlockedTimeMin(tableId, normalizedGameTime, normalizedDayTime)) {
            return;
        }
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player,
                SyncJournalLogPayload.firstUnlock(sessionId, session.nextSequence(), tableId,
                        normalizedGameTime, normalizedDayTime));
    }

    public static void recordExcavation(ServerPlayer player, ResourceLocation tableId, ExcavationLogEntry entry) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        if (!session.isSeeded()) {
            session.queueExcavation(tableId, entry);
            return;
        }
        session.mirroredState().appendEntry(tableId, entry);
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player,
                SyncJournalLogPayload.excavation(sessionId, session.nextSequence(), tableId,
                        entry.itemId(), entry.structureId(), entry.biomeId(), entry.pos(), entry.gameTime(), entry.dayTime()));
    }

    public static void clearLogs(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        if (!session.isSeeded()) {
            session.queueClearAll();
            return;
        }
        if (session.mirroredState().isEmpty()) {
            return;
        }
        session.mirroredState().clear();
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        long sequence = session.nextSequence();
        Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.clearAll(sessionId, sequence));
        syncLogSnapshot(player);
    }

    public static void clearLogsForTable(ServerPlayer player, ResourceLocation tableId) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        if (!session.isSeeded()) {
            session.queueClearTable(tableId);
            return;
        }
        if (!session.mirroredState().removeTable(tableId)) {
            return;
        }
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        long sequence = session.nextSequence();
        Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.clearTable(sessionId, sequence, tableId));
        syncLogSnapshot(player);
    }

    public static void syncLogSnapshot(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded()) {
            return;
        }
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player,
                new SyncJournalLogSnapshotPayload(sessionId, session.lastSequence(), session.mirroredState().toTag()));
    }

    public static void onDataPackReload(MinecraftServer server) {
        ArchaeologyJournalServerCatalog.invalidate();
        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncCatalog(player);
        }
    }

    private static void resetLogSession(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session != null) {
            session.reset();
        }
    }

    private static ArchaeologyJournalLogSyncSession getLogSession(ServerPlayer player) {
        if (player instanceof ArchaeologyJournalLogSyncSessionHolder holder) {
            return holder.unsuspiciousblock$getArchaeologyJournalLogSyncSession();
        }
        return null;
    }
}
