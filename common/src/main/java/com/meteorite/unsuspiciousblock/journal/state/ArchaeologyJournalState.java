package com.meteorite.unsuspiciousblock.journal.state;

import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 玩家考古日记的进度状态——管理战利品表的解锁与物品收集计数。
 * 每个表对应一个 TableProgress，表内每个物品对应一个 ItemProgress。
 * 提供序列化 (toTag / fromTag) 支持，通过 mixin 附加在玩家 NBT 中持久化。
 */
public final class ArchaeologyJournalState {
    private static final String TABLES_TAG = "tables";
    private static final String UNLOCKED_TAG = "unlocked";
    private static final String ITEMS_TAG = "items";
    private static final String COUNT_TAG = "count";
    private static final String REVISION_TAG = "revision";

    private final LinkedHashMap<ResourceLocation, TableProgress> tables = new LinkedHashMap<>();
    // 增量同步：版本号，每次变更递增
    private long revision;
    // 增量同步：脏表追踪
    private final LinkedHashSet<ResourceLocation> dirtyTables = new LinkedHashSet<>();

    // 清除所有表进度
    public void clear() {
        this.tables.clear();
        this.dirtyTables.clear();
        this.revision++;
    }

    // 获取当前版本号
    public long getRevision() {
        return this.revision;
    }

    // 收集并清空脏表集合，用于增量同步
    public Set<ResourceLocation> drainDirtyTables() {
        Set<ResourceLocation> dirty = new LinkedHashSet<>(this.dirtyTables);
        this.dirtyTables.clear();
        return dirty;
    }

    // 将变更的表数据写入 NBT（仅包含脏表的进度）
    public CompoundTag writeDirtyTablesToTag(Set<ResourceLocation> dirty) {
        CompoundTag tag = new CompoundTag();
        for (ResourceLocation tableId : dirty) {
            TableProgress progress = this.tables.get(tableId);
            if (progress != null) {
                tag.put(tableId.toString(), progress.toTag());
            }
        }
        return tag;
    }

    // 从增量 NBT 合并变更的表到现有状态
    public void mergeFromIncremental(CompoundTag incremental, long newRevision) {
        CompoundTag tablesTag = incremental;
        for (String key : tablesTag.getAllKeys()) {
            ResourceLocation tableId = ResourceLocation.tryParse(key);
            if (tableId == null) {
                continue;
            }
            this.tables.put(tableId, TableProgress.fromTag(tablesTag.getCompound(key)));
        }
        this.revision = newRevision;
    }

    // 解锁指定战利品表（同时创建 TableProgress）
    public boolean unlockTable(ResourceLocation tableId) {
        boolean changed = this.getOrCreateTable(tableId).unlock();
        if (changed) {
            this.markDirty(tableId);
        }
        return changed;
    }

    // 解锁指定表中指定物品（表会自动解锁）
    public boolean unlockItem(ResourceLocation tableId, LootResultSignature signature) {
        TableProgress table = this.getOrCreateTable(tableId);
        boolean tableChanged = table.unlock();
        boolean itemChanged = table.unlockItem(signature);
        if (tableChanged || itemChanged) {
            this.markDirty(tableId);
        }
        return tableChanged || itemChanged;
    }

    // 批量解锁指定表中的多个物品（去重后操作）
    public boolean unlockItems(ResourceLocation tableId, Iterable<LootResultSignature> signatures) {
        if (signatures == null) {
            return false;
        }

        LinkedHashMap<String, LootResultSignature> uniqueSignatures = new LinkedHashMap<>();
        for (LootResultSignature signature : signatures) {
            if (signature == null) {
                continue;
            }
            uniqueSignatures.putIfAbsent(signature.toStoredKey(), signature);
        }
        if (uniqueSignatures.isEmpty()) {
            return false;
        }

        TableProgress table = this.getOrCreateTable(tableId);
        boolean changed = table.unlock();
        changed |= table.unlockItems(uniqueSignatures.values());
        if (changed) {
            this.markDirty(tableId);
        }
        return changed;
    }

    // 记录单个物品的获取数量
    public boolean recordItemAcquired(ResourceLocation tableId, LootResultSignature signature, int count) {
        if (count <= 0) {
            return false;
        }

        TableProgress table = this.getOrCreateTable(tableId);
        boolean tableChanged = table.unlock();
        boolean itemChanged = table.recordItemAcquired(signature, count);
        if (tableChanged || itemChanged) {
            this.markDirty(tableId);
        }
        return tableChanged || itemChanged;
    }

    // 批量记录多个物品的获取数量
    public boolean recordItemsAcquired(ResourceLocation tableId, Map<LootResultSignature, Integer> counts) {
        if (counts == null) {
            return false;
        }

        LinkedHashMap<LootResultSignature, Integer> normalizedCounts = new LinkedHashMap<>();
        for (Map.Entry<LootResultSignature, Integer> entry : counts.entrySet()) {
            LootResultSignature signature = entry.getKey();
            Integer count = entry.getValue();
            if (signature == null || count == null || count <= 0) {
                continue;
            }
            normalizedCounts.merge(signature, count, Integer::sum);
        }
        if (normalizedCounts.isEmpty()) {
            return false;
        }

        TableProgress table = this.getOrCreateTable(tableId);
        boolean changed = table.unlock();
        changed |= table.recordItemsAcquired(normalizedCounts);
        if (changed) {
            this.markDirty(tableId);
        }
        return changed;
    }

    // 移除指定表及其所有物品进度
    public boolean removeTable(ResourceLocation tableId) {
        TableProgress removed = this.tables.remove(tableId);
        if (removed != null) {
            this.dirtyTables.remove(tableId);
            this.revision++;
            return true;
        }
        return false;
    }

    // 检查指定表是否已解锁
    public boolean isTableUnlocked(ResourceLocation tableId) {
        TableProgress table = this.tables.get(tableId);
        return table != null && table.isUnlocked();
    }

    // 检查指定表中某个物品是否已解锁
    public boolean isItemUnlocked(ResourceLocation tableId, LootResultSignature signature) {
        TableProgress table = this.tables.get(tableId);
        return table != null && table.isItemUnlocked(signature);
    }

    // 获取所有表进度的只读映射
    public Map<ResourceLocation, TableProgress> getTables() {
        return Collections.unmodifiableMap(this.tables);
    }

    @Nullable
    // 获取指定表的进度（不存在返回 null）
    public TableProgress getTable(ResourceLocation tableId) {
        return this.tables.get(tableId);
    }

    @Nullable
    // 获取指定表中某物品的进度（不存在返回 null）
    public ItemProgress getItemProgress(ResourceLocation tableId, LootResultSignature signature) {
        TableProgress table = this.tables.get(tableId);
        return table != null ? table.getItemProgress(signature) : null;
    }

    // 获取指定表中某物品的收集数量
    public int getItemCount(ResourceLocation tableId, LootResultSignature signature) {
        TableProgress table = this.tables.get(tableId);
        return table != null ? table.getItemCount(signature) : 0;
    }

    // 深度复制整个状态
    public ArchaeologyJournalState copy() {
        ArchaeologyJournalState copy = new ArchaeologyJournalState();
        copy.copyFrom(this);
        return copy;
    }

    // 从另一个状态复制全部数据（包括 revision）
    public void copyFrom(ArchaeologyJournalState other) {
        this.tables.clear();
        for (Map.Entry<ResourceLocation, TableProgress> entry : other.tables.entrySet()) {
            this.tables.put(entry.getKey(), entry.getValue().copy());
        }
        this.revision = other.revision;
        this.dirtyTables.clear();
    }

    // 序列化为 NBT（创建新 CompoundTag 并写入）
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        this.writeTo(tag);
        return tag;
    }

    // 将状态写入已有的 CompoundTag
    public void writeTo(CompoundTag tag) {
        tag.putLong(REVISION_TAG, this.revision);
        CompoundTag tablesTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, TableProgress> entry : this.tables.entrySet()) {
            tablesTag.put(entry.getKey().toString(), entry.getValue().toTag());
        }
        tag.put(TABLES_TAG, tablesTag);
    }

    // 从 CompoundTag 反序列化恢复状态
    public void readFrom(CompoundTag tag) {
        this.clear();
        this.revision = tag.contains(REVISION_TAG, Tag.TAG_LONG)
                ? Math.max(0L, tag.getLong(REVISION_TAG)) : 0L;
        if (!tag.contains(TABLES_TAG, Tag.TAG_COMPOUND)) {
            return;
        }

        CompoundTag tablesTag = tag.getCompound(TABLES_TAG);
        for (String key : tablesTag.getAllKeys()) {
            ResourceLocation tableId = ResourceLocation.tryParse(key);
            if (tableId == null) {
                continue;
            }

            this.tables.put(tableId, TableProgress.fromTag(tablesTag.getCompound(key)));
        }
    }

    // 静态工厂：从 CompoundTag 创建新的状态实例
    public static ArchaeologyJournalState fromTag(CompoundTag tag) {
        ArchaeologyJournalState state = new ArchaeologyJournalState();
        state.readFrom(tag);
        return state;
    }

    // 标记指定表为脏，并递增版本号
    private void markDirty(ResourceLocation tableId) {
        this.dirtyTables.add(tableId);
        this.revision++;
    }

    private TableProgress getOrCreateTable(ResourceLocation tableId) {
        return this.tables.computeIfAbsent(tableId, ignored -> new TableProgress());
    }

    public static final class TableProgress {
        private boolean unlocked;
        private final LinkedHashMap<String, ItemProgress> items = new LinkedHashMap<>();

        public boolean isUnlocked() {
            return this.unlocked;
        }

        @Nullable
        public ItemProgress getItemProgress(LootResultSignature signature) {
            if (signature == null) {
                return null;
            }
            return this.items.get(signature.toStoredKey());
        }

        public boolean isItemUnlocked(LootResultSignature signature) {
            ItemProgress item = this.getItemProgress(signature);
            return item != null && item.isUnlocked();
        }

        public int getItemCount(LootResultSignature signature) {
            ItemProgress item = this.getItemProgress(signature);
            return item != null && item.isUnlocked() ? item.getCount() : 0;
        }

        public boolean unlock() {
            if (this.unlocked) {
                return false;
            }

            this.unlocked = true;
            return true;
        }

        public boolean unlockItem(LootResultSignature signature) {
            return this.getOrCreateItem(signature).unlock();
        }

        public boolean unlockItems(Iterable<LootResultSignature> signatures) {
            if (signatures == null) {
                return false;
            }

            boolean changed = false;
            for (LootResultSignature signature : signatures) {
                if (signature == null) {
                    continue;
                }
                changed |= this.unlockItem(signature);
            }
            return changed;
        }

        public boolean recordItemAcquired(LootResultSignature signature, int count) {
            if (count <= 0) {
                return false;
            }

            ItemProgress item = this.getOrCreateItem(signature);
            boolean changed = item.unlock();
            changed |= item.incrementCount(count);
            return changed;
        }

        public boolean recordItemsAcquired(Map<LootResultSignature, Integer> counts) {
            if (counts == null) {
                return false;
            }

            boolean changed = false;
            for (Map.Entry<LootResultSignature, Integer> entry : counts.entrySet()) {
                LootResultSignature signature = entry.getKey();
                Integer count = entry.getValue();
                if (signature == null || count == null || count <= 0) {
                    continue;
                }
                changed |= this.recordItemAcquired(signature, count);
            }
            return changed;
        }

        public boolean isEmpty() {
            return !this.unlocked && this.items.isEmpty();
        }

        public TableProgress copy() {
            TableProgress copy = new TableProgress();
            copy.unlocked = this.unlocked;
            for (Map.Entry<String, ItemProgress> entry : this.items.entrySet()) {
                copy.items.put(entry.getKey(), entry.getValue().copy());
            }
            return copy;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean(UNLOCKED_TAG, this.unlocked);

            CompoundTag itemsTag = new CompoundTag();
            for (Map.Entry<String, ItemProgress> entry : this.items.entrySet()) {
                itemsTag.put(entry.getKey(), entry.getValue().toTag());
            }
            tag.put(ITEMS_TAG, itemsTag);
            return tag;
        }

        public static TableProgress fromTag(CompoundTag tag) {
            TableProgress progress = new TableProgress();
            progress.unlocked = tag.getBoolean(UNLOCKED_TAG);

            if (tag.contains(ITEMS_TAG, Tag.TAG_COMPOUND)) {
                CompoundTag itemsTag = tag.getCompound(ITEMS_TAG);
                for (String key : itemsTag.getAllKeys()) {
                    LootResultSignature signature = LootResultSignature.fromStoredKey(key);
                    if (signature == null) {
                        continue;
                    }

                    progress.items.put(signature.toStoredKey(), ItemProgress.fromTag(itemsTag.getCompound(key)));
                }
            }

            return progress;
        }

        private ItemProgress getOrCreateItem(LootResultSignature signature) {
            return this.items.computeIfAbsent(signature.toStoredKey(), ignored -> new ItemProgress());
        }
    }

    public static final class ItemProgress {
        private boolean unlocked;
        private int count;

        public boolean isUnlocked() {
            return this.unlocked;
        }

        public int getCount() {
            return this.count;
        }

        public boolean unlock() {
            if (this.unlocked) {
                return false;
            }

            this.unlocked = true;
            return true;
        }

        public boolean incrementCount() {
            return this.incrementCount(1);
        }

        public boolean incrementCount(int amount) {
            if (amount <= 0) {
                return false;
            }

            this.count += amount;
            return true;
        }

        public ItemProgress copy() {
            ItemProgress copy = new ItemProgress();
            copy.unlocked = this.unlocked;
            copy.count = this.count;
            return copy;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean(UNLOCKED_TAG, this.unlocked);
            tag.putInt(COUNT_TAG, this.count);
            return tag;
        }

        public static ItemProgress fromTag(CompoundTag tag) {
            ItemProgress progress = new ItemProgress();
            progress.unlocked = tag.getBoolean(UNLOCKED_TAG);
            progress.count = Math.max(0, tag.getInt(COUNT_TAG));
            return progress;
        }
    }
}
