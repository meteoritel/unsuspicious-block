package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 页码指示器 —— "第 X/Y 页" */
public final class PageIndicator {
    private int page;
    private int pageCount;
    private final JournalBookBackground.BookLayout layout;

    public PageIndicator(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.page = 0;
        this.pageCount = 1;
    }

    public void setPage(int page, int pageCount) {
        this.page = page;
        this.pageCount = Math.max(1, pageCount);
    }

    public void render(GuiGraphics guiGraphics, Font font) {
        Component pageText = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.page", page + 1, pageCount);
        int textWidth = font.width(pageText);
        guiGraphics.drawString(font, pageText,
                layout.rightPageRight() - textWidth - 8,
                layout.rightPageY() + JournalLayout.GRID_PAGE_INDICATOR_Y, 0x6E5A42, false);
    }
}
