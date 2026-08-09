package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.support.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.PaginationState;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 考古信息面板 —— 玩家个人进度：进度条 + 已发现物品列表 + 模组来源 */
public final class DetailOverlayPanel implements PagePanel {
    private static final int BAR_HEIGHT = 12;
    private static final int BAR_BG_COLOR = 0xFF555555;
    private static final int BAR_FG_COLOR = 0xFFC8A050;
    private static final int TEXT_COLOR = 0x4A3320;
    private static final int LABEL_COLOR = 0x5A422C;
    private static final int MUTED_COLOR = 0x7A6247;
    private static final int ITEM_ROW_HEIGHT = 16;
    private static final int FOOTER_HEIGHT = 32;
    private static final int MOD_SOURCE_TOP_OFFSET = 42;

    private final JournalBookBackground.BookLayout layout;
    private final PaginationState pagination = new PaginationState(this::computePageCount);
    private int parsedCount;
    private int totalCount;
    private String modSource;
    private int progressLabelScrollTicks;
    private int progressValueScrollTicks;
    private int discoveredLabelScrollTicks;
    private int emptyStateScrollTicks;
    private List<DiscoveredItemEntry> unlockedItems = List.of();

    public DetailOverlayPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.parsedCount = 0;
        this.totalCount = 0;
        this.modSource = "";
    }

    public void setData(ResourceLocation tableId, int parsedCount, int totalCount,
                        List<ItemGridPanel.GridItem> allItems) {
        this.parsedCount = parsedCount;
        this.totalCount = totalCount;
        this.pagination.reset();
        if (tableId != null) {
            this.modSource = Services.PLATFORM.getModDisplayName(tableId.getNamespace());
        } else {
            this.modSource = "???";
        }
        this.unlockedItems = new ArrayList<>();
        List<DiscoveredItemEntry> highlightedEntries = new ArrayList<>();
        List<DiscoveredItemEntry> nonHighlightedEntries = new ArrayList<>();
        for (ItemGridPanel.GridItem item : allItems) {
            if (item.unlocked()) {
                DiscoveredItemEntry entry = new DiscoveredItemEntry(item);
                if (item.highlighted()) {
                    highlightedEntries.add(entry);
                } else {
                    nonHighlightedEntries.add(entry);
                }
            }
        }
        this.unlockedItems.addAll(highlightedEntries);
        this.unlockedItems.addAll(nonHighlightedEntries);
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= this.layout.rightPageX() && mouseX <= this.layout.rightPageRight()
                && mouseY >= this.layout.rightPageY() && mouseY <= this.layout.rightPageBottom();
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = this.layout.rightPageX() + 8;
        int contentWidth = this.layout.rightPageWidth() - 20;
        int y = this.layout.rightPageY() + JournalLayout.GRID_TOP;

        // 解析进度标题
        Component progressLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.parse_progress");
        boolean progressLabelHovered = isTextHovered(mouseX, mouseY, leftX, y, contentWidth, font.lineHeight);
        this.progressLabelScrollTicks = progressLabelHovered ? this.progressLabelScrollTicks + 1 : 0;
        ScrollTextHelper.draw(guiGraphics, font, progressLabel.getString(), leftX, y, contentWidth,
                LABEL_COLOR, progressLabelHovered, this.progressLabelScrollTicks, false);
        y += 14;

        guiGraphics.fill(leftX, y, leftX + contentWidth, y + BAR_HEIGHT, BAR_BG_COLOR);

        if (this.totalCount > 0) {
            double ratio = (double) this.parsedCount / this.totalCount;
            int filledWidth = (int) (contentWidth * ratio);
            if (filledWidth > 0) {
                int progressColor = ratio >= 1.0 ? 0xFF6BA050 : BAR_FG_COLOR;
                guiGraphics.fill(leftX + 1, y + 1, leftX + filledWidth - 1, y + BAR_HEIGHT - 1, progressColor);
            }

            int percent = (int) (ratio * 100.0);
            String percentText = percent + "%  (" + this.parsedCount + "/" + this.totalCount + ")";
            boolean valueHovered = isTextHovered(mouseX, mouseY, leftX + 2, y + 1,
                    contentWidth - 4, BAR_HEIGHT - 2);
            this.progressValueScrollTicks = valueHovered ? this.progressValueScrollTicks + 1 : 0;
            ScrollTextHelper.draw(guiGraphics, font, percentText, leftX + 2, y + 2, contentWidth - 4,
                    0xFFFFFFFF, valueHovered, this.progressValueScrollTicks, true);
        } else {
            this.progressValueScrollTicks = 0;
        }
        y += BAR_HEIGHT + 10;

        // 已发现物品列表
        Component discoveredLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.discovered_items");
        boolean discoveredLabelHovered = isTextHovered(mouseX, mouseY, leftX, y, contentWidth, font.lineHeight);
        this.discoveredLabelScrollTicks = discoveredLabelHovered ? this.discoveredLabelScrollTicks + 1 : 0;
        ScrollTextHelper.draw(guiGraphics, font, discoveredLabel.getString(), leftX, y, contentWidth,
                LABEL_COLOR, discoveredLabelHovered, this.discoveredLabelScrollTicks, false);
        y = listStartY(y);

        if (this.unlockedItems.isEmpty()) {
            int emptyWidth = contentWidth - 4;
            boolean emptyHovered = isTextHovered(mouseX, mouseY, leftX + 2, y, emptyWidth, font.lineHeight);
            this.emptyStateScrollTicks = emptyHovered ? this.emptyStateScrollTicks + 1 : 0;
            ScrollTextHelper.draw(guiGraphics, font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.no_discoveries").getString(),
                    leftX + 2, y, emptyWidth, MUTED_COLOR, emptyHovered, this.emptyStateScrollTicks, false);
            y += 12;
        } else {
            this.emptyStateScrollTicks = 0;
            int maxVisibleItems = maxVisibleItems(y);
            int page = this.pagination.getPage();
            int from = page * maxVisibleItems;
            int to = Math.min(this.unlockedItems.size(), from + maxVisibleItems);
            int showCount = Math.max(0, to - from);
            int countColumnWidth = 0;
            for (int i = from; i < to; i++) {
                countColumnWidth = Math.max(countColumnWidth,
                        font.width(formatCount(this.unlockedItems.get(i).item.count())));
            }

            for (int i = 0; i < showCount; i++) {
                DiscoveredItemEntry entry = this.unlockedItems.get(from + i);
                ItemGridPanel.GridItem item = entry.item;
                int rowY = y + i * ITEM_ROW_HEIGHT;

                // 图标
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(item.id()));
                guiGraphics.renderItem(stack, leftX + 2, rowY - 1);
                guiGraphics.renderItemDecorations(font, stack, leftX + 2, rowY - 1);

                // 获得次数
                String countText = formatCount(item.count());
                int countX = leftX + contentWidth - countColumnWidth;
                guiGraphics.drawString(font, countText, countX + countColumnWidth - font.width(countText),
                        rowY + 2, LABEL_COLOR, false);

                // 名称
                int nameX = leftX + 22;
                int nameMaxWidth = Math.max(0, countX - nameX - 4);
                boolean hovered = mouseX >= nameX && mouseX < nameX + nameMaxWidth
                        && mouseY >= rowY && mouseY < rowY + ITEM_ROW_HEIGHT;
                if (!entry.wasHovered && hovered) {
                    entry.scrollTicks = 0;
                }
                entry.wasHovered = hovered;
                if (hovered) {
                    entry.scrollTicks++;
                }
                // 名称（搜索不匹配时变暗）
                int nameColor = entry.highlighted ? TEXT_COLOR : MUTED_COLOR;
                ScrollTextHelper.draw(guiGraphics, font, item.displayName().getString(),
                        nameX, rowY + 2, nameMaxWidth, nameColor, hovered, entry.scrollTicks, false);
            }
            y += showCount * ITEM_ROW_HEIGHT + 6;
        }

        // 模组来源：名称足够短时与标签同行，过长时从下一行开始换行
        y = Math.max(y, this.layout.rightPageBottom() - MOD_SOURCE_TOP_OFFSET);
        Component modLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.mod_source");
        String labelText = modLabel.getString();
        int labelWidth = font.width(labelText);
        int gap = font.width(" ");
        int sourceWidth = font.width(this.modSource);
        if (labelWidth + gap + sourceWidth <= contentWidth) {
            guiGraphics.drawString(font, labelText, leftX, y, LABEL_COLOR, false);
            guiGraphics.drawString(font, this.modSource, leftX + labelWidth + gap, y, TEXT_COLOR, false);
        } else {
            guiGraphics.drawString(font, labelText, leftX, y, LABEL_COLOR, false);
            int sourceY = y + font.lineHeight + 2;
            List<FormattedCharSequence> sourceLines = font.split(
                    Component.literal(this.modSource), contentWidth);
            for (int index = 0; index < sourceLines.size(); index++) {
                guiGraphics.drawString(font, sourceLines.get(index), leftX,
                        sourceY + index * (font.lineHeight + 2), TEXT_COLOR, false);
            }
        }
    }

    public int pageCount() {
        return this.pagination.pageCount();
    }

    public int getPage() {
        return this.pagination.getPage();
    }

    public void changePage(int delta) {
        this.pagination.changePage(delta);
    }

    public void setPage(int page) {
        this.pagination.setPage(page);
    }

    private int maxVisibleItems(int listStartY) {
        return Math.max(1, (this.layout.rightPageBottom() - listStartY - FOOTER_HEIGHT) / ITEM_ROW_HEIGHT);
    }

    private int listStartY(int discoveredLabelY) {
        return discoveredLabelY + 14;
    }

    private int computePageCount() {
        if (this.unlockedItems.isEmpty()) {
            return 1;
        }
        int listStartY = listStartY(this.layout.rightPageY() + JournalLayout.GRID_TOP + 14 + BAR_HEIGHT + 10);
        int maxVisibleItems = maxVisibleItems(listStartY);
        return Math.max(1, (this.unlockedItems.size() + maxVisibleItems - 1) / maxVisibleItems);
    }

    private static String formatCount(int count) {
        return "×" + count;
    }

    private static boolean isTextHovered(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static final class DiscoveredItemEntry {
        private final ItemGridPanel.GridItem item;
        private final boolean highlighted;
        private int scrollTicks;
        private boolean wasHovered;

        private DiscoveredItemEntry(ItemGridPanel.GridItem item) {
            this.item = item;
            this.highlighted = item.highlighted();
        }
    }
}
