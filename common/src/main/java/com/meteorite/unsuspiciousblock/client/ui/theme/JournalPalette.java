package com.meteorite.unsuspiciousblock.client.ui.theme;

import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.meteosuikit.api.text.TextScroll;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 羊皮纸书页 GUI 的结构色板与共用进度条绘制工具。
 *
 * <p>文本语义色统一使用 {@link UiTextScheme#PARCHMENT} 与 {@link JournalTextColors}；
 * 本类收拢边框、卡片底纹、分隔线、进度条与遮罩等非文本结构色（全部采用显式 8 位 ARGB），
 * 确保各页观感一致，同时避免漏写 Alpha 通道导致 {@code GuiGraphics.fill} 透明失效。</p>
 */
public final class JournalPalette {
    // —— 边框与分隔线 ——
    public static final int BORDER_WARM = 0xFF8B6914;
    public static final int BORDER_SOFT = 0x50C8B090;
    public static final int SEPARATOR = 0x508B6914;
    public static final int GRID_SEPARATOR = 0x408B6914;

    // —— 卡片、行底纹与选中条 ——
    public static final int CARD_BG = 0x18A67C42;
    public static final int CARD_HOVER = 0x30C8A050;
    public static final int CARD_SUBTLE = 0x28A08060;
    public static final int CARD_SUBTLE_LIGHT = 0x18A08060;
    public static final int CARD_SUBTLE_HOVER = 0x22C8B090;
    public static final int CARD_MUTED_HOVER = 0x22776456;
    public static final int NOTE_BADGE_HOVER_BG = 0x60A08060;
    public static final int SELECTED_BAR = 0xC07B3E18;

    // —— 子表 / 物品标签合集卡片 ——
    public static final int COLLECTION_BG = 0x186B7D46;
    public static final int COLLECTION_HOVER = 0x306B7D46;
    public static final int COLLECTION_BORDER = 0x806B7D46;

    // —— 搜索未命中暖色淡化遮罩 ——
    public static final int DIM_MASK = 0x709C8B74;

    // —— 场景文档连线与标题底纹 ——
    public static final int SCENARIO_BRANCH_LINE = 0xFFA99370;
    public static final int SCENARIO_SECTION_FILL = 0x40B99A60;
    public static final int SCENARIO_ENTRY_FILL = 0x18B99A60;

    // —— 统一进度条（左侧目录 / 介绍页 / 日志详情页共用） ——
    public static final int PROGRESS_BORDER = BORDER_WARM;
    public static final int PROGRESS_TRACK = 0xFFD8C0A0;
    public static final int PROGRESS_FILL_ACTIVE = 0xFFC89B48;
    public static final int PROGRESS_FILL_DONE = 0xFF8FA65A;
    public static final int PROGRESS_TEXT = UiTextScheme.PARCHMENT.title();

    // —— 物品图标前景角标（带原版阴影渲染在物品图标上） ——
    public static final int ITEM_COUNT_NORMAL = 0xFFFFFFFF;
    public static final int ITEM_COUNT_ABBR = 0xFFFFC060;
    public static final int LOOT_BADGE_FULL = 0xFF3A8C3A;
    public static final int LOOT_BADGE_PARTIAL = 0xFFB08C00;

    private JournalPalette() {
    }

    /**
     * 绘制统一羊皮纸风格进度条（边框 + 底槽 + 按完成态切换的填充 + 居中无阴影文本）。
     *
     * @param graphics 绘制上下文
     * @param font     字体
     * @param x        左上角 X
     * @param y        左上角 Y
     * @param width    总宽度
     * @param height   总高度
     * @param current  当前进度值
     * @param total    总进度值
     * @param label    条内显示文案
     * @param hovered  指针是否悬停于进度条文本区（用于超宽走马灯）
     * @param ticks    悬停走马灯计时
     */
    public static void drawProgressBar(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                                       int current, int total, Component label, boolean hovered, int ticks) {
        graphics.fill(x, y, x + width, y + height, PROGRESS_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PROGRESS_TRACK);
        int innerWidth = Math.max(0, width - 2);
        if (total > 0 && innerWidth > 0) {
            int clamped = Mth.clamp(current, 0, total);
            int filled = Mth.clamp(Math.round(innerWidth * (clamped / (float) total)), 0, innerWidth);
            if (filled > 0) {
                int fillColor = clamped >= total ? PROGRESS_FILL_DONE : PROGRESS_FILL_ACTIVE;
                graphics.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, fillColor);
            }
        }
        int textY = y + (height - font.lineHeight + 1) / 2;
        TextScroll.draw(graphics, font, label.getString(), x + 3, textY, Math.max(0, width - 6),
                PROGRESS_TEXT, hovered, ticks, true);
    }
}
