package com.meteorite.unsuspiciousblock.mixin.block;

import com.meteorite.unsuspiciousblock.world.PlacedBoneBlockTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 骨块在玩家破坏路径中延后消费放置标记，其他移除场景立即清理陈旧坐标。 */
@Mixin(net.minecraft.world.level.block.Block.class)
public abstract class BlockMixin {
    @Inject(
            method = "playerWillDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD")
    )
    private void unsuspiciousblock$markPlayerBreakingBoneBlock(Level level, BlockPos pos, BlockState state,
                                                               Player player,
                                                               CallbackInfoReturnable<BlockState> cir) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer)) {
            return;
        }
        PlacedBoneBlockTracker.markPlayerBreaking(serverLevel, pos, state);
    }

    @Inject(
            method = "destroy(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("TAIL")
    )
    private void unsuspiciousblock$clearPlayerBreakingBoneBlock(LevelAccessor level, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel) || !state.is(Blocks.BONE_BLOCK)) {
            return;
        }
        PlacedBoneBlockTracker.clearPlayerBreaking(serverLevel, pos);
    }
}