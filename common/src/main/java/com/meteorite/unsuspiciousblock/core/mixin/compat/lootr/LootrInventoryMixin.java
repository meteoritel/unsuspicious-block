package com.meteorite.unsuspiciousblock.core.mixin.compat.lootr;

import com.meteorite.unsuspiciousblock.core.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.core.journal.tracking.ContainerLootTracking;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import noobanidus.mods.lootr.common.data.LootrInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


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
    private final ContainerLootTracking unsuspiciousblock$tracking =
            new ContainerLootTracking(() -> ((LootrInventory) (Object) this).setChanged());

    @Override
    public ContainerLootTracking unsuspiciousblock$tracking() {
        return this.unsuspiciousblock$tracking;
    }

    // Lootr 保存库存物品后，将本模组追踪字段写入同一个 CompoundTag
    @Inject(method = "saveToTag", at = @At("RETURN"))
    private void unsuspiciousblock$appendTrackingData(
            HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        this.unsuspiciousblock$writeTrackedLootData(cir.getReturnValue());
    }
}
