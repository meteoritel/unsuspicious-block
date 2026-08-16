package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.network.journal.JournalLogSnapshotCodec;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotEndPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotStartPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogTableChunkPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端本地日志存储——管理考古日志的本地持久化和服务端同步数据的应用。
 * 服务端权威模式：sessionId 完全由服务端下发，客户端不主动生成。
 */
public final class ArchaeologyJournalLogLocalStore {
    private static final String STORAGE_DIR = "unsuspiciousblock_journal_logs";
    private static final String STORAGE_FILE = "journal_log_state.dat";
    private static final long MAX_LOCAL_NBT_BYTES = 256L * 1024 * 1024;

    private static volatile ArchaeologyJournalLogState logState = new ArchaeologyJournalLogState();
    @Nullable
    private static volatile Path loadedPath;
    @Nullable
    private static ClientPacketListener trackedConnection;
    @Nullable
    private static UUID currentSessionId;
    @Nullable
    private static PendingSnapshot pendingSnapshot;
    private static final ArrayList<SyncJournalLogPayload> pendingIncrementals = new ArrayList<>();
    private static long lastAppliedSequence;
    private static long revision;
    // 日志快照重同步请求限流时间戳（ms），防止极端场景下请求风暴
    private static long lastSnapshotRequestMs;
    // 重同步请求最小间隔
    private static final long RESYNC_COOLDOWN_MS = 2000L;

    private ArchaeologyJournalLogLocalStore() {
    }

    // 每次读取状态前先执行 tick，确保本地缓存与连接状态同步。
    // 这是刻意设计：调用方只需读取状态，无需额外记住刷新步骤。
    public static synchronized ArchaeologyJournalLogState getState() {
        tick();
        return logState;
    }

    public static synchronized long getRevision() {
        tick();
        return revision;
    }

    public static synchronized void tick() {
        refreshConnection();
        ensureLoaded();
        if (loadedPath == null) {
            return;
        }
        tryCommitSnapshot();
        flushPending();
    }

    // 开始接收服务端权威分片快照，旧状态在完整校验前继续可用
    public static synchronized void beginSnapshot(SyncJournalLogSnapshotStartPayload payload) {
        refreshConnection();
        ensureLoaded();
        if (payload.sequence() < lastAppliedSequence) {
            return;
        }
        pendingSnapshot = new PendingSnapshot(payload.sessionId(), payload.sequence(),
                payload.snapshotId(), payload.tableCount());
        pendingIncrementals.clear();
    }

    // 接收单表压缩数据的一片；完整单表立即解码进待提交状态
    public static synchronized void applySnapshotChunk(SyncJournalLogTableChunkPayload payload) {
        refreshConnection();
        PendingSnapshot snapshot = pendingSnapshot;
        if (snapshot == null || !snapshot.matches(payload.sessionId(), payload.snapshotId())) {
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
    public static synchronized void completeSnapshot(SyncJournalLogSnapshotEndPayload payload) {
        refreshConnection();
        PendingSnapshot snapshot = pendingSnapshot;
        if (snapshot == null || !snapshot.matches(payload.sessionId(), payload.snapshotId())) {
            requestLogSnapshotWithCooldown();
            return;
        }
        snapshot.ended = true;
        tryCommitSnapshot();
    }

    // 接受服务端下发的增量更新
    public static synchronized void applyUpdate(SyncJournalLogPayload payload) {
        // 先刷新连接状态和待定数据（不触发基线上传）
        refreshConnection();
        ensureLoaded();
        if (loadedPath == null) {
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
            save();
        } else {
            if (currentSessionId == null) {
                currentSessionId = payload.sessionId();
            }
            lastAppliedSequence = Math.max(lastAppliedSequence, payload.sequence());
        }
    }

    private static void refreshConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == trackedConnection) {
            return;
        }
        trackedConnection = connection;
        resetSessionState();
        loadedPath = null;
    }

    private static void resetSessionState() {
        currentSessionId = null;
        pendingSnapshot = null;
        pendingIncrementals.clear();
        lastAppliedSequence = 0L;
    }

    // 请求日志快照重同步（带限流），用于 sessionId 不匹配时的恢复路径
    // 清除本地 sessionId 后向服务端请求全新快照，使客户端重新对齐服务端权威状态
    private static void requestLogSnapshotWithCooldown() {
        long now = System.currentTimeMillis();
        if (now - lastSnapshotRequestMs < RESYNC_COOLDOWN_MS) {
            return;
        }
        lastSnapshotRequestMs = now;
        // 清除旧会话状态，允许后续快照重建基线
        currentSessionId = null;
        lastAppliedSequence = 0L;
        pendingSnapshot = null;
        pendingIncrementals.clear();
        Services.NETWORK.sendToServer(new RequestJournalLogSnapshotPayload());
    }

    private static void flushPending() {
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
            save();
        }
        pendingIncrementals.clear();
    }

    private static void tryCommitSnapshot() {
        PendingSnapshot snapshot = pendingSnapshot;
        if (snapshot == null || !snapshot.ended || loadedPath == null) {
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
        save();
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

    private static void ensureLoaded() {
        Path currentPath = resolveCurrentPath();
        if (currentPath == null) {
            if (loadedPath != null) {
                loadedPath = null;
                logState = new ArchaeologyJournalLogState();
                revision++;
            }
            return;
        }
        if (currentPath.equals(loadedPath)) {
            return;
        }
        loadedPath = currentPath;
        logState = load(currentPath);
        revision++;
    }

    private static ArchaeologyJournalLogState load(Path path) {
        ArchaeologyJournalLogState state = new ArchaeologyJournalLogState();
        if (!Files.exists(path)) {
            return state;
        }
        try (InputStream inputStream = Files.newInputStream(path)) {
            CompoundTag tag = NbtIo.readCompressed(inputStream, NbtAccounter.create(MAX_LOCAL_NBT_BYTES));
            state.readFrom(tag);
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("读取本地考古日志失败: {}", path, e);
        }
        return state;
    }

    private static boolean save() {
        Path path = loadedPath;
        if (path == null) {
            return false;
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        boolean moved = false;
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream outputStream = Files.newOutputStream(temporary)) {
                NbtIo.writeCompressed(logState.toTag(), outputStream);
            }
            try {
                Files.move(temporary, path,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return true;
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("保存本地考古日志失败: {}", path, e);
            return false;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupException) {
                    Constants.LOG.debug("清理本地考古日志临时文件失败: {}", temporary, cleanupException);
                }
            }
        }
    }

    @Nullable
    private static Path resolveCurrentPath() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
            return minecraft.getSingleplayerServer()
                    .getWorldPath(LevelResource.ROOT)
                    .resolve(STORAGE_DIR)
                    .resolve(minecraft.player.getUUID().toString())
                    .resolve(STORAGE_FILE);
        }

        ServerData serverData = minecraft.getCurrentServer();
        if (serverData == null) {
            return null;
        }

        String serverKey = sanitize(serverData.ip);
        return minecraft.gameDirectory.toPath()
                .resolve(STORAGE_DIR)
                .resolve(serverKey)
                .resolve(minecraft.player.getUUID().toString())
                .resolve(STORAGE_FILE);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown_server";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 一次正在接收的服务端日志快照。 */
    private static final class PendingSnapshot {
        private final UUID sessionId;
        private final long sequence;
        private final UUID snapshotId;
        private final int expectedTableCount;
        private final ArchaeologyJournalLogState state = new ArchaeologyJournalLogState();
        private final Map<net.minecraft.resources.ResourceLocation, ChunkAccumulator> tables = new HashMap<>();
        private final Set<net.minecraft.resources.ResourceLocation> completedTables = new HashSet<>();
        private int compressedBytes;
        private boolean ended;

        private PendingSnapshot(UUID sessionId, long sequence, UUID snapshotId, int expectedTableCount) {
            this.sessionId = sessionId;
            this.sequence = sequence;
            this.snapshotId = snapshotId;
            this.expectedTableCount = expectedTableCount;
        }

        private boolean matches(UUID sessionId, UUID snapshotId) {
            return this.sessionId.equals(sessionId) && this.snapshotId.equals(snapshotId);
        }

        private void add(SyncJournalLogTableChunkPayload payload) {
            if (this.completedTables.contains(payload.tableId())) {
                return;
            }
            byte[] chunk = payload.data();
            this.compressedBytes += chunk.length;
            if (this.compressedBytes > JournalLogSnapshotCodec.MAX_COMPRESSED_TABLE_BYTES
                    * Math.max(1, Math.min(this.expectedTableCount, 16))) {
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
