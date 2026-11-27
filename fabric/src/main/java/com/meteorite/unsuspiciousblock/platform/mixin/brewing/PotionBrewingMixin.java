package com.meteorite.unsuspiciousblock.platform.mixin.brewing;

import com.meteorite.unsuspiciousblock.core.potion.PurrmintPotionBrewing;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric 端酿造配方接入——补齐 Fabric 缺失的跨物品酿造配方能力，
 * 将灵猫药水酿造链委托至公共 {@link PurrmintPotionBrewing}。
 */
@Mixin(PotionBrewing.class)
public abstract class PotionBrewingMixin {

    @Inject(method = "isIngredient", at = @At("RETURN"), cancellable = true)
    private void unsuspiciousblock$allowPurrmintIngredient(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && PurrmintPotionBrewing.isBrewingIngredient(stack)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "hasMix", at = @At("RETURN"), cancellable = true)
    private void unsuspiciousblock$checkPurrmintMix(ItemStack input, ItemStack ingredient,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && PurrmintPotionBrewing.hasMix(input, ingredient)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mix", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$applyPurrmintMix(ItemStack ingredient, ItemStack input,
                                                    CallbackInfoReturnable<ItemStack> cir) {
        ItemStack output = PurrmintPotionBrewing.getOutput(input, ingredient);
        if (!output.isEmpty()) {
            cir.setReturnValue(output);
        }
    }
}
