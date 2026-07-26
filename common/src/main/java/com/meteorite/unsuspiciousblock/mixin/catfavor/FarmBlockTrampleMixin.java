package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 轻步——践踏耕地不会使其退化，但仍保留原版落地与摔落伤害处理。
 */
@Mixin(FarmBlock.class)
public abstract class FarmBlockTrampleMixin {

    @Redirect(method = "fallOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FarmBlock;turnToDirt(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void unsuspiciousblock$lightStepNoTrample(Entity entity, BlockState state,
                                                       Level level, BlockPos pos) {
        if (!(entity instanceof Player player) || !CatPassiveAbilities.hasLightStep(player)) {
            FarmBlock.turnToDirt(entity, state, level, pos);
        }
    }
}
