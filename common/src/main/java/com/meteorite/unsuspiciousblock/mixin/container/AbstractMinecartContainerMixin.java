package com.meteorite.unsuspiciousblock.mixin.container;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerTrackingService;
import com.meteorite.unsuspiciousblock.loottable.signature.LootCounts;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 为原版随机战利品矿车保存运行时追踪状态，并接入实体存档与销毁生命周期。
 */
@Mixin(AbstractMinecartContainer.class)
public abstract class AbstractMinecartContainerMixin implements TrackedContainerLootState {
    @Unique
    private static final String UNSUSPICIOUSBLOCK_TRACKED_LOOT_TABLE_TAG =
            "unsuspiciousblock_tracked_loot_table";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_TRACKED_LOOT_ITEMS_TAG =
            "unsuspiciousblock_tracked_loot_items";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG =
            "unsuspiciousblock_pending_journal_entry";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_TRACKED_PLAYER_UUID_TAG =
            "unsuspiciousblock_tracked_player_uuid";

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

    @Override
    @Nullable
    public ResourceLocation unsuspiciousblock$getTrackedLootTableName() {
        return this.unsuspiciousblock$trackedLootTableName;
    }

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
        this.unsuspiciousblock$pendingJournalEntry = entry;
    }

    @Override
    @Nullable
    public UUID unsuspiciousblock$getTrackedPlayerUuid() {
        return this.unsuspiciousblock$trackedPlayerUuid;
    }

    @Override
    public void unsuspiciousblock$setTrackedPlayerUuid(@Nullable UUID uuid) {
        this.unsuspiciousblock$trackedPlayerUuid = uuid;
    }

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
    }

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
        return consumed;
    }

    @Override
    public void unsuspiciousblock$clearTrackedLoot() {
        this.unsuspiciousblock$trackedLootTableName = null;
        this.unsuspiciousblock$trackedLootCounts.clear();
    }

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

    // 原版矿车保存物品与战利品表后，追加本模组追踪状态
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$saveTrackedLootData(CompoundTag tag, CallbackInfo ci) {
        this.unsuspiciousblock$writeTrackedLootData(tag);
    }

    // 原版矿车读取物品与战利品表后，恢复本模组追踪状态
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$loadTrackedLootData(CompoundTag tag, CallbackInfo ci) {
        this.unsuspiciousblock$readTrackedLootData(tag);
    }

    // 仅在实体真正销毁时结算待定日志；区块卸载仍依靠实体 NBT 保留状态
    @Inject(method = "remove", at = @At("HEAD"))
    private void unsuspiciousblock$flushTrackedLootOnDestroy(Entity.RemovalReason reason, CallbackInfo ci) {
        AbstractMinecartContainer minecart = (AbstractMinecartContainer) (Object) this;
        if (reason.shouldDestroy() && minecart.level() instanceof ServerLevel serverLevel) {
            ContainerTrackingService.onTrackedContainerDestroyed(serverLevel, this);
        }
    }

}
