package com.meteorite.unsuspiciousblock.mixin.chunk;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.world.IBoneBlockChunkAccess;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ChunkSerializer 读写注入——将自然骨块坐标集合随 chunk NBT 持久化。
 * <p>
 * 写入：{@link ChunkSerializer#write} 返回前，读取 chunk（若为 ImposterProtoChunk 取其 wrapped）字段并写入 NBT；
 * 读取：{@link ChunkSerializer#read} 返回后，从 NBT 回填到 wrapped LevelChunk（若返回 ImposterProtoChunk）。
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {

    @Unique
    private static final String TAG_NATURAL_BONE_BLOCKS =
            Constants.MOD_ID + ":natural_bone_blocks";

    // 取 chunk 实际承载字段的 accessor——ImposterProtoChunk 委托到 wrapped LevelChunk
    @Unique
    private static IBoneBlockChunkAccess unsuspiciousblock$accessor(ChunkAccess chunk) {
        if (chunk instanceof ImposterProtoChunk imposter) {
            return (IBoneBlockChunkAccess) imposter.getWrapped();
        }
        return (IBoneBlockChunkAccess) chunk;
    }

    @Inject(method = "write", at = @At("TAIL"))
    private static void unsuspiciousblock$writeNaturalBoneBlocks(ServerLevel level, ChunkAccess chunk,
                                                                 CallbackInfoReturnable<CompoundTag> cir) {
        IBoneBlockChunkAccess accessor = unsuspiciousblock$accessor(chunk);
        LongOpenHashSet set = accessor.unsuspiciousblock_getNaturalBoneBlocks();
        if (set == null || set.isEmpty()) {
            return;
        }
        cir.getReturnValue().putLongArray(TAG_NATURAL_BONE_BLOCKS, set.toLongArray());
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void unsuspiciousblock$readNaturalBoneBlocks(ServerLevel level,
                                                                PoiManager poiManager,
                                                                RegionStorageInfo storageInfo,
                                                                ChunkPos chunkPos,
                                                                CompoundTag tag,
                                                                CallbackInfoReturnable<ChunkAccess> cir) {
        if (!tag.contains(TAG_NATURAL_BONE_BLOCKS, net.minecraft.nbt.Tag.TAG_LONG_ARRAY)) {
            return;
        }
        long[] packed = tag.getLongArray(TAG_NATURAL_BONE_BLOCKS);
        if (packed.length == 0) {
            return;
        }
        LongOpenHashSet set = new LongOpenHashSet(new LongArrayList(packed));
        unsuspiciousblock$accessor(cir.getReturnValue()).unsuspiciousblock_setNaturalBoneBlocks(set);
    }
}
