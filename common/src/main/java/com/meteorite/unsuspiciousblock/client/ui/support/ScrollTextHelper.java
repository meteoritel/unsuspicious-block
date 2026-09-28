package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.kit.TextScroll;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 滚动文字绘制工具 —— 文字超宽时悬停自动滚动（{@code String} 门面，几何与节奏见 {@link TextScroll}）。
 *
 * <p>本类补两件门面价值：一是 {@code centered} 的默认值，二是把**已测宽度**透传给 kit，
 * 让逐帧调用点不必每帧重新 {@code font.width(text)}（原版测量无缓存，会逐码点走一遍）。</p>
 */
public final class ScrollTextHelper {

    private ScrollTextHelper() {
    }

    // 默认不居中：面板文本绝大多数从可用区域左边界起画。
    public static void draw(GuiGraphics guiGraphics, Font font, String text,
                            int x, int y, int maxWidth, int color,
                            boolean hovered, int scrollTicks) {
        TextScroll.draw(guiGraphics, font, text, x, y, maxWidth, color, hovered, scrollTicks, false);
    }

    public static void draw(GuiGraphics guiGraphics, Font font, String text,
                            int x, int y, int maxWidth, int color,
                            boolean hovered, int scrollTicks, boolean centered) {
        TextScroll.draw(guiGraphics, font, text, x, y, maxWidth, color, hovered, scrollTicks, centered);
    }

    // 已测宽度版本：宽度口径必须与 Font.width(String) 一致（同字体、同字符串、同语言）。
    public static void draw(GuiGraphics guiGraphics, Font font, String text, int textWidth,
                            int x, int y, int maxWidth, int color,
                            boolean hovered, int scrollTicks, boolean centered) {
        TextScroll.draw(guiGraphics, font, text, textWidth, x, y, maxWidth, color, hovered, scrollTicks, centered);
    }
}
