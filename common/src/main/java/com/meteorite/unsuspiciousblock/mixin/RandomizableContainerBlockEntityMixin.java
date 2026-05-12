package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 为可解析战利品表的容器方块实体保存运行时追踪状态。
 */
@Mixin(RandomizableContainerBlockEntity.class)
public abstract class RandomizableContainerBlockEntityMixin implements TrackedContainerLootState {
    @Unique
    private static final String UNSUSPICIOUSBLOCK_TRACKED_LOOT_TABLE_TAG = "unsuspiciousblock_tracked_loot_table";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG = "unsuspiciousblock_tracked_loot_items";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG = "unsuspiciousblock_pending_journal_entry";

    @Unique
    @Nullable
    private ResourceLocation unsuspiciousblock$trackedLootTableName;

    @Unique
    private final LinkedHashMap<String, Integer> unsuspiciousblock$trackedLootCounts = new LinkedHashMap<>();

    @Unique
    @Nullable
    private ExcavationLogEntry unsuspiciousblock$pendingJournalEntry;

    // 返回当前容器关联的已追踪战利品表
    @Override
    @Nullable
    public ResourceLocation unsuspiciousblock$getTrackedLootTableName() {
        return this.unsuspiciousblock$trackedLootTableName;
    }

    // 返回当前容器中尚未结算到玩家背包的追踪物品数量
    @Override
    public Map<String, Integer> unsuspiciousblock$getTrackedLootCounts() {
        return this.unsuspiciousblock$trackedLootCounts;
    }

    @Override
    @Nullable
    public ExcavationLogEntry unsuspiciousblock$getPendingJournalEntry() {
        return this.unsuspiciousblock$pendingJournalEntry;
    }

    @Override
    public void unsuspiciousblock$setPendingJournalEntry(@Nullable ExcavationLogEntry entry) {
        if (Objects.equals(this.unsuspiciousblock$pendingJournalEntry, entry)) {
            return;
        }
        this.unsuspiciousblock$pendingJournalEntry = entry;
        this.unsuspiciousblock$markTrackingChanged();
    }

    // 用本次开箱解析结果覆盖容器追踪状态
    @Override
    public void unsuspiciousblock$setTrackedLoot(ResourceLocation tableId, Map<String, Integer> itemCounts) {
        LinkedHashMap<String, Integer> normalized = this.unsuspiciousblock$normalizeTrackedLootCounts(itemCounts);
        if (normalized.isEmpty()) {
            this.unsuspiciousblock$clearTrackedLoot();
            return;
        }
        if (Objects.equals(this.unsuspiciousblock$trackedLootTableName, tableId)
                && this.unsuspiciousblock$trackedLootCounts.equals(normalized)) {
            return;
        }

        this.unsuspiciousblock$trackedLootTableName = tableId;
        this.unsuspiciousblock$trackedLootCounts.clear();
        this.unsuspiciousblock$trackedLootCounts.putAll(normalized);
        this.unsuspiciousblock$markTrackingChanged();
    }

    // 消耗指定物品签名的已追踪数量，并返回本次实际结算的数量
    @Override
    public int unsuspiciousblock$consumeTrackedLoot(String signatureKey, int amount) {
        if (amount <= 0) {
            return 0;
        }

        Integer current = this.unsuspiciousblock$trackedLootCounts.get(signatureKey);
        if (current == null || current <= 0) {
            return 0;
        }

        int consumed = Math.min(current, amount);
        int remaining = current - consumed;
        if (remaining > 0) {
            this.unsuspiciousblock$trackedLootCounts.put(signatureKey, remaining);
        } else {
            this.unsuspiciousblock$trackedLootCounts.remove(signatureKey);
        }
        if (this.unsuspiciousblock$trackedLootCounts.isEmpty()) {
            this.unsuspiciousblock$trackedLootTableName = null;
            this.unsuspiciousblock$pendingJournalEntry = null;
        }
        this.unsuspiciousblock$markTrackingChanged();
        return consumed;
    }

    // 根据容器当前物品重新校正尚未结算的追踪数量
    @Override
    public void unsuspiciousblock$reconcileTrackedLoot() {
        if (this.unsuspiciousblock$trackedLootTableName == null || this.unsuspiciousblock$trackedLootCounts.isEmpty()) {
            return;
        }

        List<LootResultSignature> candidates = new ArrayList<>();
        for (String signatureKey : this.unsuspiciousblock$trackedLootCounts.keySet()) {
            LootResultSignature signature = LootResultSignature.fromStoredKey(signatureKey);
            if (signature != null) {
                candidates.add(signature);
            }
        }
        if (candidates.isEmpty()) {
            this.unsuspiciousblock$clearTrackedLoot();
            return;
        }

        Map<String, Integer> currentCounts = this.unsuspiciousblock$collectContainerItemCounts(candidates);
        LinkedHashMap<String, Integer> reconciled = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : this.unsuspiciousblock$trackedLootCounts.entrySet()) {
            int remaining = Math.min(entry.getValue(), currentCounts.getOrDefault(entry.getKey(), 0));
            if (remaining > 0) {
                reconciled.put(entry.getKey(), remaining);
            }
        }

        if (reconciled.isEmpty()) {
            this.unsuspiciousblock$clearTrackedLoot();
            return;
        }
        if (this.unsuspiciousblock$trackedLootCounts.equals(reconciled)) {
            return;
        }

        this.unsuspiciousblock$trackedLootCounts.clear();
        this.unsuspiciousblock$trackedLootCounts.putAll(reconciled);
        this.unsuspiciousblock$markTrackingChanged();
    }

    // 清空当前容器保存的战利品追踪状态
    @Override
    public void unsuspiciousblock$clearTrackedLoot() {
        if (this.unsuspiciousblock$trackedLootTableName == null && this.unsuspiciousblock$trackedLootCounts.isEmpty()) {
            return;
        }

        this.unsuspiciousblock$trackedLootTableName = null;
        this.unsuspiciousblock$trackedLootCounts.clear();
        this.unsuspiciousblock$pendingJournalEntry = null;
        this.unsuspiciousblock$markTrackingChanged();
    }

    // 过滤并合并非法或重复的追踪数量记录
    @Unique
    private LinkedHashMap<String, Integer> unsuspiciousblock$normalizeTrackedLootCounts(Map<String, Integer> itemCounts) {
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            String signatureKey = entry.getKey();
            if (signatureKey == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            if (LootResultSignature.fromStoredKey(signatureKey) == null) {
                continue;
            }
            normalized.merge(signatureKey, entry.getValue(), Integer::sum);
        }
        return normalized;
    }

    // 标记追踪状态已变化，便于容器方块实体后续保存
    @Unique
    private void unsuspiciousblock$markTrackingChanged() {
        ((BlockEntity) (Object) this).setChanged();
    }

    // 将当前追踪状态写入容器方块实体 NBT
    @Override
    public void unsuspiciousblock$writeTrackedLootData(CompoundTag tag) {
        if (this.unsuspiciousblock$trackedLootTableName != null) {
            tag.putString(UNSUSPICIOUSBLOCK_TRACKED_LOOT_TABLE_TAG, this.unsuspiciousblock$trackedLootTableName.toString());
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_TRACKED_LOOT_TABLE_TAG);
        }
        if (this.unsuspiciousblock$pendingJournalEntry != null) {
            tag.put(UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG, this.unsuspiciousblock$pendingJournalEntry.toTag());
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG);
        }

        if (this.unsuspiciousblock$trackedLootCounts.isEmpty()) {
            tag.remove(UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG);
            return;
        }

        CompoundTag itemCountsTag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : this.unsuspiciousblock$trackedLootCounts.entrySet()) {
            itemCountsTag.putInt(entry.getKey(), entry.getValue());
        }
        tag.put(UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG, itemCountsTag);
    }

    // 从容器方块实体 NBT 中恢复当前追踪状态
    @Override
    public void unsuspiciousblock$readTrackedLootData(CompoundTag tag) {
        this.unsuspiciousblock$trackedLootTableName = tag.contains(UNSUSPICIOUSBLOCK_TRACKED_LOOT_TABLE_TAG, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(UNSUSPICIOUSBLOCK_TRACKED_LOOT_TABLE_TAG))
                : null;
        this.unsuspiciousblock$pendingJournalEntry = tag.contains(UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG, Tag.TAG_COMPOUND)
                ? ExcavationLogEntry.fromTag(tag.getCompound(UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG))
                : null;
        this.unsuspiciousblock$trackedLootCounts.clear();
        if (!tag.contains(UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG, Tag.TAG_COMPOUND)) {
            if (this.unsuspiciousblock$trackedLootTableName == null) {
                return;
            }
            this.unsuspiciousblock$trackedLootTableName = null;
            return;
        }

        CompoundTag itemCountsTag = tag.getCompound(UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG);
        for (String key : itemCountsTag.getAllKeys()) {
            int count = itemCountsTag.getInt(key);
            if (count <= 0 || LootResultSignature.fromStoredKey(key) == null) {
                continue;
            }
            this.unsuspiciousblock$trackedLootCounts.put(key, count);
        }
        if (this.unsuspiciousblock$trackedLootCounts.isEmpty()) {
            this.unsuspiciousblock$trackedLootTableName = null;
            this.unsuspiciousblock$pendingJournalEntry = null;
        }
    }
}
