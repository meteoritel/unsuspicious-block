package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 柔软肉垫（恩惠≥70）——免疫 80% 的摔落伤害。
 * 注入 LivingEntity.getDamageAfterMagicAbsorb 的返回处，在原版摔落保护
 * （附魔、抗性等）结算之后再对剩余伤害 ×0.2，仅保留 20%。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFallDamageMixin {

    @Inject(method = "getDamageAfterMagicAbsorb", at = @At("RETURN"), cancellable = true)
    private void unsuspiciousblock$softPawsReduceFallDamage(DamageSource source, float amount,
                                                            CallbackInfoReturnable<Float> cir) {
        if ((LivingEntity) (Object) this instanceof Player player
                && source.is(DamageTypeTags.IS_FALL)
                && CatPassiveAbilities.hasSoftPaws(player)) {
            cir.setReturnValue(cir.getReturnValueF() * 0.2F);
        }
    }
}
