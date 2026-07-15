package com.meteorite.unsuspiciousblock.enchantment.framework;

import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import com.meteorite.unsuspiciousblock.enchantment.framework.builtin.FossilHunterEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.builtin.PrecisionExcavationEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.builtin.TextileRecoveryEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;

/**
 * 附魔效果集中注册入口——在 common init 阶段调用，将全部附魔效果组件注册到 {@link EnchantmentManager}。
 */
public final class EnchantmentEffects {

    private EnchantmentEffects() {
    }

    // 注册全部附魔效果到 EnchantmentManager
    public static void registerAll() {
        // fossil_hunter：方块破坏（副作用）
        EnchantmentManager.register(TriggerType.BLOCK_BREAK, ModEnchantments.FOSSIL_HUNTER,
                new FossilHunterEffect());

        // textile_recovery：剪羊毛（副作用）
        EnchantmentManager.register(TriggerType.ENTITY_SHEAR, ModEnchantments.TEXTILE_RECOVERY,
                new TextileRecoveryEffect());

        // precision_excavation：刷子翻倍（值变换）
        EnchantmentManager.registerValueEffect(TriggerType.BRUSH_ITEM_DROP,
                ModEnchantments.PRECISION_EXCAVATION, new PrecisionExcavationEffect());
    }
}
