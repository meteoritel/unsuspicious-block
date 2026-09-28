package com.meteorite.unsuspiciousblock.client.ui.panel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.util.HashMap;
import java.util.Map;

/**
 * 面板文本「已测宽度」缓存——P-10 的调用点侧：把逐帧 {@code Font#width(String)} 降为每「语言 + Font 实例」一次。
 *
 * <p>原版测量无缓存，会逐码点走一遍；kit 明确不为 {@code String} 建全局宽度缓存（修复计划 D4），
 * 所以由宿主面板按自己的失效点持有。缓存键就是文本本身（宽度是「文本 + 字体 + 语言」的纯函数），
 * 代 = 语言代码 + {@code Font} 实例身份：语言切换、资源包/字体重载、GUI 缩放换 Font 都会整表失效。
 * 面板数据整体替换（如 {@code setTable}）时调用 {@link #clear()}，与文案缓存同一失效事件。</p>
 *
 * <p>只在客户端 GUI 线程使用，无并发要求。</p>
 */
final class PanelTextMetrics {
    /** 已测文案：文本与宽度同批返回，保证传给 TextScroll 的宽度与文本同代。 */
    record Measured(String text, int width) {
    }

    private final Map<String, Integer> widths = new HashMap<>();
    private Font font;
    private String language = "";

    // 逐帧起点：语言或 Font 实例变化即整表失效（字形宽度只在同一 Font 实例内可比）
    void beginFrame(Font font) {
        String current = Minecraft.getInstance().getLanguageManager().getSelected();
        if (this.font == font && current.equals(this.language)) {
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
