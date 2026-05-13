package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.enchantment.fossil.FossilHunterService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 骨块被非玩家路径移除时，及时清理玩家放置标记。 */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourMixin {
    @Inject(
            method = "onPlace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            at = @At("HEAD")
    )
    private void unsuspiciousblock$movePlacedBoneBlockMarker(BlockState state, Level level, BlockPos pos,
                                                             BlockState oldState, boolean movedByPiston,
                                                             CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)
                || !movedByPiston
                || !state.is(Blocks.BONE_BLOCK)) {
            return;
        }
        FossilHunterService.tryMovePlacedBoneBlock(serverLevel, pos);
    }

    @Inject(
            method = "onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            at = @At("HEAD")
    )
    private void unsuspiciousblock$clearPlacedBoneBlock(BlockState state, Level level, BlockPos pos,
                                                        BlockState newState, boolean movedByPiston,
                                                        CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)
                || movedByPiston
                || !state.is(Blocks.BONE_BLOCK)
                || newState.is(Blocks.BONE_BLOCK)
                || FossilHunterService.isPlayerBreakingBoneBlock(serverLevel, pos)) {
            return;
        }
        FossilHunterService.clearPlacedBoneBlock(serverLevel, pos);
    }
}
