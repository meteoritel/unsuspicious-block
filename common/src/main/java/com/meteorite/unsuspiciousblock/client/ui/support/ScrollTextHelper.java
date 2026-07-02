package com.meteorite.unsuspiciousblock.client.ui.support;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 滚动文字绘制工具 —— 文字超宽时悬停自动滚动 */
public final class ScrollTextHelper {
    private static final int SCROLL_PAUSE_WIDTH = 20;
    private static final int SCROLL_SPEED = 1;

    private ScrollTextHelper() {
    }

    // 绘制可滚动文字，默认使用 font.lineHeight + 1 作为裁切高度
    public static void draw(GuiGraphics guiGraphics, Font font, String text,
                            int x, int y, int maxWidth, int color,
                            boolean hovered, int scrollTicks, boolean centered) {
        draw(guiGraphics, font, text, x, y, y, maxWidth, font.lineHeight + 1,
                color, hovered, scrollTicks, centered);
    }

    // 绘制可滚动文字，分别指定裁切区域和文字绘制 Y 坐标
    public static void draw(GuiGraphics guiGraphics, Font font, String text,
                            int scissorX, int scissorY, int drawY, int maxWidth, int scissorHeight,
                            int color, boolean hovered, int scrollTicks, boolean centered) {
        if (maxWidth <= 0) {
            return;
        }
        int textWidth = font.width(text);
        if (textWidth <= maxWidth) {
            int drawX = centered ? scissorX + (maxWidth - textWidth) / 2 : scissorX;
            guiGraphics.drawString(font, text, drawX, drawY, color, false);
            return;
        }

        guiGraphics.enableScissor(scissorX, scissorY, scissorX + maxWidth, scissorY + scissorHeight);
        int overflow = textWidth - maxWidth;
        int offset = 0;
        if (hovered) {
            int period = overflow + SCROLL_PAUSE_WIDTH * 2;
            int rawOffset = (scrollTicks * SCROLL_SPEED / 4) % period;
            if (rawOffset > overflow + SCROLL_PAUSE_WIDTH) {
                rawOffset = period - rawOffset;
            }
            if (rawOffset > overflow) {
                rawOffset = overflow;
            }
            offset = rawOffset;
        }
        guiGraphics.drawString(font, text, scissorX - offset, drawY, color, false);
        guiGraphics.disableScissor();
    }
}
