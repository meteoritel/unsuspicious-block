package com.meteorite.unsuspiciousblock.core.journal.sync;

import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation.MergeAcquisition;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 考古日志同步会话——管理服务端与客户端之间的日志状态同步。
 * 正常流程由服务端持久数据建立镜像并向客户端下发快照。
 * 未建立服务端镜像时（seeded = false）暂存变更到 queuedMutations 队列，
 * 旧数据降级迁移完成后再回放到 mirroredState。
 */
public final class ArchaeologyJournalLogSyncSession {
    @Nullable
    private UUID sessionId;
    private long nextSequence = 1L;
    private boolean seeded;
    // 镜像状态：正常流程下与 JournalLogStorage 中对应玩家的缓存状态为同一引用。
    // 修改后只需标记对应表分片为 dirty，无需深拷贝整个日志。
    private ArchaeologyJournalLogState mirroredState = new ArchaeologyJournalLogState();
    private final ArrayList<JournalLogMutation.Intent> queuedMutations = new ArrayList<>();

    // 从服务端持久数据恢复日志到镜像状态（替代旧版的 reset + 客户端上传模式）
    // 直接引用分片存储缓存中的状态对象，消除登录时的全量深拷贝。
    // 不变式：调用后 session.mirroredState 与 JournalLogStorage 缓存持有同一引用。
    public ReplayResult restoreFromPersisted(ArchaeologyJournalLogState persisted) {
        this.sessionId = UUID.randomUUID();
        this.nextSequence = 1L;
        this.seeded = true;
        this.mirroredState = persisted;
        Set<ResourceLocation> affectedTables = new HashSet<>();
        boolean clearAll = false;
        boolean hadQueuedMutations = !this.queuedMutations.isEmpty();
        for (JournalLogMutation.Intent mutation : this.queuedMutations) {
            clearAll |= mutation instanceof JournalLogMutation.ClearAll;
            affectedTables.addAll(JournalLogMutation.replay(this.mirroredState, mutation));
        }
        this.queuedMutations.clear();
        return new ReplayResult(Set.copyOf(affectedTables), clearAll, hadQueuedMutations);
    }

    // 重置会话：清空 sessionId、镜像状态与待定变更队列
    // 保留作为服务端存储不可用时的降级路径。
    // 使用新空对象替换，避免误清空 JournalLogStorage 仍持有的缓存状态。
    public void reset() {
        this.sessionId = null;
        this.nextSequence = 1L;
        this.seeded = false;
        this.mirroredState = new ArchaeologyJournalLogState();
        this.queuedMutations.clear();
    }

    // 从另一个会话复制全部数据（用于玩家重生后的状态迁移）
    // 镜像状态采用引用接管而非深拷贝：旧 session 随旧玩家实体销毁，
    // 新 session 接管 mirroredState 引用，保持与 JournalLogStorage 缓存的同一性不变式。
    public void copyFrom(ArchaeologyJournalLogSyncSession other) {
        this.sessionId = other.sessionId;
        this.nextSequence = other.nextSequence;
        this.seeded = other.seeded;
        this.mirroredState = other.mirroredState;
        this.queuedMutations.clear();
        this.queuedMutations.addAll(other.queuedMutations);
    }

    @Nullable
    public UUID getSessionId() {
        return this.sessionId;
    }

    // 检查服务端持久化状态是否已建立为同步基线
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

    // 兼容旧流程：从客户端状态建立同步基础并回放暂存变更
    public void seedFromClient(UUID sessionId, ArchaeologyJournalLogState clientState) {
        this.sessionId = sessionId;
        this.nextSequence = 1L;
        this.seeded = true;
        this.mirroredState.copyFrom(clientState);
        for (JournalLogMutation.Intent mutation : this.queuedMutations) {
            JournalLogMutation.replay(this.mirroredState, mutation);
        }
        this.queuedMutations.clear();
    }

    // 将客户端上传的数据补充到服务端镜像状态；相同 entryId 始终保留服务端版本
    public void mergeFromClient(ArchaeologyJournalLogState clientState) {
        for (var entry : clientState.getTables().entrySet()) {
            ResourceLocation tableId = entry.getKey();
            ArchaeologyJournalLogState.TableLogHistory clientHistory = entry.getValue();
            // 合并首次解锁时间（取最小值）
            if (clientHistory.getFirstUnlockedGameTime() != null) {
                this.mirroredState.setFirstUnlockMetaMin(tableId,
                        clientHistory.getFirstUnlockLootSource(),
                        clientHistory.getFirstUnlockedGameTime(),
                        clientHistory.getFirstUnlockedDayTime() != null
                                ? clientHistory.getFirstUnlockedDayTime()
                                : clientHistory.getFirstUnlockedGameTime());
            }
            // 合并发掘条目
            for (ExcavationLogEntry logEntry : clientHistory.getEntries()) {
                if (!this.mirroredState.containsEntry(tableId, logEntry.entryId())) {
                    this.mirroredState.upsertEntry(tableId, logEntry);
                }
            }
        }
    }

    // 暂存首次解锁元数据变更（seeded 前排队，seeded 后直接应用）
    public void queueFirstUnlockMeta(ResourceLocation tableId, @Nullable LootSourceType lootSource,
                                     long gameTime, long dayTime) {
        this.queuedMutations.add(new JournalLogMutation.FirstUnlock(tableId, lootSource, gameTime, dayTime));
    }

    // 暂存日志条目插入/更新变更
    public void queueUpsertEntry(ResourceLocation tableId, ExcavationLogEntry entry) {
        this.queuedMutations.add(new JournalLogMutation.Upsert(tableId, entry));
    }

    /** 等待读到个人日志后再合并当时收到的领取增量。 */
    public void queueAcquisition(MergeAcquisition acquisition) {
        this.queuedMutations.add(acquisition);
    }

    // 暂存全部清除变更
    public void queueClearAll() {
        this.queuedMutations.add(new JournalLogMutation.ClearAll());
    }

    // 暂存清除指定表变更
    public void queueClearTable(ResourceLocation tableId) {
        this.queuedMutations.add(new JournalLogMutation.RemoveTable(tableId));
    }

    /** 服务端恢复持久化状态时回放的未 seeded 变更及其持久化影响范围。 */
    public record ReplayResult(Set<ResourceLocation> affectedTables,
                               boolean clearAll,
                               boolean hadQueuedMutations) {
    }
}
