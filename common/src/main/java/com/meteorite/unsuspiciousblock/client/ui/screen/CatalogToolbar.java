package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalControls;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 目录查询、排序与 kit 控件装配；搜索草稿按防抖窗口提交。 */
public class CatalogToolbar {
    // 搜索输入防抖窗口：逐字符提交会重建整份目录投影，200ms 内连打只提交一次
    private static final long SEARCH_DEBOUNCE_MILLIS = 200L;

    // 状态
    private JournalSearchQuery currentSearch = JournalSearchQuery.EMPTY;
    private CatalogSorter.SortOrder currentSortOrder = CatalogSorter.SortOrder.DEFAULT;
    private boolean sortDescending = false;
    private boolean searchExpanded = false;
    private boolean hideLocked = false;
    // 本次展开需要在控件创建后把宿主焦点交给搜索框（点击放大镜、C 键带物品打开）
    private boolean focusSearchOnCreate = false;
    // 已创建但尚未兑现的宿主焦点请求；宿主在鼠标事件尾部或重建后兑现并清理
    private boolean searchFocusRequested = false;
    // 防抖中的搜索草稿；null 表示当前没有未提交输入
    @Nullable
    private String pendingSearchText;
    private long pendingSearchChangedAt;
    private int horizontalOffset;
    private int expandedSearchFieldWidth = JournalLayout.SEARCH_FIELD_WIDTH;

    // widget 引用
    private UiControl sortButton;
    private UiControl sortOrderButton;
    private UiControl hideLockedButton;
    private UiTextInput searchField;

    // 回调
    private final Runnable onContentChanged;
    private final Runnable onLayoutChanged;

    public CatalogToolbar(Runnable onContentChanged, Runnable onLayoutChanged) {
        this.onContentChanged = onContentChanged;
        this.onLayoutChanged = onLayoutChanged;
    }

    // —— 状态访问器 ——

    public CatalogSorter.SortOrder currentSortOrder() {
        return currentSortOrder;
    }

    public boolean sortDescending() {
        return sortDescending;
    }

    public boolean searchExpanded() {
        return searchExpanded;
    }

    public void setCurrentSortOrder(CatalogSorter.SortOrder order) {
        this.currentSortOrder = order;
    }

    public void setSortDescending(boolean descending) {
        this.sortDescending = descending;
    }

    public void setCurrentSearch(JournalSearchQuery search) {
        this.currentSearch = search;
        // 从快照/外部恢复状态时丢弃尚未提交的草稿，避免旧输入在新状态下迟到生效
        this.pendingSearchText = null;
        if (searchField != null) {
            searchField.setOnValueChanged(null);
            searchField.setValue(search.rawQuery());
            searchField.setOnValueChanged(() -> onSearchChanged(searchField.value()));
        }
    }

    public void setSearchExpanded(boolean expanded) {
        this.searchExpanded = expanded;
        if (!expanded) {
            this.pendingSearchText = null;
            this.searchFocusRequested = false;
        }
    }

    public boolean hideLocked() {
        return hideLocked;
    }

    public void setHideLocked(boolean hideLocked) {
        this.hideLocked = hideLocked;
    }

    public UiTextInput searchField() {
        return searchField;
    }

    public boolean isSearchFocused() {
        return this.searchExpanded && this.searchField != null && this.searchField.isFocused();
    }

    // —— 搜索输入的防抖与焦点请求 ——

    /** 含未提交草稿的当前搜索：宿主在目录动作前一律用它，避免读到防抖窗口内的旧值 */
    public JournalSearchQuery effectiveSearch() {
        return this.pendingSearchText == null
                ? this.currentSearch : JournalSearchQuery.parse(this.pendingSearchText);
    }

    CatalogQueryState queryState() {
        return new CatalogQueryState(this.effectiveSearch().rawQuery(), this.currentSortOrder,
                this.sortDescending, this.hideLocked);
    }

    /** 防抖到点才提交搜索输入；由宿主逐帧调用 */
    public void flushPendingSearchIfDue() {
        if (this.pendingSearchText == null
                || System.currentTimeMillis() - this.pendingSearchChangedAt < SEARCH_DEBOUNCE_MILLIS) {
            return;
        }
        commitPendingSearch();
    }

    /** 立即提交未生效的搜索输入（resize 快照、退出子屏、其它目录动作前） */
    public void flushPendingSearch() {
        if (this.pendingSearchText != null) {
            commitPendingSearch();
        }
    }

    // 提交草稿：先落定查询再重建，保证重建读到的就是同一份输入
    private void commitPendingSearch() {
        String text = this.pendingSearchText;
        this.pendingSearchText = null;
        this.currentSearch = JournalSearchQuery.parse(text);
        this.onContentChanged.run();
    }

    /** 是否存在等待宿主兑现的搜索框焦点请求 */
    public boolean isSearchFocusRequested() {
        return this.searchFocusRequested;
    }

    /** 作废未兑现的焦点请求：模态打开、收起搜索、切页与控件重建后由宿主调用 */
    public void clearSearchFocusRequest() {
        this.searchFocusRequested = false;
    }

    // —— 事件处理 ——

    /** 搜索框内容变化：只登记草稿，等防抖窗口到点或宿主显式提交 */
    public void onSearchChanged(String text) {
        this.pendingSearchText = text;
        this.pendingSearchChangedAt = System.currentTimeMillis();
    }

    /** 切换搜索框展开/收起 */
    public void toggleSearch() {
        this.searchExpanded = !this.searchExpanded;
        if (this.searchExpanded) {
            this.focusSearchOnCreate = true;
        } else {
            this.setCurrentSearch(JournalSearchQuery.EMPTY);
            this.pendingSearchText = null;
            this.searchFocusRequested = false;
        }
        this.onLayoutChanged.run();
    }

    /** 循环切换排序方式 */
    public void cycleSortOrder() {
        this.currentSortOrder = this.currentSortOrder.next();
        if (this.sortButton != null) {
            JournalControls.configureIcon(sortButton, sortOrderIcon(currentSortOrder),
                    CatalogSorter.sortOrderTooltip(currentSortOrder), this::cycleSortOrder);
        }
        this.onContentChanged.run();
    }

    /** 切换正序/倒序 */
    public void toggleSortDirection() {
        this.sortDescending = !this.sortDescending;
        if (this.sortOrderButton != null) {
            JournalControls.configureIcon(sortOrderButton, sortDirectionIcon(sortDescending),
                    CatalogSorter.sortDirectionTooltip(sortDescending), this::toggleSortDirection);
        }
        this.onContentChanged.run();
    }

    /** 切换是否隐藏未解锁条目 */
    public void toggleHideLocked() {
        this.hideLocked = !this.hideLocked;
        if (this.hideLockedButton != null) {
            JournalControls.configureIcon(hideLockedButton, hideLockedIcon(hideLocked),
                    hideLockedTooltip(hideLocked), this::toggleHideLocked);
        }
        this.onContentChanged.run();
    }

    private static JournalControls.Icon sortOrderIcon(CatalogSorter.SortOrder order) {
        return switch (order) {
            case DEFAULT -> JournalControls.Icon.SORT_DEFAULT;
            case NAME -> JournalControls.Icon.SORT_NAME;
            case UNLOCK -> JournalControls.Icon.SORT_UNLOCK;
            case ITEM_COUNT -> JournalControls.Icon.SORT_ITEM_COUNT;
            case FAVORITE -> JournalControls.Icon.SORT_FAVORITE;
            case UPDATE_TIME -> JournalControls.Icon.SORT_UPDATE_TIME;
        };
    }

    private static JournalControls.Icon sortDirectionIcon(boolean descending) {
        return descending ? JournalControls.Icon.ARROW_DOWN : JournalControls.Icon.ARROW_UP;
    }

    // 眼睛表示正在显示未解锁条目，划线眼睛表示正在隐藏
    private static JournalControls.Icon hideLockedIcon(boolean hideLocked) {
        return hideLocked ? JournalControls.Icon.HIDE_LOCKED : JournalControls.Icon.SHOW_LOCKED;
    }

    // 隐藏未解锁按钮的 tooltip
    private static Component hideLockedTooltip(boolean hideLocked) {
        String key = hideLocked
                ? "screen.unsuspiciousblock.archaeology_journal.hide_locked.on"
                : "screen.unsuspiciousblock.archaeology_journal.hide_locked.off";
        return Component.translatable(key);
    }

    public void setSortingEnabled(boolean enabled) {
        boolean visible = enabled && !this.searchExpanded;
        if (this.sortButton != null) {
            this.sortButton.setEnabled(visible);
            this.sortButton.setVisible(visible);
        }
        if (this.sortOrderButton != null) {
            this.sortOrderButton.setEnabled(visible);
            this.sortOrderButton.setVisible(visible);
        }
    }

    public void setHorizontalOffset(int horizontalOffset) {
        this.horizontalOffset = horizontalOffset;
    }

    public void setExpandedSearchFieldWidth(int expandedSearchFieldWidth) {
        this.expandedSearchFieldWidth = Math.max(1, expandedSearchFieldWidth);
    }

    public void setCategoryHomeMode(boolean categoryHome) {
        setSortingEnabled(!categoryHome);
        if (this.hideLockedButton != null) {
            boolean visible = !categoryHome && !this.searchExpanded;
            this.hideLockedButton.setEnabled(visible);
            this.hideLockedButton.setVisible(visible);
        }
    }

    // —— Widget 创建 ——

    /**
     * 创建所有目录工具栏 widget，通过 adder 回调注册到 Screen。
     *
     * @param screen    ArchaeologyJournalScreen，用于通过 registerWidget 注册 widget
     * @param bookLayout 书页布局
     * @param font       字体
     */
    public void createWidgets(ArchaeologyJournalScreen screen, JournalBookBackground.BookLayout bookLayout, Font font) {
        int toolbarY = bookLayout.leftPageY() + JournalLayout.TOOLBAR_Y;
        int toolbarX = bookLayout.leftPageX()
                + (bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                + JournalLayout.CATALOG_X_OFFSET + this.horizontalOffset;

        // 搜索规则分成语法与用途两行，缩短 tooltip 宽度并区分信息层级。
        String tooltipBase = "screen.unsuspiciousblock.archaeology_journal.search_tooltip";
        UiControl searchToggleButton = JournalControls.iconButton(
                toolbarX, toolbarY,
                JournalLayout.SEARCH_ICON_SIZE,
                this.searchExpanded ? JournalControls.Icon.CLOSE : JournalControls.Icon.SEARCH,
                List.of(
                        Component.translatable(tooltipBase).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                        Component.translatable(tooltipBase + ".rule_table.syntax").withStyle(ChatFormatting.AQUA),
                        tooltipDescription(tooltipBase + ".rule_table.description"),
                        Component.translatable(tooltipBase + ".rule_mod.syntax").withStyle(ChatFormatting.AQUA),
                        tooltipDescription(tooltipBase + ".rule_mod.description"),
                        Component.translatable(tooltipBase + ".rule_type.syntax").withStyle(ChatFormatting.AQUA),
                        tooltipDescription(tooltipBase + ".rule_type.description"),
                        Component.translatable(tooltipBase + ".rule_item.syntax").withStyle(ChatFormatting.AQUA),
                        tooltipDescription(tooltipBase + ".rule_item.description"),
                        Component.translatable(tooltipBase + ".rule_tag.syntax").withStyle(ChatFormatting.AQUA),
                        tooltipDescription(tooltipBase + ".rule_tag.description")),
                this::toggleSearch
        );
        screen.registerWidget(searchToggleButton);

        // 排序图标按钮
        int sortOrderX = this.searchExpanded
                ? toolbarX + JournalLayout.SEARCH_ICON_SIZE + this.expandedSearchFieldWidth + JournalLayout.TOOLBAR_GAP
                : toolbarX + JournalLayout.SEARCH_ICON_SIZE + JournalLayout.TOOLBAR_GAP;
        this.sortButton = JournalControls.iconButton(
                sortOrderX, toolbarY,
                JournalLayout.SORT_ICON_SIZE,
                sortOrderIcon(this.currentSortOrder),
                CatalogSorter.sortOrderTooltip(this.currentSortOrder),
                this::cycleSortOrder
        );
        screen.registerWidget(this.sortButton);

        // 排序方向按钮
        int sortDirectionX = sortOrderX + JournalLayout.SORT_ICON_SIZE + JournalLayout.TOOLBAR_GAP;
        this.sortOrderButton = JournalControls.iconButton(
                sortDirectionX, toolbarY,
                JournalLayout.SORT_ICON_SIZE,
                sortDirectionIcon(this.sortDescending),
                CatalogSorter.sortDirectionTooltip(this.sortDescending),
                this::toggleSortDirection
        );
        screen.registerWidget(this.sortOrderButton);

        // 隐藏未解锁条目按钮
        int hideLockedX = sortDirectionX + JournalLayout.SORT_ICON_SIZE + JournalLayout.TOOLBAR_GAP;
        this.hideLockedButton = JournalControls.iconButton(
                hideLockedX, toolbarY,
                JournalLayout.SORT_ICON_SIZE,
                hideLockedIcon(this.hideLocked),
                hideLockedTooltip(this.hideLocked),
                this::toggleHideLocked
        );
        screen.registerWidget(this.hideLockedButton);

        if (searchField == null) {
            searchField = new UiTextInput(() -> UnsuspiciousUiTheme.PARCHMENT, font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_placeholder"))
                    .setMaxLength(256).setValue(currentSearch.rawQuery());
            searchField.setOnValueChanged(() -> onSearchChanged(searchField.value()));
        }
        searchField.setVisible(searchExpanded);
        searchField.setBounds(toolbarX + JournalLayout.SEARCH_ICON_SIZE, toolbarY,
                Math.max(0, expandedSearchFieldWidth), JournalLayout.SEARCH_BAR_HEIGHT);
        screen.registerWidget(searchField);
        searchFocusRequested = focusSearchOnCreate;
        focusSearchOnCreate = false;
    }

    private static Component tooltipDescription(String translationKey) {
        return Component.literal("  ")
                .append(Component.translatable(translationKey).withStyle(ChatFormatting.GRAY));
    }

}
