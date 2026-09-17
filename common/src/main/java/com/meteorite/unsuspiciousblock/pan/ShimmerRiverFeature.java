package com.meteorite.unsuspiciousblock.pan;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/*** 在河流新区块中写入淘洗点 NBT，由原版在区块完成后于主线程加载实体。 */
public final class ShimmerRiverFeature extends Feature<NoneFeatureConfiguration> {
    public ShimmerRiverFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        ChunkPos chunkPos = new ChunkPos(context.origin());
        // 已完成的区块不接收生成期 NBT，避免重放或手动放置时静默丢失实体。
        if (!(level.getChunk(chunkPos.x, chunkPos.z) instanceof ProtoChunk proto)
                || proto instanceof ImposterProtoChunk) {
            return false;
        }
        BlockPos anchor = ShimmerPlacement.wanderForSurface(level, context.random(), chunkPos,
                context.chunkGenerator().getSeaLevel(), pos -> true);
        if (anchor == null) {
            return false;
        }
        proto.addEntity(ShimmerEntity.createWorldgenTag(anchor));
        return true;
    }
}
