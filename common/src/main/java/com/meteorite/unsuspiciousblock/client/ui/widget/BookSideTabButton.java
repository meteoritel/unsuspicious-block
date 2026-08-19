package com.meteorite.unsuspiciousblock.client.ui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 附着在书本左侧、直接使用完整纹理绘制的标签按钮。
 */
public final class BookSideTabButton extends AbstractButton {
    private static final int HOVER_OVERLAY = 0x20FFFFFF;

    private final ResourceLocation texture;
    private final List<Component> tooltipLines;
    private final Runnable onPressed;

    public BookSideTabButton(int x, int y, int width, int height, ResourceLocation texture,
                             List<Component> tooltipLines, Runnable onPressed) {
        super(x, y, width, height, firstTooltipLine(tooltipLines));
        this.texture = texture;
        this.tooltipLines = List.copyOf(tooltipLines);
        this.onPressed = onPressed;
    }

    public BookSideTabButton(int x, int y, int width, int height, ResourceLocation texture,
                             Component tooltip, Runnable onPressed) {
        this(x, y, width, height, texture, List.of(tooltip), onPressed);
    }

    @Override
    public void onPress() {
        this.onPressed.run();
    }

    @Override
    protected void updateWidgetNarration(@NotNull NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    @Override
    protected void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blit(this.texture, this.getX(), this.getY(),
                0, 0, this.width, this.height, this.width, this.height);
        if (this.active && this.isHoveredOrFocused()) {
            graphics.fill(this.getX() + 2, this.getY() + 2,
                    this.getX() + this.width - 1, this.getY() + this.height - 2, HOVER_OVERLAY);
        }
    }

    // 由 Screen.render 在所有组件之后调用，避免提示被后续组件覆盖。
    public void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!this.tooltipLines.isEmpty() && this.isHovered()) {
            Font font = Minecraft.getInstance().font;
            graphics.renderTooltip(font,
                    this.tooltipLines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }

    private static Component firstTooltipLine(List<Component> tooltipLines) {
        return tooltipLines.isEmpty() ? Component.empty() : tooltipLines.getFirst();
    }
}
