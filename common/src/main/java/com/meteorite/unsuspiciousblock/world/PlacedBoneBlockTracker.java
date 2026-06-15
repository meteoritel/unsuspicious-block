package com.meteorite.unsuspiciousblock.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * 骨块放置标记与破坏流程跟踪——服务于化石猎手附魔，区分玩家放置与自然生成的骨块。
 * <p>
 * 本类仅做状态管理，不涉及附魔效果本身；附魔效果由 framework 下的 FossilHunterEffect 承载。
 */
public final class PlacedBoneBlockTracker {
    // 玩家破坏路径中的骨块坐标，用于延迟消费放置标记
    private static final Set<PendingPlayerBreak> PENDING_PLAYER_BREAKS = new HashSet<>();

    private PlacedBoneBlockTracker() {
    }

    // 标记玩家放置的骨块位置
    public static void markPlaced(ServerLevel level, BlockPos pos) {
        PlacedBoneBlockSavedData.get(level).markPlaced(pos);
    }

    // 清除指定位置的放置标记
    public static void clearPlaced(ServerLevel level, BlockPos pos) {
        PlacedBoneBlockSavedData.get(level).clearPlaced(pos);
    }

    // 消费放置标记，返回该位置是否曾被标记为玩家放置
    public static boolean consumePlaced(ServerLevel level, BlockPos pos) {
        return PlacedBoneBlockSavedData.get(level).consumePlaced(pos);
    }

    // 活塞推动骨块时，尝试将邻近的放置标记迁移到当前位置
    public static void tryMovePlaced(ServerLevel level, BlockPos pos) {
        PlacedBoneBlockSavedData data = PlacedBoneBlockSavedData.get(level);
        for (Direction direction : Direction.values()) {
            BlockPos fromPos = pos.relative(direction);
            // 当前位置若已是骨块，无需迁移
            if (level.getBlockState(fromPos).is(Blocks.BONE_BLOCK)) {
                continue;
            }
            if (data.movePlaced(fromPos, pos)) {
                return;
            }
        }
    }

    // 记录玩家正在破坏某骨块坐标
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
