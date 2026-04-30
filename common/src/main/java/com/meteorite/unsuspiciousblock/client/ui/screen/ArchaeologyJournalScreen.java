package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.widget.CatalogSelectionList;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
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
    private CatalogSelectionList catalogList;
    private RightPageContainer rightPage;
    private Button itemPrevButton;
    private Button itemNextButton;
    private boolean catalogCallbackEnabled;
    private long lastCatalogRevision;
    private long lastStateRevision;

    public ArchaeologyJournalScreen(ArchaeologyJournalState state) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"));
        this.state = state;
    }

    @Override
    protected void init() {
        this.bookLayout = JournalBookBackground.compute(this.width, this.height);
        this.rightPage = new RightPageContainer(this.bookLayout);
        this.reloadCatalog();
        super.init(); // 先创建 widget tree（catalogList 等）
        this.rebuildViewModels(); // 再填充数据（此时 catalogList 已存在）
        this.catalogCallbackEnabled = true;
        this.lastCatalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
        this.lastStateRevision = ArchaeologyJournalClientState.getStateRevision();
        this.syncButtonState();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.refreshClientDataIfNeeded();

        // 屏幕暗色背景
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF2A221A);

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
        // 非 widget 区域的点击（右侧面板其他子区域）
        if (this.rightPage.mouseClicked(mouseX, mouseY, button)) {
            syncButtonState();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.catalogList != null && this.catalogList.containsMouse(mouseX, mouseY)) {
            this.catalogList.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

        // 左侧目录列表
        int catalogX = this.bookLayout.leftPageX() + JournalLayout.CATALOG_LEFT_PAD;
        int catalogY = this.bookLayout.leftPageY() + JournalLayout.CATALOG_LIST_TOP;
        int catalogWidth = this.bookLayout.leftPageWidth() - JournalLayout.CATALOG_LEFT_PAD * 2;
        int catalogHeight = this.bookLayout.leftPageHeight() - JournalLayout.CATALOG_LIST_TOP - JournalLayout.CATALOG_LIST_BOTTOM_PAD;

        this.catalogList = new CatalogSelectionList(Minecraft.getInstance(), catalogWidth, catalogHeight, catalogY);
        this.catalogList.setPosition(catalogX, catalogY);
        this.catalogList.setSelectionCallback(index -> {
            if (catalogCallbackEnabled && index >= 0) {
                setSelectedIndex(index);
            }
        });
        this.addRenderableWidget(catalogList);

        // 书签切换按钮
        this.addRenderableWidget(rightPage.getBookmarkButton());

        int buttonSize = JournalLayout.PAGE_BUTTON_SIZE;
        int bottomY = this.bookLayout.leftPageBottom() - buttonSize - JournalLayout.PAGE_BUTTON_BOTTOM_PAD;

        // 右页（物品）翻页按钮
        this.itemPrevButton = this.addRenderableWidget(
                Button.builder(Component.literal("◀"), btn -> { rightPage.changePage(-1); syncButtonState(); })
                        .bounds(this.bookLayout.rightPageX() + 8, bottomY, buttonSize, buttonSize).build());
        this.itemNextButton = this.addRenderableWidget(
                Button.builder(Component.literal("▶"), btn -> { rightPage.changePage(1); syncButtonState(); })
                        .bounds(this.bookLayout.rightPageX() + 8 + buttonSize + 4, bottomY, buttonSize, buttonSize).build());
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
            if (progress == null) {
                progress = new ArchaeologyJournalState.TableProgress();
            }
            this.tableViews.add(buildTableView(id, definition, progress));
        }
        this.tableViews.sort(Comparator.comparing(view -> view.displayName().getString()));

        if (this.tableViews.isEmpty()) {
            this.selectedIndex = -1;
        } else if (selectedId != null) {
            for (int i = 0; i < this.tableViews.size(); i++) {
                if (this.tableViews.get(i).id().equals(selectedId)) {
                    this.selectedIndex = i;
                    break;
                }
            }
        }
        if (this.selectedIndex < 0 || this.selectedIndex >= this.tableViews.size()) {
            this.selectedIndex = this.tableViews.isEmpty() ? -1 : 0;
        }

        // 将 TableView 转换为目录列表数据
        this.catalogCallbackEnabled = false;
        List<CatalogSelectionList.CatalogEntryData> catalogEntries = new ArrayList<>();
        for (TableView tv : this.tableViews) {
            catalogEntries.add(new CatalogSelectionList.CatalogEntryData(tv.id(), tv.displayName(), tv.parsedCount() > 0));
        }
        if (this.catalogList != null) {
            this.catalogList.setCatalogEntries(catalogEntries, this.font, this.selectedIndex);
        }
        this.catalogCallbackEnabled = true;

        updateItemGridPanel();
        syncButtonState();
    }

    private void updateItemGridPanel() {
        TableView selected = selectedTable();
        if (selected == null) {
            this.rightPage.setTable(null, Component.empty(), List.of(), 0.0, 0, 0, false);
        } else {
            List<ItemGridPanel.GridItem> gridItems = new ArrayList<>();
            for (ItemView iv : selected.items()) {
                gridItems.add(new ItemGridPanel.GridItem(iv.id(), iv.displayName(), iv.weight(), iv.unlocked(), iv.count()));
            }
            this.rightPage.setTable(selected.id(), selected.displayName(), gridItems,
                    selected.totalWeight(), selected.parsedCount(), selected.totalCount(), selected.approximate());
        }
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
        this.rightPage.resetPage();
        updateItemGridPanel();
        syncButtonState();
    }

    private void syncButtonState() {
        if (this.itemPrevButton != null) {
            this.itemPrevButton.active = this.rightPage.pageCount() > 1 && this.rightPage.getPage() > 0;
        }
        if (this.itemNextButton != null) {
            this.itemNextButton.active = this.rightPage.pageCount() > 1 && this.rightPage.getPage() < this.rightPage.pageCount() - 1;
        }
    }

    private TableView selectedTable() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.tableViews.size()) return null;
        return this.tableViews.get(this.selectedIndex);
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
