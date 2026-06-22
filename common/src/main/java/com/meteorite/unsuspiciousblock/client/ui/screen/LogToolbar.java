package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.unsuspiciousblock.client.ui.widget.IconButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 日志工具栏管理器。
 * <p>
 * 管理日志页面的搜索/排序方向/分组 widget 组及状态，
 * 与 {@link CatalogToolbar} 遵循相同的设计模式。
 */
public class LogToolbar {

    // 状态
    private boolean sortDescending = true;
    private String searchText = "";
    private boolean searchExpanded = false;
    private LogGrouper.GroupMode groupMode = LogGrouper.GroupMode.TIME;

    // widget 引用
    private IconButton searchToggleBtn;
    private IconButton sortDirBtn;
    private IconButton groupBtn;
    private EditBox searchField;

    // 回调
    private final Runnable onRebuildWidgets;

    public LogToolbar(Runnable onRebuildWidgets) {
        this.onRebuildWidgets = onRebuildWidgets;
    }

    // —— 状态访问器 ——

    public boolean sortDescending() {
        return sortDescending;
    }

    public void setSortDescending(boolean descending) {
        this.sortDescending = descending;
    }

    public String searchText() {
        return searchText;
    }

    public void setSearchText(String text) {
        this.searchText = text;
    }

    public boolean searchExpanded() {
        return searchExpanded;
    }

    public void setSearchExpanded(boolean expanded) {
        this.searchExpanded = expanded;
    }

    public LogGrouper.GroupMode groupMode() {
        return groupMode;
    }

    public void setGroupMode(LogGrouper.GroupMode mode) {
        this.groupMode = mode;
    }

    public EditBox searchField() {
        return searchField;
    }

    public IconButton searchToggleBtn() {
        return searchToggleBtn;
    }

    public IconButton sortDirBtn() {
        return sortDirBtn;
    }

    public IconButton groupBtn() {
        return groupBtn;
    }

    // —— 事件处理 ——

    /** 切换日志搜索框展开/收起 */
    public void toggleSearch(RightPageContainer rightPage) {
        this.searchExpanded = !this.searchExpanded;
        if (!this.searchExpanded) {
            this.searchText = "";
            rightPage.getLogPanel().setSearchFilter("");
        }
        onRebuildWidgets.run();
    }

    /** 日志搜索框内容变化 */
    public void onSearchChanged(String text, RightPageContainer rightPage) {
        this.searchText = text;
        rightPage.getLogPanel().setSearchFilter(text);
    }

    /** 切换日志排序方向 */
    public void toggleSortDirection(RightPageContainer rightPage) {
        this.sortDescending = !this.sortDescending;
        rightPage.getLogPanel().setSortDescending(this.sortDescending);
        if (this.sortDirBtn != null) {
            this.sortDirBtn.setIconChar(sortDirectionIcon(this.sortDescending));
            this.sortDirBtn.setTooltip(sortDirectionTooltip(this.sortDescending));
        }
    }

    /** 循环切换分组方式 */
    public void cycleGroupMode(RightPageContainer rightPage) {
        this.groupMode = this.groupMode.next();
        rightPage.getLogPanel().setGroupMode(this.groupMode);
        if (this.groupBtn != null) {
            this.groupBtn.setIconChar(LogGrouper.groupModeIcon(this.groupMode));
            this.groupBtn.setTooltip(LogGrouper.groupModeTooltip(this.groupMode));
        }
    }

    /** 处理 ESC 键关闭搜索框，返回 true 表示已消费事件 */
    public boolean handleEsc(RightPageContainer rightPage) {
        if (this.searchExpanded) {
            this.searchExpanded = false;
            this.searchText = "";
            rightPage.getLogPanel().setSearchFilter("");
            onRebuildWidgets.run();
            return true;
        }
        return false;
    }

    // —— 图标 / tooltip —— 排序方向专用，从原 LogSorter 内联

    public static char sortDirectionIcon(boolean descending) {
        return descending ? '↓' : '↑';
    }

    public static Component sortDirectionTooltip(boolean descending) {
        String key = descending
                ? "screen.unsuspiciousblock.archaeology_journal.sort.descending"
                : "screen.unsuspiciousblock.archaeology_journal.sort.ascending";
        return Component.translatable(key);
    }

    // —— Widget 创建 ——

    /**
     * 在 Screen 上创建日志工具栏 widget。
     * visible 由 {@link #syncVisibility} 控制。
     */
    public void createWidgets(ArchaeologyJournalScreen screen, JournalBookBackground.BookLayout bookLayout, Font font,
                              boolean isLogListMode, RightPageContainer rightPage) {
        int logToolbarY = bookLayout.rightPageY() + JournalLayout.LOG_LIST_LABEL_Y
                + (JournalLayout.LOG_SEARCH_ICON_SIZE - JournalLayout.SEARCH_QUICK_BAR_HEIGHT) / 2;
        int logToolbarRightX = bookLayout.rightPageX() + bookLayout.rightPageWidth() - 8;

        // 搜索切换按钮
        this.searchToggleBtn = new IconButton(
                logToolbarRightX - JournalLayout.LOG_SEARCH_ICON_SIZE,
                logToolbarY,
                JournalLayout.LOG_SEARCH_ICON_SIZE,
                this.searchExpanded ? '✕' : '⌕',
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_search_tooltip"),
                () -> toggleSearch(rightPage));
        this.searchToggleBtn.visible = isLogListMode;
        screen.registerWidget(this.searchToggleBtn);

        // 排序方向按钮
        int logSortDirX = logToolbarRightX - JournalLayout.LOG_SEARCH_ICON_SIZE - JournalLayout.LOG_TOOLBAR_GAP
                - JournalLayout.LOG_SORT_ICON_SIZE;
        this.sortDirBtn = new IconButton(
                logSortDirX, logToolbarY,
                JournalLayout.LOG_SORT_ICON_SIZE,
                sortDirectionIcon(this.sortDescending),
                sortDirectionTooltip(this.sortDescending),
                () -> toggleSortDirection(rightPage));
        this.sortDirBtn.visible = isLogListMode;
        screen.registerWidget(this.sortDirBtn);

        // 分组按钮
        int logGroupX = logSortDirX - JournalLayout.LOG_GROUP_ICON_SIZE - JournalLayout.LOG_TOOLBAR_GAP;
        this.groupBtn = new IconButton(
                logGroupX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                LogGrouper.groupModeIcon(this.groupMode),
                LogGrouper.groupModeTooltip(this.groupMode),
                () -> cycleGroupMode(rightPage));
        this.groupBtn.visible = isLogListMode;
        screen.registerWidget(this.groupBtn);

        // 搜索框
        if (this.searchExpanded) {
            String savedText = this.searchText;
            // 搜索框宽度：从分组按钮左侧到搜索按钮右侧
            int logSearchFieldX = logGroupX - JournalLayout.LOG_TOOLBAR_GAP - JournalLayout.LOG_SEARCH_FIELD_WIDTH;
            this.searchField = new EditBox(font,
                    logSearchFieldX,
                    logToolbarY + (JournalLayout.SEARCH_QUICK_BAR_HEIGHT - JournalLayout.LOG_SEARCH_BAR_HEIGHT) / 2,
                    JournalLayout.LOG_SEARCH_FIELD_WIDTH,
                    JournalLayout.LOG_SEARCH_BAR_HEIGHT,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_search_placeholder"));
            this.searchField.setHint(Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_search_placeholder"));
            this.searchField.setMaxLength(50);
            this.searchField.setResponder(text -> onSearchChanged(text, rightPage));
            this.searchField.setValue(savedText);
            this.searchField.setFocused(true);
            this.searchField.visible = isLogListMode;
            screen.registerWidget(this.searchField);
        } else {
            this.searchField = new EditBox(font, 0, 0, 0, 0, Component.empty());
            this.searchField.setVisible(false);
            this.searchField.setResponder(text -> onSearchChanged(text, rightPage));
        }
    }

    /** 同步日志工具栏 widget 可见性 */
    public void syncVisibility(boolean isLogListMode, boolean logHasEntries) {
        boolean visible = isLogListMode && logHasEntries;
        if (this.searchToggleBtn != null) {
            this.searchToggleBtn.visible = visible;
        }
        if (this.sortDirBtn != null) {
            this.sortDirBtn.visible = visible;
        }
        if (this.groupBtn != null) {
            this.groupBtn.visible = visible;
        }
        if (this.searchField != null && this.searchExpanded) {
            this.searchField.visible = visible;
        }
    }

    /** 渲染日志搜索框展开态底色背景 */
    public void renderSearchBackground(net.minecraft.client.gui.GuiGraphics guiGraphics, boolean isLogListMode) {
        if (this.searchExpanded && this.searchField != null && this.searchField.visible && isLogListMode) {
            int logSearchBgX = this.searchField.getX() - 1;
            int logSearchBgY = this.searchField.getY() - 1;
            int logSearchBgW = this.searchField.getWidth() + 2;
            int logSearchBgH = this.searchField.getHeight() + 2;
            int borderColor = 0xFF8B6914;
            int borderLight = 0xFFB8943C;
            int bgColor = 0xFFD8C0A0;
            guiGraphics.fill(logSearchBgX, logSearchBgY, logSearchBgX + logSearchBgW, logSearchBgY + logSearchBgH, borderColor);
            guiGraphics.fill(logSearchBgX + 1, logSearchBgY + 1, logSearchBgX + logSearchBgW - 1, logSearchBgY + 2, borderLight);
            guiGraphics.fill(logSearchBgX + 1, logSearchBgY + 2, logSearchBgX + 2, logSearchBgY + logSearchBgH - 1, borderLight);
            guiGraphics.fill(logSearchBgX + 1, logSearchBgY + 1, logSearchBgX + logSearchBgW - 1, logSearchBgY + logSearchBgH - 1, bgColor);
        }
    }

    /** 渲染日志工具栏 tooltip */
    public void renderTooltips(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.sortDirBtn != null) {
            this.sortDirBtn.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.groupBtn != null) {
            this.groupBtn.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.searchToggleBtn != null) {
            this.searchToggleBtn.renderTooltip(guiGraphics, mouseX, mouseY);
        }
    }
}
