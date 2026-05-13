package com.meteorite.unsuspiciousblock.network;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.TriggerType;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSession;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import com.meteorite.unsuspiciousblock.network.payload.*;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

public final class ArchaeologyJournalNetwork {
    private static final ResourceLocation CACHE_ME_IF_YOU_CAN_ADVANCEMENT =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "challenges/cache_me_if_you_can");
    private static final String CACHE_ME_IF_YOU_CAN_CRITERION = "reach_1024_entries";
    private static final int CACHE_ME_IF_YOU_CAN_THRESHOLD = 1024;

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
        int uploadedEntryCount = uploadedState.getTotalEntryCount();
        session.seedFromClient(payload.sessionId(), uploadedState);
        int mirroredEntryCount = session.mirroredState().getTotalEntryCount();
        if (crossesCacheMeIfYouCanThreshold(uploadedEntryCount, mirroredEntryCount)) {
            tryAwardCacheMeIfYouCan(player, mirroredEntryCount);
        }
        syncLogSnapshot(player);
    }

    public static void recordFirstUnlock(ServerPlayer player, ResourceLocation tableId, long gameTime, long dayTime) {
        recordFirstUnlock(player, tableId, null, gameTime, dayTime);
    }

    public static void recordFirstUnlock(ServerPlayer player, ResourceLocation tableId, @Nullable TriggerType triggerType,
                                         long gameTime, long dayTime) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        long normalizedGameTime = Math.max(0L, gameTime);
        long normalizedDayTime = Math.max(0L, dayTime);
        if (!session.isSeeded()) {
            session.queueFirstUnlockMeta(tableId, triggerType, normalizedGameTime, normalizedDayTime);
            return;
        }
        if (!session.mirroredState().setFirstUnlockMetaMin(tableId, triggerType, normalizedGameTime, normalizedDayTime)) {
            return;
        }
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player,
                SyncJournalLogPayload.setFirstUnlockMeta(sessionId, session.nextSequence(), tableId,
                        triggerType, normalizedGameTime, normalizedDayTime));
    }

    public static void recordExcavation(ServerPlayer player, ResourceLocation tableId, ExcavationLogEntry entry) {
        upsertExcavationEntry(player, tableId, entry);
    }

    public static void upsertExcavationEntry(ServerPlayer player, ResourceLocation tableId, ExcavationLogEntry entry) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        if (!session.isSeeded()) {
            session.queueUpsertEntry(tableId, entry);
            return;
        }
        int previousTotalEntryCount = session.mirroredState().getTotalEntryCount();
        if (!session.mirroredState().upsertEntry(tableId, entry)) {
            return;
        }
        int currentTotalEntryCount = session.mirroredState().getTotalEntryCount();
        if (crossesCacheMeIfYouCanThreshold(previousTotalEntryCount, currentTotalEntryCount)) {
            tryAwardCacheMeIfYouCan(player, currentTotalEntryCount);
        }
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player,
                SyncJournalLogPayload.upsertEntry(sessionId, session.nextSequence(), tableId, entry.toTag()));
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

    static boolean crossesCacheMeIfYouCanThreshold(int previousTotalEntryCount, int currentTotalEntryCount) {
        return previousTotalEntryCount < CACHE_ME_IF_YOU_CAN_THRESHOLD
                && currentTotalEntryCount >= CACHE_ME_IF_YOU_CAN_THRESHOLD;
    }

    private static void tryAwardCacheMeIfYouCan(ServerPlayer player, int totalEntryCount) {
        if (totalEntryCount < CACHE_ME_IF_YOU_CAN_THRESHOLD) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        AdvancementHolder advancement = server.getAdvancements().get(CACHE_ME_IF_YOU_CAN_ADVANCEMENT);
        if (advancement == null) {
            return;
        }
        player.getAdvancements().award(advancement, CACHE_ME_IF_YOU_CAN_CRITERION);
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
