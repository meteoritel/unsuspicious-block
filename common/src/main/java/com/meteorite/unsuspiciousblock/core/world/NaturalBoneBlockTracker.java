package com.meteorite.unsuspiciousblock.core.world;

import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;


/** 识别结构自然生成的骨块，位置标记由双平台的区块存储持久化。 */
public final class NaturalBoneBlockTracker {
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

    // 直接扫描并写入已知 chunk，生成事件调用此重载时不会重新进入 chunk 获取流程
    public static void scanChunk(ChunkAccess chunk) {
        chunk.findBlocks(NaturalBoneBlockTracker::isBoneBlock,
                (pos, state) -> Services.BONE_BLOCK_TRACKER.markNatural(chunk, pos));
    }

    private static boolean isBoneBlock(BlockState state) {
        return state.is(Blocks.BONE_BLOCK);
    }
}
