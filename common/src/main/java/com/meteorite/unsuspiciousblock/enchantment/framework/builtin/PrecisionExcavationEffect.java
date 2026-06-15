package com.meteorite.unsuspiciousblock.enchantment.framework.builtin;

import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentValueEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.ValueEffectContext;
import net.minecraft.world.item.ItemStack;

/**
 * 精掘附魔效果：刷拭可疑方块时概率翻倍战利品。
 * <p>
 * 实现 {@link EnchantmentValueEffect}&lt;{@link ItemStack}&gt;&gt;，
 * 由 {@link com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager#dispatchValue}
 * 调用；未命中条件时原样返回 originalValue。
 */
public final class PrecisionExcavationEffect implements EnchantmentValueEffect<ItemStack> {
    private static final double DOUBLE_LOOT_CHANCE = 0.10D;

    @Override
    public ItemStack apply(ValueEffectContext<ItemStack> ctx) {
        ItemStack loot = ctx.originalValue();
        if (loot.isEmpty()) {
            return loot;
        }

        if (ctx.player().getRandom().nextDouble() >= DOUBLE_LOOT_CHANCE) {
            return loot;
        }

        ItemStack doubled = loot.copy();
        int doubledCount = Math.min(doubled.getMaxStackSize(), doubled.getCount() * 2);
        doubled.setCount(doubledCount);
        return doubled;
    }
}
