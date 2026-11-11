package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioRecommendationOverlay;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationAssistTarget;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.UiPanelRegistry;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions;
import net.minecraft.nbt.CompoundTag;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryLogRef;
import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalControls;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import com.meteorite.meteosuikit.api.layout.UiLayoutSource;
import com.meteorite.meteosuikit.api.layout.UiLayoutNode;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/** 右侧页面容器 —— 通过四枚书签 Tab 切换介绍信息/考古信息/日志/场景 */
public final class RightPageContainer implements UiInputTarget {
    public void prepareUi() {
        activePanel().prepareUi();
        syncPageChrome();
    }
    public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
        activePanel().renderControls(graphics, font, pointer);
    }
    @Override public void addFocusTargets(Consumer<UiFocusTarget> out) { activePanel().addFocusTargets(out); }
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
    private final List<UiControl> pageControls;
    private final DetailOverlayPanel detailPanel;
    private final LogPanel logPanel;
    private final LogDetailPanel logDetailPanel;
    private final List<PageDescriptor> pages;
    private final List<UiControl> bookmarks;
    private final List<SavedPage> savedPages;
    private boolean chromeAvailable;

    private record PageDescriptor(Tab tab, Supplier<PagePanel> content,
            int indicatorOffset, boolean alwaysIndicator, Runnable enter, Runnable leave, UiControl bookmark) {}
    private record SavedPage(String key, PagePanel panel) {}

    @Nullable
    private ResourceLocation currentTableId;
    private Tab activeTab = Tab.INTRO;
    private LogMode logMode = LogMode.LIST;
    @Nullable
    private UUID selectedLogEntryId;
    private final JournalOverlays overlays;

    public RightPageContainer(JournalBookBackground.BookLayout layout,
                              JournalOverlays overlays,
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
        gridPanel.setActions(this::syncPageChrome, target -> {
            if (currentTableId != null) ScenarioRecommendationOverlay.open(
                    overlays, currentTableId, SimulationAssistTarget.childTable(target));
        });
        logPanel.setOnOpenEntry(entry -> restoreLogSelection(entry.entryId(), true));
    }

    private static Component tabLabel(String name) {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_" + name);
    }

    private PageDescriptor page(Tab tab, Supplier<PagePanel> content, int indicatorOffset,
            boolean alwaysIndicator, Component label, Runnable enter, Runnable leave) {
        UiControl bookmark = JournalControls.bookmark(layout.rightPageX() + layout.rightPageWidth(),
                layout.rightPageY() + BOOKMARK_TOP_OFFSET + tab.ordinal() * (BOOKMARK_HEIGHT + BOOKMARK_GAP),
                label.copy().withStyle(ChatFormatting.YELLOW), () -> setActiveTab(tab));
        bookmark.setLayoutKey("journal/tab/" + tab.name().toLowerCase(Locale.ROOT));
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
        syncPageChrome();
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

    /** 当前右页贡献实际输入内容；chrome 由根已有成员树导出。 */
    public List<UiLayoutNode> layoutNodes() {
        return activePage().content().get() instanceof UiLayoutSource source ? source.layoutNodes() : List.of();
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

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        syncPageChrome();
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

    public void setActiveTab(Tab tab) {
        if (this.activeTab == tab) {
            return;
        }
        activePage().leave().run();
        this.activeTab = tab;
        activePage().enter().run();
        syncBookmarks();
        syncPageChrome();
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

    private void syncPageChrome() {
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
        boolean hasPages = chromeAvailable && panel.pageCount() > 1;
        pageControls.get(0).setVisible(hasPages);
        pageControls.get(1).setVisible(hasPages);
        pageControls.get(0).setEnabled(hasPages && panel.getPage() > 0);
        pageControls.get(1).setEnabled(hasPages && panel.getPage() < panel.pageCount() - 1);
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
        @Override public void addFocusTargets(Consumer<UiFocusTarget> out) {
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
        @Override public int pageCount() { return grid.pageCount(); }
        @Override public int getPage() { return grid.getPage(); }
        @Override public void setPage(int page) { grid.setPage(page); }
        @Override public void changePage(int delta) { grid.changePage(delta); }
    }
}
