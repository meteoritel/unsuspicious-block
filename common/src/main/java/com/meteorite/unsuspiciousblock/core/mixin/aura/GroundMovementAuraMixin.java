package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraMovement;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 原版已完成输入旋转与归一化，只在推进写入时区分本次贡献与携带速度。 */
@Mixin(Entity.class)
public abstract class GroundMovementAuraMixin {
    @WrapOperation(method = "moveRelative", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void unsuspiciousblock$selectGroundVelocity(Entity entity, Vec3 proposed, Operation<Void> original) {
        Vec3 accepted = entity instanceof Creeper creeper
                ? AuraMovement.selectGroundVelocity(creeper, creeper.getDeltaMovement(), proposed) : proposed;
        original.call(entity, accepted);
    }
}
