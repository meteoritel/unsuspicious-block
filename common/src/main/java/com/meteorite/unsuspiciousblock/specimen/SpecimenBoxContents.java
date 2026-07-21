package com.meteorite.unsuspiciousblock.specimen;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;

/**
 * 各平台饰品适配层共用的标本箱内容读写工具。
 */
public final class SpecimenBoxContents {

    private SpecimenBoxContents() {
    }

    // 读取可安全修改的箱内物品列表。
    public static NonNullList<ItemStack> read(ItemStack boxStack) {
        NonNullList<ItemStack> items = NonNullList.withSize(
                SpecimenBoxMenu.CONTAINER_SIZE, ItemStack.EMPTY);
        boxStack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
        return items;
    }

    // 创建用于变更比较的完整副本。
    public static NonNullList<ItemStack> copy(List<ItemStack> items) {
        NonNullList<ItemStack> result = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int index = 0; index < items.size(); index++) {
            result.set(index, items.get(index).copy());
        }
        return result;
    }

    // 仅在内容实际改变时更新组件，避免每次饰品查询都触发同步。
    public static void writeIfChanged(ItemStack boxStack, List<ItemStack> previous,
                                      NonNullList<ItemStack> current) {
        if (!sameItems(previous, current)) {
            boxStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(current));
        }
    }

    private static boolean sameItems(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!ItemStack.matches(first.get(index), second.get(index))) {
                return false;
            }
        }
        return true;
    }
}
