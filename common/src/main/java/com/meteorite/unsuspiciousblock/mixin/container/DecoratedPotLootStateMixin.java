package com.meteorite.unsuspiciousblock.mixin.container;

import com.meteorite.unsuspiciousblock.blockentity.DecoratedPotLootState;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;
import java.util.UUID;

/**
 * 为陶罐保存破坏时即时提交所需的最小状态。
 */
@Mixin(DecoratedPotBlockEntity.class)
public abstract class DecoratedPotLootStateMixin implements DecoratedPotLootState {
    @Unique
    private static final String UNSUSPICIOUSBLOCK_LOOT_TABLE_TAG = "unsuspiciousblock_tracked_loot_table";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_PLAYER_UUID_TAG = "unsuspiciousblock_tracked_player_uuid";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_LEGACY_LOOT_ITEMS_TAG = "unsuspiciousblock_tracked_loot_items";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_LEGACY_PENDING_ENTRY_TAG = "unsuspiciousblock_pending_journal_entry";

    @Unique
    @Nullable
    private ResourceLocation unsuspiciousblock$decoratedPotLootTableName;

    @Unique
    @Nullable
    private UUID unsuspiciousblock$decoratedPotPlayerUuid;

    @Override
    @Nullable
    public ResourceLocation unsuspiciousblock$getDecoratedPotLootTableName() {
        return this.unsuspiciousblock$decoratedPotLootTableName;
    }

    @Override
    public void unsuspiciousblock$setDecoratedPotLootTableName(@Nullable ResourceLocation tableId) {
        if (Objects.equals(this.unsuspiciousblock$decoratedPotLootTableName, tableId)) {
            return;
        }
        this.unsuspiciousblock$decoratedPotLootTableName = tableId;
        ((BlockEntity) (Object) this).setChanged();
    }

    @Override
    @Nullable
    public UUID unsuspiciousblock$getDecoratedPotPlayerUuid() {
        return this.unsuspiciousblock$decoratedPotPlayerUuid;
    }

    @Override
    public void unsuspiciousblock$setDecoratedPotPlayerUuid(@Nullable UUID playerUuid) {
        if (Objects.equals(this.unsuspiciousblock$decoratedPotPlayerUuid, playerUuid)) {
            return;
        }
        this.unsuspiciousblock$decoratedPotPlayerUuid = playerUuid;
        ((BlockEntity) (Object) this).setChanged();
    }

    // 写入最小状态，同时移除旧版陶罐延迟追踪数据
    @Inject(method = "saveAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("TAIL"))
    private void unsuspiciousblock$saveDecoratedPotLootState(
            CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (this.unsuspiciousblock$decoratedPotLootTableName != null) {
            tag.putString(UNSUSPICIOUSBLOCK_LOOT_TABLE_TAG,
                    this.unsuspiciousblock$decoratedPotLootTableName.toString());
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_LOOT_TABLE_TAG);
        }
        if (this.unsuspiciousblock$decoratedPotPlayerUuid != null) {
            tag.putUUID(UNSUSPICIOUSBLOCK_PLAYER_UUID_TAG, this.unsuspiciousblock$decoratedPotPlayerUuid);
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_PLAYER_UUID_TAG);
        }
        tag.remove(UNSUSPICIOUSBLOCK_LEGACY_LOOT_ITEMS_TAG);
        tag.remove(UNSUSPICIOUSBLOCK_LEGACY_PENDING_ENTRY_TAG);
    }

    // 读取最小状态，兼容上一个实现使用的相同 table 与 player 标签
    @Inject(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("TAIL"))
    private void unsuspiciousblock$loadDecoratedPotLootState(
            CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        this.unsuspiciousblock$decoratedPotLootTableName = tag.contains(UNSUSPICIOUSBLOCK_LOOT_TABLE_TAG, Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(UNSUSPICIOUSBLOCK_LOOT_TABLE_TAG))
                : null;
        this.unsuspiciousblock$decoratedPotPlayerUuid = tag.hasUUID(UNSUSPICIOUSBLOCK_PLAYER_UUID_TAG)
                ? tag.getUUID(UNSUSPICIOUSBLOCK_PLAYER_UUID_TAG)
                : null;
    }
}
