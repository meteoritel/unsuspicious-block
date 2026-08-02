package com.meteorite.unsuspiciousblock.block;

import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 方块注册回调，由各平台提供具体注册方式。
 */
@FunctionalInterface
public interface BlockRegistrar {

    // 注册单个方块并回写跨平台引用
    void register(String name, Supplier<Block> factory, Consumer<Supplier<Block>> setter);
}
