package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryItem;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyJournalEntry;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.panel.CatalogPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.PageIndicator;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.support.CatalogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.client.ui.widget.IconButton;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ArchaeologyJournalScreen extends Screen {

    private final ArchaeologyJournalState state;
    private ArchaeologyJournalLogState logState;
    private final List<ArchaeologyJournalEntry> tableViews = new ArrayList<>();
    private final Map<ResourceLocation, TableDefinition> catalogDefinitions = new LinkedHashMap<>();
    private int selectedIndex = -1;
    private JournalSearchQuery currentSearch = JournalSearchQuery.EMPTY;
    private CatalogSorter.SortOrder currentSortOrder = CatalogSorter.SortOrder.DEFAULT;
    private boolean sortDescending = false;
    private EditBox searchField;
    private IconButton searchToggleButton;
    private IconButton sortButton;
    private IconButton sortOrderButton;
    private boolean searchExpanded = false;

    private JournalBookBackground.BookLayout bookLayout;
    private CatalogPanel catalogPanel;
    private RightPageContainer rightPage;
    private PageIndicator catalogPageIndicator;
    private Button catalogPrevButton;
    private Button catalogNextButton;
    private Button itemPrevButton;
    private Button itemNextButton;
    private long lastCatalogRevision;
    private long lastStateRevision;
    private long lastLogRevision;
    private boolean selectionInitialized;

    public ArchaeologyJournalScreen(ArchaeologyJournalState state) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"));
        this.state = state;
        this.logState = ArchaeologyJournalClientState.getLogState();
    }

    @Override
    protected void init() {
        super.init();
        this.bookLayout = JournalBookBackground.compute(this.width, this.height);
        this.rightPage = new RightPageContainer(this.bookLayout);
        this.reloadCatalog();
        this.rebuildViewModels();
        this.rebuildWidgets();

        this.lastCatalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
        this.lastStateRevision = ArchaeologyJournalClientState.getStateRevision();
        this.lastLogRevision = ArchaeologyJournalClientState.getLogRevision();
    }

    @Override
    public void removed() {
        ArchaeologyJournalClientState.rememberLastSelectedTable(this.selectedTableId());
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 键关闭搜索框
        if (this.searchExpanded && keyCode == 256) { // GLFW_KEY_ESCAPE
            this.searchExpanded = false;
            if (this.searchField != null) {
                this.searchField.setValue("");
                this.currentSearch = JournalSearchQuery.EMPTY;
            }
            this.rebuildWidgets();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void repositionElements() {
        RightPageContainer.Tab savedTab = this.rightPage != null ? this.rightPage.getActiveTab() : RightPageContainer.Tab.INTRO;
        int savedPage = this.rightPage != null ? this.rightPage.getPage() : 0;
        int savedCatalogPage = this.catalogPanel != null ? this.catalogPanel.getPage() : 0;
        boolean savedLogDetail = this.rightPage != null && this.rightPage.isShowingLogDetail();
        UUID savedLogEntryId = this.rightPage != null ? this.rightPage.getSelectedLogEntryId() : null;

        this.bookLayout = JournalBookBackground.compute(this.width, this.height);
        this.rightPage = new RightPageContainer(this.bookLayout);

        // 先重新灌入当前选中表的数据，再恢复右页状态；否则新容器内的默认页码会覆盖保存的用户上下文。
        this.updateItemGridPanel();
        this.rightPage.restoreLogSelection(savedLogEntryId, savedLogDetail);

        this.rightPage.setActiveTab(savedTab);
        this.rightPage.setPage(savedPage);

        this.rebuildWidgets();
        if (this.catalogPanel != null) {
            this.catalogPanel.setPage(savedCatalogPage);
        }
        this.syncButtonState();
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.refreshClientDataIfNeeded();

        JournalBookBackground.render(guiGraphics, this.bookLayout);

        int titleWidth = this.font.width(this.title);
        guiGraphics.drawString(this.font, this.title,
                (this.width - titleWidth) / 2, this.bookLayout.bookY() + 2, 0x4A3320, false);

        // 已删除目录标题文字，搜索框收起态路径图标替代

        if (this.catalogPanel != null) {
            this.catalogPanel.render(guiGraphics, this.font, this.selectedIndex, mouseX, mouseY);
        }

        if (!this.tableViews.isEmpty()) {
            Component unlockedTables = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.unlocked_tables",
                    this.unlockedTableCount(), this.tableViews.size());
            int unlockedTablesWidth = this.font.width(unlockedTables);
            int unlockedTablesX = this.bookLayout.leftPageX()
                    + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                    + JournalLayout.CATALOG_X_OFFSET
                    + (JournalLayout.CATALOG_TEXTURE_WIDTH - unlockedTablesWidth) / 2;
            guiGraphics.drawString(this.font, unlockedTables,
                    unlockedTablesX,
                    this.bookLayout.leftPageY() + JournalLayout.CATALOG_SUMMARY_Y, 0x6E5A42, false);
        }

        if (this.catalogPageIndicator != null && this.catalogPanel != null && !this.tableViews.isEmpty()) {
            this.catalogPageIndicator.setPage(this.catalogPanel.getPage(), this.catalogPanel.pageCount());
            this.catalogPageIndicator.render(guiGraphics, this.font);
        }

        if (this.tableViews.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_catalog"),
                    this.bookLayout.leftPageX()
                            + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                            + JournalLayout.CATALOG_X_OFFSET
                            + JournalLayout.CATALOG_LEFT_PAD,
                    this.bookLayout.leftPageY() + JournalLayout.CATALOG_LIST_TOP, 0x7A6247, false);
        }

        this.rightPage.render(guiGraphics, this.font, mouseX, mouseY);

        // 渲染搜索框展开态的暗黄色底色背景
        if (this.searchExpanded && this.searchField != null) {
            int searchBgX = this.searchField.getX() - 1;
            int searchBgY = this.searchField.getY() - 1;
            int searchBgW = this.searchField.getWidth() + 2;
            int searchBgH = this.searchField.getHeight() + 2;
            // 像素风木质边框色
            int borderColor = 0xFF8B6914;
            int borderLight = 0xFFB8943C;
            int bgColor = 0xFFD8C0A0;
            // 边框
            guiGraphics.fill(searchBgX, searchBgY, searchBgX + searchBgW, searchBgY + searchBgH, borderColor);
            // 高光边（上、左）
            guiGraphics.fill(searchBgX + 1, searchBgY + 1, searchBgX + searchBgW - 1, searchBgY + 2, borderLight);
            guiGraphics.fill(searchBgX + 1, searchBgY + 2, searchBgX + 2, searchBgY + searchBgH - 1, borderLight);
            // 背景
            guiGraphics.fill(searchBgX + 1, searchBgY + 1, searchBgX + searchBgW - 1, searchBgY + searchBgH - 1, bgColor);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 排序按钮、排序方向按钮和搜索按钮的 tooltip
        if (this.sortButton != null) {
            this.sortButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.sortOrderButton != null) {
            this.sortOrderButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.searchToggleButton != null) {
            this.searchToggleButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }

        ItemGridPanel.TooltipData tooltipData = this.rightPage.getTooltipData(mouseX, mouseY);
        if (tooltipData != null && !tooltipData.stack().isEmpty()) {
            if (tooltipData.hint() == null || this.minecraft == null || this.minecraft.level == null || this.minecraft.player == null) {
                guiGraphics.renderTooltip(this.font, tooltipData.stack(), mouseX, mouseY);
            } else {
                List<Component> tooltipLines = new ArrayList<>(tooltipData.stack().getTooltipLines(
                        Item.TooltipContext.of(this.minecraft.level), this.minecraft.player,
                        this.minecraft.options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL));
                tooltipLines.add(tooltipData.hint().copy().withStyle(ChatFormatting.GRAY));
                guiGraphics.renderTooltip(this.font, tooltipLines, tooltipData.stack().getTooltipImage(), mouseX, mouseY);
            }
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
                setSelectedIndex(clickedIndex);
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
        this.clearWidgets();

        this.addRenderableWidget(this.rightPage.getIntroTabButton());
        this.addRenderableWidget(this.rightPage.getArchaeologyTabButton());
        this.addRenderableWidget(this.rightPage.getLogTabButton());

        // 工具栏布局：搜索图标按钮 + 排序图标按钮，与目录条目左对齐
        int toolbarY = this.bookLayout.leftPageY() + JournalLayout.TOOLBAR_Y;
        int toolbarX = this.bookLayout.leftPageX()
                + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                + JournalLayout.CATALOG_X_OFFSET;

        // 搜索切换按钮（收起态显示放大镜，展开态显示×）
        this.searchToggleButton = this.addRenderableWidget(new IconButton(
                toolbarX, toolbarY,
                JournalLayout.SEARCH_ICON_SIZE,
                this.searchExpanded ? '✕' : '⌕',
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_tooltip"),
                this::toggleSearch
        ));

        // 排序图标按钮
        int sortOrderX = this.searchExpanded
                ? toolbarX + JournalLayout.SEARCH_FIELD_WIDTH + JournalLayout.TOOLBAR_GAP + JournalLayout.SORT_ICON_SIZE
                : toolbarX + JournalLayout.SEARCH_ICON_SIZE + JournalLayout.TOOLBAR_GAP;
        this.sortButton = this.addRenderableWidget(new IconButton(
                sortOrderX, toolbarY,
                JournalLayout.SORT_ICON_SIZE,
                CatalogSorter.sortOrderIcon(this.currentSortOrder),
                CatalogSorter.sortOrderTooltip(this.currentSortOrder),
                this::cycleSortOrder
        ));

        // 排序方向按钮（正序↑/倒序↓）
        int sortDirectionX = sortOrderX + JournalLayout.SORT_ICON_SIZE + JournalLayout.TOOLBAR_GAP;
        this.sortOrderButton = this.addRenderableWidget(new IconButton(
                sortDirectionX, toolbarY,
                JournalLayout.SORT_ICON_SIZE,
                CatalogSorter.sortDirectionIcon(this.sortDescending),
                CatalogSorter.sortDirectionTooltip(this.sortDescending),
                this::toggleSortDirection
        ));

        // 搜索框：展开态时在图标按钮右侧显示
        if (this.searchExpanded) {
            String savedText = this.searchField != null ? this.searchField.getValue() : "";
            int searchFieldX = toolbarX + JournalLayout.SEARCH_ICON_SIZE;
            this.searchField = new EditBox(this.font,
                    searchFieldX, toolbarY + (JournalLayout.SEARCH_QUICK_BAR_HEIGHT - JournalLayout.SEARCH_BAR_HEIGHT) / 2,
                    JournalLayout.SEARCH_FIELD_WIDTH, JournalLayout.SEARCH_BAR_HEIGHT,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_placeholder"));
            this.searchField.setHint(Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_placeholder"));
            this.searchField.setMaxLength(50);
            this.searchField.setResponder(this::onSearchChanged);
            this.searchField.setValue(savedText);
            this.searchField.setFocused(true);
            this.addRenderableWidget(this.searchField);
        } else {
            this.searchField = new EditBox(this.font, 0, 0, 0, 0, Component.empty());
            this.searchField.setVisible(false);
            this.searchField.setResponder(this::onSearchChanged);
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
        this.catalogPanel.setEntries(buildCatalogEntries());
        this.catalogPanel.ensureIndexVisible(this.selectedIndex);
        this.syncButtonState();
    }

    private void reloadCatalog() {
        this.catalogDefinitions.clear();
        this.catalogDefinitions.putAll(ArchaeologyJournalClientState.getCatalog());
    }

    private void refreshClientDataIfNeeded() {
        long currentCatalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
        long currentStateRevision = ArchaeologyJournalClientState.getStateRevision();
        long currentLogRevision = ArchaeologyJournalClientState.getLogRevision();
        boolean catalogChanged = currentCatalogRevision != this.lastCatalogRevision;
        boolean stateChanged = currentStateRevision != this.lastStateRevision;
        boolean logChanged = currentLogRevision != this.lastLogRevision;

        if (!catalogChanged && !stateChanged && !logChanged) {
            return;
        }

        if (stateChanged) {
            this.lastStateRevision = currentStateRevision;
            this.state.copyFrom(ArchaeologyJournalClientState.getState());
        }

        if (catalogChanged) {
            this.lastCatalogRevision = currentCatalogRevision;
            this.reloadCatalog();
        }

        if (logChanged) {
            this.lastLogRevision = currentLogRevision;
            this.logState = ArchaeologyJournalClientState.getLogState();
        }

        this.rebuildViewModels();
    }

    private void rebuildViewModels() {
        ResourceLocation selectedId = this.selectedTableId();
        ResourceLocation rememberedId = this.selectionInitialized
                ? null
                : ArchaeologyJournalClientState.getLastSelectedTableId();
        this.tableViews.clear();
        for (Map.Entry<ResourceLocation, TableDefinition> entry : this.catalogDefinitions.entrySet()) {
            ResourceLocation id = entry.getKey();
            TableDefinition definition = entry.getValue();
            ArchaeologyJournalState.TableProgress progress = this.state.getTable(id);
            ArchaeologyJournalLogState.TableLogHistory logHistory = this.logState.getTable(id);
            ArchaeologyJournalEntry view = ArchaeologyJournalEntry.of(id, definition, progress, logHistory);

            // 应用搜索过滤
            if (!this.currentSearch.isEmpty()) {
                boolean matches = this.currentSearch.matchesTableByItem(
                        id, view.displayName().getString(), view.unlocked(),
                        view.items());
                if (!matches) continue;
            }
            this.tableViews.add(view);
        }

        // 应用排序
        this.tableViews.sort(CatalogSorter.getComparator(this.currentSortOrder, this.sortDescending));

        this.selectedIndex = resolveSelectedIndex(selectedId, rememberedId);
        if (!this.selectionInitialized && !this.tableViews.isEmpty()) {
            this.selectionInitialized = true;
        }

        if (this.catalogPanel != null) {
            this.catalogPanel.setEntries(buildCatalogEntries());
            this.catalogPanel.ensureIndexVisible(this.selectedIndex);
        }

        updateItemGridPanel();
        syncButtonState();
    }

    
    // 搜索框内容变化回调
    private void onSearchChanged(String text) {
        this.currentSearch = JournalSearchQuery.parse(text);
        this.rebuildViewModels();
    }

    // 切换搜索框展开/收起
    private void toggleSearch() {
        this.searchExpanded = !this.searchExpanded;
        if (!this.searchExpanded && this.searchField != null) {
            // 收起时清空搜索
            this.searchField.setValue("");
            this.currentSearch = JournalSearchQuery.EMPTY;
        }
        this.rebuildWidgets();
    }

    // 循环切换排序方式
    private void cycleSortOrder() {
        this.currentSortOrder = this.currentSortOrder.next();
        if (this.sortButton != null) {
            this.sortButton.setIconChar(CatalogSorter.sortOrderIcon(this.currentSortOrder));
            this.sortButton.setTooltip(CatalogSorter.sortOrderTooltip(this.currentSortOrder));
        }
        this.rebuildViewModels();
    }

    // 切换正序/倒序
    private void toggleSortDirection() {
        this.sortDescending = !this.sortDescending;
        if (this.sortOrderButton != null) {
            this.sortOrderButton.setIconChar(CatalogSorter.sortDirectionIcon(this.sortDescending));
            this.sortOrderButton.setTooltip(CatalogSorter.sortDirectionTooltip(this.sortDescending));
        }
        this.rebuildViewModels();
    }

    
    private void updateItemGridPanel() {
        ArchaeologyJournalEntry selected = selectedTable();
        if (selected == null) {
            this.rightPage.setTable(null, List.of(),
                    0, 0, null);
        } else {
            // 构建完整物品列表，搜索匹配项标记为 highlighted
            List<ItemGridPanel.GridItem> gridItems = new ArrayList<>();
            for (ArchaeologyEntryItem iv : selected.items()) {
                boolean highlighted = this.currentSearch.isEmpty()
                        || this.currentSearch.mode() == JournalSearchQuery.Mode.NAMESPACE
                        || this.currentSearch.matchesItem(
                                iv.id(), iv.displayName().getString(), iv.unlocked(), iv.probability());
                gridItems.add(new ItemGridPanel.GridItem(
                        iv.id(), iv.displayName(), iv.tooltipHint(),
                        iv.probability(), iv.unlocked(), iv.count(), iv.signature(), highlighted));
            }
            this.rightPage.setTable(selected.id(), gridItems,
                    selected.parsedCount(), selected.totalCount(),
                    selected.logRef());
        }
    }

    private List<CatalogPanel.CatalogEntryData> buildCatalogEntries() {
        List<CatalogPanel.CatalogEntryData> catalogEntries = new ArrayList<>();
        for (ArchaeologyJournalEntry tv : this.tableViews) {
            catalogEntries.add(new CatalogPanel.CatalogEntryData(tv.id(), tv.displayName(), tv.unlocked()));
        }
        return catalogEntries;
    }

    private void setSelectedIndex(int index) {
        if (this.tableViews.isEmpty()) {
            this.selectedIndex = -1;
            return;
        }
        this.selectedIndex = Mth.clamp(index, 0, this.tableViews.size() - 1);
        if (this.catalogPanel != null) {
            this.catalogPanel.ensureIndexVisible(this.selectedIndex);
        }
        updateItemGridPanel();
        syncButtonState();
    }

    private int resolveSelectedIndex(@Nullable ResourceLocation selectedId, @Nullable ResourceLocation rememberedId) {
        if (this.tableViews.isEmpty()) {
            return -1;
        }

        int currentIndex = findTableIndex(selectedId);
        if (currentIndex >= 0) {
            return currentIndex;
        }

        int rememberedIndex = findTableIndex(rememberedId);
        if (rememberedIndex >= 0) {
            return rememberedIndex;
        }

        int firstUnlockedIndex = findFirstUnlockedIndex();
        return Math.max(firstUnlockedIndex, 0);
    }

    private int findTableIndex(@Nullable ResourceLocation tableId) {
        if (tableId == null) {
            return -1;
        }
        for (int i = 0; i < this.tableViews.size(); i++) {
            if (this.tableViews.get(i).id().equals(tableId)) {
                return i;
            }
        }
        return -1;
    }

    private int findFirstUnlockedIndex() {
        for (int i = 0; i < this.tableViews.size(); i++) {
            if (this.tableViews.get(i).unlocked()) {
                return i;
            }
        }
        return -1;
    }

    private PageButton createPageButton(int x, int y, boolean isForward, Button.OnPress onPress) {
        return new JournalPageButton(x, y, isForward, onPress);
    }

    private void syncButtonState() {
        boolean hasMultipleCatalogPages = this.catalogPanel != null && !this.tableViews.isEmpty()
                && this.catalogPanel.pageCount() > 1;
        if (this.catalogPrevButton != null) {
            this.catalogPrevButton.visible = hasMultipleCatalogPages;
            this.catalogPrevButton.active = hasMultipleCatalogPages && this.catalogPanel.getPage() > 0;
        }
        if (this.catalogNextButton != null) {
            this.catalogNextButton.visible = hasMultipleCatalogPages;
            this.catalogNextButton.active = hasMultipleCatalogPages
                    && this.catalogPanel.getPage() < this.catalogPanel.pageCount() - 1;
        }

        boolean hasMultipleItemPages = this.rightPage.pageCount() > 1;
        if (this.itemPrevButton != null) {
            this.itemPrevButton.visible = hasMultipleItemPages;
            this.itemPrevButton.active = hasMultipleItemPages && this.rightPage.getPage() > 0;
        }
        if (this.itemNextButton != null) {
            this.itemNextButton.visible = hasMultipleItemPages;
            this.itemNextButton.active = hasMultipleItemPages && this.rightPage.getPage() < this.rightPage.pageCount() - 1;
        }
    }

    @Nullable
    private ResourceLocation selectedTableId() {
        ArchaeologyJournalEntry selectedTable = this.selectedTable();
        return selectedTable != null ? selectedTable.id() : null;
    }

    private ArchaeologyJournalEntry selectedTable() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.tableViews.size()) {
            return null;
        }
        return this.tableViews.get(this.selectedIndex);
    }

    private int unlockedTableCount() {
        int unlocked = 0;
        for (ArchaeologyJournalEntry tableView : this.tableViews) {
            if (tableView.unlocked()) {
                unlocked++;
            }
        }
        return unlocked;
    }

    private static final class JournalPageButton extends PageButton {
        private static final ResourceLocation PAGE_FORWARD_HIGHLIGHTED_SPRITE = ResourceLocation.withDefaultNamespace("widget/page_forward_highlighted");
        private static final ResourceLocation PAGE_FORWARD_SPRITE = ResourceLocation.withDefaultNamespace("widget/page_forward");
        private static final ResourceLocation PAGE_BACKWARD_HIGHLIGHTED_SPRITE = ResourceLocation.withDefaultNamespace("widget/page_backward_highlighted");
        private static final ResourceLocation PAGE_BACKWARD_SPRITE = ResourceLocation.withDefaultNamespace("widget/page_backward");

        private final boolean isForward;

        private JournalPageButton(int x, int y, boolean isForward, Button.OnPress onPress) {
            super(x, y, isForward, onPress, true);
            this.isForward = isForward;
        }

        @Override
        public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            ResourceLocation sprite;
            if (this.isForward) {
                sprite = this.active && this.isHovered() ? PAGE_FORWARD_HIGHLIGHTED_SPRITE : PAGE_FORWARD_SPRITE;
            } else {
                sprite = this.active && this.isHovered() ? PAGE_BACKWARD_HIGHLIGHTED_SPRITE : PAGE_BACKWARD_SPRITE;
            }
            guiGraphics.blitSprite(sprite, this.getX(), this.getY(), JournalLayout.PAGE_BUTTON_WIDTH, JournalLayout.PAGE_BUTTON_HEIGHT);
        }
    }
}
