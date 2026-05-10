package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.widget.BookmarkToggleButton;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState.ExcavationLogEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** 右侧页面容器 —— 通过三枚书签 Tab 切换介绍信息/考古信息/日志 */
public final class RightPageContainer {

    private static final int TAB_WIDTH = 40;
    private static final int TAB_HEIGHT = 16;
    private static final int TAB_GAP = 2;

    public enum Tab { INTRO, ARCHAEOLOGY, LOG }

    private final ItemGridPanel gridPanel;
    private final PageIndicator pageIndicator;
    private final DetailOverlayPanel detailPanel;
    private final LogPanel logPanel;
    private final BookmarkToggleButton introTabBtn;
    private final BookmarkToggleButton archaeologyTabBtn;
    private final BookmarkToggleButton logTabBtn;

    @Nullable
    private ResourceLocation currentTableId;
    private Tab activeTab = Tab.INTRO;

    public RightPageContainer(JournalBookBackground.BookLayout layout) {
        this.gridPanel = new ItemGridPanel(layout);
        this.pageIndicator = new PageIndicator(layout);
        this.detailPanel = new DetailOverlayPanel(layout);
        this.logPanel = new LogPanel(layout);

        int btnY = layout.rightPageY() + 2;
        int groupWidth = TAB_WIDTH * 3 + TAB_GAP * 2;
        int introBtnX = layout.rightPageX() + (layout.rightPageWidth() - groupWidth) / 2;
        int archBtnX = introBtnX + TAB_WIDTH + TAB_GAP;
        int logBtnX = archBtnX + TAB_WIDTH + TAB_GAP;

        this.introTabBtn = new BookmarkToggleButton(introBtnX, btnY, TAB_WIDTH, TAB_HEIGHT,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_intro"),
                () -> switchTab(Tab.INTRO));
        this.introTabBtn.setToggled(true);

        this.archaeologyTabBtn = new BookmarkToggleButton(archBtnX, btnY, TAB_WIDTH, TAB_HEIGHT,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_archaeology"),
                () -> switchTab(Tab.ARCHAEOLOGY));

        this.logTabBtn = new BookmarkToggleButton(logBtnX, btnY, TAB_WIDTH, TAB_HEIGHT,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_log"),
                () -> switchTab(Tab.LOG));
    }

    private void switchTab(Tab tab) {
        if (this.activeTab == tab) return;
        this.activeTab = tab;
        this.introTabBtn.setToggled(tab == Tab.INTRO);
        this.archaeologyTabBtn.setToggled(tab == Tab.ARCHAEOLOGY);
        this.logTabBtn.setToggled(tab == Tab.LOG);
        syncPageIndicator();
    }

    public void setTable(ResourceLocation tableId, List<ItemGridPanel.GridItem> items,
                         double totalWeight, int parsedCount, int totalCount, boolean approximate,
                         @Nullable Long firstUnlockedGameTime, @Nullable Long firstUnlockedDayTime,
                         List<ExcavationLogEntry> recentEntries) {
        boolean sameTable = Objects.equals(this.currentTableId, tableId);
        int archaeologyPage = this.gridPanel.getPage();
        int introPage = this.detailPanel.getPage();
        int logPage = this.logPanel.getPage();

        this.gridPanel.setTable(items, totalWeight, approximate);
        this.detailPanel.setData(tableId, parsedCount, totalCount, items);
        this.logPanel.setData(firstUnlockedGameTime, firstUnlockedDayTime, recentEntries);

        if (sameTable) {
            this.gridPanel.setPage(archaeologyPage);
            this.detailPanel.setPage(introPage);
            this.logPanel.setPage(logPage);
        } else {
            this.gridPanel.resetPage();
        }

        this.currentTableId = tableId;
        syncPageIndicator();
    }

    public BookmarkToggleButton getIntroTabButton() {
        return introTabBtn;
    }

    public BookmarkToggleButton getArchaeologyTabButton() {
        return archaeologyTabBtn;
    }

    public BookmarkToggleButton getLogTabButton() {
        return this.logTabBtn;
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        if (activeTab == Tab.INTRO) {
            detailPanel.render(guiGraphics, font, mouseX, mouseY);
            if (detailPanel.pageCount() > 1) {
                pageIndicator.render(guiGraphics, font);
            }
        } else if (activeTab == Tab.ARCHAEOLOGY) {
            gridPanel.render(guiGraphics, font, mouseX, mouseY);
            pageIndicator.render(guiGraphics, font);
        } else {
            logPanel.render(guiGraphics, font, mouseX, mouseY);
            if (logPanel.pageCount() > 1) {
                pageIndicator.render(guiGraphics, font);
            }
        }
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        if (introTabBtn.isMouseOver(mouseX, mouseY)) return true;
        if (archaeologyTabBtn.isMouseOver(mouseX, mouseY)) return true;
        if (logTabBtn.isMouseOver(mouseX, mouseY)) return true;
        return activeTab == Tab.LOG ? logPanel.containsMouse(mouseX, mouseY) : gridPanel.containsMouse(mouseX, mouseY);
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
        if (logTabBtn.isMouseOver(mouseX, mouseY)) {
            logTabBtn.mouseClicked(mouseX, mouseY, button);
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
        return switch (activeTab) {
            case INTRO -> detailPanel.pageCount();
            case ARCHAEOLOGY -> gridPanel.pageCount();
            case LOG -> logPanel.pageCount();
        };
    }

    public int getPage() {
        return switch (activeTab) {
            case INTRO -> detailPanel.getPage();
            case ARCHAEOLOGY -> gridPanel.getPage();
            case LOG -> logPanel.getPage();
        };
    }

    public void changePage(int delta) {
        switch (activeTab) {
            case INTRO -> detailPanel.changePage(delta);
            case ARCHAEOLOGY -> gridPanel.changePage(delta);
            case LOG -> logPanel.changePage(delta);
        }
        syncPageIndicator();
    }

    public void setActiveTab(Tab tab) {
        if (this.activeTab == tab) return;
        this.activeTab = tab;
        this.introTabBtn.setToggled(tab == Tab.INTRO);
        this.archaeologyTabBtn.setToggled(tab == Tab.ARCHAEOLOGY);
        this.logTabBtn.setToggled(tab == Tab.LOG);
        syncPageIndicator();
    }

    public Tab getActiveTab() {
        return activeTab;
    }

    public void setPage(int page) {
        switch (this.activeTab) {
            case INTRO -> this.detailPanel.setPage(page);
            case ARCHAEOLOGY -> this.gridPanel.setPage(page);
            case LOG -> this.logPanel.setPage(page);
        }
        syncPageIndicator();
    }

    private void syncPageIndicator() {
        switch (this.activeTab) {
            case INTRO -> this.pageIndicator.setPage(this.detailPanel.getPage(), this.detailPanel.pageCount());
            case ARCHAEOLOGY -> this.pageIndicator.setPage(this.gridPanel.getPage(), this.gridPanel.pageCount());
            case LOG -> this.pageIndicator.setPage(this.logPanel.getPage(), this.logPanel.pageCount());
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
