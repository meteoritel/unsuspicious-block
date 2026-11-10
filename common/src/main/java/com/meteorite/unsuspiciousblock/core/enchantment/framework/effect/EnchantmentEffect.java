package com.meteorite.unsuspiciousblock.core.enchantment.framework.effect;

/** 附魔副作用效果接口——实现此接口注册到
 * {@link com.meteorite.unsuspiciousblock.core.enchantment.framework.EnchantmentManager}
 * */
@FunctionalInterface
public interface EnchantmentEffect {
    // 执行附魔作用，value 为 null
    void apply(EffectContext<?> ctx);
}
