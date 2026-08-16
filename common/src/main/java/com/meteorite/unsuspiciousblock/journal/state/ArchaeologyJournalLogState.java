package com.meteorite.unsuspiciousblock.journal.state;

import com.meteorite.unsuspiciousblock.platform.Services;

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

/**
 * 考古日志的历史记录状态——记录每个战利品表的首次解锁时间与所有发掘日志条目。
 * 每张表对应一个 TableLogHistory，内含 firstUnlockMeta 与 ExcavationLogEntry 列表。
 * 提供服务端-客户端双向同步支持（配合 sync 子包中的 ArchaeologyJournalLogSyncSession）。
 */
public final class ArchaeologyJournalLogState {
    private static final String TABLES_TAG = "tables";
    private static final String FIRST_UNLOCKED_TIME_TAG = "first_unlocked_game_time";
    private static final String FIRST_UNLOCKED_DAY_TIME_TAG = "first_unlocked_day_time";
    private static final String FIRST_UNLOCK_LOOT_SOURCE_TAG = "first_unlock_loot_source";
    private static final String ENTRIES_TAG = "entries";

    private final LinkedHashMap<ResourceLocation, TableLogHistory> tables = new LinkedHashMap<>();

    // 清除所有表的历史记录
    public void clear() {
        this.tables.clear();
    }

    @Nullable
    // 获取指定表的历史记录
    public TableLogHistory getTable(ResourceLocation tableId) {
        return this.tables.get(tableId);
    }

    // 获取所有表历史记录的只读映射
    public Map<ResourceLocation, TableLogHistory> getTables() {
        return Collections.unmodifiableMap(this.tables);
    }

    // 检查是否没有任何历史记录
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

    // 设置（或保留最小）首次解锁时间元数据
    public boolean setFirstUnlockMetaMin(ResourceLocation tableId, @Nullable LootSourceType lootSource,
                                         long gameTime, long dayTime) {
        return this.getOrCreateTable(tableId).setFirstUnlockMetaMin(lootSource, gameTime, dayTime);
    }

    // 插入或更新指定表中的日志条目（基于 entryId 去重）
    public boolean upsertEntry(ResourceLocation tableId, ExcavationLogEntry entry) {
        return this.getOrCreateTable(tableId).upsertEntry(entry);
    }

    // 检查指定表中是否已存在日志条目
    public boolean containsEntry(ResourceLocation tableId, UUID entryId) {
        TableLogHistory history = this.tables.get(tableId);
        return history != null && history.containsEntry(entryId);
    }

    // 移除指定表及其所有日志条目
    public boolean removeTable(ResourceLocation tableId) {
        return this.tables.remove(tableId) != null;
    }

    // 移除指定表中的单条日志；表的首次解锁元数据保持不变
    public boolean removeEntry(ResourceLocation tableId, UUID entryId) {
        TableLogHistory history = this.tables.get(tableId);
        return history != null && history.removeEntry(entryId);
    }

    // 分片存储加载入口：以已反序列化的表历史覆盖对应表
    public void putTable(ResourceLocation tableId, TableLogHistory history) {
        if (tableId != null && history != null) {
            this.tables.put(tableId, history);
        }
    }

    // 深度复制整个日志状态
    public ArchaeologyJournalLogState copy() {
        ArchaeologyJournalLogState copy = new ArchaeologyJournalLogState();
        copy.copyFrom(this);
        return copy;
    }

    // 从另一个日志状态复制全部数据
    public void copyFrom(ArchaeologyJournalLogState other) {
        this.tables.clear();
        for (Map.Entry<ResourceLocation, TableLogHistory> entry : other.tables.entrySet()) {
            this.tables.put(entry.getKey(), entry.getValue().copy());
        }
    }

    // 序列化为 NBT
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        this.writeTo(tag);
        return tag;
    }

    // 将日志状态写入已有的 CompoundTag
    public void writeTo(CompoundTag tag) {
        tag.putInt(NbtDataVersion.TAG, NbtDataVersion.CURRENT);
        CompoundTag tablesTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, TableLogHistory> entry : this.tables.entrySet()) {
            tablesTag.put(entry.getKey().toString(), entry.getValue().toTag());
        }
        tag.put(TABLES_TAG, tablesTag);
    }

    // 从 CompoundTag 反序列化恢复日志状态
    public void readFrom(CompoundTag tag) {
        this.clear();
        int version = NbtDataMigrator.migrateIfNeeded(tag, "ArchaeologyJournalLogState");
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
        // 单个表的日志条目上限，从配置读取
        public static int getMaxEntries() {
            return Services.LOOT_TABLE_CONFIG.getMaxLogEntriesPerTable();
        }

        @Nullable
        private Long firstUnlockedGameTime;
        @Nullable
        private Long firstUnlockedDayTime;
        @Nullable
        private LootSourceType firstUnlockLootSource;
        private final LinkedHashMap<UUID, ExcavationLogEntry> entries = new LinkedHashMap<>();
        // 懒缓存：仅在 entries 修改后重建
        private int entriesVersion = 0;
        private int cachedEntriesVersion = -1;
        private List<ExcavationLogEntry> cachedEntries = List.of();

        @Nullable
        public Long getFirstUnlockedGameTime() {
            return this.firstUnlockedGameTime;
        }

        @Nullable
        public Long getFirstUnlockedDayTime() {
            return this.firstUnlockedDayTime;
        }

        @Nullable
        public LootSourceType getFirstUnlockLootSource() {
            return this.firstUnlockLootSource;
        }

        public List<ExcavationLogEntry> getEntries() {
            if (this.cachedEntriesVersion != this.entriesVersion) {
                this.cachedEntries = List.copyOf(this.entries.values());
                this.cachedEntriesVersion = this.entriesVersion;
            }
            return this.cachedEntries;
        }

        public int getTotalEntryCount() {
            return this.entries.size();
        }

        public boolean containsEntry(UUID entryId) {
            return entryId != null && this.entries.containsKey(entryId);
        }

        private boolean removeEntry(UUID entryId) {
            if (entryId == null || this.entries.remove(entryId) == null) {
                return false;
            }
            this.entriesVersion++;
            return true;
        }

        private boolean setFirstUnlockMetaMin(@Nullable LootSourceType lootSource, long gameTime, long dayTime) {
            long normalizedGameTime = Math.max(0L, gameTime);
            long normalizedDayTime = Math.max(0L, dayTime);
            if (this.firstUnlockedGameTime == null) {
                this.firstUnlockedGameTime = normalizedGameTime;
                this.firstUnlockedDayTime = normalizedDayTime;
                this.firstUnlockLootSource = lootSource;
                return true;
            }

            long currentDayTime = this.firstUnlockedDayTime != null ? this.firstUnlockedDayTime : this.firstUnlockedGameTime;
            if (normalizedGameTime < this.firstUnlockedGameTime
                    || normalizedGameTime == this.firstUnlockedGameTime && normalizedDayTime < currentDayTime) {
                this.firstUnlockedGameTime = normalizedGameTime;
                this.firstUnlockedDayTime = normalizedDayTime;
                this.firstUnlockLootSource = lootSource;
                return true;
            }

            if (normalizedGameTime == this.firstUnlockedGameTime && normalizedDayTime == currentDayTime
                    && this.firstUnlockLootSource == null && lootSource != null) {
                this.firstUnlockLootSource = lootSource;
                return true;
            }
            return false;
        }

        private boolean upsertEntry(ExcavationLogEntry entry) {
            ExcavationLogEntry previous = this.entries.put(entry.entryId(), entry);
            // 仅新增条目触发淘汰。清空备注会让旧条目恢复资格，下一次新增时参与淘汰。
            if (previous == null) {
                this.trimEntriesToLimit();
            }
            boolean changed = !entry.equals(previous);
            if (changed) {
                this.entriesVersion++;
            }
            return changed;
        }

        // 超出上限时移除最旧的无备注条目；带备注条目始终受保护
        private void trimEntriesToLimit() {
            var it = this.entries.values().iterator();
            while (it.hasNext() && this.entries.size() > getMaxEntries()) {
                ExcavationLogEntry candidate = it.next();
                if (!candidate.hasNote()) {
                    it.remove();
                }
            }
        }

        public TableLogHistory copy() {
            TableLogHistory copy = new TableLogHistory();
            copy.firstUnlockedGameTime = this.firstUnlockedGameTime;
            copy.firstUnlockedDayTime = this.firstUnlockedDayTime;
            copy.firstUnlockLootSource = this.firstUnlockLootSource;
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
            if (this.firstUnlockLootSource != null) {
                tag.putString(FIRST_UNLOCK_LOOT_SOURCE_TAG, this.firstUnlockLootSource.id().toString());
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
            // 向后兼容：优先读取新字段，其次读取旧字段
            @Deprecated // 将于 1.5.0 移除
            String LOOT_SOURCE_LEGACY_TAG = "first_unlock_trigger_type";
            if (tag.contains(FIRST_UNLOCK_LOOT_SOURCE_TAG, Tag.TAG_STRING)) {
                history.firstUnlockLootSource = LootSourceType.fromId(tag.getString(FIRST_UNLOCK_LOOT_SOURCE_TAG));
            } else if (tag.contains(LOOT_SOURCE_LEGACY_TAG, Tag.TAG_STRING)) {
                history.firstUnlockLootSource = LootSourceType.fromId(tag.getString(LOOT_SOURCE_LEGACY_TAG));
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
