package com.meteorite.unsuspiciousblock.specimen;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 标本箱 tooltip 预览数据，固定保存 5 个槽位的物品快照。
 */
public record SpecimenBoxTooltip(List<ItemStack> items) implements TooltipComponent {
    public SpecimenBoxTooltip {
        items = items.stream().map(ItemStack::copy).toList();
    }
}
