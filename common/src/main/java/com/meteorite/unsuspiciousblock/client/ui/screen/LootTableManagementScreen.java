package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 战利品表追踪管理页面——以可展开路径树浏览服务端权威索引并提交管理员操作。
 */
public final class LootTableManagementScreen extends Screen {
    private static final int SCREEN_MARGIN = 6;
    private static final int MAX_PANEL_WIDTH = 520;
    private static final int MAX_PANEL_HEIGHT = 290;
    private static final int INNER_MARGIN = 10;
    private static final int COLUMN_GAP = 10;
    private static final int MIN_DETAILS_WIDTH = 120;
    private static final int MAX_DETAILS_WIDTH = 180;
    private static final int ROW_HEIGHT = 20;
    private static final int INDENT_WIDTH = 10;
    private static final int SCROLLBAR_WIDTH = 5;

    private final Screen parent;
    private final List<SyncLootTableManagementPayload.Entry> filteredEntries = new ArrayList<>();
    private final List<TreeRow> visibleRows = new ArrayList<>();
    private final Set<String> expandedPaths = new HashSet<>();
    private EditBox searchBox;
    private EditBox languageBox;
    private EditBox nameBox;
    private Button filterButton;
    private Button applyButton;
    private Button saveNameButton;
    private Filter filter = Filter.ALL;
    private int scrollRow;
    private boolean draggingScrollbar;
    private long observedRevision = -1L;
    @Nullable
    private ResourceLocation selectedTableId;
    @Nullable
    private ResourceLocation hoveredTableId;

    public LootTableManagementScreen(Screen parent) {
        super(Component.translatable("screen.unsuspiciousblock.loot_table_management.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = panelLeft();
        int top = panelTop();
        int listWidth = listWidth();
        int detailsWidth = detailsWidth();
        int detailsX = left + INNER_MARGIN + listWidth + COLUMN_GAP;
        int filterWidth = Math.min(76, Math.max(58, listWidth / 3));
        int searchWidth = listWidth - filterWidth - 5;
        int backY = top + panelHeight() - 30;
        int actionY = backY - 30;
        int actionWidth = (detailsWidth - 5) / 2;

        this.searchBox = new EditBox(this.font, left + INNER_MARGIN, top + 28, searchWidth, 20,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.search"));
        this.searchBox.setHint(Component.translatable(
                "screen.unsuspiciousblock.loot_table_management.search").withStyle(ChatFormatting.DARK_GRAY));
        this.searchBox.setResponder(ignored -> rebuildTree());
        this.addRenderableWidget(this.searchBox);

        this.filterButton = this.addRenderableWidget(Button.builder(this.filter.label(), button -> {
            this.filter = this.filter.next();
            button.setMessage(this.filter.label());
            rebuildTree();
        }).bounds(left + INNER_MARGIN + searchWidth + 5, top + 28, filterWidth, 20).build());

        this.languageBox = new EditBox(this.font, detailsX, actionY - 72, detailsWidth, 20,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.language"));
        this.languageBox.setMaxLength(16);
        this.languageBox.setValue(Minecraft.getInstance().getLanguageManager().getSelected());
        this.languageBox.setResponder(ignored -> {
            populateName();
            syncControls();
        });
        this.addRenderableWidget(this.languageBox);

        this.nameBox = new EditBox(this.font, detailsX, actionY - 33, detailsWidth, 20,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.localized_name"));
        this.nameBox.setMaxLength(128);
        this.addRenderableWidget(this.nameBox);

        this.applyButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> applySelection())
                .bounds(detailsX, actionY, actionWidth, 20).build());
        this.saveNameButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.unsuspiciousblock.loot_table_management.save_name"),
                        button -> saveName())
                .bounds(detailsX + actionWidth + 5, actionY, detailsWidth - actionWidth - 5, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(detailsX, backY, detailsWidth, 20).build());

        Services.NETWORK.sendToServer(new RequestLootTableManagementPayload());
        refreshSnapshot();
        syncControls();
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refreshSnapshot();
        int left = panelLeft();
        int top = panelTop();
        int listWidth = listWidth();
        this.hoveredTableId = null;

        // Screen.render 会调用一次 renderBackground 并绘制全部 Widget；其余文字随后绘制以保持清晰
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, top + 9, 0xFFFFFF);

        int capacity = visibleRowCapacity();
        int end = Math.min(this.visibleRows.size(), this.scrollRow + capacity);
        for (int index = this.scrollRow; index < end; index++) {
            renderTreeRow(graphics, this.visibleRows.get(index),
                    left + INNER_MARGIN, listTop() + (index - this.scrollRow) * ROW_HEIGHT,
                    listWidth, mouseX, mouseY);
        }
        renderScrollbar(graphics, left + INNER_MARGIN + listWidth - SCROLLBAR_WIDTH, listTop());
        renderDetails(graphics, left + INNER_MARGIN + listWidth + COLUMN_GAP, top, detailsWidth());
        if (this.hoveredTableId != null) {
            graphics.renderTooltip(this.font, Component.literal(this.hoveredTableId.toString()), mouseX, mouseY);
        }
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = panelLeft();
        int top = panelTop();
        graphics.fill(left, top, left + panelWidth(), top + panelHeight(), 0xE8181818);
        graphics.fill(left, top, left + panelWidth(), top + 1, 0xFF909090);
    }

    private void renderTreeRow(GuiGraphics graphics, TreeRow row, int x, int y, int width,
                               int mouseX, int mouseY) {
        boolean selected = row.entry() != null && row.entry().tableId().equals(this.selectedTableId);
        boolean hovered = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + ROW_HEIGHT - 1;
        graphics.fill(x, y, x + width - SCROLLBAR_WIDTH - 2, y + ROW_HEIGHT - 1,
                selected ? 0xFF4C6278 : hovered ? 0xFF353535 : 0xFF272727);

        int contentX = x + 5 + row.depth() * INDENT_WIDTH;
        if (row.expandable()) {
            graphics.drawString(this.font, row.expanded() ? "v" : ">",
                    contentX, y + 6, 0xC8C8C8, false);
            contentX += 10;
        } else {
            contentX += 4;
        }
        if (row.entry() != null) {
            graphics.fill(contentX, y + 5, contentX + 3, y + ROW_HEIGHT - 5,
                    row.entry().tracked() ? 0xFF78B66A : 0xFF777777);
            contentX += 7;
        }
        int textColor = row.depth() == 0 ? 0xFFE3B96B : row.entry() != null ? 0xFFFFFF : 0xD0D0D0;
        int availableWidth = Math.max(8, x + width - SCROLLBAR_WIDTH - 7 - contentX);
        graphics.drawString(this.font, trimToWidth(row.label(), availableWidth),
                contentX, y + 6, textColor, false);
        if (hovered && row.entry() != null) this.hoveredTableId = row.entry().tableId();
    }

    private void renderScrollbar(GuiGraphics graphics, int x, int y) {
        int height = listHeight();
        graphics.fill(x, y, x + SCROLLBAR_WIDTH, y + height, 0xFF202020);
        int maxScroll = maxScrollRow();
        if (maxScroll <= 0) return;
        int thumbHeight = scrollbarThumbHeight();
        int thumbY = y + (height - thumbHeight) * this.scrollRow / maxScroll;
        graphics.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight,
                this.draggingScrollbar ? 0xFFE0E0E0 : 0xFF909090);
    }

    private void renderDetails(GuiGraphics graphics, int x, int top, int width) {
        SyncLootTableManagementPayload.Entry selected = selectedEntry();
        graphics.drawString(this.font,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.details"),
                x, top + 32, 0xFFFFFF, false);
        if (selected != null) {
            graphics.drawString(this.font, trimToWidth(selected.tableId().toString(), width),
                    x, top + 51, 0xB8B8B8, false);
            Component status = Component.translatable(selected.tracked()
                    ? "screen.unsuspiciousblock.loot_table_management.tracked"
                    : "screen.unsuspiciousblock.loot_table_management.not_tracked");
            graphics.drawString(this.font, status, x, top + 67,
                    selected.tracked() ? 0x78D66A : 0x999999, false);
        }
        graphics.drawString(this.font,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.language"),
                x, this.languageBox.getY() - 11, 0xB8B8B8, false);
        graphics.drawString(this.font,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.localized_name"),
                x, this.nameBox.getY() - 11, 0xB8B8B8, false);
        if (!LootTableManagementClientState.canEdit()) {
            graphics.drawString(this.font,
                    trimToWidth(Component.translatable(
                            "screen.unsuspiciousblock.loot_table_management.read_only").getString(), width),
                    x, this.applyButton.getY() + 24, 0xFFAA00, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !isOverTextBox(mouseX, mouseY)) this.setFocused(null);
        if (button == 0 && isOverScrollbar(mouseX, mouseY) && maxScrollRow() > 0) {
            this.draggingScrollbar = true;
            setScrollFromMouse(mouseY);
            return true;
        }
        if (button == 0 && isOverList(mouseX, mouseY)) {
            int index = this.scrollRow + (int) ((mouseY - listTop()) / ROW_HEIGHT);
            if (index >= 0 && index < this.visibleRows.size()) {
                TreeRow row = this.visibleRows.get(index);
                int toggleRight = panelLeft() + INNER_MARGIN + 5 + row.depth() * INDENT_WIDTH + 14;
                if (row.expandable() && (row.entry() == null || mouseX < toggleRight)) {
                    toggleExpanded(row);
                } else if (row.entry() != null) {
                    this.selectedTableId = row.entry().tableId();
                    populateName();
                    syncControls();
                }
                return true;
            }
        }

        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        // 原版在按钮回调结束后设置焦点；筛选和追踪切换是即时动作，不保留键盘焦点
        if (this.getFocused() == this.filterButton || this.getFocused() == this.applyButton) {
            this.setFocused(null);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar && button == 0) {
            setScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalDelta, double verticalDelta) {
        if (isOverList(mouseX, mouseY) && verticalDelta != 0.0) {
            int direction = verticalDelta > 0.0 ? -1 : 1;
            this.scrollRow = clampScroll(this.scrollRow + direction * 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalDelta, verticalDelta);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    private void refreshSnapshot() {
        long revision = LootTableManagementClientState.revision();
        if (revision != this.observedRevision) {
            this.observedRevision = revision;
            rebuildTree();
        }
    }

    private void rebuildTree() {
        if (this.searchBox == null) return;
        String query = this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        boolean expandSearchResults = !query.isEmpty();
        this.filteredEntries.clear();
        TreeMap<String, TreeNode> namespaces = new TreeMap<>();
        for (SyncLootTableManagementPayload.Entry entry : LootTableManagementClientState.entries()) {
            if (!this.filter.matches(entry.tracked()) || !matchesQuery(entry, query)) continue;
            this.filteredEntries.add(entry);
            TreeNode node = namespaces.computeIfAbsent(entry.tableId().getNamespace(), namespace ->
                    new TreeNode(namespace, "namespace:" + namespace));
            StringBuilder pathKey = new StringBuilder(entry.tableId().getNamespace()).append(':');
            for (String segment : entry.tableId().getPath().split("/")) {
                if (pathKey.charAt(pathKey.length() - 1) != ':') pathKey.append('/');
                pathKey.append(segment);
                String key = pathKey.toString();
                node = node.children.computeIfAbsent(segment, ignored -> new TreeNode(segment, key));
            }
            node.entry = entry;
        }

        this.visibleRows.clear();
        for (TreeNode namespace : namespaces.values()) flattenNode(namespace, 0, expandSearchResults);
        this.scrollRow = clampScroll(this.scrollRow);
        if (selectedEntry() == null) this.selectedTableId = null;
        populateName();
        syncControls();
    }

    private void flattenNode(TreeNode node, int depth, boolean forceExpanded) {
        boolean expandable = !node.children.isEmpty();
        boolean expanded = expandable && (forceExpanded || this.expandedPaths.contains(node.key));
        this.visibleRows.add(new TreeRow(node.label, node.key, depth, expandable, expanded, node.entry));
        if (expanded) {
            for (TreeNode child : node.children.values()) flattenNode(child, depth + 1, forceExpanded);
        }
    }

    private boolean matchesQuery(SyncLootTableManagementPayload.Entry entry, String query) {
        if (query.isEmpty()) return true;
        String id = entry.tableId().toString().toLowerCase(Locale.ROOT);
        String fallback = readableName(entry.tableId()).toLowerCase(Locale.ROOT);
        String localized = Component.translatableWithFallback(
                LootTableNames.createTranslationKey(entry.tableId()), fallback).getString().toLowerCase(Locale.ROOT);
        return id.contains(query) || fallback.contains(query) || localized.contains(query);
    }

    private void toggleExpanded(TreeRow row) {
        if (row.expanded()) this.expandedPaths.remove(row.key());
        else this.expandedPaths.add(row.key());
        rebuildTree();
    }

    private void applySelection() {
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        if (entry == null || !LootTableManagementClientState.canEdit()) return;
        Services.NETWORK.sendToServer(new UpdateTrackedLootTablePayload(entry.tableId(), !entry.tracked(),
                this.languageBox.getValue().trim().toLowerCase(Locale.ROOT), this.nameBox.getValue().trim()));
    }

    private void saveName() {
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        if (entry == null || !LootTableManagementClientState.canEdit()) return;
        Services.NETWORK.sendToServer(new UpdateTrackedLootTablePayload(entry.tableId(), entry.tracked(),
                this.languageBox.getValue().trim().toLowerCase(Locale.ROOT), this.nameBox.getValue().trim()));
    }

    private void populateName() {
        if (this.nameBox == null || this.languageBox == null) return;
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        if (entry == null) {
            this.nameBox.setValue("");
            return;
        }
        String key = LootTableNames.createTranslationKey(entry.tableId());
        Map<String, String> language = LootTableManagementClientState.translations().getOrDefault(
                this.languageBox.getValue().trim().toLowerCase(Locale.ROOT), Map.of());
        this.nameBox.setValue(language.getOrDefault(key, ""));
    }

    private void syncControls() {
        if (this.applyButton == null || this.saveNameButton == null
                || this.languageBox == null || this.nameBox == null) return;
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        boolean hasSelectionPermission = entry != null && LootTableManagementClientState.canEdit();
        boolean editable = hasSelectionPermission
                && this.languageBox.getValue().trim().toLowerCase(Locale.ROOT)
                .matches("[a-z0-9_-]{2,16}");
        this.applyButton.active = editable;
        this.saveNameButton.active = editable;
        this.languageBox.active = hasSelectionPermission;
        this.nameBox.active = hasSelectionPermission;
        this.applyButton.setMessage(Component.translatable(entry != null && entry.tracked()
                ? "screen.unsuspiciousblock.loot_table_management.remove"
                : "screen.unsuspiciousblock.loot_table_management.add"));
    }

    @Nullable
    private SyncLootTableManagementPayload.Entry selectedEntry() {
        if (this.selectedTableId == null) return null;
        for (SyncLootTableManagementPayload.Entry entry : this.filteredEntries) {
            if (entry.tableId().equals(this.selectedTableId)) return entry;
        }
        return null;
    }

    private boolean isOverTextBox(double mouseX, double mouseY) {
        return this.searchBox != null && this.searchBox.isMouseOver(mouseX, mouseY)
                || this.languageBox != null && this.languageBox.isMouseOver(mouseX, mouseY)
                || this.nameBox != null && this.nameBox.isMouseOver(mouseX, mouseY);
    }

    private boolean isOverList(double mouseX, double mouseY) {
        return mouseX >= panelLeft() + INNER_MARGIN && mouseX < panelLeft() + INNER_MARGIN + listWidth()
                && mouseY >= listTop() && mouseY < listTop() + listHeight();
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        int x = panelLeft() + INNER_MARGIN + listWidth() - SCROLLBAR_WIDTH;
        return mouseX >= x && mouseX < x + SCROLLBAR_WIDTH
                && mouseY >= listTop() && mouseY < listTop() + listHeight();
    }

    private void setScrollFromMouse(double mouseY) {
        int maxScroll = maxScrollRow();
        if (maxScroll <= 0) {
            this.scrollRow = 0;
            return;
        }
        int travel = listHeight() - scrollbarThumbHeight();
        double relative = mouseY - listTop() - scrollbarThumbHeight() / 2.0;
        this.scrollRow = clampScroll((int) Math.round(relative * maxScroll / Math.max(1, travel)));
    }

    private int scrollbarThumbHeight() {
        if (this.visibleRows.isEmpty()) return listHeight();
        return Math.max(12, listHeight() * visibleRowCapacity() / this.visibleRows.size());
    }

    private int maxScrollRow() {
        return Math.max(0, this.visibleRows.size() - visibleRowCapacity());
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(maxScrollRow(), value));
    }

    private int panelLeft() {
        return (this.width - panelWidth()) / 2;
    }

    private int panelTop() {
        return (this.height - panelHeight()) / 2;
    }

    private int panelWidth() {
        return Math.max(1, Math.min(MAX_PANEL_WIDTH, this.width - SCREEN_MARGIN * 2));
    }

    private int panelHeight() {
        return Math.max(1, Math.min(MAX_PANEL_HEIGHT, this.height - SCREEN_MARGIN * 2));
    }

    private int detailsWidth() {
        int availableWidth = panelWidth() - INNER_MARGIN * 2 - COLUMN_GAP;
        return Math.min(MAX_DETAILS_WIDTH, Math.max(MIN_DETAILS_WIDTH, availableWidth * 38 / 100));
    }

    private int listWidth() {
        return panelWidth() - INNER_MARGIN * 2 - COLUMN_GAP - detailsWidth();
    }

    private int listTop() {
        return panelTop() + 55;
    }

    private int listHeight() {
        return Math.max(ROW_HEIGHT, panelTop() + panelHeight() - INNER_MARGIN - listTop());
    }

    private int visibleRowCapacity() {
        return Math.max(1, listHeight() / ROW_HEIGHT);
    }

    private static String readableName(ResourceLocation id) {
        String path = id.getPath();
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        return path.replace('_', ' ').replace('-', ' ');
    }

    private String trimToWidth(String value, int maxWidth) {
        if (this.font.width(value) <= maxWidth) return value;
        String suffix = "...";
        if (maxWidth <= this.font.width(suffix)) {
            return this.font.plainSubstrByWidth(value, Math.max(0, maxWidth));
        }
        return this.font.plainSubstrByWidth(value, maxWidth - this.font.width(suffix)) + suffix;
    }

    private static final class TreeNode {
        private final String label;
        private final String key;
        private final TreeMap<String, TreeNode> children = new TreeMap<>();
        @Nullable
        private SyncLootTableManagementPayload.Entry entry;

        private TreeNode(String label, String key) {
            this.label = label;
            this.key = key;
        }
    }

    private record TreeRow(String label, String key, int depth, boolean expandable,
                           boolean expanded, @Nullable SyncLootTableManagementPayload.Entry entry) {
    }

    private enum Filter {
        ALL("screen.unsuspiciousblock.loot_table_management.filter.all"),
        TRACKED("screen.unsuspiciousblock.loot_table_management.filter.tracked"),
        UNTRACKED("screen.unsuspiciousblock.loot_table_management.filter.untracked");

        private final String key;

        Filter(String key) {
            this.key = key;
        }

        private Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        private Component label() {
            return Component.translatable(this.key);
        }

        private boolean matches(boolean tracked) {
            return this == ALL || (this == TRACKED) == tracked;
        }
    }
}
