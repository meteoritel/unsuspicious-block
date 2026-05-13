package com.meteorite.unsuspiciousblock.journal;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ArchaeologyJournalLogState {
    private static final String TABLES_TAG = "tables";
    private static final String FIRST_UNLOCKED_TIME_TAG = "first_unlocked_game_time";
    private static final String FIRST_UNLOCKED_DAY_TIME_TAG = "first_unlocked_day_time";
    private static final String FIRST_UNLOCK_TRIGGER_TYPE_TAG = "first_unlock_trigger_type";
    private static final String ENTRIES_TAG = "entries";

    private final LinkedHashMap<ResourceLocation, TableLogHistory> tables = new LinkedHashMap<>();

    public void clear() {
        this.tables.clear();
    }

    @Nullable
    public TableLogHistory getTable(ResourceLocation tableId) {
        return this.tables.get(tableId);
    }

    public Map<ResourceLocation, TableLogHistory> getTables() {
        return Collections.unmodifiableMap(this.tables);
    }

    public boolean isEmpty() {
        return this.tables.isEmpty();
    }

    public int getTotalEntryCount() {
        int total = 0;
        for (TableLogHistory history : this.tables.values()) {
            total += history.getTotalEntryCount();
        }
        return total;
    }

    public boolean setFirstUnlockMetaMin(ResourceLocation tableId, @Nullable TriggerType triggerType,
                                         long gameTime, long dayTime) {
        return this.getOrCreateTable(tableId).setFirstUnlockMetaMin(triggerType, gameTime, dayTime);
    }

    public boolean upsertEntry(ResourceLocation tableId, ExcavationLogEntry entry) {
        return this.getOrCreateTable(tableId).upsertEntry(entry);
    }

    public boolean removeTable(ResourceLocation tableId) {
        return this.tables.remove(tableId) != null;
    }

    public ArchaeologyJournalLogState copy() {
        ArchaeologyJournalLogState copy = new ArchaeologyJournalLogState();
        copy.copyFrom(this);
        return copy;
    }

    public void copyFrom(ArchaeologyJournalLogState other) {
        this.tables.clear();
        for (Map.Entry<ResourceLocation, TableLogHistory> entry : other.tables.entrySet()) {
            this.tables.put(entry.getKey(), entry.getValue().copy());
        }
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        this.writeTo(tag);
        return tag;
    }

    public void writeTo(CompoundTag tag) {
        CompoundTag tablesTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, TableLogHistory> entry : this.tables.entrySet()) {
            tablesTag.put(entry.getKey().toString(), entry.getValue().toTag());
        }
        tag.put(TABLES_TAG, tablesTag);
    }

    public void readFrom(CompoundTag tag) {
        this.clear();
        if (!tag.contains(TABLES_TAG, Tag.TAG_COMPOUND)) {
            return;
        }

        CompoundTag tablesTag = tag.getCompound(TABLES_TAG);
        for (String key : tablesTag.getAllKeys()) {
            ResourceLocation tableId = ResourceLocation.tryParse(key);
            if (tableId == null) {
                continue;
            }
            this.tables.put(tableId, TableLogHistory.fromTag(tablesTag.getCompound(key)));
        }
    }

    private TableLogHistory getOrCreateTable(ResourceLocation tableId) {
        return this.tables.computeIfAbsent(tableId, ignored -> new TableLogHistory());
    }

    public static final class TableLogHistory {
        @Nullable
        private Long firstUnlockedGameTime;
        @Nullable
        private Long firstUnlockedDayTime;
        @Nullable
        private TriggerType firstUnlockTriggerType;
        private final LinkedHashMap<UUID, ExcavationLogEntry> entries = new LinkedHashMap<>();

        @Nullable
        public Long getFirstUnlockedGameTime() {
            return this.firstUnlockedGameTime;
        }

        @Nullable
        public Long getFirstUnlockedDayTime() {
            return this.firstUnlockedDayTime;
        }

        @Nullable
        public TriggerType getFirstUnlockTriggerType() {
            return this.firstUnlockTriggerType;
        }

        public List<ExcavationLogEntry> getEntries() {
            return List.copyOf(this.entries.values());
        }

        public int getTotalEntryCount() {
            return this.entries.size();
        }

        public boolean setFirstUnlockedTimeMin(long gameTime, long dayTime) {
            return this.setFirstUnlockMetaMin(null, gameTime, dayTime);
        }

        public boolean setFirstUnlockMetaMin(@Nullable TriggerType triggerType, long gameTime, long dayTime) {
            long normalizedGameTime = Math.max(0L, gameTime);
            long normalizedDayTime = Math.max(0L, dayTime);
            if (this.firstUnlockedGameTime == null) {
                this.firstUnlockedGameTime = normalizedGameTime;
                this.firstUnlockedDayTime = normalizedDayTime;
                this.firstUnlockTriggerType = triggerType;
                return true;
            }

            long currentDayTime = this.firstUnlockedDayTime != null ? this.firstUnlockedDayTime : this.firstUnlockedGameTime;
            if (normalizedGameTime < this.firstUnlockedGameTime
                    || normalizedGameTime == this.firstUnlockedGameTime && normalizedDayTime < currentDayTime) {
                this.firstUnlockedGameTime = normalizedGameTime;
                this.firstUnlockedDayTime = normalizedDayTime;
                this.firstUnlockTriggerType = triggerType;
                return true;
            }

            if (normalizedGameTime == this.firstUnlockedGameTime && normalizedDayTime == currentDayTime
                    && this.firstUnlockTriggerType == null && triggerType != null) {
                this.firstUnlockTriggerType = triggerType;
                return true;
            }
            return false;
        }

        public boolean upsertEntry(ExcavationLogEntry entry) {
            ExcavationLogEntry previous = this.entries.put(entry.entryId(), entry);
            return !entry.equals(previous);
        }

        public TableLogHistory copy() {
            TableLogHistory copy = new TableLogHistory();
            copy.firstUnlockedGameTime = this.firstUnlockedGameTime;
            copy.firstUnlockedDayTime = this.firstUnlockedDayTime;
            copy.firstUnlockTriggerType = this.firstUnlockTriggerType;
            copy.entries.putAll(this.entries);
            return copy;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            if (this.firstUnlockedGameTime != null) {
                tag.putLong(FIRST_UNLOCKED_TIME_TAG, this.firstUnlockedGameTime);
                tag.putLong(FIRST_UNLOCKED_DAY_TIME_TAG,
                        this.firstUnlockedDayTime != null ? this.firstUnlockedDayTime : this.firstUnlockedGameTime);
            }
            if (this.firstUnlockTriggerType != null) {
                tag.putString(FIRST_UNLOCK_TRIGGER_TYPE_TAG, this.firstUnlockTriggerType.serializedName());
            }
            ListTag entriesTag = new ListTag();
            for (ExcavationLogEntry entry : this.entries.values()) {
                entriesTag.add(entry.toTag());
            }
            tag.put(ENTRIES_TAG, entriesTag);
            return tag;
        }

        public static TableLogHistory fromTag(CompoundTag tag) {
            TableLogHistory history = new TableLogHistory();
            if (tag.contains(FIRST_UNLOCKED_TIME_TAG, Tag.TAG_LONG)) {
                history.firstUnlockedGameTime = Math.max(0L, tag.getLong(FIRST_UNLOCKED_TIME_TAG));
                history.firstUnlockedDayTime = tag.contains(FIRST_UNLOCKED_DAY_TIME_TAG, Tag.TAG_LONG)
                        ? Math.max(0L, tag.getLong(FIRST_UNLOCKED_DAY_TIME_TAG))
                        : history.firstUnlockedGameTime;
            }
            if (tag.contains(FIRST_UNLOCK_TRIGGER_TYPE_TAG, Tag.TAG_STRING)) {
                history.firstUnlockTriggerType = TriggerType.fromSerializedName(tag.getString(FIRST_UNLOCK_TRIGGER_TYPE_TAG));
            }
            if (tag.contains(ENTRIES_TAG, Tag.TAG_LIST)) {
                ListTag entriesTag = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
                for (int i = 0; i < entriesTag.size(); i++) {
                    history.upsertEntry(ExcavationLogEntry.fromTag(entriesTag.getCompound(i)));
                }
            }
            return history;
        }
    }
}