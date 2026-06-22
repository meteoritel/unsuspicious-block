package com.meteorite.unsuspiciousblock.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 自然生成骨块位置追踪——平台服务接口。
 * <p>
 * 不同平台使用不同持久化承载：NeoForge 使用 {@code ChunkAttachment}，Fabric 使用 SavedData。
 * 调用方应通过 {@link NaturalBoneBlockTracker} 静态门面访问，不应直接持有本接口实例。
 */
public interface IBoneBlockTracker {

    // 查询指定位置是否为自然生成的骨块
    boolean isNatural(ServerLevel level, BlockPos pos);

    // 标记指定位置为自然生成
    void markNatural(ServerLevel level, BlockPos pos);

    // 清除指定位置的自然生成标记
    void clearNatural(ServerLevel level, BlockPos pos);
}
