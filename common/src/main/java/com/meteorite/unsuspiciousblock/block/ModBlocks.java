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
                    block -> UNSUSPICIOUS_GRAVEL = block)
    );

    // 创建不可疑的沙子，基础属性与原版可疑的沙子保持一致
    public static Block createUnsuspiciousSand() {
        return new UnsuspiciousBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.SUSPICIOUS_SAND));
    }

    // 创建不可疑的沙砾，基础属性与原版可疑的沙砾保持一致
    public static Block createUnsuspiciousGravel() {
        return new UnsuspiciousBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.SUSPICIOUS_GRAVEL));
    }

    // 遍历清单并交由平台完成注册
    public static void forEach(BlockRegistrar registrar) {
        for (BlockEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.name(), entry.factory(), entry.setter());
        }
    }
}
