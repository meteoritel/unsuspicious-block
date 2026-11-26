package com.meteorite.unsuspiciousblock.core.journal.sync;

import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation;
import com.meteorite.unsuspiciousblock.core.journal.state.JournalLogMutation.MergeAcquisition;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalLogPublication;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalTransferOutbox;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalPublicationTickets;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.DeleteJournalLogPayload;
import java.util.Set;

/**
 * 考古日志同步会话——管理服务端与客户端之间的日志状态同步。
 * 正常流程由服务端持久数据建立镜像并向客户端下发快照。
 * 未建立服务端镜像时（seeded = false）暂存变更到 queuedMutations 队列，
 * 旧数据降级迁移完成后再回放到 mirroredState。
 */
public final class ArchaeologyJournalLogSyncSession {
    private static final int DELETE_BATCH_TIMEOUT_TICKS = 200;
    private @Nullable DeleteBatch deleteBatch;
    private JournalTransferOutbox catalog = new JournalTransferOutbox();
    private JournalPublicationTickets catalogTickets = new JournalPublicationTickets();
    private JournalTransferOutbox progress = new JournalTransferOutbox();
    private JournalPublicationTickets progressTickets = new JournalPublicationTickets();
    private JournalLogPublication publication = new JournalLogPublication();
    private boolean seeded;
    // 镜像状态：正常流程下与 JournalLogStorage 中对应玩家的缓存状态为同一引用。
    // 修改后只需标记对应表分片为 dirty，无需深拷贝整个日志。
    private ArchaeologyJournalLogState mirroredState = new ArchaeologyJournalLogState();
    private final ArrayList<JournalLogMutation.Intent> queuedMutations = new ArrayList<>();

    // 从服务端持久数据恢复日志到镜像状态（替代旧版的 reset + 客户端上传模式）
    // 直接引用分片存储缓存中的状态对象，消除登录时的全量深拷贝。
    // 不变式：调用后 session.mirroredState 与 JournalLogStorage 缓存持有同一引用。
    public ReplayResult restoreFromPersisted(ArchaeologyJournalLogState persisted) {
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
        this.publication = new JournalLogPublication();
        this.deleteBatch = null;
        this.seeded = false;
        this.mirroredState = new ArchaeologyJournalLogState();
        this.queuedMutations.clear();
    }

    // 从另一个会话复制全部数据（用于玩家重生后的状态迁移）
    // 镜像状态采用引用接管而非深拷贝：旧 session 随旧玩家实体销毁，
    // 新 session 接管 mirroredState 引用，保持与 JournalLogStorage 缓存的同一性不变式。
    public void copyFrom(ArchaeologyJournalLogSyncSession other) {
        this.publication = other.publication;
        this.catalog = other.catalog;
        this.catalogTickets = other.catalogTickets;
        this.progress = other.progress;
        this.progressTickets = other.progressTickets;
        this.deleteBatch = other.deleteBatch;
        this.seeded = other.seeded;
        this.mirroredState = other.mirroredState;
        this.queuedMutations.clear();
        this.queuedMutations.addAll(other.queuedMutations);
    }

    /** 暂存首片的请求身份。 */
    public void startDeleteBatch(DeleteJournalLogPayload first, int tick) {
        this.deleteBatch = new DeleteBatch(first, tick, new LinkedHashSet<>());
    }

    /** 片号连续、身份和范围一致时接收。重复 UUID 使整个请求失效。 */
    public @Nullable Set<UUID> appendDeleteBatch(DeleteJournalLogPayload piece) {
        DeleteBatch batch = this.deleteBatch;
        if (batch == null || !batch.first.requestId().equals(piece.requestId())) {
            throw new IllegalArgumentException("没有匹配的考古日志删除批次");
        }
        Set<UUID> ids = batch.ids;
        if (!Objects.equals(batch.first.tableId(), piece.tableId())
                || batch.first.total() != piece.total() || ids.size() != piece.offset()) {
            this.deleteBatch = null;
            throw new IllegalArgumentException("考古日志删除批次的范围或片号不连续");
        }
        ids.addAll(piece.entryIds());
        if (ids.size() != piece.offset() + piece.entryIds().size()) {
            this.deleteBatch = null;
            throw new IllegalArgumentException("考古日志删除批次包含重复 UUID");
        }
        if (ids.size() < piece.total()) return null;
        this.deleteBatch = null;
        return ids;
    }

    /** 释放缺片请求，玩家拿到对应回执。 */
    public @Nullable DeleteJournalLogPayload expireDeleteBatch(int tick) {
        DeleteBatch batch = this.deleteBatch;
        if (batch == null || tick - batch.requestedAt < DELETE_BATCH_TIMEOUT_TICKS) return null;
        this.deleteBatch = null;
        return batch.first;
    }

    private record DeleteBatch(DeleteJournalLogPayload first, int requestedAt, Set<UUID> ids) {}

    /** 当前连接的发送顺序和活动历史查询。 */
    public JournalLogPublication publication() {
        return publication;
    }

    /** 同连接的目录发送窗口和请求票据。 */
    public JournalTransferOutbox catalog() { return catalog; }

    public JournalPublicationTickets catalogTickets() { return catalogTickets; }

    /** 同连接的进度发布窗口。 */
    public JournalTransferOutbox progress() { return progress; }

    /** 进度重同步票据随连接移交。 */
    public JournalPublicationTickets progressTickets() { return progressTickets; }

    public boolean isSeeded() {
        return this.seeded;
    }

    // 获取服务端镜像的日志状态（可修改）
    public ArchaeologyJournalLogState mirroredState() {
        return this.mirroredState;
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
