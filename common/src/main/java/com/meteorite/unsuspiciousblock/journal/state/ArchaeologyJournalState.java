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
    private static final String COMPLETION_REWARD_CLAIMED_TAG = "completion_reward_claimed";

    private final LinkedHashMap<ResourceLocation, TableProgress> tables = new LinkedHashMap<>();
    // 增量同步：版本号，每次发送增量包时递增（由 drainDirtyTables 触发）
    // clear/removeTable 等全量重置操作也会 +1，对应全量同步
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
    // 若存在脏表，递增 revision，保证一次增量同步只 +1
    // 这样一次业务操作内多次 markDirty 不会让 revision 跳跃，
    // 客户端的间隙检测（incomingRevision > lastNotifiedRevision + 1）才具有真实意义
    public Set<ResourceLocation> drainDirtyTables() {
        Set<ResourceLocation> dirty = new LinkedHashSet<>(this.dirtyTables);
        this.dirtyTables.clear();
        if (!dirty.isEmpty()) {
            this.revision++;
        }
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

    /**
     * 从增量 NBT 合并变更的表到现有状态。
     * 采用服务端权威语义：增量数据中的条目会直接覆盖本地对应条目，
     * 客户端不会对服务端发来的数据进行二次合并或裁剪。
     */
    public void mergeFromIncremental(CompoundTag tablesTag, long newRevision) {
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

    // 标记指定表的 100% 完成奖励已发放，返回是否为首次标记（true=本次 newly claimed）
    // 若表不存在或已标记过，返回 false；调用方据此决定是否发放奖励
    public boolean claimCompletionReward(ResourceLocation tableId) {
        TableProgress table = this.tables.get(tableId);
        if (table == null || table.isCompletionRewardClaimed()) {
            return false;
        }
        table.setCompletionRewardClaimed(true);
        this.markDirty(tableId);
        return true;
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

    // 深度复制整个状态
    public ArchaeologyJournalState copy() {
        ArchaeologyJournalState copy = new ArchaeologyJournalState();
        copy.copyFrom(this);
        return copy;
    }

    // 仅复制指定表集合的进度，用于增量 Diff
    public ArchaeologyJournalState partialCopy(Set<ResourceLocation> tableIds) {
        ArchaeologyJournalState partial = new ArchaeologyJournalState();
        for (ResourceLocation id : tableIds) {
            TableProgress progress = this.tables.get(id);
            if (progress != null) {
                partial.tables.put(id, progress.copy());
            }
        }
        partial.revision = this.revision;
        return partial;
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
        tag.putInt(NbtDataVersion.TAG, NbtDataVersion.CURRENT);
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

    // 标记指定表为脏
    // 注意：不在此处递增 revision，revision 统一由 drainDirtyTables 在发送增量包时递增，
    // 保证一次同步对应一次 revision +1
    private void markDirty(ResourceLocation tableId) {
        this.dirtyTables.add(tableId);
    }

    private TableProgress getOrCreateTable(ResourceLocation tableId) {
        return this.tables.computeIfAbsent(tableId, ignored -> new TableProgress());
    }

    public static final class TableProgress {
        private boolean unlocked;
        // 该表的 100% 完成奖励是否已发放；clear/removeTable 会移除整个 TableProgress 从而重置此标记
        private boolean completionRewardClaimed;
        private final LinkedHashMap<String, ItemProgress> items = new LinkedHashMap<>();

        public boolean isUnlocked() {
            return this.unlocked;
        }

        // 该表的 100% 完成奖励是否已发放
        public boolean isCompletionRewardClaimed() {
            return this.completionRewardClaimed;
        }

        public void setCompletionRewardClaimed(boolean claimed) {
            this.completionRewardClaimed = claimed;
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
            return !this.unlocked && !this.completionRewardClaimed && this.items.isEmpty();
        }

        public TableProgress copy() {
            TableProgress copy = new TableProgress();
            copy.unlocked = this.unlocked;
            copy.completionRewardClaimed = this.completionRewardClaimed;
            for (Map.Entry<String, ItemProgress> entry : this.items.entrySet()) {
                copy.items.put(entry.getKey(), entry.getValue().copy());
            }
            return copy;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean(UNLOCKED_TAG, this.unlocked);
            if (this.completionRewardClaimed) {
                tag.putBoolean(COMPLETION_REWARD_CLAIMED_TAG, true);
            }

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
            progress.completionRewardClaimed = tag.getBoolean(COMPLETION_REWARD_CLAIMED_TAG);

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
