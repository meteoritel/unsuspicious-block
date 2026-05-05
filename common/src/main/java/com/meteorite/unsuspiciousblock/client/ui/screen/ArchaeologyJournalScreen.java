package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.panel.CatalogPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ArchaeologyJournalScreen extends Screen {

    private final ArchaeologyJournalState state;
    private final List<TableView> tableViews = new ArrayList<>();
    private final Map<ResourceLocation, TableDefinition> catalogDefinitions = new LinkedHashMap<>();
    private int selectedIndex = -1;

    private JournalBookBackground.BookLayout bookLayout;
    private CatalogPanel catalogPanel;
    private RightPageContainer rightPage;
    private Button itemPrevButton;
    private Button itemNextButton;
    private long lastCatalogRevision;
    private long lastStateRevision;

    public ArchaeologyJournalScreen(ArchaeologyJournalState state) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"));
        this.state = state;
    }

    @Override
    protected void init() {
        super.init();
        this.bookLayout = JournalBookBackground.compute(this.width, this.height);
        this.rightPage = new RightPageContainer(this.bookLayout);
        this.reloadCatalog();
        this.rebuildViewModels();   // 先构建数据模型（tableViews），更新右侧面板
        this.rebuildWidgets();      // 创建所有 widget 和目录面板

        this.lastCatalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
        this.lastStateRevision = ArchaeologyJournalClientState.getStateRevision();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void repositionElements() {
        // resize 时保存右侧面板状态
        RightPageContainer.Tab savedTab = this.rightPage != null ? this.rightPage.getActiveTab() : RightPageContainer.Tab.INTRO;
        int savedPage = this.rightPage != null ? this.rightPage.getPage() : 0;

        // 重新计算布局
        this.bookLayout = JournalBookBackground.compute(this.width, this.height);
        this.rightPage = new RightPageContainer(this.bookLayout);

        // 恢复数据（setTable 会重置 Tab 和页码）
        this.updateItemGridPanel();

        // 恢复 Tab 和页码状态
        this.rightPage.setActiveTab(savedTab);
        this.rightPage.setGridPage(savedPage);

        this.rebuildWidgets();
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.refreshClientDataIfNeeded();

        // 屏幕暗色背景
        // guiGraphics.fill(0, 0, this.width, this.height, 0xFF2A221A);

        // 书页背景纹理
        JournalBookBackground.render(guiGraphics, this.bookLayout);

        // 标题
        int titleWidth = this.font.width(this.title);
        guiGraphics.drawString(this.font, this.title,
                (this.width - titleWidth) / 2, this.bookLayout.bookY() + 2, 0x4A3320, false);

        // 左侧目录标题
        guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.catalog"),
                this.bookLayout.leftPageX() + JournalLayout.CATALOG_LEFT_PAD + 4,
                this.bookLayout.leftPageY() + JournalLayout.CATALOG_TITLE_Y, 0x4A3320, false);

        if (this.catalogPanel != null) {
            this.catalogPanel.render(guiGraphics, this.font, this.selectedIndex, mouseX, mouseY);
        }

        // 空目录提示
        if (this.tableViews.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_catalog"),
                    this.bookLayout.leftPageX() + JournalLayout.CATALOG_LEFT_PAD + 4,
                    this.bookLayout.leftPageY() + JournalLayout.CATALOG_LIST_TOP, 0x7A6247, false);
        }

        // 右侧页面
        this.rightPage.render(guiGraphics, this.font, mouseX, mouseY);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
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
        // 非 widget 区域的点击（右侧面板其他子区域）
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

        // 双书签Tab切换按钮
        this.addRenderableWidget(rightPage.getIntroTabButton());
        this.addRenderableWidget(rightPage.getArchaeologyTabButton());

        int indicatorCenterX = this.bookLayout.rightPageX() + this.bookLayout.rightPageWidth() / 2;
        int bottomY = this.bookLayout.rightPageY() + JournalLayout.GRID_PAGE_INDICATOR_Y
                + (this.font.lineHeight - JournalLayout.PAGE_BUTTON_HEIGHT) / 2;
        int buttonWidth = JournalLayout.PAGE_BUTTON_WIDTH;
        int buttonGap = JournalLayout.PAGE_BUTTON_CENTER_GAP;

        // 右页（考古页）翻页按钮
        this.itemPrevButton = this.addRenderableWidget(
                createPageButton(indicatorCenterX - buttonGap - buttonWidth, bottomY, false,
                        btn -> { rightPage.changePage(-1); syncButtonState(); }));
        this.itemNextButton = this.addRenderableWidget(
                createPageButton(indicatorCenterX + buttonGap, bottomY, true,
                        btn -> { rightPage.changePage(1); syncButtonState(); }));

        // 左侧目录面板（resize 时也需要重建）
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
        boolean catalogChanged = currentCatalogRevision != this.lastCatalogRevision;
        boolean stateChanged = currentStateRevision != this.lastStateRevision;

        if (!catalogChanged && !stateChanged) {
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

        this.rebuildViewModels();
    }

    private void rebuildViewModels() {
        ResourceLocation selectedId = selectedTable() == null ? null : Objects.requireNonNull(selectedTable()).id();
        this.tableViews.clear();
        for (Map.Entry<ResourceLocation, TableDefinition> entry : this.catalogDefinitions.entrySet()) {
            ResourceLocation id = entry.getKey();
            TableDefinition definition = entry.getValue();
            ArchaeologyJournalState.TableProgress progress = this.state.getTable(id);
            if (progress == null || !progress.isUnlocked()) {
                continue;
            }
            this.tableViews.add(buildTableView(id, definition, progress));
        }
        this.tableViews.sort(Comparator.comparing(view -> view.displayName().getString()));

        boolean foundSelected = selectedId == null;
        if (this.tableViews.isEmpty()) {
            this.selectedIndex = -1;
        } else if (selectedId != null) {
            for (int i = 0; i < this.tableViews.size(); i++) {
                if (this.tableViews.get(i).id().equals(selectedId)) {
                    this.selectedIndex = i;
                    foundSelected = true;
                    break;
                }
            }
        }
        if (this.tableViews.isEmpty()) {
            this.selectedIndex = -1;
        } else if (!foundSelected || this.selectedIndex < 0 || this.selectedIndex >= this.tableViews.size()) {
            this.selectedIndex = 0;
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
            this.rightPage.setTable(null, List.of(), 0.0, 0, 0, false);
        } else {
            List<ItemGridPanel.GridItem> gridItems = new ArrayList<>();
            for (ItemView iv : selected.items()) {
                gridItems.add(new ItemGridPanel.GridItem(iv.id(), iv.displayName(), iv.weight(), iv.unlocked(), iv.count()));
            }
            this.rightPage.setTable(selected.id(), gridItems,
                    selected.totalWeight(), selected.parsedCount(), selected.totalCount(), selected.approximate());
        }
    }

    private List<CatalogPanel.CatalogEntryData> buildCatalogEntries() {
        List<CatalogPanel.CatalogEntryData> catalogEntries = new ArrayList<>();
        for (TableView tv : this.tableViews) {
            catalogEntries.add(new CatalogPanel.CatalogEntryData(tv.id(), tv.displayName()));
        }
        return catalogEntries;
    }

    private static TableView buildTableView(ResourceLocation tableId, TableDefinition definition, ArchaeologyJournalState.TableProgress progress) {
        List<ItemView> items = new ArrayList<>();
        int parsedCount = 0;
        for (ItemDefinition itemDefinition : definition.items()) {
            boolean unlocked = progress.getItems().containsKey(itemDefinition.id())
                    && progress.getItems().get(itemDefinition.id()).isUnlocked();
            int count = unlocked ? progress.getItems().get(itemDefinition.id()).getCount() : 0;
            if (unlocked) parsedCount++;
            items.add(new ItemView(itemDefinition.id(), itemDefinition.displayName(), itemDefinition.weight(), unlocked, count));
        }
        return new TableView(tableId, definition.displayName(), items, definition.totalWeight(),
                definition.items().size(), parsedCount, definition.approximate());
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
        this.rightPage.resetPage();
        updateItemGridPanel();
        syncButtonState();
    }

    private PageButton createPageButton(int x, int y, boolean isForward, Button.OnPress onPress) {
        return new JournalPageButton(x, y, isForward, onPress);
    }

    private void syncButtonState() {
        boolean isArchaeology = this.rightPage.isArchaeologyActive();
        if (this.itemPrevButton != null) {
            this.itemPrevButton.visible = isArchaeology && this.rightPage.pageCount() > 1;
            this.itemPrevButton.active = isArchaeology && this.rightPage.pageCount() > 1 && this.rightPage.getPage() > 0;
        }
        if (this.itemNextButton != null) {
            this.itemNextButton.visible = isArchaeology && this.rightPage.pageCount() > 1;
            this.itemNextButton.active = isArchaeology && this.rightPage.pageCount() > 1 && this.rightPage.getPage() < this.rightPage.pageCount() - 1;
        }
    }

    private TableView selectedTable() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.tableViews.size()) return null;
        return this.tableViews.get(this.selectedIndex);
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
                             double totalWeight, int totalCount, int parsedCount, boolean approximate) {
    }

    private record ItemView(ResourceLocation id, Component displayName, double weight, boolean unlocked, int count) {
        private net.minecraft.world.item.ItemStack stack() {
            return new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(this.id));
        }
    }
}
