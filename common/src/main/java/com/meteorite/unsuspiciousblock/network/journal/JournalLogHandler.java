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
import com.meteorite.unsuspiciousblock.network.payload.c2s.DeleteJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.JournalLogDeleteResultPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotEndPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotStartPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogTableChunkPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.world.JournalLogStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 日志同步处理器——管理日志条目的服务端持久化、增量同步、分片快照与删除。 */
public final class JournalLogHandler {

    private JournalLogHandler() {}

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
        markTableDirty(player, tableId);
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
        markTableDirty(player, tableId);
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
        ArchaeologyJournalLogState state = session.mirroredState();
        ArchaeologyJournalLogState backup = state.copy();
        List<ResourceLocation> removedTables = List.copyOf(state.getTables().keySet());
        state.clear();
        if (!persistClearAll(player, removedTables)) {
            state.copyFrom(backup);
            MinecraftServer server = player.getServer();
            if (server != null) {
                JournalLogStorage.markAllDirty(server, player.getUUID());
            }
            return;
        }
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
        ArchaeologyJournalLogState state = session.mirroredState();
        ArchaeologyJournalLogState.TableLogHistory history = state.getTable(tableId);
        if (history == null || !state.removeTable(tableId)) {
            return;
        }
        if (!persistTableNow(player, tableId)) {
            state.putTable(tableId, history);
            markTableDirty(player, tableId);
            return;
        }
        UUID sessionId = session.getSessionId();
        if (sessionId == null) {
            return;
        }
        long sequence = session.nextSequence();
        Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.clearTable(sessionId, sequence, tableId));
    }

    // 处理玩家自己的三级日志删除请求；不触碰目录解锁、物品计数或成就状态
    public static void handleDeleteLogs(ServerPlayer player, DeleteJournalLogPayload payload) {
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (session == null || !session.isSeeded() || session.getSessionId() == null) {
            sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.INVALID, 0);
            return;
        }

        ArchaeologyJournalLogState state = session.mirroredState();
        switch (payload.scope()) {
            case ENTRY -> deleteEntry(player, session, state, payload);
            case TABLE -> deleteTable(player, session, state, payload);
            case ALL -> deleteAll(player, session, state, payload);
        }
    }

    private static void deleteEntry(ServerPlayer player, ArchaeologyJournalLogSyncSession session,
                                    ArchaeologyJournalLogState state, DeleteJournalLogPayload payload) {
        ResourceLocation tableId = payload.tableId();
        UUID entryId = payload.entryId();
        ArchaeologyJournalLogState.TableLogHistory history = tableId != null ? state.getTable(tableId) : null;
        ExcavationLogEntry existing = history != null && entryId != null
                ? history.getEntries().stream().filter(entry -> entry.entryId().equals(entryId)).findFirst().orElse(null)
                : null;
        if (tableId == null || entryId == null || existing == null || !state.removeEntry(tableId, entryId)) {
            sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.NOT_FOUND, 0);
            return;
        }
        if (!persistTableNow(player, tableId)) {
            state.upsertEntry(tableId, existing);
            markTableDirty(player, tableId);
            sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.INVALID, 0);
            return;
        }
        Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.deleteEntry(
                session.getSessionId(), session.nextSequence(), tableId, entryId));
        sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.SUCCESS, 1);
    }

    private static void deleteTable(ServerPlayer player, ArchaeologyJournalLogSyncSession session,
                                    ArchaeologyJournalLogState state, DeleteJournalLogPayload payload) {
        ResourceLocation tableId = payload.tableId();
        ArchaeologyJournalLogState.TableLogHistory history = tableId != null ? state.getTable(tableId) : null;
        if (tableId == null || history == null) {
            sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.NOT_FOUND, 0);
            return;
        }
        int removed = history.getTotalEntryCount();
        state.removeTable(tableId);
        if (!persistTableNow(player, tableId)) {
            state.putTable(tableId, history);
            markTableDirty(player, tableId);
            sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.INVALID, 0);
            return;
        }
        Services.NETWORK.sendToPlayer(player, SyncJournalLogPayload.clearTable(
                session.getSessionId(), session.nextSequence(), tableId));
        sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.SUCCESS, removed);
    }

    private static void deleteAll(ServerPlayer player, ArchaeologyJournalLogSyncSession session,
                                  ArchaeologyJournalLogState state, DeleteJournalLogPayload payload) {
        if (state.isEmpty()) {
            sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.NOT_FOUND, 0);
            return;
        }
        ArchaeologyJournalLogState backup = state.copy();
        int removed = state.getTotalEntryCount();
        List<ResourceLocation> removedTables = List.copyOf(state.getTables().keySet());
        state.clear();
        if (!persistClearAll(player, removedTables)) {
            state.copyFrom(backup);
            MinecraftServer server = player.getServer();
            if (server != null) {
                JournalLogStorage.markAllDirty(server, player.getUUID());
            }
            sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.INVALID, 0);
            return;
        }
        Services.NETWORK.sendToPlayer(player,
                SyncJournalLogPayload.clearAll(session.getSessionId(), session.nextSequence()));
        sendDeleteResult(player, payload, JournalLogDeleteResultPayload.Result.SUCCESS, removed);
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
        markTableDirty(player, payload.tableId());
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

    /** 向玩家下发按战利品表压缩并切片的日志全量快照 */
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
            // 会话未 seeded 时无法下发快照，先尝试从持久数据恢复
            restoreAndSyncOnJoin(player);
            return;
        }
        syncLogSnapshot(player);
    }

    // 玩家加入时从 v2 分片存储恢复日志状态并下发快照（服务端权威模式）
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

        ArchaeologyJournalLogState persisted = JournalLogStorage.getPlayerState(server, player.getUUID());
        session.restoreFromPersisted(persisted);

        // 一次性迁移：若玩家 NBT 中残留旧版日志 tag，按 entryId 合并到 v2 分片
        if (player instanceof ArchaeologyJournalLogLegacyAccess access) {
            CompoundTag legacy = access.unsuspiciousblock$consumeLegacyJournalLogTag();
            if (legacy != null) {
                ArchaeologyJournalLogState migrated = new ArchaeologyJournalLogState();
                migrated.readFrom(legacy);
                session.mergeFromClient(migrated);
                JournalLogStorage.markAllDirty(server, player.getUUID());
            }
        }

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

    // 标记单表分片为 dirty；session 与存储缓存共享状态引用，无需深拷贝
    private static void markTableDirty(ServerPlayer player, ResourceLocation tableId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        JournalLogStorage.markTableDirty(server, player.getUUID(), tableId);
    }

    private static boolean persistTableNow(ServerPlayer player, ResourceLocation tableId) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            return JournalLogStorage.persistTableNow(server, player.getUUID(), tableId);
        }
        return false;
    }

    private static boolean persistClearAll(ServerPlayer player, Iterable<ResourceLocation> removedTables) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            return JournalLogStorage.persistClearAllNow(server, player.getUUID(), removedTables);
        }
        return false;
    }

    private record EncodedTable(ResourceLocation tableId, byte[] data) {
    }
}
