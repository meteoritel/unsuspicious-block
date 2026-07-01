package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 猫的陪伴（恩惠≥50）——幻翼不再以该玩家作为索敌目标。
 * 注入 Phantom$PhantomAttackPlayerTargetGoal 的 canUse / canContinueToUse，
 * 重定向 canAttack 调用：若目标玩家拥有猫的陪伴，则视为不可攻击，
 * 幻翼不会锁定该玩家，已锁定的也会因 canContinueToUse 返回 false 而放弃。
 */
@Mixin(targets = "net.minecraft.world.entity.monster.Phantom$PhantomAttackPlayerTargetGoal")
public abstract class PhantomTargetGoalMixin {

    @Redirect(method = {"canUse", "canContinueToUse"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/Phantom;canAttack(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;)Z"))
    private boolean unsuspiciousblock$skipCatCompanion(Phantom phantom, LivingEntity target,
                                                       TargetingConditions conditions) {
        if (target instanceof Player player && CatPassiveAbilities.hasCatCompanion(player)) {
            return false;
        }
        return phantom.canAttack(target, conditions);
    }
}
