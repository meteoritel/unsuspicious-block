package com.meteorite.unsuspiciousblock.enchantment.framework.builtin;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentValueEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.ValueEffectContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.Set;

/**
 * 淤泥 dredging 附魔效果：钓鱼收杆时概率将原版战利品表替换为自定义沼泽掉落表。
 * <p>
 * 实现 {@link EnchantmentValueEffect}&lt;{@link ResourceKey}&lt;{@link LootTable}&gt;&gt;，
 * 由 {@link com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager#dispatchValue}
 * 调用；未命中条件时原样返回 originalValue。
 */
public final class MudDredgingEffect implements EnchantmentValueEffect<ResourceKey<LootTable>> {
    public static final ResourceKey<LootTable> MUD_DREDGING_LOOT_TABLE =
            ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging"));

    private static final double CHANCE_PER_LEVEL = 0.10D;
    private static final double BONUS_CHANCE = 0.10D;
    private static final Set<ResourceKey<Biome>> BONUS_BIOMES = Set.of(Biomes.SWAMP, Biomes.MANGROVE_SWAMP);

    @Override
    public ResourceKey<LootTable> apply(ValueEffectContext<ResourceKey<LootTable>> ctx) {
        // 通过 targetEntity 获取 FishingHook
        if (!(ctx.player().fishing instanceof FishingHook hook) || !hook.isOpenWaterFishing()) {
            return ctx.originalValue();
        }

        double chance = ctx.enchantmentLevel() * CHANCE_PER_LEVEL;
        if (isBonusBiome(ctx, hook)) {
            chance += BONUS_CHANCE;
        }

        return ctx.player().getRandom().nextDouble() < Math.min(1.0D, chance)
                ? MUD_DREDGING_LOOT_TABLE
                : ctx.originalValue();
    }

    // 检查是否在奖励群系中钓鱼
    private boolean isBonusBiome(ValueEffectContext<ResourceKey<LootTable>> ctx, FishingHook hook) {
        var biome = ctx.level().getBiome(hook.blockPosition());
        for (ResourceKey<Biome> bonusBiome : BONUS_BIOMES) {
            if (biome.is(bonusBiome)) {
                return true;
            }
        }
        return false;
    }

}
