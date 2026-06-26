package com.meteorite.unsuspiciousblock.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 实体注册回调 —— 由各平台实现，描述“如何注册单个实体条目”
 * <p>
 * 采用泛型方法，使类型参数 T 在接口内被捕获，平台侧实现无需任何 unchecked 强制转换。
 * 注意：泛型方法无法用 lambda 实现，平台侧须使用匿名内部类。
 * <p>
 * 注册结果以 {@link Supplier} 形式回写 common 静态字段，避免平台间回写时机差异
 * （NeoForge 的 DeferredHolder 本身即 Supplier，Fabric 端包装为不可变 supplier）。
 */
@FunctionalInterface
public interface EntityRegistrar {

    // 注册单个实体：创建类型、回写 common 静态字段（Supplier 形式）、注册默认属性
    <T extends LivingEntity> void register(
            String name,
            Supplier<EntityType<T>> factory,
            Consumer<Supplier<EntityType<T>>> setter,
            Supplier<AttributeSupplier.Builder> attributes);
}
