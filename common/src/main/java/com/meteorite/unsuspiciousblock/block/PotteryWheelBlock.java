package com.meteorite.unsuspiciousblock.block;

import com.meteorite.unsuspiciousblock.blockentity.PotteryWheelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

/** 纹饰陶轮台方块，提供陶罐合成和陶片压印工作界面。 */
public class PotteryWheelBlock extends Block implements EntityBlock {
    public static final BooleanProperty WET_CLAY = BooleanProperty.create("wet_clay");

    public PotteryWheelBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
                .setValue(WET_CLAY, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING, WET_CLAY);
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return this.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,
                context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(HorizontalDirectionalBlock.FACING,
                rotation.rotate(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    public @NotNull BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new PotteryWheelBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level,
                                               @NotNull BlockPos pos, @NotNull Player player,
                                               @NotNull BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof PotteryWheelBlockEntity wheel) {
            player.openMenu(wheel);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                            @NotNull BlockState newState, boolean movedByPiston) {
        if (state.getBlock() != newState.getBlock()
                && !level.isClientSide()
                && level.getBlockEntity(pos) instanceof PotteryWheelBlockEntity wheel) {
            wheel.dropInputs(level);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
