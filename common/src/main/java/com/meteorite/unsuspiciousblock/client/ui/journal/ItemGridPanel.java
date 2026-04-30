package com.meteorite.unsuspiciousblock.client.ui.journal;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 右侧物品网格面板 */
public final class ItemGridPanel {
    static final int CELLS_PER_ROW = 4;
    static final int ROWS_PER_PAGE = 4;
    static final int ITEMS_PER_PAGE = CELLS_PER_ROW * ROWS_PER_PAGE;
    static final int CELL_WIDTH = 38;
    static final int CELL_HEIGHT = 48;
    private static final int GRID_TOP_OFFSET = 32;
    private static final int TITLE_OFFSET_Y = 6;
    private static final int PROGRESS_OFFSET_Y = 18;
    private static final int PAGE_INDICATOR_OFFSET_Y = 216;
    private static final int SEPARATOR_COLOR = 0x40A0A0A0;

    private final List<GridItem> items = new ArrayList<>();
    private final JournalBookBackground.BookLayout layout;
    private int page;
    private Component tableName = Component.empty();
    private int parsedCount;
    private int totalCount;
    private double totalWeight;
    private boolean approximate;

    public ItemGridPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.page = 0;
    }

    /** 设置当前展示的战利品表数据 */
    public void setTable(Component tableName, List<GridItem> items, double totalWeight, int parsedCount, int totalCount, boolean approximate) {
        this.tableName = tableName;
        this.items.clear();
        this.items.addAll(items);
        this.totalWeight = totalWeight;
        this.parsedCount = parsedCount;
        this.totalCount = totalCount;
        this.approximate = approximate;
        this.page = 0;
    }

    public int pageCount() {
        if (items.isEmpty()) return 1;
        return (items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
    }

    public int getPage() {
        return page;
    }

    public void changePage(int delta) {
        page = Mth.clamp(page + delta, 0, Math.max(0, pageCount() - 1));
    }

    /** 判断鼠标是否在物品网格面板区域内 */
    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= layout.rightPageX() && mouseX <= layout.rightPageRight()
                && mouseY >= layout.rightPageY() && mouseY <= layout.rightPageBottom();
    }

    /** 渲染物品网格面板 */
    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = layout.rightPageX() + 8;

        // 表名标题
        guiGraphics.drawString(font, tableName, leftX, layout.rightPageY() + TITLE_OFFSET_Y, 0x4A3320, false);

        // 进度文字
        Component progress = Component.translatable("screen.unsuspiciousblock.archaeology_journal.progress_items", parsedCount, totalCount);
        guiGraphics.drawString(font, progress, leftX, layout.rightPageY() + PROGRESS_OFFSET_Y, 0x5A422C, false);

        // 空状态
        if (items.isEmpty()) {
            guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_entries"),
                    leftX, layout.rightPageY() + GRID_TOP_OFFSET, 0x7A6247, false);
            renderPageIndicator(guiGraphics, font);
            return;
        }

        // 网格区域
        int gridX = layout.rightPageX() + 4;
        int gridY = layout.rightPageY() + GRID_TOP_OFFSET;
        int gridWidth = CELLS_PER_ROW * CELL_WIDTH;

        int from = page * ITEMS_PER_PAGE;
        int to = Math.min(items.size(), from + ITEMS_PER_PAGE);

        for (int i = from; i < to; i++) {
            int visualIndex = i - from;
            int col = visualIndex % CELLS_PER_ROW;
            int row = visualIndex / CELLS_PER_ROW;
            int cellX = gridX + col * CELL_WIDTH;
            int cellY = gridY + row * CELL_HEIGHT;
            boolean hovered = mouseX >= cellX && mouseX < cellX + CELL_WIDTH
                    && mouseY >= cellY && mouseY < cellY + CELL_HEIGHT;
            renderItemCell(guiGraphics, font, cellX, cellY, items.get(i), hovered);
        }

        // 行间分隔线（行1-2间、行2-3间、行3-4间）
        int visibleRows = (to - from + CELLS_PER_ROW - 1) / CELLS_PER_ROW;
        for (int r = 1; r < visibleRows; r++) {
            int sepY = gridY + r * CELL_HEIGHT;
            guiGraphics.fill(gridX, sepY, gridX + gridWidth, sepY + 1, SEPARATOR_COLOR);
        }

        renderPageIndicator(guiGraphics, font);
    }

    private void renderItemCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY, GridItem item, boolean hovered) {
        boolean unlocked = item.unlocked();
        int iconX = cellX + 11;
        int iconY = cellY + 1;

        // 单元格背景
        int bgColor = unlocked ? (hovered ? 0x22C8B090 : 0x00000000) : (hovered ? 0x22776456 : 0x00000000);
        if (bgColor != 0) {
            guiGraphics.fill(cellX, cellY, cellX + CELL_WIDTH, cellY + CELL_HEIGHT, bgColor);
        }

        if (unlocked) {
            // 物品图标
            guiGraphics.renderItem(item.stack(), iconX, iconY);
            guiGraphics.renderItemDecorations(font, item.stack(), iconX, iconY);

            // 名称
            drawCenteredTruncatedString(guiGraphics, font, item.displayName().getString(),
                    cellX, cellY + 18, CELL_WIDTH, 0x4A3320);
            // 获得次数
            String countText;
            if (item.count() > 0) {
                countText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.acquired", item.count()).getString();
            } else {
                countText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.acquired", 0).getString();
            }
            drawCenteredTruncatedString(guiGraphics, font, countText,
                    cellX, cellY + 28, CELL_WIDTH, 0x7B3E18);
            // 权重
            String probText = formatProbability(item.weight(), totalWeight, approximate);
            drawCenteredTruncatedString(guiGraphics, font, probText,
                    cellX, cellY + 38, CELL_WIDTH, 0x6E5A42);
        } else {
            // 物品图标（作为轮廓）
            guiGraphics.renderItem(item.stack(), iconX, iconY);
            // 纯黑滤层
            guiGraphics.fill(iconX, iconY, iconX + 16, iconY + 16, 0xEE000000);
            // "???"
            int qmWidth = font.width("???");
            guiGraphics.drawString(font, "???", iconX + 8 - qmWidth / 2, iconY + 4, 0xFFFFFFFF, false);

            // 三行 "???"
            int mutedColor = 0x6E655B;
            drawCenteredTruncatedString(guiGraphics, font, "???", cellX, cellY + 18, CELL_WIDTH, mutedColor);
            drawCenteredTruncatedString(guiGraphics, font, "???", cellX, cellY + 28, CELL_WIDTH, mutedColor);
            drawCenteredTruncatedString(guiGraphics, font, "???", cellX, cellY + 38, CELL_WIDTH, mutedColor);
        }
    }

    private void renderPageIndicator(GuiGraphics guiGraphics, Font font) {
        int pages = pageCount();
        Component pageText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.page", page + 1, pages);
        int textWidth = font.width(pageText);
        guiGraphics.drawString(font, pageText,
                layout.rightPageRight() - textWidth - 8, layout.rightPageY() + PAGE_INDICATOR_OFFSET_Y, 0x6E5A42, false);
    }

    private static void drawCenteredTruncatedString(GuiGraphics guiGraphics, Font font, String text, int x, int y, int width, int color) {
        if (width <= 0) return;
        String clipped = truncate(font, text, width);
        int textWidth = font.width(clipped);
        int textX = x + Math.max(0, (width - textWidth) / 2);
        guiGraphics.drawString(font, clipped, textX, y, color, false);
    }

    private static String truncate(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) return text;
        if (font.width(text) <= maxWidth) return text;
        int ellipsisWidth = font.width("…");
        if (maxWidth <= ellipsisWidth) return "";
        return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth) + "…";
    }

    /** 格式化概率文字 */
    public static String formatProbability(double weight, double totalWeight, boolean approximate) {
        if (totalWeight <= 0 || weight <= 0) return "???";
        double percent = weight * 100.0 / totalWeight;
        String chance = String.format(Locale.ROOT, "%.1f%%", percent);
        if (approximate) chance = "≈ " + chance;
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.weight", chance).getString();
    }

    /** 物品网格条目 */
    public record GridItem(ResourceLocation id, Component displayName, double weight, boolean unlocked, int count) {
        public ItemStack stack() {
            return new ItemStack(BuiltInRegistries.ITEM.get(this.id));
        }
    }
}
