package com.meteorite.unsuspiciousblock.mixin.compat.lootr;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.loottable.signature.LootCounts;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import noobanidus.mods.lootr.common.data.LootrInventory;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 为 Lootr 的每玩家库存附加运行时战利品追踪状态。
 * <p>
 * Lootr 打开菜单时以 {@link LootrInventory} 为容器，而非原版 BlockEntity。
 * 本 Mixin 使其实现 {@link TrackedContainerLootState}，让菜单核对阶段能自然找到追踪状态。
 * 追踪状态随 LootrInventory 一同写入 LootrSavedData，确保重启后仍能继续核对未取走的物品。
 */
@Mixin(value = LootrInventory.class, remap = false)
public abstract class LootrInventoryMixin implements TrackedContainerLootState {

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

    // 标记 LootrSavedData 为 dirty，使追踪字段与库存物品一同落盘
    @Unique
    private void unsuspiciousblock$markTrackingChanged() {
        ((LootrInventory) (Object) this).setChanged();
    }

    // 将追踪状态附加到 LootrInventory 自身的存档 CompoundTag
    @Override
    public void unsuspiciousblock$writeTrackedLootData(CompoundTag tag) {
        if (this.unsuspiciousblock$trackedLootTableName != null) {
            tag.putString(UNSUSPICIOUSBLOCK_TRACKED_LOOT_TABLE_TAG,
                    this.unsuspiciousblock$trackedLootTableName.toString());
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_TRACKED_LOOT_TABLE_TAG);
        }
        if (this.unsuspiciousblock$pendingJournalEntry != null) {
            tag.put(UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG,
                    this.unsuspiciousblock$pendingJournalEntry.toTag());
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
        } else {
            tag.put(UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG,
                    LootCounts.writeToNbt(this.unsuspiciousblock$trackedLootCounts));
        }
    }

    // 从 LootrInventory 存档中恢复追踪状态，并丢弃不完整的 table/count 组合
    @Override
    public void unsuspiciousblock$readTrackedLootData(CompoundTag tag) {
        this.unsuspiciousblock$trackedLootTableName = tag.contains(
                UNSUSPICIOUSBLOCK_TRACKED_LOOT_TABLE_TAG, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(UNSUSPICIOUSBLOCK_TRACKED_LOOT_TABLE_TAG))
                : null;
        this.unsuspiciousblock$pendingJournalEntry = tag.contains(
                UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG, Tag.TAG_COMPOUND)
                ? ExcavationLogEntry.fromTag(tag.getCompound(UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG))
                : null;
        this.unsuspiciousblock$trackedPlayerUuid = tag.contains(UNSUSPICIOUSBLOCK_TRACKED_PLAYER_UUID_TAG)
                ? tag.getUUID(UNSUSPICIOUSBLOCK_TRACKED_PLAYER_UUID_TAG)
                : null;

        this.unsuspiciousblock$trackedLootCounts.clear();
        if (tag.contains(UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG, Tag.TAG_COMPOUND)) {
            this.unsuspiciousblock$trackedLootCounts.putAll(LootCounts.normalize(
                    LootCounts.readFromNbt(tag, UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG)));
        }
        if (this.unsuspiciousblock$trackedLootCounts.isEmpty()) {
            this.unsuspiciousblock$trackedLootTableName = null;
        }
    }

    // Lootr 保存库存物品后，将本模组追踪字段写入同一个 CompoundTag
    @Inject(method = "saveToTag", at = @At("RETURN"))
    private void unsuspiciousblock$appendTrackingData(
            HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        this.unsuspiciousblock$writeTrackedLootData(cir.getReturnValue());
    }
}
