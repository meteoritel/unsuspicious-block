package com.meteorite.unsuspiciousblock.platform.mixin.brewing;

import com.meteorite.unsuspiciousblock.core.potion.PurrmintPotionBrewing;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric 端酿造台药水槽位放行——允许玩家手动放入三款灵猫药水进行红石延长、萤石强化及形态转换。
 */
@Mixin(targets = "net.minecraft.world.inventory.BrewingStandMenu$PotionSlot")
public abstract class BrewingStandSlotMixin {

    @Inject(method = "mayPlaceItem", at = @At("RETURN"), cancellable = true)
    private static void unsuspiciousblock$allowPurrmintPotion(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && PurrmintPotionBrewing.isPurrmintPotionItem(stack)) {
            cir.setReturnValue(true);
        }
    }
}
