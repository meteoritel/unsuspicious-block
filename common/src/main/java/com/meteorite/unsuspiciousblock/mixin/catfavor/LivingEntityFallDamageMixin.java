package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 柔软肉垫（恩惠≥60）——免疫所有摔落伤害。
 * 注入 LivingEntity.causeFallDamage，对持有该被动的玩家直接返回未造成伤害。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFallDamageMixin {

    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$softPawsFallImmunity(float fallDistance, float multiplier,
                                                        net.minecraft.world.damagesource.DamageSource source,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Player player && CatPassiveAbilities.hasSoftPaws(player)) {
            cir.setReturnValue(false);
        }
    }
}
