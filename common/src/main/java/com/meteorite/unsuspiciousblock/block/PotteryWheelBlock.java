package com.meteorite.unsuspiciousblock.block;

import com.meteorite.unsuspiciousblock.blockentity.PotteryWheelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 纹饰陶轮台方块，提供陶罐合成和陶片压印工作界面。 */
public class PotteryWheelBlock extends Block implements EntityBlock {
    public static final BooleanProperty WET_CLAY = BooleanProperty.create("wet_clay");
    public static final BooleanProperty WORKING = BooleanProperty.create("working");
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(1.0D, 0.0D, 1.0D, 4.0D, 9.0D, 4.0D),
            Block.box(12.0D, 0.0D, 1.0D, 15.0D, 9.0D, 4.0D),
            Block.box(1.0D, 0.0D, 12.0D, 4.0D, 9.0D, 15.0D),
            Block.box(12.0D, 0.0D, 12.0D, 15.0D, 9.0D, 15.0D),
            Block.box(1.0D, 9.0D, 1.0D, 15.0D, 12.0D, 15.0D),
            Block.box(4.0D, 12.0D, 4.0D, 12.0D, 14.0D, 12.0D)
    ).optimize();

    public PotteryWheelBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
                .setValue(WET_CLAY, false)
                .setValue(WORKING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING, WET_CLAY, WORKING);
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return this.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,
                context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected @NotNull BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(HorizontalDirectionalBlock.FACING,
                rotation.rotate(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    protected @NotNull BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level,
                                            @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @NotNull BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new PotteryWheelBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            @NotNull Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        if (type != com.meteorite.unsuspiciousblock.blockentity.ModBlockEntities.POTTERY_WHEEL.get()) {
            return null;
        }
        if (level.isClientSide()) {
            return (tickLevel, pos, tickState, blockEntity) ->
                    PotteryWheelBlockEntity.clientTick(tickLevel, pos, tickState);
        }
        return (tickLevel, pos, tickState, blockEntity) ->
                PotteryWheelBlockEntity.serverTick(
                        (PotteryWheelBlockEntity) blockEntity);
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level,
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
