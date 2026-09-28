package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.client.ui.widget.IconButton;
import com.meteorite.unsuspiciousblock.client.ui.widget.ShadowlessEditBox;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 目录工具栏管理器。
 * <p>
 * 管理目录页面的搜索/排序 widget 组（搜索切换按钮、搜索框、排序按钮、排序方向按钮）
 * 及其状态（搜索查询、排序方式、排序方向、搜索展开状态）。
 * <p>
 * 搜索框的宿主焦点不在展开回调里当场设置：原版 {@code ContainerEventHandler.mouseClicked}
 * 会在按钮回调返回后才把焦点设回被点击的旧按钮。工具栏只登记"本次展开要聚焦的输入框"，
 * 由宿主在鼠标事件尾部或控件重建后兑现。
 */
public class CatalogToolbar {
    private static final int SEARCH_TEXT_HORIZONTAL_PADDING = 3;
    private static final int SEARCH_BORDER_COLOR = 0xFF8B6914;
    private static final int SEARCH_BG_COLOR = 0xFFD8C0A0;
    private static final int SEARCH_TEXT_COLOR = 0xFF5A3D23;
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
    private IconButton searchToggleButton;
    private IconButton sortButton;
    private IconButton sortOrderButton;
    private IconButton hideLockedButton;
    private EditBox searchField;
    private int searchBackgroundX;
    private int searchBackgroundY;

    // 回调
    private final Runnable onContentChanged;
    private final Runnable onLayoutChanged;

    public CatalogToolbar(Runnable onContentChanged, Runnable onLayoutChanged) {
        this.onContentChanged = onContentChanged;
        this.onLayoutChanged = onLayoutChanged;
    }

    // —— 状态访问器 ——

    public JournalSearchQuery currentSearch() {
        return currentSearch;
    }

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

    public EditBox searchField() {
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
            this.currentSearch = JournalSearchQuery.EMPTY;
            this.pendingSearchText = null;
            this.searchFocusRequested = false;
        }
        this.onLayoutChanged.run();
    }

    /** 循环切换排序方式 */
    public void cycleSortOrder() {
        this.currentSortOrder = this.currentSortOrder.next();
        if (this.sortButton != null) {
            this.sortButton.setIcon(sortOrderIcon(this.currentSortOrder));
            this.sortButton.setTooltip(CatalogSorter.sortOrderTooltip(this.currentSortOrder));
        }
        this.onContentChanged.run();
    }

    /** 切换正序/倒序 */
    public void toggleSortDirection() {
        this.sortDescending = !this.sortDescending;
        if (this.sortOrderButton != null) {
            this.sortOrderButton.setIcon(sortDirectionIcon(this.sortDescending));
            this.sortOrderButton.setTooltip(CatalogSorter.sortDirectionTooltip(this.sortDescending));
        }
        this.onContentChanged.run();
    }

    /** 切换是否隐藏未解锁条目 */
    public void toggleHideLocked() {
        this.hideLocked = !this.hideLocked;
        if (this.hideLockedButton != null) {
            this.hideLockedButton.setIcon(hideLockedIcon(this.hideLocked));
            this.hideLockedButton.setTooltip(hideLockedTooltip(this.hideLocked));
        }
        this.onContentChanged.run();
    }

    private static IconButton.Icon sortOrderIcon(CatalogSorter.SortOrder order) {
        return switch (order) {
            case DEFAULT -> IconButton.Icon.SORT_DEFAULT;
            case NAME -> IconButton.Icon.SORT_NAME;
            case UNLOCK -> IconButton.Icon.SORT_UNLOCK;
            case ITEM_COUNT -> IconButton.Icon.SORT_ITEM_COUNT;
            case FAVORITE -> IconButton.Icon.SORT_FAVORITE;
            case UPDATE_TIME -> IconButton.Icon.SORT_UPDATE_TIME;
        };
    }

    private static IconButton.Icon sortDirectionIcon(boolean descending) {
        return descending ? IconButton.Icon.ARROW_DOWN : IconButton.Icon.ARROW_UP;
    }

    // 眼睛表示正在显示未解锁条目，划线眼睛表示正在隐藏
    private static IconButton.Icon hideLockedIcon(boolean hideLocked) {
        return hideLocked ? IconButton.Icon.HIDE_LOCKED : IconButton.Icon.SHOW_LOCKED;
    }

    // 隐藏未解锁按钮的 tooltip
    private static Component hideLockedTooltip(boolean hideLocked) {
        String key = hideLocked
                ? "screen.unsuspiciousblock.archaeology_journal.hide_locked.on"
                : "screen.unsuspiciousblock.archaeology_journal.hide_locked.off";
        return Component.translatable(key);
    }

    /** 处理 ESC 键，返回 true 表示已消费事件 */
    public boolean handleEsc() {
        return false;
    }

    public void setSortingEnabled(boolean enabled) {
        boolean visible = enabled && !this.searchExpanded;
        if (this.sortButton != null) {
            this.sortButton.active = visible;
            this.sortButton.visible = visible;
        }
        if (this.sortOrderButton != null) {
            this.sortOrderButton.active = visible;
            this.sortOrderButton.visible = visible;
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
            this.hideLockedButton.active = visible;
            this.hideLockedButton.visible = visible;
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
        EditBox previousField = this.searchField;
        int toolbarY = bookLayout.leftPageY() + JournalLayout.TOOLBAR_Y;
        int toolbarX = bookLayout.leftPageX()
                + (bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                + JournalLayout.CATALOG_X_OFFSET + this.horizontalOffset;

        // 搜索规则分成语法与用途两行，缩短 tooltip 宽度并区分信息层级。
        String tooltipBase = "screen.unsuspiciousblock.archaeology_journal.search_tooltip";
        this.searchToggleButton = new IconButton(
                toolbarX, toolbarY,
                JournalLayout.SEARCH_ICON_SIZE,
                this.searchExpanded ? IconButton.Icon.CLOSE : IconButton.Icon.SEARCH,
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
        screen.registerWidget(this.searchToggleButton);

        // 排序图标按钮
        int sortOrderX = this.searchExpanded
                ? toolbarX + JournalLayout.SEARCH_ICON_SIZE + this.expandedSearchFieldWidth + JournalLayout.TOOLBAR_GAP
                : toolbarX + JournalLayout.SEARCH_ICON_SIZE + JournalLayout.TOOLBAR_GAP;
        this.sortButton = new IconButton(
                sortOrderX, toolbarY,
                JournalLayout.SORT_ICON_SIZE,
                sortOrderIcon(this.currentSortOrder),
                CatalogSorter.sortOrderTooltip(this.currentSortOrder),
                this::cycleSortOrder
        );
        screen.registerWidget(this.sortButton);

        // 排序方向按钮
        int sortDirectionX = sortOrderX + JournalLayout.SORT_ICON_SIZE + JournalLayout.TOOLBAR_GAP;
        this.sortOrderButton = new IconButton(
                sortDirectionX, toolbarY,
                JournalLayout.SORT_ICON_SIZE,
                sortDirectionIcon(this.sortDescending),
                CatalogSorter.sortDirectionTooltip(this.sortDescending),
                this::toggleSortDirection
        );
        screen.registerWidget(this.sortOrderButton);

        // 隐藏未解锁条目按钮
        int hideLockedX = sortDirectionX + JournalLayout.SORT_ICON_SIZE + JournalLayout.TOOLBAR_GAP;
        this.hideLockedButton = new IconButton(
                hideLockedX, toolbarY,
                JournalLayout.SORT_ICON_SIZE,
                hideLockedIcon(this.hideLocked),
                hideLockedTooltip(this.hideLocked),
                this::toggleHideLocked
        );
        screen.registerWidget(this.hideLockedButton);

        // 搜索框
        if (this.searchExpanded) {
            // 重建前保留输入文本与焦点归属：只有原本就属于搜索框的焦点才自动恢复
            String savedText = this.pendingSearchText != null ? this.pendingSearchText : this.currentSearch.rawQuery();
            boolean hadFocus = previousField != null && previousField.isFocused();
            this.searchBackgroundX = toolbarX + JournalLayout.SEARCH_ICON_SIZE;
            this.searchBackgroundY = toolbarY;
            int searchFieldX = this.searchBackgroundX + SEARCH_TEXT_HORIZONTAL_PADDING;
            int searchFieldY = toolbarY + (JournalLayout.SEARCH_BAR_HEIGHT - font.lineHeight) / 2;
            int searchFieldWidth = Math.max(1,
                    this.expandedSearchFieldWidth - SEARCH_TEXT_HORIZONTAL_PADDING * 2);
            this.searchField = new ShadowlessEditBox(font,
                    searchFieldX, searchFieldY, searchFieldWidth, font.lineHeight,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_placeholder"));
            this.searchField.setBordered(false);
            this.searchField.setTextColor(SEARCH_TEXT_COLOR);
            this.searchField.setHint(Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_placeholder"));
            this.searchField.setMaxLength(256);
            // 初始化值会同步触发 responder，必须在绑定回调前恢复文本，避免重建递归
            this.searchField.setValue(savedText);
            this.searchField.setResponder(this::onSearchChanged);
            // 只登记焦点请求：原版点击分发会在按钮回调返回后把焦点设回被点击的旧按钮，
            // 因此真正的 setFocused 交给宿主在鼠标事件尾部（或非鼠标重建处）完成
            this.searchFocusRequested = this.focusSearchOnCreate || hadFocus;
            this.focusSearchOnCreate = false;
            screen.registerWidget(this.searchField);
        } else {
            this.searchFocusRequested = false;
            this.focusSearchOnCreate = false;
            // 宿主焦点若仍指向被移除的输入框，交由宿主清空，避免键盘事件继续发给已移除控件
            if (previousField != null) {
                screen.clearSearchFocus(previousField);
            }
            this.searchField = new EditBox(font, 0, 0, 0, 0, Component.empty());
            this.searchField.setVisible(false);
            this.searchField.setResponder(this::onSearchChanged);
        }
    }

    private static Component tooltipDescription(String translationKey) {
        return Component.literal("  ")
                .append(Component.translatable(translationKey).withStyle(ChatFormatting.GRAY));
    }

    /** 渲染与搜索按钮等高、同色的搜索框背景。 */
    public void renderSearchBackground(GuiGraphics guiGraphics) {
        if (this.searchExpanded && this.searchField != null) {
            int right = this.searchBackgroundX + this.expandedSearchFieldWidth;
            int bottom = this.searchBackgroundY + JournalLayout.SEARCH_BAR_HEIGHT;
            guiGraphics.fill(this.searchBackgroundX, this.searchBackgroundY,
                    right, bottom, SEARCH_BORDER_COLOR);
            guiGraphics.fill(this.searchBackgroundX + 1, this.searchBackgroundY + 1,
                    right - 1, bottom - 1, SEARCH_BG_COLOR);
        }
    }

    /** 渲染工具栏 tooltip */
    public void renderTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.sortButton != null && this.sortButton.visible) {
            this.sortButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.sortOrderButton != null && this.sortOrderButton.visible) {
            this.sortOrderButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.searchToggleButton != null && this.searchToggleButton.visible) {
            this.searchToggleButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.hideLockedButton != null && this.hideLockedButton.visible) {
            this.hideLockedButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
    }
}
