package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.helper.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
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

/** 右侧物品网格面板 */
public final class ItemGridPanel {

    private static final ResourceLocation UNKNOWN_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/unknown_item.png");
    private static final int ICON_SIZE = 16;
    private static final int ICON_TOP = 4;
    private static final int INFO_TEXT_WIDTH = JournalLayout.GRID_CELL_WIDTH;
    private static final int NAME_TEXT_Y = 24;
    private static final int DETAIL_TEXT_Y = 34;
    private static final int FOOTER_TEXT_Y = 44;

    private final List<GridEntry> items = new ArrayList<>();
    private final JournalBookBackground.BookLayout layout;
    private int page;

    public ItemGridPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.page = 0;
    }

    // 设置当前展示的战利品表数据
    public void setTable(List<GridItem> items) {
        this.items.clear();
        for (GridItem item : items) {
            this.items.add(new GridEntry(item));
        }
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
            int leftX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
            guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_entries"),
                    leftX, layout.rightPageY() + JournalLayout.GRID_TOP, 0x7A6247, false);
            return;
        }

        // 网格区域
        int gridWidth = JournalLayout.GRID_CELLS_PER_ROW * JournalLayout.GRID_CELL_WIDTH
                + JournalLayout.GRID_COLUMN_GAP;
        int gridX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
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
        int bgColor = unlocked ? (textHovered ? 0x22C8B090 : 0x00000000) : (textHovered ? 0x22776456 : 0x00000000);
        if (bgColor != 0) {
            guiGraphics.fill(cellX, cellY, cellX + JournalLayout.GRID_CELL_WIDTH, cellY + JournalLayout.GRID_CELL_HEIGHT, bgColor);
        }

        int textX = cellX;
        if (unlocked) {
            ItemStack stack = item.stack();

            // 物品图标
            guiGraphics.renderItem(stack, iconX, iconY);
            guiGraphics.renderItemDecorations(font, stack, iconX, iconY);

            String nameText = item.displayName().getString();
            String countText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.acquired", item.count()).getString();
            String probabilityText = formatProbability(item.probability());
            ScrollTextHelper.draw(guiGraphics, font, nameText,
                    textX, cellY + NAME_TEXT_Y, INFO_TEXT_WIDTH, 0x5A422C, textHovered, entry.scrollTicks, true);
            ScrollTextHelper.draw(guiGraphics, font, countText,
                    textX, cellY + DETAIL_TEXT_Y, INFO_TEXT_WIDTH, 0x71604B, textHovered, entry.scrollTicks, true);
            ScrollTextHelper.draw(guiGraphics, font, probabilityText,
                    textX, cellY + FOOTER_TEXT_Y, INFO_TEXT_WIDTH, 0x857565, textHovered, entry.scrollTicks, true);

            // 搜索时不匹配：覆盖半透明遮罩降低视觉权重
            if (!item.highlighted()) {
                guiGraphics.fill(cellX, cellY, cellX + JournalLayout.GRID_CELL_WIDTH,
                        cellY + JournalLayout.GRID_CELL_HEIGHT, 0x80FFFFFF);
            }
        } else {
            // 黑色立体剪影材质
            guiGraphics.blit(UNKNOWN_TEXTURE, iconX, iconY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

            String nameText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_entry").getString();
            String detailText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.undiscovered").getString();
            String footerText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.pending_analysis").getString();
            ScrollTextHelper.draw(guiGraphics, font, nameText,
                    textX, cellY + NAME_TEXT_Y, INFO_TEXT_WIDTH, 0x6A6157, textHovered, entry.scrollTicks, true);
            ScrollTextHelper.draw(guiGraphics, font, detailText,
                    textX, cellY + DETAIL_TEXT_Y, INFO_TEXT_WIDTH, 0x7B7268, false, 0, true);
            ScrollTextHelper.draw(guiGraphics, font, footerText,
                    textX, cellY + FOOTER_TEXT_Y, INFO_TEXT_WIDTH, 0x8B8278, false, 0, true);
        }
    }

    @Nullable
    public TooltipData getTooltipData(double mouseX, double mouseY) {
        int from = page * JournalLayout.GRID_ITEMS_PER_PAGE;
        int to = Math.min(items.size(), from + JournalLayout.GRID_ITEMS_PER_PAGE);
        int gridX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
        int gridY = layout.rightPageY() + JournalLayout.GRID_TOP;

        for (int i = from; i < to; i++) {
            GridItem item = items.get(i).item;
            if (!item.unlocked()) {
                continue;
            }
            int visualIndex = i - from;
            int cellX = cellX(gridX, visualIndex);
            int cellY = cellY(gridY, visualIndex);
            if (isMouseOverCell(cellX, cellY, mouseX, mouseY)) {
                return new TooltipData(item.stack(), item.tooltipHint());
            }
        }
        return null;
    }

    private static int cellX(int gridX, int visualIndex) {
        int col = visualIndex % JournalLayout.GRID_CELLS_PER_ROW;
        return gridX + col * (JournalLayout.GRID_CELL_WIDTH + JournalLayout.GRID_COLUMN_GAP);
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

    // 格式化概率文字
    public static String formatProbability(String probability) {
        if (probability == null || probability.equals("?")) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.probability_unknown").getString();
        }
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.probability", probability).getString();
    }

    private static final class GridEntry {
        private final GridItem item;
        private int scrollTicks;
        private boolean wasHovered;

        private GridEntry(GridItem item) {
            this.item = item;
        }
    }

    public record TooltipData(ItemStack stack, @Nullable Component hint) {
    }

    // 物品网格条目；highlighted 标记搜索匹配（true = 匹配/无搜索，false = 搜索不匹配）
    public record GridItem(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                           String probability, boolean unlocked, int count,
                           LootResultSignature signature, boolean highlighted) {
        // 便利构造：无搜索时默认全部高亮
        public GridItem(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                        String probability, boolean unlocked, int count,
                        LootResultSignature signature) {
            this(id, displayName, tooltipHint, probability, unlocked, count, signature, true);
        }

        public ItemStack stack() {
            if (this.signature != null) {
                ItemStack preview = this.signature.createPreviewStack();
                if (!preview.isEmpty()) {
                    return preview;
                }
            }
            return new ItemStack(BuiltInRegistries.ITEM.get(this.id));
        }
    }
}
