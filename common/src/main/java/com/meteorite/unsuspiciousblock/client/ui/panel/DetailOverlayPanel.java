package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
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

    private final JournalBookBackground.BookLayout layout;
    private int parsedCount;
    private int totalCount;
    private String modSource;
    private List<ItemGridPanel.GridItem> unlockedItems = List.of();

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
        if (tableId != null) {
            String ns = tableId.getNamespace();
            this.modSource = "minecraft".equals(ns) ? "原版 (Minecraft)" : ns;
        } else {
            this.modSource = "???";
        }
        this.unlockedItems = new ArrayList<>();
        for (ItemGridPanel.GridItem item : allItems) {
            if (item.unlocked()) {
                this.unlockedItems.add(item);
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

        // 进度条
        int barWidth = contentWidth;
        guiGraphics.fill(leftX, y, leftX + barWidth, y + BAR_HEIGHT, BAR_BG_COLOR);

        if (totalCount > 0) {
            double ratio = (double) parsedCount / totalCount;
            int filledWidth = (int) (barWidth * ratio);
            if (filledWidth > 0) {
                int progressColor = ratio >= 1.0 ? 0xFF6BA050 : BAR_FG_COLOR;
                guiGraphics.fill(leftX + 1, y + 1, leftX + filledWidth - 1, y + BAR_HEIGHT - 1, progressColor);
            }

            int percent = (int) (ratio * 100.0);
            String percentText = percent + "%  (" + parsedCount + "/" + totalCount + ")";
            int textWidth = font.width(percentText);
            guiGraphics.drawString(font, percentText,
                    leftX + (barWidth - textWidth) / 2, y + 2, 0xFFFFFFFF, false);
        }
        y += BAR_HEIGHT + 10;

        // 已发现物品列表
        Component discoveredLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.discovered_items");
        guiGraphics.drawString(font, discoveredLabel, leftX, y, LABEL_COLOR, false);
        y += 14;

        if (unlockedItems.isEmpty()) {
            guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.no_discoveries"),
                    leftX + 2, y, MUTED_COLOR, false);
            y += 12;
        } else {
            // 物品列表（每行：图标 + 名称 + 获得次数）
            int itemRowHeight = 16;
            int maxVisibleItems = (layout.rightPageBottom() - y - 50) / itemRowHeight;
            int showCount = Math.min(unlockedItems.size(), Math.max(1, maxVisibleItems));

            for (int i = 0; i < showCount; i++) {
                ItemGridPanel.GridItem item = unlockedItems.get(i);
                int rowY = y + i * itemRowHeight;

                // 图标
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(item.id()));
                guiGraphics.renderItem(stack, leftX + 2, rowY - 1);
                guiGraphics.renderItemDecorations(font, stack, leftX + 2, rowY - 1);

                // 名称
                guiGraphics.drawString(font, item.displayName(), leftX + 22, rowY + 2, TEXT_COLOR, false);

                // 获得次数
                String countText = "×" + item.count();
                int countWidth = font.width(countText);
                guiGraphics.drawString(font, countText, leftX + contentWidth - countWidth, rowY + 2, LABEL_COLOR, false);
            }
            y += showCount * itemRowHeight + 6;
        }

        // 模组来源
        y = Math.max(y, layout.rightPageBottom() - 30);
        Component modLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.mod_source");
        guiGraphics.drawString(font, modLabel, leftX, y, LABEL_COLOR, false);
        guiGraphics.drawString(font, modSource, leftX, y + 12, TEXT_COLOR, false);
    }
}
