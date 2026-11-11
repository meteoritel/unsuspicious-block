package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.core.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.core.journal.JournalPlayerDataService;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation.Status;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.journal.sync.ArchaeologyJournalLogSyncSession;
import com.meteorite.unsuspiciousblock.core.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateJournalLogNotePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.DeleteJournalLogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateJournalLogRetentionPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogDeleteResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogSnapshotEndPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogSnapshotStartPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogTableChunkPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.core.world.JournalLogStorage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

/** 日志网络校验、变更回执与快照分片；领域变更由 JournalLogMutation 实施。 */
public final class JournalLogHandler {

    private JournalLogHandler() {}

    // 记录表首次解锁事件（含触发类型），未 seed 时排队，已 seed 时增量同步
    public static void recordFirstUnlock(ServerPlayer player, ResourceLocation tableId, @Nullable LootSourceType lootSource,
                                         long gameTime, long dayTime) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        JournalLogMutation.FirstUnlock intent = new JournalLogMutation.FirstUnlock(tableId, lootSource, gameTime, dayTime);
        if (!session.isSeeded()) {
            session.queueFirstUnlockMeta(tableId, lootSource, intent.gameTime(), intent.dayTime());
            return;
        }
        JournalLogMutation.Result result = mutations(player, session).apply(intent);
        if (result.status() != Status.CHANGED) return;
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player,
                SyncJournalLogPayload.setFirstUnlockMeta(sessionId, session.nextSequence(), tableId,
                        lootSource, intent.gameTime(), intent.dayTime()));
    }

    // 插入或更新挖掘日志条目，未 seed 时排队，已 seed 时增量同步并检测成就
    public static void upsertExcavationEntry(ServerPlayer player, ResourceLocation tableId, ExcavationLogEntry entry) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        if (!session.isSeeded()) {
            session.queueUpsertEntry(tableId, entry);
            return;
        }
        JournalLogMutation.Result mutation = mutations(player, session).apply(new JournalLogMutation.Upsert(tableId, entry));
        ArchaeologyJournalLogState.EntryUpsertResult result = Objects.requireNonNull(mutation.upsert());
        if (result.blocked()) {
            ArchaeologyJournalLogState.TableLogHistory table = session.mirroredState().getTable(tableId);
            int limit = table != null ? table.getEffectiveRetentionLimit() : 0;
            int noted = table != null ? table.getNotedEntryCount() : 0;
            player.sendSystemMessage(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_retention.blocked",
                    tableId.toString(), noted, limit));
            return;
        }
        if (!result.changed()) {
            return;
        }
        if (JournalLogMutation.crossesAchievementThreshold(
                result.previousLifetimeCount(), result.currentLifetimeCount())) {
            AchievementManager.grantIfNotAlready(player, ModAchievements.CACHE_ME_IF_YOU_CAN);
        }
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        ArchaeologyJournalLogState.TableLogHistory table = session.mirroredState().getTable(tableId);
        if (table != null && (result.added() || !result.removedEntryIds().isEmpty())) {
            Services.NETWORK.sendToPlayer(player,
                    SyncJournalLogPayload.replaceTable(
                            sessionId, session.nextSequence(), tableId, table.toTag()));
        } else {
            Services.NETWORK.sendToPlayer(player,
                    SyncJournalLogPayload.upsertEntry(sessionId, session.nextSequence(), tableId, entry.toTag()));
        }
    }

    // 清空全部日志，未 seed 时排队，已 seed 时增量同步
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
        if (mutations(player, session).apply(new JournalLogMutation.ClearAll()).status() != Status.CHANGED) return;
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        long sequence = session.nextSequence();
        Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.clearAll(sessionId, sequence));
    }

    // 清空指定表的日志，未 seed 时排队，已 seed 时增量同步
    public static void clearLogsForTable(ServerPlayer player, ResourceLocation tableId) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        if (!session.isSeeded()) {
            session.queueClearTable(tableId);
            return;
        }
        if (mutations(player, session).apply(new JournalLogMutation.RemoveTable(tableId)).status() != Status.CHANGED) return;
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        long sequence = session.nextSequence();
        Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.clearTable(sessionId, sequence, tableId));
    }

    // 处理玩家自己的单条、批量、当前表或全部日志删除请求
    public static void handleDeleteLogs(ServerPlayer player, DeleteJournalLogPayload payload) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded() || session.getSessionId() == null) {
            sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.INVALID, 0);
            return;
        }

        ResourceLocation tableId = payload.tableId();
        JournalLogMutation.Intent intent;
        switch (payload.scope()) {
            case ENTRY -> {
                if (tableId == null || payload.entryId() == null) {
                    sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.NOT_FOUND, 0);
                    return;
                }
                intent = new JournalLogMutation.RemoveEntries(tableId, Set.of(payload.entryId()));
            }
            case BATCH -> {
                Set<UUID> entryIds = new LinkedHashSet<>(payload.entryIds());
                if (tableId == null || session.mirroredState().getTable(tableId) == null || entryIds.isEmpty()
                        || entryIds.size() > DeleteJournalLogPayload.MAX_BATCH_ENTRIES) {
                    sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.INVALID, 0);
                    return;
                }
                intent = new JournalLogMutation.RemoveEntries(tableId, entryIds);
            }
            case TABLE -> {
                if (tableId == null) {
                    sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.NOT_FOUND, 0);
                    return;
                }
                intent = new JournalLogMutation.ClearEntries(tableId);
            }
            case ALL -> intent = new JournalLogMutation.ClearAllEntries();
            default -> throw new IllegalStateException("Unknown deletion scope: " + payload.scope());
        }
        JournalLogMutation.Result result = mutations(player, session).apply(intent);
        if (result.status() != Status.CHANGED) {
            sendDeleteResult(player, payload, result.status() == Status.FAILED
                    ? JournalLogDeleteResultPayload.Result.INVALID : JournalLogDeleteResultPayload.Result.NOT_FOUND, 0);
            return;
        }
        switch (payload.scope()) {
            case ENTRY -> Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.deleteEntry(
                    session.getSessionId(), session.nextSequence(), tableId, payload.entryId()));
            case BATCH, TABLE -> syncTable(player, session, tableId, session.mirroredState().getTable(tableId));
            case ALL -> syncLogSnapshot(player);
        }
        sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.SUCCESS, result.removedCount());
    }

    // 更新当前表的自动保留上限，或按时间批量清理较旧的无备注日志
    public static void handleUpdateRetention(ServerPlayer player, UpdateJournalLogRetentionPayload payload) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded() || session.getSessionId() == null) {
            player.sendSystemMessage(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_retention.invalid"));
            return;
        }
        int globalLimit = ArchaeologyJournalLogState.TableLogHistory.getGlobalMaxEntries();
        if (payload.value() < (payload.action() == UpdateJournalLogRetentionPayload.Action.SET_LIMIT ? 1 : 0)
                || payload.value() > globalLimit) {
            player.sendSystemMessage(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_retention.invalid_range", globalLimit));
            return;
        }

        JournalLogMutation.Intent intent = payload.action() == UpdateJournalLogRetentionPayload.Action.SET_LIMIT
                ? new JournalLogMutation.SetRetention(payload.tableId(), payload.value())
                : new JournalLogMutation.Prune(payload.tableId(), payload.value());
        JournalLogMutation.Result result = mutations(player, session).apply(intent);
        if (result.status() == Status.FAILED) {
            player.sendSystemMessage(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_retention.invalid"));
            return;
        }
        int removed = result.removedCount();
        int retentionLimit = result.retentionLimit();
        ArchaeologyJournalLogState state = session.mirroredState();
        syncTable(player, session, payload.tableId(), state.getTable(payload.tableId()));
        String key = payload.action() == UpdateJournalLogRetentionPayload.Action.SET_LIMIT
                ? "screen.unsuspiciousblock.archaeology_journal.log_retention.limit_saved"
                : "screen.unsuspiciousblock.archaeology_journal.log_retention.pruned";
        player.sendSystemMessage(Component.translatable(key,
                payload.action() == UpdateJournalLogRetentionPayload.Action.SET_LIMIT ? retentionLimit : removed,
                removed));
    }

    private static void sendDeleteResult(ServerPlayer player, DeleteJournalLogPayload request,
                                         JournalLogDeleteResultPayload.Result result, int removedCount) {
        Services.NETWORK.sendToPlayer(player,
                new JournalLogDeleteResultPayload(request.requestId(), result, removedCount));
    }

    // 处理客户端发来的备注更新请求：定位条目并写入新备注，然后增量同步给客户端
    public static void handleUpdateNote(ServerPlayer player, UpdateJournalLogNotePayload payload) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded()) {
            return;
        }
        JournalLogMutation.Result result = mutations(player, session).apply(
                new JournalLogMutation.UpdateNote(payload.tableId(), payload.entryId(), payload.note()));
        UUID sessionId = session.getSessionId();
        if (result.status() != Status.CHANGED || sessionId == null) return;
        Services.NETWORK.sendToPlayer(player,
                SyncJournalLogPayload.upsertEntry(sessionId, session.nextSequence(), payload.tableId(), Objects.requireNonNull(result.entry()).toTag()));
    }

    // 向玩家下发按战利品表压缩并切片的日志全量快照
    public static void syncLogSnapshot(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded()) {
            return;
        }
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        List<EncodedTable> encodedTables = new ArrayList<>(session.mirroredState().getTables().size());
        for (Map.Entry<ResourceLocation, ArchaeologyJournalLogState.TableLogHistory> table
                : session.mirroredState().getTables().entrySet()) {
            encodedTables.add(new EncodedTable(table.getKey(), JournalLogSnapshotCodec.encode(table.getValue())));
        }

        UUID snapshotId = UUID.randomUUID();
        Services.NETWORK.sendToPlayer(player,
                new SyncJournalLogSnapshotStartPayload(
                        sessionId, session.lastSequence(), snapshotId, encodedTables.size()));
        for (EncodedTable table : encodedTables) {
            int chunkCount = Math.max(1, (table.data().length + JournalLogSnapshotCodec.MAX_CHUNK_BYTES - 1)
                    / JournalLogSnapshotCodec.MAX_CHUNK_BYTES);
            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int from = chunkIndex * JournalLogSnapshotCodec.MAX_CHUNK_BYTES;
                int to = Math.min(table.data().length, from + JournalLogSnapshotCodec.MAX_CHUNK_BYTES);
                Services.NETWORK.sendToPlayer(player,
                        new SyncJournalLogTableChunkPayload(sessionId, snapshotId, table.tableId(),
                                chunkIndex, chunkCount, Arrays.copyOfRange(table.data(), from, to)));
            }
        }
        Services.NETWORK.sendToPlayer(player,
                new SyncJournalLogSnapshotEndPayload(sessionId, snapshotId));
    }

    // 处理客户端主动请求的日志快照重同步（sessionId 不匹配恢复路径）
    public static void handleRequestSnapshot(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded()) {
            // 会话未 seeded 时通过统一入口恢复并迁移玩家数据
            JournalPlayerDataService.preparePlayerData(player);
        }
        syncLogSnapshot(player);
    }

    // 获取玩家的日志同步会话（通过 mixin 接口）
    private static ArchaeologyJournalLogSyncSession getLogSession(ServerPlayer player) {
        if (player instanceof ArchaeologyJournalLogSyncSessionHolder holder) {
            return holder.unsuspiciousblock$getArchaeologyJournalLogSyncSession();
        }
        return null;
    }

    private static JournalLogMutation mutations(ServerPlayer player, ArchaeologyJournalLogSyncSession session) {
        MinecraftServer server = player.getServer();
        return new JournalLogMutation(session.mirroredState(), tables -> {
            if (server != null) tables.forEach(tableId -> JournalLogStorage.markTableDirty(server, player.getUUID(), tableId));
        }, tables -> server != null && JournalLogStorage.persistTablesNow(server, player.getUUID(), tables));
    }

    private static void syncTable(ServerPlayer player, ArchaeologyJournalLogSyncSession session,
                                  ResourceLocation tableId,
                                  @Nullable ArchaeologyJournalLogState.TableLogHistory history) {
        if (history == null || session.getSessionId() == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.replaceTable(
                session.getSessionId(), session.nextSequence(), tableId, history.toTag()));
    }

    private record EncodedTable(ResourceLocation tableId, byte[] data) {
    }
}
