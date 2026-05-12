package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.panel.CatalogPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.PageIndicator;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ArchaeologyJournalScreen extends Screen {

    private final ArchaeologyJournalState state;
    private ArchaeologyJournalLogState logState;
    private final List<TableView> tableViews = new ArrayList<>();
    private final Map<ResourceLocation, TableDefinition> catalogDefinitions = new LinkedHashMap<>();
    private int selectedIndex = -1;

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

        this.bookLayout = JournalBookBackground.compute(this.width, this.height);
        this.rightPage = new RightPageContainer(this.bookLayout);

        // 先重新灌入当前选中表的数据，再恢复右页状态；否则新容器内的默认页码会覆盖保存的用户上下文。
        this.updateItemGridPanel();

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

        Component catalogTitle = Component.translatable("screen.unsuspiciousblock.archaeology_journal.catalog");
        float catalogTitleScale = 1.125F;
        int catalogTitleWidth = Mth.ceil(this.font.width(catalogTitle) * catalogTitleScale);
        int catalogTitleX = this.bookLayout.leftPageX()
                + (this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                + JournalLayout.CATALOG_X_OFFSET
                + (JournalLayout.CATALOG_TEXTURE_WIDTH - catalogTitleWidth) / 2;
        int catalogTitleY = this.bookLayout.leftPageY() + JournalLayout.CATALOG_TITLE_Y;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(catalogTitleScale, catalogTitleScale, 1.0F);
        guiGraphics.drawString(this.font, catalogTitle,
                Mth.floor(catalogTitleX / catalogTitleScale),
                Mth.floor(catalogTitleY / catalogTitleScale), 0x5A3D23, false);
        guiGraphics.pose().popPose();

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

        ItemStack tooltipStack = this.rightPage.getTooltipStack(mouseX, mouseY);
        if (tooltipStack != null && !tooltipStack.isEmpty()) {
            guiGraphics.renderTooltip(this.font, tooltipStack, mouseX, mouseY);
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
            this.tableViews.add(buildTableView(id, definition, progress, logHistory));
        }
        this.tableViews.sort(Comparator
                .comparing((TableView view) -> !"minecraft".equals(view.id().getNamespace()))
                .thenComparing(view -> view.id().getNamespace())
                .thenComparing(view -> view.displayName().getString())
                .thenComparing(view -> view.id().getPath()));

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

    private void updateItemGridPanel() {
        TableView selected = selectedTable();
        if (selected == null) {
            this.rightPage.setTable(null, List.of(), 0.0, 0, 0, false, null, null, List.of());
        } else {
            List<ItemGridPanel.GridItem> gridItems = new ArrayList<>();
            for (ItemView iv : selected.items()) {
                gridItems.add(new ItemGridPanel.GridItem(iv.id(), iv.displayName(), iv.weight(), iv.unlocked(), iv.count(), iv.signature()));
            }
            this.rightPage.setTable(selected.id(), gridItems,
                    selected.totalWeight(), selected.parsedCount(), selected.totalCount(), selected.approximate(),
                    selected.firstUnlockedGameTime(), selected.firstUnlockedDayTime(), selected.recentLogs());
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
        Map<String, ArchaeologyJournalState.ItemProgress> progressItems =
                progress != null ? progress.getItems() : Map.of();
        for (ItemDefinition itemDefinition : definition.items()) {
            ArchaeologyJournalState.ItemProgress itemProgress = progressItems.get(itemDefinition.signature().toStoredKey());
            boolean unlocked = itemProgress != null && itemProgress.isUnlocked();
            int count = unlocked ? itemProgress.getCount() : 0;
            if (unlocked) {
                parsedCount++;
            }
            items.add(new ItemView(itemDefinition.id(), itemDefinition.displayName(), itemDefinition.weight(), unlocked, count,
                    itemDefinition.signature()));
        }
        boolean tableUnlocked = progress != null && progress.isUnlocked();
        Long firstUnlockedGameTime = logHistory != null ? logHistory.getFirstUnlockedGameTime() : null;
        Long firstUnlockedDayTime = logHistory != null ? logHistory.getFirstUnlockedDayTime() : null;
        List<ArchaeologyJournalLogState.ExcavationLogEntry> recentLogs = logHistory != null
                ? List.copyOf(logHistory.getRecentEntries())
                : List.of();
        return new TableView(tableId, definition.displayName(), items, definition.totalWeight(),
                definition.items().size(), parsedCount, definition.approximate(), tableUnlocked,
                firstUnlockedGameTime, firstUnlockedDayTime, recentLogs);
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
        return firstUnlockedIndex >= 0 ? firstUnlockedIndex : 0;
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
                             double totalWeight, int totalCount, int parsedCount, boolean approximate, boolean unlocked,
                             @Nullable Long firstUnlockedGameTime, @Nullable Long firstUnlockedDayTime,
                             List<ArchaeologyJournalLogState.ExcavationLogEntry> recentLogs) {
    }

    private record ItemView(ResourceLocation id, Component displayName, double weight,
                            boolean unlocked, int count,
                            LootResultSignature signature) {
    }
}
