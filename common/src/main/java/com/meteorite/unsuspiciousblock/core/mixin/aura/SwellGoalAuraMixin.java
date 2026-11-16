package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.SwellGoal;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 蓄爆 Goal 独立缓存目标，需与 Mob 目标一起终止。 */
@Mixin(SwellGoal.class)
public abstract class SwellGoalAuraMixin {
    @Shadow @Final private Creeper creeper;
    @Shadow private LivingEntity target;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$stopProtectedSwell(CallbackInfo ci) {
        if (AuraRepulsion.shouldStopSwell(creeper, target)) {
            target = null;
            ci.cancel();
        }
    }
}
