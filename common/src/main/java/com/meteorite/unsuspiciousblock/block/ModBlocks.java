package com.meteorite.unsuspiciousblock.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 模组方块清单，方块实例由各平台分别注册。
 */
public final class ModBlocks {
    public static Supplier<Block> UNSUSPICIOUS_SAND;
    public static Supplier<Block> UNSUSPICIOUS_GRAVEL;
    public static Supplier<Block> POTTERY_WHEEL;
    public static Supplier<Block> UNFIRED_DECORATED_POT;

    private ModBlocks() {
    }

    /** 方块注册清单条目。 */
    public record BlockEntry(String name, Supplier<Block> factory, Consumer<Supplier<Block>> setter) {
    }

    public static final List<BlockEntry> REGISTRY_MANIFEST = List.of(
            new BlockEntry("unsuspicious_sand",
                    ModBlocks::createUnsuspiciousSand,
                    block -> UNSUSPICIOUS_SAND = block),
            new BlockEntry("unsuspicious_gravel",
                    ModBlocks::createUnsuspiciousGravel,
                    block -> UNSUSPICIOUS_GRAVEL = block),
            new BlockEntry("pottery_wheel",
                    ModBlocks::createPotteryWheel,
                    block -> POTTERY_WHEEL = block),
            new BlockEntry("unfired_decorated_pot",
                    ModBlocks::createUnfiredDecoratedPot,
                    block -> UNFIRED_DECORATED_POT = block)
    );

    // 创建不可疑的沙子，基础属性与原版可疑的沙子保持一致
    public static Block createUnsuspiciousSand() {
        return new UnsuspiciousBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.SUSPICIOUS_SAND));
    }

    // 创建不可疑的沙砾，基础属性与原版可疑的沙砾保持一致
    public static Block createUnsuspiciousGravel() {
        return new UnsuspiciousBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.SUSPICIOUS_GRAVEL));
    }

    // 创建纹饰陶轮台
    public static Block createPotteryWheel() {
        return new PotteryWheelBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS)
                .strength(2.5F).noOcclusion());
    }

    // 创建未烧制的纹饰陶罐，占位模型阶段沿用黏土材质属性
    public static Block createUnfiredDecoratedPot() {
        return new UnfiredDecoratedPotBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.CLAY)
                .strength(0.6F));
    }

    // 遍历清单并交由平台完成注册
    public static void forEach(BlockRegistrar registrar) {
        for (BlockEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.name(), entry.factory(), entry.setter());
        }
    }
}
