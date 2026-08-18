package com.meteorite.unsuspiciousblock.client.state;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 可选物品查看器向考古笔记快捷键提供当前鼠标悬停物品的客户端接口。
 * 返回空物品栈表示输入已被查看器占用，不应继续回退到原版容器槽位。
 */
@FunctionalInterface
public interface JournalHoveredItemProvider {

    Optional<ItemStack> findHoveredItem(Screen screen, double mouseX, double mouseY);
}
