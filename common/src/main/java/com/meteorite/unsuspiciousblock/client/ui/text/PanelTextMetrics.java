package com.meteorite.unsuspiciousblock.client.ui.text;

import net.minecraft.locale.Language;
import net.minecraft.client.gui.Font;

import java.util.HashMap;
import java.util.Map;

/**
 * 面板持有的文本宽度缓存；随语言资源、字体身份及业务数据失效，仅在 GUI 线程使用。
 */
public final class PanelTextMetrics {
    /**
     * 已测文案：文本与宽度同批返回，保证传给 TextScroll 的宽度与文本同代。
     */
    public record Measured(String text, int width) {
    }

    private final Map<String, Integer> widths = new HashMap<>();
    private Font font;
    private Language language;

    /**
     * 同一 Font 与语言资源内复用测量，任一身份变化时清空缓存。
     */
    public void beginFrame(Font font) {
        Language current = Language.getInstance();
        if (this.font == font && current == this.language) {
            return;
        }
        this.font = font;
        this.language = current;
        this.widths.clear();
    }

    /**
     * 文案与宽度同批返回，缓存生命周期由持有面板控制。
     */
    public Measured measure(String text, Font font) {
        Integer width = this.widths.get(text);
        if (width == null) {
            width = font.width(text);
            this.widths.put(text, width);
        }
        return new Measured(text, width);
    }

    /**
     * 业务内容整体替换时释放旧文本度量。
     */
    public void clear() {
        this.widths.clear();
    }
}
