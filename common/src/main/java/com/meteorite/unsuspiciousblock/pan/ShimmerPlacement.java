package com.meteorite.unsuspiciousblock.pan;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

/**
 * 闪烁的光落点判定工具——河流群系识别、开阔水域选取与绑定水方块校验。
 * <p>
 * 自然生成与世界生成两条来源共用这里的判定，保证“能生成的点”与“能存活的点”始终一致。
 */
public final class ShimmerPlacement {
    // 以世界水平面为基准向下搜索水面方块的最大深度
    private static final int SURFACE_SEARCH_DEPTH = 3;
    // 3x3 水平范围内至少需要几列开阔水面，避免在过窄的水流中生成
    private static final int MIN_OPEN_SURFACE_COLUMNS = 6;

    private ShimmerPlacement() {
    }

    // 指定位置是否属于河流群系
    public static boolean isRiverBiome(Level level, BlockPos pos) {
        return level.getBiome(pos).is(BiomeTags.IS_RIVER);
    }

    // 区块内是否含河流群系——3x3 列采样，用于生成前的廉价预筛
    public static boolean hasRiverBiome(Level level, ChunkPos chunkPos) {
        int seaLevel = level.getSeaLevel();
        for (int offsetX = 4; offsetX <= 12; offsetX += 4) {
            for (int offsetZ = 4; offsetZ <= 12; offsetZ += 4) {
                BlockPos sample = new BlockPos(chunkPos.getMinBlockX() + offsetX, seaLevel,
                        chunkPos.getMinBlockZ() + offsetZ);
                if (isRiverBiome(level, sample)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 在世界水平面附近寻找可依附的开阔水面方块；找不到时返回 null
    @Nullable
    public static BlockPos findOpenWaterSurface(Level level, int x, int z) {
        int seaLevel = level.getSeaLevel();
        for (int y = seaLevel + 1; y >= seaLevel - SURFACE_SEARCH_DEPTH; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos).is(Blocks.WATER)) {
                continue;
            }
            // 水面之上必须是空气，否则玩家无法看到水面波光
            if (!level.getBlockState(pos.above()).isAir()) {
                return null;
            }
            return isOpenWater(level, pos) ? pos : null;
        }
        return null;
    }

    // 3x3 水平范围内足够多的开阔水面列，视为“开阔水域”
    public static boolean isOpenWater(Level level, BlockPos waterPos) {
        int openColumns = 0;
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                BlockPos neighbor = waterPos.offset(offsetX, 0, offsetZ);
                if (level.getBlockState(neighbor).is(Blocks.WATER)
                        && level.getBlockState(neighbor.above()).isAir()) {
                    openColumns++;
                }
            }
        }
        return openColumns >= MIN_OPEN_SURFACE_COLUMNS;
    }

    // 绑定的水方块是否仍然完好：仍是水，且上方为空气
    public static boolean isBoundWaterIntact(Level level, BlockPos waterPos) {
        return level.getBlockState(waterPos).is(Blocks.WATER)
                && level.getBlockState(waterPos.above()).isAir();
    }
}
