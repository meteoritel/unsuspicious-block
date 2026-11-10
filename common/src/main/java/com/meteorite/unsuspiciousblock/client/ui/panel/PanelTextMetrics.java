package com.meteorite.unsuspiciousblock.client.ui.panel;

import net.minecraft.locale.Language;
import net.minecraft.client.gui.Font;

import java.util.HashMap;
import java.util.Map;

/** 面板持有的文本宽度缓存；随语言资源、字体身份及业务数据失效，仅在 GUI 线程使用。 */
final class PanelTextMetrics {
    /** 已测文案：文本与宽度同批返回，保证传给 TextScroll 的宽度与文本同代。 */
    record Measured(String text, int width) {
    }

    private final Map<String, Integer> widths = new HashMap<>();
    private Font font;
    private Language language;

    // 逐帧起点：语言或 Font 实例变化即整表失效（字形宽度只在同一 Font 实例内可比）
    void beginFrame(Font font) {
        Language current = Language.getInstance();
        if (this.font == font && current == this.language) {
            return;
        }
        this.font = font;
        this.language = current;
        this.widths.clear();
    }

    // 取已测文案：未命中时用当前 Font 测一次并缓存
    Measured measure(String text, Font font) {
        Integer width = this.widths.get(text);
        if (width == null) {
            width = font.width(text);
            this.widths.put(text, width);
        }
        return new Measured(text, width);
    }

    // 面板内容整体替换时清空（与文案缓存同一失效事件）
    void clear() {
        this.widths.clear();
    }
}
