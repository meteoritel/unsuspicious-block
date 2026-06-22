package com.meteorite.unsuspiciousblock.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * ChunkAccess 鸭子类型接口——由 Fabric 端 {@code ChunkAccessMixin} 实现，
 * 供其它 mixin（ChunkSerializerMixin / LevelChunkMixin）与平台服务（FabricBoneBlockTracker）
 * 以接口强转方式访问注入字段，避免 mixin 处理器在目标类继承链中解析不到 ChunkAccessMixin 类型。
 * <p>
 * 放在 common 的 world 包中而非 mixin 包，因为 mixin 禁止直接引用 mixin 包中的类。
 * NeoForge 端不使用此接口（使用 ChunkAttachment），但接口本身无平台依赖。
 */
public interface IBoneBlockChunkAccess {

    LongOpenHashSet unsuspiciousblock_getOrCreateNaturalBoneBlocks();

    LongOpenHashSet unsuspiciousblock_getNaturalBoneBlocks();

    void unsuspiciousblock_setNaturalBoneBlocks(LongOpenHashSet set);
}
