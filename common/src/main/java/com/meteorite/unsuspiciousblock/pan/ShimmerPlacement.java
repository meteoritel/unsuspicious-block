package com.meteorite.unsuspiciousblock.pan;

import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/***
 * 淘洗点落点采样骨架——沿生成域的海平面上下寻找可依附的液面，并校验其足够开阔。
 * <p>
 * 依附介质、群系与冻结判定全部由变体提供，本类只保留一份与介质无关的采样流程，
 * 自然生成与世界生成两条来源共用它，保证「能生成的点」与「能存活的点」始终一致。
 */
public final class ShimmerPlacement {
    // 以世界水平面为基准向下搜索液面方块的最大深度
    private static final int SURFACE_SEARCH_DEPTH = 3;
    // 3x3 水平范围内至少需要几列开阔液面，避免在过窄的水流中生成
    private static final int MIN_OPEN_SURFACE_COLUMNS = 6;
    private static final int WANDER_ATTEMPTS = 12;

    private ShimmerPlacement() {
    }

    // 在生成域的海平面附近寻找可依附的开阔液面；找不到时返回 null
    @Nullable
    public static BlockPos findSurface(LevelReader level, ShimmerVariant variant, int x, int z, int seaLevel) {
        for (int y = seaLevel + 1; y >= seaLevel - SURFACE_SEARCH_DEPTH; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!variant.anchor().isAnchorBlock(level.getBlockState(pos))) {
                continue;
            }
            // 液面之上必须是空气，否则玩家无法看到波光
            if (!level.getBlockState(pos.above()).isAir()) {
                return null;
            }
            return isOpenSurface(level, variant, pos) ? pos : null;
        }
        return null;
    }

    // 3x3 水平范围内足够多的开阔液面列，视为开阔液面
    public static boolean isOpenSurface(LevelReader level, ShimmerVariant variant, BlockPos surfacePos) {
        int openColumns = 0;
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                BlockPos neighbor = surfacePos.offset(offsetX, 0, offsetZ);
                if (variant.anchor().isAnchorBlock(level.getBlockState(neighbor))
                        && level.getBlockState(neighbor.above()).isAir()) {
                    openColumns++;
                }
            }
        }
        return openColumns >= MIN_OPEN_SURFACE_COLUMNS;
    }

    // 随机起点逐区探查 4×3 分区；内缩一格保证 3×3 液面检查只读取本区块。
    @Nullable
    public static BlockPos wanderForSurface(LevelReader level, RandomSource random, ChunkPos chunkPos,
                                            ShimmerVariant variant, int seaLevel,
                                            Predicate<BlockPos> acceptsPosition) {
        int start = random.nextInt(WANDER_ATTEMPTS);
        for (int attempt = 0; attempt < WANDER_ATTEMPTS; attempt++) {
            int cell = (start + attempt) % WANDER_ATTEMPTS;
            int minX = 1 + (cell % 4) * 14 / 4;
            int maxX = 1 + (cell % 4 + 1) * 14 / 4;
            int minZ = 1 + (cell / 4) * 14 / 3;
            int maxZ = 1 + (cell / 4 + 1) * 14 / 3;
            BlockPos pos = findSurface(level, variant,
                    chunkPos.getMinBlockX() + minX + random.nextInt(maxX - minX),
                    chunkPos.getMinBlockZ() + minZ + random.nextInt(maxZ - minZ), seaLevel);
            if (pos != null && variant.spawnDomain().acceptsBiome(level, pos) && acceptsPosition.test(pos)) {
                return pos;
            }
        }
        return null;
    }
}
