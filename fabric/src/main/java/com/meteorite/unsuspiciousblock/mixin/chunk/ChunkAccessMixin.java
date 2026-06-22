package com.meteorite.unsuspiciousblock.mixin.chunk;

import com.meteorite.unsuspiciousblock.world.IBoneBlockChunkAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Fabric 端自然骨块 chunk attachment 承载——给 {@link ChunkAccess} 注入 {@link LongOpenHashSet} 字段，
 * 配合 {@link ChunkSerializerMixin} 完成随 chunk NBT 持久化、{@link LevelChunkMixin} 完成 promotion 迁移，
 * 等价于 NeoForge 的 ChunkAttachment。
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin implements IBoneBlockChunkAccess {

    @Unique
    private LongOpenHashSet unsuspiciousblock_naturalBoneBlocks = null;

    @Override
    public LongOpenHashSet unsuspiciousblock_getOrCreateNaturalBoneBlocks() {
        if (this.unsuspiciousblock_naturalBoneBlocks == null) {
            this.unsuspiciousblock_naturalBoneBlocks = new LongOpenHashSet();
        }
        return this.unsuspiciousblock_naturalBoneBlocks;
    }

    @Override
    public LongOpenHashSet unsuspiciousblock_getNaturalBoneBlocks() {
        return this.unsuspiciousblock_naturalBoneBlocks;
    }

    @Override
    public void unsuspiciousblock_setNaturalBoneBlocks(LongOpenHashSet set) {
        this.unsuspiciousblock_naturalBoneBlocks = set;
    }
}
