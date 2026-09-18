package com.meteorite.unsuspiciousblock.pan.variant;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/***
 * 岩浆依附规则——下界的岩浆源方块。
 * <p>
 * 依附判定不区分流体等级：岩浆海面与流动岩浆都可生成，只要上方是空气即可绘制波光。
 * 岩浆没有冻结相位，演出高度直接取流体实际高度（源方块上方为空气时约 0.889）。
 */
public final class LavaAnchor implements AnchorRule {
    @Override
    public boolean isAnchorBlock(BlockState state) {
        return state.is(Blocks.LAVA);
    }

    @Override
    public BlockState mediumState() {
        return Blocks.LAVA.defaultBlockState();
    }

    @Override
    public boolean isFrozenAt(LevelReader level, BlockPos pos) {
        return false;
    }

    @Override
    public double surfaceOffset(LevelReader level, BlockPos pos, boolean frozen) {
        return level.getFluidState(pos).getHeight(level, pos);
    }
}
