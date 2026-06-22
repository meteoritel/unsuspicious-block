package com.meteorite.unsuspiciousblock.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Fabric 端自然骨块追踪实现——基于 mixin 注入到 {@link ChunkAccess} 的字段持久化。
 * <p>
 * 由 {@code ChunkAccessMixin} 承载字段、{@code ChunkSerializerMixin} 负责 NBT 持久化、
 * {@code LevelChunkMixin} 负责 promotion 迁移。本类只做读写转发，等价于 NeoForge 的 ChunkAttachment。
 */
public final class FabricBoneBlockTracker implements IBoneBlockTracker {

    @Override
    public boolean isNatural(ServerLevel level, BlockPos pos) {
        LongOpenHashSet set = accessor(level, pos).unsuspiciousblock_getNaturalBoneBlocks();
        return set != null && set.contains(pos.asLong());
    }

    @Override
    public void markNatural(ServerLevel level, BlockPos pos) {
        LongOpenHashSet set = accessor(level, pos).unsuspiciousblock_getOrCreateNaturalBoneBlocks();
        if (set.add(pos.asLong())) {
            level.getChunk(pos).setUnsaved(true);
        }
    }

    @Override
    public void clearNatural(ServerLevel level, BlockPos pos) {
        LongOpenHashSet set = accessor(level, pos).unsuspiciousblock_getNaturalBoneBlocks();
        if (set != null && set.remove(pos.asLong())) {
            level.getChunk(pos).setUnsaved(true);
        }
    }

    // 取目标 chunk 的 mixin accessor——ChunkAccess 的所有子类（ProtoChunk/LevelChunk/ImposterProtoChunk）都被注入字段
    private static IBoneBlockChunkAccess accessor(ServerLevel level, BlockPos pos) {
        ChunkAccess chunk = level.getChunk(pos);
        return (IBoneBlockChunkAccess) (Object) chunk;
    }

    public FabricBoneBlockTracker() {
    }
}
