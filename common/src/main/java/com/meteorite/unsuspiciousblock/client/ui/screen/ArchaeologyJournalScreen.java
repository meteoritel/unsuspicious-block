package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalViewport;
import com.meteorite.unsuspiciousblock.client.ui.panel.CatalogPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.PageIndicator;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalTooltipBuilder;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.unsuspiciousblock.client.ui.widget.IconButton;
import com.meteorite.unsuspiciousblock.client.ui.widget.JournalPageButton;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 考古笔记主界面。
 */
public class ArchaeologyJournalScreen extends Screen {

    private final JournalViewModel viewModel;
    private final CatalogToolbar catalogToolbar;
    private final LogToolbar logToolbar;

    private JournalViewport viewport;
    private JournalBookBackground.BookLayout bookLayout;
    private CatalogPanel catalogPanel;
    private RightPageContainer rightPage;
    private PageIndicator catalogPageIndicator;
    private Button catalogPrevButton;
    private Button catalogNextButton;
    private Button itemPrevButton;
    private Button itemNextButton;
    // 书页外左上角帮助按钮
    private IconButton helpButton;
    private IconButton directoryBackButton;
    private int categoryFocusIndex;
    private int restoredCatalogPage;

    public ArchaeologyJournalScreen(ArchaeologyJournalState state) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"));
        this.viewModel = new JournalViewModel(state);
        this.catalogToolbar = new CatalogToolbar(this::rebuildWidgets);
        this.logToolbar = new LogToolbar();
    }

    @Override
    protected void init() {
        super.init();
        this.viewport = JournalViewport.compute(this.width, this.height);
        this.bookLayout = JournalBookBackground.compute(this.viewport.logicalWidth(), this.viewport.logicalHeight());
        this.rightPage = new RightPageContainer(this.bookLayout);

        // 恢复上次关闭时持久化的 UI 状态
        restorePersistedUiState();

        this.viewModel.reloadCatalog();
        this.viewModel.restoreDirectoryState(
                ArchaeologyJournalClientState.isLastDirectoryHome(),
                ArchaeologyJournalClientState.getLastDirectoryCategory(),
                ArchaeologyJournalClientState.getExpandedDirectoryTables());
        this.categoryFocusIndex = ArchaeologyJournalClientState.getLastCategoryFocus();
        this.restoredCatalogPage = ArchaeologyJournalClientState.getLastDirectoryPage();
        this.viewModel.rebuildViewModels(this.rightPage, null);
        this.viewModel.initRevisions();
        this.rebuildWidgets();
    }

    // 从 ClientState 恢复跨打开/关闭的 UI 状态
    private void restorePersistedUiState() {
        // 恢复目录工具栏状态
        CatalogSorter.SortOrder savedCatalogSortOrder = ArchaeologyJournalClientState.getLastCatalogSortOrder();
        if (savedCatalogSortOrder != null) {
            this.catalogToolbar.setCurrentSortOrder(savedCatalogSortOrder);
        }
        this.catalogToolbar.setSortDescending(ArchaeologyJournalClientState.getLastCatalogSortDescending());
        this.catalogToolbar.setHideLocked(ArchaeologyJournalClientState.getLastCatalogHideLocked());
        String savedCatalogSearchText = ArchaeologyJournalClientState.getLastCatalogSearchText();
        if (savedCatalogSearchText != null && !savedCatalogSearchText.isEmpty()) {
            this.catalogToolbar.setCurrentSearch(JournalSearchQuery.parse(savedCatalogSearchText));
        }

        // 恢复日志工具栏状态
        this.logToolbar.setSortDescending(ArchaeologyJournalClientState.getLastLogSortDescending());
        LogGrouper.GroupMode savedLogGroupMode = ArchaeologyJournalClientState.getLastLogGroupMode();
        if (savedLogGroupMode != null) {
            this.logToolbar.setGroupMode(savedLogGroupMode);
        }

        // 恢复右侧 tab 选择
        RightPageContainer.Tab savedTab = ArchaeologyJournalClientState.getLastRightPageTab();
        if (savedTab != null) {
            this.rightPage.setActiveTab(savedTab);
        }
    }

    @Override
    public void removed() {
        ArchaeologyJournalClientState.rememberLastSelectedTable(this.viewModel.selectedTableId());
        ArchaeologyJournalClientState.rememberDirectoryState(this.viewModel.isCategoryHomeMode(),
                this.viewModel.selectedCategory(), this.viewModel.expandedRoots(),
                this.catalogPanel != null ? this.catalogPanel.getPage() : 0, this.categoryFocusIndex);
        // 持久化目录工具栏状态
        ArchaeologyJournalClientState.setLastCatalogSortOrder(this.catalogToolbar.currentSortOrder());
        ArchaeologyJournalClientState.setLastCatalogSortDescending(this.catalogToolbar.sortDescending());
        ArchaeologyJournalClientState.setLastCatalogHideLocked(this.catalogToolbar.hideLocked());
        ArchaeologyJournalClientState.setLastCatalogSearchText(this.catalogToolbar.currentSearch().rawQuery());
        // 持久化日志工具栏状态
        ArchaeologyJournalClientState.setLastLogSortDescending(this.logToolbar.sortDescending());
        ArchaeologyJournalClientState.setLastLogGroupMode(this.logToolbar.groupMode());
        // 持久化右侧 tab 选择
        if (this.rightPage != null) {
            ArchaeologyJournalClientState.setLastRightPageTab(this.rightPage.getActiveTab());
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (!this.catalogToolbar.isSearchFocused()
                && this.minecraft != null
                && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        if (!this.catalogToolbar.isSearchFocused() && handleCatalogNavigation(keyCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // 分类首页按网格导航；目录列表使用上下选择、左右展开折叠。
    private boolean handleCatalogNavigation(int keyCode) {
        if (this.catalogPanel == null) return false;
        if (this.viewModel.isCategoryHome()) return handleCategoryNavigation(keyCode);
        if (this.viewModel.tableViews().isEmpty()) return false;
        return switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> moveCatalogSelection(-1);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> moveCatalogSelection(1);
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> setCurrentExpansion(false);
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> setCurrentExpansion(true);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { updateItemGridPanel(); yield true; }
            case GLFW.GLFW_KEY_PAGE_UP -> moveCatalogPage(-1);
            case GLFW.GLFW_KEY_PAGE_DOWN -> moveCatalogPage(1);
            default -> false;
        };
    }

    private boolean handleCategoryNavigation(int keyCode) {
        int count = this.viewModel.categoryViews().size();
        if (count == 0) return false;
        int target = this.categoryFocusIndex;
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> target--;
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> target++;
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> target -= 2;
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> target += 2;
            case GLFW.GLFW_KEY_PAGE_UP -> { this.catalogPanel.changePage(-1); target = this.catalogPanel.getPage() * 6; }
            case GLFW.GLFW_KEY_PAGE_DOWN -> { this.catalogPanel.changePage(1); target = this.catalogPanel.getPage() * 6; }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                enterFocusedCategory();
                return true;
            }
            default -> { return false; }
        }
        if (target < 0) {
            this.catalogPanel.changePage(-1);
            target = Math.max(0, this.catalogPanel.getPage() * 6 + 5);
        } else if (target >= count) {
            this.catalogPanel.changePage(1);
            target = Math.min(count - 1, this.catalogPanel.getPage() * 6);
        }
        this.categoryFocusIndex = Mth.clamp(target, 0, count - 1);
        this.catalogPanel.ensureIndexVisible(this.categoryFocusIndex);
        syncButtonState();
        return true;
    }

    private void enterFocusedCategory() {
        if (this.categoryFocusIndex < 0 || this.categoryFocusIndex >= this.viewModel.categoryViews().size()) return;
        this.viewModel.enterCategory(this.viewModel.categoryViews().get(this.categoryFocusIndex).id());
        rebuildWidgets();
    }

    private boolean setCurrentExpansion(boolean expanded) {
        int index = this.viewModel.selectedIndex();
        if (!this.viewModel.rowHasChildren(index) || this.viewModel.rowExpanded(index) == expanded) return true;
        ResourceLocation selected = this.viewModel.selectedTableId();
        this.viewModel.toggleExpanded(index);
        rebuildViewModels();
        if (selected != null) setSelectedTable(selected);
        return true;
    }

    private void setSelectedTable(ResourceLocation id) {
        for (int index = 0; index < this.viewModel.tableViews().size(); index++) {
            if (this.viewModel.tableViews().get(index).id().equals(id)) {
                setSelectedIndex(index);
                return;
            }
        }
    }

    private boolean moveCatalogSelection(int delta) {
        int currentIndex = this.viewModel.selectedIndex();
        setSelectedIndex(currentIndex < 0 ? 0 : currentIndex + delta);
        return true;
    }

    private boolean moveCatalogPage(int delta) {
        int targetIndex = this.catalogPanel.moveSelectionPage(this.viewModel.selectedIndex(), delta);
        if (targetIndex >= 0) {
            setSelectedIndex(targetIndex);
        }
        return true;
    }

    @Override
    protected void repositionElements() {
        UiStateSnapshot snapshot = captureUiState();

        this.viewport = JournalViewport.compute(this.width, this.height);
        this.bookLayout = JournalBookBackground.compute(this.viewport.logicalWidth(), this.viewport.logicalHeight());
        this.rightPage = new RightPageContainer(this.bookLayout);

        this.updateItemGridPanel();
        this.rightPage.setActiveItemTag(snapshot.activeItemTag());
        this.rightPage.restoreLogSelection(snapshot.logEntryId(), snapshot.logDetail());

        // 恢复日志排序状态
        this.logToolbar.setSortDescending(snapshot.logSortDescending());
        this.logToolbar.setGroupMode(snapshot.groupMode());
        this.rightPage.getLogPanel().setSortDescending(this.logToolbar.sortDescending());
        this.rightPage.getLogPanel().setGroupMode(snapshot.groupMode());

        // 恢复目录搜索/排序状态
        this.catalogToolbar.setCurrentSortOrder(snapshot.catalogSortOrder());
        this.catalogToolbar.setSortDescending(snapshot.catalogSortDescending());
        this.catalogToolbar.setCurrentSearch(snapshot.catalogSearch());
        this.catalogToolbar.setSearchExpanded(snapshot.catalogSearchExpanded());
        this.catalogToolbar.setHideLocked(snapshot.catalogHideLocked());

        this.rightPage.setActiveTab(snapshot.tab());
        this.rightPage.setPage(snapshot.rightPagePage());

        this.rebuildWidgets();
        // 仅在第一次 init 之后的 reposition 中恢复 catalog page，
        if (this.catalogPanel != null && snapshot.catalogPage() > 0) {
            this.catalogPanel.setPage(snapshot.catalogPage());
        }
        this.syncButtonState();
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int logicalMouseX = (int) this.viewport.toLogicalX(mouseX);
        int logicalMouseY = (int) this.viewport.toLogicalY(mouseY);
        this.viewport.push(guiGraphics);
        try {
            refreshAndSync();
            JournalBookBackground.render(guiGraphics, this.bookLayout);
            renderTitle(guiGraphics);
            renderCatalogArea(guiGraphics, logicalMouseX, logicalMouseY);
            if (this.viewModel.isCategoryHome()) renderWelcomePage(guiGraphics);
            else if (this.viewModel.selectedTable() == null) renderEmptyCategoryPage(guiGraphics);
            else this.rightPage.render(guiGraphics, this.font, logicalMouseX, logicalMouseY);
            this.catalogToolbar.renderSearchBackground(guiGraphics);
            super.render(guiGraphics, logicalMouseX, logicalMouseY, partialTick);
            renderOverlays(guiGraphics, logicalMouseX, logicalMouseY);
        } finally {
            this.viewport.pop(guiGraphics);
        }
    }

    // 检测服务端数据变更并按需刷新
    private void refreshAndSync() {
        if (this.viewModel.refreshIfNeeded()) {
            this.viewModel.setLogSortDescending(this.logToolbar.sortDescending());
            this.viewModel.setCurrentGroupMode(this.logToolbar.groupMode());
            this.viewModel.setCurrentSortOrder(this.catalogToolbar.currentSortOrder());
            this.viewModel.setSortDescending(this.catalogToolbar.sortDescending());
            this.viewModel.setHideLocked(this.catalogToolbar.hideLocked());
            this.viewModel.setCurrentSearch(this.catalogToolbar.currentSearch());
            this.viewModel.rebuildViewModels(this.rightPage, this.catalogPanel);
            this.updateItemGridPanel();
            this.syncButtonState();
        }
    }

    // 渲染标题
    private void renderTitle(GuiGraphics guiGraphics) {
        int titleWidth = this.font.width(this.title);
        guiGraphics.drawString(this.font, this.title,
                (this.viewport.logicalWidth() - titleWidth) / 2,
                this.bookLayout.bookY() + 2, 0x4A3320, false);
    }

    // 渲染左侧目录区域（目录面板、解锁进度、翻页指示器、空状态）
    private void renderCatalogArea(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.catalogPanel != null) {
            this.catalogPanel.render(guiGraphics, this.font, this.viewModel.selectedIndex(), mouseX, mouseY);
        }

        if (this.viewModel.totalTableCount() > 0) {
            renderCatalogProgress(guiGraphics);
        }

        if (this.catalogPageIndicator != null && this.catalogPanel != null) {
            this.catalogPageIndicator.setPage(this.catalogPanel.getPage(), this.catalogPanel.pageCount());
            this.catalogPageIndicator.render(guiGraphics, this.font);
        }

        if (!this.viewModel.isCategoryHome() && this.viewModel.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_catalog"),
                    this.bookLayout.leftPageX()
                            + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                            + JournalLayout.CATALOG_X_OFFSET
                            + JournalLayout.CATALOG_LEFT_PAD,
                    this.bookLayout.leftPageY() + JournalLayout.CATALOG_LIST_TOP, 0x7A6247, false);
        }
    }

    private void renderCatalogProgress(GuiGraphics graphics) {
        int unlocked = this.viewModel.displayedUnlockedTableCount();
        int total = this.viewModel.displayedTableCount();
        int x = this.bookLayout.leftPageX()
                + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                + JournalLayout.CATALOG_X_OFFSET;
        int y = this.bookLayout.leftPageY() + JournalLayout.CATALOG_SUMMARY_Y - 1;
        int width = JournalLayout.CATALOG_TEXTURE_WIDTH;
        int height = 11;
        graphics.fill(x, y, x + width, y + height, 0xFF8B6914);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFD8C0A0);
        int innerWidth = width - 2;
        int filled = total > 0 ? Mth.clamp(Math.round(innerWidth * (unlocked / (float) total)), 0, innerWidth) : 0;
        if (filled > 0) {
            graphics.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, 0xFF8FA65A);
        }
        Component label = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.unlocked_tables", unlocked, total);
        graphics.drawString(this.font, label, x + (width - this.font.width(label)) / 2,
                y + 2, 0xFF3D2810, false);
    }

    private void renderWelcomePage(GuiGraphics graphics) {
        int x = this.bookLayout.rightPageX() + 16;
        int y = this.bookLayout.rightPageY() + 28;
        int width = this.bookLayout.rightPageWidth() - 32;
        Component welcome = Component.translatable("screen.unsuspiciousblock.archaeology_journal.welcome.title");
        graphics.drawString(this.font, welcome, x + (width - this.font.width(welcome)) / 2, y, 0x4A3320, false);
        y += 24;
        for (var line : this.font.split(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.welcome.body"), width)) {
            graphics.drawString(this.font, line, x, y, 0x6E5A42, false);
            y += this.font.lineHeight + 2;
        }
        y += 16;
        Component progress = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.unlocked_tables",
                this.viewModel.unlockedTableCount(), this.viewModel.totalTableCount());
        graphics.drawString(this.font, progress, x + (width - this.font.width(progress)) / 2,
                y, 0x7B3E18, false);
    }

    private void renderEmptyCategoryPage(GuiGraphics graphics) {
        Component message = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.category.no_discovered_entries");
        int width = this.bookLayout.rightPageWidth() - 32;
        int x = this.bookLayout.rightPageX() + 16;
        int y = this.bookLayout.rightPageY() + 72;
        for (var line : this.font.split(message, width)) {
            graphics.drawString(this.font, line, x, y, 0x7A6247, false);
            y += this.font.lineHeight + 2;
        }
    }

    // 渲染所有叠加层 tooltip（工具栏、帮助按钮、日志条目、物品网格）
    private void renderOverlays(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        this.catalogToolbar.renderTooltips(guiGraphics, mouseX, mouseY);
        boolean isLogListMode = this.rightPage.getActiveTab() == RightPageContainer.Tab.LOG
                && !this.rightPage.isShowingLogDetail();
        if (isLogListMode) {
            this.logToolbar.renderTooltips(guiGraphics, mouseX, mouseY);
        }

        if (this.helpButton != null) {
            this.helpButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }

        if (this.viewModel.isCategoryHome()) {
            int hovered = this.catalogPanel != null ? this.catalogPanel.hoveredIndex(mouseX, mouseY) : -1;
            if (hovered >= 0 && hovered < this.viewModel.categoryViews().size()) {
                var category = this.viewModel.categoryViews().get(hovered);
                guiGraphics.renderTooltip(this.font, List.of(
                        category.name().getVisualOrderText(), category.description().getVisualOrderText()), mouseX, mouseY);
            }
            return;
        }
        int hoveredCatalog = this.catalogPanel != null ? this.catalogPanel.hoveredIndex(mouseX, mouseY) : -1;
        if (this.viewModel.rowIsChild(hoveredCatalog)) {
            List<Component> lines = new java.util.ArrayList<>();
            List<Component> parentNames = this.viewModel.rowReferencingParentNames(hoveredCatalog);
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.child_references", parentNames.size()));
            parentNames.forEach(parentName -> lines.add(Component.literal("∈ ").append(parentName)));
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.child_probability_short"));
            guiGraphics.renderTooltip(this.font,
                    lines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
            return;
        }
        if (this.viewModel.selectedTable() == null) return;
        this.rightPage.renderTooltips(guiGraphics, this.font, mouseX, mouseY);

        ItemGridPanel.TooltipData tooltipData = this.rightPage.getTooltipData(mouseX, mouseY);
        if (tooltipData != null) {
            List<Component> tooltipLines = JournalTooltipBuilder.build(tooltipData);
            if (tooltipData.discovered() && !tooltipData.stack().isEmpty()) {
                guiGraphics.renderTooltip(this.font, tooltipLines,
                        tooltipData.stack().getTooltipImage(), mouseX, mouseY);
            } else {
                guiGraphics.renderTooltip(this.font, tooltipLines, Optional.empty(), mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseX = this.viewport.toLogicalX(mouseX);
        mouseY = this.viewport.toLogicalY(mouseY);
        if (super.mouseClicked(mouseX, mouseY, button)) {
            syncButtonState();
            return true;
        }
        if (this.catalogPanel != null && this.catalogPanel.containsMouse(mouseX, mouseY)) {
            CatalogPanel.ClickResult click = this.catalogPanel.handleClick(mouseX, mouseY);
            int clickedIndex = click.index();
            if (clickedIndex >= 0) {
                if (this.viewModel.isCategoryHome()) {
                    this.categoryFocusIndex = clickedIndex;
                    enterFocusedCategory();
                } else if (click.toggleExpansion()) {
                    ResourceLocation selected = this.viewModel.tableViews().get(clickedIndex).id();
                    this.viewModel.toggleExpanded(clickedIndex);
                    rebuildViewModels();
                    setSelectedTable(selected);
                } else if (hasShiftDown()) {
                    // Shift+点击：切换收藏状态，消费点击避免触发选中/翻页
                    List<com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyJournalEntry> views = this.viewModel.tableViews();
                    if (clickedIndex < views.size()) {
                        ResourceLocation id = views.get(clickedIndex).id();
                        ArchaeologyJournalClientState.toggleFavorite(id);
                        this.rebuildViewModels();
                    }
                } else {
                    setSelectedIndex(clickedIndex);
                }
            }
            return true;
        }
        if (!this.viewModel.isCategoryHome() && this.viewModel.selectedTable() != null
                && this.rightPage.mouseClicked(mouseX, mouseY, button)) {
            syncButtonState();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        mouseX = this.viewport.toLogicalX(mouseX);
        mouseY = this.viewport.toLogicalY(mouseY);
        if (this.catalogPanel != null && this.catalogPanel.containsMouse(mouseX, mouseY)) {
            this.catalogPanel.changePage(scrollY < 0.0 ? 1 : -1);
            this.syncButtonState();
            return true;
        }
        if (this.rightPage.containsMouse(mouseX, mouseY) && this.rightPage.pageCount() > 1) {
            this.rightPage.handleScroll(scrollY);
            this.syncButtonState();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(
                this.viewport.toLogicalX(mouseX), this.viewport.toLogicalY(mouseY), button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        return super.mouseDragged(
                this.viewport.toLogicalX(mouseX), this.viewport.toLogicalY(mouseY), button,
                this.viewport.toLogicalDistance(dragX), this.viewport.toLogicalDistance(dragY));
    }

    @Override
    protected void rebuildWidgets() {
        int previousCatalogPage = this.catalogPanel != null
                ? this.catalogPanel.getPage() : this.restoredCatalogPage;
        // 重建 widget 前，同步工具栏搜索/排序状态到 ViewModel，
        this.rebuildViewModels();
        this.clearWidgets();

        if (!this.viewModel.isCategoryHome()) {
            this.addRenderableWidget(this.rightPage.getIntroTabButton());
            this.addRenderableWidget(this.rightPage.getArchaeologyTabButton());
            this.addRenderableWidget(this.rightPage.getLogTabButton());
        }

        // 目录工具栏
        boolean categoryHomeMode = this.viewModel.isCategoryHomeMode();
        boolean showDirectoryBack = !categoryHomeMode;
        int toolbarOffset = categoryHomeMode ? 5 : 18;
        int searchRightEdge = categoryHomeMode
                ? CatalogPanel.CATEGORY_GRID_WIDTH
                : JournalLayout.CATALOG_TEXTURE_WIDTH - toolbarOffset;
        this.catalogToolbar.setHorizontalOffset(toolbarOffset);
        this.catalogToolbar.setExpandedSearchFieldWidth(searchRightEdge - JournalLayout.SEARCH_ICON_SIZE);
        this.catalogToolbar.createWidgets(this, this.bookLayout, this.font);
        this.catalogToolbar.setCategoryHomeMode(categoryHomeMode);

        if (showDirectoryBack) {
            int backX = this.bookLayout.leftPageX()
                    + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                    + JournalLayout.CATALOG_X_OFFSET;
            int backY = this.bookLayout.leftPageY() + JournalLayout.TOOLBAR_Y;
            this.directoryBackButton = this.addRenderableWidget(new IconButton(
                    backX, backY, JournalLayout.SEARCH_ICON_SIZE, '<',
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.category.back"),
                    () -> {
                        ResourceLocation current = this.viewModel.selectedCategory();
                        if (current != null) {
                            for (int index = 0; index < this.viewModel.categoryViews().size(); index++) {
                                if (this.viewModel.categoryViews().get(index).id().equals(current)) {
                                    this.categoryFocusIndex = index;
                                    break;
                                }
                            }
                        }
                        this.viewModel.showCategoryHome();
                        rebuildWidgets();
                    }));
        } else {
            this.directoryBackButton = null;
        }

        int buttonWidth = JournalLayout.PAGE_BUTTON_WIDTH;
        int buttonGap = JournalLayout.PAGE_BUTTON_CENTER_GAP;

        int catalogIndicatorCenterX = this.bookLayout.leftPageX() + this.bookLayout.leftPageWidth() / 2;
        int catalogBottomY = this.bookLayout.leftPageY() + JournalLayout.CATALOG_PAGE_INDICATOR_Y
                + (this.font.lineHeight - JournalLayout.PAGE_BUTTON_HEIGHT) / 2;
        this.catalogPageIndicator = new PageIndicator(catalogIndicatorCenterX,
                this.bookLayout.leftPageY() + JournalLayout.CATALOG_PAGE_INDICATOR_Y);
        this.catalogPrevButton = this.addRenderableWidget(
                createPageButton(catalogIndicatorCenterX - buttonGap - buttonWidth, catalogBottomY, false,
                        btn -> {
                            if (this.catalogPanel != null) {
                                this.catalogPanel.changePage(-1);
                                this.syncButtonState();
                            }
                        }));
        this.catalogNextButton = this.addRenderableWidget(
                createPageButton(catalogIndicatorCenterX + buttonGap, catalogBottomY, true,
                        btn -> {
                            if (this.catalogPanel != null) {
                                this.catalogPanel.changePage(1);
                                this.syncButtonState();
                            }
                        }));

        int itemIndicatorCenterX = this.bookLayout.rightPageX() + this.bookLayout.rightPageWidth() / 2;
        int itemBottomY = this.bookLayout.rightPageY() + JournalLayout.GRID_PAGE_INDICATOR_Y
                + (this.font.lineHeight - JournalLayout.PAGE_BUTTON_HEIGHT) / 2;
        this.itemPrevButton = this.addRenderableWidget(
                createPageButton(itemIndicatorCenterX - buttonGap - buttonWidth, itemBottomY, false,
                        btn -> {
                            this.rightPage.changePage(-1);
                            this.syncButtonState();
                        }));
        this.itemNextButton = this.addRenderableWidget(
                createPageButton(itemIndicatorCenterX + buttonGap, itemBottomY, true,
                        btn -> {
                            this.rightPage.changePage(1);
                            this.syncButtonState();
                        }));

        this.catalogPanel = new CatalogPanel(this.bookLayout);
        if (this.viewModel.isCategoryHome()) {
            this.catalogPanel.setCategories(this.viewModel.categoryViews());
            this.catalogPanel.setPage(previousCatalogPage);
            this.catalogPanel.ensureIndexVisible(this.categoryFocusIndex);
        } else {
            this.catalogPanel.setEntries(this.viewModel.buildCatalogEntries());
            this.catalogPanel.ensureIndexVisible(this.viewModel.selectedIndex());
        }
        this.restoredCatalogPage = 0;

        // 日志工具栏
        boolean isLogTab = this.rightPage != null && this.rightPage.getActiveTab() == RightPageContainer.Tab.LOG;
        boolean isLogListMode = isLogTab && !this.rightPage.isShowingLogDetail();
        this.logToolbar.createWidgets(this, this.bookLayout, this.font, isLogListMode, this.rightPage);

        // 日志详情页返回按钮（IconButton）
        this.rightPage.getLogDetailPanel().createBackButton(this::registerWidget,
                () -> this.rightPage.restoreLogSelection(null, false));

        // 日志详情页备注编辑按钮（IconButton）
        this.rightPage.getLogDetailPanel().createNoteButton(this::registerWidget, this::openNoteEditor);

        // 书页外右上角帮助按钮（?），悬停展示使用提示
        int helpX = Math.min(this.viewport.logicalWidth() - JournalLayout.HELP_BUTTON_SIZE,
                this.bookLayout.bookX() + JournalLayout.TEXTURE_WIDTH + JournalLayout.HELP_BUTTON_GAP);
        int helpY = this.bookLayout.bookY() + JournalLayout.HELP_BUTTON_Y_OFFSET;
        this.helpButton = this.addRenderableWidget(new IconButton(
                helpX, helpY,
                JournalLayout.HELP_BUTTON_SIZE,
                '?',
                buildHelpTooltip(),
                () -> {}
        ));

        // 日志列表页备注图标点击回调：直接打开备注编辑界面，无需进入详情页
        this.rightPage.getLogPanel().setNoteClickHandler(this::openNoteEditorForEntry);

        this.syncButtonState();
    }

    // 构建帮助按钮的多行 tooltip
    private static java.util.List<Component> buildHelpTooltip() {
        return java.util.List.of(
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.help_tooltip.title"),
                Component.literal("- ").append(Component.translatable("screen.unsuspiciousblock.archaeology_journal.help_tooltip.favorite")),
                Component.literal("- ").append(Component.translatable("screen.unsuspiciousblock.archaeology_journal.help_tooltip.probability")),
                Component.literal("- ").append(Component.translatable("screen.unsuspiciousblock.archaeology_journal.help_tooltip.sort"))
        );
    }

    private void rebuildViewModels() {
        // 同步日志工具栏状态到 ViewModel，避免 rebuildViewModels 覆盖
        this.viewModel.setLogSortDescending(this.logToolbar.sortDescending());
        this.viewModel.setCurrentGroupMode(this.logToolbar.groupMode());
        // 同步目录工具栏状态到 ViewModel
        this.viewModel.setCurrentSortOrder(this.catalogToolbar.currentSortOrder());
        this.viewModel.setSortDescending(this.catalogToolbar.sortDescending());
        this.viewModel.setHideLocked(this.catalogToolbar.hideLocked());
        this.viewModel.setCurrentSearch(this.catalogToolbar.currentSearch());
        this.viewModel.rebuildViewModels(this.rightPage, this.catalogPanel);
        updateItemGridPanel();
        syncButtonState();
    }

    private void updateItemGridPanel() {
        JournalViewModel.BuildGridResult result = this.viewModel.buildGridItems();
        if (result == null) {
            this.rightPage.setTable(null, List.of(), 0, 0, null);
        } else {
            this.rightPage.setTable(result.tableId(), result.gridItems(),
                    result.parsedCount(), result.totalCount(), result.logRef());
        }
    }

    private void setSelectedIndex(int index) {
        if (this.viewModel.tableViews().isEmpty()) {
            this.viewModel.setSelectedIndex(-1);
            return;
        }
        this.viewModel.setSelectedIndex(Mth.clamp(index, 0, this.viewModel.tableViews().size() - 1));
        if (this.catalogPanel != null) {
            this.catalogPanel.ensureIndexVisible(this.viewModel.selectedIndex());
        }
        updateItemGridPanel();
        syncButtonState();
    }

    private JournalPageButton createPageButton(int x, int y, boolean isForward, Button.OnPress onPress) {
        return new JournalPageButton(x, y, isForward, onPress);
    }

    // 提供一个 public 方法供 toolbar 注册 widget。
    public <T extends AbstractWidget> T registerWidget(T widget) {
        return this.addRenderableWidget(widget);
    }

    private void syncButtonState() {
        boolean hasCatalogContent = this.viewModel.isCategoryHome()
                ? !this.viewModel.categoryViews().isEmpty() : !this.viewModel.isEmpty();
        boolean hasMultipleCatalogPages = this.catalogPanel != null && hasCatalogContent
                && this.catalogPanel.pageCount() > 1;
        applyButtonState(this.catalogPrevButton, hasMultipleCatalogPages,
                hasMultipleCatalogPages && this.catalogPanel != null && this.catalogPanel.getPage() > 0);
        applyButtonState(this.catalogNextButton, hasMultipleCatalogPages,
                hasMultipleCatalogPages && this.catalogPanel != null
                        && this.catalogPanel.getPage() < this.catalogPanel.pageCount() - 1);

        boolean hasMultipleItemPages = !this.viewModel.isCategoryHome() && this.rightPage.pageCount() > 1;
        applyButtonState(this.itemPrevButton, hasMultipleItemPages,
                hasMultipleItemPages && this.rightPage.getPage() > 0);
        applyButtonState(this.itemNextButton, hasMultipleItemPages,
                hasMultipleItemPages && this.rightPage.getPage() < this.rightPage.pageCount() - 1);

        // 日志工具栏可见性
        boolean isLogListMode = this.rightPage.getActiveTab() == RightPageContainer.Tab.LOG
                && !this.rightPage.isShowingLogDetail();
        boolean logHasEntries = isLogListMode && this.rightPage.getLogPanel().hasVisibleEntries();
        this.logToolbar.syncVisibility(isLogListMode, logHasEntries);

        // 日志详情页返回/备注按钮可见性
        boolean isLogDetailMode = this.rightPage.getActiveTab() == RightPageContainer.Tab.LOG
                && this.rightPage.isShowingLogDetail();
        applyButtonState(this.rightPage.getLogDetailPanel().getBackButton(), isLogDetailMode, isLogDetailMode);
        applyButtonState(this.rightPage.getLogDetailPanel().getNoteButton(), isLogDetailMode, isLogDetailMode);
    }

    private static void applyButtonState(@Nullable AbstractWidget btn, boolean visible, boolean active) {
        if (btn != null) {
            btn.visible = visible;
            btn.active = active;
        }
    }

    // 打开备注编辑子界面：取出当前详情页的 entry + tableId
    private void openNoteEditor() {
        var detail = this.rightPage.getLogDetailPanel();
        var entry = detail.getEntry();
        var tableId = detail.getTableId();
        if (entry == null || tableId == null) {
            return;
        }
        Objects.requireNonNull(this.minecraft, "minecraft must not be null while screen is active")
                .setScreen(new JournalLogNoteEditScreen(this, tableId, entry.entryId(), entry.note()));
    }

    // 从日志列表页备注图标直接打开编辑界面（不经过详情页）
    private void openNoteEditorForEntry(ExcavationLogEntry entry) {
        ResourceLocation tableId = this.viewModel.selectedTableId();
        if (tableId == null) {
            return;
        }
        Objects.requireNonNull(this.minecraft, "minecraft must not be null while screen is active")
                .setScreen(new JournalLogNoteEditScreen(this, tableId, entry.entryId(), entry.note()));
    }

    // 捕获当前 UI 状态快照，用于窗口 resize 后恢复
    private UiStateSnapshot captureUiState() {
        return new UiStateSnapshot(
                this.rightPage != null ? this.rightPage.getActiveTab() : RightPageContainer.Tab.INTRO,
                this.rightPage != null ? this.rightPage.getPage() : 0,
                this.catalogPanel != null ? this.catalogPanel.getPage() : 0,
                this.rightPage != null ? this.rightPage.getActiveItemTag() : null,
                this.rightPage != null && this.rightPage.isShowingLogDetail(),
                this.rightPage != null ? this.rightPage.getSelectedLogEntryId() : null,
                this.logToolbar.sortDescending(),
                this.logToolbar.groupMode(),
                this.catalogToolbar.currentSortOrder(),
                this.catalogToolbar.sortDescending(),
                this.catalogToolbar.currentSearch(),
                this.catalogToolbar.searchExpanded(),
                this.catalogToolbar.hideLocked()
        );
    }

    // UI 状态快照，用于窗口 resize 时保存/恢复跨布局重建的状态
    private record UiStateSnapshot(
            RightPageContainer.Tab tab,
            int rightPagePage,
            int catalogPage,
            @Nullable ResourceLocation activeItemTag,
            boolean logDetail,
            @Nullable UUID logEntryId,
            boolean logSortDescending,
            LogGrouper.GroupMode groupMode,
            CatalogSorter.SortOrder catalogSortOrder,
            boolean catalogSortDescending,
            JournalSearchQuery catalogSearch,
            boolean catalogSearchExpanded,
            boolean catalogHideLocked
    ) {}

    }
