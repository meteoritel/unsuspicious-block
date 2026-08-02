package com.meteorite.unsuspiciousblock.blockentity;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 方块实体类型注册回调，由各平台提供具体注册方式。
 */
@FunctionalInterface
public interface BlockEntityRegistrar {

    // 注册单个方块实体类型并回写跨平台引用
    <T extends BlockEntity> void register(String name, Supplier<BlockEntityType<T>> factory,
                                          Consumer<Supplier<BlockEntityType<T>>> setter);
}
