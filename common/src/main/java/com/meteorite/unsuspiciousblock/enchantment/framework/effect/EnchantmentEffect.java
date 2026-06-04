package com.meteorite.unsuspiciousblock.enchantment.framework.effect;

/** 附魔效果组件接口——每个附魔效果实现此接口，注册到 {@link com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager} */
@FunctionalInterface
public interface EnchantmentEffect {
    /** 执行附魔效果 */
    void apply(EffectContext ctx);
}
