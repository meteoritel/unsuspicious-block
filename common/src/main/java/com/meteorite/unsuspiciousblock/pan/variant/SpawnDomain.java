package com.meteorite.unsuspiciousblock.pan.variant;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkGenerator;

/***
 * 生成域——淘洗点的落点基准高度、群系预筛与区块级廉价预筛。
 * <p>
 * 这是变体之间第二处需要真实算法的差异：水域沿河流群系与海平面采样，岩浆域沿下界群系与岩浆海面采样。
 */
public interface SpawnDomain {
    // 液面搜索的基准高度，默认取生成器海平面；两端维度的该常量已核实为一致来源
    default int surfaceSearchCenter(ChunkGenerator generator) {
        return generator.getSeaLevel();
    }

    // 该位置是否属于本变体的生成域
    boolean acceptsBiome(LevelReader level, BlockPos pos);

    // 区块级廉价预筛，避免在不含生成域的区块上做逐格水面采样
    boolean acceptsChunk(LevelReader level, ChunkPos chunkPos, int seaLevel);
}
