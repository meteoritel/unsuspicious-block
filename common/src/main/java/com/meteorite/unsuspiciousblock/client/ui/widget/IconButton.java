package com.meteorite.unsuspiciousblock.client.ui.widget;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 正方形图标按钮 —— 渲染像素图标或字体字符图标 + 像素风木质背景，支持多行 tooltip。
 */
public class IconButton extends AbstractButton {

    /** 尺寸统一的内置像素图标，避免不同字体字符出现大小和基线差异。 */
    public enum Icon {
        ARROW_UP(0, 0),
        ARROW_DOWN(1, 0),
        SHOW_LOCKED(2, 0),
        HIDE_LOCKED(3, 0),
        SEARCH(4, 0),
        CLOSE(5, 0),
        SORT_DEFAULT(0, 1),
        SORT_NAME(1, 1),
        SORT_ITEM_COUNT(2, 1),
        SORT_FAVORITE(3, 1),
        SORT_UNLOCK(4, 1),
        SORT_UPDATE_TIME(5, 1),
        DISABLED(6, 1),
        ENABLED(7, 1);

        private final int atlasColumn;
        private final int atlasRow;

        Icon(int atlasColumn, int atlasRow) {
            this.atlasColumn = atlasColumn;
            this.atlasRow = atlasRow;
        }

        private int atlasX() {
            return this.atlasColumn * ICON_ATLAS_CELL_SIZE;
        }

        private int atlasY() {
            return this.atlasRow * ICON_ATLAS_CELL_SIZE;
        }
    }

    private static final ResourceLocation ICON_ATLAS = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/toolbar_icons.png");
    private static final int ICON_ATLAS_CELL_SIZE = 9;
    private static final int ICON_ATLAS_WIDTH = 81;
    private static final int ICON_ATLAS_HEIGHT = 18;

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
    private @Nullable Icon icon;
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

    /** 使用统一像素图标的构造器。 */
    public IconButton(int x, int y, int size, @Nullable Icon icon,
                      @Nullable List<Component> tooltipLines, Runnable onPressed) {
        super(x, y, size, size, firstTooltipLine(tooltipLines));
        this.icon = icon;
        this.tooltipLines = tooltipLines;
        this.onPressed = onPressed;
    }

    /** 便捷构造 —— 单行 tooltip */
    public IconButton(int x, int y, int size, char iconChar,
                      @Nullable Component tooltip, Runnable onPressed) {
        this(x, y, size, iconChar, tooltip != null ? List.of(tooltip) : null, onPressed);
    }

    /** 使用统一像素图标的便捷构造器。 */
    public IconButton(int x, int y, int size, Icon icon,
                      @Nullable Component tooltip, Runnable onPressed) {
        this(x, y, size, icon, tooltip != null ? List.of(tooltip) : null, onPressed);
    }

    public void setIconChar(char iconChar) {
        this.iconChar = iconChar;
        this.icon = null;
    }

    public void setIcon(@Nullable Icon icon) {
        this.icon = icon;
    }

    /** 设置单行 tooltip（兼容便捷方法） */
    public void setTooltip(@Nullable Component tooltip) {
        this.tooltipLines = tooltip != null ? List.of(tooltip) : null;
        this.setMessage(tooltip != null ? tooltip : Component.empty());
    }

    /** 设置多行 tooltip */
    public void setTooltipLines(@Nullable List<Component> tooltipLines) {
        this.tooltipLines = tooltipLines;
        this.setMessage(firstTooltipLine(tooltipLines));
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

        int textColor = hovered ? TEXT_COLOR_HOVERED : TEXT_COLOR_NORMAL;
        if (this.icon != null) {
            int iconX = x + (w - ICON_ATLAS_CELL_SIZE) / 2;
            int iconY = y + (h - ICON_ATLAS_CELL_SIZE) / 2;
            guiGraphics.blit(ICON_ATLAS, iconX, iconY,
                    this.icon.atlasX(), this.icon.atlasY(),
                    ICON_ATLAS_CELL_SIZE, ICON_ATLAS_CELL_SIZE,
                    ICON_ATLAS_WIDTH, ICON_ATLAS_HEIGHT);
        } else {
            Font font = Minecraft.getInstance().font;
            String text = String.valueOf(iconChar);
            int textWidth = font.width(text);
            int textX = x + (w - textWidth) / 2;
            int textY = y + (h - 8) / 2;
            guiGraphics.drawString(font, text, textX, textY, textColor, false);
        }
    }

    private static Component firstTooltipLine(@Nullable List<Component> tooltipLines) {
        return tooltipLines != null && !tooltipLines.isEmpty() ? tooltipLines.getFirst() : Component.empty();
    }

    /** 由 Screen.render 调用，在所有 widget 之后绘制 tooltip */
    public void renderTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.tooltipLines != null && !this.tooltipLines.isEmpty() && this.isHovered()) {
            Font font = Minecraft.getInstance().font;
            guiGraphics.renderTooltip(font, this.tooltipLines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }
}
