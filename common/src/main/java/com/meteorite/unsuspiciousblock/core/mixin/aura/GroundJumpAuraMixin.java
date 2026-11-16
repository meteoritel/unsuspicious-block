package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraMovement;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 跳跃同样是 AI 推进；逐次核对竖直与冲刺贡献，保留写入前已有的外力。 */
@Mixin(LivingEntity.class)
public abstract class GroundJumpAuraMixin {
    @WrapOperation(method = "jumpFromGround", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"))
    private void unsuspiciousblock$selectJumpVelocity(LivingEntity entity, double x, double y, double z,
                                                     Operation<Void> original) {
        if (!(entity instanceof Creeper creeper)) {
            original.call(entity, x, y, z);
            return;
        }
        Vec3 accepted = AuraMovement.selectGroundVelocity(creeper, creeper.getDeltaMovement(), new Vec3(x, y, z));
        original.call(entity, accepted.x, accepted.y, accepted.z);
    }

    @WrapOperation(method = "jumpFromGround", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void unsuspiciousblock$selectSprintJumpVelocity(LivingEntity entity, Vec3 contribution,
                                                           Operation<Void> original) {
        if (entity instanceof Creeper creeper) {
            Vec3 carried = creeper.getDeltaMovement();
            Vec3 proposed = carried.add(contribution);
            if (AuraMovement.selectGroundVelocity(creeper, carried, proposed) == carried) {
                return;
            }
        }
        original.call(entity, contribution);
    }

    @WrapOperation(method = "jumpInLiquid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void unsuspiciousblock$selectLiquidJumpVelocity(LivingEntity entity, Vec3 proposed,
                                                           Operation<Void> original) {
        Vec3 accepted = entity instanceof Creeper creeper
                ? AuraMovement.selectGroundVelocity(creeper, creeper.getDeltaMovement(), proposed) : proposed;
        original.call(entity, accepted);
    }
}
