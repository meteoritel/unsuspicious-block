package com.meteorite.unsuspiciousblock.core.mixin.effect;

import com.meteorite.unsuspiciousblock.core.effect.PurrmintEffectService;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 喵步落地静音入口——处于静音条件时取消落地方块碰撞音效。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySilenceMixin {

    @Inject(method = "playBlockFallSound", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$silenceBlockFallSound(CallbackInfo ci) {
        if (PurrmintEffectService.shouldSilenceMovement((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
