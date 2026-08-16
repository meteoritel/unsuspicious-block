package com.meteorite.unsuspiciousblock.journal.state;

import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.journal.migration.JournalDataVersion;
import com.meteorite.unsuspiciousblock.journal.migration.JournalNbtMigrator;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return this.upsertEntryWithResult(tableId, entry).changed();
    }

    // 插入或更新条目，并返回累计数、拦截状态和自动淘汰结果
    public EntryUpsertResult upsertEntryWithResult(ResourceLocation tableId, ExcavationLogEntry entry) {
        return this.getOrCreateTable(tableId).upsertEntryWithResult(entry);
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

    // 批量移除指定表中的日志条目
    public int removeEntries(ResourceLocation tableId, Set<UUID> entryIds) {
        TableLogHistory history = this.tables.get(tableId);
        return history != null ? history.removeEntries(entryIds) : 0;
    }

    // 清空指定表的条目，保留累计数、首次解锁元数据与玩家保留策略
    public int clearEntries(ResourceLocation tableId) {
        TableLogHistory history = this.tables.get(tableId);
        return history != null ? history.clearEntries() : 0;
    }

    // 清空所有表的条目，保留各表累计数与保留策略
    public int clearAllEntries() {
        int removed = 0;
        for (TableLogHistory history : this.tables.values()) {
            removed += history.clearEntries();
        }
        return removed;
    }

    // 更新单表自动保留上限，并立即淘汰超限的无备注旧条目
    public RetentionUpdateResult setRetentionLimit(ResourceLocation tableId, int limit) {
        return this.getOrCreateTable(tableId).setRetentionLimit(limit);
    }

    // 仅保留时间最新的指定数量，带备注条目不受本次批量清理影响
    public List<UUID> pruneToMostRecent(ResourceLocation tableId, int keepCount) {
        TableLogHistory history = this.tables.get(tableId);
        return history != null ? history.pruneToMostRecent(keepCount) : List.of();
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
        tag.putInt(JournalDataVersion.NBT_VERSION_TAG, JournalDataVersion.CURRENT_NBT_VERSION);
        CompoundTag tablesTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, TableLogHistory> entry : this.tables.entrySet()) {
            tablesTag.put(entry.getKey().toString(), entry.getValue().toTag());
        }
        tag.put(TABLES_TAG, tablesTag);
    }

    // 从 CompoundTag 反序列化恢复日志状态
    public void readFrom(CompoundTag tag) {
        this.clear();
        JournalNbtMigrator.migrateJournalLogState(tag);
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

    /** 单条日志 upsert 的服务端决策结果。 */
    public record EntryUpsertResult(boolean changed,
                                    boolean added,
                                    boolean blocked,
                                    long previousLifetimeCount,
                                    long currentLifetimeCount,
                                    List<UUID> removedEntryIds) {
    }

    /** 修改单表自动保留上限后的结果。 */
    public record RetentionUpdateResult(int retentionLimit, List<UUID> removedEntryIds) {
    }

    public static final class TableLogHistory {
        private static final String LIFETIME_ENTRY_COUNT_TAG = "lifetime_entry_count";
        private static final String RETENTION_LIMIT_TAG = "retention_limit";

        // 服务端配置是所有玩家、所有表共享的最后兜底上限
        public static int getGlobalMaxEntries() {
            return Math.max(1, Services.LOOT_TABLE_CONFIG.getMaxLogEntriesPerTable());
        }

        @Nullable
        private Long firstUnlockedGameTime;
        @Nullable
        private Long firstUnlockedDayTime;
        @Nullable
        private LootSourceType firstUnlockLootSource;
        private final LinkedHashMap<UUID, ExcavationLogEntry> entries = new LinkedHashMap<>();
        private long lifetimeEntryCount;
        private int retentionLimit = getGlobalMaxEntries();
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

        public long getLifetimeEntryCount() {
            return this.lifetimeEntryCount;
        }

        public int getRetentionLimit() {
            return this.retentionLimit;
        }

        public int getEffectiveRetentionLimit() {
            return Math.min(this.retentionLimit, getGlobalMaxEntries());
        }

        public int getNotedEntryCount() {
            int count = 0;
            for (ExcavationLogEntry entry : this.entries.values()) {
                if (entry.hasNote()) {
                    count++;
                }
            }
            return count;
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

        private int removeEntries(Set<UUID> entryIds) {
            if (entryIds == null || entryIds.isEmpty()) {
                return 0;
            }
            int previousSize = this.entries.size();
            this.entries.keySet().removeIf(entryIds::contains);
            int removed = previousSize - this.entries.size();
            if (removed > 0) {
                this.entriesVersion++;
            }
            return removed;
        }

        private int clearEntries() {
            int removed = this.entries.size();
            if (removed > 0) {
                this.entries.clear();
                this.entriesVersion++;
            }
            return removed;
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

        private EntryUpsertResult upsertEntryWithResult(ExcavationLogEntry entry) {
            ExcavationLogEntry previous = this.entries.get(entry.entryId());
            long previousLifetimeCount = this.lifetimeEntryCount;
            if (previous == null && this.getNotedEntryCount() >= this.getEffectiveRetentionLimit()) {
                return new EntryUpsertResult(false, false, true,
                        previousLifetimeCount, previousLifetimeCount, List.of());
            }

            this.entries.put(entry.entryId(), entry);
            boolean added = previous == null;
            if (added && this.lifetimeEntryCount < Long.MAX_VALUE) {
                this.lifetimeEntryCount++;
            }
            List<UUID> removedEntryIds = added
                    ? this.trimEntriesToLimit(this.getEffectiveRetentionLimit())
                    : List.of();
            boolean changed = !entry.equals(previous) || !removedEntryIds.isEmpty();
            if (changed) {
                this.entriesVersion++;
            }
            return new EntryUpsertResult(changed, added, false,
                    previousLifetimeCount, this.lifetimeEntryCount, removedEntryIds);
        }

        private RetentionUpdateResult setRetentionLimit(int limit) {
            this.retentionLimit = Math.max(1, Math.min(limit, getGlobalMaxEntries()));
            List<UUID> removedEntryIds = this.trimEntriesToLimit(this.getEffectiveRetentionLimit());
            if (!removedEntryIds.isEmpty()) {
                this.entriesVersion++;
            }
            return new RetentionUpdateResult(this.retentionLimit, removedEntryIds);
        }

        private List<UUID> pruneToMostRecent(int keepCount) {
            int normalizedKeepCount = Math.max(0, keepCount);
            List<ExcavationLogEntry> newestFirst = new ArrayList<>(this.entries.values());
            newestFirst.sort(Comparator
                    .comparingLong(ExcavationLogEntry::lastUpdatedGameTime)
                    .thenComparingLong(ExcavationLogEntry::lastUpdatedDayTime)
                    .thenComparingLong(ExcavationLogEntry::createdGameTime)
                    .thenComparingLong(ExcavationLogEntry::createdDayTime)
                    .thenComparing(ExcavationLogEntry::entryId)
                    .reversed());
            Set<UUID> retained = new HashSet<>();
            for (int index = 0; index < Math.min(normalizedKeepCount, newestFirst.size()); index++) {
                retained.add(newestFirst.get(index).entryId());
            }
            List<UUID> removed = new ArrayList<>();
            var iterator = this.entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, ExcavationLogEntry> candidate = iterator.next();
                if (!candidate.getValue().hasNote() && !retained.contains(candidate.getKey())) {
                    removed.add(candidate.getKey());
                    iterator.remove();
                }
            }
            if (!removed.isEmpty()) {
                this.entriesVersion++;
            }
            return List.copyOf(removed);
        }

        // 超出指定上限时移除最旧的无备注条目；带备注条目始终受保护
        private List<UUID> trimEntriesToLimit(int limit) {
            List<UUID> removed = new ArrayList<>();
            var iterator = this.entries.entrySet().iterator();
            while (iterator.hasNext() && this.entries.size() > limit) {
                Map.Entry<UUID, ExcavationLogEntry> candidate = iterator.next();
                if (!candidate.getValue().hasNote()) {
                    removed.add(candidate.getKey());
                    iterator.remove();
                }
            }
            return List.copyOf(removed);
        }

        public TableLogHistory copy() {
            TableLogHistory copy = new TableLogHistory();
            copy.firstUnlockedGameTime = this.firstUnlockedGameTime;
            copy.firstUnlockedDayTime = this.firstUnlockedDayTime;
            copy.firstUnlockLootSource = this.firstUnlockLootSource;
            copy.lifetimeEntryCount = this.lifetimeEntryCount;
            copy.retentionLimit = this.retentionLimit;
            copy.entries.putAll(this.entries);
            return copy;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putInt(JournalDataVersion.NBT_VERSION_TAG, JournalDataVersion.CURRENT_NBT_VERSION);
            if (this.firstUnlockedGameTime != null) {
                tag.putLong(FIRST_UNLOCKED_TIME_TAG, this.firstUnlockedGameTime);
                tag.putLong(FIRST_UNLOCKED_DAY_TIME_TAG,
                        this.firstUnlockedDayTime != null ? this.firstUnlockedDayTime : this.firstUnlockedGameTime);
            }
            if (this.firstUnlockLootSource != null) {
                tag.putString(FIRST_UNLOCK_LOOT_SOURCE_TAG, this.firstUnlockLootSource.id().toString());
            }
            tag.putLong(LIFETIME_ENTRY_COUNT_TAG, this.lifetimeEntryCount);
            tag.putInt(RETENTION_LIMIT_TAG, this.retentionLimit);
            ListTag entriesTag = new ListTag();
            for (ExcavationLogEntry entry : this.entries.values()) {
                entriesTag.add(entry.toTag());
            }
            tag.put(ENTRIES_TAG, entriesTag);
            return tag;
        }

        public static TableLogHistory fromTag(CompoundTag tag) {
            TableLogHistory history = new TableLogHistory();
            JournalNbtMigrator.migrateTableLogHistory(tag);
            if (tag.contains(FIRST_UNLOCKED_TIME_TAG, Tag.TAG_LONG)) {
                history.firstUnlockedGameTime = Math.max(0L, tag.getLong(FIRST_UNLOCKED_TIME_TAG));
                history.firstUnlockedDayTime = tag.contains(FIRST_UNLOCKED_DAY_TIME_TAG, Tag.TAG_LONG)
                        ? Math.max(0L, tag.getLong(FIRST_UNLOCKED_DAY_TIME_TAG))
                        : history.firstUnlockedGameTime;
            }
            if (tag.contains(FIRST_UNLOCK_LOOT_SOURCE_TAG, Tag.TAG_STRING)) {
                history.firstUnlockLootSource = LootSourceType.fromId(tag.getString(FIRST_UNLOCK_LOOT_SOURCE_TAG));
            }
            history.lifetimeEntryCount = Math.max(0L, tag.getLong(LIFETIME_ENTRY_COUNT_TAG));
            int storedRetentionLimit = tag.getInt(RETENTION_LIMIT_TAG);
            history.retentionLimit = storedRetentionLimit > 0
                    ? storedRetentionLimit : getGlobalMaxEntries();
            if (tag.contains(ENTRIES_TAG, Tag.TAG_LIST)) {
                ListTag entriesTag = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
                for (int i = 0; i < entriesTag.size(); i++) {
                    ExcavationLogEntry entry = ExcavationLogEntry.fromTag(entriesTag.getCompound(i));
                    // 读盘只恢复磁盘内容；条目上限淘汰仅发生在运行时新增路径。
                    history.entries.put(entry.entryId(), entry);
                }
            }
            history.lifetimeEntryCount = Math.max(history.lifetimeEntryCount, history.entries.size());
            return history;
        }
    }
}
