package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.panel.CatalogPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.PageIndicator;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalSearchQuery;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.TriggerType;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import com.meteorite.unsuspiciousblock.loottable.ProbabilityFormat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ArchaeologyJournalScreen extends Screen {

    private final ArchaeologyJournalState state;
    private ArchaeologyJournalLogState logState;
    private final List<TableView> tableViews = new ArrayList<>();
    private final Map<ResourceLocation, TableDefinition> catalogDefinitions = new LinkedHashMap<>();
    private int selectedIndex = -1;
    private JournalSearchQuery currentSearch = JournalSearchQuery.EMPTY;
    private JournalSearchQuery.SortOrder currentSortOrder = JournalSearchQuery.SortOrder.DEFAULT;
    private EditBox searchField;
    private Button sortButton;

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

        // 搜索框上方的小标题（取代原放大目录标题，搜索框紧随其后）
        Component catalogLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.catalog");
        guiGraphics.drawString(this.font, catalogLabel,
                this.bookLayout.leftPageX()
                        + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                        + JournalLayout.CATALOG_X_OFFSET
                        + JournalLayout.CATALOG_LEFT_PAD,
                this.bookLayout.leftPageY() + JournalLayout.CATALOG_TITLE_Y,
                0x5A3D23, false);

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

        super.render(guiGraphics, mouseX, mouseY, partialTick);

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

        // 搜索框：左侧页面目录标题下方
        int searchX = this.bookLayout.leftPageX() + JournalLayout.CATALOG_X_OFFSET;
        int searchY = this.bookLayout.leftPageY() + JournalLayout.SEARCH_BAR_Y;
        this.searchField = new EditBox(this.font,
                searchX, searchY,
                JournalLayout.SEARCH_FIELD_WIDTH, JournalLayout.SEARCH_BAR_HEIGHT,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_placeholder"));
        this.searchField.setHint(Component.translatable("screen.unsuspiciousblock.archaeology_journal.search_placeholder"));
        this.searchField.setMaxLength(50);
        this.searchField.setResponder(this::onSearchChanged);
        this.addRenderableWidget(this.searchField);

        // 排序按钮：搜索框右侧
        int sortX = searchX + JournalLayout.SEARCH_FIELD_WIDTH + JournalLayout.SORT_BUTTON_GAP;
        this.sortButton = this.addRenderableWidget(Button.builder(sortOrderLabel(), btn -> cycleSortOrder())
                .pos(sortX, searchY)
                .size(JournalLayout.SORT_BUTTON_WIDTH, JournalLayout.SEARCH_BAR_HEIGHT)
                .build());

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
            TableView view = buildTableView(id, definition, progress, logHistory);

            // 应用搜索过滤
            if (!this.currentSearch.isEmpty()) {
                boolean matches = this.currentSearch.matchesCatalogEntry(
                        id, view.displayName().getString(), view.unlocked());
                // 物品级搜索也需要匹配
                if (!matches && (this.currentSearch.mode() == JournalSearchQuery.Mode.NAME
                        || this.currentSearch.mode() == JournalSearchQuery.Mode.RARITY)) {
                    for (ItemView iv : view.items()) {
                        if (this.currentSearch.matchesItem(iv.id(), iv.displayName().getString(),
                                iv.unlocked(), iv.probability())) {
                            matches = true;
                            break;
                        }
                    }
                }
                if (!matches) continue;
            }
            this.tableViews.add(view);
        }

        // 应用排序
        this.tableViews.sort(getSortComparator(this.currentSortOrder));

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

    // 根据排序方式返回对应比较器
    private Comparator<TableView> getSortComparator(JournalSearchQuery.SortOrder order) {
        return switch (order) {
            case DEFAULT -> Comparator
                    .comparing((TableView view) -> !view.unlocked())
                    .thenComparing(view -> !"minecraft".equals(view.id().getNamespace()))
                    .thenComparing(view -> view.id().getNamespace())
                    .thenComparing(view -> view.displayName().getString())
                    .thenComparing(view -> view.id().getPath());
            case NAME -> Comparator
                    .comparing((TableView view) -> view.displayName().getString());
            case RARITY -> Comparator
                    .<TableView>comparingDouble(TableView::lowestItemProbability).reversed()
                    .thenComparing(view -> view.displayName().getString());
            case UNLOCK -> Comparator
                    .comparing((TableView view) -> !view.unlocked())
                    .thenComparing(view -> view.displayName().getString());
        };
    }

    // 搜索框内容变化回调
    private void onSearchChanged(String text) {
        this.currentSearch = JournalSearchQuery.parse(text);
        this.rebuildViewModels();
    }

    // 循环切换排序方式
    private void cycleSortOrder() {
        JournalSearchQuery.SortOrder[] orders = JournalSearchQuery.SortOrder.values();
        int nextIndex = (this.currentSortOrder.ordinal() + 1) % orders.length;
        this.currentSortOrder = orders[nextIndex];
        if (this.sortButton != null) {
            this.sortButton.setMessage(sortOrderLabel());
        }
        this.rebuildViewModels();
    }

    // 排序按钮标签
    private Component sortOrderLabel() {
        return switch (this.currentSortOrder) {
            case DEFAULT -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.sort.default");
            case NAME -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.sort.name");
            case RARITY -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.sort.rarity");
            case UNLOCK -> Component.translatable("screen.unsuspiciousblock.archaeology_journal.sort.unlock");
        };
    }

    private void updateItemGridPanel() {
        TableView selected = selectedTable();
        if (selected == null) {
            this.rightPage.setTable(null, List.of(),
                    0, 0,
                    null, null, null, List.of());
        } else {
            // 物品级搜索过滤：NAME 和 RARITY 模式下只显示匹配的物品
            List<ItemView> filteredItems;
            if (!this.currentSearch.isEmpty()
                    && (this.currentSearch.mode() == JournalSearchQuery.Mode.NAME
                    || this.currentSearch.mode() == JournalSearchQuery.Mode.RARITY)) {
                filteredItems = selected.items().stream()
                        .filter(iv -> this.currentSearch.matchesItem(
                                iv.id(), iv.displayName().getString(), iv.unlocked(), iv.probability()))
                        .toList();
            } else {
                filteredItems = selected.items();
            }

            List<ItemGridPanel.GridItem> gridItems = new ArrayList<>();
            for (ItemView iv : filteredItems) {
                gridItems.add(new ItemGridPanel.GridItem(iv.id(), iv.displayName(), iv.tooltipHint(),
                        iv.probability(), iv.unlocked(), iv.count(), iv.signature()));
            }
            this.rightPage.setTable(selected.id(), gridItems,
                    selected.parsedCount(), selected.totalCount(),
                    selected.firstUnlockedGameTime(), selected.firstUnlockedDayTime(),
                    selected.firstUnlockTriggerType(), selected.logEntries());
        }
    }

    private List<CatalogPanel.CatalogEntryData> buildCatalogEntries() {
        List<CatalogPanel.CatalogEntryData> catalogEntries = new ArrayList<>();
        for (TableView tv : this.tableViews) {
            catalogEntries.add(new CatalogPanel.CatalogEntryData(tv.id(), tv.displayName(), tv.unlocked()));
        }
        return catalogEntries;
    }

    private static TableView buildTableView(ResourceLocation tableId, TableDefinition definition,
                                            @Nullable ArchaeologyJournalState.TableProgress progress,
                                            @Nullable ArchaeologyJournalLogState.TableLogHistory logHistory) {
        List<ItemView> items = new ArrayList<>();
        int parsedCount = 0;
        for (ItemDefinition itemDefinition : definition.items()) {
            ArchaeologyJournalState.ItemProgress itemProgress = progress != null
                    ? progress.getItemProgress(itemDefinition.signature())
                    : null;
            boolean unlocked = itemProgress != null && itemProgress.isUnlocked();
            int count = progress != null ? progress.getItemCount(itemDefinition.signature()) : 0;
            if (unlocked) {
                parsedCount++;
            }
            items.add(new ItemView(itemDefinition.id(), itemDefinition.displayName(), itemDefinition.tooltipHint(),
                    itemDefinition.probability(), unlocked, count, itemDefinition.signature()));
        }
        boolean tableUnlocked = progress != null && progress.isUnlocked();
        Long firstUnlockedGameTime = logHistory != null ? logHistory.getFirstUnlockedGameTime() : null;
        Long firstUnlockedDayTime = logHistory != null ? logHistory.getFirstUnlockedDayTime() : null;
        TriggerType firstUnlockTriggerType = logHistory != null
                ? logHistory.getFirstUnlockTriggerType()
                : null;
        List<ExcavationLogEntry> logEntries = logHistory != null
                ? List.copyOf(logHistory.getEntries())
                : List.of();
        return new TableView(tableId, definition.displayName(), items, definition.simulationCount(),
                definition.items().size(), parsedCount, tableUnlocked,
                firstUnlockedGameTime, firstUnlockedDayTime, firstUnlockTriggerType, logEntries);
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
        TableView selectedTable = this.selectedTable();
        return selectedTable != null ? selectedTable.id() : null;
    }

    private TableView selectedTable() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.tableViews.size()) {
            return null;
        }
        return this.tableViews.get(this.selectedIndex);
    }

    private int unlockedTableCount() {
        int unlocked = 0;
        for (TableView tableView : this.tableViews) {
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

    private record TableView(ResourceLocation id, Component displayName, List<ItemView> items,
                             int simulationCount, int totalCount, int parsedCount, boolean unlocked,
                             @Nullable Long firstUnlockedGameTime, @Nullable Long firstUnlockedDayTime,
                             @Nullable TriggerType firstUnlockTriggerType,
                             List<ExcavationLogEntry> logEntries) {

        // 返回表中最低概率物品的比例值，用于 RARITY 排序
        double lowestItemProbability() {
            double lowest = 1.0;
            for (ItemView item : items) {
                double fraction = ProbabilityFormat.parsePercentToFraction(item.probability());
                if (fraction >= 0 && fraction < lowest) {
                    lowest = fraction;
                }
            }
            return lowest;
        }
    }

    private record ItemView(ResourceLocation id, Component displayName,
                            @Nullable Component tooltipHint, String probability,
                            boolean unlocked, int count,
                            LootResultSignature signature) {
    }
}
