package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.kit.TextScroll;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 滚动文字绘制工具 —— 文字超宽时悬停自动滚动（{@code String} 门面，几何与节奏见 {@link TextScroll}）。 */
public final class ScrollTextHelper {

    private ScrollTextHelper() {
    }

    public static void draw(GuiGraphics guiGraphics, Font font, String text,
                            int x, int y, int maxWidth, int color,
                            boolean hovered, int scrollTicks, boolean centered) {
        TextScroll.draw(guiGraphics, font, text, x, y, maxWidth, color, hovered, scrollTicks, centered);
    }
}
