package com.meteorite.unsuspiciousblock.core.blockentity;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Function;

/** 统一脏标记与服务端方块通知，状态来源由宿主决定。 */
public final class BlockEntitySync {
    private BlockEntitySync() {
    }

    public static void markChanged(BlockEntity entity, Function<Level, BlockState> stateSource) {
        entity.setChanged();
        Level level = entity.getLevel();
        if (level != null && !level.isClientSide()) {
            BlockState state = stateSource.apply(level);
            level.sendBlockUpdated(entity.getBlockPos(), state, state, 3);
        }
    }
}
