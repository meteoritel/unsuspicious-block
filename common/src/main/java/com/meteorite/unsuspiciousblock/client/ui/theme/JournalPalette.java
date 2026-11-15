package com.meteorite.unsuspiciousblock.client.ui.theme;

import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.render.UiNineSlice;
import com.meteorite.meteosuikit.api.render.UiRenderLayers;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.meteosuikit.api.text.TextScroll;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

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

    // —— 搜索未命中暖色淡化遮罩 ——
    public static final int DIM_MASK = 0x709C8B74;

    // —— 物品插槽与药丸指标底纹 ——
    public static final int SLOT_BG = 0x20000000;
    public static final int SLOT_BORDER = 0x558B6914;
    public static final int SLOT_BORDER_HOVER = 0xFFC89B48;
    public static final int STAT_PILL_BG = 0x1A8B6914;
    public static final int STAT_PILL_BORDER = 0x508B6914;
    public static final int STAT_PILL_HOVER = 0x30C8A050;

    // —— 统一进度条（左侧目录 / 介绍页 / 日志详情页共用） ——
    public static final int PROGRESS_BORDER = 0xFF7A5722;
    public static final int PROGRESS_TRACK = 0xFFD8C0A0;
    public static final int PROGRESS_FILL_ACTIVE = 0xFFC89B48;
    public static final int PROGRESS_FILL_DONE = 0xFF7D9C4A;
    public static final int PROGRESS_TEXT = UiTextScheme.PARCHMENT.title();

    // —— 物品图标前景角标（带原版阴影渲染在物品图标上） ——
    public static final int ITEM_COUNT_NORMAL = 0xFFFFFFFF;
    public static final int ITEM_COUNT_ABBR = 0xFFFFC060;
    public static final int LOOT_BADGE_FULL = 0xFF3A8C3A;
    public static final int LOOT_BADGE_PARTIAL = 0xFFB08C00;

    private JournalPalette() {
    }

    /**
     * 绘制精致羊皮纸风格插槽底框（程序化 9-slice 内凹阴影凹槽 + 底部高光 + 悬停强调）。
     */
    public static void drawSlot(GuiGraphics graphics, int x, int y, int size, boolean hovered) {
        UiNineSlice slice = hovered ? UiAtlas.SLOT_INSET_HOVER : UiAtlas.SLOT_INSET;
        slice.render(graphics, new UiRect(x, y, size, size));
    }

    /**
     * 绘制精致羊皮纸风格状态指标药丸（程序化 9-slice 柔和圆角矩形）。
     */
    public static void drawStatPill(GuiGraphics graphics, int x, int y, int width, int height, boolean subtle) {
        UiNineSlice slice = subtle ? UiAtlas.STAT_PILL_SUBTLE : UiAtlas.STAT_PILL;
        slice.render(graphics, new UiRect(x, y, width, height));
    }

    /**
     * 绘制居中空状态占位卡片（带装饰物品插槽与自动换行说明文本）。
     */
    public static void drawEmptyState(GuiGraphics graphics, Font font, UiRect area,
                                      ItemStack icon, Component message) {
        if (area.width() < 24 || area.height() < 24) {
            return;
        }
        int maxTextWidth = Math.max(20, area.width() - 24);
        var lines = font.split(message, maxTextWidth);
        int textHeight = lines.size() * (font.lineHeight + 2);
        int iconBlock = icon.isEmpty() ? 0 : 26;
        int totalHeight = iconBlock + textHeight;
        int startY = area.y() + Math.max(6, (area.height() - totalHeight) / 2);

        if (!icon.isEmpty()) {
            int slotSize = 20;
            int slotX = area.x() + (area.width() - slotSize) / 2;
            int itemX = slotX + 2;
            int itemY = startY + 2;
            drawSlot(graphics, slotX, startY, slotSize, false);
            UiRenderLayers.item(graphics, new UiRect(itemX, itemY, 16, 16),
                    local -> local.renderItem(icon, itemX, itemY));
            startY += iconBlock;
        }

        for (var line : lines) {
            int lineWidth = font.width(line);
            int lineX = area.x() + (area.width() - lineWidth) / 2;
            graphics.drawString(font, line, lineX, startY, UiTextScheme.PARCHMENT.label(), false);
            startY += font.lineHeight + 2;
        }
    }

    /**
     * 绘制统一羊皮纸风格进度条（内凹槽 + 渐变高光填充 + 居中文本）。
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
        // 凹槽内顶阴影与左侧阴影
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0x30000000);
        graphics.fill(x + 1, y + 1, x + 2, y + height - 1, 0x20000000);

        int innerWidth = Math.max(0, width - 2);
        if (total > 0 && innerWidth > 0) {
            int clamped = Mth.clamp(current, 0, total);
            int filled = Mth.clamp(Math.round(innerWidth * (clamped / (float) total)), 0, innerWidth);
            if (filled > 0) {
                int fillColor = clamped >= total ? PROGRESS_FILL_DONE : PROGRESS_FILL_ACTIVE;
                graphics.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, fillColor);
                // 填充条上沿亮光与下沿微阴影，营造微立体质感
                graphics.fill(x + 1, y + 1, x + 1 + filled, y + 2, 0x40FFFFFF);
                graphics.fill(x + 1, y + height - 2, x + 1 + filled, y + height - 1, 0x30000000);
            }
        }
        int textY = y + (height - font.lineHeight + 1) / 2;
        TextScroll.draw(graphics, font, label.getString(), x + 3, textY, Math.max(0, width - 6),
                PROGRESS_TEXT, hovered, ticks, true);
    }
}
