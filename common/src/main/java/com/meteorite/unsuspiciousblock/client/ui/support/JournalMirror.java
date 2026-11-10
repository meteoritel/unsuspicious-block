package com.meteorite.unsuspiciousblock.client.ui.support;

import net.minecraft.resources.ResourceLocation;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalLogSnapshotCodec;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogSnapshotEndPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogSnapshotStartPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncJournalLogTableChunkPayload;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import java.util.function.LongSupplier;

/** 单个连接的日志会话迁移；时间与重同步动作由宿主注入。 */
public final class JournalMirror {
    private final ResyncPolicy resyncPolicy;
    private final Runnable requestSnapshot;
    private ArchaeologyJournalLogState logState = new ArchaeologyJournalLogState();
    private @Nullable UUID currentSessionId;
    private @Nullable PendingSnapshot pendingSnapshot;
    private final ArrayList<SyncJournalLogPayload> pendingIncrementals = new ArrayList<>();
    private long lastAppliedSequence;
    private long revision;
    private boolean dirty;
    private boolean initialized;

    public JournalMirror(LongSupplier clock, Runnable requestSnapshot) {
        this.resyncPolicy = new ResyncPolicy(clock);
        this.requestSnapshot = requestSnapshot;
    }

    public ArchaeologyJournalLogState current() {
        return logState.copy();
    }

    public long revision() {
        return revision;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markSaved() {
        dirty = false;
    }

    public void restore(ArchaeologyJournalLogState state) {
        logState = state;
        initialized = true;
        revision++;
    }

    public void advance() {
        if (!initialized) {
            return;
        }
        tryCommitSnapshot();
        flushPending();
    }

    // 开始接收服务端权威分片快照，旧状态在完整校验前继续可用
    public void beginSnapshot(SyncJournalLogSnapshotStartPayload payload) {
        if (payload.sequence() < lastAppliedSequence) {
            return;
        }
        pendingSnapshot = new PendingSnapshot(payload.sessionId(), payload.sequence(),
                payload.snapshotId(), payload.tableCount());
        pendingIncrementals.clear();
    }

    // 接收单表压缩数据的一片；完整单表立即解码进待提交状态
    public void acceptChunk(SyncJournalLogTableChunkPayload payload) {
        PendingSnapshot snapshot = pendingSnapshot;
        if (snapshot == null || snapshot.mismatches(payload.sessionId(), payload.snapshotId())) {
            requestLogSnapshotWithCooldown();
            return;
        }
        try {
            snapshot.add(payload);
        } catch (RuntimeException exception) {
            Constants.LOG.warn("接收考古日志快照分片失败，正在请求重同步", exception);
            requestLogSnapshotWithCooldown();
        }
    }

    // 标记分片快照传输结束，完整时原子提交
    public void endSnapshot(SyncJournalLogSnapshotEndPayload payload) {
        PendingSnapshot snapshot = pendingSnapshot;
        if (snapshot == null || snapshot.mismatches(payload.sessionId(), payload.snapshotId())) {
            requestLogSnapshotWithCooldown();
            return;
        }
        snapshot.ended = true;
        tryCommitSnapshot();
    }

    // 接受服务端下发的增量更新
    public void applyUpdate(SyncJournalLogPayload payload) {
        if (!initialized) {
            pendingIncrementals.add(payload);
            return;
        }
        if (pendingSnapshot != null) {
            if (pendingSnapshot.sessionId.equals(payload.sessionId())) {
                pendingIncrementals.add(payload);
            } else {
                requestLogSnapshotWithCooldown();
            }
            return;
        }
        flushPending();
        // sessionId 不匹配（currentSessionId 非空且与服务端不一致）→ 会话失效，请求快照重同步
        if (currentSessionId != null && !currentSessionId.equals(payload.sessionId())) {
            requestLogSnapshotWithCooldown();
            return;
        }
        // sequence 过期或重复 → 静默跳过（正常情况）
        if (payload.sequence() <= lastAppliedSequence) {
            return;
        }

        boolean changed = applyIncremental(logState, payload);
        if (changed) {
            currentSessionId = payload.sessionId();
            lastAppliedSequence = payload.sequence();
            revision++;
            dirty = true;
        } else {
            if (currentSessionId == null) {
                currentSessionId = payload.sessionId();
            }
            lastAppliedSequence = Math.max(lastAppliedSequence, payload.sequence());
        }
    }

    // 请求日志快照重同步（带限流），用于 sessionId 不匹配时的恢复路径
    // 清除本地 sessionId 后向服务端请求全新快照，使客户端重新对齐服务端权威状态
    private void requestLogSnapshotWithCooldown() {
        if (!resyncPolicy.tryRequest()) {
            return;
        }
        // 清除旧会话状态，允许后续快照重建基线
        currentSessionId = null;
        lastAppliedSequence = 0L;
        pendingSnapshot = null;
        pendingIncrementals.clear();
        requestSnapshot.run();
    }

    private void flushPending() {
        if (pendingSnapshot != null || pendingIncrementals.isEmpty()) {
            return;
        }

        // 处理排队的增量更新（卫语句已保证此时队列非空）
        ArrayList<SyncJournalLogPayload> sortedIncrementals = new ArrayList<>(pendingIncrementals);
        sortedIncrementals.sort(Comparator.comparingLong(SyncJournalLogPayload::sequence));
        boolean anyChanged = false;
        for (SyncJournalLogPayload payload : sortedIncrementals) {
            if (!shouldAcceptUpdate(currentSessionId, lastAppliedSequence, payload)) {
                continue;
            }
            if (applyIncremental(logState, payload)) {
                currentSessionId = payload.sessionId();
                lastAppliedSequence = payload.sequence();
                anyChanged = true;
            } else {
                if (currentSessionId == null) {
                    currentSessionId = payload.sessionId();
                }
                lastAppliedSequence = Math.max(lastAppliedSequence, payload.sequence());
            }
        }
        if (anyChanged) {
            revision++;
            dirty = true;
        }
        pendingIncrementals.clear();
    }

    private void tryCommitSnapshot() {
        PendingSnapshot snapshot = pendingSnapshot;
        if (snapshot == null || !snapshot.ended || !initialized) {
            return;
        }
        if (!snapshot.isComplete()) {
            Constants.LOG.warn("考古日志快照不完整: 收到 {}/{} 张表",
                    snapshot.completedTables.size(), snapshot.expectedTableCount);
            requestLogSnapshotWithCooldown();
            return;
        }
        logState = snapshot.state;
        currentSessionId = snapshot.sessionId;
        lastAppliedSequence = snapshot.sequence;
        pendingSnapshot = null;
        revision++;
        dirty = true;
        flushPending();
    }

    private static boolean shouldAcceptUpdate(@Nullable UUID sessionId, long lastSequence,
                                              SyncJournalLogPayload payload) {
        // sessionId 匹配检查：如果当前已有 sessionId，必须与服务端一致
        if (sessionId != null && !sessionId.equals(payload.sessionId())) {
            return false;
        }
        return payload.sequence() > lastSequence;
    }

    private static boolean applyIncremental(ArchaeologyJournalLogState state, SyncJournalLogPayload payload) {
        return switch (payload.action()) {
            case CLEAR_ALL -> clearAll(state);
            case CLEAR_TABLE -> payload.tableId() != null && state.removeTable(payload.tableId());
            case DELETE_ENTRY -> payload.tableId() != null && payload.entryId() != null
                    && state.removeEntry(payload.tableId(), payload.entryId());
            case REPLACE_TABLE -> applyReplaceTable(state, payload);
            case SET_FIRST_UNLOCK_META -> applyFirstUnlockMeta(state, payload);
            case UPSERT_ENTRY -> applyUpsertEntry(state, payload);
        };
    }

    private static boolean applyReplaceTable(ArchaeologyJournalLogState state, SyncJournalLogPayload payload) {
        if (payload.tableId() == null) {
            return false;
        }
        state.putTable(payload.tableId(), ArchaeologyJournalLogState.TableLogHistory.fromTag(payload.data().copy()));
        return true;
    }

    private static boolean applyFirstUnlockMeta(ArchaeologyJournalLogState state, SyncJournalLogPayload payload) {
        if (payload.tableId() == null) {
            return false;
        }
        LootSourceType lootSource = payload.lootSource();
        return state.setFirstUnlockMetaMin(payload.tableId(), lootSource, payload.gameTime(), payload.dayTime());
    }

    private static boolean applyUpsertEntry(ArchaeologyJournalLogState state, SyncJournalLogPayload payload) {
        if (payload.tableId() == null) {
            return false;
        }
        return state.upsertEntry(payload.tableId(), ExcavationLogEntry.fromTag(payload.data()));
    }

    private static boolean clearAll(ArchaeologyJournalLogState state) {
        if (state.isEmpty()) {
            return false;
        }
        state.clear();
        return true;
    }

    /** 一次正在接收的服务端日志快照。 */
    private static final class PendingSnapshot {
        private final UUID sessionId;
        private final long sequence;
        private final UUID snapshotId;
        private final int expectedTableCount;
        private final ArchaeologyJournalLogState state = new ArchaeologyJournalLogState();
        private final Map<ResourceLocation, ChunkAccumulator> tables = new HashMap<>();
        private final Set<ResourceLocation> completedTables = new HashSet<>();
        private int compressedBytes;
        private boolean ended;

        private PendingSnapshot(UUID sessionId, long sequence, UUID snapshotId, int expectedTableCount) {
            this.sessionId = sessionId;
            this.sequence = sequence;
            this.snapshotId = snapshotId;
            this.expectedTableCount = expectedTableCount;
        }

        private boolean mismatches(UUID sessionId, UUID snapshotId) {
            return !this.sessionId.equals(sessionId) || !this.snapshotId.equals(snapshotId);
        }

        private void add(SyncJournalLogTableChunkPayload payload) {
            if (this.completedTables.contains(payload.tableId())) {
                return;
            }
            byte[] chunk = payload.data();
            this.compressedBytes += chunk.length;
            if (this.compressedBytes > JournalLogSnapshotCodec.MAX_COMPRESSED_TABLE_BYTES
                    * Math.clamp(this.expectedTableCount, 1, 16)) {
                throw new IllegalArgumentException("考古日志快照压缩数据总量超过限制");
            }
            ChunkAccumulator accumulator = this.tables.computeIfAbsent(payload.tableId(),
                    ignored -> new ChunkAccumulator(payload.chunkCount()));
            byte[] completed = accumulator.add(payload.chunkIndex(), payload.chunkCount(), chunk);
            if (completed == null) {
                return;
            }
            this.state.putTable(payload.tableId(), JournalLogSnapshotCodec.decode(completed));
            this.tables.remove(payload.tableId());
            this.completedTables.add(payload.tableId());
        }

        private boolean isComplete() {
            return this.tables.isEmpty() && this.completedTables.size() == this.expectedTableCount;
        }
    }

    /** 单张表的定长分片重组器。 */
    private static final class ChunkAccumulator {
        private final byte[][] chunks;
        private int received;
        private int totalBytes;

        private ChunkAccumulator(int chunkCount) {
            this.chunks = new byte[chunkCount][];
        }

        private byte @Nullable [] add(int chunkIndex, int chunkCount, byte[] data) {
            if (chunkCount != this.chunks.length) {
                throw new IllegalArgumentException("同一日志表的分片总数不一致");
            }
            if (this.chunks[chunkIndex] == null) {
                this.chunks[chunkIndex] = data;
                this.received++;
                this.totalBytes += data.length;
                if (this.totalBytes > JournalLogSnapshotCodec.MAX_COMPRESSED_TABLE_BYTES) {
                    throw new IllegalArgumentException("单张日志表压缩数据超过限制");
                }
            }
            if (this.received != this.chunks.length) {
                return null;
            }
            byte[] result = new byte[this.totalBytes];
            int offset = 0;
            for (byte[] chunk : this.chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            return result;
        }
    }
}
