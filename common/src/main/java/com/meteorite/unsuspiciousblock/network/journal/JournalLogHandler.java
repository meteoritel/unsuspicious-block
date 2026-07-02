package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogStateHolder;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSession;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UploadJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 日志同步处理器——管理日志条目的同步、上传合并与全量快照下发 */
public final class JournalLogHandler {

    private JournalLogHandler() {}

    // 处理客户端上传的日志快照：
    // - session 未 seeded（降级路径）：seed 合并 → 检测成就 → 下发权威快照
    // - session 已 seeded（服务端权威模式）：合并客户端数据到服务端镜像，以保留断线期间的条目
    public static void handleUploadedLogSnapshot(ServerPlayer player, UploadJournalLogSnapshotPayload payload) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        ArchaeologyJournalLogState uploadedState = new ArchaeologyJournalLogState();
        uploadedState.readFrom(payload.state());
        int uploadedEntryCount = uploadedState.getTotalEntryCount();
        if (!session.isSeeded()) {
            // 降级路径：旧版客户端或无持久数据时，从客户端上传建立初始状态
            session.seedFromClient(payload.sessionId(), uploadedState);
            syncToPersistedState(player);
        } else {
            // 服务端权威模式：合并客户端数据，保留断线期间的条目
            session.mergeFromClient(uploadedState);
            syncToPersistedState(player);
        }
        int mirroredEntryCount = session.mirroredState().getTotalEntryCount();
        if (crossesCacheMeIfYouCanThreshold(uploadedEntryCount, mirroredEntryCount)) {
            AchievementManager.grant(player, ModAchievements.CACHE_ME_IF_YOU_CAN);
        }
        syncLogSnapshot(player);
    }

    // 记录表首次解锁事件（含触发类型），未 seed 时排队，已 seed 时增量同步
    public static void recordFirstUnlock(ServerPlayer player, ResourceLocation tableId, @Nullable LootSourceType lootSource,
                                         long gameTime, long dayTime) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }
        long normalizedGameTime = Math.max(0L, gameTime);
        long normalizedDayTime = Math.max(0L, dayTime);
        if (!session.isSeeded()) {
            session.queueFirstUnlockMeta(tableId, lootSource, normalizedGameTime, normalizedDayTime);
            return;
        }
        if (!session.mirroredState().setFirstUnlockMetaMin(tableId, lootSource, normalizedGameTime, normalizedDayTime)) {
            return;
        }
        syncToPersistedState(player);
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player,
                SyncJournalLogPayload.setFirstUnlockMeta(sessionId, session.nextSequence(), tableId,
                        lootSource, normalizedGameTime, normalizedDayTime));
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
        int previousTotalEntryCount = session.mirroredState().getTotalEntryCount();
        if (!session.mirroredState().upsertEntry(tableId, entry)) {
            return;
        }
        syncToPersistedState(player);
        int currentTotalEntryCount = session.mirroredState().getTotalEntryCount();
        if (crossesCacheMeIfYouCanThreshold(previousTotalEntryCount, currentTotalEntryCount)) {
            AchievementManager.grant(player, ModAchievements.CACHE_ME_IF_YOU_CAN);
        }
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player,
                SyncJournalLogPayload.upsertEntry(sessionId, session.nextSequence(), tableId, entry.toTag()));
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
        session.mirroredState().clear();
        syncToPersistedState(player);
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        long sequence = session.nextSequence();
        Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.clearAll(sessionId, sequence));
        syncLogSnapshot(player);
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
        if (!session.mirroredState().removeTable(tableId)) {
            return;
        }
        syncToPersistedState(player);
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        long sequence = session.nextSequence();
        Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.clearTable(sessionId, sequence, tableId));
        syncLogSnapshot(player);
    }

    /** 向玩家下发当前日志全量快照 */
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

    // 处理客户端主动请求的日志快照重同步（sessionId 不匹配恢复路径）
    public static void handleRequestSnapshot(ServerPlayer player,
                                             com.meteorite.unsuspiciousblock.network.payload.c2s.RequestJournalLogSnapshotPayload payload) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded()) {
            // 会话未 seeded 时无法下发快照，先尝试从持久数据恢复
            restoreAndSyncOnJoin(player);
            return;
        }
        syncLogSnapshot(player);
    }

    // 玩家加入时从 NBT 恢复日志状态并下发快照（服务端权威模式）
    public static void restoreAndSyncOnJoin(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }

        // 从玩家 NBT 持久数据恢复日志到镜像状态
        if (player instanceof ArchaeologyJournalLogStateHolder holder) {
            ArchaeologyJournalLogState persisted = holder.unsuspiciousblock$getArchaeologyJournalLogState();
            session.restoreFromPersisted(persisted);
        } else {
            session.reset();
        }

        // 下发日志快照给客户端
        syncLogSnapshot(player);
    }

    // 重置玩家的日志同步会话
    public static void resetLogSession(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session != null) {
            session.reset();
        }
    }

    // 判断是否跨越了"缓存大师"成就门槛——使用单表日志上限作为总条数门槛
    static boolean crossesCacheMeIfYouCanThreshold(int previousTotalEntryCount, int currentTotalEntryCount) {
        int threshold = Services.LOOT_TABLE_CONFIG.getMaxLogEntriesPerTable();
        return previousTotalEntryCount < threshold
                && currentTotalEntryCount >= threshold;
    }

    // 获取玩家的日志同步会话（通过 mixin 接口）
    private static ArchaeologyJournalLogSyncSession getLogSession(ServerPlayer player) {
        if (player instanceof ArchaeologyJournalLogSyncSessionHolder holder) {
            return holder.unsuspiciousblock$getArchaeologyJournalLogSyncSession();
        }
        return null;
    }

    // 将 session 镜像状态同步回玩家 NBT 持久状态，确保自动保存时写入最新数据
    private static void syncToPersistedState(ServerPlayer player) {
        if (player instanceof ArchaeologyJournalLogStateHolder holder) {
            ArchaeologyJournalLogSyncSession session = getLogSession(player);
            if (session != null && session.isSeeded()) {
                holder.unsuspiciousblock$getArchaeologyJournalLogState().copyFrom(session.mirroredState());
            }
        }
    }
}