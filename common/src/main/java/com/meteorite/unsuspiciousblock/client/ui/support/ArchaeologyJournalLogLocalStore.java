package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotEndPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotStartPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogTableChunkPayload;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * 客户端本地日志存储——管理考古日志的本地持久化和服务端同步数据的应用。
 * 服务端权威模式：sessionId 完全由服务端下发，客户端不主动生成。
 */
public final class ArchaeologyJournalLogLocalStore {
    static final String STORAGE_FILE = "journal_log_state.dat";
    private static final long MAX_LOCAL_NBT_BYTES = 256L * 1024 * 1024;

    private static final JournalLocalPersistence.PendingWrites writes = new JournalLocalPersistence.PendingWrites();
    private static JournalMirror mirror = new JournalMirror(System::currentTimeMillis, () -> {});
    private static long revisionOffset;
    @Nullable
    private static Path loadedPath;

    private ArchaeologyJournalLogLocalStore() {
    }

    public static synchronized ArchaeologyJournalLogState getState() {
        return mirror.current();
    }

    public static synchronized long getRevision() {
        return revisionOffset + mirror.revision();
    }

    public static synchronized void tick() {
        mirror.advance();
        flush();
    }

    public static synchronized void flush() {
        if (mirror.isDirty() && loadedPath != null) {
            writes.stage(loadedPath, mirror.current().toTag());
        }
        writes.flush();
        if (loadedPath != null && writes.isIdle(loadedPath)) {
            mirror.markSaved();
        }
    }

    public static synchronized void beginSnapshot(SyncJournalLogSnapshotStartPayload payload) {
        mirror.beginSnapshot(payload);
    }

    public static synchronized void applySnapshotChunk(SyncJournalLogTableChunkPayload payload) {
        mirror.acceptChunk(payload);
    }

    public static synchronized void completeSnapshot(SyncJournalLogSnapshotEndPayload payload) {
        mirror.endSnapshot(payload);
    }

    public static synchronized void applyUpdate(SyncJournalLogPayload payload) {
        mirror.applyUpdate(payload);
    }

    static synchronized void beginConnection(JournalMirror nextMirror) {
        flush();
        revisionOffset += mirror.revision() + 1L;
        mirror = nextMirror;
        loadedPath = null;
    }

    static synchronized void loadCurrentPath(Path path) {
        if (!path.equals(loadedPath)) {
            loadedPath = path;
            mirror.restore(load(path));
        }
    }

    private static ArchaeologyJournalLogState load(Path path) {
        CompoundTag pending = writes.pending(path);
        JournalLocalPersistence.Loaded loaded = pending == null
                ? JournalLocalPersistence.load(path, MAX_LOCAL_NBT_BYTES)
                : new JournalLocalPersistence.Loaded(pending, true);
        ArchaeologyJournalLogState state = new ArchaeologyJournalLogState();
        if (loaded.successful()) {
            state.readFrom(loaded.tag());
        }
        return state;
    }

}
