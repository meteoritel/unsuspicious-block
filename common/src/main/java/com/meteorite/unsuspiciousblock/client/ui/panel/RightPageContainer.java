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
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 右侧页面容器 —— 通过四枚书签 Tab 切换介绍信息/考古信息/日志/场景 */
public final class RightPageContainer implements UiInputTarget {
    public void prepareUi() { activePanel().prepareUi(); if (activeTab == Tab.ARCHAEOLOGY) scenarioPanel.prepareUi(); }
    public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
        if (activeTab == Tab.ARCHAEOLOGY) { gridPanel.renderControls(graphics, font, pointer); scenarioPanel.renderControls(graphics, font, pointer); }
        else if (activeTab == Tab.SCENARIO) scenarioDetailPanel.renderControls(graphics, font, pointer);
        else if (activeTab == Tab.LOG && logMode == LogMode.LIST) logPanel.renderControls(graphics, font, pointer);
        else if (activeTab == Tab.LOG) logDetailPanel.renderControls(graphics, font, pointer);
    }
    @Override public void addFocusTargets(java.util.function.Consumer<UiFocusTarget> out) { activePanel().addFocusTargets(out); if (activeTab == Tab.ARCHAEOLOGY) scenarioPanel.addFocusTargets(out); }
    @Override public long focusRevision() { return activePanel().focusRevision() * 31 + activeTab.ordinal() * 2L + logMode.ordinal() + (activeTab == Tab.ARCHAEOLOGY ? 17 * scenarioPanel.focusRevision() : 0); }
    @Override public UiPointerHit hitTest(double x, double y) {
        UiPointerHit hit = activeTab == Tab.ARCHAEOLOGY ? scenarioPanel.hitTest(x, y) : null;
        if (hit == null) hit = activePanel().hitTest(x, y);
        if (activeTab == Tab.ARCHAEOLOGY && hit == null && containsMouse(x, y))
            return UiPointerHit.leaf(this, x, y);
        return hit == null ? null : hit.within(this);
    }
    @Override public boolean mousePressed(UiInputContext context) {
        if (activeTab == Tab.ARCHAEOLOGY && context.hits(scenarioPanel)) return scenarioPanel.mousePressed(context);
        return activePanel().mousePressed(context);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        return activePanel().keyPressed(key, scan, modifiers)
                || activeTab == Tab.ARCHAEOLOGY && scenarioPanel.keyPressed(key, scan, modifiers);
    }
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
    private final UiControl scenarioTabBtn;
    private final ItemGridPanel gridPanel;
    private final PageIndicator pageIndicator;
    private final DetailOverlayPanel detailPanel;
    private final LogPanel logPanel;
    private final LogDetailPanel logDetailPanel;
    private final UiControl introTabBtn;
    private final UiControl archaeologyTabBtn;
    private final UiControl logTabBtn;

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

        // 书签附着在书本右边缘，垂直排列
        int bookRightEdge = layout.rightPageX() + layout.rightPageWidth();
        int startY = layout.rightPageY() + BOOKMARK_TOP_OFFSET;

        this.introTabBtn = JournalControls.bookmark(
                bookRightEdge, startY,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_intro")
                        .withStyle(ChatFormatting.YELLOW),
                () -> switchTab(Tab.INTRO));
        this.introTabBtn.setSelected(true);

        this.archaeologyTabBtn = JournalControls.bookmark(
                bookRightEdge, startY + BOOKMARK_HEIGHT + BOOKMARK_GAP,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_archaeology")
                        .withStyle(ChatFormatting.YELLOW),
                () -> switchTab(Tab.ARCHAEOLOGY));

        this.scenarioTabBtn = JournalControls.bookmark(bookRightEdge,
                startY + (BOOKMARK_HEIGHT + BOOKMARK_GAP) * 3,
                com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState.text("tab").copy()
                        .withStyle(ChatFormatting.YELLOW), () -> switchTab(Tab.SCENARIO));
        this.logTabBtn = JournalControls.bookmark(
                bookRightEdge, startY + (BOOKMARK_HEIGHT + BOOKMARK_GAP) * 2,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.tab_log")
                        .withStyle(ChatFormatting.YELLOW),
                () -> switchTab(Tab.LOG));
        gridPanel.setActions(this::syncPageIndicator, target -> {
            if (currentTableId != null) com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioRecommendationOverlay.open(
                    overlays, currentTableId, com.meteorite.unsuspiciousblock.loottable.simulation.SimulationAssistTarget.childTable(target));
        });
        logPanel.setOnOpenEntry(entry -> restoreLogSelection(entry.entryId(), true));
    }

    private void switchTab(Tab tab) {
        setActiveTab(tab);
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

    public UiControl getScenarioTabButton() { return scenarioTabBtn; }

    public UiControl getIntroTabButton() {
        return this.introTabBtn;
    }

    public UiControl getArchaeologyTabButton() {
        return this.archaeologyTabBtn;
    }

    public UiControl getLogTabButton() {
        return this.logTabBtn;
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        syncPageIndicator();
        PagePanel panel = activePanel();
        panel.render(guiGraphics, font, mouseX, mouseY);
        // ARCHAEOLOGY tab always shows page indicator; others only when multi-page
        if (this.activeTab == Tab.ARCHAEOLOGY || panel.pageCount() > 1) {
            this.pageIndicator.render(guiGraphics, font);
        }
    }

    /** 获取当前活跃的可分页面板 */
    private PagePanel activePanel() {
        return switch (this.activeTab) {
            case SCENARIO -> this.scenarioDetailPanel;
            case INTRO -> this.detailPanel;
            case ARCHAEOLOGY -> this.gridPanel;
            case LOG -> this.logMode == LogMode.DETAIL ? this.logDetailPanel : this.logPanel;
        };
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        if (this.introTabBtn.bounds().contains(mouseX, mouseY)) {
            return true;
        }
        if (this.archaeologyTabBtn.bounds().contains(mouseX, mouseY)) {
            return true;
        }
        if (this.logTabBtn.bounds().contains(mouseX, mouseY)) {
            return true;
        }
        return activePanel().containsMouse(mouseX, mouseY);
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

    // 旧面板不迁移接口，仅由现有容器保存全部页号，避免只保存当前 tab。
    public CompoundTag savePages() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("intro", detailPanel.getPage());
        tag.putInt("grid", gridPanel.getPage());
        tag.putInt("log", logPanel.getPage());
        tag.putInt("logDetail", logDetailPanel.getPage());
        return tag;
    }

    public void loadPages(CompoundTag tag) {
        detailPanel.setPage(tag.getInt("intro"));
        gridPanel.setPage(tag.getInt("grid"));
        logPanel.setPage(tag.getInt("log"));
        logDetailPanel.setPage(tag.getInt("logDetail"));
        syncPageIndicator();
    }

    public int pageIndicatorY() {
        return layout.rightPageY() + switch (activeTab) {
            case INTRO -> JournalLayout.INTRO_PAGE_INDICATOR_Y;
            case ARCHAEOLOGY -> JournalLayout.GRID_PAGE_INDICATOR_Y;
            case LOG -> JournalLayout.LOG_PAGE_INDICATOR_Y;
            case SCENARIO -> JournalLayout.SCENARIO_PAGE_INDICATOR_Y;
        };
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
        this.activeTab = tab;
        if (tab != Tab.LOG) {
            this.logPanel.setBatchSelectionMode(false);
        }
        // 切换到日志页时刷新时间分组参考刻，避免每帧重算
        if (tab == Tab.LOG) {
            captureReferenceGameTime();
        }
        this.introTabBtn.setSelected(tab == Tab.INTRO);
        this.archaeologyTabBtn.setSelected(tab == Tab.ARCHAEOLOGY);
        this.logTabBtn.setSelected(tab == Tab.LOG);
        this.scenarioTabBtn.setSelected(tab == Tab.SCENARIO);
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

    @Nullable
    public ResourceLocation getActiveItemTag() {
        return this.gridPanel.getActiveTag();
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

    @Nullable
    public UUID getSelectedLogEntryId() {
        return this.selectedLogEntryId;
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

    @Nullable
    public ItemGridPanel.TooltipData getTooltipData(double mouseX, double mouseY) {
        if (this.activeTab == Tab.INTRO) {
            ItemStack stack = this.detailPanel.getHoveredItemStack(mouseX, mouseY).orElse(ItemStack.EMPTY);
            return stack.isEmpty() ? null : new ItemGridPanel.TooltipData(stack, null);
        }
        if (this.activeTab == Tab.ARCHAEOLOGY) {
            return this.gridPanel.getTooltipData(mouseX, mouseY);
        }
        if (this.activeTab == Tab.LOG && this.logMode == LogMode.DETAIL) {
            ItemStack stack = this.logDetailPanel.getTooltipStack(mouseX, mouseY);
            return stack != null ? new ItemGridPanel.TooltipData(stack, null) : null;
        }
        return null;
    }

    public ItemGridPanel.TooltipData getTooltipData(UiPointerHit hit) {
        if (hit == null) return null;
        if (activeTab == Tab.ARCHAEOLOGY && hit.owns(gridPanel)) return gridPanel.getTooltipData(hit);
        if (activeTab == Tab.INTRO && hit.owns(detailPanel) || activeTab == Tab.LOG && hit.owns(logDetailPanel))
            return getTooltipData(hit.x(), hit.y());
        return null;
    }


}
