package com.meteorite.unsuspiciousblock.journal;

import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
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
    private static final String ENTRY_ID_TAG = "entry_id";
    private static final String TRIGGER_TYPE_TAG = "trigger_type";
    private static final String SOURCE_BLOCK_ID_TAG = "source_block_id";
    private static final String ITEM_ID_TAG = "item_id";
    private static final String STRUCTURE_ID_TAG = "structure_id";
    private static final String BIOME_ID_TAG = "biome_id";
    private static final String POS_X_TAG = "pos_x";
    private static final String POS_Y_TAG = "pos_y";
    private static final String POS_Z_TAG = "pos_z";
    private static final String GAME_TIME_TAG = "game_time";
    private static final String DAY_TIME_TAG = "day_time";
    private static final String CREATED_GAME_TIME_TAG = "created_game_time";
    private static final String CREATED_DAY_TIME_TAG = "created_day_time";
    private static final String LAST_UPDATED_GAME_TIME_TAG = "last_updated_game_time";
    private static final String LAST_UPDATED_DAY_TIME_TAG = "last_updated_day_time";
    private static final String EXPECTED_LOOT_TAG = "expected_loot";
    private static final String ACTUAL_LOOT_TAG = "actual_loot";

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

    public enum TriggerType {
        UNKNOWN("unknown"),
        BRUSH("brush"),
        READER("reader"),
        SPADE("spade"),
        CONTAINER("container");

        private final String serializedName;

        TriggerType(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return this.serializedName;
        }

        @Nullable
        public static TriggerType fromSerializedName(String name) {
            for (TriggerType value : values()) {
                if (value.serializedName.equals(name)) {
                    return value;
                }
            }
            return null;
        }
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

        public List<ExcavationLogEntry> getRecentEntries() {
            return this.getEntries();
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

        public boolean appendEntry(ExcavationLogEntry entry) {
            return this.upsertEntry(entry);
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

    public record ExcavationLogEntry(UUID entryId,
                                     @Nullable TriggerType triggerType,
                                     @Nullable ResourceLocation sourceBlockId,
                                     @Nullable ResourceLocation structureId,
                                     ResourceLocation biomeId,
                                     BlockPos pos,
                                     long createdGameTime,
                                     long createdDayTime,
                                     long lastUpdatedGameTime,
                                     long lastUpdatedDayTime,
                                     Map<String, Integer> expectedLoot,
                                     Map<String, Integer> actualLoot) {
        public ExcavationLogEntry {
            if (biomeId == null) {
                biomeId = ResourceLocation.withDefaultNamespace("plains");
            }
            if (pos == null) {
                pos = BlockPos.ZERO;
            }

            createdGameTime = Math.max(0L, createdGameTime);
            createdDayTime = Math.max(0L, createdDayTime);
            lastUpdatedGameTime = Math.max(0L, lastUpdatedGameTime);
            lastUpdatedDayTime = Math.max(0L, lastUpdatedDayTime);
            if (lastUpdatedGameTime < createdGameTime
                    || lastUpdatedGameTime == createdGameTime && lastUpdatedDayTime < createdDayTime) {
                lastUpdatedGameTime = createdGameTime;
                lastUpdatedDayTime = createdDayTime;
            }

            expectedLoot = normalizeLootMap(expectedLoot);
            actualLoot = normalizeLootMap(actualLoot);
        }

        public ExcavationLogEntry withActualLootMerged(Map<String, Integer> deltaLoot,
                                                       long updatedGameTime, long updatedDayTime) {
            LinkedHashMap<String, Integer> mergedActualLoot = new LinkedHashMap<>(this.actualLoot);
            mergeLootInto(mergedActualLoot, deltaLoot);
            return new ExcavationLogEntry(this.entryId, this.triggerType, this.sourceBlockId, this.structureId,
                    this.biomeId, this.pos, this.createdGameTime, this.createdDayTime,
                    updatedGameTime, updatedDayTime, this.expectedLoot, mergedActualLoot);
        }

        @Nullable
        public ResourceLocation itemId() {
            ResourceLocation actualItemId = firstItemId(this.actualLoot);
            if (actualItemId != null) {
                return actualItemId;
            }
            return firstItemId(this.expectedLoot);
        }

        public long gameTime() {
            return this.createdGameTime;
        }

        public long dayTime() {
            return this.createdDayTime;
        }

        public CompoundTag toTag() {
            CompoundTag tag = getCompoundTag();
            tag.putInt(POS_X_TAG, this.pos.getX());
            tag.putInt(POS_Y_TAG, this.pos.getY());
            tag.putInt(POS_Z_TAG, this.pos.getZ());
            tag.putLong(CREATED_GAME_TIME_TAG, this.createdGameTime);
            tag.putLong(CREATED_DAY_TIME_TAG, this.createdDayTime);
            tag.putLong(LAST_UPDATED_GAME_TIME_TAG, this.lastUpdatedGameTime);
            tag.putLong(LAST_UPDATED_DAY_TIME_TAG, this.lastUpdatedDayTime);
            tag.put(EXPECTED_LOOT_TAG, writeLootMap(this.expectedLoot));
            tag.put(ACTUAL_LOOT_TAG, writeLootMap(this.actualLoot));
            return tag;
        }

        private @NotNull CompoundTag getCompoundTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString(ENTRY_ID_TAG, this.entryId.toString());
            if (this.triggerType != null) {
                tag.putString(TRIGGER_TYPE_TAG, this.triggerType.serializedName());
            }
            if (this.sourceBlockId != null) {
                tag.putString(SOURCE_BLOCK_ID_TAG, this.sourceBlockId.toString());
            }
            if (this.structureId != null) {
                tag.putString(STRUCTURE_ID_TAG, this.structureId.toString());
            }
            tag.putString(BIOME_ID_TAG, this.biomeId.toString());
            return tag;
        }

        public static ExcavationLogEntry fromTag(CompoundTag tag) {
            UUID entryId = parseUuid(tag.getString(ENTRY_ID_TAG));
            if (entryId == null) {
                entryId = UUID.randomUUID();
            }
            TriggerType triggerType = tag.contains(TRIGGER_TYPE_TAG, Tag.TAG_STRING)
                    ? TriggerType.fromSerializedName(tag.getString(TRIGGER_TYPE_TAG))
                    : null;
            ResourceLocation sourceBlockId = tag.contains(SOURCE_BLOCK_ID_TAG, Tag.TAG_STRING)
                    ? ResourceLocation.tryParse(tag.getString(SOURCE_BLOCK_ID_TAG))
                    : null;
            ResourceLocation structureId = tag.contains(STRUCTURE_ID_TAG, Tag.TAG_STRING)
                    ? ResourceLocation.tryParse(tag.getString(STRUCTURE_ID_TAG))
                    : null;
            ResourceLocation biomeId = ResourceLocation.tryParse(tag.getString(BIOME_ID_TAG));
            if (biomeId == null) {
                biomeId = ResourceLocation.withDefaultNamespace("plains");
            }
            BlockPos pos = new BlockPos(tag.getInt(POS_X_TAG), tag.getInt(POS_Y_TAG), tag.getInt(POS_Z_TAG));

            long legacyGameTime = Math.max(0L, tag.getLong(GAME_TIME_TAG));
            long legacyDayTime = tag.contains(DAY_TIME_TAG, Tag.TAG_LONG)
                    ? Math.max(0L, tag.getLong(DAY_TIME_TAG))
                    : legacyGameTime;
            long createdGameTime = tag.contains(CREATED_GAME_TIME_TAG, Tag.TAG_LONG)
                    ? Math.max(0L, tag.getLong(CREATED_GAME_TIME_TAG))
                    : legacyGameTime;
            long createdDayTime = tag.contains(CREATED_DAY_TIME_TAG, Tag.TAG_LONG)
                    ? Math.max(0L, tag.getLong(CREATED_DAY_TIME_TAG))
                    : legacyDayTime;
            long lastUpdatedGameTime = tag.contains(LAST_UPDATED_GAME_TIME_TAG, Tag.TAG_LONG)
                    ? Math.max(0L, tag.getLong(LAST_UPDATED_GAME_TIME_TAG))
                    : createdGameTime;
            long lastUpdatedDayTime = tag.contains(LAST_UPDATED_DAY_TIME_TAG, Tag.TAG_LONG)
                    ? Math.max(0L, tag.getLong(LAST_UPDATED_DAY_TIME_TAG))
                    : createdDayTime;

            ResourceLocation legacyItemId = tag.contains(ITEM_ID_TAG, Tag.TAG_STRING)
                    ? ResourceLocation.tryParse(tag.getString(ITEM_ID_TAG))
                    : null;
            Map<String, Integer> expectedLoot = tag.contains(EXPECTED_LOOT_TAG, Tag.TAG_COMPOUND)
                    ? readLootMap(tag.getCompound(EXPECTED_LOOT_TAG))
                    : createLegacyLootMap(legacyItemId);
            Map<String, Integer> actualLoot = tag.contains(ACTUAL_LOOT_TAG, Tag.TAG_COMPOUND)
                    ? readLootMap(tag.getCompound(ACTUAL_LOOT_TAG))
                    : createLegacyLootMap(legacyItemId);
            return new ExcavationLogEntry(entryId, triggerType, sourceBlockId, structureId, biomeId, pos,
                    createdGameTime, createdDayTime, lastUpdatedGameTime, lastUpdatedDayTime,
                    expectedLoot, actualLoot);
        }

        @Nullable
        private static UUID parseUuid(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        private static Map<String, Integer> createLegacyLootMap(@Nullable ResourceLocation itemId) {
            if (itemId == null) {
                return Map.of();
            }
            return Map.of(LootResultSignature.plain(itemId).toStoredKey(), 1);
        }

        private static Map<String, Integer> normalizeLootMap(@Nullable Map<String, Integer> lootMap) {
            if (lootMap == null || lootMap.isEmpty()) {
                return Map.of();
            }
            LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
            mergeLootInto(normalized, lootMap);
            if (normalized.isEmpty()) {
                return Map.of();
            }
            return Collections.unmodifiableMap(normalized);
        }

        private static void mergeLootInto(Map<String, Integer> target, @Nullable Map<String, Integer> source) {
            if (source == null || source.isEmpty()) {
                return;
            }
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                String signatureKey = entry.getKey();
                Integer count = entry.getValue();
                if (signatureKey == null || signatureKey.isBlank() || count == null || count <= 0) {
                    continue;
                }
                target.merge(signatureKey, count, Integer::sum);
            }
        }

        @Nullable
        private static ResourceLocation firstItemId(Map<String, Integer> lootMap) {
            for (String signatureKey : lootMap.keySet()) {
                LootResultSignature signature = LootResultSignature.fromStoredKey(signatureKey);
                if (signature != null) {
                    return signature.itemId();
                }
            }
            return null;
        }

        private static CompoundTag writeLootMap(Map<String, Integer> lootMap) {
            CompoundTag tag = new CompoundTag();
            for (Map.Entry<String, Integer> entry : lootMap.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    tag.putInt(entry.getKey(), entry.getValue());
                }
            }
            return tag;
        }

        private static Map<String, Integer> readLootMap(CompoundTag tag) {
            if (tag.isEmpty()) {
                return Map.of();
            }
            LinkedHashMap<String, Integer> lootMap = new LinkedHashMap<>();
            for (String key : tag.getAllKeys()) {
                int count = tag.getInt(key);
                if (count > 0) {
                    lootMap.put(key, count);
                }
            }
            if (lootMap.isEmpty()) {
                return Map.of();
            }
            return Collections.unmodifiableMap(lootMap);
        }
    }
}
