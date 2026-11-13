package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.journal.catalog.JournalViewModel;
import com.meteorite.unsuspiciousblock.client.ui.journal.catalog.CatalogToolbar;
import com.meteorite.unsuspiciousblock.client.state.SimulationPreferenceStore;
import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;

import java.util.Set;

import net.minecraft.world.item.ItemStack;
import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalControls;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.Util;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.journal.JournalReadingState;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalUiPreferencesStore;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.journal.catalog.CatalogPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.panel.WelcomeStatsPanel;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalLogLocalStore;
import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.DeleteJournalLogPayload;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import net.minecraft.client.gui.GuiGraphics;
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
public class ArchaeologyJournalScreen extends UiScreen {

    private static final String GITHUB_ISSUES_URL =
            "https://github.com/meteoritel/unsuspicious-block/issues";
    private static final String CURSEFORGE_URL =
            "https://www.curseforge.com/minecraft/mc-mods/unsuspicious-block";
    private static final String MODRINTH_URL =
            "https://modrinth.com/mod/unsuspicious-block";
    private static final String HELP_WIKI_URL =
            "https://github.com/meteoritel/unsuspicious-block/wiki";
    private static final int MINIMUM_CANVAS_WIDTH = 412;
    private static final int MINIMUM_CANVAS_HEIGHT = 256;

    @Override
    protected int minimumUiWidth() {
        return MINIMUM_CANVAS_WIDTH;
    }

    @Override
    protected int minimumUiHeight() {
        return MINIMUM_CANVAS_HEIGHT;
    }

    private static final int WELCOME_LINK_BUTTON_SIZE = 20;
    private static final int WELCOME_LINK_BUTTON_GAP = 4;

    private final JournalViewModel viewModel;
    private final CatalogToolbar catalogToolbar;
    private final LogToolbar logToolbar;

    private JournalBookBackground.BookLayout bookLayout;
    private CatalogPanel catalogPanel;
    private RightPageContainer rightPage;
    /**
     * 模态浮层：打开期间接管全部输入，是本页面唯一的浮层来源。
     */
    private final JournalOverlays overlays = new JournalOverlays();
    private WelcomeStatsPanel welcomeStatsPanel;
    private List<UiControl> externalLinkButtons = List.of();
    private int categoryFocusIndex;
    private int restoredCatalogScrollOffset;
    private int emptyCatalogScrollTicks;
    private int catalogProgressScrollTicks;
    @Nullable
    private final ResourceLocation initialItemSearch;
    private final UiChildren<UiWidget> widgets = new UiChildren<>();
    private final JournalContent content = new JournalContent();
    private final UiInputScope scope = new UiInputScope(content);
    private final UiModalStack confirmations = new UiModalStack(() -> UnsuspiciousUiTheme.PARCHMENT);
    private final UiNavigationHistory<NavigationState> history =
            new UiNavigationHistory<>(64);

    /**
     * 只恢复阅读状态与条件勾选，不回滚参数提交或服务端操作。
     */
    private record NavigationState(@Nullable ResourceLocation table, List<String> conditions, UiStateSnapshot view,
                                   boolean home, @Nullable ResourceLocation category, Set<ResourceLocation> expanded) {
    }

    private record NavigationKey(@Nullable ResourceLocation table, List<String> conditions, boolean home,
                                 @Nullable ResourceLocation category, RightPageContainer.Tab tab,
                                 boolean logDetail, @Nullable UUID logEntryId,
                                 @Nullable ResourceLocation activeItemTag) {
    }

    private NavigationState navigationState() {
        ResourceLocation table = viewModel.selectedTableId();
        var selection = table == null ? null : JournalClientConnection.scenario().selection(table);
        return rightPage == null ? null : new NavigationState(table,
                selection == null ? List.of() : selection.conditions(),
                captureUiState(), viewModel.isCategoryHomeMode(), viewModel.selectedCategory(), viewModel.expandedRoots());
    }

    private void rememberNavigation(NavigationState before) {
        NavigationState after = navigationState();
        if (before == null || after == null) return;
        if (!navigationKey(before).equals(navigationKey(after))) history.push(before);
    }

    private static NavigationKey navigationKey(NavigationState state) {
        RightPageContainer.ReadingState reading = state.view().rightPage();
        return new NavigationKey(state.table(), state.conditions(), state.home(), state.category(), reading.tab(),
                reading.logDetail(), reading.logEntryId(), reading.activeItemTag());
    }

    private boolean validNavigation(NavigationState state) {
        return state.home() || state.table() == null && viewModel.categoryViews().stream().anyMatch(category -> category.id().equals(state.category()))
                || state.table() != null && JournalClientConnection.scenario().table(state.table()) != null
                && viewModel.isNavigationTargetValid(state.table());
    }

    private void goBack() {
        history.back(this::validNavigation, state -> restoreUiState(state.view()));
    }

    public ArchaeologyJournalScreen(ArchaeologyJournalState state, @Nullable ResourceLocation initialItemSearch) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"));
        this.viewModel = new JournalViewModel(state);
        this.catalogToolbar = new CatalogToolbar(this::rebuildViewModels, this::rebuildWidgets);
        this.logToolbar = new LogToolbar();
        this.initialItemSearch = initialItemSearch;
        scope.setEscape(this::onClose);
        confirmations.setOnScopeChanged(this::refreshUiState);
    }

    @Override
    protected void init() {
        super.init();
        if (resumeStateEpoch != JournalClientConnection.epoch()) resumeState = null;
        this.bookLayout = JournalBookBackground.compute(viewport().logicalWidth(), viewport().logicalHeight());
        this.overlays.setBounds(viewport().logicalWidth(), viewport().logicalHeight());
        this.rightPage = new RightPageContainer(this.bookLayout, this.overlays, scope);
        this.welcomeStatsPanel = new WelcomeStatsPanel(this.bookLayout);

        // 恢复上次关闭时持久化的 UI 状态
        restorePersistedUiState();

        if (this.initialItemSearch != null && resumeState == null) {
            JournalSearchQuery search = JournalSearchQuery.forItemId(this.initialItemSearch);
            this.catalogToolbar.setCurrentSearch(search);
            this.catalogToolbar.setSearchExpanded(true);
            this.viewModel.applyCatalogState(this.catalogToolbar.queryState());
        }

        this.viewModel.reloadCatalog();
        this.viewModel.restoreDirectoryState(
                JournalUiPreferencesStore.isLastDirectoryHome(),
                JournalUiPreferencesStore.getLastDirectoryCategory(),
                JournalUiPreferencesStore.getExpandedDirectoryTables());
        this.categoryFocusIndex = JournalUiPreferencesStore.getLastCategoryFocus();
        this.restoredCatalogScrollOffset = JournalUiPreferencesStore.getLastDirectoryPage();
        this.viewModel.applyCatalogState(this.catalogToolbar.queryState());
        this.viewModel.rebuildViewModels();
        this.viewModel.initRevisions();
        this.rebuildWidgets();
        if (resumeState != null) {
            restoreUiState(resumeState);
            restorePendingReading();
        } else if (initialItemSearch == null) {
            pendingReading = JournalUiPreferencesStore.readingState();
            pendingReadingEpoch = JournalClientConnection.epoch();
            restorePendingReading();
        }
    }

    // 从 ClientState 恢复跨打开/关闭的 UI 状态
    private void restorePersistedUiState() {
        // 恢复目录工具栏状态
        CatalogSorter.SortOrder savedCatalogSortOrder = JournalUiPreferencesStore.getLastCatalogSortOrder();
        if (savedCatalogSortOrder != null) {
            this.catalogToolbar.setCurrentSortOrder(savedCatalogSortOrder);
        }
        this.catalogToolbar.setSortDescending(JournalUiPreferencesStore.getLastCatalogSortDescending());
        this.catalogToolbar.setHideLocked(JournalUiPreferencesStore.getLastCatalogHideLocked());

        // 恢复日志工具栏状态
        this.logToolbar.setSortDescending(JournalUiPreferencesStore.getLastLogSortDescending());
        LogGrouper.GroupMode savedLogGroupMode = JournalUiPreferencesStore.getLastLogGroupMode();
        if (savedLogGroupMode != null) {
            this.logToolbar.setGroupMode(savedLogGroupMode);
        }


    }

    @Override
    public void removed() {
        resumeState = captureUiState();
        resumeStateEpoch = JournalClientConnection.epoch();
        JournalUiPreferencesStore.rememberReadingState(pendingReading != null ? pendingReading
                : new JournalReadingState(resumeState.table(), resumeState.rightPage()));
        this.overlays.close();
        this.confirmations.clear();
        SimulationPreferenceStore.flush();
        JournalUiPreferencesStore.rememberDirectoryState(this.viewModel.isCategoryHomeMode(),
                this.viewModel.selectedCategory(), this.viewModel.expandedRoots(),
                this.catalogPanel != null ? this.catalogPanel.getScrollOffset() : 0, this.categoryFocusIndex);
        // 持久化目录工具栏状态
        JournalUiPreferencesStore.setLastCatalogSortOrder(this.catalogToolbar.currentSortOrder());
        JournalUiPreferencesStore.setLastCatalogSortDescending(this.catalogToolbar.sortDescending());
        JournalUiPreferencesStore.setLastCatalogHideLocked(this.catalogToolbar.hideLocked());
        JournalUiPreferencesStore.setLastCatalogSearchText("");
        this.catalogToolbar.setCurrentSearch(JournalSearchQuery.EMPTY);
        this.catalogToolbar.setSearchExpanded(false);
        // 持久化日志工具栏状态
        JournalUiPreferencesStore.setLastLogSortDescending(this.logToolbar.sortDescending());
        JournalUiPreferencesStore.setLastLogGroupMode(this.logToolbar.groupMode());
        JournalUiPreferencesStore.flushIfDirty();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected UiInputScope inputScope() {
        if (!confirmations.isEmpty()) return confirmations.top().inputScope();
        return overlays.isOpen() ? overlays.inputScope() : scope;
    }

    @Override
    protected void prepareUi() {
        if (rightPage == null) return;
        refreshAndSync();
        ResourceLocation navigationTarget = rightPage.consumeNavigationTarget();
        if (navigationTarget != null) navigateToChildTable(navigationTarget);
        rightPage.prepareUi();
        overlays.prepare(font);
        syncButtonState();
        confirmations.prepareUi();
        applySearchFocusRequest();
    }

    @Override
    protected void invalidateUiResources() {
        if (rightPage != null) rebuildViewModels();
    }

    @Override
    protected void advanceUiFrame() {
        confirmations.tick();
        if (this.catalogPanel != null) {
            this.catalogPanel.advanceFrame();
        }
    }

    @Override
    protected void layoutUi() {
        confirmations.setBounds(0, 0, viewport().logicalWidth(), viewport().logicalHeight());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        pendingReading = null;
        NavigationState before = navigationState();
        try {
            if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (catalogToolbar.isSearchFocused()) return false;
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                goBack();
                return true;
            }
            if (this.minecraft != null && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
                onClose();
                return true;
            }
            return false;
        } finally {
            if (keyCode != GLFW.GLFW_KEY_BACKSPACE) rememberNavigation(before);
        }
    }

    // 分类首页按网格导航；目录列表使用上下选择、左右展开折叠，Page Up/Down 跨越一个视口。
    private boolean handleCatalogNavigation(int keyCode) {
        if (this.catalogPanel == null) return false;
        if (this.viewModel.isCategoryHome()) return handleCategoryNavigation(keyCode);
        if (this.viewModel.tableViews().isEmpty()) return false;
        return switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> moveCatalogSelection(-1);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> moveCatalogSelection(1);
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> {
                setCurrentExpansion(false);
                yield true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> {
                setCurrentExpansion(true);
                yield true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                updateItemGridPanel();
                yield true;
            }
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
            default -> {
                return false;
            }
        }
        this.categoryFocusIndex = Mth.clamp(target, 0, count - 1);
        this.catalogPanel.ensureIndexVisible(this.categoryFocusIndex, true);
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
        this.overlays.close();
        this.confirmations.clear();

        this.bookLayout = JournalBookBackground.compute(viewport().logicalWidth(), viewport().logicalHeight());
        this.overlays.setBounds(viewport().logicalWidth(), viewport().logicalHeight());
        this.rightPage = new RightPageContainer(this.bookLayout, this.overlays, scope);
        this.welcomeStatsPanel = new WelcomeStatsPanel(this.bookLayout);

        restoreUiState(snapshot);
    }

    private void restoreUiState(UiStateSnapshot snapshot) {
        this.logToolbar.setSortDescending(snapshot.logSortDescending());
        this.logToolbar.setGroupMode(snapshot.groupMode());
        this.rightPage.getLogPanel().setSortDescending(this.logToolbar.sortDescending());
        this.rightPage.getLogPanel().setGroupMode(this.logToolbar.groupMode());

        this.catalogToolbar.setCurrentSortOrder(snapshot.catalogSortOrder());
        this.catalogToolbar.setSortDescending(snapshot.catalogSortDescending());
        this.catalogToolbar.setCurrentSearch(snapshot.catalogSearch());
        this.catalogToolbar.setSearchExpanded(snapshot.catalogSearchExpanded());
        this.catalogToolbar.setHideLocked(snapshot.catalogHideLocked());
        this.viewModel.applyCatalogState(this.catalogToolbar.queryState());
        this.viewModel.restoreDirectoryState(snapshot.home(), snapshot.category(), snapshot.expanded());
        this.rebuildViewModels();
        if (snapshot.table() != null) {
            setSelectedTable(snapshot.table());
            var choice = JournalClientConnection.scenario().selection(snapshot.table());
            if (choice != null) {
                JournalClientConnection.scenario().select(snapshot.table(), snapshot.conditions(), choice.params());
            }
        }

        this.rebuildWidgets();
        restoreRightPage(snapshot.table(), snapshot.rightPage());
        this.categoryFocusIndex = snapshot.categoryFocusIndex();
        if (this.catalogPanel != null) {
            this.catalogPanel.setScrollOffset(snapshot.catalogScrollOffset());
        }
        this.syncButtonState();
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    protected void renderUi(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {

        int underlayMouseX = (this.overlays.isOpen() || !confirmations.isEmpty()) ? -10000 : mouseX;
        int underlayMouseY = (this.overlays.isOpen() || !confirmations.isEmpty()) ? -10000 : mouseY;
        JournalBookBackground.render(guiGraphics, this.bookLayout);
        renderCatalogArea(guiGraphics, underlayMouseX, underlayMouseY);
        if (this.viewModel.isCategoryHome()) this.welcomeStatsPanel.render(
                guiGraphics, this.font);
        else if (this.viewModel.selectedTable() == null) renderEmptyCategoryPage(guiGraphics);
        else this.rightPage.render(guiGraphics, this.font, underlayMouseX, underlayMouseY);
        UiPointer pointer = scope.pointer(mouseX, mouseY).resolve(content);
        if (hasSelectedTable()) rightPage.renderControls(guiGraphics, font, pointer);
        for (UiWidget widget : widgets) if (widget.isVisible()) widget.render(guiGraphics, font, pointer);
        // 覆盖面使用各自的 kit 层，普通控件提示来自根的同一次命中。
        this.overlays.render(guiGraphics, this.font, mouseX, mouseY);
        confirmations.render(guiGraphics, font, inputScope().pointer(mouseX, mouseY));
        if (pointer.allowsTooltip() && pointer.hit() != null)
            pointer.hit().renderTooltip(guiGraphics, font, UnsuspiciousUiTheme.PARCHMENT.tooltipSkin(), mouseX, mouseY);
    }

    // 检测服务端数据变更并按需刷新
    private void refreshAndSync() {
        // 搜索输入防抖：到点才提交一次，避免逐字符重建整份目录投影
        this.catalogToolbar.flushPendingSearchIfDue();
        if (this.viewModel.refreshIfNeeded()) {
            this.viewModel.applyCatalogState(this.catalogToolbar.queryState());
            this.viewModel.rebuildViewModels();
            syncCatalogProjection();
            this.updateItemGridPanel();
            restorePendingReading();
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
            TextScroll.draw(guiGraphics, this.font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_catalog").getString(),
                    x, y, width, UiTextScheme.PARCHMENT.label(), hovered, this.emptyCatalogScrollTicks, false);
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
        Component label = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.unlocked_tables", unlocked, total);
        boolean hovered = isTextHovered(mouseX, mouseY, x + 2, y + 1, width - 4);
        this.catalogProgressScrollTicks = hovered ? this.catalogProgressScrollTicks + 1 : 0;
        JournalPalette.drawProgressBar(graphics, this.font, x, y, width, height, unlocked, total,
                label, hovered, this.catalogProgressScrollTicks);
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
            graphics.drawString(this.font, line, x, y, UiTextScheme.PARCHMENT.label(), false);
            y += this.font.lineHeight + 2;
        }
    }

    private UiPointerHit catalogTooltip(UiPointerHit hit) {
        int index = hit.rowIndex(catalogPanel);
        if (viewModel.isCategoryHome() && index >= 0 && index < viewModel.categoryViews().size()) {
            var category = viewModel.categoryViews().get(index);
            return hit.withTextTooltip(List.of(
                    category.name().copy().withStyle(TooltipBuilder.TITLE),
                    category.description().copy().withStyle(TooltipBuilder.LABEL)), catalogPanel.bounds());
        }
        if (!viewModel.rowIsChild(index)) return hit;
        List<Component> lines = new ArrayList<>();
        List<Component> parents = viewModel.rowReferencingParentNames(index);
        lines.add(Component.translatable("screen.unsuspiciousblock.archaeology_journal.child_references", parents.size())
                .withStyle(TooltipBuilder.TITLE));
        parents.forEach(name -> lines.add(Component.literal("∈ ").withStyle(TooltipBuilder.ACCENT)
                .append(name.copy().withStyle(TooltipBuilder.BODY))));
        lines.add(Component.translatable("screen.unsuspiciousblock.archaeology_journal.child_probability_short")
                .withStyle(TooltipBuilder.LABEL));
        return hit.withTextTooltip(lines, catalogPanel.bounds());
    }

    private @Nullable UiStateSnapshot resumeState;
    private long resumeStateEpoch;
    private @Nullable JournalReadingState pendingReading;
    private long pendingReadingEpoch;

    private void restorePendingReading() {
        JournalReadingState target = pendingReading;
        if (target == null) return;
        if (pendingReadingEpoch != JournalClientConnection.epoch()) {
            pendingReading = null;
            return;
        }
        if (!ArchaeologyJournalClientState.isReadingReady()) return;
        pendingReading = null;
        if (target.table() == null) {
            viewModel.showCategoryHome();
            rebuildViewModels();
        }
        if (target.table() != null && viewModel.isNavigationTargetValid(target.table())) {
            if (catalogToolbar.effectiveSearch().isEmpty()) viewModel.prepareNavigationTo(target.table());
            viewModel.rebuildViewModels();
            syncCatalogProjection();
            setSelectedTable(target.table());
        }
        updateItemGridPanel();
        restoreRightPage(target.table(), target.rightPage());
        syncButtonState();
    }

    private void restoreRightPage(@Nullable ResourceLocation table, RightPageContainer.ReadingState reading) {
        if (Objects.equals(table, viewModel.selectedTableId())) rightPage.restoreReadingState(reading);
        else rightPage.restoreReadingState(new RightPageContainer.ReadingState(reading.tab(),
                JournalReadingState.PagePositions.empty(), null, false, null, false, List.of(), reading.conditionOffsets()));
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        pendingReading = null;
        NavigationState before = navigationState();
        try {
            return super.mouseClicked(x, y, button);
        } finally {
            rememberNavigation(before);
        }
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        NavigationState before = navigationState();
        try {
            return super.mouseReleased(x, y, button);
        } finally {
            rememberNavigation(before);
        }
    }

    // 书页内容与控件共用根命中；页内业务由当前页容器处理。
    private final class JournalContent implements UiInputContainer {
        private final UiChildren<UiInputTarget> members = new UiChildren<>();

        private void syncMembers() {
            List<UiInputTarget> next = new ArrayList<>();
            if (catalogPanel != null) next.add(catalogPanel);
            if (viewModel.isCategoryHome() && welcomeStatsPanel != null) next.add(welcomeStatsPanel);
            else if (hasSelectedTable()) next.add(rightPage);
            next.add(widgets);
            if (members.equals(next)) return;
            for (UiInputTarget previous : List.copyOf(members))
                if (!next.contains(previous)) {
                    scope.interaction().end(UiInteractionSession.EndReason.UNMOUNTED);
                    previous.scopeExited(UiInteractionSession.EndReason.UNMOUNTED);
                }
            members.clear();
            members.addAll(next);
            members.setTabOrder(widgets, -1);
        }

        @Override
        public UiChildren<UiInputTarget> inputChildren() {
            return members;
        }

        @Override
        public UiPointerHit hitTest(double x, double y) {
            UiPointerHit hit = UiInputContainer.super.hitTest(x, y);
            return hit != null && catalogPanel != null && hit.owns(catalogPanel) ? catalogTooltip(hit) : hit;
        }

        @Override
        public boolean mousePressed(UiInputContext context) {
            boolean consumed = UiInputContainer.super.mousePressed(context);
            if (consumed && context.hits(rightPage)) {
                ResourceLocation target = rightPage.consumeNavigationTarget();
                if (target != null) navigateToChildTable(target);
                syncButtonState();
            }
            return consumed;
        }

        @Override
        public boolean charTyped(char value, int modifiers) {
            pendingReading = null;
            return UiInputContainer.super.charTyped(value, modifiers);
        }

        @Override
        public boolean mouseScrolled(UiInputContext context, double sx, double sy) {
            if (UiInputContainer.super.mouseScrolled(context, sx, sy)) return true;
            if (!context.hits(rightPage)) return false;
            rightPage.handleScroll(sy);
            syncButtonState();
            return true;
        }
    }

    private void activateCatalog(CatalogPanel.ClickResult click, boolean shift) {
        int index = click.index();
        if (index < 0) return;
        if (viewModel.isCategoryHome()) {
            categoryFocusIndex = index;
            enterFocusedCategory();
        } else if (click.toggleExpansion()) {
            ResourceLocation selected = viewModel.tableViews().get(index).id();
            viewModel.toggleExpanded(index);
            rebuildViewModels();
            setSelectedTable(selected);
        } else if (shift) {
            JournalUiPreferencesStore.toggleFavorite(viewModel.tableViews().get(index).id());
            rebuildViewModels();
        } else setSelectedIndex(index);
    }

    @Override
    protected void rebuildWidgets() {
        int previousCatalogScrollOffset = this.catalogPanel != null
                ? this.catalogPanel.getScrollOffset() : this.restoredCatalogScrollOffset;
        // 同步目录投影后统一提交视图，保留跨模式的目录偏移。
        this.rebuildViewModels(false);
        scope.interaction().end(UiInteractionSession.EndReason.UNMOUNTED);
        widgets.scopeExited(UiInteractionSession.EndReason.UNMOUNTED);
        this.widgets.clear();

        // 右页 chrome 由容器统一持有，根只注册实际控件。
        this.rightPage.chromeControls().forEach(this::registerWidget);

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
        applySearchFocusRequest();
        this.catalogToolbar.setCategoryHomeMode(categoryHomeMode);

        if (showDirectoryBack) {
            int backX = this.bookLayout.leftPageX()
                    + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                    + JournalLayout.CATALOG_X_OFFSET;
            int backY = this.bookLayout.leftPageY() + JournalLayout.TOOLBAR_Y;
            this.registerWidget(JournalControls.iconButton(
                    backX, backY, JournalLayout.SEARCH_ICON_SIZE, UiAtlas.Icon.BACK,
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

        boolean isNewCatalogPanel = this.catalogPanel == null;
        if (isNewCatalogPanel) this.catalogPanel = new CatalogPanel(this.bookLayout);
        else this.catalogPanel.applyLayout(this.bookLayout);
        this.catalogPanel.setNavigation(this::handleCatalogNavigation);
        this.catalogPanel.setOnActivate(this::activateCatalog);
        CatalogPanel.Mode previousMode = catalogPanel.mode();
        syncCatalogProjection();
        if (isNewCatalogPanel || previousMode != catalogPanel.mode()) {
            catalogPanel.setScrollOffset(previousCatalogScrollOffset);
            catalogPanel.ensureIndexVisible(viewModel.isCategoryHome() ? categoryFocusIndex : viewModel.selectedIndex());
        }
        this.restoredCatalogScrollOffset = 0;

        // 日志工具栏
        this.logToolbar.createWidgets(this, this.bookLayout, this.rightPage.getLogPanel(),
                this::openLogRetentionConfig, this::toggleBatchSelection, this::confirmDeleteSelectedEntries,
                this::confirmClearCurrentTableLogs, this::confirmClearAllLogs);

        // 日志详情页返回按钮（kit 控件）
        this.rightPage.getLogDetailPanel().createBackButton(this::registerWidget,
                () -> this.rightPage.restoreLogSelection(null, false));

        // 日志详情页备注编辑按钮（kit 控件）
        this.rightPage.getLogDetailPanel().createNoteButton(this::registerWidget, this::openNoteEditor);

        // 日志详情页删除按钮（kit 控件）
        this.rightPage.getLogDetailPanel().createDeleteButton(this::registerWidget, this::confirmDeleteCurrentEntry);

        int sideTabX = Math.max(0, this.bookLayout.bookX()
                - JournalLayout.SIDE_TAB_WIDTH + JournalLayout.SIDE_TAB_OVERLAP);
        int managementY = this.bookLayout.bookY() + JournalLayout.SIDE_TAB_Y_OFFSET;
        this.registerWidget(JournalControls.sideTab(
                sideTabX, managementY,
                JournalLayout.SIDE_TAB_WIDTH, JournalLayout.SIDE_TAB_HEIGHT,
                UiAtlas.SideTabKind.MANAGEMENT, UiAtlas.Icon.GEAR,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.open"),
                () -> Objects.requireNonNull(this.minecraft).setScreen(new LootTableManagementScreen(this))
        ));
        int helpY = managementY + JournalLayout.SIDE_TAB_HEIGHT + JournalLayout.SIDE_TAB_GAP;
        this.registerWidget(JournalControls.sideTab(
                sideTabX, helpY,
                JournalLayout.SIDE_TAB_WIDTH, JournalLayout.SIDE_TAB_HEIGHT,
                UiAtlas.SideTabKind.HELP, UiAtlas.Icon.HELP_CREAM,
                buildHelpTooltip(),
                () -> confirmExternalLink(HELP_WIKI_URL)
        ));

        createExternalLinkButtons();

        // 日志列表页备注图标点击回调：直接打开备注编辑界面，无需进入详情页
        this.rightPage.getLogPanel().setNoteClickHandler(this::openNoteEditorForEntry);

        this.syncButtonState();
    }

    private void createExternalLinkButtons() {
        int buttonY = this.bookLayout.rightPageBottom() - WELCOME_LINK_BUTTON_SIZE - 8;
        int right = this.bookLayout.rightPageRight() - 10;
        List<UiControl> buttons = new ArrayList<>(3);
        buttons.add(createExternalLinkButton(right - WELCOME_LINK_BUTTON_SIZE, buttonY,
                UiAtlas.EXTERNAL_MODRINTH, MODRINTH_URL,
                "screen.unsuspiciousblock.archaeology_journal.welcome.link.modrinth"));
        right -= WELCOME_LINK_BUTTON_SIZE + WELCOME_LINK_BUTTON_GAP;
        buttons.add(createExternalLinkButton(right - WELCOME_LINK_BUTTON_SIZE, buttonY,
                UiAtlas.EXTERNAL_CURSEFORGE, CURSEFORGE_URL,
                "screen.unsuspiciousblock.archaeology_journal.welcome.link.curseforge"));
        right -= WELCOME_LINK_BUTTON_SIZE + WELCOME_LINK_BUTTON_GAP;
        buttons.add(createExternalLinkButton(right - WELCOME_LINK_BUTTON_SIZE, buttonY,
                UiAtlas.EXTERNAL_GITHUB, GITHUB_ISSUES_URL,
                "screen.unsuspiciousblock.archaeology_journal.welcome.link.github_issues"));
        this.externalLinkButtons = List.copyOf(buttons);
    }

    private UiControl createExternalLinkButton(int x, int y, UiIcon icon,
                                               String url, String tooltipKey) {
        UiControl button = JournalControls.iconButton(x, y, WELCOME_LINK_BUTTON_SIZE, icon,
                Component.translatable(tooltipKey), () -> confirmExternalLink(url));
        return registerWidget(button);
    }

    // 构建分层配色的帮助提示，统一使用 TooltipBuilder 暗底亮色语义常量。
    private static List<Component> buildHelpTooltip() {
        String tooltipBase = "screen.unsuspiciousblock.archaeology_journal.help_tooltip";
        return List.of(
                Component.translatable(tooltipBase + ".title")
                        .withStyle(TooltipBuilder.TITLE),
                Component.translatable(tooltipBase + ".favorite.label").withStyle(TooltipBuilder.ACCENT),
                tooltipDescription(tooltipBase + ".favorite"),
                Component.translatable(tooltipBase + ".probability.label").withStyle(TooltipBuilder.ACCENT),
                tooltipDescription(tooltipBase + ".probability.summary"),
                tooltipDescription(tooltipBase + ".probability.range"),
                Component.translatable(tooltipBase + ".sort.label").withStyle(TooltipBuilder.ACCENT),
                tooltipDescription(tooltipBase + ".sort"),
                Component.translatable(tooltipBase + ".wiki").withStyle(TooltipBuilder.POSITIVE)
        );
    }

    private static Component tooltipDescription(String translationKey) {
        return Component.literal("  ")
                .append(Component.translatable(translationKey).withStyle(TooltipBuilder.LABEL));
    }

    private void rebuildViewModels() {
        rebuildViewModels(true);
    }

    // 控件重建统一提交目录投影，普通数据刷新直接同步现有面板。
    private void rebuildViewModels(boolean syncCatalogPanel) {
        // 同步目录工具栏状态到 ViewModel（含防抖窗口内尚未提交的搜索草稿）
        this.viewModel.applyCatalogState(this.catalogToolbar.queryState());
        this.viewModel.rebuildViewModels();
        if (syncCatalogPanel) syncCatalogProjection();
        updateItemGridPanel();
        syncButtonState();
    }

    private void syncCatalogProjection() {
        if (catalogPanel == null) return;
        if (viewModel.isCategoryHome()) catalogPanel.setCategories(viewModel.categoryViews());
        else catalogPanel.setEntries(viewModel.buildCatalogEntries());
        catalogPanel.ensureIndexVisible(viewModel.isCategoryHome() ? categoryFocusIndex : viewModel.selectedIndex());
    }

    private void applySearchFocusRequest() {
        content.syncMembers();
        if (!catalogToolbar.isSearchFocusRequested()) return;
        catalogToolbar.clearSearchFocusRequest();
        if (catalogToolbar.searchExpanded()) {
            scope.refreshFocus();
            scope.focus().focusOn(catalogToolbar.searchField());
        }
    }

    @Override
    protected void setInitialFocus() {
        if (initialItemSearch != null && catalogToolbar.searchExpanded()) {
            scope.refreshFocus();
            scope.focus().focusOn(catalogToolbar.searchField());
        }
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
        if (parentTableId == null || !this.viewModel.prepareNavigationToChild(parentTableId, tableId)) {
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
            this.catalogPanel.ensureIndexVisible(this.viewModel.selectedIndex(), true);
        }
        updateItemGridPanel();
        syncButtonState();
    }

    public <T extends UiWidget> T registerWidget(T widget) {
        widgets.add(widget);
        return widget;
    }

    private void syncButtonState() {
        boolean showRightPageTabs = !this.viewModel.isCategoryHome();
        this.rightPage.setChromeAvailable(showRightPageTabs);

        boolean showExternalLinks = this.viewModel.isCategoryHome();
        for (UiControl button : this.externalLinkButtons) {
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
        content.syncMembers();
    }

    private static void applyButtonState(@Nullable UiControl btn, boolean visible, boolean active) {
        if (btn != null) {
            btn.setVisible(visible);
            btn.setEnabled(active);
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
        var history = ArchaeologyJournalLogLocalStore.getState().getTable(tableId);
        int retentionLimit = history != null
                ? history.getRetentionLimit()
                : ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE;
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
                        ClientNetwork.sendToServer(DeleteJournalLogPayload.batch(
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
                    ClientNetwork.sendToServer(DeleteJournalLogPayload.entry(tableId, entry.entryId()));
                    this.rightPage.restoreLogSelection(null, false);
                });
    }

    // 二次确认后清空当前战利品表的全部日志历史
    private void confirmClearCurrentTableLogs() {
        ResourceLocation tableId = this.viewModel.selectedTableId();
        if (tableId == null) {
            return;
        }
        var history = ArchaeologyJournalLogLocalStore.getState().getTable(tableId);
        int count = history != null ? history.getTotalEntryCount() : 0;
        openDeleteConfirmation(
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.table_title"),
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.table_message",
                        tableId.toString(), count),
                () -> ClientNetwork.sendToServer(DeleteJournalLogPayload.table(tableId)));
    }

    // 二次确认后清空当前玩家的全部日志历史
    private void confirmClearAllLogs() {
        int count = ArchaeologyJournalLogLocalStore.getState().getTotalEntryCount();
        openDeleteConfirmation(
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.all_title"),
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_delete.all_message", count),
                () -> ClientNetwork.sendToServer(DeleteJournalLogPayload.all()));
    }

    private void openDeleteConfirmation(Component title, Component message, Runnable confirmedAction) {
        confirmations.push(UiModal.create(() -> UnsuspiciousUiTheme.PARCHMENT, font).title(title).message(message)
                .confirm(Component.translatable("gui.yes"), confirmedAction::run)
                .cancel(Component.translatable("gui.cancel")));
    }

    private void confirmExternalLink(String url) {
        confirmations.push(UiModal.create(() -> UnsuspiciousUiTheme.PARCHMENT, font)
                .title(Component.translatable("chat.link.confirm"))
                .message(Component.literal(url))
                .confirm(Component.translatable("chat.link.open"), () -> Util.getPlatform().openUri(url))
                .confirm(Component.translatable("chat.copy"), () -> Objects.requireNonNull(minecraft).keyboardHandler.setClipboard(url))
                .cancel(Component.translatable("gui.cancel")));
    }

    // 捕获当前 UI 状态快照，用于窗口 resize 后恢复
    private UiStateSnapshot captureUiState() {
        ResourceLocation table = this.viewModel.selectedTableId();
        var selection = table == null ? null : JournalClientConnection.scenario().selection(table);
        return new UiStateSnapshot(
                this.rightPage != null ? this.rightPage.captureReadingState() : RightPageContainer.ReadingState.empty(),
                this.catalogPanel != null ? this.catalogPanel.getScrollOffset() : 0,
                this.logToolbar.sortDescending(),
                this.logToolbar.groupMode(),
                this.catalogToolbar.currentSortOrder(),
                this.catalogToolbar.sortDescending(),
                this.catalogToolbar.effectiveSearch(),
                this.catalogToolbar.searchExpanded(),
                this.catalogToolbar.hideLocked(),
                table,
                selection == null ? List.of() : List.copyOf(selection.conditions()),
                this.viewModel.isCategoryHomeMode(),
                this.viewModel.selectedCategory(),
                this.viewModel.expandedRoots(),
                this.categoryFocusIndex
        );
    }

    // 将原版 GUI 坐标转换为手册逻辑坐标，并返回当前悬停的已解锁物品。
    public Optional<ItemStack> getHoveredItemStack(double mouseX, double mouseY) {
        if (this.rightPage == null || (this.overlays.isOpen() || !confirmations.isEmpty())) {
            return Optional.empty();
        }
        double logicalMouseX = viewport().toLogical(mouseX);
        double logicalMouseY = viewport().toLogical(mouseY);
        prepareUi();
        UiPointer pointer = scope.pointer(logicalMouseX, logicalMouseY).resolve(content);
        if (!pointer.allowsTooltip()) return Optional.empty();
        UiPointerHit hit = pointer.hit();
        if (hit == null) return Optional.empty();
        for (UiPointerHit.Row row : hit.rows()) {
            if (row.key() instanceof ItemStack stack && !stack.isEmpty()) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    // UI 状态快照，用于窗口 resize 时保存/恢复跨布局重建的状态
    private record UiStateSnapshot(
            RightPageContainer.ReadingState rightPage,
            int catalogScrollOffset,
            boolean logSortDescending,
            LogGrouper.GroupMode groupMode,
            CatalogSorter.SortOrder catalogSortOrder,
            boolean catalogSortDescending,
            JournalSearchQuery catalogSearch,
            boolean catalogSearchExpanded,
            boolean catalogHideLocked,
            @Nullable ResourceLocation table,
            List<String> conditions,
            boolean home,
            @Nullable ResourceLocation category,
            Set<ResourceLocation> expanded,
            int categoryFocusIndex
    ) {
    }

}
