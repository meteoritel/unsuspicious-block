package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.TriggerType;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.UploadJournalLogSnapshotPayload;
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

public final class ArchaeologyJournalLogLocalStore {
    private static final String STORAGE_DIR = "unsuspiciousblock_journal_logs";
    private static final String STORAGE_FILE = "journal_log_state.dat";

    private static ArchaeologyJournalLogState logState = new ArchaeologyJournalLogState();
    @Nullable
    private static Path loadedPath;
    @Nullable
    private static ClientPacketListener trackedConnection;
    @Nullable
    private static UUID currentSessionId;
    @Nullable
    private static SyncJournalLogSnapshotPayload pendingSnapshot;
    private static final ArrayList<SyncJournalLogPayload> pendingIncrementals = new ArrayList<>();
    private static long lastAppliedSequence;
    private static long revision;
    private static boolean baselineUploaded;

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
        if (trackedConnection != null && !baselineUploaded) {
            uploadBaseline();
        }
        flushPending();
    }

    public static synchronized void applySnapshot(SyncJournalLogSnapshotPayload payload) {
        tick();
        if (loadedPath == null) {
            queueSnapshot(payload);
            return;
        }
        if (!shouldAcceptSnapshot(currentSessionId, lastAppliedSequence, payload)) {
            return;
        }

        ArchaeologyJournalLogState updatedState = new ArchaeologyJournalLogState();
        updatedState.readFrom(payload.state());
        logState = updatedState;
        currentSessionId = payload.sessionId();
        lastAppliedSequence = payload.sequence();
        baselineUploaded = true;

    }

    public static synchronized void applyUpdate(SyncJournalLogPayload payload) {
        tick();
        if (loadedPath == null) {
            pendingIncrementals.add(payload);
            return;
        }
        if (!shouldAcceptUpdate(currentSessionId, lastAppliedSequence, payload)) {
            return;
        }

        boolean changed = applyIncremental(logState, payload);
        if (!changed) {
            if (currentSessionId == null) {
                currentSessionId = payload.sessionId();
            }
            lastAppliedSequence = Math.max(lastAppliedSequence, payload.sequence());
            return;
        }
        currentSessionId = payload.sessionId();
        lastAppliedSequence = payload.sequence();

    }

    private static boolean saveWithRollback(ArchaeologyJournalLogState previousState,
                                            @Nullable UUID previousSessionId,
                                            long previousSequence,
                                            boolean previousBaselineUploaded) {
        if (save()) {
            revision++;
            return true;
        }
        logState = previousState;
        currentSessionId = previousSessionId;
        lastAppliedSequence = previousSequence;
        baselineUploaded = previousBaselineUploaded;
        return false;
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
        baselineUploaded = false;
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
        ArchaeologyJournalLogState previousState = logState.copy();
        UUID previousSessionId = currentSessionId;
        long previousSequence = lastAppliedSequence;
        boolean previousBaselineUploaded = baselineUploaded;

        ArchaeologyJournalLogState workingState = logState.copy();
        UUID workingSessionId = currentSessionId;
        long workingSequence = lastAppliedSequence;
        boolean changed = false;
        boolean acceptedSnapshot = false;
        boolean acceptedIncremental = false;

        if (pendingSnapshot != null && shouldAcceptSnapshot(workingSessionId, workingSequence, pendingSnapshot)) {
            ArchaeologyJournalLogState snapshotState = new ArchaeologyJournalLogState();
            snapshotState.readFrom(pendingSnapshot.state());
            workingState = snapshotState;
            workingSessionId = pendingSnapshot.sessionId();
            workingSequence = pendingSnapshot.sequence();
            acceptedSnapshot = true;
            changed = true;
        }

        ArrayList<SyncJournalLogPayload> sortedIncrementals = new ArrayList<>(pendingIncrementals);
        sortedIncrementals.sort(Comparator.comparingLong(SyncJournalLogPayload::sequence));
        for (SyncJournalLogPayload payload : sortedIncrementals) {
            if (!shouldAcceptUpdate(workingSessionId, workingSequence, payload)) {
                continue;
            }
            workingSessionId = payload.sessionId();
            workingSequence = payload.sequence();
            acceptedIncremental = true;
            if (applyIncremental(workingState, payload)) {
                changed = true;
            }
        }

        if (!changed) {
            if (acceptedIncremental) {
                currentSessionId = workingSessionId;
                lastAppliedSequence = workingSequence;
                baselineUploaded = previousBaselineUploaded;
            }
            pendingSnapshot = null;
            pendingIncrementals.clear();
            return;
        }

        logState = workingState;
        currentSessionId = workingSessionId;
        lastAppliedSequence = workingSequence;
        baselineUploaded = previousBaselineUploaded || acceptedSnapshot;
        if (saveWithRollback(previousState, previousSessionId, previousSequence, previousBaselineUploaded)) {
            pendingSnapshot = null;
            pendingIncrementals.clear();
        }
    }

    private static boolean shouldAcceptSnapshot(@Nullable UUID sessionId, long lastSequence,
                                                SyncJournalLogSnapshotPayload payload) {
        if (sessionId != null && !sessionId.equals(payload.sessionId())) {
            return false;
        }
        return payload.sequence() >= lastSequence;
    }

    private static boolean shouldAcceptUpdate(@Nullable UUID sessionId, long lastSequence,
                                              SyncJournalLogPayload payload) {
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
        TriggerType triggerType = payload.triggerType();
        return state.setFirstUnlockMetaMin(payload.tableId(), triggerType, payload.gameTime(), payload.dayTime());
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

    private static void uploadBaseline() {
        if (loadedPath == null || trackedConnection == null || baselineUploaded) {
            return;
        }
        UUID sessionId = UUID.randomUUID();
        currentSessionId = sessionId;
        lastAppliedSequence = 0L;
        baselineUploaded = true;
        Services.NETWORK.sendToServer(new UploadJournalLogSnapshotPayload(sessionId, logState.toTag()));
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
