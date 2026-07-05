package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

/**
 * 世界上下文解析器。
 * 将追踪容器/方块位置解析为日志条目所需的生物群系、结构、源方块等上下文信息，
 * 与追踪业务逻辑解耦，便于其他场景（如日志 UI、条目编辑）复用。
 */
public final class WorldContextResolver {
    private WorldContextResolver() {
    }

    // 解析追踪容器对应的方块坐标，非方块容器回退到 ZERO
    public static BlockPos resolveContainerPos(TrackedContainerLootState container) {
        if (container instanceof BlockEntity blockEntity) {
            return blockEntity.getBlockPos();
        }
        return BlockPos.ZERO;
    }

    @Nullable
    // 解析追踪容器对应的源方块 id，非方块容器返回 null
    public static ResourceLocation resolveContainerSourceBlockId(TrackedContainerLootState container) {
        if (container instanceof BlockEntity blockEntity) {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        }
        return null;
    }

    // 解析指定坐标处的生物群系 id，无法解析时回退到 plains
    public static ResourceLocation resolveBiomeId(ServerLevel level, BlockPos pos) {
        Holder<Biome> biomeHolder = level.getBiome(pos);
        return biomeHolder.unwrapKey()
                .map(ResourceKey::location)
                .orElse(ResourceLocation.withDefaultNamespace("plains"));
    }

    @Nullable
    // 解析指定坐标处所属结构 id，多个匹配时取字典序最小者，无匹配返回 null
    public static ResourceLocation resolveStructureId(ServerLevel level, BlockPos pos) {
        StructureManager structureManager = level.structureManager();
        // 一步获取该 chunk 内所有已解析的结构开端
        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ResourceLocation bestMatch = null;

        for (StructureStart start : structureManager.startsForStructure(new ChunkPos(pos), structure -> true)) {
            if (!start.isValid() || !start.getBoundingBox().isInside(pos)) {
                continue;
            }
            ResourceLocation id = structureRegistry.getKey(start.getStructure());
            if (id == null) {
                continue;
            }
            if (bestMatch == null || id.toString().compareTo(bestMatch.toString()) < 0) {
                bestMatch = id;
            }
        }
        return bestMatch;
    }
}
