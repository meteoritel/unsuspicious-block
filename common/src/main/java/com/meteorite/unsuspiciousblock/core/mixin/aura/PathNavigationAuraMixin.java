package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** 路径复用前复核目标，提交实际 waypoint 时复核移动段。 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationAuraMixin {
    @Shadow @Final protected Mob mob;

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$releaseProtectedTarget(Set<BlockPos> targets, int padding, boolean offset,
                                                         int reach, float distance, CallbackInfoReturnable<Path> cir) {
        if (AuraRepulsion.clearProtectedTarget(mob)) {
            cir.setReturnValue(null);
        }
    }

    @ModifyVariable(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"), argsOnly = true)
    private Set<BlockPos> unsuspiciousblock$filterAuraDestinations(Set<BlockPos> targets) {
        return AuraRepulsion.filterDestinations(mob, targets);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/control/MoveControl;setWantedPosition(DDDD)V"))
    private void unsuspiciousblock$checkWaypoint(MoveControl control, double x, double y, double z, double speed,
                                                Operation<Void> original) {
        if (AuraRepulsion.isRepelled(mob) && AuraRepulsion.blocksMovement(mob, new Vec3(x, y, z))) {
            AuraRepulsion.stopMovement(mob);
        } else {
            original.call(control, x, y, z, speed);
        }
    }
}
