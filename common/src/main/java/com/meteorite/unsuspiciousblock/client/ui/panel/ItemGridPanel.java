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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 右侧物品网格面板 */
public final class ItemGridPanel {

    private static final ResourceLocation UNKNOWN_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/unknown_item.png");
    private static final int ICON_SIZE = 16;
    private static final int ICON_TOP = 4;
    private static final int INFO_TEXT_LEFT_PAD = 2;
    private static final int INFO_TEXT_WIDTH = JournalLayout.GRID_CELL_WIDTH - INFO_TEXT_LEFT_PAD * 2;
    private static final int COUNT_TEXT_Y = 24;
    private static final int PROBABILITY_TEXT_Y = 34;
    private static final int SCROLL_PAUSE_WIDTH = 20;
    private static final int SCROLL_SPEED = 1;

    private final List<GridEntry> items = new ArrayList<>();
    private final JournalBookBackground.BookLayout layout;
    private int page;
    private double totalWeight;
    private boolean approximate;

    public ItemGridPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.page = 0;
    }

    // 设置当前展示的战利品表数据
    public void setTable(List<GridItem> items, double totalWeight, boolean approximate) {
        this.items.clear();
        for (GridItem item : items) {
            this.items.add(new GridEntry(item));
        }
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

    // 直接设置页码（resize 后恢复用）
    public void setPage(int page) {
        this.page = Mth.clamp(page, 0, Math.max(0, pageCount() - 1));
    }

    // 判断鼠标是否在物品网格面板区域内
    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= layout.rightPageX() && mouseX <= layout.rightPageRight()
                && mouseY >= layout.rightPageY() && mouseY <= layout.rightPageBottom();
    }

    // 渲染物品网格（仅网格区域，不含进度/页码）
    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        if (items.isEmpty()) {
            int leftX = layout.rightPageX() + 8;
            guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_entries"),
                    leftX, layout.rightPageY() + JournalLayout.GRID_TOP, 0x7A6247, false);
            return;
        }

        // 网格区域
        int gridWidth = JournalLayout.GRID_CELLS_PER_ROW * JournalLayout.GRID_CELL_WIDTH;
        int gridX = layout.rightPageX() + (layout.rightPageWidth() - gridWidth) / 2;
        int gridY = layout.rightPageY() + JournalLayout.GRID_TOP;

        int from = page * JournalLayout.GRID_ITEMS_PER_PAGE;
        int to = Math.min(items.size(), from + JournalLayout.GRID_ITEMS_PER_PAGE);

        for (int i = from; i < to; i++) {
            int visualIndex = i - from;
            int cellX = cellX(gridX, visualIndex);
            int cellY = cellY(gridY, visualIndex);
            boolean iconHovered = isMouseOverIcon(cellX, cellY, mouseX, mouseY);
            boolean textHovered = isMouseOverCell(cellX, cellY, mouseX, mouseY);
            renderItemCell(guiGraphics, font, cellX, cellY, items.get(i), iconHovered, textHovered);
        }

        // 行间分隔线
        int visibleRows = (to - from + JournalLayout.GRID_CELLS_PER_ROW - 1) / JournalLayout.GRID_CELLS_PER_ROW;
        for (int r = 1; r < visibleRows; r++) {
            int sepY = gridY + r * JournalLayout.GRID_CELL_HEIGHT;
            guiGraphics.fill(gridX, sepY, gridX + gridWidth, sepY + 1, JournalLayout.GRID_SEPARATOR_COLOR);
        }
    }

    private void renderItemCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                GridEntry entry, boolean iconHovered, boolean textHovered) {
        GridItem item = entry.item;
        boolean unlocked = item.unlocked();
        int iconX = cellX + (JournalLayout.GRID_CELL_WIDTH - ICON_SIZE) / 2;
        int iconY = cellY + ICON_TOP;

        if (!entry.wasHovered && textHovered) {
            entry.scrollTicks = 0;
        }
        entry.wasHovered = textHovered;
        if (textHovered) {
            entry.scrollTicks++;
        }

        // 单元格背景
        int bgColor = unlocked ? (iconHovered ? 0x22C8B090 : 0x00000000) : (iconHovered ? 0x22776456 : 0x00000000);
        if (bgColor != 0) {
            guiGraphics.fill(cellX, cellY, cellX + JournalLayout.GRID_CELL_WIDTH, cellY + JournalLayout.GRID_CELL_HEIGHT, bgColor);
        }

        if (unlocked) {
            ItemStack stack = item.stack();

            // 物品图标
            guiGraphics.renderItem(stack, iconX, iconY);
            guiGraphics.renderItemDecorations(font, stack, iconX, iconY);

            // 获得次数
            String countText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.acquired", item.count()).getString();
            drawScrollingText(guiGraphics, font, countText,
                    cellX + INFO_TEXT_LEFT_PAD, cellY + COUNT_TEXT_Y, INFO_TEXT_WIDTH, 0x7B3E18, textHovered, entry.scrollTicks);
            // 概率
            String probabilityText = formatProbability(item.weight(), totalWeight, approximate);
            drawScrollingText(guiGraphics, font, probabilityText,
                    cellX + INFO_TEXT_LEFT_PAD, cellY + PROBABILITY_TEXT_Y, INFO_TEXT_WIDTH, 0x6E5A42, textHovered, entry.scrollTicks);
        } else {
            // 黑色立体剪影材质
            guiGraphics.blit(UNKNOWN_TEXTURE, iconX, iconY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

            // 未解锁信息
            int mutedColor = 0x6E655B;
            drawScrollingText(guiGraphics, font, "?", cellX + INFO_TEXT_LEFT_PAD, cellY + COUNT_TEXT_Y, INFO_TEXT_WIDTH, mutedColor, false, 0);
            drawScrollingText(guiGraphics, font, "?", cellX + INFO_TEXT_LEFT_PAD, cellY + PROBABILITY_TEXT_Y, INFO_TEXT_WIDTH, mutedColor, false, 0);
        }
    }

    @Nullable
    public ItemStack getTooltipStack(double mouseX, double mouseY) {
        int from = page * JournalLayout.GRID_ITEMS_PER_PAGE;
        int to = Math.min(items.size(), from + JournalLayout.GRID_ITEMS_PER_PAGE);
        int gridWidth = JournalLayout.GRID_CELLS_PER_ROW * JournalLayout.GRID_CELL_WIDTH;
        int gridX = layout.rightPageX() + (layout.rightPageWidth() - gridWidth) / 2;
        int gridY = layout.rightPageY() + JournalLayout.GRID_TOP;

        for (int i = from; i < to; i++) {
            GridItem item = items.get(i).item;
            if (!item.unlocked()) {
                continue;
            }
            int visualIndex = i - from;
            int cellX = cellX(gridX, visualIndex);
            int cellY = cellY(gridY, visualIndex);
            if (isMouseOverIcon(cellX, cellY, mouseX, mouseY)) {
                return item.stack();
            }
        }
        return null;
    }

    private static int cellX(int gridX, int visualIndex) {
        int col = visualIndex % JournalLayout.GRID_CELLS_PER_ROW;
        return gridX + col * JournalLayout.GRID_CELL_WIDTH;
    }

    private static int cellY(int gridY, int visualIndex) {
        int row = visualIndex / JournalLayout.GRID_CELLS_PER_ROW;
        return gridY + row * JournalLayout.GRID_CELL_HEIGHT;
    }

    private static boolean isMouseOverCell(int cellX, int cellY, double mouseX, double mouseY) {
        return mouseX >= cellX && mouseX < cellX + JournalLayout.GRID_CELL_WIDTH
                && mouseY >= cellY && mouseY < cellY + JournalLayout.GRID_CELL_HEIGHT;
    }

    private static boolean isMouseOverIcon(int cellX, int cellY, double mouseX, double mouseY) {
        int iconX = cellX + (JournalLayout.GRID_CELL_WIDTH - ICON_SIZE) / 2;
        int iconY = cellY + ICON_TOP;
        return mouseX >= iconX && mouseX < iconX + ICON_SIZE
                && mouseY >= iconY && mouseY < iconY + ICON_SIZE;
    }

    private static void drawScrollingText(GuiGraphics guiGraphics, Font font, String text,
                                          int x, int y, int width, int color, boolean hovered, int scrollTicks) {
        if (width <= 0) {
            return;
        }
        int textWidth = font.width(text);
        if (textWidth <= width) {
            guiGraphics.drawString(font, text, x, y, color, false);
            return;
        }

        guiGraphics.enableScissor(x, y, x + width, y + font.lineHeight + 1);
        int overflow = textWidth - width;
        int offset = 0;
        if (hovered && overflow > 0) {
            offset = (scrollTicks * SCROLL_SPEED / 2) % (overflow + SCROLL_PAUSE_WIDTH * 2);
            if (offset > overflow + SCROLL_PAUSE_WIDTH) {
                offset = overflow + SCROLL_PAUSE_WIDTH * 2 - offset;
            }
            if (offset > overflow) {
                offset = overflow;
            }
        }
        guiGraphics.drawString(font, text, x - offset, y, color, false);
        guiGraphics.disableScissor();
    }

    /** 格式化概率文字 */
    public static String formatProbability(double weight, double totalWeight, boolean approximate) {
        if (totalWeight <= 0 || weight <= 0) return "???";
        double percent = weight * 100.0 / totalWeight;
        String chance = String.format(Locale.ROOT, "%.2f%%", percent);
        if (approximate) chance = "≈ " + chance;
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.probability", chance).getString();
    }

    private static final class GridEntry {
        private final GridItem item;
        private int scrollTicks;
        private boolean wasHovered;

        private GridEntry(GridItem item) {
            this.item = item;
        }
    }

    /** 物品网格条目 */
    public record GridItem(ResourceLocation id, Component displayName, double weight, boolean unlocked, int count) {
        public ItemStack stack() {
            return new ItemStack(BuiltInRegistries.ITEM.get(this.id));
        }
    }
}
