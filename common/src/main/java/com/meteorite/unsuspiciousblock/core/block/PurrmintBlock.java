package com.meteorite.unsuspiciousblock.core.block;

import com.meteorite.unsuspiciousblock.core.world.aura.BlockAuras;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

/** 成熟咕噜荆芥，可作为独立植株放置或装入原版花盆。 */
public class PurrmintBlock extends BushBlock {
    public static final MapCodec<PurrmintBlock> CODEC = simpleCodec(PurrmintBlock::new);
    private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 15.0, 15.0);

    public PurrmintBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull MapCodec<PurrmintBlock> codec() {
        return CODEC;
    }

    @Override
    protected void onPlace(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                           @NotNull BlockState previous, boolean isMoving) {
        super.onPlace(state, level, pos, previous, isMoving);
        BlockAuras.updateSource(level, pos, state);
    }

    @Override
    protected void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                            @NotNull BlockState replacement, boolean isMoving) {
        super.onRemove(state, level, pos, replacement, isMoving);
        BlockAuras.updateSource(level, pos, replacement);
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level,
                                          @NotNull BlockPos pos, @NotNull CollisionContext context) {
        Vec3 offset = state.getOffset(level, pos);
        return SHAPE.move(offset.x, offset.y, offset.z);
    }
}
