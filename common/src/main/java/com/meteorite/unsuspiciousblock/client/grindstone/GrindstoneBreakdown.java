package com.meteorite.unsuspiciousblock.client.grindstone;

import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.List;

/**
 * 砂轮操作分解数据。
 * 由 {@link GrindstoneBreakdownCalculator} 在客户端从 {@link net.minecraft.world.inventory.GrindstoneMenu}
 * 当前槽位状态分析得出，供 {@link GrindstoneBreakdownTooltipBuilder} 渲染到 tooltip。
 *
 * <p>与铁砧 {@link com.meteorite.unsuspiciousblock.client.anvil.AnvilBreakdown} 不同，
 * 砂轮结果槽由原版客户端 {@code createResult} 直接计算完成，本 record 仅做差异分析，
 * 无需复刻算法。</p>
 *
 * <p>操作类型：
 * <ul>
 *   <li>{@link OperationType#DISENCHANT} — 单槽去魔：移除所有非诅咒附魔</li>
 *   <li>{@link OperationType#MERGE_REPAIR} — 双槽合并：合并耐久 + 移除非诅咒附魔 + 诅咒保留</li>
 * </ul>
 * </p>
 */
public record GrindstoneBreakdown(
        OperationType operationType,
        List<EnchantEntry> removed,
        List<EnchantEntry> kept,
        int expMin,
        int expMax,
        int oldRepairCost,
        int newRepairCost,
        boolean hasDurabilityChange,
        int oldDurability,
        int newDurability,
        boolean bookTransformed
) {

    /** 操作类型。 */
    public enum OperationType {
        /** 单槽去魔。 */
        DISENCHANT,
        /** 双槽合并修复。 */
        MERGE_REPAIR
    }

    /** 单条附魔记录：holder + 等级。 */
    public record EnchantEntry(
            Holder<Enchantment> enchant,
            int level
    ) {}
}
