package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 原版地面 navigation 调整目标高度之前检查原始目的地。 */
@Mixin(GroundPathNavigation.class)
public abstract class GroundNavigationAuraMixin extends PathNavigation {
    protected GroundNavigationAuraMixin(Mob mob, Level level) {
        super(mob, level);
    }

    @Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$checkBlockDestination(BlockPos target, int reach, CallbackInfoReturnable<Path> cir) {
        if (AuraRepulsion.clearProtectedTarget(mob) || !AuraRepulsion.canMoveTo(mob, target)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "createPath(Lnet/minecraft/world/entity/Entity;I)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$checkEntityDestination(Entity target, int reach, CallbackInfoReturnable<Path> cir) {
        if (AuraRepulsion.clearProtectedTarget(mob) || !AuraRepulsion.canMoveTo(mob, target.blockPosition())) {
            cir.setReturnValue(null);
        }
    }
}
