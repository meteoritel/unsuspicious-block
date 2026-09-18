package com.meteorite.unsuspiciousblock.pan.variant;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/***
 * 水域依附规则——水源方块与河水冻结后的普通冰。
 * <p>
 * 浮冰、蓝冰等其它固体不视为河水；冰水互换不破坏依附关系。演出高度沿用原有取值：
 * 水面 0.875（粒子与音效贴水面的既有调校值），冰面 1.0（位于完整方块顶面）。
 */
public final class WaterAnchor implements AnchorRule {
    private static final double WATER_SURFACE_OFFSET = 0.875D;
    private static final double FROZEN_SURFACE_OFFSET = 1.0D;

    @Override
    public boolean isAnchorBlock(BlockState state) {
        return state.is(Blocks.WATER) || state.is(Blocks.ICE);
    }

    @Override
    public boolean isFrozenAt(LevelReader level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.ICE);
    }

    @Override
    public double surfaceOffset(LevelReader level, BlockPos pos, boolean frozen) {
        return frozen ? FROZEN_SURFACE_OFFSET : WATER_SURFACE_OFFSET;
    }
}
