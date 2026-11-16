package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraMovement;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 盘旋选点写入时转交区域飞行决策，无需暴露幻翼私有状态。 */
@Mixin(targets = "net.minecraft.world.entity.monster.Phantom$PhantomCircleAroundAnchorGoal")
public abstract class PhantomCircleAuraMixin {
    @WrapOperation(method = "selectNext", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/monster/Phantom;moveTargetPoint:Lnet/minecraft/world/phys/Vec3;",
            opcode = Opcodes.PUTFIELD))
    private void unsuspiciousblock$selectAuraFlightTarget(Phantom phantom, Vec3 target, Operation<Void> original) {
        original.call(phantom, AuraMovement.selectFlightTarget(phantom, target));
    }
}
