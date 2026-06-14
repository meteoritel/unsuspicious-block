package com.meteorite.unsuspiciousblock.client.ui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 正方形图标按钮 —— 渲染字体字符图标 + 像素风木质背景，支持多行 tooltip。
 * 用于搜索框收起态放大镜按钮和排序方式切换按钮。
 */
public class IconButton extends AbstractButton {

    // 像素风木质边框颜色
    private static final int BORDER_COLOR = 0xFF8B6914;
    private static final int BORDER_LIGHT = 0xFFB8943C;
    // 背景色：略深于羊皮纸的暗黄色
    private static final int BG_NORMAL = 0xFFD8C0A0;
    private static final int BG_HOVERED = 0xFFC8A878;
    // 文字颜色
    private static final int TEXT_COLOR_NORMAL = 0xFF5A3D23;
    private static final int TEXT_COLOR_HOVERED = 0xFF3D2810;

    private char iconChar;
    private @Nullable List<Component> tooltipLines;
    private final Runnable onPressed;

    /**
     * @param x            按钮左上角 x
     * @param y            按钮左上角 y
     * @param size         按钮宽高（正方形）
     * @param iconChar     图标字符
     * @param tooltipLines 多行悬浮提示，可为 null
     * @param onPressed    点击回调
     */
    public IconButton(int x, int y, int size, char iconChar,
                      @Nullable List<Component> tooltipLines, Runnable onPressed) {
        super(x, y, size, size, Component.empty());
        this.iconChar = iconChar;
        this.tooltipLines = tooltipLines;
        this.onPressed = onPressed;
    }

    /** 便捷构造 —— 单行 tooltip */
    public IconButton(int x, int y, int size, char iconChar,
                      @Nullable Component tooltip, Runnable onPressed) {
        this(x, y, size, iconChar, tooltip != null ? List.of(tooltip) : null, onPressed);
    }

    public void setIconChar(char iconChar) {
        this.iconChar = iconChar;
    }

    /** 设置单行 tooltip（兼容便捷方法） */
    public void setTooltip(@Nullable Component tooltip) {
        this.tooltipLines = tooltip != null ? List.of(tooltip) : null;
    }

    /** 设置多行 tooltip */
    public void setTooltipLines(@Nullable List<Component> tooltipLines) {
        this.tooltipLines = tooltipLines;
    }

    @Override
    public void onPress() {
        if (this.onPressed != null) {
            this.onPressed.run();
        }
    }

    @Override
    protected void updateWidgetNarration(@NotNull NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    @Override
    protected void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        int w = this.width;
        int h = this.height;
        boolean hovered = this.isHovered() && this.active;

        // 像素风木质边框 (1px)
        guiGraphics.fill(x, y, x + w, y + h, BORDER_COLOR);
        // 内部高光边（上、左 1px）
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + 2, BORDER_LIGHT);
        guiGraphics.fill(x + 1, y + 2, x + 2, y + h - 1, BORDER_LIGHT);
        // 背景
        int bg = hovered ? BG_HOVERED : BG_NORMAL;
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);

        // 图标字符居中
        Font font = Minecraft.getInstance().font;
        String text = String.valueOf(iconChar);
        int textWidth = font.width(text);
        int textX = x + (w - textWidth) / 2;
        int textY = y + (h - 8) / 2;
        int textColor = hovered ? TEXT_COLOR_HOVERED : TEXT_COLOR_NORMAL;
        guiGraphics.drawString(font, text, textX, textY, textColor, false);
    }

    /** 由 Screen.render 调用，在所有 widget 之后绘制 tooltip */
    public void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.tooltipLines != null && !this.tooltipLines.isEmpty() && this.isHovered()) {
            Font font = Minecraft.getInstance().font;
            guiGraphics.renderTooltip(font, this.tooltipLines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }
}