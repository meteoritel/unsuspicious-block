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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 柔软肉垫（恩惠≥60）——践踏耕地不会使其退化。
 * 注入 FarmBlock.fallOn，对持有该被动的玩家整体取消落地处理（既不退化耕地也不造成摔伤）。
 */
@Mixin(FarmBlock.class)
public abstract class FarmBlockTrampleMixin {

    @Inject(method = "fallOn", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$softPawsNoTrample(Level level, BlockState state, BlockPos pos,
                                                     Entity entity, float fallDistance, CallbackInfo ci) {
        if (entity instanceof Player player && CatPassiveAbilities.hasSoftPaws(player)) {
            ci.cancel();
        }
    }
}
