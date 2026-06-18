package com.meteorite.unsuspiciousblock.mixin.container;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为容器方块实体的基础存档流程补充追踪数据读写。
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin {

    // 在方块实体保存完成后写入容器战利品追踪数据
    @Inject(method = "saveAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("TAIL"))
    private void unsuspiciousblock$saveTrackedLootData(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if ((Object) this instanceof TrackedContainerLootState trackedContainer) {
            trackedContainer.unsuspiciousblock$writeTrackedLootData(tag);
        }
    }

    // 在方块实体读取完成后恢复容器战利品追踪数据
    @Inject(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("TAIL"))
    private void unsuspiciousblock$loadTrackedLootData(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if ((Object) this instanceof TrackedContainerLootState trackedContainer) {
            trackedContainer.unsuspiciousblock$readTrackedLootData(tag);
        }
    }
}
