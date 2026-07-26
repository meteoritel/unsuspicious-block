package com.meteorite.unsuspiciousblock.world;

import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.HashSet;
import java.util.Set;

/**
 * 自然生成骨块位置追踪——静态门面，抹平平台差异。
 * <p>
 * 持久化部分委托给 {@link IBoneBlockTracker} 平台服务；玩家破坏路径中的延迟消费状态
 * （{@link PendingPlayerBreak}）仅存在于内存中，用于在方块移除事件与附魔效果触发之间
 * 保持标记不被过早清除。
 * TODO 审查活塞移动骨块后标记是否被移除，是否有必要加入区块载入时核查标记处是否还存在骨块
 */
public final class NaturalBoneBlockTracker {
    // 玩家破坏路径中的骨块坐标，用于延迟清除自然生成标记
    private static final Set<PendingPlayerBreak> PENDING_PLAYER_BREAKS = new HashSet<>();

    private NaturalBoneBlockTracker() {
    }

    // 查询指定位置是否为自然生成的骨块
    public static boolean isNatural(ServerLevel level, BlockPos pos) {
        return Services.BONE_BLOCK_TRACKER.isNatural(level, pos);
    }

    // 标记指定位置为自然生成
    public static void markNatural(ServerLevel level, BlockPos pos) {
        Services.BONE_BLOCK_TRACKER.markNatural(level, pos);
    }

    // 清除指定位置的自然生成标记
    public static void clearNatural(ServerLevel level, BlockPos pos) {
        Services.BONE_BLOCK_TRACKER.clearNatural(level, pos);
    }

    // 消费自然生成标记：若存在则清除并返回 true，否则返回 false
    public static boolean consumeNatural(ServerLevel level, BlockPos pos) {
        if (!isNatural(level, pos)) {
            return false;
        }
        clearNatural(level, pos);
        return true;
    }

    // 直接扫描并写入已知 chunk，生成事件调用此重载时不会重新进入 chunk 获取流程
    public static void scanChunk(ChunkAccess chunk) {
        chunk.findBlocks(NaturalBoneBlockTracker::isBoneBlock,
                (pos, state) -> Services.BONE_BLOCK_TRACKER.markNatural(chunk, pos));
    }

    // 扫描指定 bounding box 内的骨块，全部标记为自然生成——用于结构生成监听
    public static void scanBoundingBox(ServerLevel level, BoundingBox box) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    cursor.set(x, y, z);
                    if (level.getBlockState(cursor).is(Blocks.BONE_BLOCK)) {
                        Services.BONE_BLOCK_TRACKER.markNatural(level, cursor);
                    }
                }
            }
        }
    }

    private static boolean isBoneBlock(BlockState state) {
        return state.is(Blocks.BONE_BLOCK);
    }

    // 记录玩家正在破坏某骨块坐标——用于延迟清除自然生成标记
    public static void markPlayerBreaking(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.is(Blocks.BONE_BLOCK)) {
            return;
        }
        PENDING_PLAYER_BREAKS.add(new PendingPlayerBreak(level.dimension(), pos.asLong()));
    }

    // 查询玩家是否正在破坏某骨块坐标
    public static boolean isPlayerBreaking(ServerLevel level, BlockPos pos) {
        return PENDING_PLAYER_BREAKS.contains(new PendingPlayerBreak(level.dimension(), pos.asLong()));
    }

    // 清除玩家破坏某骨块坐标的记录
    public static void clearPlayerBreaking(ServerLevel level, BlockPos pos) {
        PENDING_PLAYER_BREAKS.remove(new PendingPlayerBreak(level.dimension(), pos.asLong()));
    }

    // 服务器关闭时清空全部破坏流程记录
    public static void clearPendingPlayerBreaks() {
        PENDING_PLAYER_BREAKS.clear();
    }

    private record PendingPlayerBreak(ResourceKey<Level> dimension, long packedPos) {
    }
}
