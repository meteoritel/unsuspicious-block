package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.block.ModBlocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 模组方块实体类型清单，由各平台分别完成注册。
 */
public final class ModBlockEntities {
    public static Supplier<BlockEntityType<UnsuspiciousBlockEntity>> UNSUSPICIOUS_BLOCK;
    public static Supplier<BlockEntityType<PotteryWheelBlockEntity>> POTTERY_WHEEL;

    private ModBlockEntities() {
    }

    /** 方块实体类型注册清单条目。 */
    public record BlockEntityEntry<T extends BlockEntity>(
            String name,
            Supplier<BlockEntityType<T>> factory,
            Consumer<Supplier<BlockEntityType<T>>> setter) {
    }

    public static final List<BlockEntityEntry<?>> REGISTRY_MANIFEST = List.of(
            new BlockEntityEntry<>("unsuspicious_block",
                    ModBlockEntities::createUnsuspiciousBlockType,
                    type -> UNSUSPICIOUS_BLOCK = type),
            new BlockEntityEntry<>("pottery_wheel",
                    ModBlockEntities::createPotteryWheelType,
                    type -> POTTERY_WHEEL = type)
    );

    // 创建两种不可疑方块共用的方块实体类型
    public static BlockEntityType<UnsuspiciousBlockEntity> createUnsuspiciousBlockType() {
        return BlockEntityType.Builder.of(
                UnsuspiciousBlockEntity::new,
                ModBlocks.UNSUSPICIOUS_SAND.get(),
                ModBlocks.UNSUSPICIOUS_GRAVEL.get()
        ).build(null);
    }

    // 创建纹饰陶轮台方块实体类型
    public static BlockEntityType<PotteryWheelBlockEntity> createPotteryWheelType() {
        return BlockEntityType.Builder.of(PotteryWheelBlockEntity::new, ModBlocks.POTTERY_WHEEL.get()).build(null);
    }

    // 遍历清单并交由平台完成注册
    public static void forEach(BlockEntityRegistrar registrar) {
        for (BlockEntityEntry<?> entry : REGISTRY_MANIFEST) {
            register(entry, registrar);
        }
    }

    // 捕获条目的泛型参数，避免平台侧强制转换
    private static <T extends BlockEntity> void register(BlockEntityEntry<T> entry,
                                                         BlockEntityRegistrar registrar) {
        registrar.register(entry.name(), entry.factory(), entry.setter());
    }
}
