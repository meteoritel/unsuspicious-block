package com.meteorite.unsuspiciousblock.client.ui.widget;

import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 考古笔记专用翻页按钮，使用原版 PageButton 精灵贴图。
 */
public final class JournalPageButton extends PageButton {
    private static final ResourceLocation PAGE_FORWARD_HIGHLIGHTED_SPRITE = ResourceLocation.withDefaultNamespace("widget/page_forward_highlighted");
    private static final ResourceLocation PAGE_FORWARD_SPRITE = ResourceLocation.withDefaultNamespace("widget/page_forward");
    private static final ResourceLocation PAGE_BACKWARD_HIGHLIGHTED_SPRITE = ResourceLocation.withDefaultNamespace("widget/page_backward_highlighted");
    private static final ResourceLocation PAGE_BACKWARD_SPRITE = ResourceLocation.withDefaultNamespace("widget/page_backward");

    private final boolean isForward;

    public JournalPageButton(int x, int y, boolean isForward, Button.OnPress onPress) {
        super(x, y, isForward, onPress, true);
        this.isForward = isForward;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        ResourceLocation sprite;
        if (this.isForward) {
            sprite = this.active && this.isHovered() ? PAGE_FORWARD_HIGHLIGHTED_SPRITE : PAGE_FORWARD_SPRITE;
        } else {
            sprite = this.active && this.isHovered() ? PAGE_BACKWARD_HIGHLIGHTED_SPRITE : PAGE_BACKWARD_SPRITE;
        }
        guiGraphics.blitSprite(sprite, this.getX(), this.getY(), JournalLayout.PAGE_BUTTON_WIDTH, JournalLayout.PAGE_BUTTON_HEIGHT);
    }
}