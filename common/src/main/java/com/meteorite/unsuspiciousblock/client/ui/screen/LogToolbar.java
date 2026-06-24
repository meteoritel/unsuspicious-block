package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.unsuspiciousblock.client.ui.widget.IconButton;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * 日志工具栏管理器。
 * <p>
 * 管理日志页面的排序方向/分组 widget 组及状态，
 * 与 {@link CatalogToolbar} 遵循相同的设计模式。
 */
public class LogToolbar {

    // 状态
    private boolean sortDescending = true;
    private LogGrouper.GroupMode groupMode = LogGrouper.GroupMode.TIME;

    // widget 引用
    private IconButton sortDirBtn;
    private IconButton groupBtn;

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

    public LogGrouper.GroupMode groupMode() {
        return groupMode;
    }

    public void setGroupMode(LogGrouper.GroupMode mode) {
        this.groupMode = mode;
    }

    public IconButton sortDirBtn() {
        return sortDirBtn;
    }

    public IconButton groupBtn() {
        return groupBtn;
    }

    // —— 事件处理 ——

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
                + (JournalLayout.LOG_SORT_ICON_SIZE - JournalLayout.SEARCH_QUICK_BAR_HEIGHT) / 2;
        int logToolbarRightX = bookLayout.rightPageX() + bookLayout.rightPageWidth() - 8;

        // 排序方向按钮（最右侧）
        this.sortDirBtn = new IconButton(
                logToolbarRightX - JournalLayout.LOG_SORT_ICON_SIZE,
                logToolbarY,
                JournalLayout.LOG_SORT_ICON_SIZE,
                sortDirectionIcon(this.sortDescending),
                sortDirectionTooltip(this.sortDescending),
                () -> toggleSortDirection(rightPage));
        this.sortDirBtn.visible = isLogListMode;
        screen.registerWidget(this.sortDirBtn);

        // 分组按钮
        int logGroupX = logToolbarRightX - JournalLayout.LOG_SORT_ICON_SIZE - JournalLayout.LOG_TOOLBAR_GAP
                - JournalLayout.LOG_GROUP_ICON_SIZE;
        this.groupBtn = new IconButton(
                logGroupX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                LogGrouper.groupModeIcon(this.groupMode),
                LogGrouper.groupModeTooltip(this.groupMode),
                () -> cycleGroupMode(rightPage));
        this.groupBtn.visible = isLogListMode;
        screen.registerWidget(this.groupBtn);
    }

    /** 同步日志工具栏 widget 可见性 */
    public void syncVisibility(boolean isLogListMode, boolean logHasEntries) {
        boolean visible = isLogListMode && logHasEntries;
        if (this.sortDirBtn != null) {
            this.sortDirBtn.visible = visible;
        }
        if (this.groupBtn != null) {
            this.groupBtn.visible = visible;
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
    }
}
