package com.meteorite.unsuspiciousblock.client.ui.kit;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** 注入字体度量；首版采用自然尺寸排版，文字不折行、不截断。 */
public interface TextMeasurer {
    int width(Component text);
    int lineHeight();

    static TextMeasurer of(Font font) { return new FontMeasurer(font); }

    /** 原版字体适配器，仅在排版阶段测量。 */
    record FontMeasurer(Font font) implements TextMeasurer {
        @Override public int width(Component text) { return font.width(text); }
        @Override public int lineHeight() { return font.lineHeight; }
    }
}
