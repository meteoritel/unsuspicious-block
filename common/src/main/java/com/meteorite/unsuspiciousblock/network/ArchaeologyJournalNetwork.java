package com.meteorite.unsuspiciousblock.network;

import com.meteorite.unsuspiciousblock.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.TriggerType;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSession;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateReaderScanLevelPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UploadJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.item.SuspiciousReaderItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * 考古日记网络同步中枢。
 * 负责日记目录、状态、日志的快照/增量同步，以及客户端日志上传合并。
 */
public final class ArchaeologyJournalNetwork {
    private static final int CACHE_ME_IF_YOU_CAN_THRESHOLD = 1024;

    private ArchaeologyJournalNetwork() {
    }

    // 玩家加入时全量同步：重置日志会话 → 下发目录 → 下发状态
    public static void syncOnJoin(ServerPlayer player) {
        resetLogSession(player);
        syncCatalog(player);
        syncState(player);
    }

    // 向玩家同步全量战利品目录
    public static void syncCatalog(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getCatalog();
        if (catalog.isEmpty()) return;

        Services.NETWORK.sendToPlayer(player, new SyncArchaeologyCatalogPayload(catalog));
    }

    // 向玩家同步考古日记状态（进度与解锁信息）
    public static void syncState(ServerPlayer player) {
        if (!(player instanceof ArchaeologyJournalStateHolder holder)) return;

        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        Services.NETWORK.sendToPlayer(player, new SyncJournalStatePayload(state.toTag()));
    }

    // 处理客户端上传的日志快照：seed 合并 → 检测成就 → 下发权威快照
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
            AchievementManager.grant(player, ModAchievements.CACHE_ME_IF_YOU_CAN);
        }
        syncLogSnapshot(player);
    }

    // 记录表首次解锁事件（含触发类型），未 seed 时排队，已 seed 时增量同步
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

    /** 数据包重载时使缓存失效并重新同步目录给所有在线玩家 */
    public static void onDataPackReload(MinecraftServer server) {
        ArchaeologyJournalServerCatalog.invalidate();
        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncCatalog(player);
        }
    }

    // 判断是否跨越了"缓存大师"成就门槛
    static boolean crossesCacheMeIfYouCanThreshold(int previousTotalEntryCount, int currentTotalEntryCount) {
        return previousTotalEntryCount < CACHE_ME_IF_YOU_CAN_THRESHOLD
                && currentTotalEntryCount >= CACHE_ME_IF_YOU_CAN_THRESHOLD;
    }

    // 处理客户端同步的扫描仪扫描等级切换
    public static void handleUpdateReaderScanLevel(UpdateReaderScanLevelPayload payload, ServerPlayer player) {
        var stack = player.getMainHandItem();
        if (stack.getItem() == ModItems.SUSPICIOUS_READER) {
            SuspiciousReaderItem.setScanLevel(stack, payload.newLevel());
            return;
        }
        stack = player.getOffhandItem();
        if (stack.getItem() == ModItems.SUSPICIOUS_READER) {
            SuspiciousReaderItem.setScanLevel(stack, payload.newLevel());
        }
    }

    // 重置玩家的日志同步会话
    private static void resetLogSession(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session != null) {
            session.reset();
        }
    }

    // 获取玩家的日志同步会话（通过 mixin 接口）
    private static ArchaeologyJournalLogSyncSession getLogSession(ServerPlayer player) {
        if (player instanceof ArchaeologyJournalLogSyncSessionHolder holder) {
            return holder.unsuspiciousblock$getArchaeologyJournalLogSyncSession();
        }
        return null;
    }
}
