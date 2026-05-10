package com.meteorite.unsuspiciousblock.journal;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ArchaeologyJournalLogState {
    private static final String TABLES_TAG = "tables";
    private static final String FIRST_UNLOCKED_TIME_TAG = "first_unlocked_game_time";
    private static final String FIRST_UNLOCKED_DAY_TIME_TAG = "first_unlocked_day_time";
    private static final String ENTRIES_TAG = "entries";
    private static final String ITEM_ID_TAG = "item_id";
    private static final String STRUCTURE_ID_TAG = "structure_id";
    private static final String BIOME_ID_TAG = "biome_id";
    private static final String POS_X_TAG = "pos_x";
    private static final String POS_Y_TAG = "pos_y";
    private static final String POS_Z_TAG = "pos_z";
    private static final String GAME_TIME_TAG = "game_time";
    private static final String DAY_TIME_TAG = "day_time";
    private static final int MAX_RECENT_ENTRIES = 50;

    private final LinkedHashMap<ResourceLocation, TableLogHistory> tables = new LinkedHashMap<>();

    public void clear() {
        this.tables.clear();
    }

    @Nullable
    public TableLogHistory getTable(ResourceLocation tableId) {
        return this.tables.get(tableId);
    }

    public boolean isEmpty() {
        return this.tables.isEmpty();
    }

    public boolean setFirstUnlockedTimeMin(ResourceLocation tableId, long gameTime, long dayTime) {
        return this.getOrCreateTable(tableId).setFirstUnlockedTimeMin(gameTime, dayTime);
    }

    public boolean appendEntry(ResourceLocation tableId, ExcavationLogEntry entry) {
        return this.getOrCreateTable(tableId).appendEntry(entry);
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
        private final ArrayList<ExcavationLogEntry> recentEntries = new ArrayList<>();

        @Nullable
        public Long getFirstUnlockedGameTime() {
            return this.firstUnlockedGameTime;
        }

        @Nullable
        public Long getFirstUnlockedDayTime() {
            return this.firstUnlockedDayTime;
        }

        public List<ExcavationLogEntry> getRecentEntries() {
            return Collections.unmodifiableList(this.recentEntries);
        }

        public boolean setFirstUnlockedTimeMin(long gameTime, long dayTime) {
            long normalizedGameTime = Math.max(0L, gameTime);
            long normalizedDayTime = Math.max(0L, dayTime);
            if (this.firstUnlockedGameTime == null) {
                this.firstUnlockedGameTime = normalizedGameTime;
                this.firstUnlockedDayTime = normalizedDayTime;
                return true;
            }
            long currentDayTime = this.firstUnlockedDayTime != null ? this.firstUnlockedDayTime : this.firstUnlockedGameTime;
            if (normalizedGameTime > this.firstUnlockedGameTime
                    || normalizedGameTime == this.firstUnlockedGameTime && normalizedDayTime >= currentDayTime) {
                return false;
            }
            this.firstUnlockedGameTime = normalizedGameTime;
            this.firstUnlockedDayTime = normalizedDayTime;
            return true;
        }

        public boolean appendEntry(ExcavationLogEntry entry) {
            this.recentEntries.add(entry);
            while (this.recentEntries.size() > MAX_RECENT_ENTRIES) {
                this.recentEntries.removeFirst();
            }
            return true;
        }

        public TableLogHistory copy() {
            TableLogHistory copy = new TableLogHistory();
            copy.firstUnlockedGameTime = this.firstUnlockedGameTime;
            copy.firstUnlockedDayTime = this.firstUnlockedDayTime;
            copy.recentEntries.addAll(this.recentEntries);
            return copy;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            if (this.firstUnlockedGameTime != null) {
                tag.putLong(FIRST_UNLOCKED_TIME_TAG, this.firstUnlockedGameTime);
                tag.putLong(FIRST_UNLOCKED_DAY_TIME_TAG,
                        this.firstUnlockedDayTime != null ? this.firstUnlockedDayTime : this.firstUnlockedGameTime);
            }
            ListTag entriesTag = new ListTag();
            for (ExcavationLogEntry entry : this.recentEntries) {
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
            if (tag.contains(ENTRIES_TAG, Tag.TAG_LIST)) {
                ListTag entriesTag = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
                for (int i = 0; i < entriesTag.size(); i++) {
                    history.appendEntry(ExcavationLogEntry.fromTag(entriesTag.getCompound(i)));
                }
            }
            return history;
        }
    }

    public record ExcavationLogEntry(@Nullable ResourceLocation itemId,
                                     @Nullable ResourceLocation structureId,
                                     ResourceLocation biomeId,
                                     BlockPos pos,
                                     long gameTime,
                                     long dayTime) {
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            if (this.itemId != null) {
                tag.putString(ITEM_ID_TAG, this.itemId.toString());
            }
            if (this.structureId != null) {
                tag.putString(STRUCTURE_ID_TAG, this.structureId.toString());
            }
            tag.putString(BIOME_ID_TAG, this.biomeId.toString());
            tag.putInt(POS_X_TAG, this.pos.getX());
            tag.putInt(POS_Y_TAG, this.pos.getY());
            tag.putInt(POS_Z_TAG, this.pos.getZ());
            tag.putLong(GAME_TIME_TAG, Math.max(0L, this.gameTime));
            tag.putLong(DAY_TIME_TAG, Math.max(0L, this.dayTime));
            return tag;
        }

        public static ExcavationLogEntry fromTag(CompoundTag tag) {
            ResourceLocation itemId = tag.contains(ITEM_ID_TAG, Tag.TAG_STRING)
                    ? ResourceLocation.tryParse(tag.getString(ITEM_ID_TAG))
                    : null;
            ResourceLocation structureId = tag.contains(STRUCTURE_ID_TAG, Tag.TAG_STRING)
                    ? ResourceLocation.tryParse(tag.getString(STRUCTURE_ID_TAG))
                    : null;
            ResourceLocation biomeId = ResourceLocation.tryParse(tag.getString(BIOME_ID_TAG));
            if (biomeId == null) {
                biomeId = ResourceLocation.withDefaultNamespace("plains");
            }
            BlockPos pos = new BlockPos(tag.getInt(POS_X_TAG), tag.getInt(POS_Y_TAG), tag.getInt(POS_Z_TAG));
            long gameTime = Math.max(0L, tag.getLong(GAME_TIME_TAG));
            long dayTime = tag.contains(DAY_TIME_TAG, Tag.TAG_LONG)
                    ? Math.max(0L, tag.getLong(DAY_TIME_TAG))
                    : gameTime;
            return new ExcavationLogEntry(itemId, structureId, biomeId, pos, gameTime, dayTime);
        }
    }
}
