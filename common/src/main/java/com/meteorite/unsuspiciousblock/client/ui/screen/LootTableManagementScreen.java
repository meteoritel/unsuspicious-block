package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.style.UiTextScheme;

import com.meteorite.meteosuikit.api.control.UiFocusFollow;

import net.minecraft.Util;
import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncLootTableManagementPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

/** 战利品追踪管理；业务树和草稿归宿主，列表、输入与确认统一使用 kit。 */
public final class LootTableManagementScreen extends UiThemedScreen {
    private static final int SCREEN_MARGIN = 6;
    private static final int MAX_PANEL_WIDTH = 520;
    private static final int MAX_PANEL_HEIGHT = 310;
    private static final int INNER_MARGIN = 10;
    private static final int COLUMN_GAP = 10;
    private static final int ROW_HEIGHT = 20;
    private static final int INDENT_WIDTH = 10;
    private static final int IMPORT_STATUS_OK_COLOR = UiTextScheme.DARK.positive();
    private static final int IMPORT_STATUS_ERROR_COLOR = UiTextScheme.DARK.negative();
    private static final String PREFIX = "screen.unsuspiciousblock.loot_table_management.";
    private final Screen parent;
    private final LootTableManagementActions actions = new LootTableManagementActions();
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private final UiControlGroup controls = new UiControlGroup();
    private final UiControlGroup detailControls = new UiControlGroup();
    private final UiScrollView detailScroll = new UiScrollView();
    private final UiModalStack modals = new UiModalStack(theme());
    private final UiListView<TreeRow> list;
    private final UiTextInput searchBox;
    private final UiTextInput languageBox;
    private final UiTextInput nameBox;
    private UiControl filterButton;
    private UiControl backButton;
    private UiControl applyButton;
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

    public LootTableManagementScreen(Screen parent) {
        super(Component.translatable(PREFIX + "title"), new UiThemeContext<>(UnsuspiciousUiTheme.DARK));
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
            String marker = row.expandable() ? row.expanded() ? "v " : "> " : "";
            String tracking = row.entry() == null ? "" : row.entry().tracked() ? "+ " : "- ";
            int x = rect.x() + row.depth() * INDENT_WIDTH;
            graphics.drawString(rowFont, TextScroll.trimToWidth(rowFont, marker + tracking + row.label(),
                    Math.max(0, rect.right() - x)), x, rect.y() + 5, UiTextScheme.DARK.body(), false);
        });
        list.setRowHeight(ROW_HEIGHT);
        list.setActivateOnSingleClick(true);
        list.setOnActivate(row -> {
            if (row.entry() != null) {
                selectedTableId = row.entry().tableId();
                populateName();
            }
            if (row.expandable()) toggleExpanded(row);
            syncControls();
        });
        UiChildren<UiInputTarget> fields = new UiChildren<>();
        fields.addAll(List.of(languageBox, nameBox, detailControls));
        UiScrollContainer details = new UiScrollContainer(detailScroll, fields);
        content.addAll(List.of(searchBox, list, details, controls));
        scope.setEscape(this::onClose);
        scope.focus().setListener(new UiFocusFollow(detailScroll,
                target -> target == languageBox || target == nameBox || (target instanceof UiControl control && detailControls.controls().contains(control)),
                target -> new UiRect(0, target.bounds().y() - detailScroll.viewport().y() + detailScroll.offset() - 13,
                        target.bounds().width(), target.bounds().height() + 13), this::layoutUi));
        modals.setOnScopeChanged(this::refreshUiState);
    }

    @Override protected void init() {
        controls.setTheme(theme());
        detailControls.setTheme(theme());
        controls.beginUpdate();
        detailControls.beginUpdate();
        configureControls();
        controls.endUpdate();
        detailControls.endUpdate();
        if (!initialized) { initialized = true; actions.requestSnapshot(); }
        refreshSnapshot();
        syncControls();
    }

    private void configureControls() {
        filterButton = controls.obtain("filter");
        filterButton.configure(font, filter.label(), null, List.of(), this::cycleFilter);
        languageButton = detailControls.obtain("language");
        languageButton.configure(font, Component.translatable(PREFIX + "choose_language"), null, List.of(), this::chooseLanguage);
        applyButton = detailControls.obtain("tracking");
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

    @Override protected UiInputScope inputScope() { return modals.isEmpty() ? scope : modals.top().inputScope(); }
    @Override protected void prepareUi() { refreshSnapshot(); syncControls(); modals.prepareUi(); }
    @Override protected void advanceUiFrame() { modals.tick(); }
    @Override protected void invalidateUiResources() {
        initializeLanguageCodes();
        reconcileDrafts();
        rebuildTree();
    }
    @Override protected void layoutUi() {
        int left = panelLeft();
        int top = panelTop();
        int detailsX = left + INNER_MARGIN + listWidth() + COLUMN_GAP;
        detailScroll.setViewport(detailsX, top + 28, detailsWidth(), Math.max(0, panelHeight() - 64));
        detailScroll.setContentHeight(224);
        detailScroll.setScrollbarVisible(detailScroll.maxOffset() > 0);
        int detailsWidth = detailScroll.contentWidth();
        int backY = top + panelHeight() - 30;
        int actionY = detailScroll.toScreenY(194);
        int nameY = detailScroll.toScreenY(164);
        int languageY = detailScroll.toScreenY(100);
        searchBox.setBounds(left + INNER_MARGIN, top + 28, Math.max(0, listWidth() - 76), 20);
        filterButton.setBounds(left + INNER_MARGIN + Math.max(0, listWidth() - 72), top + 28, 72, 20);
        languageBox.setBounds(detailsX, languageY, detailsWidth, 20);
        languageButton.setBounds(detailsX, languageY + 23, detailsWidth, 20);
        nameBox.setBounds(detailsX, nameY, detailsWidth, 20);
        int actionWidth = Math.max(0, (detailsWidth - 5) / 2);
        applyButton.setBounds(detailsX, actionY, actionWidth, 20);
        saveNameButton.setBounds(detailsX + actionWidth + 5, actionY, actionWidth, 20);
        actionWidth = Math.max(0, (detailsWidth() - 5) / 2);
        importButton.setBounds(detailsX, backY, actionWidth, 20);
        backButton.setBounds(detailsX + actionWidth + 5, backY, actionWidth, 20);
        syncListGeometry();
        modals.setBounds(0, 0, width, height);
    }

    private void syncControls() {
        if (applyButton == null) return;
        var entry = selectedEntry();
        boolean selected = entry != null && LootTableManagementClientState.canEdit();
        applyButton.setEnabled(selected);
        applyButton.configure(Minecraft.getInstance().font, Component.translatable(PREFIX
                + (entry != null && entry.tracked() ? "remove" : "add")), null, List.of(), this::applySelection);
        saveNameButton.setEnabled(LootTableManagementClientState.canEdit() && !nameDrafts.isEmpty() && !hasInvalidEnglishDraft());
        languageButton.setEnabled(selected);
        languageBox.shell().setEnabled(selected);
        nameBox.shell().setEnabled(selected && hasValidLanguageCode()
                && resourceName(new DraftKey(currentLanguageCode(), entry.tableId())) == null);
        importButton.setEnabled(LootTableManagementClientState.canEdit());
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override protected void renderThemedUi(GuiGraphics graphics, int x, int y, float delta) {
        graphics.drawCenteredString(font, title, width / 2, panelTop() + 9, UiTextScheme.DARK.title());
        UiPointer pointer = scope.pointer(x, y).resolve(content);
        list.render(graphics, font, pointer);
        searchBox.render(graphics, font, pointer);
        UiRect viewport = detailScroll.viewport();
        graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        var detailPointer = pointer.restrict(viewport.contains(x, y));
        renderDetails(graphics, viewport.x(), detailScroll.toScreenY(0), detailScroll.contentWidth());
        languageBox.render(graphics, font, detailPointer);
        nameBox.render(graphics, font, detailPointer);
        detailControls.render(graphics, font, detailPointer);
        graphics.disableScissor();
        detailScroll.renderScrollbar(graphics, theme());
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
                        row.x(), row.y() + 5, UiTextScheme.DARK.body(), false));
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

    private void syncListGeometry() { list.setBounds(panelLeft() + INNER_MARGIN, panelTop() + 55,
            listWidth(), Math.max(0, panelHeight() - 65)); }
    private int panelWidth() { return Math.clamp(width - SCREEN_MARGIN * 2, 0, MAX_PANEL_WIDTH); }
    private int panelHeight() { return Math.clamp(height - SCREEN_MARGIN * 2, 0, MAX_PANEL_HEIGHT); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int panelTop() { return (height - panelHeight()) / 2; }
    private int detailsWidth() { return Math.max(0, (panelWidth() - INNER_MARGIN * 2 - COLUMN_GAP) * 42 / 100); }
    private int listWidth() { return Math.max(0, panelWidth() - INNER_MARGIN * 2 - COLUMN_GAP - detailsWidth()); }
    private static String readableName(ResourceLocation id) {
        String path = id.getPath();
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1).replace('_', ' ').replace('-', ' ');
    }

    private void renderOverflowText(GuiGraphics graphics, String text, int x, int y, int width, int color) {
        TextScroll.draw(graphics, font, text, x, y, width, color, true,
                (int) (Util.getMillis() / 50), false);
    }

    private void renderDetails(GuiGraphics graphics, int x, int top, int width) {
        SyncLootTableManagementPayload.Entry selected = selectedEntry();
        graphics.drawString(this.font,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.details"),
                x, top + 4, 0xFFFFFF, false);
        if (!this.nameDrafts.isEmpty()) {
            Component draftStatus = Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.source.draft", this.nameDrafts.size());
            renderOverflowText(graphics, draftStatus.getString(), x, top + 78, width, 0xFFAA00);
        }
        if (selected != null) {
            renderOverflowText(graphics, selected.tableId().toString(), x, top + 23,
                    width, UiTextScheme.DARK.label());
            Component status = Component.translatable(selected.tracked()
                    ? "screen.unsuspiciousblock.loot_table_management.tracked"
                    : "screen.unsuspiciousblock.loot_table_management.not_tracked");
            graphics.drawString(this.font, status, x, top + 39,
                    selected.tracked() ? 0x78D66A : 0x999999, false);
        }
        graphics.drawString(this.font,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.language"),
                x, this.languageBox.bounds().y() - 11, 0xB8B8B8, false);
        graphics.drawString(this.font,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.localized_name"),
                x, this.nameBox.bounds().y() - 11, 0xB8B8B8, false);
        int warningY = top + 52;
        if (!LootTableManagementClientState.canEdit()) {
            renderOverflowText(graphics, Component.translatable(
                            "screen.unsuspiciousblock.loot_table_management.read_only").getString(),
                    x, warningY, width, UiTextScheme.DARK.accent());
        } else if (!hasValidLanguageCode()) {
            renderOverflowText(graphics, Component.translatable(
                            "screen.unsuspiciousblock.loot_table_management.invalid_language_code").getString(),
                    x, warningY, width, UiTextScheme.DARK.negative());
        } else if (requiresEnglishName()) {
            renderOverflowText(graphics, Component.translatable(
                            "screen.unsuspiciousblock.loot_table_management.english_name_required").getString(),
                    x, warningY, width, UiTextScheme.DARK.accent());
        } else if (selected != null) {
            renderOverflowText(graphics, nameSourceLabel(selected.tableId()).getString(),
                    x, warningY, width, UiTextScheme.DARK.label());
        }
        if (this.importStatus != null) {
            renderOverflowText(graphics, this.importStatus.getString(),
                    x, warningY + 12, width, this.importStatusColor);
        }
    }

    private void refreshSnapshot() {
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
        // 行数变化后重新钳制偏移：同时按当前面板尺寸刷新视口，resize 后的首次重建也拿到正确容量
        syncListGeometry();
        list.setItems(visibleRows);
        if (selectedEntry() == null) this.selectedTableId = null;
        int selectedIndex = -1;
        for (int i = 0; i < visibleRows.size(); i++) {
            if (Objects.equals(visibleRows.get(i).key(), selectedKey)) { selectedIndex = i; break; }
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

    private void applySelection() {
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        if (entry == null || !LootTableManagementClientState.canEdit()) return;
        this.actions.submitTracking(entry.tableId(), !entry.tracked());
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

    /** 按命名空间与路径构建的业务目录节点。 */
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

    /** 页面内未提交名称的复合键。 */
    private record DraftKey(String languageCode, ResourceLocation tableId) {
    }

    /** 管理目录的可见行投影。 */
    private record TreeRow(String label, String key, int depth, boolean expandable,
                           boolean expanded, @Nullable SyncLootTableManagementPayload.Entry entry) {
    }

    /** 管理页筛选语义。 */
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
