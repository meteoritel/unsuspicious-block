package com.meteorite.unsuspiciousblock.journal.sync;

import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.TriggerType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.UUID;

public final class ArchaeologyJournalLogSyncSession {
    @Nullable
    private UUID sessionId;
    private long nextSequence = 1L;
    private boolean seeded;
    private final ArchaeologyJournalLogState mirroredState = new ArchaeologyJournalLogState();
    private final ArrayList<QueuedMutation> queuedMutations = new ArrayList<>();

    public void reset() {
        this.sessionId = null;
        this.nextSequence = 1L;
        this.seeded = false;
        this.mirroredState.clear();
        this.queuedMutations.clear();
    }

    public void copyFrom(ArchaeologyJournalLogSyncSession other) {
        this.sessionId = other.sessionId;
        this.nextSequence = other.nextSequence;
        this.seeded = other.seeded;
        this.mirroredState.copyFrom(other.mirroredState);
        this.queuedMutations.clear();
        this.queuedMutations.addAll(other.queuedMutations);
    }

    @Nullable
    public UUID getSessionId() {
        return this.sessionId;
    }

    public boolean isSeeded() {
        return this.seeded;
    }

    public long nextSequence() {
        return this.nextSequence++;
    }

    public long lastSequence() {
        return Math.max(0L, this.nextSequence - 1L);
    }

    public ArchaeologyJournalLogState mirroredState() {
        return this.mirroredState;
    }

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

    public void queueFirstUnlockMeta(ResourceLocation tableId, @Nullable TriggerType triggerType,
                                     long gameTime, long dayTime) {
        this.queuedMutations.add(QueuedMutation.firstUnlockMeta(tableId, triggerType, gameTime, dayTime));
    }

    public void queueUpsertEntry(ResourceLocation tableId, ExcavationLogEntry entry) {
        this.queuedMutations.add(QueuedMutation.upsertEntry(tableId, entry));
    }

    public void queueClearAll() {
        this.queuedMutations.add(QueuedMutation.clearAll());
    }

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
