package com.meteorite.unsuspiciousblock.effect;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 平台无关的效果注册回调--各平台实现此接口完成实际注册并回写 Holder。
 * 回写类型为 {@code Holder<MobEffect>}，因为 1.21.1 的
 * {@code LivingEntity.hasEffect} / {@code MobEffectInstance} 构造器均需 Holder 入参。
 */
@FunctionalInterface
public interface MobEffectRegistrar {
    /**
     * 注册单个效果。
     *
     * @param name    注册名（命名空间为模组 id）
     * @param factory 效果工厂（每次调用创建新实例）
     * @param setter  回写器，接收注册后的 Holder 供 common 代码引用
     */
    void register(String name, Supplier<MobEffect> factory, Consumer<Holder<MobEffect>> setter);
}
