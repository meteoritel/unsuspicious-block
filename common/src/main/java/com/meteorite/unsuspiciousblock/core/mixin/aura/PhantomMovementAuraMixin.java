package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraMovement;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 保留原版转向算法，在控制器写入处过滤新贡献而不裁剪惯性与击退。 */
@Mixin(targets = "net.minecraft.world.entity.monster.Phantom$PhantomMoveControl")
public abstract class PhantomMovementAuraMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/Phantom;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void unsuspiciousblock$selectFlightVelocity(Phantom phantom, Vec3 proposed, Operation<Void> original) {
        original.call(phantom, AuraMovement.selectFlightVelocity(phantom, phantom.getDeltaMovement(), proposed));
    }
}
