package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.UiPanelRegistry;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import net.minecraft.nbt.CompoundTag;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryLogRef;
import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalControls;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 右侧页面容器 —— 通过四枚书签 Tab 切换介绍信息/考古信息/日志/场景 */
public final class RightPageContainer implements UiInputTarget {
    public void prepareUi() { activePanel().prepareUi(); }
    public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
        activePanel().renderControls(graphics, font, pointer);
    }
    @Override public void addFocusTargets(java.util.function.Consumer<UiFocusTarget> out) { activePanel().addFocusTargets(out); }
    @Override public long focusRevision() { return activePanel().focusRevision() * 31 + activeTab.ordinal() * 2L + logMode.ordinal(); }
    @Override public UiPointerHit hitTest(double x, double y) {
        UiPointerHit hit = activePanel().hitTest(x, y);
        return hit == null ? null : hit.within(this);
    }
    @Override public boolean mousePressed(UiInputContext context) { return activePanel().mousePressed(context); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) { return activePanel().keyPressed(key, scan, modifiers); }
    @Override public boolean keyReleased(int key, int scan, int modifiers) { return activePanel().keyReleased(key, scan, modifiers); }
    @Override public boolean charTyped(char value, int modifiers) { return activePanel().charTyped(value, modifiers); }
    @Override public boolean mouseScrolled(UiInputContext context, double x, double y) { return activePanel().mouseScrolled(context, x, y); }

    // 书签尺寸与间距
    private static final int BOOKMARK_HEIGHT = 22;
    private static final int BOOKMARK_GAP = 4;
    private static final int BOOKMARK_TOP_OFFSET = 20; // 距右页顶部偏移

    public enum Tab { INTRO, ARCHAEOLOGY, LOG, SCENARIO }

    private enum LogMode { LIST, DETAIL }

    private final ScenarioPanel scenarioPanel;
    private final JournalBookBackground.BookLayout layout;
    private final ScenarioDetailPanel scenarioDetailPanel;
    private final ItemGridPanel gridPanel;
    private final PageIndicator pageIndicator;
    private final DetailOverlayPanel detailPanel;
    private final LogPanel logPanel;
    private final LogDetailPanel logDetailPanel;
    private final List<PageDescriptor> pages;
    private final List<UiControl> bookmarks;
    private final List<SavedPage> savedPages;

    private record PageDescriptor(Tab tab, java.util.function.Supplier<PagePanel> content,
            int indicatorOffset, boolean alwaysIndicator, Runnable enter, Runnable leave, UiControl bookmark) {}
    private record SavedPage(String key, PagePanel panel) {}

    @Nullable
    private ResourceLocation currentTableId;
    private Tab activeTab = Tab.INTRO;
    private LogMode logMode = LogMode.LIST;
    @Nullable
    private UUID selectedLogEntryId;
    private final com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays overlays;

    public RightPageContainer(JournalBookBackground.BookLayout layout,
                              com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays overlays,
                              UiPanelRegistry panels) {
        this.layout = layout;
        this.overlays = overlays;
        this.scenarioPanel = new ScenarioPanel(layout);
        this.scenarioPanel.setOpenScenes(this::openSceneOverlay);
        this.scenarioDetailPanel = panels.register("scenario", new ScenarioDetailPanel(layout, overlays));
        this.gridPanel = new ItemGridPanel(layout);
        this.scenarioDetailPanel.setTargetNavigation(gridPanel::targetVisible, (kind, target) -> {
            if (gridPanel.locateTarget(kind, target)) setActiveTab(Tab.ARCHAEOLOGY);
        }, () -> setActiveTab(Tab.ARCHAEOLOGY));
        this.pageIndicator = new PageIndicator(layout);
        this.detailPanel = new DetailOverlayPanel(layout);
        this.logPanel = new LogPanel(layout);
        this.logDetailPanel = new LogDetailPanel(layout);

        PagePanel archaeology = new ArchaeologyPage(gridPanel, scenarioPanel);
        Runnable noAction = () -> {};
        Runnable leaveLog = () -> logPanel.setBatchSelectionMode(false);
        this.pages = List.of(
                page(Tab.INTRO, () -> detailPanel, JournalLayout.INTRO_PAGE_INDICATOR_Y, false,
                        tabLabel("intro"), leaveLog, noAction),
                page(Tab.ARCHAEOLOGY, () -> archaeology, JournalLayout.GRID_PAGE_INDICATOR_Y, true,
                        tabLabel("archaeology"), leaveLog, noAction),
                page(Tab.LOG, () -> logMode == LogMode.DETAIL ? logDetailPanel : logPanel,
                        JournalLayout.LOG_PAGE_INDICATOR_Y, false, tabLabel("log"),
                        this::captureReferenceGameTime, leaveLog),
                page(Tab.SCENARIO, () -> scenarioDetailPanel, JournalLayout.SCENARIO_PAGE_INDICATOR_Y, false,
                        ScenarioSimulationClientState.text("tab"), leaveLog, noAction));
        this.bookmarks = this.pages.stream().map(PageDescriptor::bookmark).toList();
        this.savedPages = List.of(new SavedPage("intro", detailPanel), new SavedPage("grid", gridPanel),
                new SavedPage("log", logPanel), new SavedPage("logDetail", logDetailPanel));
        syncBookmarks();
        gridPanel.setActions(this::syncPageIndicator, target -> {
            if (currentTableId != null) com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioRecommendationOverlay.open(
                    overlays, currentTableId, com.meteorite.unsuspiciousblock.loottable.simulation.SimulationAssistTarget.childTable(target));
        });
        logPanel.setOnOpenEntry(entry -> restoreLogSelection(entry.entryId(), true));
    }

    private static Component tabLabel(String name) {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_" + name);
    }

    private PageDescriptor page(Tab tab, java.util.function.Supplier<PagePanel> content, int indicatorOffset,
            boolean alwaysIndicator, Component label, Runnable enter, Runnable leave) {
        UiControl bookmark = JournalControls.bookmark(layout.rightPageX() + layout.rightPageWidth(),
                layout.rightPageY() + BOOKMARK_TOP_OFFSET + tab.ordinal() * (BOOKMARK_HEIGHT + BOOKMARK_GAP),
                label.copy().withStyle(ChatFormatting.YELLOW), () -> setActiveTab(tab));
        return new PageDescriptor(tab, content, indicatorOffset, alwaysIndicator, enter, leave, bookmark);
    }

    private PageDescriptor activePage() { return pages.get(activeTab.ordinal()); }

    private void syncBookmarks() {
        pages.forEach(page -> page.bookmark().setSelected(page.tab() == activeTab));
    }

    public void setTable(@Nullable ResourceLocation tableId, List<ItemGridPanel.GridItem> items,
                         List<ItemGridPanel.ChildTableEntry> childTables,
                         List<DetailOverlayPanel.IntroItem> introItems,
                         int parsedCount, int totalCount,
                         @Nullable ArchaeologyEntryLogRef logRef) {
        boolean sameTable = Objects.equals(this.currentTableId, tableId);
        int archaeologyPage = this.gridPanel.getPage();
        int introPage = this.detailPanel.getPage();
        int logListPage = this.logPanel.getPage();
        LogMode savedLogMode = this.logMode;
        UUID savedSelectedLogEntryId = this.selectedLogEntryId;

        this.gridPanel.setTable(items, childTables);
        this.detailPanel.setData(tableId, parsedCount, totalCount, introItems);
        this.logPanel.setData(logRef);

        if (sameTable) {
            this.gridPanel.setPage(archaeologyPage);
            this.detailPanel.setPage(introPage);
            this.logPanel.setPage(logListPage);
            restoreLogSelection(savedSelectedLogEntryId, savedLogMode == LogMode.DETAIL);
        } else {
            overlays.close();
            ScenarioSimulationClientState.cancelAssist();
            this.logPanel.setBatchSelectionMode(false);
            this.gridPanel.resetPage();
            restoreLogSelection(null, false);
        }

        this.currentTableId = tableId;
        this.scenarioPanel.setTable(tableId);
        this.scenarioDetailPanel.setTable(tableId);
        syncPageIndicator();
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
        syncPageIndicator();
    }

    public List<UiControl> bookmarkControls() { return bookmarks; }

    public void setBookmarksAvailable(boolean available) {
        bookmarks.forEach(bookmark -> {
            bookmark.setVisible(available);
            bookmark.setEnabled(available);
        });
        syncBookmarks();
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        syncPageIndicator();
        PagePanel panel = activePanel();
        panel.render(guiGraphics, font, mouseX, mouseY);
        // 指示器的可见性与位置由当前页描述决定。
        if (activePage().alwaysIndicator() || panel.pageCount() > 1) {
            this.pageIndicator.render(guiGraphics, font);
        }
    }

    /** 获取当前活跃的可分页面板 */
    private PagePanel activePanel() {
        return activePage().content().get();
    }

    @Nullable
    public ResourceLocation consumeNavigationTarget() {
        return this.gridPanel.consumeNavigationTarget();
    }

    public void handleScroll(double scrollY) {
        if (pageCount() > 1) {
            changePage(scrollY < 0.0 ? 1 : -1);
        }
    }

    public record ReadingState(Tab tab, CompoundTag pages, @Nullable ResourceLocation activeItemTag,
            boolean logDetail, @Nullable UUID logEntryId, boolean logBatchSelectionMode,
            List<UUID> selectedLogEntryIds) {
        public ReadingState {
            pages = pages.copy();
            selectedLogEntryIds = List.copyOf(selectedLogEntryIds);
        }
        public static ReadingState empty() {
            return new ReadingState(Tab.INTRO, new CompoundTag(), null, false, null, false, List.of());
        }
    }

    public ReadingState captureReadingState() {
        return new ReadingState(activeTab, savePages(), gridPanel.getActiveTag(), isShowingLogDetail(),
                selectedLogEntryId, logPanel.isBatchSelectionMode(), logPanel.getSelectedEntryIds());
    }

    public void restoreReadingState(ReadingState state) {
        setActiveItemTag(state.activeItemTag());
        restoreLogSelection(state.logEntryId(), state.logDetail());
        setActiveTab(state.tab());
        logPanel.restoreBatchSelection(state.logBatchSelectionMode(), state.selectedLogEntryIds());
        loadPages(state.pages());
    }

    private CompoundTag savePages() {
        CompoundTag tag = new CompoundTag();
        savedPages.forEach(saved -> tag.putInt(saved.key(), saved.panel().getPage()));
        return tag;
    }

    private void loadPages(CompoundTag tag) {
        savedPages.forEach(saved -> saved.panel().setPage(tag.getInt(saved.key())));
        syncPageIndicator();
    }

    public int pageIndicatorY() {
        return layout.rightPageY() + activePage().indicatorOffset();
    }

    public int pageCount() {
        return activePanel().pageCount();
    }

    public int getPage() {
        return activePanel().getPage();
    }

    public void changePage(int delta) {
        activePanel().changePage(delta);
        syncPageIndicator();
    }

    public void setActiveTab(Tab tab) {
        if (this.activeTab == tab) {
            return;
        }
        activePage().leave().run();
        this.activeTab = tab;
        activePage().enter().run();
        syncBookmarks();
        syncPageIndicator();
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
        syncPageIndicator();
    }



    public void setActiveItemTag(@Nullable ResourceLocation tagId) {
        this.gridPanel.setActiveTag(tagId);
        syncPageIndicator();
    }

    public boolean isShowingLogDetail() {
        return this.logMode == LogMode.DETAIL;
    }

    public LogPanel getLogPanel() {
        return this.logPanel;
    }

    public LogDetailPanel getLogDetailPanel() {
        return this.logDetailPanel;
    }



    // 网格页的「切换场景」与场景页的「场景」按钮打开同一套浮层；这里只切换选择（网格数字随之更新），
    // 不跳页也不改参数。缺数据时静默不开，避免空浮层。
    private void openSceneOverlay() {
        if (currentTableId == null) return;
        CatalogTableDto dto = ScenarioSimulationClientState.table(currentTableId);
        var selection = ScenarioSimulationClientState.selection(currentTableId);
        if (dto == null || dto.options() == null || selection == null) return;
        SimulationOptions options = dto.options();
        List<CatalogTableDto.ScenarioAssumptions> scenes = options.scenes();
        if (scenes.isEmpty()) return;
        int current = 0;
        for (int i = 0; i < scenes.size(); i++) {
            if (scenes.get(i).scenarioKey().equals(selection.scene())) current = i;
        }
        ResourceLocation table = currentTableId;
        overlays.open(new ScenarioSelectionOverlay(overlays,
                table, options, selection.params(), current,
                index -> ScenarioSimulationClientState.select(table, scenes.get(index).scenarioKey(), selection.params())));
    }

    private void syncPageIndicator() {
        this.pageIndicator.setTextY(pageIndicatorY());
        PagePanel panel = activePanel();
        this.pageIndicator.setPage(panel.getPage(), panel.pageCount());
    }

    public ItemGridPanel.TooltipData getTooltipData(UiPointerHit hit) {
        return activePanel().tooltip(hit);
    }

    /** 网格与场景栏共享一个 PagePanel；命中优先级与持续输入仍交现有 kit。 */
    private record ArchaeologyPage(ItemGridPanel grid, ScenarioPanel scenario) implements PagePanel {
        @Override public void prepareUi() { grid.prepareUi(); scenario.prepareUi(); }
        @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            grid.render(graphics, font, mouseX, mouseY);
        }
        @Override public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
            grid.renderControls(graphics, font, pointer);
            scenario.renderControls(graphics, font, pointer);
        }
        @Override public void addFocusTargets(java.util.function.Consumer<UiFocusTarget> out) {
            grid.addFocusTargets(out); scenario.addFocusTargets(out);
        }
        @Override public long focusRevision() { return grid.focusRevision() * 31 + 17 * scenario.focusRevision(); }
        @Override public UiPointerHit hitTest(double x, double y) {
            UiPointerHit hit = scenario.hitTest(x, y);
            if (hit == null) hit = grid.hitTest(x, y);
            if (hit == null && grid.containsMouse(x, y)) return UiPointerHit.leaf(this, x, y);
            return hit == null ? null : hit.within(this);
        }
        @Override public boolean containsMouse(double x, double y) { return grid.containsMouse(x, y); }
        @Override public boolean mousePressed(UiInputContext context) {
            return context.hits(scenario) ? scenario.mousePressed(context) : grid.mousePressed(context);
        }
        @Override public boolean keyPressed(int key, int scan, int modifiers) {
            return grid.keyPressed(key, scan, modifiers) || scenario.keyPressed(key, scan, modifiers);
        }
        @Override public boolean keyReleased(int key, int scan, int modifiers) { return grid.keyReleased(key, scan, modifiers); }
        @Override public boolean charTyped(char value, int modifiers) { return grid.charTyped(value, modifiers); }
        @Override public boolean mouseScrolled(UiInputContext context, double x, double y) { return grid.mouseScrolled(context, x, y); }
        @Override public ItemGridPanel.TooltipData tooltip(UiPointerHit hit) { return grid.tooltip(hit); }
        @Override public int pageCount() { return grid.pageCount(); }
        @Override public int getPage() { return grid.getPage(); }
        @Override public void setPage(int page) { grid.setPage(page); }
        @Override public void changePage(int delta) { grid.changePage(delta); }
    }
}
