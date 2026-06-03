package com.meteorite.unsuspiciousblock.journal.sync;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.TriggerType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 考古日志同步会话——管理服务端与客户端之间的日志状态同步。
 * 使用 "客户端先上传全量 → 服务端镜像 → 后续增量同步" 的两阶段策略。
 * 在客户端尚未上传前（seeded = false），暂存变更到 queuedMutations 队列，
 * 待 seedFromClient 后回放到 mirroredState。
 */
public final class ArchaeologyJournalLogSyncSession {
    @Nullable
    private UUID sessionId;
    private long nextSequence = 1L;
    private boolean seeded;
    private final ArchaeologyJournalLogState mirroredState = new ArchaeologyJournalLogState();
    private final ArrayList<QueuedMutation> queuedMutations = new ArrayList<>();

    // 重置会话：清空 sessionId、镜像状态与待定变更队列
    public void reset() {
        this.sessionId = null;
        this.nextSequence = 1L;
        this.seeded = false;
        this.mirroredState.clear();
        this.queuedMutations.clear();
    }

    // 从另一个会话复制全部数据（用于玩家重生后的状态迁移）
    public void copyFrom(ArchaeologyJournalLogSyncSession other) {
        this.sessionId = other.sessionId;
        this.nextSequence = other.nextSequence;
        this.seeded = other.seeded;
        this.mirroredState.copyFrom(other.mirroredState);
        this.queuedMutations.clear();
        this.queuedMutations.addAll(other.queuedMutations);
    }

    @Nullable
    // 获取当前同步会话 ID
    public UUID getSessionId() {
        return this.sessionId;
    }

    // 检查是否已收到客户端的基础日志数据（已播种）
    public boolean isSeeded() {
        return this.seeded;
    }

    // 获取下一个序列号（用于增量同步的顺序保证）
    public long nextSequence() {
        return this.nextSequence++;
    }

    // 获取已使用的最后一个序列号
    public long lastSequence() {
        return Math.max(0L, this.nextSequence - 1L);
    }

    // 获取服务端镜像的日志状态（可修改）
    public ArchaeologyJournalLogState mirroredState() {
        return this.mirroredState;
    }

    // 从客户端接收基础日志数据，建立同步基础（回放所有暂存的待定变更）
    public void seedFromClient(UUID sessionId, ArchaeologyJournalLogState clientState) {
        this.sessionId = sessionId;
        this.nextSequence = 1L;
        this.seeded = true;
        this.mirroredState.copyFrom(clientState);
        for (QueuedMutation mutation : this.queuedMutations) {
            mutation.apply(this.mirroredState);
        }
        this.queuedMutations.clear();
    }

    // 暂存首次解锁元数据变更（seeded 前排队，seeded 后直接应用）
    public void queueFirstUnlockMeta(ResourceLocation tableId, @Nullable TriggerType triggerType,
                                     long gameTime, long dayTime) {
        this.queuedMutations.add(QueuedMutation.firstUnlockMeta(tableId, triggerType, gameTime, dayTime));
    }

    // 暂存日志条目插入/更新变更
    public void queueUpsertEntry(ResourceLocation tableId, ExcavationLogEntry entry) {
        this.queuedMutations.add(QueuedMutation.upsertEntry(tableId, entry));
    }

    // 暂存全部清除变更
    public void queueClearAll() {
        this.queuedMutations.add(QueuedMutation.clearAll());
    }

    // 暂存清除指定表变更
    public void queueClearTable(ResourceLocation tableId) {
        this.queuedMutations.add(QueuedMutation.clearTable(tableId));
    }

    private record QueuedMutation(Type type,
                                  @Nullable ResourceLocation tableId,
                                  @Nullable TriggerType triggerType,
                                  long firstUnlockedGameTime,
                                  long firstUnlockedDayTime,
                                  @Nullable ExcavationLogEntry entry) {
        private static QueuedMutation firstUnlockMeta(ResourceLocation tableId, @Nullable TriggerType triggerType,
                                                      long gameTime, long dayTime) {
            return new QueuedMutation(Type.SET_FIRST_UNLOCK_META, tableId, triggerType, gameTime, dayTime, null);
        }

        private static QueuedMutation upsertEntry(ResourceLocation tableId, ExcavationLogEntry entry) {
            return new QueuedMutation(Type.UPSERT_ENTRY, tableId, null, 0L, 0L, entry);
        }

        private static QueuedMutation clearAll() {
            return new QueuedMutation(Type.CLEAR_ALL, null, null, 0L, 0L, null);
        }

        private static QueuedMutation clearTable(ResourceLocation tableId) {
            return new QueuedMutation(Type.CLEAR_TABLE, tableId, null, 0L, 0L, null);
        }

        private void apply(ArchaeologyJournalLogState state) {
            switch (this.type) {
                case SET_FIRST_UNLOCK_META -> {
                    if (this.tableId != null) {
                        state.setFirstUnlockMetaMin(this.tableId, this.triggerType,
                                this.firstUnlockedGameTime, this.firstUnlockedDayTime);
                    }
                }
                case UPSERT_ENTRY -> {
                    if (this.tableId != null && this.entry != null) {
                        state.upsertEntry(this.tableId, this.entry);
                    }
                }
                case CLEAR_ALL -> state.clear();
                case CLEAR_TABLE -> {
                    if (this.tableId != null) {
                        state.removeTable(this.tableId);
                    }
                }
            }
        }
    }

    private enum Type {
        SET_FIRST_UNLOCK_META,
        UPSERT_ENTRY,
        CLEAR_ALL,
        CLEAR_TABLE
    }
}
