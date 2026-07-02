package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

/**
 * 客户端本地日志存储——管理考古日志的本地持久化和服务端同步数据的应用。
 * 服务端权威模式：sessionId 完全由服务端下发，客户端不主动生成。
 */
public final class ArchaeologyJournalLogLocalStore {
    private static final String STORAGE_DIR = "unsuspiciousblock_journal_logs";
    private static final String STORAGE_FILE = "journal_log_state.dat";

    private static volatile ArchaeologyJournalLogState logState = new ArchaeologyJournalLogState();
    @Nullable
    private static volatile Path loadedPath;
    @Nullable
    private static ClientPacketListener trackedConnection;
    @Nullable
    private static UUID currentSessionId;
    @Nullable
    private static SyncJournalLogSnapshotPayload pendingSnapshot;
    private static final ArrayList<SyncJournalLogPayload> pendingIncrementals = new ArrayList<>();
    private static long lastAppliedSequence;
    private static volatile long revision;
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
        flushPending();
    }

    // 接受服务端下发的全量快照——直接覆盖本地状态，设定 sessionId
    public static synchronized void applySnapshot(SyncJournalLogSnapshotPayload payload) {
        // 先刷新连接状态和待定数据（不触发基线上传）
        refreshConnection();
        ensureLoaded();
        flushPending();
        if (loadedPath == null) {
            queueSnapshot(payload);
            return;
        }
        // 服务端权威模式：接受任意 sessionId 的快照（连接切换时 sessionId 会变化）
        // 只要 sequence 不低于当前，就接受
        if (payload.sequence() < lastAppliedSequence) {
            return;
        }

        ArchaeologyJournalLogState updatedState = new ArchaeologyJournalLogState();
        updatedState.readFrom(payload.state());
        logState = updatedState;
        currentSessionId = payload.sessionId();
        lastAppliedSequence = payload.sequence();
        revision++;
        save();
    }

    // 接受服务端下发的增量更新
    public static synchronized void applyUpdate(SyncJournalLogPayload payload) {
        // 先刷新连接状态和待定数据（不触发基线上传）
        refreshConnection();
        ensureLoaded();
        flushPending();
        if (loadedPath == null) {
            pendingIncrementals.add(payload);
            return;
        }
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
        pendingIncrementals.clear();
        Services.NETWORK.sendToServer(new RequestJournalLogSnapshotPayload());
    }

    private static void queueSnapshot(SyncJournalLogSnapshotPayload payload) {
        if (pendingSnapshot == null
                || !pendingSnapshot.sessionId().equals(payload.sessionId())
                || payload.sequence() >= pendingSnapshot.sequence()) {
            pendingSnapshot = payload;
        }
    }

    private static void flushPending() {
        if (pendingSnapshot == null && pendingIncrementals.isEmpty()) {
            return;
        }

        // 服务端权威模式：只处理与当前 sessionId 匹配的待定数据
        if (pendingSnapshot != null) {
            // 快照总是被接受（sequence 检查已排序）
            if (pendingSnapshot.sequence() >= lastAppliedSequence) {
                ArchaeologyJournalLogState snapshotState = new ArchaeologyJournalLogState();
                snapshotState.readFrom(pendingSnapshot.state());
                logState = snapshotState;
                currentSessionId = pendingSnapshot.sessionId();
                lastAppliedSequence = pendingSnapshot.sequence();
                revision++;
                save();
            }
            pendingSnapshot = null;
        }

        // 处理排队的增量更新
        if (!pendingIncrementals.isEmpty()) {
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
            case SET_FIRST_UNLOCK_META -> applyFirstUnlockMeta(state, payload);
            case UPSERT_ENTRY -> applyUpsertEntry(state, payload);
        };
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
            CompoundTag tag = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
            state.readFrom(tag);
        } catch (IOException e) {
            Constants.LOG.warn("读取本地考古日志失败: {}", path, e);
        }
        return state;
    }

    private static boolean save() {
        if (loadedPath == null) {
            return false;
        }
        try {
            Files.createDirectories(loadedPath.getParent());
            try (OutputStream outputStream = Files.newOutputStream(loadedPath)) {
                NbtIo.writeCompressed(logState.toTag(), outputStream);
            }
            return true;
        } catch (IOException e) {
            Constants.LOG.warn("保存本地考古日志失败: {}", loadedPath, e);
            return false;
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
}