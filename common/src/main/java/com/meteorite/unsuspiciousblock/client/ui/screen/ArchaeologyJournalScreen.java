package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
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
import java.util.UUID;

public class ArchaeologyJournalScreen extends Screen {

    private final JournalViewModel viewModel;
    private final CatalogToolbar catalogToolbar;
    private final LogToolbar logToolbar;

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

    public ArchaeologyJournalScreen(ArchaeologyJournalState state) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"));
        this.viewModel = new JournalViewModel(state);
        this.catalogToolbar = new CatalogToolbar(this::rebuildWidgets);
        this.logToolbar = new LogToolbar();
    }

    @Override
    protected void init() {
        super.init();
        this.bookLayout = JournalBookBackground.compute(this.width, this.height);
        this.rightPage = new RightPageContainer(this.bookLayout);

        // 恢复上次关闭时持久化的 UI 状态
        restorePersistedUiState();

        this.viewModel.reloadCatalog();
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
            if (this.catalogToolbar.handleEsc()) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void repositionElements() {
        UiStateSnapshot snapshot = captureUiState();

        this.bookLayout = JournalBookBackground.compute(this.width, this.height);
        this.rightPage = new RightPageContainer(this.bookLayout);

        this.updateItemGridPanel();
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
        refreshAndSync();
        JournalBookBackground.render(guiGraphics, this.bookLayout);
        renderTitle(guiGraphics);
        renderCatalogArea(guiGraphics, mouseX, mouseY);
        this.rightPage.render(guiGraphics, this.font, mouseX, mouseY);
        this.catalogToolbar.renderSearchBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderOverlays(guiGraphics, mouseX, mouseY);
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
                (this.width - titleWidth) / 2, this.bookLayout.bookY() + 2, 0x4A3320, false);
    }

    // 渲染左侧目录区域（目录面板、解锁进度、翻页指示器、空状态）
    private void renderCatalogArea(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.catalogPanel != null) {
            this.catalogPanel.render(guiGraphics, this.font, this.viewModel.selectedIndex(), mouseX, mouseY);
        }

        if (!this.viewModel.isEmpty()) {
            Component unlockedTables = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.unlocked_tables",
                    this.viewModel.unlockedTableCount(), this.viewModel.totalTableCount());
            int unlockedTablesWidth = this.font.width(unlockedTables);
            int unlockedTablesX = this.bookLayout.leftPageX()
                    + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                    + JournalLayout.CATALOG_X_OFFSET
                    + (JournalLayout.CATALOG_TEXTURE_WIDTH - unlockedTablesWidth) / 2;
            guiGraphics.drawString(this.font, unlockedTables,
                    unlockedTablesX,
                    this.bookLayout.leftPageY() + JournalLayout.CATALOG_SUMMARY_Y, 0x6E5A42, false);
        }

        if (this.catalogPageIndicator != null && this.catalogPanel != null && !this.viewModel.isEmpty()) {
            this.catalogPageIndicator.setPage(this.catalogPanel.getPage(), this.catalogPanel.pageCount());
            this.catalogPageIndicator.render(guiGraphics, this.font);
        }

        if (this.viewModel.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_catalog"),
                    this.bookLayout.leftPageX()
                            + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                            + JournalLayout.CATALOG_X_OFFSET
                            + JournalLayout.CATALOG_LEFT_PAD,
                    this.bookLayout.leftPageY() + JournalLayout.CATALOG_LIST_TOP, 0x7A6247, false);
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

        this.rightPage.renderTooltips(guiGraphics, this.font, mouseX, mouseY);

        ItemGridPanel.TooltipData tooltipData = this.rightPage.getTooltipData(mouseX, mouseY);
        if (tooltipData != null && !tooltipData.stack().isEmpty()) {
            List<Component> tooltipLines = JournalTooltipBuilder.build(tooltipData);
            guiGraphics.renderTooltip(this.font, tooltipLines, tooltipData.stack().getTooltipImage(), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            syncButtonState();
            return true;
        }
        if (this.catalogPanel != null && this.catalogPanel.containsMouse(mouseX, mouseY)) {
            int clickedIndex = this.catalogPanel.handleClick(mouseX, mouseY);
            if (clickedIndex >= 0) {
                if (hasShiftDown()) {
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
        if (this.rightPage.mouseClicked(mouseX, mouseY, button)) {
            syncButtonState();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.catalogPanel != null && this.catalogPanel.containsMouse(mouseX, mouseY)) {
            this.catalogPanel.mouseScrolled(scrollY);
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
    protected void rebuildWidgets() {
        // 重建 widget 前，同步工具栏搜索/排序状态到 ViewModel，
        this.rebuildViewModels();
        this.clearWidgets();

        this.addRenderableWidget(this.rightPage.getIntroTabButton());
        this.addRenderableWidget(this.rightPage.getArchaeologyTabButton());
        this.addRenderableWidget(this.rightPage.getLogTabButton());

        // 目录工具栏
        this.catalogToolbar.createWidgets(this, this.bookLayout, this.font);

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
        this.catalogPanel.setEntries(this.viewModel.buildCatalogEntries());
        this.catalogPanel.ensureIndexVisible(this.viewModel.selectedIndex());

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
        int helpX = Math.min(this.width - JournalLayout.HELP_BUTTON_SIZE,
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
        boolean hasMultipleCatalogPages = this.catalogPanel != null && !this.viewModel.isEmpty()
                && this.catalogPanel.pageCount() > 1;
        applyButtonState(this.catalogPrevButton, hasMultipleCatalogPages,
                hasMultipleCatalogPages && this.catalogPanel != null && this.catalogPanel.getPage() > 0);
        applyButtonState(this.catalogNextButton, hasMultipleCatalogPages,
                hasMultipleCatalogPages && this.catalogPanel != null
                        && this.catalogPanel.getPage() < this.catalogPanel.pageCount() - 1);

        boolean hasMultipleItemPages = this.rightPage.pageCount() > 1;
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