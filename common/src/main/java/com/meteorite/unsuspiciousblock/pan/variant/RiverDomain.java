package com.meteorite.unsuspiciousblock.pan.variant;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;

/***
 * 河流生成域——河流群系识别与区块级列采样预筛。
 * <p>
 * 预筛在区块内 3×3 位置采样群系，用于在自然生成的落点游走之前廉价地排除非河流区块。
 */
public final class RiverDomain implements SpawnDomain {
    // 预筛采样点相对区块最小坐标的偏移
    private static final int SAMPLE_OFFSET_START = 4;
    private static final int SAMPLE_OFFSET_END = 12;
    private static final int SAMPLE_OFFSET_STEP = 4;

    @Override
    public boolean acceptsBiome(LevelReader level, BlockPos pos) {
        return level.getBiome(pos).is(BiomeTags.IS_RIVER);
    }

    @Override
    public boolean acceptsChunk(LevelReader level, ChunkPos chunkPos, int seaLevel) {
        for (int offsetX = SAMPLE_OFFSET_START; offsetX <= SAMPLE_OFFSET_END; offsetX += SAMPLE_OFFSET_STEP) {
            for (int offsetZ = SAMPLE_OFFSET_START; offsetZ <= SAMPLE_OFFSET_END; offsetZ += SAMPLE_OFFSET_STEP) {
                BlockPos sample = new BlockPos(chunkPos.getMinBlockX() + offsetX, seaLevel,
                        chunkPos.getMinBlockZ() + offsetZ);
                if (this.acceptsBiome(level, sample)) {
                    return true;
                }
            }
        }
        return false;
    }
}
