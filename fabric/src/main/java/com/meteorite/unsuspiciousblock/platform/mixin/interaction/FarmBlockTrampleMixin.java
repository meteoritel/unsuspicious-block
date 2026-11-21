package com.meteorite.unsuspiciousblock.platform.mixin.interaction;

import com.meteorite.unsuspiciousblock.core.cat.CatPassiveAbilities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * 轻步——践踏耕地不会使其退化，但仍保留原版落地与摔落伤害处理。
 */
@Mixin(FarmBlock.class)
public abstract class FarmBlockTrampleMixin {

    @WrapOperation(method = "fallOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FarmBlock;turnToDirt(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void unsuspiciousblock$lightStepNoTrample(Entity entity, BlockState state,
                                                       Level level, BlockPos pos, Operation<Void> original) {
        if (!(entity instanceof Player player) || !CatPassiveAbilities.hasLightStep(player)) {
            original.call(entity, state, level, pos);
        }
    }
}
