package com.meteorite.unsuspiciousblock.mixin.chunk;

import com.meteorite.unsuspiciousblock.world.IBoneBlockChunkAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LevelChunk promotion 钩子——从 ProtoChunk 构造 LevelChunk 时迁移自然骨块字段。
 * <p>
 * 原版 promotion 走 {@code new LevelChunk(serverLevel, protoChunk, postLoad)}，不会触发
 * {@code ChunkSerializer.write/read}，因此必须在此处显式复制字段，否则生成阶段写入的标记会丢失。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(
            method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
            at = @At("TAIL")
    )
    private void unsuspiciousblock$copyNaturalBoneBlocksFromProto(ServerLevel level, ProtoChunk proto,
                                                                  LevelChunk.PostLoadProcessor postLoad,
                                                                  CallbackInfo ci) {
        LongOpenHashSet set = ((IBoneBlockChunkAccess) (Object) proto).unsuspiciousblock_getNaturalBoneBlocks();
        if (set != null && !set.isEmpty()) {
            ((IBoneBlockChunkAccess) (Object) this).unsuspiciousblock_setNaturalBoneBlocks(set);
        }
    }
}
