package com.meteorite.unsuspiciousblock.client.ui.support;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 滚动文字绘制工具 —— 文字超宽时悬停自动滚动 */
public final class ScrollTextHelper {
    private static final int SCROLL_PAUSE_WIDTH = 20;
    private static final int SCROLL_SPEED = 1;

    private ScrollTextHelper() {
    }

    public static void draw(GuiGraphics guiGraphics, Font font, String text,
                            int x, int y, int maxWidth, int color,
                            boolean hovered, int scrollTicks, boolean centered) {
        if (maxWidth <= 0) {
            return;
        }
        int textWidth = font.width(text);
        if (textWidth <= maxWidth) {
            int drawX = centered ? x + (maxWidth - textWidth) / 2 : x;
            guiGraphics.drawString(font, text, drawX, y, color, false);
            return;
        }

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

        // 整体 UI 缩放时 GuiGraphics 的 scissor 坐标不会跟随 PoseStack，
        // 改为按字符宽度截取可见窗口，避免超长文字被完整裁掉。
        String visibleText;
        if (offset >= overflow) {
            visibleText = font.plainSubstrByWidth(text, maxWidth, true);
        } else {
            String skippedPrefix = font.plainSubstrByWidth(text, offset);
            String remaining = text.substring(skippedPrefix.length());
            visibleText = font.plainSubstrByWidth(remaining, maxWidth);
        }
        guiGraphics.drawString(font, visibleText, x, y, color, false);
    }
}
