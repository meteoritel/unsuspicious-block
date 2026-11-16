package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 将区域通行约束施加在原版已经生成的候选边上。 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNeighborsAuraMixin extends NodeEvaluator {
    @Inject(method = "getNeighbors", at = @At("RETURN"), cancellable = true)
    private void unsuspiciousblock$filterAuraEdges(Node[] neighbors, Node origin, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(AuraRepulsion.filterNeighbors(mob, origin, neighbors, cir.getReturnValueI()));
    }
}
