package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.unsuspiciousblock.client.ui.widget.IconButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

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
    private IconButton retentionBtn;
    private IconButton batchSelectBtn;
    private IconButton executeBatchBtn;
    private IconButton clearTableBtn;
    private IconButton clearAllBtn;
    private final List<IconButton> toolbarButtons = new ArrayList<>();

    public LogToolbar() {
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
    public void createWidgets(ArchaeologyJournalScreen screen, JournalBookBackground.BookLayout bookLayout,
                              RightPageContainer rightPage,
                              Runnable onOpenRetention, Runnable onToggleBatch, Runnable onExecuteBatch,
                              Runnable onClearTable, Runnable onClearAll) {
        this.toolbarButtons.clear();
        int logToolbarY = bookLayout.rightPageY() + JournalLayout.LOG_LIST_LABEL_Y
                + (JournalLayout.LOG_SORT_ICON_SIZE - JournalLayout.SEARCH_QUICK_BAR_HEIGHT) / 2;
        int logToolbarLeftX = bookLayout.rightPageX() + 8;
        int logToolbarRightX = bookLayout.rightPageX() + bookLayout.rightPageWidth() - 8;

        // 排序方向按钮（最右侧）
        this.sortDirBtn = new IconButton(
                logToolbarRightX - JournalLayout.LOG_SORT_ICON_SIZE,
                logToolbarY,
                JournalLayout.LOG_SORT_ICON_SIZE,
                sortDirectionIcon(this.sortDescending),
                sortDirectionTooltip(this.sortDescending),
                () -> toggleSortDirection(rightPage));
        registerToolbarButton(screen, this.sortDirBtn);

        // 分组按钮
        int logGroupX = logToolbarRightX - JournalLayout.LOG_SORT_ICON_SIZE - JournalLayout.LOG_TOOLBAR_GAP
                - JournalLayout.LOG_GROUP_ICON_SIZE;
        this.groupBtn = new IconButton(
                logGroupX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                LogGrouper.groupModeIcon(this.groupMode),
                LogGrouper.groupModeTooltip(this.groupMode),
                () -> cycleGroupMode(rightPage));
        registerToolbarButton(screen, this.groupBtn);

        // 清空操作与右侧视图设置排成一行。
        int clearAllX = logGroupX - JournalLayout.LOG_TOOLBAR_GAP - JournalLayout.LOG_GROUP_ICON_SIZE;
        this.clearAllBtn = new IconButton(
                clearAllX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                '!',
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.all_tooltip"),
                onClearAll);
        registerToolbarButton(screen, this.clearAllBtn);

        int clearTableX = clearAllX - JournalLayout.LOG_TOOLBAR_GAP - JournalLayout.LOG_GROUP_ICON_SIZE;
        this.clearTableBtn = new IconButton(
                clearTableX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                '×',
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.table_tooltip"),
                onClearTable);
        registerToolbarButton(screen, this.clearTableBtn);

        // 保留配置、批量选择、执行三个固定槽位，批量模式切换时布局不会跳动。
        int executeBatchX = logToolbarLeftX
                + (JournalLayout.LOG_GROUP_ICON_SIZE + JournalLayout.LOG_TOOLBAR_GAP) * 2;
        this.executeBatchBtn = new IconButton(
                executeBatchX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                '✓',
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.execute_batch_tooltip"),
                onExecuteBatch);
        registerToolbarButton(screen, this.executeBatchBtn);

        int batchSelectX = logToolbarLeftX + JournalLayout.LOG_GROUP_ICON_SIZE
                + JournalLayout.LOG_TOOLBAR_GAP;
        this.batchSelectBtn = new IconButton(
                batchSelectX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                '□',
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.batch_tooltip"),
                onToggleBatch);
        registerToolbarButton(screen, this.batchSelectBtn);

        int retentionX = logToolbarLeftX;
        this.retentionBtn = new IconButton(
                retentionX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                '⚙',
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_retention.open_tooltip"),
                onOpenRetention);
        registerToolbarButton(screen, this.retentionBtn);
    }

    // 所有日志工具栏按钮均从隐藏状态注册，只允许 syncVisibility 决定是否显示。
    private void registerToolbarButton(ArchaeologyJournalScreen screen, IconButton button) {
        button.visible = false;
        this.toolbarButtons.add(button);
        screen.registerWidget(button);
    }

    /** 同步日志工具栏 widget 可见性 */
    public void syncVisibility(boolean toolbarVisible, boolean batchSelectionMode, int selectedEntryCount) {
        this.toolbarButtons.forEach(button -> button.visible = toolbarVisible);
        if (this.batchSelectBtn != null) {
            this.batchSelectBtn.setIconChar(batchSelectionMode ? '■' : '□');
            this.batchSelectBtn.setTooltip(Component.translatable(batchSelectionMode
                    ? "screen.unsuspiciousblock.archaeology_journal.log_delete.batch_exit_tooltip"
                    : "screen.unsuspiciousblock.archaeology_journal.log_delete.batch_tooltip"));
        }
        if (this.executeBatchBtn != null) {
            this.executeBatchBtn.visible = toolbarVisible && batchSelectionMode;
            this.executeBatchBtn.active = selectedEntryCount > 0;
        }
    }

    /** 渲染日志工具栏 tooltip */
    public void renderTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        this.toolbarButtons.forEach(button -> button.renderTooltip(guiGraphics, mouseX, mouseY));
    }
}
