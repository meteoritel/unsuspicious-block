package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 右侧物品网格面板 */
public final class ItemGridPanel {

    private static final ResourceLocation UNKNOWN_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/unknown_item.png");

    private final List<GridItem> items = new ArrayList<>();
    private final JournalBookBackground.BookLayout layout;
    private int page;
    private double totalWeight;
    private boolean approximate;

    public ItemGridPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.page = 0;
    }

    /** 设置当前展示的战利品表数据 */
    public void setTable(List<GridItem> items, double totalWeight, boolean approximate) {
        this.items.clear();
        this.items.addAll(items);
        this.totalWeight = totalWeight;
        this.approximate = approximate;
        this.page = Mth.clamp(this.page, 0, Math.max(0, pageCount() - 1));
    }

    public int pageCount() {
        if (items.isEmpty()) return 1;
        return (items.size() + JournalLayout.GRID_ITEMS_PER_PAGE - 1) / JournalLayout.GRID_ITEMS_PER_PAGE;
    }

    public int getPage() {
        return page;
    }

    public void changePage(int delta) {
        page = Mth.clamp(page + delta, 0, Math.max(0, pageCount() - 1));
    }

    public void resetPage() {
        page = 0;
    }

    // 判断鼠标是否在物品网格面板区域内
    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= layout.rightPageX() && mouseX <= layout.rightPageRight()
                && mouseY >= layout.rightPageY() && mouseY <= layout.rightPageBottom();
    }

    /** 渲染物品网格（仅网格区域，不含标题/进度/页码） */
    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        if (items.isEmpty()) {
            int leftX = layout.rightPageX() + 8;
            guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_entries"),
                    leftX, layout.rightPageY() + JournalLayout.GRID_TOP, 0x7A6247, false);
            return;
        }

        // 网格区域
        int gridX = layout.rightPageX() + 4;
        int gridY = layout.rightPageY() + JournalLayout.GRID_TOP;
        int gridWidth = JournalLayout.GRID_CELLS_PER_ROW * JournalLayout.GRID_CELL_WIDTH;

        int from = page * JournalLayout.GRID_ITEMS_PER_PAGE;
        int to = Math.min(items.size(), from + JournalLayout.GRID_ITEMS_PER_PAGE);

        for (int i = from; i < to; i++) {
            int visualIndex = i - from;
            int col = visualIndex % JournalLayout.GRID_CELLS_PER_ROW;
            int row = visualIndex / JournalLayout.GRID_CELLS_PER_ROW;
            int cellX = gridX + col * JournalLayout.GRID_CELL_WIDTH;
            int cellY = gridY + row * JournalLayout.GRID_CELL_HEIGHT;
            boolean hovered = mouseX >= cellX && mouseX < cellX + JournalLayout.GRID_CELL_WIDTH
                    && mouseY >= cellY && mouseY < cellY + JournalLayout.GRID_CELL_HEIGHT;
            renderItemCell(guiGraphics, font, cellX, cellY, items.get(i), hovered);
        }

        // 行间分隔线
        int visibleRows = (to - from + JournalLayout.GRID_CELLS_PER_ROW - 1) / JournalLayout.GRID_CELLS_PER_ROW;
        for (int r = 1; r < visibleRows; r++) {
            int sepY = gridY + r * JournalLayout.GRID_CELL_HEIGHT;
            guiGraphics.fill(gridX, sepY, gridX + gridWidth, sepY + 1, JournalLayout.GRID_SEPARATOR_COLOR);
        }
    }

    private void renderItemCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY, GridItem item, boolean hovered) {
        boolean unlocked = item.unlocked();
        int iconX = cellX + (JournalLayout.GRID_CELL_WIDTH - 16) / 2;
        int iconY = cellY + 4;

        // 单元格背景
        int bgColor = unlocked ? (hovered ? 0x22C8B090 : 0x00000000) : (hovered ? 0x22776456 : 0x00000000);
        if (bgColor != 0) {
            guiGraphics.fill(cellX, cellY, cellX + JournalLayout.GRID_CELL_WIDTH, cellY + JournalLayout.GRID_CELL_HEIGHT, bgColor);
        }

        if (unlocked) {
            // 物品图标
            guiGraphics.renderItem(item.stack(), iconX, iconY);
            guiGraphics.renderItemDecorations(font, item.stack(), iconX, iconY);

            // 名称
            drawCenteredTruncatedString(guiGraphics, font, item.displayName().getString(),
                    cellX, cellY + 24, JournalLayout.GRID_CELL_WIDTH, 0x4A3320);
            // 获得次数
            String countText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.acquired", item.count()).getString();
            drawCenteredTruncatedString(guiGraphics, font, countText,
                    cellX, cellY + 34, JournalLayout.GRID_CELL_WIDTH, 0x7B3E18);
            // 权重
            String probText = formatProbability(item.weight(), totalWeight, approximate);
            drawCenteredTruncatedString(guiGraphics, font, probText,
                    cellX, cellY + 44, JournalLayout.GRID_CELL_WIDTH, 0x6E5A42);
        } else {
            // 黑色立体剪影材质
            guiGraphics.blit(UNKNOWN_TEXTURE, iconX, iconY, 0, 0, 16, 16, 16, 16);

            // 三行 "?"
            int mutedColor = 0x6E655B;
            drawCenteredTruncatedString(guiGraphics, font, "?", cellX, cellY + 24, JournalLayout.GRID_CELL_WIDTH, mutedColor);
            drawCenteredTruncatedString(guiGraphics, font, "?", cellX, cellY + 34, JournalLayout.GRID_CELL_WIDTH, mutedColor);
            drawCenteredTruncatedString(guiGraphics, font, "?", cellX, cellY + 44, JournalLayout.GRID_CELL_WIDTH, mutedColor);
        }
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
