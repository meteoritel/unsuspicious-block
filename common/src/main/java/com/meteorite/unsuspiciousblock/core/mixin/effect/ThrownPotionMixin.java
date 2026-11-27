package com.meteorite.unsuspiciousblock.core.mixin.effect;

import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.world.entity.projectile.ThrownPotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 扩展原版投掷药水的滞留判定——原版 {@code ThrownPotion#isLingering} 硬编码比对 {@code Items.LINGERING_POTION}，
 * 此处补齐对滞留型灵猫药水的识别以正确生成持续药水云。
 */
@Mixin(ThrownPotion.class)
public abstract class ThrownPotionMixin {

    @Inject(method = "isLingering", at = @At("RETURN"), cancellable = true)
    private void unsuspiciousblock$recognizeLingeringPurrmint(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
                && ModItems.LINGERING_PURRMINT_POTION != null
                && ((ThrownPotion) (Object) this).getItem().is(ModItems.LINGERING_PURRMINT_POTION)) {
            cir.setReturnValue(true);
        }
    }
}
