package com.meteorite.unsuspiciousblock.pan;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/***
 * 闪烁的光落点判定工具——河流群系识别、开阔水域选取及水与普通冰的依附校验。
 * <p>
 * 自然生成与世界生成两条来源共用这里的判定，保证“能生成的点”与“能存活的点”始终一致。
 */
public final class ShimmerPlacement {
    // 以世界水平面为基准向下搜索水面方块的最大深度
    private static final int SURFACE_SEARCH_DEPTH = 3;
    // 3x3 水平范围内至少需要几列开阔水面或冰面，避免在过窄的水流中生成
    private static final int MIN_OPEN_SURFACE_COLUMNS = 6;
    private static final int WANDER_ATTEMPTS = 12;

    private ShimmerPlacement() {
    }

    // 指定位置是否属于河流群系
    public static boolean isRiverBiome(LevelReader level, BlockPos pos) {
        return level.getBiome(pos).is(BiomeTags.IS_RIVER);
    }

    // 区块内是否含河流群系——3x3 列采样，用于生成前的廉价预筛
    public static boolean hasRiverBiome(LevelReader level, ChunkPos chunkPos, int seaLevel) {
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

    // 在世界水平面附近寻找可依附的开阔水面或冰面方块；找不到时返回 null
    @Nullable
    public static BlockPos findOpenWaterSurface(LevelReader level, int x, int z, int seaLevel) {
        for (int y = seaLevel + 1; y >= seaLevel - SURFACE_SEARCH_DEPTH; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!isWaterOrIce(level.getBlockState(pos))) {
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

    // 3x3 水平范围内足够多的开阔水面或冰面列，视为“开阔水域”
    public static boolean isOpenWater(LevelReader level, BlockPos waterPos) {
        int openColumns = 0;
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                BlockPos neighbor = waterPos.offset(offsetX, 0, offsetZ);
                if (isWaterOrIce(level.getBlockState(neighbor))
                        && level.getBlockState(neighbor.above()).isAir()) {
                    openColumns++;
                }
            }
        }
        return openColumns >= MIN_OPEN_SURFACE_COLUMNS;
    }

    // 普通冰是河水冻结后的依附面；不把浮冰、蓝冰或其它固体当作河水。
    private static boolean isWaterOrIce(BlockState state) {
        return state.is(Blocks.WATER) || state.is(Blocks.ICE);
    }

    // 冰水互换不破坏依附关系；上方仍须为空气。
    public static boolean isBoundWaterIntact(LevelReader level, BlockPos waterPos) {
        return isWaterOrIce(level.getBlockState(waterPos))
                && level.getBlockState(waterPos.above()).isAir();
    }

    // 随机起点逐区探查 4×3 分区；内缩一格保证 3×3 水面检查只读取本区块。
    @Nullable
    public static BlockPos wanderForSurface(LevelReader level, RandomSource random, ChunkPos chunkPos,
                                           int seaLevel, Predicate<BlockPos> acceptsPosition) {
        int start = random.nextInt(WANDER_ATTEMPTS);
        for (int attempt = 0; attempt < WANDER_ATTEMPTS; attempt++) {
            int cell = (start + attempt) % WANDER_ATTEMPTS;
            int minX = 1 + (cell % 4) * 14 / 4;
            int maxX = 1 + (cell % 4 + 1) * 14 / 4;
            int minZ = 1 + (cell / 4) * 14 / 3;
            int maxZ = 1 + (cell / 4 + 1) * 14 / 3;
            BlockPos pos = findOpenWaterSurface(level,
                    chunkPos.getMinBlockX() + minX + random.nextInt(maxX - minX),
                    chunkPos.getMinBlockZ() + minZ + random.nextInt(maxZ - minZ), seaLevel);
            if (pos != null && isRiverBiome(level, pos) && acceptsPosition.test(pos)) {
                return pos;
            }
        }
        return null;
    }
}
