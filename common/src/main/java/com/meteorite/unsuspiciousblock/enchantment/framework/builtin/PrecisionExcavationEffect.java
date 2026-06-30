package com.meteorite.unsuspiciousblock.enchantment.framework.builtin;

import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EffectContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentValueEffect;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 精掘附魔效果：刷拭可疑方块时概率翻倍战利品。
 * <p>
 * 实现 {@link EnchantmentValueEffect}&lt;{@link List}&lt;{@link ItemStack}&gt;&gt;，
 * 由 {@link com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager#dispatchValue}
 * 调用。命中概率时在原列表基础上追加每个物品的副本，生成两个独立的 ItemStack，
 * 以兼容无法叠加的物品。未命中条件时原样返回 ctx.value()。
 * <p>
 * 触发概率公式：{@code (BASE_CHANCE + STEP_PER_LEVEL * level) / 100}，
 * 即 1 级 28%、2 级 44%、3 级 60%。
 */
public final class PrecisionExcavationEffect implements EnchantmentValueEffect<List<ItemStack>> {
    // 基础概率（百分比）与每级增量（百分比），由公式计算各等级概率，避免硬编码数组
    private static final double BASE_CHANCE = 12.0D;
    private static final double STEP_PER_LEVEL = 16.0D;

    @Override
    public List<ItemStack> apply(EffectContext<List<ItemStack>> ctx) {
        List<ItemStack> original = ctx.value();
        if (original.isEmpty()) {
            return original;
        }

        int level = ctx.enchantmentLevel();
        if (level < 1) {
            return original;
        }

        double chance = (BASE_CHANCE + STEP_PER_LEVEL * level) / 100.0D;
        if (ctx.triggerContext().player.getRandom().nextDouble() >= chance) {
            return original;
        }

        // 为列表中每个物品追加一个副本，以兼容无法叠加的物品
        List<ItemStack> doubled = new ArrayList<>(original.size() * 2);
        for (ItemStack stack : original) {
            doubled.add(stack);
            doubled.add(stack.copy());
        }
        return doubled;
    }
}
