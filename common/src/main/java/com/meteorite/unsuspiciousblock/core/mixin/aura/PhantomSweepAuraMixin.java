package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 俯冲决策与攻击前解除受保护目标，原版 stop 负责恢复盘旋阶段。 */
@Mixin(targets = "net.minecraft.world.entity.monster.Phantom$PhantomSweepAttackGoal")
public abstract class PhantomSweepAuraMixin {
    @WrapOperation(method = {"canUse", "canContinueToUse", "tick"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/Phantom;getTarget()Lnet/minecraft/world/entity/LivingEntity;"))
    private LivingEntity unsuspiciousblock$filterAuraSweepTarget(Phantom phantom, Operation<LivingEntity> original) {
        return AuraRepulsion.clearProtectedTarget(phantom) ? null : original.call(phantom);
    }
}
