package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.widget.BookmarkToggleButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 右侧页面容器 —— 通过双书签Tab切换介绍信息/考古信息 */
public final class RightPageContainer {

    private static final int TAB_WIDTH = 40;
    private static final int TAB_HEIGHT = 16;
    private static final int TAB_GAP = 2;

    public enum Tab { INTRO, ARCHAEOLOGY }

    private final JournalBookBackground.BookLayout layout;
    private final ItemGridPanel gridPanel;
    private final PageIndicator pageIndicator;
    private final DetailOverlayPanel detailPanel;
    private final BookmarkToggleButton introTabBtn;
    private final BookmarkToggleButton archaeologyTabBtn;

    private int parsedCount;
    private int totalCount;
    private Tab activeTab = Tab.INTRO;

    public RightPageContainer(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.gridPanel = new ItemGridPanel(layout);
        this.pageIndicator = new PageIndicator(layout);
        this.detailPanel = new DetailOverlayPanel(layout);

        int btnY = layout.rightPageY() + 2;
        int groupWidth = TAB_WIDTH * 2 + TAB_GAP;
        int introBtnX = layout.rightPageX() + (layout.rightPageWidth() - groupWidth) / 2;
        int archBtnX = introBtnX + TAB_WIDTH + TAB_GAP;

        this.introTabBtn = new BookmarkToggleButton(introBtnX, btnY, TAB_WIDTH, TAB_HEIGHT,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_intro"),
                () -> switchTab(Tab.INTRO));
        this.introTabBtn.setToggled(true);

        this.archaeologyTabBtn = new BookmarkToggleButton(archBtnX, btnY, TAB_WIDTH, TAB_HEIGHT,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_archaeology"),
                () -> switchTab(Tab.ARCHAEOLOGY));
    }

    private void switchTab(Tab tab) {
        if (this.activeTab == tab) return;
        this.activeTab = tab;
        this.introTabBtn.setToggled(tab == Tab.INTRO);
        this.archaeologyTabBtn.setToggled(tab == Tab.ARCHAEOLOGY);
        syncPageIndicator();
    }

    public void setTable(ResourceLocation tableId, List<ItemGridPanel.GridItem> items,
                         double totalWeight, int parsedCount, int totalCount, boolean approximate) {
        this.parsedCount = parsedCount;
        this.totalCount = totalCount;
        this.activeTab = Tab.INTRO;
        this.introTabBtn.setToggled(true);
        this.archaeologyTabBtn.setToggled(false);
        this.gridPanel.setTable(items, totalWeight, approximate);
        this.gridPanel.resetPage();
        this.detailPanel.setData(tableId, parsedCount, totalCount, items);
        syncPageIndicator();
    }

    public BookmarkToggleButton getIntroTabButton() {
        return introTabBtn;
    }

    public BookmarkToggleButton getArchaeologyTabButton() {
        return archaeologyTabBtn;
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = layout.rightPageX() + 8;

        // 进度文字
        Component progress = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.progress_items", parsedCount, totalCount);
        guiGraphics.drawString(font, progress, leftX,
                layout.rightPageY() + JournalLayout.GRID_PROGRESS_Y, 0x5A422C, false);

        // Tab按钮
        introTabBtn.render(guiGraphics, mouseX, mouseY, 0f);
        archaeologyTabBtn.render(guiGraphics, mouseX, mouseY, 0f);

        if (activeTab == Tab.INTRO) {
            detailPanel.render(guiGraphics, font, mouseX, mouseY);
            if (detailPanel.pageCount() > 1) {
                pageIndicator.render(guiGraphics, font);
            }
        } else {
            gridPanel.render(guiGraphics, font, mouseX, mouseY);
            pageIndicator.render(guiGraphics, font);
        }
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        if (introTabBtn.isMouseOver(mouseX, mouseY)) return true;
        if (archaeologyTabBtn.isMouseOver(mouseX, mouseY)) return true;
        return gridPanel.containsMouse(mouseX, mouseY);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (introTabBtn.isMouseOver(mouseX, mouseY)) {
            introTabBtn.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (archaeologyTabBtn.isMouseOver(mouseX, mouseY)) {
            archaeologyTabBtn.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return false;
    }

    public void handleScroll(double scrollY) {
        if (pageCount() > 1) {
            changePage(scrollY < 0.0 ? 1 : -1);
        }
    }

    public int pageCount() {
        return activeTab == Tab.ARCHAEOLOGY ? gridPanel.pageCount() : detailPanel.pageCount();
    }

    public int getPage() {
        return activeTab == Tab.ARCHAEOLOGY ? gridPanel.getPage() : detailPanel.getPage();
    }

    public void changePage(int delta) {
        if (activeTab == Tab.ARCHAEOLOGY) {
            gridPanel.changePage(delta);
        } else {
            detailPanel.changePage(delta);
        }
        syncPageIndicator();
    }

    public void resetPage() {
        gridPanel.resetPage();
        detailPanel.resetPage();
        syncPageIndicator();
    }

    /** 切换到指定Tab（不触发按钮回调） */
    public void setActiveTab(Tab tab) {
        if (this.activeTab == tab) return;
        this.activeTab = tab;
        this.introTabBtn.setToggled(tab == Tab.INTRO);
        this.archaeologyTabBtn.setToggled(tab == Tab.ARCHAEOLOGY);
        syncPageIndicator();
    }

    /** 获取当前激活的Tab */
    public Tab getActiveTab() {
        return activeTab;
    }

    /** 设置当前标签页页码（resize 后恢复页码用） */
    public void setPage(int page) {
        if (this.activeTab == Tab.ARCHAEOLOGY) {
            this.gridPanel.setPage(page);
        } else {
            this.detailPanel.setPage(page);
        }
        syncPageIndicator();
    }

    public boolean isIntroActive() {
        return activeTab == Tab.INTRO;
    }

    public boolean isArchaeologyActive() {
        return activeTab == Tab.ARCHAEOLOGY;
    }

    private void syncPageIndicator() {
        if (this.activeTab == Tab.ARCHAEOLOGY) {
            this.pageIndicator.setPage(this.gridPanel.getPage(), this.gridPanel.pageCount());
        } else {
            this.pageIndicator.setPage(this.detailPanel.getPage(), this.detailPanel.pageCount());
        }
    }

    @Nullable
    public ItemStack getTooltipStack(double mouseX, double mouseY) {
        if (this.activeTab != Tab.ARCHAEOLOGY) {
            return null;
        }
        return this.gridPanel.getTooltipStack(mouseX, mouseY);
    }
}
