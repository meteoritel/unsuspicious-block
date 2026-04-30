package com.meteorite.unsuspiciousblock.client.ui.journal;

import com.meteorite.unsuspiciousblock.client.ui.journal.ArchaeologyJournalCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.client.ui.journal.ArchaeologyJournalCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ArchaeologyJournalScreen extends Screen {
    private static final int BUTTON_SIZE = 18;

    private final ArchaeologyJournalState state;
    private final List<TableView> tableViews = new ArrayList<>();
    private final Map<ResourceLocation, TableDefinition> catalogDefinitions = new LinkedHashMap<>();
    private int selectedIndex = -1;

    private JournalBookBackground.BookLayout bookLayout;
    private CatalogPanel catalogPanel;
    private ItemGridPanel itemGridPanel;
    private Button itemPrevButton;
    private Button itemNextButton;

    public ArchaeologyJournalScreen(ArchaeologyJournalState state) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"));
        this.state = state;
    }

    @Override
    protected void init() {
        this.bookLayout = JournalBookBackground.compute(this.width, this.height);
        this.catalogPanel = new CatalogPanel(List.of(), this.bookLayout);
        this.itemGridPanel = new ItemGridPanel(this.bookLayout);
        this.reloadCatalog();
        this.rebuildViewModels();
        super.init(); // 调用 rebuildWidgets()，此时 tableViews 就绪
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
        // 屏幕暗色背景
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF2A221A);

        // 书页背景纹理
        JournalBookBackground.render(guiGraphics, this.bookLayout);

        // 标题
        int titleWidth = this.font.width(this.title);
        guiGraphics.drawString(this.font, this.title,
                (this.width - titleWidth) / 2, this.bookLayout.bookY() + 2, 0x4A3320, false);

        // 左侧目录
        this.catalogPanel.render(guiGraphics, this.font, this.selectedIndex, mouseX, mouseY);

        // 右侧物品网格
        this.itemGridPanel.render(guiGraphics, this.font, mouseX, mouseY);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 目录面板点击
            if (this.catalogPanel.containsMouse(mouseX, mouseY)) {
                int clickedIndex = this.catalogPanel.handleClick(mouseX, mouseY);
                if (clickedIndex >= 0 && clickedIndex < this.tableViews.size()) {
                    setSelectedIndex(clickedIndex);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.catalogPanel.containsMouse(mouseX, mouseY)) {
            this.catalogPanel.mouseScrolled(scrollY);
            return true;
        }
        if (this.itemGridPanel.containsMouse(mouseX, mouseY) && this.itemGridPanel.pageCount() > 1) {
            this.itemGridPanel.changePage(scrollY < 0.0 ? 1 : -1);
            this.syncButtonState();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void rebuildWidgets() {
        this.clearWidgets();
        int bottomY = this.bookLayout.leftPageBottom() - BUTTON_SIZE - 4;

        // 右页（物品）翻页按钮
        this.itemPrevButton = this.addRenderableWidget(
                Button.builder(Component.literal("◀"), btn -> { itemGridPanel.changePage(-1); syncButtonState(); })
                        .bounds(this.bookLayout.rightPageX() + 8, bottomY, BUTTON_SIZE, BUTTON_SIZE).build());
        this.itemNextButton = this.addRenderableWidget(
                Button.builder(Component.literal("▶"), btn -> { itemGridPanel.changePage(1); syncButtonState(); })
                        .bounds(this.bookLayout.rightPageX() + 8 + BUTTON_SIZE + 4, bottomY, BUTTON_SIZE, BUTTON_SIZE).build());
    }

    private void reloadCatalog() {
        this.catalogDefinitions.clear();
        this.catalogDefinitions.putAll(ArchaeologyJournalClientState.getCatalog());
    }

    private void rebuildViewModels() {
        ResourceLocation selectedId = selectedTable() == null ? null : selectedTable().id();
        this.tableViews.clear();
        for (Map.Entry<ResourceLocation, ArchaeologyJournalState.TableProgress> entry : this.state.getTables().entrySet()) {
            TableDefinition definition = this.catalogDefinitions.get(entry.getKey());
            if (definition == null) {
                definition = new TableDefinition(entry.getKey(), Component.literal(entry.getKey().toString()), List.of(), 0.0, false);
            }
            this.tableViews.add(buildTableView(entry.getKey(), definition, entry.getValue()));
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

        // 将 TableView 转换为面板所需的数据
        List<CatalogPanel.CatalogEntry> catalogEntries = new ArrayList<>();
        for (TableView tv : this.tableViews) {
            catalogEntries.add(new CatalogPanel.CatalogEntry(tv.id(), tv.displayName(), tv.parsedCount() > 0));
        }
        this.catalogPanel.setEntries(catalogEntries);
        this.catalogPanel.ensureIndexVisible(this.selectedIndex);

        updateItemGridPanel();
        syncButtonState();
    }

    private void updateItemGridPanel() {
        TableView selected = selectedTable();
        if (selected == null) {
            this.itemGridPanel.setTable(Component.empty(), List.of(), 0.0, 0, 0, false);
        } else {
            List<ItemGridPanel.GridItem> gridItems = new ArrayList<>();
            for (ItemView iv : selected.items()) {
                gridItems.add(new ItemGridPanel.GridItem(iv.id(), iv.displayName(), iv.weight(), iv.unlocked(), iv.count()));
            }
            this.itemGridPanel.setTable(selected.displayName(), gridItems,
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
        this.catalogPanel.ensureIndexVisible(this.selectedIndex);
        this.itemGridPanel.changePage(0); // 重置物品页
        updateItemGridPanel();
        syncButtonState();
    }

    private void syncButtonState() {
        if (this.itemPrevButton != null) {
            this.itemPrevButton.active = this.itemGridPanel.pageCount() > 1 && this.itemGridPanel.getPage() > 0;
        }
        if (this.itemNextButton != null) {
            this.itemNextButton.active = this.itemGridPanel.pageCount() > 1 && this.itemGridPanel.getPage() < this.itemGridPanel.pageCount() - 1;
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
