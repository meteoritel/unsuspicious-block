package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;

import com.meteorite.meteosuikit.api.layout.UiFormLayout;
import com.meteorite.meteosuikit.api.layout.UiLinearLayout;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import net.minecraft.client.gui.Font;

import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingRules.TableEdit;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

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
    private static final int IMPORT_STATUS_OK_COLOR = UiTextScheme.PARCHMENT.positive();
    private static final int IMPORT_STATUS_ERROR_COLOR = UiTextScheme.PARCHMENT.negative();
    private static final String PREFIX = "screen.unsuspiciousblock.loot_table_management.";
    private final Screen parent;
    private final LootTableManagementActions actions = new LootTableManagementActions();
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
    private final UiTextInput languageBox;
    private final UiTextInput nameBox;
    private final UiControl filterButton = new UiControl();
    private final UiWidgetRow searchRow = new UiWidgetRow(4, UiWidgetRow.Alignment.START);
    private UiControl backButton;
    private UiControl applyButton;
    private UiControl clearOverride;
    private UiControl editRules;
    private UiControl saveNameButton;
    private UiControl languageButton;
    private UiControl importButton;
    private final List<SyncLootTableManagementPayload.Entry> filteredEntries = new ArrayList<>();
    private final List<TreeRow> visibleRows = new ArrayList<>();
    private final Set<String> expandedPaths = new HashSet<>();
    private final List<String> languageCodes = new ArrayList<>();
    private final Map<DraftKey, String> nameDrafts = new LinkedHashMap<>();
    private Filter filter = Filter.ALL;
    private boolean populatingName;
    private boolean initialized;
    private long observedRevision = -1L;
    private String selectedLanguageCode = "";
    private @Nullable ResourceLocation selectedTableId;
    private @Nullable Component importStatus;
    private int importStatusColor = IMPORT_STATUS_OK_COLOR;
    private int pendingTrackingRequest;
    private @Nullable Component trackingStatus;

    public LootTableManagementScreen(Screen parent) {
        super(Component.translatable(PREFIX + "title"), new UiThemeContext<>(UnsuspiciousUiTheme.PARCHMENT));
        this.parent = parent;
        var font = Minecraft.getInstance().font;
        initializeLanguageCodes();
        searchBox = new UiTextInput(theme(), font, Component.translatable(PREFIX + "search"));
        languageBox = new UiTextInput(theme(), font, Component.translatable(PREFIX + "language"))
                .setMaxLength(16).setValue(selectedLanguageCode);
        nameBox = new UiTextInput(theme(), font, Component.translatable(PREFIX + "localized_name")).setMaxLength(128);
        searchBox.setOnValueChanged(this::rebuildTree);
        languageBox.setOnValueChanged(() -> onLanguageInputChanged(languageBox.value()));
        nameBox.setOnValueChanged(() -> onNameInputChanged(nameBox.value()));
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
            graphics.drawString(rowFont, TextScroll.trimToWidth(rowFont, row.label(),
                    Math.max(0, rect.right() - x)), x, rect.y() + 6, UiTextScheme.PARCHMENT.body(), false);
        });
        list.setRowHeight(ROW_HEIGHT);
        list.setLayoutKey("management/tables");
        list.setItemKey(TreeRow::key);
        list.setActivateOnSingleClick(true);
        list.setOnActivate(row -> {
            if (row.entry() != null) {
                selectedTableId = row.entry().tableId();
                populateName();
            }
            if (row.expandable()) toggleExpanded(row);
            syncControls();
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
            details.add(Component.translatable(PREFIX + "language"), languageBox, 20);
            details.add(Component.empty(), languageButton, 20);
            details.add(Component.translatable(PREFIX + "localized_name"), nameBox, 20);
            details.add(Component.empty(), saveNameButton, 20);
        }
        if (!initialized) {
            initialized = true;
            LootTableManagementClientState.requestSnapshot();
        }
        refreshSnapshot();
        syncControls();
    }

    private void configureControls() {
        filterButton.configure(font, filter.label(), null, List.of(), this::cycleFilter);
        languageButton = detailControls.obtain("language");
        languageButton.configure(font, Component.translatable(PREFIX + "choose_language"), null, List.of(), this::chooseLanguage);
        editRules = detailControls.obtain("rules");
        editRules.configure(font, Component.translatable(PREFIX + "world_rules"), null, List.of(),
                () -> Minecraft.getInstance().setScreen(new LootTrackingConfigScreen(this)));
        applyButton = detailControls.obtain("tracking");
        clearOverride = detailControls.obtain("clear-override");
        clearOverride.configure(font, Component.translatable(PREFIX + "clear_override"), null, List.of(),
                () -> submitTracking(TableEdit.CLEAR));
        saveNameButton = detailControls.obtain("name");
        saveNameButton.configure(font, Component.translatable(PREFIX + "save_name"), null, List.of(), this::saveName);
        importButton = controls.obtain("import");
        importButton.configure(font, Component.translatable(PREFIX + "import_json"), null, List.of(), this::importJson);
        backButton = controls.obtain("back");
        backButton.configure(font, Component.translatable("gui.back"), null, List.of(), this::onClose);
    }

    private void cycleFilter() {
        filter = filter.next();
        filterButton.configure(font, filter.label(), null, List.of(), this::cycleFilter);
        rebuildTree();
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
        initializeLanguageCodes();
        reconcileDrafts();
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
        backButton.setBounds(detailsX + actionWidth + 5, backY, actionWidth, 20);
        syncListGeometry();
        modals.setBounds(0, 0, width, height);
    }

    @Override
    protected void renderThemedBackground(GuiGraphics graphics, int x, int y, float delta) { }

    private void syncControls() {
        if (applyButton == null) return;
        var entry = selectedEntry();
        boolean canEditName = entry != null && LootTableManagementClientState.canEdit();
        boolean canEditTable = entry != null && canEditTracking();
        boolean isRootCandidate = entry != null && entry.match().isRootCandidate();
        applyButton.setEnabled(canEditTable);
        applyButton.configure(font, Component.translatable(PREFIX
                + (isRootCandidate ? "exclude_root" : "include_root")), null, List.of(),
                () -> submitTracking(isRootCandidate ? TableEdit.EXCLUDE : TableEdit.INCLUDE));
        applyButton.setTheme(theme(), isRootCandidate ? UiControlRole.SECONDARY : UiControlRole.PRIMARY);
        clearOverride.setEnabled(canEditTable && entry.hasExactOverride());
        editRules.setEnabled(pendingTrackingRequest == 0);
        boolean canSave = LootTableManagementClientState.canEdit() && !nameDrafts.isEmpty() && !hasInvalidEnglishDraft();
        saveNameButton.setEnabled(canSave);
        if (canSave) {
            saveNameButton.setTheme(theme(), UiControlRole.PRIMARY);
        } else {
            saveNameButton.setTheme(theme(), UiControlRole.SECONDARY);
        }
        languageButton.setEnabled(canEditName);
        languageBox.setEnabled(canEditName);
        nameBox.setEnabled(canEditName && hasValidLanguageCode()
                && resourceName(new DraftKey(currentLanguageCode(), entry.tableId())) == null);
        importButton.setEnabled(LootTableManagementClientState.canEdit());
        summary.refresh();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
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

    private void chooseLanguage() {
        UiListView<String> languages = new UiListView<>(theme(), (graphics, font, code, index, row, selected, hovered, focused) ->
                graphics.drawString(font, TextScroll.trimToWidth(font, languageOptionLabel(code).getString(), row.width()),
                        row.x(), row.y() + 5, UiTextScheme.PARCHMENT.body(), false));
        languages.setLayoutKey("management/languages");
        languages.setItemKey(code -> "language/" + code);
        languages.setItems(languageCodes).setSelectedIndex(languageCodes.indexOf(validLanguageCodeOrFallback()));
        modals.push(UiModal.create(theme(), font).title(Component.translatable(PREFIX + "language_select"))
                .preferredWidth(340).contentWidget(languages, Math.clamp(height - 110, 20, 180))
                .confirm(Component.translatable("gui.done"), () -> {
                    String selected = languages.selectedItem();
                    if (selected != null) selectLanguage(selected);
                }).cancel(Component.translatable("gui.cancel")));
    }

    private void openImportPreview(LootTableManagementActions.ImportPreview preview) {
        modals.push(UiModal.create(theme(), font).title(Component.translatable(PREFIX + "import_preview.title"))
                .message(Component.translatable(PREFIX + "import_preview.message", preview.languageCode(), preview.added(),
                        preview.updated(), preview.resourceSkipped(), preview.unmatched(), preview.invalid()))
                .confirm(Component.translatable("gui.yes"), () -> {
                    if (!preview.entries().isEmpty() && LootTableManagementClientState.canEdit()) {
                        actions.submitImport(preview);
                        importStatus = Component.translatable(PREFIX + "import_submitted", preview.entries().size());
                        importStatusColor = IMPORT_STATUS_OK_COLOR;
                    }
                }).cancel(Component.translatable("gui.cancel")));
    }

    private void syncListGeometry() {
        list.setBounds(panelLeft() + INNER_MARGIN, panelTop() + 55,
                listWidth(), Math.max(0, panelHeight() - 65));
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

    private static String readableName(ResourceLocation id) {
        String path = id.getPath();
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1).replace('_', ' ').replace('-', ' ');
    }

    private record DetailLine(Component text, int color) {
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
                next.add(new DetailLine(Component.literal(selected.tableId().toString()), UiTextScheme.PARCHMENT.label()));
                String match = selected.match().name().toLowerCase(Locale.ROOT);
                next.add(new DetailLine(Component.translatable(PREFIX + "match." + match), UiTextScheme.PARCHMENT.label()));
                String catalog = selected.isCatalogRoot() ? "root" : selected.isCollected() ? "child" : "absent";
                next.add(new DetailLine(Component.translatable(PREFIX + "catalog." + catalog),
                        selected.isCollected() ? UiTextScheme.PARCHMENT.positive() : UiTextScheme.PARCHMENT.hint()));
            }
            if (!nameDrafts.isEmpty())
                next.add(new DetailLine(Component.translatable(PREFIX + "source.draft", nameDrafts.size()), UiTextScheme.PARCHMENT.accent()));
            if (!LootTableManagementClientState.canEdit())
                next.add(new DetailLine(Component.translatable(PREFIX + "names_read_only"), UiTextScheme.PARCHMENT.accent()));
            else if (!hasValidLanguageCode())
                next.add(new DetailLine(Component.translatable(PREFIX + "invalid_language_code"), UiTextScheme.PARCHMENT.negative()));
            else if (requiresEnglishName())
                next.add(new DetailLine(Component.translatable(PREFIX + "english_name_required"), UiTextScheme.PARCHMENT.accent()));
            else if (selected != null)
                next.add(new DetailLine(nameSourceLabel(selected.tableId()), UiTextScheme.PARCHMENT.label()));
            var config = LootTableManagementClientState.configuration();
            if (config != null && config.isCatalogPending()) {
                next.add(new DetailLine(Component.translatable(PREFIX + "catalog.pending"), UiTextScheme.PARCHMENT.accent()));
            }
            if (trackingStatus != null) next.add(new DetailLine(trackingStatus, UiTextScheme.PARCHMENT.accent()));
            if (importStatus != null) next.add(new DetailLine(importStatus, importStatusColor));
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
        var receipt = LootTableManagementClientState.receipt(pendingTrackingRequest);
        if (receipt != null) {
            pendingTrackingRequest = 0;
            trackingStatus = LootTrackingConfigScreen.resultMessage(receipt.result());
        }
        long revision = LootTableManagementClientState.revision();
        if (revision != this.observedRevision) {
            this.observedRevision = revision;
            reconcileDrafts();
            rebuildTree();
        }
    }

    private void rebuildTree() {
        TreeRow selectedRow = list.selectedItem();
        String selectedKey = selectedRow == null ? null : selectedRow.key();
        String query = this.searchBox.value().trim().toLowerCase(Locale.ROOT);
        boolean expandSearchResults = !query.isEmpty();
        this.filteredEntries.clear();
        LinkedHashMap<String, TreeNode> namespaces = new LinkedHashMap<>();
        for (SyncLootTableManagementPayload.Entry entry : entriesForCurrentFilter()) {
            if (!this.filter.matches(entry.isCollected()) || !matchesQuery(entry, query)) continue;
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
        // 行数变化后重新钳制偏移：同时按当前面板尺寸刷新视口，resize 后的首次重建也拿到正确容量
        syncListGeometry();
        list.setItems(visibleRows);
        if (selectedEntry() == null) this.selectedTableId = null;
        int selectedIndex = -1;
        for (int i = 0; i < visibleRows.size(); i++) {
            if (Objects.equals(visibleRows.get(i).key(), selectedKey)) {
                selectedIndex = i;
                break;
            }
        }
        list.setSelectedIndex(selectedIndex);
        populateName();
        syncControls();
    }

    private void flattenNode(TreeNode node, int depth, boolean forceExpanded) {
        boolean expandable = !node.children.isEmpty();
        boolean expanded = expandable && (forceExpanded || this.expandedPaths.contains(node.key));
        this.visibleRows.add(new TreeRow(node.label, node.key, depth, expandable, expanded, node.entry));
        if (expanded) {
            node.children.values().stream()
                    .sorted(Comparator.comparing((TreeNode child) -> child.children.isEmpty()))
                    .forEach(child -> flattenNode(child, depth + 1, forceExpanded));
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

    private boolean canEditTracking() {
        var config = LootTableManagementClientState.configuration();
        return config != null && config.canEdit() && config.isWritable() && pendingTrackingRequest == 0;
    }

    private void submitTracking(TableEdit edit) {
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        if (entry == null || !canEditTracking()) return;
        var config = LootTableManagementClientState.configuration();
        pendingTrackingRequest = LootTableManagementClientState.submit(config.revision(),
                new UpdateTrackedLootTablePayload.Single(entry.tableId(), edit));
        trackingStatus = Component.translatable("unsuspiciousblock.config.loot_tracking.saving");
    }

    private void saveName() {
        if (!LootTableManagementClientState.canEdit() || this.nameDrafts.isEmpty()
                || hasInvalidEnglishDraft()) return;
        // 这里只做草稿到提交项的数据转换，协议组包交给动作层
        this.actions.submitNameDrafts(this.nameDrafts.entrySet().stream()
                .map(entry -> new LootTableManagementActions.NameDraft(
                        entry.getKey().languageCode(), entry.getKey().tableId(), entry.getValue()))
                .toList());
    }

    private void populateName() {
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        String value = "";
        Component hint = Component.empty();
        if (entry != null && hasValidLanguageCode()) {
            DraftKey draftKey = new DraftKey(currentLanguageCode(), entry.tableId());
            ClientLootTableLanguageStore.ResourceTranslation resource = resourceName(draftKey);
            if (resource != null) {
                value = resource.value();
            } else {
                value = this.nameDrafts.getOrDefault(draftKey, storedName(draftKey));
                String fallback = englishFallback(entry.tableId());
                if (value.isEmpty() && !fallback.isEmpty()) {
                    hint = Component.literal(fallback);
                }
            }
        }
        this.populatingName = true;
        this.nameBox.setValue(value);
        this.nameBox.setHint(hint);
        this.populatingName = false;
    }

    private void onNameInputChanged(String value) {
        if (this.populatingName || !hasValidLanguageCode()) return;
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        if (entry == null || resourceName(new DraftKey(currentLanguageCode(), entry.tableId())) != null) return;
        DraftKey draftKey = new DraftKey(currentLanguageCode(), entry.tableId());
        String normalized = value.trim();
        if (normalized.equals(storedName(draftKey))) {
            this.nameDrafts.remove(draftKey);
        } else {
            this.nameDrafts.put(draftKey, normalized);
        }
        syncControls();
    }

    private String storedName(DraftKey draftKey) {
        String key = LootTableNames.createTranslationKey(draftKey.tableId());
        return LootTableManagementClientState.translations()
                .getOrDefault(draftKey.languageCode(), Map.of()).getOrDefault(key, "");
    }

    private void reconcileDrafts() {
        this.nameDrafts.entrySet().removeIf(entry -> resourceName(entry.getKey()) != null
                || entry.getValue().equals(storedName(entry.getKey())));
    }

    @Nullable
    private SyncLootTableManagementPayload.Entry selectedEntry() {
        if (this.selectedTableId == null) return null;
        for (SyncLootTableManagementPayload.Entry entry : this.filteredEntries) {
            if (entry.tableId().equals(this.selectedTableId)) return entry;
        }
        return null;
    }

    private List<SyncLootTableManagementPayload.Entry> entriesForCurrentFilter() {
        if (this.filter != Filter.RECENT) {
            return LootTableManagementClientState.entries();
        }
        Map<ResourceLocation, SyncLootTableManagementPayload.Entry> entriesById = new LinkedHashMap<>();
        LootTableManagementClientState.entries().forEach(entry -> entriesById.put(entry.tableId(), entry));
        List<SyncLootTableManagementPayload.Entry> result = new ArrayList<>();
        for (SyncLootTableManagementPayload.RecentEntry recent
                : LootTableManagementClientState.recentEntries()) {
            SyncLootTableManagementPayload.Entry entry = entriesById.get(recent.tableId());
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    private void initializeLanguageCodes() {
        this.languageCodes.clear();
        this.languageCodes.addAll(Minecraft.getInstance().getLanguageManager().getLanguages().keySet());
        if (!isValidLanguageCode(this.selectedLanguageCode)) {
            this.selectedLanguageCode = Minecraft.getInstance().getLanguageManager().getSelected();
        }
        if (!isValidLanguageCode(this.selectedLanguageCode) && !this.languageCodes.isEmpty()) {
            this.selectedLanguageCode = this.languageCodes.getFirst();
        }
    }

    private static Component languageOptionLabel(String code) {
        var info = Minecraft.getInstance().getLanguageManager().getLanguages().get(code);
        return info == null ? Component.literal(code)
                : Component.literal(code + " - ").append(info.toComponent());
    }

    private String currentLanguageCode() {
        return this.languageBox.value().trim();
    }

    private void onLanguageInputChanged(String value) {
        boolean valid = isValidLanguageCode(value.trim());
        if (valid) {
            this.selectedLanguageCode = value.trim();
            populateName();
        }
        syncControls();
    }

    private void selectLanguage(String languageCode) {
        this.selectedLanguageCode = languageCode;
        if (this.languageBox != null) {
            this.languageBox.setValue(languageCode);
        }
        populateName();
        syncControls();
    }

    private boolean hasValidLanguageCode() {
        return isValidLanguageCode(currentLanguageCode());
    }

    private String validLanguageCodeOrFallback() {
        return hasValidLanguageCode() ? currentLanguageCode() : this.selectedLanguageCode;
    }

    // 语言码口径与导入文件名推断共用同一份实现
    private static boolean isValidLanguageCode(String languageCode) {
        return LootTableImportValidator.isValidLanguageCode(languageCode);
    }

    private boolean requiresEnglishName() {
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        if (entry == null || !hasValidLanguageCode() || "en_us".equals(currentLanguageCode())
                || this.nameBox.value().trim().isEmpty()) {
            return false;
        }
        return effectiveEnglishName(entry.tableId()).isBlank();
    }

    private boolean hasInvalidEnglishDraft() {
        for (Map.Entry<DraftKey, String> entry : this.nameDrafts.entrySet()) {
            if (!"en_us".equals(entry.getKey().languageCode()) && !entry.getValue().isBlank()
                    && effectiveEnglishName(entry.getKey().tableId()).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private String effectiveEnglishName(ResourceLocation tableId) {
        DraftKey englishKey = new DraftKey("en_us", tableId);
        ClientLootTableLanguageStore.ResourceTranslation resource = resourceName(englishKey);
        if (resource != null) return resource.value();
        return this.nameDrafts.getOrDefault(englishKey, storedName(englishKey));
    }

    private String englishFallback(ResourceLocation tableId) {
        if ("en_us".equals(currentLanguageCode())) return "";
        return effectiveEnglishName(tableId);
    }

    @Nullable
    private ClientLootTableLanguageStore.ResourceTranslation resourceName(DraftKey draftKey) {
        return ClientLootTableLanguageStore.findResourceTranslation(
                draftKey.languageCode(), LootTableNames.createTranslationKey(draftKey.tableId()));
    }

    private Component nameSourceLabel(ResourceLocation tableId) {
        DraftKey draftKey = new DraftKey(currentLanguageCode(), tableId);
        ClientLootTableLanguageStore.ResourceTranslation resource = resourceName(draftKey);
        if (resource != null) {
            return Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.source.resource", resource.sourcePackId());
        }
        if (this.nameDrafts.containsKey(draftKey)) {
            return Component.empty();
        }
        if (!storedName(draftKey).isEmpty()) {
            return Component.translatable("screen.unsuspiciousblock.loot_table_management.source.server");
        }
        DraftKey englishKey = new DraftKey("en_us", tableId);
        ClientLootTableLanguageStore.ResourceTranslation englishResource = resourceName(englishKey);
        if (englishResource != null && !"en_us".equals(currentLanguageCode())) {
            return Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.source.fallback_resource",
                    englishResource.sourcePackId());
        }
        if (!storedName(englishKey).isEmpty() && !"en_us".equals(currentLanguageCode())) {
            return Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.source.fallback_server");
        }
        return Component.translatable("screen.unsuspiciousblock.loot_table_management.source.missing");
    }

    // 导入流程只做转调：文件对话框、读取校验、预览统计都在动作层，失败原因按分类给独立文案
    private void importJson() {
        String selectedPath = this.actions.chooseImportFile();
        if (selectedPath == null) return;
        LootTableManagementActions.ImportResult result =
                this.actions.prepareImport(Path.of(selectedPath), validLanguageCodeOrFallback());
        if (result instanceof LootTableManagementActions.ImportResult.Failed(var reason)) {
            this.importStatus = importFailureMessage(reason);
            this.importStatusColor = IMPORT_STATUS_ERROR_COLOR;
            return;
        }
        openImportPreview(((LootTableManagementActions.ImportResult.Ready) result).preview());
    }

    // 导入失败原因分类：格式无效只是兜底，文件过大 / 读取失败 / 条目超限各有独立文案键
    private static Component importFailureMessage(LootTableImportValidator.Failure failure) {
        return switch (failure) {
            case TOO_LARGE -> Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.import_failed.too_large");
            case TOO_MANY_ENTRIES -> Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.import_failed.entries",
                    LootTableImportValidator.MAX_ENTRIES);
            case IO_ERROR, NOT_A_FILE -> Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.import_failed.io");
            case INVALID_JSON, ROOT_NOT_OBJECT -> Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.import_failed");
        };
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
     * 页面内未提交名称的复合键。
     */
    private record DraftKey(String languageCode, ResourceLocation tableId) {
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

        private Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        private Component label() {
            return Component.translatable(this.key);
        }

        private boolean matches(boolean tracked) {
            return this == ALL || this == RECENT || (this == TRACKED) == tracked;
        }
    }
}
