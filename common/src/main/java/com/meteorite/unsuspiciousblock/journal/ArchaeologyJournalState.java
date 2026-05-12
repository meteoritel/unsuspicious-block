package com.meteorite.unsuspiciousblock.journal;

import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ArchaeologyJournalState {
    private static final String TABLES_TAG = "tables";
    private static final String UNLOCKED_TAG = "unlocked";
    private static final String ITEMS_TAG = "items";
    private static final String COUNT_TAG = "count";

    private final LinkedHashMap<ResourceLocation, TableProgress> tables = new LinkedHashMap<>();

    public void clear() {
        this.tables.clear();
    }

    public boolean unlockTable(ResourceLocation tableId) {
        return this.getOrCreateTable(tableId).unlock();
    }

    public boolean unlockItem(ResourceLocation tableId, ResourceLocation itemId) {
        return this.unlockItem(tableId, LootResultSignature.plain(itemId));
    }

    public boolean unlockItem(ResourceLocation tableId, LootResultSignature signature) {
        TableProgress table = this.getOrCreateTable(tableId);
        table.unlock();
        return table.unlockItem(signature);
    }

    public boolean recordItemAcquired(ResourceLocation tableId, ResourceLocation itemId) {
        return this.recordItemAcquired(tableId, LootResultSignature.plain(itemId), 1);
    }

    public boolean recordItemAcquired(ResourceLocation tableId, ResourceLocation itemId, int count) {
        return this.recordItemAcquired(tableId, LootResultSignature.plain(itemId), count);
    }

    public boolean recordItemAcquired(ResourceLocation tableId, LootResultSignature signature, int count) {
        if (count <= 0) {
            return false;
        }

        TableProgress table = this.getOrCreateTable(tableId);
        table.unlock();
        return table.recordItemAcquired(signature, count);
    }

    public boolean removeTable(ResourceLocation tableId) {
        return this.tables.remove(tableId) != null;
    }

    public boolean isTableUnlocked(ResourceLocation tableId) {
        TableProgress table = this.tables.get(tableId);
        return table != null && table.isUnlocked();
    }

    public boolean isItemUnlocked(ResourceLocation tableId, ResourceLocation itemId) {
        return this.isItemUnlocked(tableId, LootResultSignature.plain(itemId));
    }

    public boolean isItemUnlocked(ResourceLocation tableId, LootResultSignature signature) {
        TableProgress table = this.tables.get(tableId);
        if (table == null) {
            return false;
        }

        ItemProgress item = table.getItems().get(signature.toStoredKey());
        return item != null && item.isUnlocked();
    }

    public Map<ResourceLocation, TableProgress> getTables() {
        return Collections.unmodifiableMap(this.tables);
    }

    @Nullable
    public TableProgress getTable(ResourceLocation tableId) {
        return this.tables.get(tableId);
    }

    public ArchaeologyJournalState copy() {
        ArchaeologyJournalState copy = new ArchaeologyJournalState();
        copy.copyFrom(this);
        return copy;
    }

    public void copyFrom(ArchaeologyJournalState other) {
        this.tables.clear();
        for (Map.Entry<ResourceLocation, TableProgress> entry : other.tables.entrySet()) {
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
        for (Map.Entry<ResourceLocation, TableProgress> entry : this.tables.entrySet()) {
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

            this.tables.put(tableId, TableProgress.fromTag(tablesTag.getCompound(key)));
        }
    }

    public static ArchaeologyJournalState fromTag(CompoundTag tag) {
        ArchaeologyJournalState state = new ArchaeologyJournalState();
        state.readFrom(tag);
        return state;
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

        public Map<String, ItemProgress> getItems() {
            return Collections.unmodifiableMap(this.items);
        }

        public boolean unlock() {
            if (this.unlocked) {
                return false;
            }

            this.unlocked = true;
            return true;
        }

        public boolean unlockItem(ResourceLocation itemId) {
            return this.unlockItem(LootResultSignature.plain(itemId));
        }

        public boolean unlockItem(LootResultSignature signature) {
            return this.getOrCreateItem(signature).unlock();
        }

        public boolean recordItemAcquired(ResourceLocation itemId) {
            return this.recordItemAcquired(LootResultSignature.plain(itemId), 1);
        }

        public boolean recordItemAcquired(ResourceLocation itemId, int count) {
            return this.recordItemAcquired(LootResultSignature.plain(itemId), count);
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

        // 统计已解析（已解锁）的物品数量
        public int getResolvedItemCount() {
            int count = 0;
            for (ItemProgress item : this.items.values()) {
                if (item.isUnlocked()) {
                    count++;
                }
            }
            return count;
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
