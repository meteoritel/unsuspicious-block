package com.meteorite.unsuspiciousblock.mixin.container;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.loottable.signature.LootCounts;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 为普通随机容器保存运行时追踪状态。
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
    private static final String UNSUSPICIOUSBLOCK_TRACKED_PLAYER_UUID_TAG = "unsuspiciousblock_tracked_player_uuid";

    @Unique
    @Nullable
    private ResourceLocation unsuspiciousblock$trackedLootTableName;

    @Unique
    private final LinkedHashMap<String, Integer> unsuspiciousblock$trackedLootCounts = new LinkedHashMap<>();

    @Unique
    @Nullable
    private ExcavationLogEntry unsuspiciousblock$pendingJournalEntry;

    @Unique
    @Nullable
    private UUID unsuspiciousblock$trackedPlayerUuid;

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

    // 返回触发战利品表解析的追踪玩家 UUID
    @Override
    @Nullable
    public UUID unsuspiciousblock$getTrackedPlayerUuid() {
        return this.unsuspiciousblock$trackedPlayerUuid;
    }

    // 设置触发战利品表解析的追踪玩家 UUID
    @Override
    public void unsuspiciousblock$setTrackedPlayerUuid(@Nullable UUID uuid) {
        if (Objects.equals(this.unsuspiciousblock$trackedPlayerUuid, uuid)) {
            return;
        }
        this.unsuspiciousblock$trackedPlayerUuid = uuid;
        this.unsuspiciousblock$markTrackingChanged();
    }

    // 用本次开箱解析结果覆盖容器追踪状态
    @Override
    public void unsuspiciousblock$setTrackedLoot(ResourceLocation tableId, Map<String, Integer> itemCounts) {
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>(LootCounts.normalize(itemCounts));
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
        this.unsuspiciousblock$markTrackingChanged();
        return consumed;
    }

    // 清空当前容器保存的战利品追踪状态（保留 pendingJournalEntry，由调用方在结算完成后显式清除）
    @Override
    public void unsuspiciousblock$clearTrackedLoot() {
        if (this.unsuspiciousblock$trackedLootTableName == null && this.unsuspiciousblock$trackedLootCounts.isEmpty()) {
            return;
        }

        this.unsuspiciousblock$trackedLootTableName = null;
        this.unsuspiciousblock$trackedLootCounts.clear();
        this.unsuspiciousblock$markTrackingChanged();
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
        if (this.unsuspiciousblock$trackedPlayerUuid != null) {
            tag.putUUID(UNSUSPICIOUSBLOCK_TRACKED_PLAYER_UUID_TAG, this.unsuspiciousblock$trackedPlayerUuid);
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_TRACKED_PLAYER_UUID_TAG);
        }

        if (this.unsuspiciousblock$trackedLootCounts.isEmpty()) {
            tag.remove(UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG);
            return;
        }

        tag.put(UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG, LootCounts.writeToNbt(this.unsuspiciousblock$trackedLootCounts));
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
        this.unsuspiciousblock$trackedPlayerUuid = tag.contains(UNSUSPICIOUSBLOCK_TRACKED_PLAYER_UUID_TAG)
                ? tag.getUUID(UNSUSPICIOUSBLOCK_TRACKED_PLAYER_UUID_TAG)
                : null;
        this.unsuspiciousblock$trackedLootCounts.clear();
        if (!tag.contains(UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG, Tag.TAG_COMPOUND)) {
            if (this.unsuspiciousblock$trackedLootTableName == null) {
                return;
            }
            this.unsuspiciousblock$trackedLootTableName = null;
            return;
        }

        this.unsuspiciousblock$trackedLootCounts.putAll(
                LootCounts.normalize(LootCounts.readFromNbt(tag, UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG))
        );
        if (this.unsuspiciousblock$trackedLootCounts.isEmpty()) {
            this.unsuspiciousblock$trackedLootTableName = null;
            // pendingJournalEntry 保留：可能追踪已清空但日志尚未结算
        }
    }
}
