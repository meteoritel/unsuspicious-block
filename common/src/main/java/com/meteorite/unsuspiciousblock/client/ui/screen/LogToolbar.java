package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.panel.LogPanel;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalControls;
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
    private UiControl sortDirBtn;
    private UiControl groupBtn;
    private UiControl batchSelectBtn;
    private UiControl executeBatchBtn;
    private Runnable onToggleBatch = () -> { };
    private final List<UiControl> toolbarButtons = new ArrayList<>();

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
    public void toggleSortDirection(LogPanel logPanel) {
        this.sortDescending = !this.sortDescending;
        logPanel.setSortDescending(this.sortDescending);
        if (this.sortDirBtn != null) {
            JournalControls.configureIcon(sortDirBtn, sortDirectionIcon(sortDescending),
                    sortDirectionTooltip(sortDescending), () -> toggleSortDirection(logPanel));
        }
    }

    /** 循环切换分组方式 */
    public void cycleGroupMode(LogPanel logPanel) {
        this.groupMode = this.groupMode.next();
        logPanel.setGroupMode(this.groupMode);
        if (this.groupBtn != null) {
            JournalControls.configureIcon(groupBtn, groupModeIcon(groupMode),
                    LogGrouper.groupModeTooltip(groupMode), () -> cycleGroupMode(logPanel));
        }
    }

    // —— 图标 / tooltip —— 排序方向专用，从原 LogSorter 内联

    public static JournalControls.Icon sortDirectionIcon(boolean descending) {
        return descending ? JournalControls.Icon.ARROW_DOWN : JournalControls.Icon.ARROW_UP;
    }

    public static Component sortDirectionTooltip(boolean descending) {
        String key = descending
                ? "screen.unsuspiciousblock.archaeology_journal.sort.descending"
                : "screen.unsuspiciousblock.archaeology_journal.sort.ascending";
        return Component.translatable(key);
    }

    // 将日志分组方式映射到对应的图集图标
    public static JournalControls.Icon groupModeIcon(LogGrouper.GroupMode mode) {
        return switch (mode) {
            case TIME -> JournalControls.Icon.CLOCK;
            case DIMENSION -> JournalControls.Icon.DIMENSION;
            case BIOME -> JournalControls.Icon.BIOME;
            case NOTED -> JournalControls.Icon.PENCIL;
        };
    }

    // —— Widget 创建 ——

    /**
     * 在 Screen 上创建日志工具栏 widget。
     * visible 由 {@link #syncVisibility} 控制。
     */
    public void createWidgets(ArchaeologyJournalScreen screen, JournalBookBackground.BookLayout bookLayout,
                              LogPanel logPanel,
                              Runnable onOpenRetention, Runnable onToggleBatch, Runnable onExecuteBatch,
                              Runnable onClearTable, Runnable onClearAll) {
        logPanel.setSortDescending(this.sortDescending);
        logPanel.setGroupMode(this.groupMode);
        this.onToggleBatch = onToggleBatch;
        this.toolbarButtons.clear();
        int logToolbarY = bookLayout.rightPageY() + JournalLayout.LOG_LIST_LABEL_Y
                + (JournalLayout.LOG_SORT_ICON_SIZE - JournalLayout.SEARCH_QUICK_BAR_HEIGHT) / 2;
        int logToolbarLeftX = bookLayout.rightPageX() + 8;
        int logToolbarRightX = bookLayout.rightPageX() + bookLayout.rightPageWidth() - 8;

        // 排序方向按钮（最右侧）
        this.sortDirBtn = JournalControls.iconButton(
                logToolbarRightX - JournalLayout.LOG_SORT_ICON_SIZE,
                logToolbarY,
                JournalLayout.LOG_SORT_ICON_SIZE,
                sortDirectionIcon(this.sortDescending),
                sortDirectionTooltip(this.sortDescending),
                () -> toggleSortDirection(logPanel));
        registerToolbarButton(screen, this.sortDirBtn);

        // 分组按钮
        int logGroupX = logToolbarRightX - JournalLayout.LOG_SORT_ICON_SIZE - JournalLayout.LOG_TOOLBAR_GAP
                - JournalLayout.LOG_GROUP_ICON_SIZE;
        this.groupBtn = JournalControls.iconButton(
                logGroupX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                groupModeIcon(this.groupMode),
                LogGrouper.groupModeTooltip(this.groupMode),
                () -> cycleGroupMode(logPanel));
        registerToolbarButton(screen, this.groupBtn);

        // 清空操作与右侧视图设置排成一行。
        int clearAllX = logGroupX - JournalLayout.LOG_TOOLBAR_GAP - JournalLayout.LOG_GROUP_ICON_SIZE;
        UiControl clearAllBtn = JournalControls.iconButton(
                clearAllX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                JournalControls.Icon.TRASH_FULL,
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.all_tooltip"),
                onClearAll);
        registerToolbarButton(screen, clearAllBtn);

        int clearTableX = clearAllX - JournalLayout.LOG_TOOLBAR_GAP - JournalLayout.LOG_GROUP_ICON_SIZE;
        UiControl clearTableBtn = JournalControls.iconButton(
                clearTableX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                JournalControls.Icon.TRASH_EMPTY,
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.table_tooltip"),
                onClearTable);
        registerToolbarButton(screen, clearTableBtn);

        // 保留配置、批量选择、执行三个固定槽位，批量模式切换时布局不会跳动。
        int executeBatchX = logToolbarLeftX
                + (JournalLayout.LOG_GROUP_ICON_SIZE + JournalLayout.LOG_TOOLBAR_GAP) * 2;
        this.executeBatchBtn = JournalControls.iconButton(
                executeBatchX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                JournalControls.Icon.CHECK,
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.execute_batch_tooltip"),
                onExecuteBatch);
        registerToolbarButton(screen, this.executeBatchBtn);

        int batchSelectX = logToolbarLeftX + JournalLayout.LOG_GROUP_ICON_SIZE
                + JournalLayout.LOG_TOOLBAR_GAP;
        this.batchSelectBtn = JournalControls.iconButton(
                batchSelectX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                JournalControls.Icon.SQUARE,
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.batch_tooltip"),
                onToggleBatch);
        registerToolbarButton(screen, this.batchSelectBtn);

        UiControl retentionBtn = JournalControls.iconButton(
                logToolbarLeftX, logToolbarY,
                JournalLayout.LOG_GROUP_ICON_SIZE,
                JournalControls.Icon.GEAR,
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_retention.open_tooltip"),
                onOpenRetention);
        registerToolbarButton(screen, retentionBtn);
    }

    // 所有日志工具栏按钮均从隐藏状态注册，只允许 syncVisibility 决定是否显示。
    private void registerToolbarButton(ArchaeologyJournalScreen screen, UiControl button) {
        button.setVisible(false);
        this.toolbarButtons.add(button);
        screen.registerWidget(button);
    }

    /** 同步日志工具栏 widget 可见性 */
    public void syncVisibility(boolean toolbarVisible, boolean batchSelectionMode, int selectedEntryCount) {
        this.toolbarButtons.forEach(button -> button.setVisible(toolbarVisible));
        if (batchSelectBtn != null) {
            JournalControls.configureIcon(batchSelectBtn, batchSelectionMode ? JournalControls.Icon.GROUP : JournalControls.Icon.SQUARE,
                    Component.translatable(batchSelectionMode
                            ? "screen.unsuspiciousblock.archaeology_journal.log_delete.batch_exit_tooltip"
                            : "screen.unsuspiciousblock.archaeology_journal.log_delete.batch_tooltip"), onToggleBatch::run);
        }
        if (this.executeBatchBtn != null) {
            this.executeBatchBtn.setVisible(toolbarVisible && batchSelectionMode);
            this.executeBatchBtn.setEnabled(selectedEntryCount > 0);
        }
    }

}
