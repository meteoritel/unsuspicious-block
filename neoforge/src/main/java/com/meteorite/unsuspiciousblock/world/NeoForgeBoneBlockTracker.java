package com.meteorite.unsuspiciousblock.world;

import com.meteorite.unsuspiciousblock.Constants;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * NeoForge 端自然骨块追踪实现——基于 {@link AttachmentType} 挂载到 chunk。
 * <p>
 * 每个 chunk 持有一个 {@link LongOpenHashSet}，记录该 chunk 内自然生成骨块的 packed pos。
 * 序列化由 NeoForge 的 ChunkAttachment 机制随 chunk NBT 自动完成。
 */
public final class NeoForgeBoneBlockTracker implements IBoneBlockTracker {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Constants.MOD_ID);

    // LongOpenHashSet <-> LongList 的编解码器，用于 chunk NBT 持久化
    private static final Codec<LongOpenHashSet> LONG_SET_CODEC = Codec.LONG.listOf().xmap(
            LongOpenHashSet::new,
            LongArrayList::new
    );

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<LongOpenHashSet>> NATURAL_BONE_BLOCKS =
            ATTACHMENT_TYPES.register("natural_bone_blocks", () ->
                    AttachmentType.<LongOpenHashSet>builder(() -> new LongOpenHashSet())
                            .serialize(LONG_SET_CODEC)
                            .build());

    @Override
    public boolean isNatural(ServerLevel level, BlockPos pos) {
        LongOpenHashSet set = getData(level, pos);
        return set.contains(pos.asLong());
    }

    @Override
    public void markNatural(ServerLevel level, BlockPos pos) {
        markNatural(chunkOf(level, pos), pos);
    }

    @Override
    public void markNatural(ChunkAccess chunk, BlockPos pos) {
        LongOpenHashSet set = chunk.getData(NATURAL_BONE_BLOCKS.get());
        if (set.add(pos.asLong())) {
            chunk.setUnsaved(true);
        }
    }

    @Override
    public void clearNatural(ServerLevel level, BlockPos pos) {
        LongOpenHashSet set = getData(level, pos);
        if (set.remove(pos.asLong())) {
            chunkOf(level, pos).setUnsaved(true);
        }
    }

    // 读取指定位置所在 chunk 的 attachment，未初始化时返回 null
    private static LongOpenHashSet getData(ServerLevel level, BlockPos pos) {
        ChunkAccess chunk = level.getChunk(pos);
        return chunk.getData(NATURAL_BONE_BLOCKS.get());
    }

    private static ChunkAccess chunkOf(ServerLevel level, BlockPos pos) {
        return level.getChunk(pos);
    }

    // ServiceLoader 需要公共无参构造
    public NeoForgeBoneBlockTracker() {
    }
}
