package com.meteorite.unsuspiciousblock.mixin.block;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.world.PlacedBoneBlockTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 骨块被非玩家路径移除时，及时清理玩家放置标记；追踪容器被破坏时 flush 日志条目。 */
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
        PlacedBoneBlockTracker.tryMovePlaced(serverLevel, pos);
    }

    @Inject(
            method = "onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            at = @At("HEAD")
    )
    private void unsuspiciousblock$onBlockRemove(BlockState state, Level level, BlockPos pos,
                                                 BlockState newState, boolean movedByPiston,
                                                 CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel) || movedByPiston) {
            return;
        }
        // 骨块非活塞移除时，清理放置标记
        if (state.is(Blocks.BONE_BLOCK) && !newState.is(Blocks.BONE_BLOCK)
                && !PlacedBoneBlockTracker.isPlayerBreaking(serverLevel, pos)) {
            PlacedBoneBlockTracker.clearPlaced(serverLevel, pos);
        }
        // 追踪容器被破坏时，flush 其日志条目
        if (state.hasBlockEntity()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof TrackedContainerLootState) {
                ArchaeologyLootRuntimeTracker.onContainerBlockDestroyed(serverLevel, pos);
            }
        }
    }
}