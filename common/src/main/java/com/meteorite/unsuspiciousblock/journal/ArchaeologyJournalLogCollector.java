package com.meteorite.unsuspiciousblock.journal;

import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

public final class ArchaeologyJournalLogCollector {
    private ArchaeologyJournalLogCollector() {
    }

    public static void recordFirstUnlock(ServerPlayer player, ResourceLocation tableId, long gameTime, long dayTime) {
        ArchaeologyJournalNetwork.recordFirstUnlock(player, tableId, gameTime, dayTime);
    }

    public static void recordExcavation(ServerPlayer player, ResourceLocation tableId, ResourceLocation itemId,
                                        BlockPos pos, long gameTime, long dayTime) {
        ServerLevel level = player.serverLevel();
        ResourceLocation biomeId = resolveBiomeId(level, pos);
        ResourceLocation structureId = resolveStructureId(level, pos);
        Map<String, Integer> loot = Map.of(LootResultSignature.plain(itemId).toStoredKey(), 1);
        ArchaeologyJournalNetwork.recordExcavation(player, tableId,
                new ExcavationLogEntry(UUID.randomUUID(), null, null, structureId, biomeId, pos,
                        Math.max(0L, gameTime), Math.max(0L, dayTime),
                        Math.max(0L, gameTime), Math.max(0L, dayTime), loot, loot));
    }

    private static ResourceLocation resolveBiomeId(ServerLevel level, BlockPos pos) {
        Holder<Biome> biomeHolder = level.getBiome(pos);
        return biomeHolder.unwrapKey()
                .map(ResourceKey::location)
                .orElse(ResourceLocation.withDefaultNamespace("plains"));
    }

    @Nullable
    private static ResourceLocation resolveStructureId(ServerLevel level, BlockPos pos) {
        StructureManager structureManager = level.structureManager();
        Map<Structure, LongSet> structureReferences = structureManager.getAllStructuresAt(pos);
        if (structureReferences.isEmpty()) {
            return null;
        }
        ResourceLocation bestMatch = null;
        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Structure structure : structureReferences.keySet()) {
            StructureStart structureStart = structureManager.getStructureAt(pos, structure);
            if (!structureStart.isValid()) {
                continue;
            }
            ResourceLocation structureId = structureRegistry.getKey(structure);
            if (structureId == null) {
                continue;
            }
            if (bestMatch == null || structureId.toString().compareTo(bestMatch.toString()) < 0) {
                bestMatch = structureId;
            }
        }
        return bestMatch;
    }
}
