package com.meteorite.unsuspiciousblock.platform.mixin.brewing;

import com.meteorite.unsuspiciousblock.core.potion.PurrmintPotionBrewing;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric 端酿造台漏斗输入放行——允许漏斗向 0–2 号空药水槽输入三款灵猫药水。
 */
@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBlockEntityMixin {
    private static final int MAX_POTION_SLOT_INDEX = 2;

    @Shadow
    private NonNullList<ItemStack> items;

    @Inject(method = "canPlaceItem", at = @At("RETURN"), cancellable = true)
    private void unsuspiciousblock$allowPurrmintHopperInput(int slot, ItemStack stack,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
                && slot >= 0
                && slot <= MAX_POTION_SLOT_INDEX
                && items.get(slot).isEmpty()
                && PurrmintPotionBrewing.isPurrmintPotionItem(stack)) {
            cir.setReturnValue(true);
        }
    }
}
