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
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestJournalLogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateJournalLogNotePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.DeleteJournalLogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateJournalLogRetentionPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogEditResultPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogEditResultPayload.Operation;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogEditResultPayload.Result;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.core.world.JournalLogStorage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

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
        publish(session, result);
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
        applyExcavationMutation(player, session, tableId, new JournalLogMutation.Upsert(tableId, entry));
    }

    /** 保留个人条目已有的数量及备注，并沿用日志会话的顺序。 */
    public static void mergeAcquisition(ServerPlayer player, JournalLogMutation.MergeAcquisition acquisition) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) return;
        if (!session.isSeeded()) {
            session.queueAcquisition(acquisition);
            return;
        }
        applyExcavationMutation(player, session, acquisition.tableId(), acquisition);
    }

    private static void applyExcavationMutation(ServerPlayer player, ArchaeologyJournalLogSyncSession session,
                                                ResourceLocation tableId, JournalLogMutation.Intent intent) {
        JournalLogMutation.Result mutation = mutations(player, session).apply(intent);
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
        publish(session, mutation);
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
        publish(session, mutations(player, session).apply(new JournalLogMutation.ClearAll()));
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
        publish(session, mutations(player, session).apply(new JournalLogMutation.RemoveTable(tableId)));
    }

    /** 只在完整同请求批次到齐后执行一次删除。 */
    public static void handleDeleteLogs(ServerPlayer player, DeleteJournalLogPayload request) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        ResourceLocation table = request.tableId();
        if (session == null || !session.isSeeded()) {
            reply(player, request.requestId(), Operation.DELETE, table, Result.INVALID, 0, 0, 0);
            return;
        }
        Set<UUID> batch = Set.of();
        if (request.scope() == DeleteJournalLogPayload.Scope.BATCH) {
            if (request.offset() == 0) {
                session.startDeleteBatch(request, player.server.getTickCount());
            }
            try {
                batch = session.appendDeleteBatch(request);
            } catch (IllegalArgumentException exception) {
                reply(player, request.requestId(), Operation.DELETE, table, Result.INVALID, 0, 0, 0);
                return;
            }
            if (batch == null) return;
        }
        JournalLogMutation.Intent intent = switch (request.scope()) {
            case ENTRY -> new JournalLogMutation.RemoveEntries(table, Set.copyOf(request.entryIds()));
            case BATCH -> new JournalLogMutation.RemoveEntries(table, batch);
            case TABLE -> new JournalLogMutation.ClearEntries(table);
            case ALL -> new JournalLogMutation.ClearAllEntries();
        };
        applyEdit(player, session, request.requestId(), Operation.DELETE, table, intent);
    }

    /** 保存每表策略或按现有规则裁剪。 */
    public static void handleUpdateRetention(ServerPlayer player, UpdateJournalLogRetentionPayload request) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        ResourceLocation table = request.tableId();
        boolean setLimit = request.action() == UpdateJournalLogRetentionPayload.Action.SET_LIMIT;
        Operation op = setLimit ? Operation.RETENTION : Operation.PRUNE;
        int global = ArchaeologyJournalLogState.TableLogHistory.getGlobalMaxEntries();
        if (session == null || !session.isSeeded() || request.value() < (setLimit ? 1 : 0) || request.value() > global) {
            reply(player, request.requestId(), op, table, Result.INVALID, 0, 0, 0);
            return;
        }
        if (session.mirroredState().getTable(table) == null
                && (!setLimit || !ArchaeologyJournalServerCatalog.staticTables().containsKey(table))) {
            reply(player, request.requestId(), op, table, Result.NOT_FOUND, 0, 0, 0);
            return;
        }
        JournalLogMutation.Intent intent = setLimit ? new JournalLogMutation.SetRetention(table, request.value())
                : new JournalLogMutation.Prune(table, request.value());
        applyEdit(player, session, request.requestId(), op, table, intent);
    }

    /** 备注在服务端受理后立即同步，持久化仍由常规日志队列执行。 */
    public static void handleUpdateNote(ServerPlayer player, UpdateJournalLogNotePayload request) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded() || request.note().length() > JournalLogMutation.MAX_NOTE_LENGTH) {
            reply(player, request.requestId(), Operation.NOTE, request.tableId(), Result.INVALID, 0, 0, 0);
            return;
        }
        applyEdit(player, session, request.requestId(), Operation.NOTE, request.tableId(),
                new JournalLogMutation.UpdateNote(request.tableId(), request.entryId(), request.note()));
    }

    private static void applyEdit(ServerPlayer player, ArchaeologyJournalLogSyncSession session, UUID requestId,
                                  Operation operation, @Nullable ResourceLocation tableId, JournalLogMutation.Intent intent) {
        JournalLogMutation.Result result = mutations(player, session).apply(intent);
        long revision = publish(session, result);
        Result outcome = switch (result.status()) {
            case CHANGED -> Result.APPLIED;
            case UNCHANGED -> Result.UNCHANGED;
            case NOT_FOUND -> Result.NOT_FOUND;
            case FAILED -> Result.SAVE_FAILED;
            case BLOCKED -> Result.INVALID;
        };
        reply(player, requestId, operation, tableId, outcome, revision, result.removedCount(), result.retentionLimit());
    }

    private static void reply(ServerPlayer player, UUID id, Operation operation, @Nullable ResourceLocation table,
                              Result result, long revision, int removed, int retention) {
        Services.NETWORK.sendToPlayer(player, new JournalLogEditResultPayload(id, operation, table, result, revision,
                removed, retention, ArchaeologyJournalLogState.TableLogHistory.getGlobalMaxEntries()));
    }

    private static void expireBatch(ServerPlayer player, ArchaeologyJournalLogSyncSession session) {
        DeleteJournalLogPayload request = session.expireDeleteBatch(player.server.getTickCount());
        if (request != null) {
            reply(player, request.requestId(), Operation.DELETE, request.tableId(), Result.EXPIRED, 0, 0, 0);
        }
    }

    /** 登录首次发布摘要，并在同一连接中保留权威序列。 */
    public static void syncLogSummary(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session != null && session.isSeeded()) {
            session.publication().baseline(session.mirroredState(), 0);
        }
    }

    /** 同一连接的重同步、查看与退出入口。 */
    public static void handleRequest(ServerPlayer player, RequestJournalLogPayload request) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) return;
        if (!session.isSeeded()) JournalPlayerDataService.preparePlayerData(player);
        if (!session.isSeeded()) return;

        switch (request.action()) {
            case SUMMARY -> session.publication().baseline(session.mirroredState(), request.ticket());
            case HISTORY -> session.publication().select(session.mirroredState(), request.tableId(), request.ticket());
            case CLOSE -> session.publication().close(request.ticket());
        }
    }

    /** 每名玩家在当前服务端 tick 推进其日志发送窗口。 */
    public static void tick(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session != null) {
            expireBatch(player, session);
            session.publication().tick(player);
        }
    }

    private static long publish(ArchaeologyJournalLogSyncSession session, JournalLogMutation.Result result) {
        return result.status() == Status.CHANGED ? session.publication().publish(session.mirroredState(), result.change()) : 0L;
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

}
