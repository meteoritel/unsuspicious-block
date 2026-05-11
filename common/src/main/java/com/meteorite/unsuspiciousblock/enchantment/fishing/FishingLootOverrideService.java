package com.meteorite.unsuspiciousblock.enchantment.fishing;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.List;
import java.util.Set;

/** 钓鱼战利品切换判定服务 */
public final class FishingLootOverrideService {
    public static final ResourceKey<LootTable> MUD_DREDGING_LOOT_TABLE = lootTableKey("gameplay/fishing/mud_dredging");

    private static final List<FishingLootOverrideDefinition> DEFINITIONS = List.of(
            new FishingLootOverrideDefinition(
                    ModEnchantments.MUD_DREDGING,
                    MUD_DREDGING_LOOT_TABLE,
                    0.10D,
                    0.10D,
                    Set.of(Biomes.SWAMP, Biomes.MANGROVE_SWAMP)
            )
    );

    private FishingLootOverrideService() {
    }

    public static ResourceKey<LootTable> resolveLootTable(ServerLevel level, FishingHook hook,
                                                           ItemStack fishingRod, ResourceKey<LootTable> originalLootTable) {
        for (FishingLootOverrideDefinition definition : DEFINITIONS) {
            if (shouldOverride(level, hook, fishingRod, definition)) {
                return definition.lootTableKey();
            }
        }
        return originalLootTable;
    }

    private static boolean shouldOverride(ServerLevel level, FishingHook hook, ItemStack fishingRod,
                                          FishingLootOverrideDefinition definition) {
        int enchantmentLevel = level.registryAccess().lookup(Registries.ENCHANTMENT)
                .flatMap(registry -> registry.get(definition.enchantmentKey()))
                .map(enchantment -> EnchantmentHelper.getItemEnchantmentLevel(enchantment, fishingRod))
                .orElse(0);
        if (enchantmentLevel <= 0 || !hook.isOpenWaterFishing()) {
            return false;
        }

        double chance = enchantmentLevel * definition.chancePerLevel();
        if (isBonusBiome(level, hook, definition.bonusBiomes())) {
            chance += definition.bonusChance();
        }
        return hook.getRandom().nextDouble() < Math.min(1.0D, chance);
    }

    private static boolean isBonusBiome(ServerLevel level, FishingHook hook, Set<ResourceKey<Biome>> bonusBiomes) {
        var biome = level.getBiome(hook.blockPosition());
        for (ResourceKey<Biome> bonusBiome : bonusBiomes) {
            if (biome.is(bonusBiome)) {
                return true;
            }
        }
        return false;
    }

    private static ResourceKey<LootTable> lootTableKey(String path) {
        return ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path));
    }

    private record FishingLootOverrideDefinition(
            ResourceKey<Enchantment> enchantmentKey,
            ResourceKey<LootTable> lootTableKey,
            double chancePerLevel,
            double bonusChance,
            Set<ResourceKey<Biome>> bonusBiomes
    ) {
    }
}
