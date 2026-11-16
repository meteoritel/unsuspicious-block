package com.meteorite.unsuspiciousblock.core.block;

import com.meteorite.unsuspiciousblock.core.world.aura.BlockAuras;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/** 沿用原版花盆交互，并将盆栽状态登记为光环源。 */
public final class PottedPurrmintBlock extends FlowerPotBlock {
    public static final MapCodec<FlowerPotBlock> CODEC = simpleCodec(PottedPurrmintBlock::new);

    // NeoForge 的原版构造入口标记为弃用，仍供跨平台花盆子类使用。
    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    public PottedPurrmintBlock(Properties properties) {
        super(ModBlocks.PURRMINT.get(), properties);
    }

    @Override
    public @NotNull MapCodec<FlowerPotBlock> codec() {
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
}
