package com.meteorite.unsuspiciousblock.journal.tracking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 陶罐追踪归属解析服务。
 * <p>
 * 原版陶罐解析战利品时不传玩家，优先使用掉落上下文中的玩家；缺失时回退到 32 格内最近玩家。
 */
public final class DecoratedPotTrackingService {
    private static final double MAX_TRACKING_DISTANCE_SQR = 32.0D * 32.0D;

    private DecoratedPotTrackingService() {
    }

    // 解析本次陶罐追踪归属；无法可靠找到附近玩家时返回 null
    @Nullable
    public static ServerPlayer resolvePlayer(ServerLevel level, BlockPos pos, @Nullable Entity sourceEntity) {
        return resolvePlayer(level, pos, sourceEntity, null);
    }

    // 优先恢复解析阶段玩家，其次使用破坏来源玩家，最后回退到附近玩家
    @Nullable
    public static ServerPlayer resolvePlayer(ServerLevel level, BlockPos pos,
                                             @Nullable Entity sourceEntity,
                                             @Nullable UUID preferredPlayerUuid) {
        if (preferredPlayerUuid != null) {
            ServerPlayer preferredPlayer = level.getServer().getPlayerList().getPlayer(preferredPlayerUuid);
            if (preferredPlayer != null) {
                return preferredPlayer;
            }
        }
        if (sourceEntity instanceof ServerPlayer player) {
            return player;
        }
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        ServerPlayer nearest = null;
        double nearestDistance = MAX_TRACKING_DISTANCE_SQR;
        for (ServerPlayer candidate : level.players()) {
            double distance = candidate.distanceToSqr(x, y, z);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }
}
