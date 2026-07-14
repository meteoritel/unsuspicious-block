package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogLegacyAccess;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSession;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateJournalLogNotePayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UploadJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.world.JournalLogSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
        if (!session.isSeeded()) {
            // 降级路径：旧版客户端或无持久数据时，从客户端上传建立初始状态
            session.seedFromClient(payload.sessionId(), uploadedState);
            syncToPersistedState(player);
        } else {
            // 服务端权威模式：合并客户端数据，保留断线期间的条目
            session.mergeFromClient(uploadedState);
            syncToPersistedState(player);
        }
        // 合并后检查是否有任意单表条目数达到上限
        if (anyTableReachedThreshold(session.mirroredState())) {
            AchievementManager.grantIfNotAlready(player, ModAchievements.CACHE_ME_IF_YOU_CAN);
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
        // 记录该表 upsert 前的条目数，用于判断是否跨越上限阈值
        ArchaeologyJournalLogState.TableLogHistory table = session.mirroredState().getTable(tableId);
        int previousTableEntryCount = table != null ? table.getTotalEntryCount() : 0;
        if (!session.mirroredState().upsertEntry(tableId, entry)) {
            return;
        }
        syncToPersistedState(player);
        table = session.mirroredState().getTable(tableId);
        int currentTableEntryCount = table != null ? table.getTotalEntryCount() : 0;
        if (crossesCacheMeIfYouCanThreshold(previousTableEntryCount, currentTableEntryCount)) {
            AchievementManager.grantIfNotAlready(player, ModAchievements.CACHE_ME_IF_YOU_CAN);
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

    // 处理客户端发来的备注更新请求：定位条目并写入新备注，然后增量同步给客户端
    public static void handleUpdateNote(ServerPlayer player, UpdateJournalLogNotePayload payload) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded()) {
            return;
        }
        ArchaeologyJournalLogState.TableLogHistory table = session.mirroredState().getTable(payload.tableId());
        if (table == null) {
            return;
        }
        UUID entryId = payload.entryId();
        ExcavationLogEntry existing = null;
        for (ExcavationLogEntry entry : table.getEntries()) {
            if (entry.entryId().equals(entryId)) {
                existing = entry;
                break;
            }
        }
        if (existing == null) {
            return;
        }
        // 服务端二次校验：截断过长内容，移除控制字符
        String sanitized = sanitizeNote(payload.note());
        if (sanitized.equals(existing.note())) {
            return;
        }
        ExcavationLogEntry updated = existing.withNote(sanitized);
        // LinkedHashMap.put 已有 key 保留原插入位置，不会触发重排
        session.mirroredState().upsertEntry(payload.tableId(), updated);
        syncToPersistedState(player);
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player,
                SyncJournalLogPayload.upsertEntry(sessionId, session.nextSequence(), payload.tableId(), updated.toTag()));
    }

    // 备注文本清洗：去控制字符、限长 500
    private static String sanitizeNote(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < 500; i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c >= ' ') {
                sb.append(c);
            }
        }
        return sb.toString();
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
    public static void handleRequestSnapshot(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded()) {
            // 会话未 seeded 时无法下发快照，先尝试从持久数据恢复
            restoreAndSyncOnJoin(player);
            return;
        }
        syncLogSnapshot(player);
    }

    // 玩家加入时从 SavedData 恢复日志状态并下发快照（服务端权威模式）
    public static void restoreAndSyncOnJoin(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null) {
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            session.reset();
            syncLogSnapshot(player);
            return;
        }

        JournalLogSavedData savedData = JournalLogSavedData.get(server.overworld());
        // 一次性迁移：若玩家 NBT 中残留旧版日志 tag，消费并迁移到 SavedData（将于 1.5.0 移除）
        if (player instanceof ArchaeologyJournalLogLegacyAccess access) {
            CompoundTag legacy = access.unsuspiciousblock$consumeLegacyJournalLogTag();
            if (legacy != null) {
                ArchaeologyJournalLogState migrated = new ArchaeologyJournalLogState();
                migrated.readFrom(legacy);
                savedData.putForPlayer(player.getUUID(), migrated);
            }
        }

        // 从 SavedData 恢复日志到镜像状态
        ArchaeologyJournalLogState persisted = savedData.getForPlayer(player.getUUID());
        session.restoreFromPersisted(persisted);

        // 下发日志快照给客户端
        syncLogSnapshot(player);
    }

    // 判断单表条目数是否跨越了"缓存大师"成就门槛——即达到单表日志条目上限
    static boolean crossesCacheMeIfYouCanThreshold(int previousEntryCount, int currentEntryCount) {
        int threshold = Services.LOOT_TABLE_CONFIG.getMaxLogEntriesPerTable();
        return previousEntryCount < threshold
                && currentEntryCount >= threshold;
    }

    // 判断状态中是否有任意单表条目数已达到上限（用于快照合并后的成就检查）
    private static boolean anyTableReachedThreshold(ArchaeologyJournalLogState state) {
        int threshold = Services.LOOT_TABLE_CONFIG.getMaxLogEntriesPerTable();
        for (ArchaeologyJournalLogState.TableLogHistory table : state.getTables().values()) {
            if (table.getTotalEntryCount() >= threshold) {
                return true;
            }
        }
        return false;
    }

    // 获取玩家的日志同步会话（通过 mixin 接口）
    private static ArchaeologyJournalLogSyncSession getLogSession(ServerPlayer player) {
        if (player instanceof ArchaeologyJournalLogSyncSessionHolder holder) {
            return holder.unsuspiciousblock$getArchaeologyJournalLogSyncSession();
        }
        return null;
    }

    // 将 session 镜像状态同步回 SavedData 持久状态，确保世界保存时写入最新数据
    // 优化:session.mirroredState 与 SavedData 共享同一引用(由 restoreFromPersisted 建立),
    // 此处只需确保引用已注册并标记脏数据,无需全量深拷贝
    private static void syncToPersistedState(ServerPlayer player) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded()) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        JournalLogSavedData savedData = JournalLogSavedData.get(server.overworld());
        // putForPlayer 直接存储引用(同对象覆盖时无副作用),并触发 setDirty
        savedData.putForPlayer(player.getUUID(), session.mirroredState());
    }
}