package com.meteorite.unsuspiciousblock.core.mixin.catfavor;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.cat.CatPassiveAbilities;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 猫之威慑——幻翼不再以该玩家作为索敌目标。
 * 注入 Phantom$PhantomAttackPlayerTargetGoal 的 canUse / canContinueToUse，
 * 在原版索敌检查中排除受保护玩家，已锁定的目标也会通过持续检查被放弃。
 */
@Mixin(targets = "net.minecraft.world.entity.monster.Phantom$PhantomAttackPlayerTargetGoal")
public abstract class PhantomTargetGoalMixin {

    @WrapOperation(method = {"canUse", "canContinueToUse"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/Phantom;canAttack(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;)Z"))
    private boolean unsuspiciousblock$skipCatCompanion(Phantom phantom, LivingEntity target,
                                                       TargetingConditions conditions, Operation<Boolean> original) {
        return original.call(phantom, target, conditions)
                && (!(target instanceof Player player) || !CatPassiveAbilities.hasPhantomDeterrence(player));
    }
}
