package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 将区域保护接入原版战斗型索敌与持续目标检查。 */
@Mixin(LivingEntity.class)
public abstract class LivingTargetAuraMixin {
    @Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$protectAuraTarget(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (AuraRepulsion.isProtectedTarget((LivingEntity) (Object) this, target)) {
            cir.setReturnValue(false);
        }
    }
}
