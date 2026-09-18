package com.meteorite.unsuspiciousblock.pan;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

/***
 * 淘洗点地物——在新区块中写入淘洗点 NBT，由原版在区块完成后于主线程加载实体。
 * <p>
 * 介质、落点规则与实体类型全部由配置携带的变体决定，因此河流与下界岩浆海共用同一个地物类。
 */
public final class ShimmerFeature extends Feature<ShimmerFeatureConfig> {
    public ShimmerFeature() {
        super(ShimmerFeatureConfig.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<ShimmerFeatureConfig> context) {
        WorldGenLevel level = context.level();
        ChunkPos chunkPos = new ChunkPos(context.origin());
        // 已完成的区块不接收生成期 NBT，避免重放或手动放置时静默丢失实体。
        if (!(level.getChunk(chunkPos.x, chunkPos.z) instanceof ProtoChunk proto)
                || proto instanceof ImposterProtoChunk) {
            return false;
        }
        ShimmerVariant variant = context.config().variant();
        int seaLevel = variant.spawnDomain().surfaceSearchCenter(context.chunkGenerator());
        BlockPos anchor = ShimmerPlacement.wanderForSurface(level, context.random(), chunkPos,
                variant, seaLevel, pos -> true);
        if (anchor == null) {
            return false;
        }
        proto.addEntity(ShimmerEntity.createWorldgenTag(anchor, variant));
        return true;
    }
}
