package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;

import com.meteorite.meteosuikit.api.layout.UiFormLayout;
import com.meteorite.meteosuikit.api.layout.UiLinearLayout;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import net.minecraft.client.gui.Font;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiControlGroup;
import com.meteorite.meteosuikit.api.control.UiFormBody;
import com.meteorite.meteosuikit.api.control.UiListView;
import com.meteorite.meteosuikit.api.control.UiModal;
import com.meteorite.meteosuikit.api.control.UiModalStack;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.meteosuikit.api.control.UiThemedScreen;
import com.meteorite.meteosuikit.api.control.UiWidgetRow;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputScope;
import com.meteorite.meteosuikit.api.input.UiInputTarget;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.control.UiWidget;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules.TableEdit;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.Application;
import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager.EditResult;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 战利品追踪管理；业务树和草稿归宿主，列表、输入与确认统一使用 kit。
 */
public final class LootTableManagementScreen extends UiThemedScreen {
    private static final int SCREEN_MARGIN = 6;
    private static final int MAX_PANEL_WIDTH = 520;
    private static final int MAX_PANEL_HEIGHT = 310;
    private static final int INNER_MARGIN = 10;
    private static final int COLUMN_GAP = 10;
    private static final int ROW_HEIGHT = 20;
    private static final int INDENT_WIDTH = 10;
    private static final String PREFIX = "screen.unsuspiciousblock.loot_table_management.";
    private final Screen parent;
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private final UiControlGroup controls = new UiControlGroup();
    private final UiControlGroup detailControls = new UiControlGroup();
    private final UiFormBody details;
    private final DetailSummary summary = new DetailSummary();
    private boolean detailsAssembled;
    private final UiModalStack modals = new UiModalStack(theme());
    private final UiListView<TreeRow> list;
    private final UiTextInput searchBox;
    private final LootTableNameEditor names;
    private final UiControl filterButton = new UiControl();
    private final UiWidgetRow searchRow = new UiWidgetRow(4, UiWidgetRow.Alignment.START);
    private UiControl backButton;
    private UiControl applyButton;
    private UiControl clearOverride;
    private UiControl editRules;
    private UiControl editNames;
    private UiControl toggleFolder;
    private UiControl importButton;
    private final List<SyncLootTableManagementPayload.Entry> filteredEntries = new ArrayList<>();
    private final List<TreeRow> visibleRows = new ArrayList<>();
    private final Set<String> expandedPaths = new HashSet<>();
    private Filter filter = Filter.ALL;
    private boolean requested;
    private boolean rebuildingRows;
    private boolean namesOpen;
    private boolean flatResults;
    private long observedRevision = -1L;
    private @Nullable ResourceLocation selectedTableId;
    private int pendingTrackingRequest;
    private @Nullable TrackingDraft trackingDraft;
    private @Nullable Component trackingStatus;

    public LootTableManagementScreen(Screen parent) {
        super(Component.translatable(PREFIX + "title"), new UiThemeContext<>(UnsuspiciousUiTheme.PARCHMENT));
        this.parent = parent;
        var font = Minecraft.getInstance().font;
        searchBox = new UiTextInput(theme(), font, Component.translatable(PREFIX + "search"));
        searchBox.setOnValueChanged(this::rebuildTree);
        names = new LootTableNameEditor(theme(), modals,
                () -> selectedEntry() == null ? null : selectedEntry().tableId(), () -> height);
        list = new UiListView<>(theme(), (graphics, rowFont, row, index, rect, selected, hovered, focused) -> {
            int x = rect.x() + row.depth() * INDENT_WIDTH;
            if (row.expandable()) {
                UiAtlas.icon(row.expanded() ? UiAtlas.Icon.TREE_EXPANDED : UiAtlas.Icon.TREE_COLLAPSED)
                        .render(graphics, x, rect.y() + 5);
                x += 11;
            }
            if (row.entry() != null) {
                UiAtlas.icon(row.entry().isCollected() ? UiAtlas.Icon.CHECK : UiAtlas.Icon.CLOSE)
                        .render(graphics, x, rect.y() + 5);
                x += 11;
            }
            int available = Math.max(0, rect.right() - x);
            int labelY = row.entry() == null ? rect.y() + 6 : rect.y() + 1;
            graphics.drawString(rowFont, TextScroll.trimToWidth(rowFont, row.label(), available),
                    x, labelY, UiTextScheme.PARCHMENT.body(), false);
            if (row.entry() != null) {
                graphics.drawString(rowFont, TextScroll.trimToWidth(rowFont, row.entry().tableId().toString(), available),
                        x, labelY + rowFont.lineHeight, UiTextScheme.PARCHMENT.hint(), false);
            }
        });
        list.setRowHeight(ROW_HEIGHT);
        list.setLayoutKey("management/tables");
        list.setItemKey(TreeRow::key);
        list.setOnSelectionChanged(index -> {
            if (rebuildingRows) return;
            TreeRow row = list.selectedItem();
            if (row != null && row.entry() != null) selectedTableId = row.entry().tableId();
            names.populateName();
            syncControls();
        });
        list.setOnActivate(row -> {
            if (row.expandable()) toggleExpanded(row);
        });
        details = new UiFormBody(theme(), TextMeasurer.of(font));
        filterButton.setTheme(theme(), UiControlRole.SECONDARY);
        filterButton.setFocusable(true);
        searchRow.add(searchBox, UiLinearLayout.Child.remain());
        searchRow.add(filterButton, UiLinearLayout.Child.fixed(72));
        content.addAll(List.of(searchRow, list, details, controls));
        scope.setEscape(this::onClose);
        scope.focus().setListener((previous, current) -> details.reveal(current));
        modals.setOnScopeChanged(this::refreshUiState);
    }

    @Override
    protected void init() {
        controls.setTheme(theme());
        detailControls.setTheme(theme());
        controls.beginUpdate();
        detailControls.beginUpdate();
        configureControls();
        controls.endUpdate();
        detailControls.endUpdate();
        if (!detailsAssembled) {
            detailsAssembled = true;
            details.add(Component.empty(), summary, summary, List.of());
            details.add(Component.empty(), editRules, 20);
            details.add(Component.empty(), applyButton, 20);
            details.add(Component.empty(), clearOverride, 20);
            details.add(Component.empty(), editNames, 20);
            names.install(details);
        }
        if (!requested && Minecraft.getInstance().level != null) {
            requested = true;
            LootTableManagementClientState.requestSnapshot();
        }
        names.configure();
        names.reopenDraft();
        refreshSnapshot();
        syncControls();
    }

    private void configureControls() {
        filterButton.configure(font, filter.label(), null, List.of(), this::chooseFilter);
        editRules = detailControls.obtain("rules");
        editRules.configure(font, Component.translatable(PREFIX + "world_rules"), null, List.of(),
                () -> Minecraft.getInstance().setScreen(new LootTrackingConfigScreen(this)));
        applyButton = detailControls.obtain("tracking");
        editNames = detailControls.obtain("names");
        toggleFolder = controls.obtain("folder");
        toggleFolder.setFocusable(true);
        clearOverride = detailControls.obtain("clear-override");
        clearOverride.configure(font, Component.translatable(PREFIX + "clear_override"), null, List.of(),
                () -> submitTracking(TableEdit.CLEAR));
        importButton = controls.obtain("import");
        importButton.configure(font, Component.translatable(PREFIX + "import_json"), null, List.of(), names::importJson);
        backButton = controls.obtain("back");
        backButton.configure(font, Component.translatable("gui.back"), null, List.of(), this::onClose);
        for (UiControl control : List.of(editRules, applyButton, clearOverride, importButton, backButton, editNames)) {
            control.setFocusable(true);
        }
    }

    private void chooseFilter() {
        UiListView<Filter> filters = new UiListView<>(theme(),
                (graphics, font, item, index, bounds, selected, hovered, focused) ->
                        graphics.drawString(font, item.label(), bounds.x(), bounds.y() + 5,
                                UiTextScheme.PARCHMENT.body(), false));
        filters.setItems(List.of(Filter.values())).setSelectedIndex(filter.ordinal());
        modals.push(UiModal.create(theme(), font).title(Component.translatable(PREFIX + "filter.title"))
                .contentWidget(filters, 92)
                .confirm(Component.translatable("gui.done"), () -> {
                    Filter selected = filters.selectedItem();
                    if (selected == null) return;
                    filter = selected;
                    filterButton.configure(font, filter.label(), null, List.of(), this::chooseFilter);
                    rebuildTree();
                }).cancel(Component.translatable("gui.cancel")));
    }

    @Override
    protected UiInputScope inputScope() {
        return modals.isEmpty() ? scope : modals.top().inputScope();
    }

    @Override
    protected void prepareUi() {
        refreshSnapshot();
        syncControls();
        modals.prepareUi();
    }

    @Override
    protected void advanceUiFrame() {
        modals.tick();
    }

    @Override
    protected void invalidateUiResources() {
        names.invalidateResources();
        rebuildTree();
    }

    @Override
    protected void layoutUi() {
        int left = panelLeft();
        int top = panelTop();
        int detailsX = left + INNER_MARGIN + listWidth() + COLUMN_GAP;
        details.setBounds(detailsX + 3, top + 30, Math.max(0, detailsWidth() - 6), Math.max(0, panelHeight() - 70));
        int backY = top + panelHeight() - 28;
        int searchWidth = listWidth();
        searchRow.add(filterButton, UiLinearLayout.Child.fixed(Math.min(72, Math.max(0, searchWidth - 4))));
        searchRow.setBounds(left + INNER_MARGIN, top + 29, searchWidth, 20);
        int actionWidth = Math.max(0, (detailsWidth() - 5) / 2);
        importButton.setBounds(detailsX, backY, actionWidth, 20);
        toggleFolder.setBounds(left + INNER_MARGIN, backY, listWidth(), 20);
        backButton.setBounds(detailsX + actionWidth + 5, backY, actionWidth, 20);
        syncListGeometry();
        modals.setBounds(0, 0, width, height);
    }

    @Override
    protected void renderThemedBackground(GuiGraphics graphics, int x, int y, float delta) { }

    private void syncControls() {
        if (applyButton == null) return;
        var entry = selectedEntry();
        boolean canEditTable = entry != null && canEditTracking();
        boolean isRootCandidate = entry != null && entry.match().isRootCandidate();
        applyButton.setEnabled(canEditTable);
        applyButton.configure(font, Component.translatable(PREFIX
                + (isRootCandidate ? "exclude_root" : "include_root")), null, List.of(),
                () -> submitTracking(isRootCandidate ? TableEdit.EXCLUDE : TableEdit.INCLUDE));
        applyButton.setTheme(theme(), isRootCandidate ? UiControlRole.SECONDARY : UiControlRole.PRIMARY);
        clearOverride.setEnabled(canEditTable && entry.hasExactOverride());
        editRules.setEnabled(pendingTrackingRequest == 0);
        editRules.setVisible(!namesOpen);
        applyButton.setVisible(!namesOpen);
        clearOverride.setVisible(!namesOpen);
        editNames.configure(font, Component.translatable(PREFIX + (namesOpen ? "show_tracking" : "edit_names")),
                null, List.of(), () -> {
                    namesOpen = !namesOpen;
                    syncControls();
                });
        names.refreshControls(namesOpen);
        importButton.setVisible(namesOpen);
        TreeRow folder = list.selectedItem();
        toggleFolder.setVisible(folder != null && folder.expandable());
        if (folder != null && folder.expandable()) {
            toggleFolder.configure(font, Component.translatable(PREFIX + (folder.expanded() ? "collapse" : "expand")),
                    null, List.of(), () -> toggleExpanded(folder));
        }
        importButton.setEnabled(names.canImport());
        summary.refresh();
    }

    @Override
    public void removed() {
        names.preserveDraft();
        if (requested) {
            LootTableManagementClientState.closeSnapshot();
            requested = false;
        }
        super.removed();
    }

    @Override
    public void onClose() {
        names.requestClose(() -> Minecraft.getInstance().setScreen(parent));
    }

    @Override
    protected void renderThemedUi(GuiGraphics graphics, int x, int y, float delta) {
        int left = panelLeft();
        int top = panelTop();
        int pWidth = panelWidth();
        int pHeight = panelHeight();

        // 羊皮纸档案台大底板
        ScenarioUi.renderPage(graphics, new UiRect(left, top, pWidth, pHeight));
        graphics.fill(left + 8, top + 23, left + pWidth - 8, top + 24, JournalPalette.SEPARATOR);
        graphics.fill(left + 8, top + pHeight - 34, left + pWidth - 8, top + pHeight - 33, JournalPalette.SEPARATOR);

        // 右侧表单区域轻量卡片衬底
        int detailsX = left + INNER_MARGIN + listWidth() + COLUMN_GAP;
        JournalPalette.drawStatPill(graphics, detailsX - 3, top + 27, detailsWidth() + 6, pHeight - 64, false);

        graphics.drawCenteredString(font, title, width / 2, top + 8, UiTextScheme.PARCHMENT.title());
        UiPointer pointer = scope.pointer(x, y).resolve(content);
        list.render(graphics, font, pointer);
        searchRow.render(graphics, font, pointer);
        details.render(graphics, font, pointer);
        controls.render(graphics, font, pointer);
        if (pointer.allowsTooltip() && pointer.hit() != null) {
            int index = pointer.hit().rowIndex(list);
            var entry = index < 0 ? null : visibleRows.get(index).entry();
            if (entry != null) graphics.renderTooltip(font, Component.literal(entry.tableId().toString()), x, y);
        }
        modals.render(graphics, font, UiPointer.at(x, y));
    }

    private void syncListGeometry() {
        list.setBounds(panelLeft() + INNER_MARGIN, panelTop() + 55,
                listWidth(), Math.max(0, panelHeight() - 88));
    }

    private int panelWidth() {
        return Math.clamp(width - SCREEN_MARGIN * 2, 0, MAX_PANEL_WIDTH);
    }

    private int panelHeight() {
        return Math.clamp(height - SCREEN_MARGIN * 2, 0, MAX_PANEL_HEIGHT);
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int panelTop() {
        return (height - panelHeight()) / 2;
    }

    private int detailsWidth() {
        return Math.max(0, (panelWidth() - INNER_MARGIN * 2 - COLUMN_GAP) * 42 / 100);
    }

    private int listWidth() {
        return Math.max(0, panelWidth() - INNER_MARGIN * 2 - COLUMN_GAP - detailsWidth());
    }

    record DetailLine(Component text, int color) {
    }

    private final class DetailSummary implements UiWidget, UiFormLayout.Control {
        private UiRect bounds = new UiRect(0, 0, 0, 0);
        private List<DetailLine> content = List.of();
        private List<DetailLine> wrapped = List.of();

        private void refresh() {
            List<DetailLine> next = new ArrayList<>();
            next.add(new DetailLine(Component.translatable(PREFIX + "details"), UiTextScheme.PARCHMENT.title()));
            var selected = selectedEntry();
            if (selected != null) {
                next.add(new DetailLine(Component.literal(displayName(selected.tableId())), UiTextScheme.PARCHMENT.title()));
                next.add(new DetailLine(Component.literal(selected.tableId().toString()), UiTextScheme.PARCHMENT.label()));
                String match = selected.match().name().toLowerCase(Locale.ROOT);
                next.add(new DetailLine(Component.translatable(PREFIX + "match." + match), UiTextScheme.PARCHMENT.label()));
                String catalog = selected.isCatalogRoot() ? "root" : selected.isCollected() ? "child" : "absent";
                next.add(new DetailLine(Component.translatable(PREFIX + "catalog." + catalog),
                        selected.isCollected() ? UiTextScheme.PARCHMENT.positive() : UiTextScheme.PARCHMENT.hint()));
                selected.parents().forEach(id -> next.add(new DetailLine(
                        Component.translatable(PREFIX + "parent", displayName(id)), UiTextScheme.PARCHMENT.label())));
                if (!selected.selectors().isEmpty()) {
                    next.add(new DetailLine(Component.literal(String.join(", ", selected.selectors())),
                            UiTextScheme.PARCHMENT.hint()));
                }
            }
            if (namesOpen) next.addAll(names.summary());
            var config = LootTableManagementClientState.configuration();
            if (config != null && config.application() != Application.APPLIED) {
                next.add(new DetailLine(LootTrackingConfigScreen.applicationMessage(config), UiTextScheme.PARCHMENT.accent()));
            }
            if (trackingStatus != null) next.add(new DetailLine(trackingStatus, UiTextScheme.PARCHMENT.accent()));
            if (next.equals(content)) return;
            content = List.copyOf(next);
            details.invalidateLayout();
        }

        @Override
        public int preferredWidth(int width) {
            return width;
        }

        @Override
        public int measureHeight(int width) {
            TextMeasurer text = TextMeasurer.of(Minecraft.getInstance().font);
            List<DetailLine> result = new ArrayList<>();
            for (DetailLine line : content)
                for (Component fragment : text.wrap(line.text(), width))
                    result.add(new DetailLine(fragment, line.color()));
            wrapped = List.copyOf(result);
            return wrapped.size() * (text.lineHeight() + 3);
        }

        @Override
        public UiRect bounds() {
            return bounds;
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            bounds = new UiRect(x, y, width, height);
        }

        @Override
        public void render(GuiGraphics graphics, Font font, UiPointer pointer) {
            int y = bounds.y();
            for (DetailLine line : wrapped) {
                graphics.drawString(font, line.text(), bounds.x(), y, line.color(), false);
                y += font.lineHeight + 3;
            }
        }
    }

    private void refreshSnapshot() {
        names.receiveResults();
        var receipt = LootTableManagementClientState.receipt(pendingTrackingRequest);
        if (receipt != null) {
            trackingStatus = LootTrackingConfigScreen.resultMessage(receipt.result());
            if (receipt.result() == EditResult.PREVIEWED && trackingDraft != null) {
                var preview = LootTableManagementClientState.preview(pendingTrackingRequest);
                if (preview != null) {
                    TrackingDraft draft = trackingDraft;
                    pendingTrackingRequest = 0;
                    trackingDraft = null;
                    LootTrackingPreviewDialog.open(modals, theme(), font, height, preview, () ->
                            pendingTrackingRequest = LootTableManagementClientState.submit(
                                    draft.revision(), draft.resourceRevision(), draft.edit()));
                }
            } else if (receipt.result() != EditResult.SAVED) {
                pendingTrackingRequest = 0;
                trackingDraft = null;
            }
        }
        long revision = LootTableManagementClientState.revision();
        if (revision != this.observedRevision) {
            this.observedRevision = revision;
            rebuildTree();
        }
    }

    private void rebuildTree() {
        TreeRow selectedRow = list.selectedItem();
        String selectedKey = selectedRow != null && selectedRow.entry() == null ? selectedRow.key() : null;
        String query = searchBox.value().trim().toLowerCase(Locale.ROOT);
        boolean flat = filter == Filter.RECENT || !query.isEmpty();
        if (!flat && flatResults && selectedTableId != null) expandPathTo(selectedTableId);
        flatResults = flat;
        filteredEntries.clear();
        visibleRows.clear();
        for (var entry : entriesForCurrentFilter()) {
            if (filter.matches(entry.isCollected()) && matchesQuery(entry, query)) filteredEntries.add(entry);
        }
        if (flat) {
            if (filter != Filter.RECENT) {
                filteredEntries.sort(Comparator.comparing(entry -> displayName(entry.tableId()),
                        String.CASE_INSENSITIVE_ORDER));
            }
            for (var entry : filteredEntries) {
                visibleRows.add(new TreeRow(displayName(entry.tableId()), entry.tableId().toString(),
                        0, false, false, entry));
            }
        } else {
            Map<String, TreeNode> namespaces = new LinkedHashMap<>();
            for (var entry : filteredEntries) {
                ResourceLocation id = entry.tableId();
                TreeNode node = namespaces.computeIfAbsent(id.getNamespace(), namespace ->
                        new TreeNode(namespace, "namespace:" + namespace));
                StringBuilder path = new StringBuilder(id.getNamespace()).append(':');
                String[] segments = id.getPath().split("/", -1);
                for (int index = 0; index < segments.length; index++) {
                    if (index > 0) path.append('/');
                    String segment = segments[index];
                    path.append(segment);
                    String key = path.toString();
                    node = node.children.computeIfAbsent(segment, ignored -> new TreeNode(segment, key));
                }
                node.entry = entry;
            }
            for (TreeNode namespace : namespaces.values()) flattenNode(namespace, 0);
        }
        String empty = !LootTableManagementClientState.indexReady() ? "loading"
                : !query.isEmpty() ? "empty_search" : filter == Filter.RECENT ? "empty_recent" : "empty";
        list.setEmptyMessage(Component.translatable(PREFIX + empty));
        syncListGeometry();
        rebuildingRows = true;
        list.setItems(visibleRows);
        int selectedIndex = -1;
        for (int index = 0; index < visibleRows.size(); index++) {
            TreeRow row = visibleRows.get(index);
            if (selectedKey != null ? selectedKey.equals(row.key())
                    : row.entry() != null && row.entry().tableId().equals(selectedTableId)) {
                selectedIndex = index;
                break;
            }
        }
        list.setSelectedIndex(selectedIndex);
        rebuildingRows = false;
        names.populateName();
        syncControls();
    }

    private void expandPathTo(ResourceLocation id) {
        expandedPaths.add("namespace:" + id.getNamespace());
        for (int slash = id.getPath().indexOf('/'); slash >= 0; slash = id.getPath().indexOf('/', slash + 1)) {
            expandedPaths.add(id.getNamespace() + ":" + id.getPath().substring(0, slash));
        }
    }

    private void flattenNode(TreeNode node, int depth) {
        boolean expandable = !node.children.isEmpty();
        boolean expanded = expandable && expandedPaths.contains(node.key);
        String label = node.entry == null ? node.label : displayName(node.entry.tableId());
        visibleRows.add(new TreeRow(label, node.key, depth, expandable, expanded, node.entry));
        if (expanded) {
            node.children.values().stream()
                    .sorted(Comparator.comparing((TreeNode child) -> child.children.isEmpty()))
                    .forEach(child -> flattenNode(child, depth + 1));
        }
    }

    private boolean matchesQuery(SyncLootTableManagementPayload.Entry entry, String query) {
        return query.isEmpty() || entry.tableId().toString().contains(query)
                || displayName(entry.tableId()).toLowerCase(Locale.ROOT).contains(query);
    }

    private static String displayName(ResourceLocation id) {
        return ClientLootTableLanguageStore.displayName(id).getString();
    }

    private void toggleExpanded(TreeRow row) {
        if (row.expanded()) this.expandedPaths.remove(row.key());
        else this.expandedPaths.add(row.key());
        rebuildTree();
    }

    private boolean canEditTracking() {
        var config = LootTableManagementClientState.configuration();
        return config != null && config.canEdit() && config.isWritable() && pendingTrackingRequest == 0;
    }

    private void submitTracking(TableEdit edit) {
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        if (entry == null || !canEditTracking()) return;
        var config = LootTableManagementClientState.configuration();
        var draft = new UpdateTrackedLootTablePayload.Single(entry.tableId(), edit);
        trackingDraft = new TrackingDraft(config.revision(), config.resourceRevision(), draft);
        pendingTrackingRequest = LootTableManagementClientState.requestPreview(
                config.revision(), config.resourceRevision(), draft);
        trackingStatus = Component.translatable(LootTrackingPreviewDialog.KEY + "previewing");
    }

    @Nullable
    private SyncLootTableManagementPayload.Entry selectedEntry() {
        TreeRow row = list.selectedItem();
        return row == null ? null : row.entry();
    }

    private record TrackingDraft(long revision, long resourceRevision, UpdateTrackedLootTablePayload.Single edit) {}

    private List<SyncLootTableManagementPayload.Entry> entriesForCurrentFilter() {
        if (this.filter != Filter.RECENT) {
            return LootTableManagementClientState.entries();
        }
        Map<ResourceLocation, SyncLootTableManagementPayload.Entry> entriesById = new LinkedHashMap<>();
        LootTableManagementClientState.entries().forEach(entry -> entriesById.put(entry.tableId(), entry));
        List<SyncLootTableManagementPayload.Entry> result = new ArrayList<>();
        for (SyncLootTableManagementPayload.RecentEntry recent
                : LootTableManagementClientState.recentEntries().stream()
                        .sorted(Comparator.comparingLong(SyncLootTableManagementPayload.RecentEntry::encounterOrder).reversed())
                        .toList()) {
            SyncLootTableManagementPayload.Entry entry = entriesById.get(recent.tableId());
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 按命名空间与路径构建的业务目录节点。
     */
    private static final class TreeNode {
        private final String label;
        private final String key;
        private final LinkedHashMap<String, TreeNode> children = new LinkedHashMap<>();
        @Nullable
        private SyncLootTableManagementPayload.Entry entry;

        private TreeNode(String label, String key) {
            this.label = label;
            this.key = key;
        }
    }

    /**
     * 管理目录的可见行投影。
     */
    private record TreeRow(String label, String key, int depth, boolean expandable,
                           boolean expanded, @Nullable SyncLootTableManagementPayload.Entry entry) {
    }

    /**
     * 管理页筛选语义。
     */
    private enum Filter {
        ALL("screen.unsuspiciousblock.loot_table_management.filter.all"),
        TRACKED("screen.unsuspiciousblock.loot_table_management.filter.tracked"),
        UNTRACKED("screen.unsuspiciousblock.loot_table_management.filter.untracked"),
        RECENT("screen.unsuspiciousblock.loot_table_management.filter.recent");

        private final String key;

        Filter(String key) {
            this.key = key;
        }

        private Component label() {
            return Component.translatable(this.key);
        }

        private boolean matches(boolean tracked) {
            return this == ALL || this == RECENT || (this == TRACKED) == tracked;
        }
    }
}
