package com.meteorite.unsuspiciousblock.item;

import net.minecraft.world.item.Item;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 物品注册回调 —— 由各平台实现，描述“如何注册单个物品条目”
 * <p>
 * 物品无类型参数，可直接用 lambda 实现。
 */
@FunctionalInterface
public interface ItemRegistrar {

    // 注册单个物品：创建实例、回写 common 静态字段
    void register(String name, Supplier<Item> factory, Consumer<Item> setter);
}
