package com.meteorite.unsuspiciousblock.platform.mixin.aura;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraMovement;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NeoForge AI 通过扩展液体跳跃入口推进，复用扩展实现后只撤回受阻贡献。 */
@Mixin(LivingEntity.class)
public abstract class FluidJumpAuraMixin {
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;jumpInFluid(Lnet/neoforged/neoforge/fluids/FluidType;)V"))
    private void unsuspiciousblock$selectFluidJumpVelocity(LivingEntity entity, FluidType fluid,
                                                          Operation<Void> original) {
        if (!(entity instanceof Creeper creeper)) {
            original.call(entity, fluid);
            return;
        }
        Vec3 carried = creeper.getDeltaMovement();
        original.call(entity, fluid);
        creeper.setDeltaMovement(AuraMovement.selectGroundVelocity(creeper, carried, creeper.getDeltaMovement()));
    }
}
