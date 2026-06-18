package com.meteorite.unsuspiciousblock.mixin.block;

import com.meteorite.unsuspiciousblock.world.PlacedBoneBlockTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 记录玩家成功放置的骨块位置，供化石猎手区分自然生成与玩家放置。 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Shadow
    public abstract Block getBlock();

    @Inject(method = "place", at = @At("RETURN"))
    private void unsuspiciousblock$markPlacedBoneBlock(BlockPlaceContext context,
                                                       CallbackInfoReturnable<InteractionResult> cir) {
        if (!cir.getReturnValue().consumesAction()
                || this.getBlock() != Blocks.BONE_BLOCK
                || !(context.getPlayer() instanceof ServerPlayer)
                || !(context.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (this.unsuspiciousblock$tryMarkPlacedBoneBlock(serverLevel, context.getClickedPos())) {
            return;
        }
        this.unsuspiciousblock$tryMarkPlacedBoneBlock(serverLevel,
                context.getClickedPos().relative(context.getClickedFace()));
    }

    @Unique
    private boolean unsuspiciousblock$tryMarkPlacedBoneBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.BONE_BLOCK)) {
            return false;
        }
        PlacedBoneBlockTracker.markPlaced(level, pos);
        return true;
    }
}