package com.meteorite.unsuspiciousblock.pan.variant;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/***
 * 依附规则——淘洗点依附面的识别、冻结相位与演出高度。
 * <p>
 * 这是变体之间少数需要真实算法而非纯参数的差异之一：水域认水与河水冻结后的普通冰，
 * 岩浆域只认岩浆且永不冻结。落点采样骨架只保留一份，介质差异全部由本接口提供。
 */
public interface AnchorRule {
    // 该方块是否为可依附介质，不校验上方是否为空气
    boolean isAnchorBlock(BlockState state);

    // 代表介质方块——调试指令据此铺设测试点，取该变体最典型的依附介质
    BlockState mediumState();

    // 该位置是否构成有效依附：介质方块 + 上方空气
    default boolean isValid(LevelReader level, BlockPos pos) {
        return this.isAnchorBlock(level.getBlockState(pos))
                && level.getBlockState(pos.above()).isAir();
    }

    // 客户端波光可绘制判定：冻结相位恒可绘制，否则要求该方块本身是依附介质
    default boolean isRenderableSurface(LevelReader level, BlockPos pos, boolean frozen) {
        return frozen || this.isAnchorBlock(level.getBlockState(pos));
    }

    // 是否处于冻结静态相位；岩浆域恒为 false
    boolean isFrozenAt(LevelReader level, BlockPos pos);

    // 演出高度：淘洗点粒子、音效与生成特效相对依附方块底部的定位高度
    double surfaceOffset(LevelReader level, BlockPos pos, boolean frozen);
}
