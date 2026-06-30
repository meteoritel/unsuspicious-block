package com.meteorite.unsuspiciousblock.enchantment.framework.builtin;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EffectContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentValueEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * 泥地打捞 附魔效果：钓鱼收杆时概率将原版战利品表替换为自定义沼泽掉落表。
 * <p>
 * 实现 {@link EnchantmentValueEffect}&lt;{@link ResourceKey}&lt;{@link LootTable}&gt;&gt;，
 * 由 {@link com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager#dispatchValue}
 * 调用；未命中条件时原样返回 ctx.value()。
 * <p>
 * 概率模型：每级 10%（满级 3 级 = 30%）；处于注册名 path 包含 "swamp" 的群系时额外 +15%。
 * <p>
 * 战利品表分流：沼泽群系命中概率检定时使用 {@link #MUD_DREDGING_SWAMP_LOOT_TABLE}（更丰厚），
 * 其余命中场景使用 {@link #MUD_DREDGING_LOOT_TABLE}（基础表）。
 */
public final class MudDredgingEffect implements EnchantmentValueEffect<ResourceKey<LootTable>> {
    public static final ResourceKey<LootTable> MUD_DREDGING_LOOT_TABLE =
            ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging"));

    // 沼泽群系专属（更丰厚）战利品表
    public static final ResourceKey<LootTable> MUD_DREDGING_SWAMP_LOOT_TABLE =
            ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/fishing/mud_dredging_swamp"));

    private static final double CHANCE_PER_LEVEL = 0.10D;
    private static final double BONUS_CHANCE = 0.15D;
    private static final String SWAMP_BIOME_PATH_MARKER = "swamp";

    @Override
    public ResourceKey<LootTable> apply(EffectContext<ResourceKey<LootTable>> ctx) {
        var triggerCtx = ctx.triggerContext();
        // 通过 triggerContext 中的 targetEntity 获取 FishingHook
        if (!(triggerCtx.targetEntity instanceof FishingHook hook) || !hook.isOpenWaterFishing()) {
            return ctx.value();
        }

        boolean swamp = isSwampBiome(triggerCtx, hook);
        double chance = ctx.enchantmentLevel() * CHANCE_PER_LEVEL;
        if (swamp) {
            chance += BONUS_CHANCE;
        }

        if (triggerCtx.player.getRandom().nextDouble() >= Math.min(1.0D, chance)) {
            return ctx.value();
        }
        // 命中后按群系分流：沼泽用更丰厚的专属表，其余用基础表
        return swamp ? MUD_DREDGING_SWAMP_LOOT_TABLE : MUD_DREDGING_LOOT_TABLE;
    }

    // 检查当前群系注册名 path 是否包含 "swamp"，兼容原版与 mod 添加的沼泽群系
    private boolean isSwampBiome(TriggerContext triggerCtx, FishingHook hook) {
        return triggerCtx.level.getBiome(hook.blockPosition())
                .unwrapKey()
                .map(key -> key.location().getPath().contains(SWAMP_BIOME_PATH_MARKER))
                .orElse(false);
    }

}
