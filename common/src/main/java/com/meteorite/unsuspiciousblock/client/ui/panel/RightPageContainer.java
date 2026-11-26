package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.state.ScenarioTableSnapshot;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.journal.JournalReadingState.PagePositions;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioRecommendationOverlay;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalMirror;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalControls;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 右侧页面容器 —— 通过四枚书签 Tab 切换介绍信息/考古信息/日志/场景
 */
public final class RightPageContainer implements UiInputContainer {
    public void prepareUi() {
        activePanel().prepareUi();
        syncPageChrome();
    }

    public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
        activePanel().renderControls(graphics, font, pointer);
    }

    private final UiChildren<UiInputTarget> members = new UiChildren<>();
    private final UiInputScope scope;
    private final UiInputRouter router;

    /**
     * 活动页是实际输入成员，chrome 继续由根登记。
     */
    @Override
    public UiChildren<UiInputTarget> inputChildren() {
        return members;
    }

    private void syncActiveMember() {
        PagePanel next = activePanel();
        router.replaceChildren(scope, members, List.of(next));
    }

    // 书签尺寸与间距
    private static final int BOOKMARK_HEIGHT = 22;
    private static final int BOOKMARK_GAP = 4;
    private static final int BOOKMARK_TOP_OFFSET = 20; // 距右页顶部偏移

    public enum Tab {INTRO, ARCHAEOLOGY, LOG, SCENARIO}

    private enum LogMode {LIST, DETAIL}

    private final ScenarioPanel scenarioPanel;
    private JournalBookBackground.BookLayout layout;
    private final ScenarioDetailPanel scenarioDetailPanel;
    private final ItemGridPanel gridPanel;
    private final PageIndicator pageIndicator;
    private final List<UiControl> pageControls;
    private final DetailOverlayPanel detailPanel;
    private final LogPanel logPanel;
    private final LogDetailPanel logDetailPanel;
    private final List<PageDescriptor> pages;
    private final List<UiControl> bookmarks;
    private boolean chromeAvailable;

    private record PageDescriptor(Tab tab, Supplier<PagePanel> content,
                                  int indicatorOffset, boolean alwaysIndicator, Runnable enter, Runnable leave,
                                  UiControl bookmark) {
    }

    @Nullable
    private ResourceLocation currentTableId;
    private Tab activeTab = Tab.INTRO;
    private LogMode logMode = LogMode.LIST;
    @Nullable
    private UUID selectedLogEntryId;
    private final JournalOverlays overlays;

    public RightPageContainer(JournalBookBackground.BookLayout layout,
                              JournalOverlays overlays, UiInputScope scope, UiInputRouter router) {
        this.scope = scope;
        this.router = router;
        this.layout = layout;
        this.overlays = overlays;
        this.scenarioPanel = new ScenarioPanel(layout);
        this.scenarioPanel.setOpenScenes(this::openSceneOverlay);
        this.scenarioDetailPanel = new ScenarioDetailPanel(layout, overlays);
        this.gridPanel = new ItemGridPanel(layout);
        this.scenarioDetailPanel.setTargetNavigation((kind, target) -> {
            if (gridPanel.locateTarget(kind, target)) setActiveTab(Tab.ARCHAEOLOGY);
        }, () -> setActiveTab(Tab.ARCHAEOLOGY));
        this.pageIndicator = new PageIndicator(layout);
        int pageCenterX = layout.rightPageX() + layout.rightPageWidth() / 2;
        int pageButtonY = layout.rightPageY() + JournalLayout.GRID_PAGE_INDICATOR_Y;
        this.pageControls = List.of(
                JournalControls.page(pageCenterX - JournalLayout.PAGE_BUTTON_CENTER_GAP
                        - JournalLayout.PAGE_BUTTON_WIDTH, pageButtonY, false, () -> changePage(-1)),
                JournalControls.page(pageCenterX + JournalLayout.PAGE_BUTTON_CENTER_GAP,
                        pageButtonY, true, () -> changePage(1)));
        this.detailPanel = new DetailOverlayPanel(layout);
        this.logPanel = new LogPanel(layout);
        this.logDetailPanel = new LogDetailPanel(layout);

        PagePanel archaeology = new ArchaeologyPage(gridPanel, scenarioPanel);
        Runnable noAction = () -> {
        };
        Runnable leaveLog = () -> logPanel.setBatchSelectionMode(false);
        this.pages = List.of(
                page(Tab.INTRO, () -> detailPanel, JournalLayout.INTRO_PAGE_INDICATOR_Y, false,
                        tabLabel("intro"), leaveLog, noAction),
                page(Tab.ARCHAEOLOGY, () -> archaeology, JournalLayout.GRID_PAGE_INDICATOR_Y, true,
                        tabLabel("archaeology"), leaveLog, noAction),
                page(Tab.LOG, () -> isShowingLogDetail() ? logDetailPanel : logPanel,
                        JournalLayout.LOG_PAGE_INDICATOR_Y, false, tabLabel("log"),
                        this::captureReferenceGameTime, leaveLog),
                page(Tab.SCENARIO, () -> scenarioDetailPanel, JournalLayout.SCENARIO_PAGE_INDICATOR_Y, false,
                        ScenarioSession.text("tab"), leaveLog, noAction));
        this.bookmarks = this.pages.stream().map(PageDescriptor::bookmark).toList();
        syncBookmarks();
        gridPanel.setActions(this::syncPageChrome, target -> {
            if (currentTableId != null) ScenarioRecommendationOverlay.open(
                    overlays, currentTableId, target);
        });
        logPanel.setOnOpenEntry(entry -> restoreLogSelection(entry.entryId(), true));
        syncActiveMember();
    }

    private static Component tabLabel(String name) {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_" + name);
    }

    public void applyLayout(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        gridPanel.applyLayout(layout);
        detailPanel.applyLayout(layout);
        logPanel.applyLayout(layout);
        logDetailPanel.applyLayout(layout);
        scenarioPanel.applyLayout(layout);
        scenarioDetailPanel.applyLayout(layout);
        pageIndicator.setCenterX(layout.rightPageX() + layout.rightPageWidth() / 2);
        for (PageDescriptor page : pages) {
            UiControl bookmark = page.bookmark();
            bookmark.setBounds(layout.rightPageRight(),
                    layout.rightPageY() + BOOKMARK_TOP_OFFSET + page.tab().ordinal() * (BOOKMARK_HEIGHT + BOOKMARK_GAP),
                    bookmark.bounds().width(), BOOKMARK_HEIGHT);
        }
        syncPageChrome();
    }

    private PageDescriptor page(Tab tab, Supplier<PagePanel> content, int indicatorOffset,
                                boolean alwaysIndicator, Component label, Runnable enter, Runnable leave) {
        UiControl bookmark = JournalControls.bookmark(layout.rightPageX() + layout.rightPageWidth(),
                layout.rightPageY() + BOOKMARK_TOP_OFFSET + tab.ordinal() * (BOOKMARK_HEIGHT + BOOKMARK_GAP),
                label, () -> setActiveTab(tab));
        bookmark.setLayoutKey("journal/tab/" + tab.name().toLowerCase(Locale.ROOT));
        return new PageDescriptor(tab, content, indicatorOffset, alwaysIndicator, enter, leave, bookmark);
    }

    private PageDescriptor activePage() {
        return pages.get(activeTab.ordinal());
    }

    private void syncBookmarks() {
        pages.forEach(page -> page.bookmark().setSelected(page.tab() == activeTab));
    }

    public void setTable(@Nullable ResourceLocation tableId, List<ItemGridPanel.GridItem> items,
                         List<ItemGridPanel.ChildTableEntry> childTables,
                         List<DetailOverlayPanel.IntroItem> introItems,
                         int parsedCount, int totalCount) {
        boolean sameTable = Objects.equals(this.currentTableId, tableId);
        int introPage = detailPanel.getPage();
        int gridPage = gridPanel.getPage();

        Map<String, ScenarioTableSnapshot.Target> targets = new LinkedHashMap<>();
        for (var item : items) {
            targets.put("item#" + item.signature().toStoredKey(), new ScenarioTableSnapshot.Target(item.unlocked(),
                    item.unlocked() ? item.displayName() : Component.empty()));
            for (var path : item.acquisitionPaths()) {
                if (path.sourceItemTag() == null) continue;
                String key = "tag#" + path.sourceItemTag();
                boolean visible = item.unlocked() || targets.containsKey(key) && targets.get(key).visible();
                targets.put(key, new ScenarioTableSnapshot.Target(visible,
                        visible ? Component.literal(path.sourceItemTag().toString()) : Component.empty()));
            }
        }
        for (var child : childTables)
            targets.put("table#" + child.tableId(),
                    new ScenarioTableSnapshot.Target(child.unlocked(), child.unlocked() ? child.displayName() : Component.empty()));
        if (tableId != null) JournalClientConnection.scenario().setTargets(tableId, targets);
        this.gridPanel.setTable(items, childTables, targets);
        this.detailPanel.setData(tableId, parsedCount, totalCount, introItems);

        if (sameTable) {
            detailPanel.setPage(introPage);
            gridPanel.setPage(gridPage);
        } else {
            overlays.close();
            JournalClientConnection.scenario().cancelAssist();
            gridPanel.setActiveTag(null);
            gridPanel.resetPage();
            detailPanel.setPage(0);
            logPanel.setLoadStatus(JournalMirror.Status.IDLE);
            logPanel.setData(List.of());
            logPanel.setPage(0);
            logDetailPanel.setPage(0);
            logPanel.setBatchSelectionMode(false);
            restoreLogSelection(null, false);
        }

        this.currentTableId = tableId;
        this.scenarioPanel.setTable(tableId);
        this.scenarioDetailPanel.setTable(tableId);
        syncPageChrome();
    }

    /** 历史重取期间保留阅读身份，完整结果到达后按 UUID 恢复详情。 */
    public void setLogHistory(JournalMirror.Status status, List<ExcavationLogEntry> history) {
        logPanel.setLoadStatus(status);
        if (status == JournalMirror.Status.READY) {
            logPanel.setData(history);
            restoreLogSelection(selectedLogEntryId, logMode == LogMode.DETAIL);
        } else {
            syncPageChrome();
        }
    }

    public void restoreLogSelection(@Nullable UUID entryId, boolean detailMode) {
        this.selectedLogEntryId = entryId;
        this.logPanel.setSelectedEntryId(entryId);
        ExcavationLogEntry selectedEntry = entryId != null ? this.logPanel.findEntry(entryId) : null;
        if (detailMode && selectedEntry != null) {
            this.logMode = LogMode.DETAIL;
            this.logDetailPanel.setEntry(selectedEntry, this.currentTableId);
        } else {
            this.logMode = LogMode.LIST;
            this.selectedLogEntryId = null;
            this.logDetailPanel.setEntry(null, null);
        }
        syncPageChrome();
    }

    public List<UiControl> chromeControls() {
        return Stream.concat(bookmarks.stream(), pageControls.stream()).toList();
    }

    public void setChromeAvailable(boolean available) {
        this.chromeAvailable = available;
        bookmarks.forEach(bookmark -> {
            bookmark.setVisible(available);
            bookmark.setEnabled(available);
        });
        pageControls.forEach(control -> control.setVisible(available));
        syncBookmarks();
        syncPageChrome();
    }

    public void render(GuiGraphics guiGraphics, Font font, UiPointer pointer) {
        syncPageChrome();
        PagePanel panel = activePanel();
        panel.render(guiGraphics, font, pointer);
        // 指示器的可见性与位置由当前页描述决定。
        if (isActivePageReady() && (activePage().alwaysIndicator() || panel.pageCount() > 1)) {
            this.pageIndicator.render(guiGraphics, font);
        }
    }

    /**
     * 获取当前活跃的可分页面板
     */
    private PagePanel activePanel() {
        return activePage().content().get();
    }

    @Nullable
    public ResourceLocation consumeNavigationTarget() {
        return this.gridPanel.consumeNavigationTarget();
    }

    public void handleScroll(double scrollY) {
        if (isActivePageReady() && pageCount() > 1) {
            changePage(scrollY < 0.0 ? 1 : -1);
        }
    }

    /**
     * 右页完整阅读快照，持久投影由 JournalReadingState 明确裁剪。
     */
    public record ReadingState(Tab tab, PagePositions pages, @Nullable ResourceLocation activeItemTag,
                               boolean logDetail, @Nullable UUID logEntryId, boolean logBatchSelectionMode,
                               List<UUID> selectedLogEntryIds, Map<String, Integer> conditionOffsets) {
        /**
         * 集合冻结后可跨布局重建复用。
         */
        public ReadingState {
            selectedLogEntryIds = List.copyOf(selectedLogEntryIds);
            conditionOffsets = Map.copyOf(conditionOffsets);
        }

        /**
         * 默认阅读起点。
         */
        public static ReadingState empty() {
            return new ReadingState(Tab.INTRO, PagePositions.empty(), null, false, null, false, List.of(), Map.of());
        }
    }

    /**
     * 从实际内容捕获所有右页阅读位置。
     */
    public ReadingState captureReadingState() {
        return new ReadingState(activeTab, new PagePositions(detailPanel.getPage(), gridPanel.getPage(), logPanel.getPage(), logDetailPanel.getPage()),
                gridPanel.getActiveTag(), logMode == LogMode.DETAIL, selectedLogEntryId, logPanel.isBatchSelectionMode(),
                logPanel.getSelectedEntryIds(), scenarioDetailPanel.captureConditionOffsets());
    }

    /**
     * 内容准备后先恢复身份，再恢复页号与偏移，最后更新 chrome。
     */
    public void restoreReadingState(ReadingState state) {
        setActiveItemTag(state.activeItemTag());
        restoreLogSelection(state.logEntryId(), state.logDetail());
        detailPanel.setPage(state.pages().intro());
        gridPanel.setPage(state.pages().grid());
        logPanel.setPage(state.pages().log());
        logDetailPanel.setPage(logMode == LogMode.DETAIL ? state.pages().logDetail() : 0);
        scenarioDetailPanel.restoreConditionOffsets(state.conditionOffsets());
        setActiveTab(state.tab());
        logPanel.restoreBatchSelection(state.logBatchSelectionMode(), state.selectedLogEntryIds());
        syncPageChrome();
    }

    public int pageCount() {
        return activePanel().pageCount();
    }

    public int getPage() {
        return activePanel().getPage();
    }

    public void changePage(int delta) {
        activePanel().changePage(delta);
        syncPageChrome();
    }

    /**
     * 切换内容与书签选中态；书签持焦时让键盘后续激活跟随新页。
     */
    public void setActiveTab(Tab tab) {
        if (this.activeTab == tab) {
            return;
        }
        UiFocusTarget focused = scope.focus().focused();
        boolean hasBookmarkFocus = focused instanceof UiControl bookmark && bookmarks.contains(bookmark);
        activePage().leave().run();
        this.activeTab = tab;
        activePage().enter().run();
        syncBookmarks();
        syncPageChrome();
        if (hasBookmarkFocus) {
            scope.refreshFocus();
            scope.focus().focusOn(activePage().bookmark());
        }
    }

    // 捕获当前游戏刻作为时间分组参考，仅在切换到日志页或屏幕初始化时调用
    public void captureReferenceGameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        long gameTime = minecraft.level != null ? minecraft.level.getGameTime() : 0L;
        this.logPanel.setReferenceGameTime(gameTime);
    }

    public Tab getActiveTab() {
        return this.activeTab;
    }

    public void setPage(int page) {
        activePanel().setPage(page);
        syncPageChrome();
    }


    public void setActiveItemTag(@Nullable ResourceLocation tagId) {
        this.gridPanel.setActiveTag(tagId);
        syncPageChrome();
    }

    public boolean isShowingLogDetail() {
        return logMode == LogMode.DETAIL && logPanel.hasLoadedHistory();
    }

    private boolean isActivePageReady() {
        return activeTab != Tab.LOG || logPanel.hasLoadedHistory();
    }

    public LogPanel getLogPanel() {
        return this.logPanel;
    }

    public LogDetailPanel getLogDetailPanel() {
        return this.logDetailPanel;
    }


    // 网格页的「条件栏」打开条件勾选浮层，也可在浮层内一键同步状态或切换参数。
    private void openSceneOverlay() {
        if (currentTableId == null) return;
        CatalogTableDto dto = JournalClientConnection.scenario().table(currentTableId);
        var selection = JournalClientConnection.scenario().selection(currentTableId);
        if (dto == null || dto.options() == null || selection == null) return;
        overlays.open(new ScenarioConditionOverlay(overlays,
                currentTableId, dto.options(), selection.conditions(), selection.params()));
    }

    private void syncPageChrome() {
        syncActiveMember();
        int indicatorY = layout.rightPageY() + activePage().indicatorOffset();
        this.pageIndicator.setTextY(indicatorY);
        PagePanel panel = activePanel();
        this.pageIndicator.setPage(panel.getPage(), panel.pageCount());
        int buttonY = indicatorY + (Minecraft.getInstance().font.lineHeight - JournalLayout.PAGE_BUTTON_HEIGHT) / 2;
        int centerX = layout.rightPageX() + layout.rightPageWidth() / 2;
        pageControls.get(0).setBounds(centerX - JournalLayout.PAGE_BUTTON_CENTER_GAP
                        - JournalLayout.PAGE_BUTTON_WIDTH, buttonY,
                JournalLayout.PAGE_BUTTON_WIDTH, JournalLayout.PAGE_BUTTON_HEIGHT);
        pageControls.get(1).setBounds(centerX + JournalLayout.PAGE_BUTTON_CENTER_GAP, buttonY,
                JournalLayout.PAGE_BUTTON_WIDTH, JournalLayout.PAGE_BUTTON_HEIGHT);
        boolean hasPages = chromeAvailable && isActivePageReady() && panel.pageCount() > 1;
        pageControls.get(0).setVisible(hasPages);
        pageControls.get(1).setVisible(hasPages);
        pageControls.get(0).setEnabled(hasPages && panel.getPage() > 0);
        pageControls.get(1).setEnabled(hasPages && panel.getPage() < panel.pageCount() - 1);
    }

    /**
     * 网格与场景栏共享一个 PagePanel；命中优先级与持续输入仍交现有 kit。
     */
    private static final class ArchaeologyPage implements PagePanel, UiInputContainer {
        private final ItemGridPanel grid;
        private final ScenarioPanel scenario;
        private final UiChildren<UiInputTarget> members = new UiChildren<>();

        private ArchaeologyPage(ItemGridPanel grid, ScenarioPanel scenario) {
            this.grid = grid;
            this.scenario = scenario;
            members.addAll(List.of(grid, scenario));
        }

        @Override
        public UiChildren<UiInputTarget> inputChildren() {
            return members;
        }

        @Override
        public void prepareUi() {
            grid.prepareUi();
            scenario.prepareUi();
        }

        @Override
        public void render(GuiGraphics graphics, Font font, UiPointer pointer) {
            grid.render(graphics, font, pointer);
        }

        @Override
        public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
            grid.renderControls(graphics, font, pointer);
            scenario.renderControls(graphics, font, pointer);
        }

        @Override
        public UiPointerHit hitTest(double x, double y) {
            UiPointerHit hit = UiInputContainer.super.hitTest(x, y);
            return hit == null && grid.containsMouse(x, y) ? UiPointerHit.leaf(this, x, y) : hit;
        }

        @Override
        public boolean containsMouse(double x, double y) {
            return grid.containsMouse(x, y);
        }

        @Override
        public int pageCount() {
            return grid.pageCount();
        }

        @Override
        public int getPage() {
            return grid.getPage();
        }

        @Override
        public void setPage(int page) {
            grid.setPage(page);
        }

        @Override
        public void changePage(int delta) {
            grid.changePage(delta);
        }
    }
}
