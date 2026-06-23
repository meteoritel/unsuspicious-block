package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryLogRef;
import com.meteorite.unsuspiciousblock.client.ui.widget.BookmarkToggleButton;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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

    // 书签尺寸与间距
    private static final int BOOKMARK_HEIGHT = 22;
    private static final int BOOKMARK_GAP = 4;
    private static final int BOOKMARK_TOP_OFFSET = 20; // 距右页顶部偏移

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

        // 书签附着在书本右边缘，垂直排列
        int bookRightEdge = layout.rightPageX() + layout.rightPageWidth();
        int startY = layout.rightPageY() + BOOKMARK_TOP_OFFSET;

        this.introTabBtn = new BookmarkToggleButton(
                bookRightEdge, startY,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_intro")
                        .withStyle(ChatFormatting.YELLOW),
                () -> switchTab(Tab.INTRO));
        this.introTabBtn.setToggled(true);

        this.archaeologyTabBtn = new BookmarkToggleButton(
                bookRightEdge, startY + BOOKMARK_HEIGHT + BOOKMARK_GAP,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_archaeology")
                        .withStyle(ChatFormatting.YELLOW),
                () -> switchTab(Tab.ARCHAEOLOGY));

        this.logTabBtn = new BookmarkToggleButton(
                bookRightEdge, startY + (BOOKMARK_HEIGHT + BOOKMARK_GAP) * 2,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_log")
                        .withStyle(ChatFormatting.YELLOW),
                () -> switchTab(Tab.LOG));
    }

    private void switchTab(Tab tab) {
        setActiveTab(tab);
    }

    public void setTable(@Nullable ResourceLocation tableId, List<ItemGridPanel.GridItem> items,
                         int parsedCount, int totalCount,
                         @Nullable ArchaeologyEntryLogRef logRef) {
        boolean sameTable = Objects.equals(this.currentTableId, tableId);
        int archaeologyPage = this.gridPanel.getPage();
        int introPage = this.detailPanel.getPage();
        int logListPage = this.logPanel.getPage();
        LogMode savedLogMode = this.logMode;
        UUID savedSelectedLogEntryId = this.selectedLogEntryId;

        this.gridPanel.setTable(items);
        this.detailPanel.setData(tableId, parsedCount, totalCount, items);
        this.logPanel.setData(logRef);

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
        this.logPanel.setSelectedEntryId(entryId);
        ExcavationLogEntry selectedEntry = entryId != null ? this.logPanel.findEntry(entryId) : null;
        if (detailMode && selectedEntry != null) {
            this.logMode = LogMode.DETAIL;
            this.logDetailPanel.setEntry(selectedEntry, this.currentTableId);
        } else {
            this.logMode = LogMode.LIST;
            this.selectedLogEntryId = null;
            this.logDetailPanel.setEntry(null, null);
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
        PagePanel panel = activePanel();
        panel.render(guiGraphics, font, mouseX, mouseY);
        // ARCHAEOLOGY tab always shows page indicator; others only when multi-page
        if (this.activeTab == Tab.ARCHAEOLOGY || panel.pageCount() > 1) {
            this.pageIndicator.render(guiGraphics, font);
        }
    }

    /** 获取当前活跃的可分页面板 */
    private PagePanel activePanel() {
        return switch (this.activeTab) {
            case INTRO -> this.detailPanel;
            case ARCHAEOLOGY -> this.gridPanel;
            case LOG -> this.logMode == LogMode.DETAIL ? this.logDetailPanel : this.logPanel;
        };
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
        return activePanel().containsMouse(mouseX, mouseY);
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
            // 返回按钮由 IconButton widget 处理；这里仅处理复制坐标按钮点击
            if (this.logDetailPanel.handleClick(mouseX, mouseY)) {
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
        this.logDetailPanel.setEntry(clickedEntry, this.currentTableId);
        syncPageIndicator();
        return true;
    }

    public void handleScroll(double scrollY) {
        if (pageCount() > 1) {
            changePage(scrollY < 0.0 ? 1 : -1);
        }
    }

    public int pageCount() {
        return activePanel().pageCount();
    }

    public int getPage() {
        return activePanel().getPage();
    }

    public void changePage(int delta) {
        activePanel().changePage(delta);
        syncPageIndicator();
    }

    public void setActiveTab(Tab tab) {
        if (this.activeTab == tab) {
            return;
        }
        this.activeTab = tab;
        // 切换到日志页时刷新时间分组参考刻，避免每帧重算
        if (tab == Tab.LOG) {
            captureReferenceGameTime();
        }
        this.introTabBtn.setToggled(tab == Tab.INTRO);
        this.archaeologyTabBtn.setToggled(tab == Tab.ARCHAEOLOGY);
        this.logTabBtn.setToggled(tab == Tab.LOG);
        syncPageIndicator();
    }

    // 捕获当前游戏刻作为时间分组参考，仅在切换到日志页或屏幕初始化时调用
    public void captureReferenceGameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        long gameTime = minecraft.level != null ? minecraft.level.getGameTime() : 0L;
        this.logPanel.setReferenceGameTime(gameTime);
    }

    public Tab getActiveTab() {
        return this.activeTab;
    }

    public void setPage(int page) {
        activePanel().setPage(page);
        syncPageIndicator();
    }

    public boolean isShowingLogDetail() {
        return this.logMode == LogMode.DETAIL;
    }

    public LogPanel getLogPanel() {
        return this.logPanel;
    }

    public LogDetailPanel getLogDetailPanel() {
        return this.logDetailPanel;
    }

    @Nullable
    public UUID getSelectedLogEntryId() {
        return this.selectedLogEntryId;
    }

    private void syncPageIndicator() {
        PagePanel panel = activePanel();
        this.pageIndicator.setPage(panel.getPage(), panel.pageCount());
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

    // 渲染自定义按钮 tooltip（如日志条目的复制坐标按钮、返回按钮、详情页复制按钮），需在 super.render 之后调用
    public void renderTooltips(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        if (this.activeTab == Tab.LOG && this.logMode == LogMode.LIST) {
            this.logPanel.renderTooltips(guiGraphics, font, mouseX, mouseY);
        } else if (this.activeTab == Tab.LOG && this.logMode == LogMode.DETAIL) {
            this.logDetailPanel.renderTooltips(guiGraphics, font, mouseX, mouseY);
        }
    }
}
