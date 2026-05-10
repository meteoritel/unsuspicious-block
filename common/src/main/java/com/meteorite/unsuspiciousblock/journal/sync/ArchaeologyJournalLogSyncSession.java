package com.meteorite.unsuspiciousblock.journal.sync;

import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState.ExcavationLogEntry;
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

    public void queueFirstUnlock(ResourceLocation tableId, long gameTime, long dayTime) {
        this.queuedMutations.add(QueuedMutation.firstUnlock(tableId, gameTime, dayTime));
    }

    public void queueExcavation(ResourceLocation tableId, ExcavationLogEntry entry) {
        this.queuedMutations.add(QueuedMutation.excavation(tableId, entry));
    }

    public void queueClearAll() {
        this.queuedMutations.add(QueuedMutation.clearAll());
    }

    public void queueClearTable(ResourceLocation tableId) {
        this.queuedMutations.add(QueuedMutation.clearTable(tableId));
    }

    private record QueuedMutation(Type type,
                                  @Nullable ResourceLocation tableId,
                                  long firstUnlockedGameTime,
                                  long firstUnlockedDayTime,
                                  @Nullable ExcavationLogEntry entry) {
        private static QueuedMutation firstUnlock(ResourceLocation tableId, long gameTime, long dayTime) {
            return new QueuedMutation(Type.FIRST_UNLOCK, tableId, gameTime, dayTime, null);
        }

        private static QueuedMutation excavation(ResourceLocation tableId, ExcavationLogEntry entry) {
            return new QueuedMutation(Type.EXCAVATION, tableId, 0L, 0L, entry);
        }

        private static QueuedMutation clearAll() {
            return new QueuedMutation(Type.CLEAR_ALL, null, 0L, 0L, null);
        }

        private static QueuedMutation clearTable(ResourceLocation tableId) {
            return new QueuedMutation(Type.CLEAR_TABLE, tableId, 0L, 0L, null);
        }

        private void apply(ArchaeologyJournalLogState state) {
            switch (this.type) {
                case FIRST_UNLOCK -> {
                    if (this.tableId != null) {
                        state.setFirstUnlockedTimeMin(this.tableId, this.firstUnlockedGameTime, this.firstUnlockedDayTime);
                    }
                }
                case EXCAVATION -> {
                    if (this.tableId != null && this.entry != null) {
                        state.appendEntry(this.tableId, this.entry);
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
        FIRST_UNLOCK,
        EXCAVATION,
        CLEAR_ALL,
        CLEAR_TABLE
    }
}
