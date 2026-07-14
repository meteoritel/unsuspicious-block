package com.meteorite.unsuspiciousblock.loottable.injection;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 考古战利品注入器——按概率将模组物品替换进指定考古战利品表的生成结果。
 * <p>
 * 不同平台通过 {@link ArchaeologyLootInjectors#register} 注册各自的实现：
 * <ul>
 *   <li>Fabric 端在 {@code BrushableBlockEntityMixin} 与概率模拟器中显式调用注入器</li>
 *   <li>NeoForge 端通过 GlobalLootModifier 实现等价语义，不注册注入器</li>
 * </ul>
 * 考古战利品表只允许产出 1 个物品，触发时需清空原版结果并放入模组物品（替换语义），
 * 避免被 {@link net.minecraft.world.level.block.entity.BrushableBlockEntity} 丢弃多余物品。
 */
public interface ArchaeologyLootInjector {

    /**
     * 按平台特定概率尝试将模组物品替换进指定表的战利品列表。
     *
     * @param tableId 战利品表 id（来自调用方上下文，非 LootTable 内部字段）
     * @param drops   战利品列表（可变，触发时清空并放入模组物品）
     * @param random  随机源（由调用方提供，确保概率分布合理）
     */
    void maybeReplace(ResourceLocation tableId, List<ItemStack> drops, RandomSource random);
}
