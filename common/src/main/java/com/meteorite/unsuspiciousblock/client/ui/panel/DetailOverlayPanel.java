package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 详情面板 —— 解析进度条 + 模组来源 */
public final class DetailOverlayPanel {
    private static final int BAR_HEIGHT = 12;
    private static final int BAR_BG_COLOR = 0xFF555555;
    private static final int BAR_FG_COLOR = 0xFFC8A050;
    private static final int TEXT_COLOR = 0x4A3320;
    private static final int LABEL_COLOR = 0x5A422C;

    private final JournalBookBackground.BookLayout layout;
    private int parsedCount;
    private int totalCount;
    private String modSource;

    public DetailOverlayPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.parsedCount = 0;
        this.totalCount = 0;
        this.modSource = "";
    }

    public void setData(ResourceLocation tableId, int parsedCount, int totalCount) {
        this.parsedCount = parsedCount;
        this.totalCount = totalCount;
        if (tableId != null) {
            String ns = tableId.getNamespace();
            this.modSource = "minecraft".equals(ns) ? "原版 (Minecraft)" : ns;
        } else {
            this.modSource = "???";
        }
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = layout.rightPageX() + 8;
        int contentWidth = layout.rightPageWidth() - 20;
        int y = layout.rightPageY() + JournalLayout.GRID_TOP;

        // 模组来源
        Component modLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.mod_source");
        guiGraphics.drawString(font, modLabel, leftX, y, LABEL_COLOR, false);
        guiGraphics.drawString(font, modSource, leftX, y + 14, TEXT_COLOR, false);

        // 解析进度标题
        y += 40;
        Component progressLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.parse_progress");
        guiGraphics.drawString(font, progressLabel, leftX, y, LABEL_COLOR, false);

        // 进度条
        y += 16;
        int barWidth = contentWidth;
        guiGraphics.fill(leftX, y, leftX + barWidth, y + BAR_HEIGHT, BAR_BG_COLOR);

        if (totalCount > 0) {
            double ratio = (double) parsedCount / totalCount;
            int filledWidth = (int) (barWidth * ratio);
            if (filledWidth > 0) {
                // 进度颜色渐变：低进度金色，高进度绿色
                int progressColor = ratio >= 1.0 ? 0xFF6BA050 : BAR_FG_COLOR;
                guiGraphics.fill(leftX + 1, y + 1, leftX + filledWidth - 1, y + BAR_HEIGHT - 1, progressColor);
            }

            // 百分比文字
            int percent = (int) (ratio * 100.0);
            String percentText = percent + "%  (" + parsedCount + "/" + totalCount + ")";
            int textWidth = font.width(percentText);
            guiGraphics.drawString(font, percentText,
                    leftX + (barWidth - textWidth) / 2, y + 2, 0xFFFFFFFF, false);
        }
    }
}
