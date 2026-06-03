package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.widget.BookmarkToggleButton;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.TriggerType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 右侧页面容器 —— 通过三枚书签 Tab 切换介绍信息/考古信息/日志 */
public final class RightPageContainer {

    private static final int TAB_WIDTH = 40;
    private static final int TAB_HEIGHT = 16;
    private static final int TAB_GAP = 2;

    public enum Tab { INTRO, ARCHAEOLOGY, LOG }

    private enum LogMode { LIST, DETAIL }

    private final ItemGridPanel gridPanel;
    private final PageIndicator pageIndicator;
    private final DetailOverlayPanel detailPanel;
    private final LogPanel logPanel;
    private final LogDetailPanel logDetailPanel;
    private final BookmarkToggleButton introTabBtn;
    private final BookmarkToggleButton archaeologyTabBtn;
    private final BookmarkToggleButton logTabBtn;

    @Nullable
    private ResourceLocation currentTableId;
    private Tab activeTab = Tab.INTRO;
    private LogMode logMode = LogMode.LIST;
    @Nullable
    private UUID selectedLogEntryId;

    public RightPageContainer(JournalBookBackground.BookLayout layout) {
        this.gridPanel = new ItemGridPanel(layout);
        this.pageIndicator = new PageIndicator(layout);
        this.detailPanel = new DetailOverlayPanel(layout);
        this.logPanel = new LogPanel(layout);
        this.logDetailPanel = new LogDetailPanel(layout);

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
        if (this.activeTab == tab) {
            return;
        }
        this.activeTab = tab;
        this.introTabBtn.setToggled(tab == Tab.INTRO);
        this.archaeologyTabBtn.setToggled(tab == Tab.ARCHAEOLOGY);
        this.logTabBtn.setToggled(tab == Tab.LOG);
        syncPageIndicator();
    }

    public void setTable(@Nullable ResourceLocation tableId, List<ItemGridPanel.GridItem> items,
                         double totalWeight, int parsedCount, int totalCount, boolean approximate,
                         @Nullable Long firstUnlockedGameTime, @Nullable Long firstUnlockedDayTime,
                         @Nullable TriggerType firstUnlockTriggerType,
                         List<ExcavationLogEntry> logEntries) {
        boolean sameTable = Objects.equals(this.currentTableId, tableId);
        int archaeologyPage = this.gridPanel.getPage();
        int introPage = this.detailPanel.getPage();
        int logListPage = this.logPanel.getPage();
        LogMode savedLogMode = this.logMode;
        UUID savedSelectedLogEntryId = this.selectedLogEntryId;

        this.gridPanel.setTable(items, totalWeight, approximate);
        this.detailPanel.setData(tableId, parsedCount, totalCount, items);
        this.logPanel.setData(firstUnlockedGameTime, firstUnlockedDayTime, firstUnlockTriggerType, logEntries);

        if (sameTable) {
            this.gridPanel.setPage(archaeologyPage);
            this.detailPanel.setPage(introPage);
            this.logPanel.setPage(logListPage);
            restoreLogSelection(savedSelectedLogEntryId, savedLogMode == LogMode.DETAIL);
        } else {
            this.gridPanel.resetPage();
            restoreLogSelection(null, false);
        }

        this.currentTableId = tableId;
        syncPageIndicator();
    }

    public void restoreLogSelection(@Nullable UUID entryId, boolean detailMode) {
        this.selectedLogEntryId = entryId;
        ExcavationLogEntry selectedEntry = entryId != null ? this.logPanel.findEntry(entryId) : null;
        if (detailMode && selectedEntry != null) {
            this.logMode = LogMode.DETAIL;
            this.logDetailPanel.setEntry(selectedEntry);
        } else {
            this.logMode = LogMode.LIST;
            this.selectedLogEntryId = null;
            this.logDetailPanel.setEntry(null);
        }
        syncPageIndicator();
    }

    public BookmarkToggleButton getIntroTabButton() {
        return this.introTabBtn;
    }

    public BookmarkToggleButton getArchaeologyTabButton() {
        return this.archaeologyTabBtn;
    }

    public BookmarkToggleButton getLogTabButton() {
        return this.logTabBtn;
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        if (this.activeTab == Tab.INTRO) {
            this.detailPanel.render(guiGraphics, font, mouseX, mouseY);
            if (this.detailPanel.pageCount() > 1) {
                this.pageIndicator.render(guiGraphics, font);
            }
            return;
        }

        if (this.activeTab == Tab.ARCHAEOLOGY) {
            this.gridPanel.render(guiGraphics, font, mouseX, mouseY);
            this.pageIndicator.render(guiGraphics, font);
            return;
        }

        if (this.logMode == LogMode.DETAIL) {
            this.logDetailPanel.render(guiGraphics, font, mouseX, mouseY);
            if (this.logDetailPanel.pageCount() > 1) {
                this.pageIndicator.render(guiGraphics, font);
            }
            return;
        }

        this.logPanel.render(guiGraphics, font, mouseX, mouseY);
        if (this.logPanel.pageCount() > 1) {
            this.pageIndicator.render(guiGraphics, font);
        }
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        if (this.introTabBtn.isMouseOver(mouseX, mouseY)) {
            return true;
        }
        if (this.archaeologyTabBtn.isMouseOver(mouseX, mouseY)) {
            return true;
        }
        if (this.logTabBtn.isMouseOver(mouseX, mouseY)) {
            return true;
        }
        return switch (this.activeTab) {
            case INTRO -> this.detailPanel.containsMouse(mouseX, mouseY);
            case ARCHAEOLOGY -> this.gridPanel.containsMouse(mouseX, mouseY);
            case LOG -> this.logMode == LogMode.DETAIL
                    ? this.logDetailPanel.containsMouse(mouseX, mouseY)
                    : this.logPanel.containsMouse(mouseX, mouseY);
        };
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.introTabBtn.isMouseOver(mouseX, mouseY)) {
            this.introTabBtn.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (this.archaeologyTabBtn.isMouseOver(mouseX, mouseY)) {
            this.archaeologyTabBtn.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (this.logTabBtn.isMouseOver(mouseX, mouseY)) {
            this.logTabBtn.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (this.activeTab != Tab.LOG) {
            return false;
        }
        if (this.logMode == LogMode.DETAIL) {
            if (this.logDetailPanel.handleClick(mouseX, mouseY)) {
                restoreLogSelection(null, false);
                return true;
            }
            return false;
        }
        ExcavationLogEntry clickedEntry = this.logPanel.handleClick(mouseX, mouseY);
        if (clickedEntry == null) {
            return false;
        }
        this.selectedLogEntryId = clickedEntry.entryId();
        this.logMode = LogMode.DETAIL;
        this.logDetailPanel.setEntry(clickedEntry);
        syncPageIndicator();
        return true;
    }

    public void handleScroll(double scrollY) {
        if (pageCount() > 1) {
            changePage(scrollY < 0.0 ? 1 : -1);
        }
    }

    public int pageCount() {
        return switch (this.activeTab) {
            case INTRO -> this.detailPanel.pageCount();
            case ARCHAEOLOGY -> this.gridPanel.pageCount();
            case LOG -> this.logMode == LogMode.DETAIL ? this.logDetailPanel.pageCount() : this.logPanel.pageCount();
        };
    }

    public int getPage() {
        return switch (this.activeTab) {
            case INTRO -> this.detailPanel.getPage();
            case ARCHAEOLOGY -> this.gridPanel.getPage();
            case LOG -> this.logMode == LogMode.DETAIL ? this.logDetailPanel.getPage() : this.logPanel.getPage();
        };
    }

    public void changePage(int delta) {
        switch (this.activeTab) {
            case INTRO -> this.detailPanel.changePage(delta);
            case ARCHAEOLOGY -> this.gridPanel.changePage(delta);
            case LOG -> {
                if (this.logMode == LogMode.DETAIL) {
                    this.logDetailPanel.changePage(delta);
                } else {
                    this.logPanel.changePage(delta);
                }
            }
        }
        syncPageIndicator();
    }

    public void setActiveTab(Tab tab) {
        if (this.activeTab == tab) {
            return;
        }
        this.activeTab = tab;
        this.introTabBtn.setToggled(tab == Tab.INTRO);
        this.archaeologyTabBtn.setToggled(tab == Tab.ARCHAEOLOGY);
        this.logTabBtn.setToggled(tab == Tab.LOG);
        syncPageIndicator();
    }

    public Tab getActiveTab() {
        return this.activeTab;
    }

    public void setPage(int page) {
        switch (this.activeTab) {
            case INTRO -> this.detailPanel.setPage(page);
            case ARCHAEOLOGY -> this.gridPanel.setPage(page);
            case LOG -> {
                if (this.logMode == LogMode.DETAIL) {
                    this.logDetailPanel.setPage(page);
                } else {
                    this.logPanel.setPage(page);
                }
            }
        }
        syncPageIndicator();
    }

    public boolean isShowingLogDetail() {
        return this.logMode == LogMode.DETAIL;
    }

    @Nullable
    public UUID getSelectedLogEntryId() {
        return this.selectedLogEntryId;
    }

    private void syncPageIndicator() {
        switch (this.activeTab) {
            case INTRO -> this.pageIndicator.setPage(this.detailPanel.getPage(), this.detailPanel.pageCount());
            case ARCHAEOLOGY -> this.pageIndicator.setPage(this.gridPanel.getPage(), this.gridPanel.pageCount());
            case LOG -> {
                if (this.logMode == LogMode.DETAIL) {
                    this.pageIndicator.setPage(this.logDetailPanel.getPage(), this.logDetailPanel.pageCount());
                } else {
                    this.pageIndicator.setPage(this.logPanel.getPage(), this.logPanel.pageCount());
                }
            }
        }
    }

    @Nullable
    public ItemGridPanel.TooltipData getTooltipData(double mouseX, double mouseY) {
        if (this.activeTab == Tab.ARCHAEOLOGY) {
            return this.gridPanel.getTooltipData(mouseX, mouseY);
        }
        if (this.activeTab == Tab.LOG && this.logMode == LogMode.DETAIL) {
            ItemStack stack = this.logDetailPanel.getTooltipStack(mouseX, mouseY);
            return stack != null ? new ItemGridPanel.TooltipData(stack, null) : null;
        }
        return null;
    }
}
