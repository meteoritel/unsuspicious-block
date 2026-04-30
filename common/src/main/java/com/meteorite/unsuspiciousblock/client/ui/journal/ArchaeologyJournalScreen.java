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
    private static final int MARGIN = 16;
    private static final int HEADER_Y = 12;
    private static final int PANEL_GAP = 12;
    private static final int TITLE_Y = 14;
    private static final int PANEL_TOP = 30;
    private static final int PANEL_BOTTOM_PADDING = 36;
    private static final int CATALOG_ROW_HEIGHT = 22;
    private static final int CATALOG_ROWS_PER_PAGE = 5;
    private static final int ITEM_COLUMNS = 5;
    private static final int ITEM_ROWS_PER_PAGE = 4;
    private static final int ITEM_CELL_MIN = 16;
    private static final int ITEM_CELL_MAX = 48;
    private static final int BUTTON_SIZE = 18;

    private final ArchaeologyJournalState state;
    private final List<TableView> tableViews = new ArrayList<>();
    private final Map<ResourceLocation, TableDefinition> catalogDefinitions = new LinkedHashMap<>();
    private int selectedIndex = -1;
    private int catalogPage;
    private int itemPage;
    private Button catalogPrevButton;
    private Button catalogNextButton;
    private Button itemPrevButton;
    private Button itemNextButton;

    public ArchaeologyJournalScreen(ArchaeologyJournalState state) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"));
        this.state = state;
    }

    @Override
    protected void init() {
        this.reloadCatalog();
        this.rebuildViewModels();
        super.init(); // Screen.init() 末尾会调用 this.rebuildWidgets()（多态），此时 tableViews 已就绪
        this.syncButtonState();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        Layout layout = this.layout();
        this.renderPaperBackground(guiGraphics, layout);
        this.renderHeader(guiGraphics);
        this.renderCatalogPanel(guiGraphics, layout, mouseX, mouseY);
        this.renderEntriesPanel(guiGraphics, layout, mouseX, mouseY);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.handleCatalogClick(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Layout layout = this.layout();
        if (this.isWithinCatalog(mouseX, mouseY, layout) && this.catalogPageCount() > 1) {
            this.changeCatalogPage(scrollY < 0.0D ? 1 : -1);
            return true;
        }
        if (this.isWithinEntries(mouseX, mouseY, layout) && this.itemPageCount() > 1) {
            this.changeItemPage(scrollY < 0.0D ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void reloadCatalog() {
        this.catalogDefinitions.clear();
        // 使用服务端同步的目录，不再从客户端资源管理器解析
        this.catalogDefinitions.putAll(ArchaeologyJournalClientState.getCatalog());
    }

    private void rebuildViewModels() {
        ResourceLocation selectedId = this.selectedTable() == null ? null : this.selectedTable().id();
        this.tableViews.clear();
        for (Map.Entry<ResourceLocation, ArchaeologyJournalState.TableProgress> entry : this.state.getTables().entrySet()) {
            TableDefinition definition = this.catalogDefinitions.get(entry.getKey());
            if (definition == null) {
                definition = new TableDefinition(entry.getKey(), Component.literal(entry.getKey().toString()), List.of(), 0.0D, false);
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
            this.selectedIndex = 0;
        }
        this.clampPages();
    }

    private static TableView buildTableView(ResourceLocation tableId, TableDefinition definition, ArchaeologyJournalState.TableProgress progress) {
        List<ItemView> items = new ArrayList<>();
        int parsedCount = 0;
        for (ItemDefinition itemDefinition : definition.items()) {
            boolean unlocked = progress.getItems().containsKey(itemDefinition.id()) && progress.getItems().get(itemDefinition.id()).isUnlocked();
            int count = unlocked ? progress.getItems().get(itemDefinition.id()).getCount() : 0;
            if (unlocked) {
                parsedCount++;
            }
            items.add(new ItemView(itemDefinition.id(), itemDefinition.displayName(), itemDefinition.weight(), unlocked, count));
        }
        return new TableView(tableId, definition.displayName(), items, definition.totalWeight(), definition.items().size(), parsedCount, definition.approximate());
    }

    @Override
    protected void rebuildWidgets() {
        this.clearWidgets();
        Layout layout = this.layout();
        int bottomY = Mth.clamp(layout.bottom() - BUTTON_SIZE - 2, layout.top() + 2, this.height - BUTTON_SIZE - 2);
        int leftButtonX = layout.leftX() + 8;
        int leftSecondButtonX = leftButtonX + BUTTON_SIZE + 4;
        int rightButtonX = layout.rightX() + 8;
        int rightSecondButtonX = rightButtonX + BUTTON_SIZE + 4;

        this.catalogPrevButton = this.addRenderableWidget(Button.builder(Component.literal("▲"), button -> this.changeCatalogPage(-1))
                .bounds(leftButtonX, bottomY, BUTTON_SIZE, BUTTON_SIZE)
                .build());
        this.catalogNextButton = this.addRenderableWidget(Button.builder(Component.literal("▼"), button -> this.changeCatalogPage(1))
                .bounds(leftSecondButtonX, bottomY, BUTTON_SIZE, BUTTON_SIZE)
                .build());
        this.itemPrevButton = this.addRenderableWidget(Button.builder(Component.literal("◀"), button -> this.changeItemPage(-1))
                .bounds(rightButtonX, bottomY, BUTTON_SIZE, BUTTON_SIZE)
                .build());
        this.itemNextButton = this.addRenderableWidget(Button.builder(Component.literal("▶"), button -> this.changeItemPage(1))
                .bounds(rightSecondButtonX, bottomY, BUTTON_SIZE, BUTTON_SIZE)
                .build());
    }

    private void renderPaperBackground(GuiGraphics guiGraphics, Layout layout) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF2A221A);
        guiGraphics.fill(layout.leftX(), layout.top(), layout.leftRight(), layout.bottom(), 0xFFE7D8B6);
        guiGraphics.fill(layout.rightX(), layout.top(), layout.rightRight(), layout.bottom(), 0xFFF0E0C2);

        this.drawBorder(guiGraphics, layout.leftX(), layout.top(), layout.leftRight(), layout.bottom(), 0xFF8B6A45);
        this.drawBorder(guiGraphics, layout.rightX(), layout.top(), layout.rightRight(), layout.bottom(), 0xFF8B6A45);

        for (int y = layout.top() + 4; y < layout.bottom() - 2; y += 6) {
            guiGraphics.fill(layout.leftX() + 2, y, layout.leftRight() - 2, y + 1, 0x12B08A63);
            guiGraphics.fill(layout.rightX() + 2, y, layout.rightRight() - 2, y + 1, 0x10B08A63);
        }
    }

    private void drawBorder(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int color) {
        guiGraphics.fill(left - 1, top - 1, right + 1, top, color);
        guiGraphics.fill(left - 1, bottom, right + 1, bottom + 1, color);
        guiGraphics.fill(left - 1, top, left, bottom, color);
        guiGraphics.fill(right, top, right + 1, bottom, color);
        guiGraphics.fill(left + 3, top + 3, right - 3, top + 4, 0x664D3A26);
    }

    private void renderHeader(GuiGraphics guiGraphics) {
        int titleWidth = this.font.width(this.title);
        guiGraphics.drawString(this.font, this.title, (this.width - titleWidth) / 2, TITLE_Y, 0x4A3320, false);

        Layout layout = this.layout();
        int unlockedTables = this.tableViews.size();
        TableView selected = this.selectedTable();
        int parsed = selected == null ? 0 : selected.parsedCount();
        int total = selected == null ? 0 : selected.totalCount();
        Component summary = Component.translatable("screen.unsuspiciousblock.archaeology_journal.summary", unlockedTables, parsed, total);
        guiGraphics.drawString(this.font, summary, layout.leftX() + 8, HEADER_Y + 18, 0x5A422C, false);

        if (selected != null) {
            guiGraphics.drawString(this.font, selected.displayName(), layout.rightX() + 8, HEADER_Y + 18, 0x5A422C, false);
            this.drawProgressBar(guiGraphics, layout.rightX() + 8, HEADER_Y + 31, Math.max(0, layout.rightWidth() - 16), selected.parsedCount(), selected.totalCount());
        }
    }

    private void drawProgressBar(GuiGraphics guiGraphics, int x, int y, int width, int value, int max) {
        guiGraphics.fill(x, y, x + width, y + 8, 0x553F2E1C);
        int fillWidth = max <= 0 ? 0 : Mth.clamp((int) Math.round(width * (value / (double) max)), 0, width);
        guiGraphics.fill(x, y, x + fillWidth, y + 8, 0xCC9A6B3D);
        guiGraphics.fill(x, y, x + width, y + 1, 0x88462F1F);
        guiGraphics.fill(x, y + 7, x + width, y + 8, 0x88462F1F);
        guiGraphics.fill(x, y, x + 1, y + 8, 0x88462F1F);
        guiGraphics.fill(x + width - 1, y, x + width, y + 8, 0x88462F1F);
        guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.progress", value, max), x, y + 10, 0x5A422C, false);
    }

    private void renderCatalogPanel(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        int x = layout.leftX() + 8;
        int y = layout.top() + 10;
        guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.catalog"), x, y, 0x4A3320, false);

        List<TableView> visibleTables = this.visibleCatalogTables();
        if (visibleTables.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_catalog"), x, y + 24, 0x7A6247, false);
            this.renderPageFooter(guiGraphics, layout.leftX(), layout.leftRight(), layout.bottom(), this.catalogPage + 1, this.catalogPageCount(), true);
            return;
        }

        int listY = y + 24;
        int maxWidth = layout.leftWidth() - 16;
        for (int i = 0; i < visibleTables.size(); i++) {
            int actualIndex = this.catalogPage * CATALOG_ROWS_PER_PAGE + i;
            TableView table = visibleTables.get(i);
            int rowY = listY + i * CATALOG_ROW_HEIGHT;
            boolean selected = actualIndex == this.selectedIndex;
            boolean hovered = mouseX >= layout.leftX() + 4 && mouseX <= layout.leftRight() - 4 && mouseY >= rowY - 2 && mouseY <= rowY + CATALOG_ROW_HEIGHT - 3;
            int background = selected ? 0x66BC8B4F : hovered ? 0x33BC8B4F : 0x00000000;
            if (background != 0) {
                guiGraphics.fill(layout.leftX() + 3, rowY - 1, layout.leftRight() - 4, rowY + CATALOG_ROW_HEIGHT - 3, background);
            }
            boolean hasResolved = table.parsedCount() > 0;
            String displayText = hasResolved ? table.displayName().getString() : "???";
            int textColor = hasResolved ? (selected ? 0x7B3E18 : 0x5A422C) : 0x777777;
            guiGraphics.drawString(this.font, this.truncate(displayText, maxWidth), x, rowY, textColor, false);
        }

        this.renderPageFooter(guiGraphics, layout.leftX(), layout.leftRight(), layout.bottom(), this.catalogPage + 1, this.catalogPageCount(), true);
    }

    private void renderEntriesPanel(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        int x = layout.rightX() + 8;
        int y = layout.top() + 10;
        guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.entries"), x, y, 0x4A3320, false);

        TableView selected = this.selectedTable();
        if (selected == null) {
            guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_entries"), x, y + 24, 0x7A6247, false);
            this.renderPageFooter(guiGraphics, layout.rightX(), layout.rightRight(), layout.bottom(), this.itemPage + 1, this.itemPageCount(), false);
            return;
        }

        List<ItemView> visibleItems = this.visibleItems(selected);
        if (visibleItems.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_entries"), x, y + 24, 0x7A6247, false);
            this.renderPageFooter(guiGraphics, layout.rightX(), layout.rightRight(), layout.bottom(), this.itemPage + 1, this.itemPageCount(), false);
            return;
        }

        int cellSize = layout.itemCellSize();
        if (cellSize < ITEM_CELL_MIN) {
            guiGraphics.drawString(this.font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.too_small"), x, y + 24, 0x7A6247, false);
            this.renderPageFooter(guiGraphics, layout.rightX(), layout.rightRight(), layout.bottom(), this.itemPage + 1, this.itemPageCount(), false);
            return;
        }

        int gridTop = y + 26;
        int gridX0 = layout.rightX() + 8;
        for (int index = 0; index < visibleItems.size(); index++) {
            ItemView item = visibleItems.get(index);
            int column = index % ITEM_COLUMNS;
            int row = index / ITEM_COLUMNS;
            int cellX = gridX0 + column * cellSize;
            int cellY = gridTop + row * cellSize;
            boolean hovered = mouseX >= cellX && mouseX < cellX + cellSize && mouseY >= cellY && mouseY < cellY + cellSize;
            this.drawItemCell(guiGraphics, cellX, cellY, cellSize, item, hovered, selected);
        }

        this.renderPageFooter(guiGraphics, layout.rightX(), layout.rightRight(), layout.bottom(), this.itemPage + 1, this.itemPageCount(), false);
    }

    private void drawItemCell(GuiGraphics guiGraphics, int x, int y, int size, ItemView item, boolean hovered, TableView selected) {
        int baseColor = item.unlocked() ? 0x66D8C39A : 0x4C6F6252;
        if (hovered) {
            baseColor = item.unlocked() ? 0x88E2D0AA : 0x66776456;
        }
        guiGraphics.fill(x, y, x + size, y + size, baseColor);
        guiGraphics.fill(x + 1, y + 1, x + size - 1, y + 2, 0xBB8C6A45);
        guiGraphics.fill(x + 1, y + size - 2, x + size - 1, y + size - 1, 0xBB8C6A45);
        guiGraphics.fill(x + 1, y + 2, x + 2, y + size - 2, 0xBB8C6A45);
        guiGraphics.fill(x + size - 2, y + 2, x + size - 1, y + size - 2, 0xBB8C6A45);

        if (item.unlocked()) {
            guiGraphics.renderItem(item.stack(), x + (size - 16) / 2, y + 4);
        } else {
            guiGraphics.fill(x + 6, y + 6, x + size - 6, y + size - 18, 0xDD201914);
            guiGraphics.drawString(this.font, "?", x + size / 2 - 2, y + 9, 0xFFF3E7C5, false);
        }

        int textWidth = size - 4;
        int textColor = item.unlocked() ? 0x4A3320 : 0x6E655B;
        if (size < 42) {
            this.drawCenteredTruncatedString(guiGraphics, item.unlocked() ? item.displayName().getString() : "???", x + 2, y + size - 10, textWidth, textColor);
            return;
        }

        int baseTextY = y + size - 21;
        this.drawCenteredTruncatedString(guiGraphics, item.unlocked() ? item.displayName().getString() : "???", x + 2, baseTextY, textWidth, textColor);
        this.drawCenteredTruncatedString(guiGraphics, item.unlocked() ? "×" + item.count() : "???", x + 2, baseTextY + 9, textWidth, item.unlocked() && item.count() > 0 ? 0x7B3E18 : 0x887C6A);

        String probability = item.unlocked() ? this.formatProbability(item.weight(), selected.totalWeight(), selected.approximate()) : "???";
        this.drawCenteredTruncatedString(guiGraphics, probability, x + 2, baseTextY + 18, textWidth, item.unlocked() ? 0x6E5A42 : 0x887C6A);
    }

    private String formatProbability(double weight, double totalWeight, boolean approximate) {
        if (totalWeight <= 0 || weight <= 0) {
            return "???";
        }

        double percent = weight * 100.0D / totalWeight;
        String chance = String.format(java.util.Locale.ROOT, "%.1f%%", percent);
        if (approximate) {
            chance = "≈ " + chance;
        }
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.probability", chance).getString();
    }

    private void renderPageFooter(GuiGraphics guiGraphics, int left, int right, int bottom, int page, int totalPages, boolean catalog) {
        Component pageText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.page", page, totalPages);
        int width = this.font.width(pageText);
        guiGraphics.drawString(this.font, pageText, right - width - 8, bottom - 16, 0x6E5A42, false);
    }

    private boolean handleCatalogClick(double mouseX, double mouseY) {
        Layout layout = this.layout();
        if (!this.isWithinCatalog(mouseX, mouseY, layout)) {
            return false;
        }

        List<TableView> visibleTables = this.visibleCatalogTables();
        int listY = layout.top() + 34;
        for (int i = 0; i < visibleTables.size(); i++) {
            int rowY = listY + i * CATALOG_ROW_HEIGHT;
            if (mouseX >= layout.leftX() + 4 && mouseX <= layout.leftRight() - 4 && mouseY >= rowY - 2 && mouseY <= rowY + CATALOG_ROW_HEIGHT - 3) {
                this.setSelectedIndex(this.catalogPage * CATALOG_ROWS_PER_PAGE + i);
                return true;
            }
        }

        return false;
    }

    private void setSelectedIndex(int index) {
        if (this.tableViews.isEmpty()) {
            this.selectedIndex = -1;
            return;
        }

        this.selectedIndex = Mth.clamp(index, 0, this.tableViews.size() - 1);
        this.ensureSelectedTableVisible();
        this.itemPage = 0;
        this.syncButtonState();
    }

    private void ensureSelectedTableVisible() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.tableViews.size()) {
            return;
        }

        int targetPage = this.selectedIndex / CATALOG_ROWS_PER_PAGE;
        this.catalogPage = Mth.clamp(targetPage, 0, Math.max(0, this.catalogPageCount() - 1));
    }

    private void changeCatalogPage(int delta) {
        this.catalogPage = Mth.clamp(this.catalogPage + delta, 0, Math.max(0, this.catalogPageCount() - 1));
        this.syncButtonState();
    }

    private void changeItemPage(int delta) {
        this.itemPage = Mth.clamp(this.itemPage + delta, 0, Math.max(0, this.itemPageCount() - 1));
        this.syncButtonState();
    }

    private List<TableView> visibleCatalogTables() {
        if (this.tableViews.isEmpty()) {
            return List.of();
        }

        int from = this.catalogPage * CATALOG_ROWS_PER_PAGE;
        if (from >= this.tableViews.size()) {
            return List.of();
        }
        int to = Math.min(this.tableViews.size(), from + CATALOG_ROWS_PER_PAGE);
        return this.tableViews.subList(from, to);
    }

    private List<ItemView> visibleItems(TableView tableView) {
        int pageSize = ITEM_COLUMNS * ITEM_ROWS_PER_PAGE;
        if (tableView.items().isEmpty()) {
            return List.of();
        }

        int from = this.itemPage * pageSize;
        if (from >= tableView.items().size()) {
            return List.of();
        }
        int to = Math.min(tableView.items().size(), from + pageSize);
        return tableView.items().subList(from, to);
    }

    private TableView selectedTable() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.tableViews.size()) {
            return null;
        }
        return this.tableViews.get(this.selectedIndex);
    }

    private int catalogPageCount() {
        return this.tableViews.isEmpty() ? 1 : (this.tableViews.size() + CATALOG_ROWS_PER_PAGE - 1) / CATALOG_ROWS_PER_PAGE;
    }

    private int itemPageCount() {
        TableView selected = this.selectedTable();
        if (selected == null || selected.items().isEmpty()) {
            return 1;
        }
        int pageSize = ITEM_COLUMNS * ITEM_ROWS_PER_PAGE;
        return (selected.items().size() + pageSize - 1) / pageSize;
    }

    private void clampPages() {
        this.catalogPage = Mth.clamp(this.catalogPage, 0, Math.max(0, this.catalogPageCount() - 1));
        this.itemPage = Mth.clamp(this.itemPage, 0, Math.max(0, this.itemPageCount() - 1));
        if (this.selectedIndex >= this.tableViews.size()) {
            this.selectedIndex = this.tableViews.isEmpty() ? -1 : this.tableViews.size() - 1;
        }
        if (this.selectedIndex < 0 && !this.tableViews.isEmpty()) {
            this.selectedIndex = 0;
        }
    }

    private void syncButtonState() {
        int catalogPages = this.catalogPageCount();
        int itemPages = this.itemPageCount();
        if (this.catalogPrevButton != null) {
            this.catalogPrevButton.active = this.catalogPage > 0;
        }
        if (this.catalogNextButton != null) {
            this.catalogNextButton.active = this.catalogPage < catalogPages - 1;
        }
        if (this.itemPrevButton != null) {
            this.itemPrevButton.active = this.itemPage > 0;
        }
        if (this.itemNextButton != null) {
            this.itemNextButton.active = this.itemPage < itemPages - 1;
        }
    }

    private boolean isWithinCatalog(double mouseX, double mouseY, Layout layout) {
        return mouseX >= layout.leftX() && mouseX <= layout.leftRight() && mouseY >= layout.top() && mouseY <= layout.bottom();
    }

    private boolean isWithinEntries(double mouseX, double mouseY, Layout layout) {
        return mouseX >= layout.rightX() && mouseX <= layout.rightRight() && mouseY >= layout.top() && mouseY <= layout.bottom();
    }

    private String truncate(String text, int width) {
        if (width <= 0) {
            return text;
        }
        int ellipsisWidth = this.font.width("…");
        if (this.font.width(text) <= width) {
            return text;
        }
        if (width <= ellipsisWidth) {
            return "";
        }
        return this.font.plainSubstrByWidth(text, width - ellipsisWidth) + "…";
    }

    private void drawCenteredTruncatedString(GuiGraphics guiGraphics, String text, int x, int y, int width, int color) {
        if (width <= 0) {
            return;
        }

        String clipped = this.truncate(text, width);
        int textWidth = this.font.width(clipped);
        int textX = x + Math.max(0, (width - textWidth) / 2);
        guiGraphics.drawString(this.font, clipped, textX, y, color, false);
    }

    private Layout layout() {
        int availableWidth = Math.max(0, this.width - MARGIN * 2 - PANEL_GAP);
        int leftWidth = Math.min(200, Math.max(120, availableWidth / 3));
        if (availableWidth - leftWidth < 240) {
            leftWidth = Math.max(100, availableWidth - 240);
        }
        leftWidth = Math.max(100, Math.min(leftWidth, Math.max(100, availableWidth - 140)));
        int rightWidth = Math.max(0, availableWidth - leftWidth);
        int leftX = MARGIN;
        int rightX = leftX + leftWidth + PANEL_GAP;
        int top = PANEL_TOP;
        int bottom = Math.min(this.height - 1, Math.max(top + 180, this.height - PANEL_BOTTOM_PADDING));
        int rightCellSize = this.computeItemCellSize(rightWidth, bottom - top);
        return new Layout(leftX, rightX, leftWidth, rightWidth, top, bottom, rightCellSize);
    }

    private int computeItemCellSize(int panelWidth, int panelHeight) {
        int availableWidth = Math.max(0, panelWidth - 16);
        int availableHeight = Math.max(0, panelHeight - 60);
        if (availableWidth <= 0 || availableHeight <= 0) {
            return 0;
        }

        int widthCell = availableWidth / ITEM_COLUMNS;
        int heightCell = availableHeight / ITEM_ROWS_PER_PAGE;
        return Math.min(Math.min(widthCell, heightCell), ITEM_CELL_MAX);
    }

    private record Layout(int leftX, int rightX, int leftWidth, int rightWidth, int top, int bottom, int itemCellSize) {
        private int leftRight() {
            return this.leftX + this.leftWidth;
        }

        private int rightRight() {
            return this.rightX + this.rightWidth;
        }
    }

    private record TableView(ResourceLocation id, Component displayName, List<ItemView> items, double totalWeight, int totalCount, int parsedCount, boolean approximate) {
    }

    private record ItemView(ResourceLocation id, Component displayName, double weight, boolean unlocked, int count) {
        private net.minecraft.world.item.ItemStack stack() {
            return new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(this.id));
        }
    }
}
