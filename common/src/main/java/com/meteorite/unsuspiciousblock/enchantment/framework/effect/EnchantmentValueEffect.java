package com.meteorite.unsuspiciousblock.enchantment.framework.effect;

/** 附魔值变换效果接口——接收原值与附魔上下文，返回变换后的值（如替换钓鱼战利品表、翻倍刷子物品） */
@FunctionalInterface
public interface EnchantmentValueEffect<T> {
    // 对原值执行变换并返回新值；未命中附魔或概率未触发时应原样返回 originalValue
    T apply(ValueEffectContext<T> ctx);
}
