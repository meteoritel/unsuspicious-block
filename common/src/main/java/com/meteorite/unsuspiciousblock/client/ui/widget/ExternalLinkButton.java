package com.meteorite.unsuspiciousblock.client.ui.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 使用品牌纹理并通过原版确认界面打开网页的紧凑按钮。
 */
public final class ExternalLinkButton extends AbstractButton {
    private static final int ICON_SIZE = 14;
    private static final int ICON_TEXTURE_SIZE = 64;
    private static final int HOVER_BACKGROUND = 0x40A08060;
    private static final int HOVER_BORDER = 0x908B6914;

    private final Screen parent;
    private final ResourceLocation icon;
    private final String url;

    public ExternalLinkButton(int x, int y, int size, Screen parent, ResourceLocation icon,
                              String url, Component description) {
        super(x, y, size, size, description);
        this.parent = parent;
        this.icon = icon;
        this.url = url;
        this.setTooltip(Tooltip.create(description));
    }

    @Override
    public void onPress() {
        ConfirmLinkScreen.confirmLinkNow(this.parent, this.url, true);
    }

    @Override
    protected void updateWidgetNarration(@NotNull NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    @Override
    protected void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.isHoveredOrFocused()) {
            graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height,
                    HOVER_BORDER);
            graphics.fill(this.getX() + 1, this.getY() + 1,
                    this.getX() + this.width - 1, this.getY() + this.height - 1, HOVER_BACKGROUND);
        }

        int iconX = this.getX() + (this.width - ICON_SIZE) / 2;
        int iconY = this.getY() + (this.height - ICON_SIZE) / 2;
        graphics.blit(this.icon, iconX, iconY, ICON_SIZE, ICON_SIZE,
                0, 0, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
    }
}
