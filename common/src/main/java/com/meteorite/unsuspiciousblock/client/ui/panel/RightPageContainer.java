package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.widget.BookmarkToggleButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** 右侧页面容器 —— 组合管理标题、进度、网格、页码、书签按钮、详情面板 */
public final class RightPageContainer {
    private final JournalBookBackground.BookLayout layout;
    private final ItemGridPanel gridPanel;
    private final PageIndicator pageIndicator;
    private final DetailOverlayPanel detailPanel;
    private final BookmarkToggleButton bookmarkBtn;
    private Component title = Component.empty();
    private int parsedCount;
    private int totalCount;
    private boolean showDetail;

    public RightPageContainer(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.gridPanel = new ItemGridPanel(layout);
        this.pageIndicator = new PageIndicator(layout);
        this.detailPanel = new DetailOverlayPanel(layout);

        int btnX = layout.rightPageRight() - 24;
        int btnY = layout.rightPageY() + 2;
        this.bookmarkBtn = new BookmarkToggleButton(btnX, btnY, 16, 18, () -> showDetail = !showDetail);
    }

    public void setTable(ResourceLocation tableId, Component tableName, List<ItemGridPanel.GridItem> items,
                         double totalWeight, int parsedCount, int totalCount, boolean approximate) {
        this.title = tableName;
        this.parsedCount = parsedCount;
        this.totalCount = totalCount;
        this.showDetail = false;
        this.bookmarkBtn.setToggled(false);
        this.gridPanel.setTable(items, totalWeight, approximate);
        this.gridPanel.resetPage();
        this.pageIndicator.setPage(gridPanel.getPage(), gridPanel.pageCount());
        this.detailPanel.setData(tableId, parsedCount, totalCount);
    }

    public BookmarkToggleButton getBookmarkButton() {
        return bookmarkBtn;
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = layout.rightPageX() + 8;

        // 表名标题（注意右侧留出书签按钮空间）
        guiGraphics.drawString(font, title, leftX,
                layout.rightPageY() + JournalLayout.GRID_TITLE_Y, 0x4A3320, false);

        // 进度文字
        Component progress = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.progress_items", parsedCount, totalCount);
        guiGraphics.drawString(font, progress, leftX,
                layout.rightPageY() + JournalLayout.GRID_PROGRESS_Y, 0x5A422C, false);

        // 书签按钮
        bookmarkBtn.render(guiGraphics, mouseX, mouseY, 0f);

        if (showDetail) {
            detailPanel.render(guiGraphics, font, mouseX, mouseY);
        } else {
            gridPanel.render(guiGraphics, font, mouseX, mouseY);
            pageIndicator.render(guiGraphics, font);
        }
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        if (bookmarkBtn.isMouseOver(mouseX, mouseY)) return true;
        return gridPanel.containsMouse(mouseX, mouseY);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (bookmarkBtn.isMouseOver(mouseX, mouseY)) {
            bookmarkBtn.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return false;
    }

    public void handleScroll(double scrollY) {
        if (!showDetail && gridPanel.pageCount() > 1) {
            gridPanel.changePage(scrollY < 0.0 ? 1 : -1);
            pageIndicator.setPage(gridPanel.getPage(), gridPanel.pageCount());
        }
    }

    public int pageCount() {
        return showDetail ? 1 : gridPanel.pageCount();
    }

    public int getPage() {
        return gridPanel.getPage();
    }

    public void changePage(int delta) {
        if (!showDetail) {
            gridPanel.changePage(delta);
            pageIndicator.setPage(gridPanel.getPage(), gridPanel.pageCount());
        }
    }

    public void resetPage() {
        gridPanel.resetPage();
        pageIndicator.setPage(0, gridPanel.pageCount());
    }
}
