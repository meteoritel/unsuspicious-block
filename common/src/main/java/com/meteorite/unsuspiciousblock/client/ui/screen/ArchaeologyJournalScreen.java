package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalViewport;
import com.meteorite.unsuspiciousblock.client.ui.panel.CatalogPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.panel.WelcomeStatsPanel;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalTooltipBuilder;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.unsuspiciousblock.client.ui.support.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.widget.BookSideTabButton;
import com.meteorite.unsuspiciousblock.client.ui.widget.ExternalLinkButton;
import com.meteorite.unsuspiciousblock.client.ui.widget.IconButton;
import com.meteorite.unsuspiciousblock.client.ui.widget.JournalPageButton;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.network.payload.c2s.DeleteJournalLogPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 考古笔记主界面。
 */
public class ArchaeologyJournalScreen extends Screen {

    private static final ResourceLocation MANAGEMENT_TAB_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/book_side_tabs/management_tab.png");
    private static final ResourceLocation HELP_TAB_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/book_side_tabs/help_tab.png");
    private static final ResourceLocation GITHUB_ICON = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/external/github.png");
    private static final ResourceLocation CURSEFORGE_ICON = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/external/curseforge.png");
    private static final ResourceLocation MODRINTH_ICON = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/external/modrinth.png");
    private static final String GITHUB_ISSUES_URL =
            "https://github.com/meteoritel/unsuspicious-block/issues";
    private static final String CURSEFORGE_URL =
            "https://www.curseforge.com/minecraft/mc-mods/unsuspicious-block";
    private static final String MODRINTH_URL =
            "https://modrinth.com/mod/unsuspicious-block";
    private static final String HELP_WIKI_URL =
            "https://github.com/meteoritel/unsuspicious-block/wiki";
    private static final int WELCOME_LINK_BUTTON_SIZE = 16;
    private static final int WELCOME_LINK_BUTTON_GAP = 3;

    private final JournalViewModel viewModel;
    private final CatalogToolbar catalogToolbar;
    private final LogToolbar logToolbar;

    private JournalViewport viewport;
    private JournalBookBackground.BookLayout bookLayout;
    private CatalogPanel catalogPanel;
    private RightPageContainer rightPage;
    private WelcomeStatsPanel welcomeStatsPanel;
    private Button itemPrevButton;
    private Button itemNextButton;
    private BookSideTabButton helpButton;
    private BookSideTabButton managementButton;
    private List<ExternalLinkButton> externalLinkButtons = List.of();
    private int categoryFocusIndex;
    private int restoredCatalogScrollOffset;
    private int emptyCatalogScrollTicks;
    private int catalogProgressScrollTicks;
    @Nullable
    private final ResourceLocation initialItemSearch;

    public ArchaeologyJournalScreen(ArchaeologyJournalState state, @Nullable ResourceLocation initialItemSearch) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"));
        this.viewModel = new JournalViewModel(state);
        this.catalogToolbar = new CatalogToolbar(this::rebuildViewModels, this::rebuildWidgets);
        this.logToolbar = new LogToolbar();
        this.initialItemSearch = initialItemSearch;
    }

    @Override
    protected void init() {
        super.init();
        this.viewport = JournalViewport.compute(this.width, this.height);
        this.bookLayout = JournalBookBackground.compute(this.viewport.logicalWidth(), this.viewport.logicalHeight());
        this.rightPage = new RightPageContainer(this.bookLayout);
        this.welcomeStatsPanel = new WelcomeStatsPanel(this.bookLayout);

        // 恢复上次关闭时持久化的 UI 状态
        restorePersistedUiState();

        if (this.initialItemSearch != null) {
            JournalSearchQuery search = JournalSearchQuery.forItemId(this.initialItemSearch);
            this.catalogToolbar.setCurrentSearch(search);
            this.catalogToolbar.setSearchExpanded(true);
            this.viewModel.setCurrentSearch(search);
        }

        this.viewModel.reloadCatalog();
        this.viewModel.restoreDirectoryState(
                ArchaeologyJournalClientState.isLastDirectoryHome(),
                ArchaeologyJournalClientState.getLastDirectoryCategory(),
                ArchaeologyJournalClientState.getExpandedDirectoryTables());
        this.categoryFocusIndex = ArchaeologyJournalClientState.getLastCategoryFocus();
        this.restoredCatalogScrollOffset = ArchaeologyJournalClientState.getLastDirectoryPage();
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
                this.catalogPanel != null ? this.catalogPanel.getScrollOffset() : 0, this.categoryFocusIndex);
        // 持久化目录工具栏状态
        ArchaeologyJournalClientState.setLastCatalogSortOrder(this.catalogToolbar.currentSortOrder());
        ArchaeologyJournalClientState.setLastCatalogSortDescending(this.catalogToolbar.sortDescending());
        ArchaeologyJournalClientState.setLastCatalogHideLocked(this.catalogToolbar.hideLocked());
        ArchaeologyJournalClientState.setLastCatalogSearchText("");
        this.catalogToolbar.setCurrentSearch(JournalSearchQuery.EMPTY);
        this.catalogToolbar.setSearchExpanded(false);
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

    // 分类首页按网格导航；目录列表使用上下选择、左右展开折叠，Page Up/Down 跨越一个视口。
    private boolean handleCatalogNavigation(int keyCode) {
        if (this.catalogPanel == null) return false;
        if (this.viewModel.isCategoryHome()) return handleCategoryNavigation(keyCode);
        if (this.viewModel.tableViews().isEmpty()) return false;
        return switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> moveCatalogSelection(-1);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> moveCatalogSelection(1);
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> { setCurrentExpansion(false); yield true; }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> { setCurrentExpansion(true); yield true; }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { updateItemGridPanel(); yield true; }
            case GLFW.GLFW_KEY_PAGE_UP -> moveCatalogViewport(-1);
            case GLFW.GLFW_KEY_PAGE_DOWN -> moveCatalogViewport(1);
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
            case GLFW.GLFW_KEY_PAGE_UP -> target -= this.catalogPanel.visibleItemCount();
            case GLFW.GLFW_KEY_PAGE_DOWN -> target += this.catalogPanel.visibleItemCount();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                enterFocusedCategory();
                return true;
            }
            default -> { return false; }
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

    private void setCurrentExpansion(boolean expanded) {
        int index = this.viewModel.selectedIndex();
        if (!this.viewModel.rowHasChildren(index) || this.viewModel.rowExpanded(index) == expanded) return;
        ResourceLocation selected = this.viewModel.selectedTableId();
        this.viewModel.toggleExpanded(index);
        rebuildViewModels();
        if (selected != null) setSelectedTable(selected);
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

    private boolean moveCatalogViewport(int delta) {
        int currentIndex = Math.max(0, this.viewModel.selectedIndex());
        int targetIndex = currentIndex + delta * this.catalogPanel.visibleItemCount();
        setSelectedIndex(targetIndex);
        return true;
    }

    @Override
    protected void repositionElements() {
        UiStateSnapshot snapshot = captureUiState();

        this.viewport = JournalViewport.compute(this.width, this.height);
        this.bookLayout = JournalBookBackground.compute(this.viewport.logicalWidth(), this.viewport.logicalHeight());
        this.rightPage = new RightPageContainer(this.bookLayout);
        this.welcomeStatsPanel = new WelcomeStatsPanel(this.bookLayout);

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
        this.rightPage.getLogPanel().restoreBatchSelection(
                snapshot.logBatchSelectionMode(), snapshot.selectedLogEntryIds());

        this.rebuildWidgets();
        // 仅在第一次 init 之后的 reposition 中恢复目录滚动位置。
        if (this.catalogPanel != null && snapshot.catalogScrollOffset() > 0) {
            this.catalogPanel.setScrollOffset(snapshot.catalogScrollOffset());
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
            renderCatalogArea(guiGraphics, logicalMouseX, logicalMouseY);
            if (this.viewModel.isCategoryHome()) this.welcomeStatsPanel.render(
                    guiGraphics, this.font, logicalMouseX, logicalMouseY);
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

    // 渲染左侧目录区域（滚动目录、解锁进度、空状态）
    private void renderCatalogArea(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.catalogPanel != null) {
            this.catalogPanel.render(guiGraphics, this.font, this.viewModel.selectedIndex(), mouseX, mouseY);
        }

        if (this.viewModel.totalTableCount() > 0) {
            renderCatalogProgress(guiGraphics, mouseX, mouseY);
        }

        if (!this.viewModel.isCategoryHome() && this.viewModel.isEmpty()) {
            int x = this.bookLayout.leftPageX()
                    + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                    + JournalLayout.CATALOG_X_OFFSET + JournalLayout.CATALOG_LEFT_PAD;
            int y = this.bookLayout.leftPageY() + JournalLayout.CATALOG_LIST_TOP;
            int width = JournalLayout.CATALOG_TEXTURE_WIDTH - JournalLayout.CATALOG_LEFT_PAD * 2;
            boolean hovered = isTextHovered(mouseX, mouseY, x, y, width);
            this.emptyCatalogScrollTicks = hovered ? this.emptyCatalogScrollTicks + 1 : 0;
            ScrollTextHelper.draw(guiGraphics, this.font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_catalog").getString(),
                    x, y, width, 0x7A6247, hovered, this.emptyCatalogScrollTicks, false);
        } else {
            this.emptyCatalogScrollTicks = 0;
        }
    }

    private void renderCatalogProgress(GuiGraphics graphics, int mouseX, int mouseY) {
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
        boolean hovered = isTextHovered(mouseX, mouseY, x + 2, y + 1, width - 4);
        this.catalogProgressScrollTicks = hovered ? this.catalogProgressScrollTicks + 1 : 0;
        ScrollTextHelper.draw(graphics, this.font, label.getString(), x + 2, y + 2, width - 4,
                0xFF3D2810, hovered, this.catalogProgressScrollTicks, true);
    }

    private boolean isTextHovered(int mouseX, int mouseY, int x, int y, int width) {
        return mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + this.font.lineHeight + 1;
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
        if (isLogToolbarVisible()) {
            this.logToolbar.renderTooltips(guiGraphics, mouseX, mouseY);
        }

        if (this.helpButton != null) {
            this.helpButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.managementButton != null) {
            this.managementButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }

        if (this.viewModel.isCategoryHome()) {
            this.welcomeStatsPanel.renderTooltip(guiGraphics, this.font, mouseX, mouseY);
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
            if (this.catalogPanel.beginScrollbarDrag(mouseX, mouseY)) {
                return true;
            }
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
            ResourceLocation navigationTarget = this.rightPage.consumeNavigationTarget();
            if (navigationTarget != null) {
                navigateToChildTable(navigationTarget);
            }
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
            this.catalogPanel.scrollByRows(scrollY < 0.0 ? 2 : -2);
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
        if (this.catalogPanel != null) {
            this.catalogPanel.endScrollbarDrag();
        }
        return super.mouseReleased(
                this.viewport.toLogicalX(mouseX), this.viewport.toLogicalY(mouseY), button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (this.catalogPanel != null
                && this.catalogPanel.dragScrollbar(this.viewport.toLogicalY(mouseY))) {
            return true;
        }
        return super.mouseDragged(
                this.viewport.toLogicalX(mouseX), this.viewport.toLogicalY(mouseY), button,
                this.viewport.toLogicalDistance(dragX), this.viewport.toLogicalDistance(dragY));
    }

    @Override
    protected void rebuildWidgets() {
        int previousCatalogScrollOffset = this.catalogPanel != null
                ? this.catalogPanel.getScrollOffset() : this.restoredCatalogScrollOffset;
        // 重建 widget 前，同步工具栏搜索/排序状态到 ViewModel，
        this.rebuildViewModels();
        this.clearWidgets();

        // 分组首页发起搜索时只刷新 ViewModel，因此书签必须预先注册，再通过可见性同步状态。
        this.addRenderableWidget(this.rightPage.getIntroTabButton());
        this.addRenderableWidget(this.rightPage.getArchaeologyTabButton());
        this.addRenderableWidget(this.rightPage.getLogTabButton());

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
            this.addRenderableWidget(new IconButton(
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
        }

        int buttonWidth = JournalLayout.PAGE_BUTTON_WIDTH;
        int buttonGap = JournalLayout.PAGE_BUTTON_CENTER_GAP;

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
            this.catalogPanel.setScrollOffset(previousCatalogScrollOffset);
            this.catalogPanel.ensureIndexVisible(this.categoryFocusIndex);
        } else {
            this.catalogPanel.setEntries(this.viewModel.buildCatalogEntries());
            this.catalogPanel.setScrollOffset(previousCatalogScrollOffset);
            this.catalogPanel.ensureIndexVisible(this.viewModel.selectedIndex());
        }
        this.restoredCatalogScrollOffset = 0;

        // 日志工具栏
        this.logToolbar.createWidgets(this, this.bookLayout, this.rightPage,
                this::openLogRetentionConfig, this::toggleBatchSelection, this::confirmDeleteSelectedEntries,
                this::confirmClearCurrentTableLogs, this::confirmClearAllLogs);

        // 日志详情页返回按钮（IconButton）
        this.rightPage.getLogDetailPanel().createBackButton(this::registerWidget,
                () -> this.rightPage.restoreLogSelection(null, false));

        // 日志详情页备注编辑按钮（IconButton）
        this.rightPage.getLogDetailPanel().createNoteButton(this::registerWidget, this::openNoteEditor);

        // 日志详情页删除按钮（IconButton）
        this.rightPage.getLogDetailPanel().createDeleteButton(this::registerWidget, this::confirmDeleteCurrentEntry);

        int sideTabX = Math.max(0, this.bookLayout.bookX()
                - JournalLayout.SIDE_TAB_WIDTH + JournalLayout.SIDE_TAB_OVERLAP);
        int managementY = this.bookLayout.bookY() + JournalLayout.SIDE_TAB_Y_OFFSET;
        this.managementButton = this.addRenderableWidget(new BookSideTabButton(
                sideTabX, managementY,
                JournalLayout.SIDE_TAB_WIDTH, JournalLayout.SIDE_TAB_HEIGHT,
                MANAGEMENT_TAB_TEXTURE,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.open"),
                () -> Objects.requireNonNull(this.minecraft).setScreen(new LootTableManagementScreen(this))
        ));
        int helpY = managementY + JournalLayout.SIDE_TAB_HEIGHT + JournalLayout.SIDE_TAB_GAP;
        this.helpButton = this.addRenderableWidget(new BookSideTabButton(
                sideTabX, helpY,
                JournalLayout.SIDE_TAB_WIDTH, JournalLayout.SIDE_TAB_HEIGHT,
                HELP_TAB_TEXTURE,
                buildHelpTooltip(),
                () -> ConfirmLinkScreen.confirmLinkNow(this, HELP_WIKI_URL, true)
        ));

        createExternalLinkButtons();

        // 日志列表页备注图标点击回调：直接打开备注编辑界面，无需进入详情页
        this.rightPage.getLogPanel().setNoteClickHandler(this::openNoteEditorForEntry);

        this.syncButtonState();
    }

    private void createExternalLinkButtons() {
        int buttonY = this.bookLayout.rightPageBottom() - WELCOME_LINK_BUTTON_SIZE - 8;
        int right = this.bookLayout.rightPageRight() - 10;
        List<ExternalLinkButton> buttons = new ArrayList<>(3);
        buttons.add(createExternalLinkButton(right - WELCOME_LINK_BUTTON_SIZE, buttonY,
                MODRINTH_ICON, MODRINTH_URL,
                "screen.unsuspiciousblock.archaeology_journal.welcome.link.modrinth"));
        right -= WELCOME_LINK_BUTTON_SIZE + WELCOME_LINK_BUTTON_GAP;
        buttons.add(createExternalLinkButton(right - WELCOME_LINK_BUTTON_SIZE, buttonY,
                CURSEFORGE_ICON, CURSEFORGE_URL,
                "screen.unsuspiciousblock.archaeology_journal.welcome.link.curseforge"));
        right -= WELCOME_LINK_BUTTON_SIZE + WELCOME_LINK_BUTTON_GAP;
        buttons.add(createExternalLinkButton(right - WELCOME_LINK_BUTTON_SIZE, buttonY,
                GITHUB_ICON, GITHUB_ISSUES_URL,
                "screen.unsuspiciousblock.archaeology_journal.welcome.link.github_issues"));
        this.externalLinkButtons = List.copyOf(buttons);
    }

    private ExternalLinkButton createExternalLinkButton(int x, int y, ResourceLocation icon,
                                                        String url, String tooltipKey) {
        return this.addRenderableWidget(new ExternalLinkButton(
                x, y, WELCOME_LINK_BUTTON_SIZE, this, icon, url, Component.translatable(tooltipKey)));
    }

    // 构建分层配色的帮助提示，长说明主动拆行以控制宽度。
    private static List<Component> buildHelpTooltip() {
        String tooltipBase = "screen.unsuspiciousblock.archaeology_journal.help_tooltip";
        return List.of(
                Component.translatable(tooltipBase + ".title")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.translatable(tooltipBase + ".favorite.label").withStyle(ChatFormatting.AQUA),
                tooltipDescription(tooltipBase + ".favorite"),
                Component.translatable(tooltipBase + ".probability.label").withStyle(ChatFormatting.AQUA),
                tooltipDescription(tooltipBase + ".probability.summary"),
                tooltipDescription(tooltipBase + ".probability.range"),
                Component.translatable(tooltipBase + ".sort.label").withStyle(ChatFormatting.AQUA),
                tooltipDescription(tooltipBase + ".sort"),
                Component.translatable(tooltipBase + ".wiki").withStyle(ChatFormatting.GREEN)
        );
    }

    private static Component tooltipDescription(String translationKey) {
        return Component.literal("  ")
                .append(Component.translatable(translationKey).withStyle(ChatFormatting.GRAY));
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
            this.rightPage.setTable(null, List.of(), List.of(), List.of(), 0, 0, null);
        } else {
            this.rightPage.setTable(result.tableId(), result.gridItems(), result.childTables(), result.introItems(),
                    result.parsedCount(), result.totalCount(), result.logRef());
        }
    }

    private void navigateToChildTable(ResourceLocation tableId) {
        ResourceLocation parentTableId = this.viewModel.selectedTableId();
        if (!this.viewModel.prepareNavigationTo(tableId)) {
            return;
        }
        this.catalogToolbar.setCurrentSearch(JournalSearchQuery.EMPTY);
        this.catalogToolbar.setHideLocked(false);
        this.catalogToolbar.setSearchExpanded(false);
        this.rebuildWidgets();
        int targetRow = this.viewModel.findNavigationRow(tableId, parentTableId);
        if (targetRow >= 0) {
            setSelectedIndex(targetRow);
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
        boolean showRightPageTabs = !this.viewModel.isCategoryHome();
        applyButtonState(this.rightPage.getIntroTabButton(), showRightPageTabs, showRightPageTabs);
        applyButtonState(this.rightPage.getArchaeologyTabButton(), showRightPageTabs, showRightPageTabs);
        applyButtonState(this.rightPage.getLogTabButton(), showRightPageTabs, showRightPageTabs);

        boolean hasMultipleItemPages = !this.viewModel.isCategoryHome() && this.rightPage.pageCount() > 1;
        applyButtonState(this.itemPrevButton, hasMultipleItemPages,
                hasMultipleItemPages && this.rightPage.getPage() > 0);
        applyButtonState(this.itemNextButton, hasMultipleItemPages,
                hasMultipleItemPages && this.rightPage.getPage() < this.rightPage.pageCount() - 1);

        boolean showExternalLinks = this.viewModel.isCategoryHome();
        for (ExternalLinkButton button : this.externalLinkButtons) {
            applyButtonState(button, showExternalLinks, showExternalLinks);
        }

        // 日志工具栏可见性
        boolean logToolbarVisible = isLogToolbarVisible();
        if (!logToolbarVisible && this.rightPage.getLogPanel().isBatchSelectionMode()) {
            this.rightPage.getLogPanel().setBatchSelectionMode(false);
        }
        this.logToolbar.syncVisibility(logToolbarVisible,
                this.rightPage.getLogPanel().isBatchSelectionMode(),
                this.rightPage.getLogPanel().getSelectedEntryCount());

        // 日志详情页返回/备注按钮可见性
        boolean isLogDetailMode = hasSelectedTable() && this.rightPage.isShowingLogDetail()
                && this.rightPage.getActiveTab() == RightPageContainer.Tab.LOG;
        applyButtonState(this.rightPage.getLogDetailPanel().getBackButton(), isLogDetailMode, isLogDetailMode);
        applyButtonState(this.rightPage.getLogDetailPanel().getNoteButton(), isLogDetailMode, isLogDetailMode);
        applyButtonState(this.rightPage.getLogDetailPanel().getDeleteButton(), isLogDetailMode, isLogDetailMode);
    }

    private static void applyButtonState(@Nullable AbstractWidget btn, boolean visible, boolean active) {
        if (btn != null) {
            btn.visible = visible;
            btn.active = active;
        }
    }

    // 日志控件统一使用这组状态判断，避免创建、同步和 tooltip 各自维护显示条件。
    private boolean hasSelectedTable() {
        return !this.viewModel.isCategoryHome() && this.viewModel.selectedTable() != null;
    }

    private boolean isLogListMode() {
        return hasSelectedTable() && this.rightPage != null
                && this.rightPage.getActiveTab() == RightPageContainer.Tab.LOG
                && !this.rightPage.isShowingLogDetail();
    }

    private boolean isLogToolbarVisible() {
        return isLogListMode() && this.rightPage.getLogPanel().hasVisibleEntries();
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

    private void openLogRetentionConfig() {
        ResourceLocation tableId = this.viewModel.selectedTableId();
        if (tableId == null) {
            return;
        }
        var history = ArchaeologyJournalClientState.getLogState().getTable(tableId);
        int retentionLimit = history != null
                ? history.getRetentionLimit()
                : com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE;
        int entryCount = history != null ? history.getTotalEntryCount() : 0;
        Objects.requireNonNull(this.minecraft, "minecraft must not be null while screen is active")
                .setScreen(new JournalLogRetentionScreen(this, tableId, retentionLimit, entryCount));
    }

    private void toggleBatchSelection() {
        var logPanel = this.rightPage.getLogPanel();
        logPanel.setBatchSelectionMode(!logPanel.isBatchSelectionMode());
        this.rightPage.restoreLogSelection(null, false);
        syncButtonState();
    }

    private void confirmDeleteSelectedEntries() {
        ResourceLocation tableId = this.viewModel.selectedTableId();
        List<UUID> selectedEntryIds = this.rightPage.getLogPanel().getSelectedEntryIds();
        if (tableId == null || selectedEntryIds.isEmpty()) {
            return;
        }
        openDeleteConfirmation(
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.batch_title"),
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.batch_message",
                        selectedEntryIds.size()),
                () -> {
                    for (int from = 0; from < selectedEntryIds.size();
                         from += DeleteJournalLogPayload.MAX_BATCH_ENTRIES) {
                        int to = Math.min(selectedEntryIds.size(),
                                from + DeleteJournalLogPayload.MAX_BATCH_ENTRIES);
                        Services.NETWORK.sendToServer(DeleteJournalLogPayload.batch(
                                tableId, selectedEntryIds.subList(from, to)));
                    }
                    this.rightPage.getLogPanel().setBatchSelectionMode(false);
                    syncButtonState();
                });
    }

    // 二次确认后删除当前表中的单条日志
    private void confirmDeleteCurrentEntry() {
        var detail = this.rightPage.getLogDetailPanel();
        ExcavationLogEntry entry = detail.getEntry();
        ResourceLocation tableId = detail.getTableId();
        if (entry == null || tableId == null) {
            return;
        }
        openDeleteConfirmation(
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.entry_title"),
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.entry_message"),
                () -> {
                    Services.NETWORK.sendToServer(DeleteJournalLogPayload.entry(tableId, entry.entryId()));
                    this.rightPage.restoreLogSelection(null, false);
                });
    }

    // 二次确认后清空当前战利品表的全部日志历史
    private void confirmClearCurrentTableLogs() {
        ResourceLocation tableId = this.viewModel.selectedTableId();
        if (tableId == null) {
            return;
        }
        var history = ArchaeologyJournalClientState.getLogState().getTable(tableId);
        int count = history != null ? history.getTotalEntryCount() : 0;
        openDeleteConfirmation(
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.table_title"),
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.table_message",
                        tableId.toString(), count),
                () -> Services.NETWORK.sendToServer(DeleteJournalLogPayload.table(tableId)));
    }

    // 二次确认后清空当前玩家的全部日志历史
    private void confirmClearAllLogs() {
        int count = ArchaeologyJournalClientState.getLogState().getTotalEntryCount();
        openDeleteConfirmation(
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.all_title"),
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.all_message", count),
                () -> Services.NETWORK.sendToServer(DeleteJournalLogPayload.all()));
    }

    private void openDeleteConfirmation(Component title, Component message, Runnable confirmedAction) {
        Objects.requireNonNull(this.minecraft, "minecraft must not be null while screen is active")
                .setScreen(new ConfirmScreen(confirmed -> {
                    Objects.requireNonNull(this.minecraft).setScreen(this);
                    if (confirmed) {
                        confirmedAction.run();
                    }
                }, title, message));
    }

    // 捕获当前 UI 状态快照，用于窗口 resize 后恢复
    private UiStateSnapshot captureUiState() {
        return new UiStateSnapshot(
                this.rightPage != null ? this.rightPage.getActiveTab() : RightPageContainer.Tab.INTRO,
                this.rightPage != null ? this.rightPage.getPage() : 0,
                this.catalogPanel != null ? this.catalogPanel.getScrollOffset() : 0,
                this.rightPage != null ? this.rightPage.getActiveItemTag() : null,
                this.rightPage != null && this.rightPage.isShowingLogDetail(),
                this.rightPage != null ? this.rightPage.getSelectedLogEntryId() : null,
                this.rightPage != null && this.rightPage.getLogPanel().isBatchSelectionMode(),
                this.rightPage != null ? this.rightPage.getLogPanel().getSelectedEntryIds() : List.of(),
                this.logToolbar.sortDescending(),
                this.logToolbar.groupMode(),
                this.catalogToolbar.currentSortOrder(),
                this.catalogToolbar.sortDescending(),
                this.catalogToolbar.currentSearch(),
                this.catalogToolbar.searchExpanded(),
                this.catalogToolbar.hideLocked()
        );
    }

    // 将物理屏幕坐标转换为手册逻辑坐标，并返回当前悬停的已解锁物品。
    public Optional<net.minecraft.world.item.ItemStack> getHoveredItemStack(double mouseX, double mouseY) {
        if (this.viewport == null || this.rightPage == null) {
            return Optional.empty();
        }
        double logicalMouseX = this.viewport.toLogicalX(mouseX);
        double logicalMouseY = this.viewport.toLogicalY(mouseY);
        ItemGridPanel.TooltipData tooltip = this.rightPage.getTooltipData(logicalMouseX, logicalMouseY);
        if (tooltip == null || tooltip.stack().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(tooltip.stack());
    }

    // UI 状态快照，用于窗口 resize 时保存/恢复跨布局重建的状态
    private record UiStateSnapshot(
            RightPageContainer.Tab tab,
            int rightPagePage,
            int catalogScrollOffset,
            @Nullable ResourceLocation activeItemTag,
            boolean logDetail,
            @Nullable UUID logEntryId,
            boolean logBatchSelectionMode,
            List<UUID> selectedLogEntryIds,
            boolean logSortDescending,
            LogGrouper.GroupMode groupMode,
            CatalogSorter.SortOrder catalogSortOrder,
            boolean catalogSortDescending,
            JournalSearchQuery catalogSearch,
            boolean catalogSearchExpanded,
            boolean catalogHideLocked
    ) {}

    }
