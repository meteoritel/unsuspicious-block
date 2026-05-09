package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.helper.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 考古信息面板 —— 玩家个人进度：进度条 + 已发现物品列表 + 模组来源 */
public final class DetailOverlayPanel {
    private static final int BAR_HEIGHT = 12;
    private static final int BAR_BG_COLOR = 0xFF555555;
    private static final int BAR_FG_COLOR = 0xFFC8A050;
    private static final int TEXT_COLOR = 0x4A3320;
    private static final int LABEL_COLOR = 0x5A422C;
    private static final int MUTED_COLOR = 0x7A6247;
    private static final int ITEM_ROW_HEIGHT = 16;
    private static final int FOOTER_HEIGHT = 50;
    private static final int MOD_SOURCE_TOP_OFFSET = 44;
    private final JournalBookBackground.BookLayout layout;
    private int parsedCount;
    private int totalCount;
    private int page;
    private String modSource;
    private List<DiscoveredItemEntry> unlockedItems = List.of();

    public DetailOverlayPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.parsedCount = 0;
        this.totalCount = 0;
        this.page = 0;
        this.modSource = "";
    }

    public void setData(ResourceLocation tableId, int parsedCount, int totalCount,
                        List<ItemGridPanel.GridItem> allItems) {
        this.parsedCount = parsedCount;
        this.totalCount = totalCount;
        this.page = 0;
        if (tableId != null) {
            this.modSource = Services.PLATFORM.getModDisplayName(tableId.getNamespace());
        } else {
            this.modSource = "???";
        }
        this.unlockedItems = new ArrayList<>();
        for (ItemGridPanel.GridItem item : allItems) {
            if (item.unlocked()) {
                this.unlockedItems.add(new DiscoveredItemEntry(item));
            }
        }
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = layout.rightPageX() + 8;
        int contentWidth = layout.rightPageWidth() - 20;
        int y = layout.rightPageY() + JournalLayout.GRID_TOP;

        // 解析进度标题
        Component progressLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.parse_progress");
        guiGraphics.drawString(font, progressLabel, leftX, y, LABEL_COLOR, false);
        y += 14;

        guiGraphics.fill(leftX, y, leftX + contentWidth, y + BAR_HEIGHT, BAR_BG_COLOR);

        if (totalCount > 0) {
            double ratio = (double) parsedCount / totalCount;
            int filledWidth = (int) (contentWidth * ratio);
            if (filledWidth > 0) {
                int progressColor = ratio >= 1.0 ? 0xFF6BA050 : BAR_FG_COLOR;
                guiGraphics.fill(leftX + 1, y + 1, leftX + filledWidth - 1, y + BAR_HEIGHT - 1, progressColor);
            }

            int percent = (int) (ratio * 100.0);
            String percentText = percent + "%  (" + parsedCount + "/" + totalCount + ")";
            int textWidth = font.width(percentText);
            guiGraphics.drawString(font, percentText,
                    leftX + (contentWidth - textWidth) / 2, y + 2, 0xFFFFFFFF, false);
        }
        y += BAR_HEIGHT + 10;

        // 已发现物品列表
        Component discoveredLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.discovered_items");
        guiGraphics.drawString(font, discoveredLabel, leftX, y, LABEL_COLOR, false);
        y = listStartY(y);

        if (unlockedItems.isEmpty()) {
            guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.no_discoveries"),
                    leftX + 2, y, MUTED_COLOR, false);
            y += 12;
        } else {
            int maxVisibleItems = maxVisibleItems(y);
            int from = page * maxVisibleItems;
            int to = Math.min(unlockedItems.size(), from + maxVisibleItems);
            int showCount = Math.max(0, to - from);

            for (int i = 0; i < showCount; i++) {
                DiscoveredItemEntry entry = unlockedItems.get(from + i);
                ItemGridPanel.GridItem item = entry.item;
                int rowY = y + i * ITEM_ROW_HEIGHT;

                // 图标
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(item.id()));
                guiGraphics.renderItem(stack, leftX + 2, rowY - 1);
                guiGraphics.renderItemDecorations(font, stack, leftX + 2, rowY - 1);

                // 获得次数
                String countText = "×" + item.count();
                int countWidth = font.width(countText);
                guiGraphics.drawString(font, countText, leftX + contentWidth - countWidth, rowY + 2, LABEL_COLOR, false);

                // 名称
                int nameX = leftX + 22;
                int nameMaxWidth = Math.max(0, contentWidth - 24 - countWidth - 6);
                boolean hovered = mouseX >= nameX && mouseX < nameX + nameMaxWidth
                        && mouseY >= rowY && mouseY < rowY + ITEM_ROW_HEIGHT;
                if (!entry.wasHovered && hovered) {
                    entry.scrollTicks = 0;
                }
                entry.wasHovered = hovered;
                if (hovered) {
                    entry.scrollTicks++;
                }
                ScrollTextHelper.draw(guiGraphics, font, item.displayName().getString(),
                        nameX, rowY + 2, nameMaxWidth, TEXT_COLOR, hovered, entry.scrollTicks, false);
            }
            y += showCount * ITEM_ROW_HEIGHT + 6;
        }

        // 模组来源
        y = Math.max(y, layout.rightPageBottom() - MOD_SOURCE_TOP_OFFSET);
        Component modLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.mod_source");
        guiGraphics.drawString(font, modLabel, leftX, y, LABEL_COLOR, false);
        guiGraphics.drawString(font, modSource, leftX, y + 12, TEXT_COLOR, false);
    }

    public int pageCount() {
        if (unlockedItems.isEmpty()) {
            return 1;
        }
        int maxVisibleItems = maxVisibleItems(listStartY(layout.rightPageY() + JournalLayout.GRID_TOP + 14 + BAR_HEIGHT + 10));
        return Math.max(1, (unlockedItems.size() + maxVisibleItems - 1) / maxVisibleItems);
    }

    public int getPage() {
        return this.page;
    }

    public void changePage(int delta) {
        this.page = Math.max(0, Math.min(this.page + delta, pageCount() - 1));
    }

    public void setPage(int page) {
        this.page = Math.max(0, Math.min(page, pageCount() - 1));
    }

    private int maxVisibleItems(int listStartY) {
        return Math.max(1, (layout.rightPageBottom() - listStartY - FOOTER_HEIGHT) / ITEM_ROW_HEIGHT);
    }

    private int listStartY(int discoveredLabelY) {
        return discoveredLabelY + 14;
    }

    private static final class DiscoveredItemEntry {
        private final ItemGridPanel.GridItem item;
        private int scrollTicks;
        private boolean wasHovered;

        private DiscoveredItemEntry(ItemGridPanel.GridItem item) {
            this.item = item;
        }
    }
}
