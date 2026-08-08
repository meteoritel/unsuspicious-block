package com.meteorite.unsuspiciousblock.block;

import com.mojang.serialization.MapCodec;
import com.meteorite.unsuspiciousblock.blockentity.UnsuspiciousBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 不可疑的沙子与沙砾共用方块实现，负责封存数据、重力检测与碎裂掉落。
 */
public class UnsuspiciousBlock extends FallingBlock implements EntityBlock {
    public static final MapCodec<UnsuspiciousBlock> CODEC = simpleCodec(UnsuspiciousBlock::new);

    public UnsuspiciousBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull MapCodec<? extends FallingBlock> codec() {
        return CODEC;
    }

    @Override
    public @NotNull BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new UnsuspiciousBlockEntity(pos, state);
    }

    @Override
    protected void tick(@NotNull BlockState state, @NotNull ServerLevel level, @NotNull BlockPos pos,
                        @NotNull RandomSource random) {
        if (FallingBlock.isFree(level.getBlockState(pos.below()))) {
            level.destroyBlock(pos, true);
        }
    }

    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state,
                            @Nullable LivingEntity placer, @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.getBlockEntity(pos) instanceof UnsuspiciousBlockEntity blockEntity) {
            blockEntity.loadFromItem(stack);
        }
    }

    @Override
    protected void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                            @NotNull BlockState newState, boolean movedByPiston) {
        if (state.getBlock() != newState.getBlock()
                && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof UnsuspiciousBlockEntity blockEntity) {
            ItemStack sealedItem = blockEntity.takeSealedItem();
            if (!sealedItem.isEmpty()) {
                popResource(serverLevel, pos, sealedItem);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
