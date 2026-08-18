package com.meteorite.unsuspiciousblock.plugin.jei;

import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 将考古笔记中的物品条目暴露为 JEI 可点击原料，使 U/R 查询复用 JEI 原生交互。
 */
public final class JournalJeiGuiHandler implements IGlobalGuiHandler {
    private final IIngredientManager ingredientManager;

    public JournalJeiGuiHandler(IIngredientManager ingredientManager) {
        this.ingredientManager = ingredientManager;
    }

    @Override
    public @NotNull Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(double mouseX, double mouseY) {
        Screen screen = Minecraft.getInstance().screen;
        if (!(screen instanceof ArchaeologyJournalScreen journalScreen)) {
            return Optional.empty();
        }
        ItemStack stack = journalScreen.getHoveredItemStack(mouseX, mouseY).orElse(ItemStack.EMPTY);
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        Rect2i mouseArea = new Rect2i((int) mouseX, (int) mouseY, 1, 1);
        return this.ingredientManager
                .createClickableIngredient(VanillaTypes.ITEM_STACK, stack, mouseArea, true)
                .map(clickable -> (IClickableIngredient<?>) clickable);
    }
}
