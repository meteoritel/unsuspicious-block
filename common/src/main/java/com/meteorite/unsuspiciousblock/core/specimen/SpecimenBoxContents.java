package com.meteorite.unsuspiciousblock.core.specimen;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;
import java.util.function.Function;

/** 标本箱内容的读取和提交。 */
public final class SpecimenBoxContents {
    private SpecimenBoxContents() {
    }

    /** 返回可独立编辑的槽位副本。 */
    public static NonNullList<ItemStack> read(ItemStack boxStack) {
        NonNullList<ItemStack> items = NonNullList.withSize(SpecimenBoxMenu.CONTAINER_SIZE, ItemStack.EMPTY);
        contents(boxStack).copyInto(items);
        return items;
    }

    /** 读取当前内容，执行编辑并提交，返回编辑结果。编辑限于当前服务端调用。 */
    public static <T> T edit(ItemStack boxStack, Function<NonNullList<ItemStack>, T> edit) {
        NonNullList<ItemStack> items = read(boxStack);
        T result = edit.apply(items);
        commit(boxStack, items);
        return result;
    }

    static ItemContainerContents contents(ItemStack boxStack) {
        return boxStack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
    }

    static ItemContainerContents commit(ItemStack boxStack, List<ItemStack> items) {
        ItemContainerContents current = contents(boxStack);
        ItemContainerContents updated = ItemContainerContents.fromItems(items);
        if (current.equals(updated)) {
            return current;
        }
        boxStack.set(DataComponents.CONTAINER, updated);
        return updated;
    }
}
