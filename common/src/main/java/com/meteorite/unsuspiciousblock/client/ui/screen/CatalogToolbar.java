package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.client.ui.widget.IconButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 目录工具栏管理器。
 * <p>
 * 管理目录页面的搜索/排序 widget 组（搜索切换按钮、搜索框、排序按钮、排序方向按钮）
 * 及其状态（搜索查询、排序方式、排序方向、搜索展开状态）。
 */
public class CatalogToolbar {

    // 状态
    private JournalSearchQuery currentSearch = JournalSearchQuery.EMPTY;
    private CatalogSorter.SortOrder currentSortOrder = CatalogSorter.SortOrder.DEFAULT;
    private boolean sortDescending = false;
    private boolean searchExpanded = false;
    // 标记搜索框是否因用户点击刚被打开，需要在 createWidgets 时自动聚焦
    private boolean searchJustOpened = false;

    // widget 引用
    private IconButton searchToggleButton;
    private IconButton sortButton;
    private IconButton sortOrderButton;
    private EditBox searchField;

    // 回调
    private final Runnable onRebuildWidgets;
    private final Runnable onRebuildViewModels;

    public CatalogToolbar(Runnable onRebuildWidgets, Runnable onRebuildViewModels) {
        this.onRebuildWidgets = onRebuildWidgets;
        this.onRebuildViewModels = onRebuildViewModels;
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
    }

    public void setSearchExpanded(boolean expanded) {
        this.searchExpanded = expanded;
    }

    public EditBox searchField() {
        return searchField;
    }

    // —— 事件处理 ——

    /** 搜索框内容变化 */
    public void onSearchChanged(String text) {
        this.currentSearch = JournalSearchQuery.parse(text);
        onRebuildViewModels.run();
    }

    /** 切换搜索框展开/收起 */
    public void toggleSearch() {
        this.searchExpanded = !this.searchExpanded;
        if (this.searchExpanded) {
            this.searchJustOpened = true;
        } else if (this.searchField != null) {
            this.searchField.setValue("");
            this.currentSearch = JournalSearchQuery.EMPTY;
        }
        onRebuildWidgets.run();
    }

    /** 循环切换排序方式 */
    public void cycleSortOrder() {
        this.currentSortOrder = this.currentSortOrder.next();
        if (this.sortButton != null) {
            this.sortButton.setIconChar(CatalogSorter.sortOrderIcon(this.currentSortOrder));
            this.sortButton.setTooltip(CatalogSorter.sortOrderTooltip(this.currentSortOrder));
        }
        onRebuildViewModels.run();
    }

    /** 切换正序/倒序 */
    public void toggleSortDirection() {
        this.sortDescending = !this.sortDescending;
        if (this.sortOrderButton != null) {
            this.sortOrderButton.setIconChar(CatalogSorter.sortDirectionIcon(this.sortDescending));
            this.sortOrderButton.setTooltip(CatalogSorter.sortDirectionTooltip(this.sortDescending));
        }
        onRebuildViewModels.run();
    }

    /** 处理 ESC 键，返回 true 表示已消费事件 */
    public boolean handleEsc() {
        if (this.searchExpanded) {
            this.searchExpanded = false;
            if (this.searchField != null) {
                this.searchField.setValue("");
                this.currentSearch = JournalSearchQuery.EMPTY;
            }
            onRebuildWidgets.run();
            return true;
        }
        return false;
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
                + JournalLayout.CATALOG_X_OFFSET;

        // 搜索切换按钮
        // 多行 tooltip：标题 + 各搜索规则
        Component header = Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_tooltip");
        Component ruleTable = Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_tooltip.rule_table");
        Component ruleMod = Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_tooltip.rule_mod");
        Component ruleItem = Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_tooltip.rule_item");
        Component ruleTag = Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_tooltip.rule_tag");
        this.searchToggleButton = new IconButton(
                toolbarX, toolbarY,
                JournalLayout.SEARCH_ICON_SIZE,
                this.searchExpanded ? '✕' : '⌕',
                List.of(header,
                        Component.literal("- ").append(ruleTable),
                        Component.literal("- ").append(ruleMod),
                        Component.literal("- ").append(ruleItem),
                        Component.literal("- ").append(ruleTag)),
                this::toggleSearch
        );
        screen.registerWidget(this.searchToggleButton);

        // 排序图标按钮
        int sortOrderX = this.searchExpanded
                ? toolbarX + JournalLayout.SEARCH_FIELD_WIDTH + JournalLayout.TOOLBAR_GAP + JournalLayout.SORT_ICON_SIZE
                : toolbarX + JournalLayout.SEARCH_ICON_SIZE + JournalLayout.TOOLBAR_GAP;
        this.sortButton = new IconButton(
                sortOrderX, toolbarY,
                JournalLayout.SORT_ICON_SIZE,
                CatalogSorter.sortOrderIcon(this.currentSortOrder),
                CatalogSorter.sortOrderTooltip(this.currentSortOrder),
                this::cycleSortOrder
        );
        screen.registerWidget(this.sortButton);

        // 排序方向按钮
        int sortDirectionX = sortOrderX + JournalLayout.SORT_ICON_SIZE + JournalLayout.TOOLBAR_GAP;
        this.sortOrderButton = new IconButton(
                sortDirectionX, toolbarY,
                JournalLayout.SORT_ICON_SIZE,
                CatalogSorter.sortDirectionIcon(this.sortDescending),
                CatalogSorter.sortDirectionTooltip(this.sortDescending),
                this::toggleSortDirection
        );
        screen.registerWidget(this.sortOrderButton);

        // 搜索框
        if (this.searchExpanded) {
            String savedText = this.searchField != null ? this.searchField.getValue() : "";
            boolean hadFocus = this.searchField != null && this.searchField.isFocused();
            int searchFieldX = toolbarX + JournalLayout.SEARCH_ICON_SIZE;
            this.searchField = new EditBox(font,
                    searchFieldX, toolbarY + (JournalLayout.SEARCH_QUICK_BAR_HEIGHT - JournalLayout.SEARCH_BAR_HEIGHT) / 2,
                    JournalLayout.SEARCH_FIELD_WIDTH, JournalLayout.SEARCH_BAR_HEIGHT,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_placeholder"));
            this.searchField.setHint(Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_placeholder"));
            this.searchField.setMaxLength(50);
            this.searchField.setResponder(this::onSearchChanged);
            this.searchField.setValue(savedText);
            // 仅在用户刚点击搜索按钮展开，或原来搜索框就有焦点时，才自动聚焦
            if (this.searchJustOpened || hadFocus) {
                this.searchField.setFocused(true);
            }
            this.searchJustOpened = false;
            screen.registerWidget(this.searchField);
        } else {
            this.searchField = new EditBox(font, 0, 0, 0, 0, Component.empty());
            this.searchField.setVisible(false);
            this.searchField.setResponder(this::onSearchChanged);
        }
    }

    /** 渲染搜索框展开态暗黄色底色背景 */
    public void renderSearchBackground(GuiGraphics guiGraphics) {
        if (this.searchExpanded && this.searchField != null) {
            int searchBgX = this.searchField.getX() - 1;
            int searchBgY = this.searchField.getY() - 1;
            int searchBgW = this.searchField.getWidth() + 2;
            int searchBgH = this.searchField.getHeight() + 2;
            int borderColor = 0xFF8B6914;
            int borderLight = 0xFFB8943C;
            int bgColor = 0xFFD8C0A0;
            guiGraphics.fill(searchBgX, searchBgY, searchBgX + searchBgW, searchBgY + searchBgH, borderColor);
            guiGraphics.fill(searchBgX + 1, searchBgY + 1, searchBgX + searchBgW - 1, searchBgY + 2, borderLight);
            guiGraphics.fill(searchBgX + 1, searchBgY + 2, searchBgX + 2, searchBgY + searchBgH - 1, borderLight);
            guiGraphics.fill(searchBgX + 1, searchBgY + 1, searchBgX + searchBgW - 1, searchBgY + searchBgH - 1, bgColor);
        }
    }

    /** 渲染工具栏 tooltip */
    public void renderTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.sortButton != null) {
            this.sortButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.sortOrderButton != null) {
            this.sortOrderButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.searchToggleButton != null) {
            this.searchToggleButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
    }
}