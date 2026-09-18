package com.meteorite.unsuspiciousblock.pan.variant;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;

/***
 * 下界生成域——岩浆海遍布全部下界群系，因此区块预筛恒为通过。
 * <p>
 * 落点基准高度沿用 {@link SpawnDomain#surfaceSearchCenter} 的默认实现，即区块生成器的海平面：
 * 下界该值为 32，向下三格的搜索窗口正好覆盖岩浆海最上层（y = 31）。
 */
public final class NetherDomain implements SpawnDomain {
    @Override
    public boolean acceptsBiome(LevelReader level, BlockPos pos) {
        return level.getBiome(pos).is(BiomeTags.IS_NETHER);
    }

    @Override
    public boolean acceptsChunk(LevelReader level, ChunkPos chunkPos, int seaLevel) {
        return true;
    }
}
