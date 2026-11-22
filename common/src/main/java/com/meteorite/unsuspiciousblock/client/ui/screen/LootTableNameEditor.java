package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiFormBody;
import com.meteorite.meteosuikit.api.control.UiListView;
import com.meteorite.meteosuikit.api.control.UiModal;
import com.meteorite.meteosuikit.api.control.UiModalStack;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiStyleSource;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.client.ui.screen.LootTableManagementScreen.DetailLine;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit.Result;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit.Status;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateLootTableTranslationsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** 表名表单、导入预览与当前管理页的编辑草稿。 */
final class LootTableNameEditor {
    private static final String PREFIX = "screen.unsuspiciousblock.loot_table_management.";
    private static final int IMPORT_STATUS_OK_COLOR = UiTextScheme.PARCHMENT.positive();
    private static final int IMPORT_STATUS_ERROR_COLOR = UiTextScheme.PARCHMENT.negative();
    private static @Nullable RecoveredDraft recoveredDraft;
    private final Supplier<? extends UiStyleSource> theme;
    private final UiModalStack modals;
    private final Supplier<@Nullable ResourceLocation> selected;
    private final IntSupplier height;
    private final @Nullable DraftOwner owner;
    private final UiTextInput languageBox;
    private final UiTextInput nameBox;
    private final UiControl languageButton = new UiControl();
    private final UiControl saveNameButton = new UiControl();
    private final UiControl resolveLegacyNames = new UiControl();
    private final List<String> languageCodes = new ArrayList<>();
    private final Map<DraftKey, NameDraft> nameDrafts = new LinkedHashMap<>();
    private int pendingNameRequest;
    private Map<DraftKey, NameDraft> submittedNames = Map.of();
    private boolean populatingName;
    private String selectedLanguageCode = "";
    private @Nullable Component importStatus;
    private int importStatusColor = IMPORT_STATUS_OK_COLOR;

    LootTableNameEditor(Supplier<? extends UiStyleSource> theme, UiModalStack modals,
                        Supplier<@Nullable ResourceLocation> selected, IntSupplier height) {
        this.theme = theme;
        this.modals = modals;
        this.selected = selected;
        this.height = height;
        this.owner = draftOwner();
        if (recoveredDraft != null && recoveredDraft.owner().equals(owner)) {
            nameDrafts.putAll(recoveredDraft.entries());
            recoveredDraft = null;
            importStatus = Component.translatable(PREFIX + "names_recovered");
        }
        initializeLanguageCodes();
        languageBox = new UiTextInput(theme, font(), Component.translatable(PREFIX + "language"))
                .setMaxLength(LootTableNameEdit.MAX_LANGUAGE_LENGTH).setValue(selectedLanguageCode);
        nameBox = new UiTextInput(theme, font(), Component.translatable(PREFIX + "localized_name"))
                .setMaxLength(LootTableNameEdit.MAX_NAME_LENGTH).setFilter(LootTableNameEdit::isName);
        languageBox.setOnValueChanged(() -> onLanguageInputChanged(languageBox.value()));
        nameBox.setOnValueChanged(() -> onNameInputChanged(nameBox.value()));
    }

    void install(UiFormBody details) {
        details.add(Component.translatable(PREFIX + "language"), languageBox, 20);
        details.add(Component.empty(), languageButton, 20);
        details.add(Component.translatable(PREFIX + "localized_name"), nameBox, 20);
        details.add(Component.empty(), saveNameButton, 20);
        details.add(Component.empty(), resolveLegacyNames, 20);
    }

    void configure() {
        for (UiControl control : List.of(languageButton, saveNameButton, resolveLegacyNames)) {
            control.setTheme(theme, UiControlRole.SECONDARY);
            control.setFocusable(true);
        }
        languageButton.configure(font(), Component.translatable(PREFIX + "choose_language"), null, List.of(), this::chooseLanguage);
        saveNameButton.configure(font(), Component.translatable(PREFIX + "save_name"), null, List.of(), this::saveName);
        resolveLegacyNames.configure(font(), Component.translatable(PREFIX + "legacy_names"),
                null, List.of(), this::openLegacyNames);
    }

    void refreshControls(boolean open) {
        boolean canEdit = LootTableManagementClientState.canEdit();
        boolean hasTable = selected.get() != null;
        boolean validLanguage = hasValidLanguageCode();
        languageBox.setVisible(open);
        languageButton.setVisible(open);
        nameBox.setVisible(open);
        saveNameButton.setVisible(open);
        languageBox.setEnabled(canEdit);
        languageButton.setEnabled(canEdit);
        nameBox.setEnabled(hasTable && canEdit && validLanguage);
        saveNameButton.setEnabled(canEdit && !nameDrafts.isEmpty() && pendingNameRequest == 0);
        saveNameButton.setTheme(theme, saveNameButton.isEnabled() ? UiControlRole.PRIMARY : UiControlRole.SECONDARY);
        boolean hasLegacy = validLanguage && !LootTableManagementClientState.unresolvedNames()
                .getOrDefault(currentLanguageCode(), Map.of()).isEmpty();
        resolveLegacyNames.setVisible(open && hasLegacy);
        resolveLegacyNames.setEnabled(canEdit);
    }

    boolean canImport() {
        return LootTableManagementClientState.canEdit() && LootTableManagementClientState.indexReady();
    }

    void invalidateResources() {
        initializeLanguageCodes();
    }

    List<DetailLine> summary() {
        List<DetailLine> lines = new ArrayList<>();
        lines.add(new DetailLine(Component.translatable(PREFIX + "instance_names"), UiTextScheme.PARCHMENT.hint()));
        if (!nameDrafts.isEmpty()) {
            lines.add(new DetailLine(Component.translatable(PREFIX + "source.draft", nameDrafts.size()),
                    UiTextScheme.PARCHMENT.accent()));
        }
        ResourceLocation id = selected.get();
        if (!LootTableManagementClientState.canEdit()) {
            lines.add(new DetailLine(Component.translatable(PREFIX + "names_read_only"), UiTextScheme.PARCHMENT.accent()));
        } else if (!hasValidLanguageCode()) {
            lines.add(new DetailLine(Component.translatable(PREFIX + "invalid_language_code"), UiTextScheme.PARCHMENT.negative()));
        } else if (id != null) {
            lines.add(new DetailLine(nameSourceLabel(id), UiTextScheme.PARCHMENT.label()));
        }
        if (importStatus != null) lines.add(new DetailLine(importStatus, importStatusColor));
        return lines;
    }

    void requestClose(Runnable close) {
        if (nameDrafts.isEmpty()) {
            close.run();
            return;
        }
        if (Minecraft.getInstance().level == null) {
            close.run();
            return;
        }
        if (pendingNameRequest != 0) return;
        modals.push(UiModal.create(theme, font())
                .title(Component.translatable(LootTrackingPreviewDialog.KEY + "discard_title"))
                .message(Component.translatable(LootTrackingPreviewDialog.KEY + "discard_message"))
                .confirm(Component.translatable(LootTrackingPreviewDialog.KEY + "discard"), () -> {
                    nameDrafts.clear();
                    close.run();
                })
                .cancel(Component.translatable(LootTrackingPreviewDialog.KEY + "continue_editing")));
    }

    void preserveDraft() {
        if (owner != null && !nameDrafts.isEmpty()) recoveredDraft = new RecoveredDraft(owner, Map.copyOf(nameDrafts));
    }

    void reopenDraft() {
        if (recoveredDraft != null && recoveredDraft.owner().equals(owner)) recoveredDraft = null;
    }

    private static @Nullable DraftOwner draftOwner() {
        Minecraft game = Minecraft.getInstance();
        if (game.player == null) return null;
        var world = game.getSingleplayerServer();
        if (world != null) return new DraftOwner(game.player.getUUID(), world.getWorldPath(LevelResource.ROOT), "");
        var server = game.getCurrentServer();
        return server == null ? null : new DraftOwner(game.player.getUUID(), null, server.ip);
    }

    private record DraftOwner(UUID player, @Nullable Path world, String server) {}

    private record RecoveredDraft(DraftOwner owner, Map<DraftKey, NameDraft> entries) {}

    private static Font font() { return Minecraft.getInstance().font; }

    private void chooseLanguage() {
        UiListView<String> languages = new UiListView<>(theme, (graphics, font, code, index, row, selected, hovered, focused) ->
                graphics.drawString(font, TextScroll.trimToWidth(font, languageOptionLabel(code).getString(), row.width()),
                        row.x(), row.y() + 5, UiTextScheme.PARCHMENT.body(), false));
        languages.setLayoutKey("management/languages");
        languages.setItemKey(code -> "language/" + code);
        languages.setItems(languageCodes).setSelectedIndex(languageCodes.indexOf(validLanguageCodeOrFallback()));
        modals.push(UiModal.create(theme, font()).title(Component.translatable(PREFIX + "language_select"))
                .preferredWidth(340).contentWidget(languages, Math.clamp(height.getAsInt() - 110, 20, 180))
                .confirm(Component.translatable("gui.done"), () -> {
                    String selected = languages.selectedItem();
                    if (selected != null) selectLanguage(selected);
                }).cancel(Component.translatable("gui.cancel")));
    }

    private void openImport(LootTableImportValidator.ParsedImport parsed) {
        UiTextInput language = new UiTextInput(theme, font(), Component.translatable(PREFIX + "language"))
                .setMaxLength(LootTableNameEdit.MAX_LANGUAGE_LENGTH).setValue(parsed.languageCode());
        UiControl next = new UiControl();
        next.setTheme(theme, UiControlRole.PRIMARY);
        next.setFocusable(true);
        UiFormBody form = new UiFormBody(theme, TextMeasurer.of(font()));
        form.add(Component.translatable(PREFIX + "language"), language, 20);
        form.add(Component.empty(), next, 20);
        next.configure(font(), Component.translatable(PREFIX + "import_preview.title"), null, List.of(),
                () -> {
                    modals.closeTop();
                    openImportPreview(parsed.entries().asMap(), language.value().trim(), new LinkedHashMap<>(), false);
                });
        language.setOnValueChanged(() -> next.setEnabled(LootTableNameEdit.isLanguage(language.value().trim())));
        next.setEnabled(LootTableNameEdit.isLanguage(language.value()));
        modals.push(UiModal.create(theme, font()).title(Component.translatable(PREFIX + "language_select"))
                .contentWidget(form, 72).cancel(Component.translatable("gui.cancel")));
    }

    private void openImportPreview(Map<String, JsonElement> data, String language,
                                   Map<String, ResourceLocation> resolved, boolean fromStore) {
        var rows = LootTableManagementActions.preview(data, language, resolved);
        UiListView<LootTableManagementActions.ImportRow> changes = new UiListView<>(theme,
                (graphics, font, row, index, bounds, selected, hovered, focused) -> {
                    Component label = Component.translatable(PREFIX + "import_row." + row.status().name().toLowerCase(Locale.ROOT),
                            row.key());
                    if (row.edit() != null) {
                        label = label.copy().append(" · ").append(row.edit().value());
                        if (row.status() == LootTableManagementActions.ImportStatus.RESOURCE) {
                            label = label.copy().append(" · ").append(ClientLootTableLanguageStore
                                    .resolve(row.edit().tableId(), language).sourcePackId());
                        }
                    }
                    graphics.drawString(font, TextScroll.trimToWidth(font, label.getString(), bounds.width()),
                            bounds.x(), bounds.y() + 5, UiTextScheme.PARCHMENT.body(), false);
                });
        changes.setItems(rows);
        changes.setItemKey(LootTableManagementActions.ImportRow::key);
        changes.setActivateOnSingleClick(false);
        changes.setOnActivate(row -> chooseLegacyId(data, language, resolved, row, fromStore));
        UiControl resolve = new UiControl();
        resolve.setTheme(theme, UiControlRole.SECONDARY);
        resolve.setFocusable(true);
        resolve.setEnabled(false);
        resolve.configure(font(), Component.translatable(PREFIX + "resolve_legacy"),
                null, List.of(), () -> chooseLegacyId(data, language, resolved, changes.selectedItem(), fromStore));
        changes.setOnSelectionChanged(index -> resolve.setEnabled(canResolveLegacy(changes.selectedItem(), fromStore)));
        UiFormBody form = new UiFormBody(theme, TextMeasurer.of(font()));
        int listHeight = Math.max(40, Math.min(180, height.getAsInt() - 120));
        form.add(Component.empty(), changes, listHeight);
        if (rows.stream().anyMatch(row -> canResolveLegacy(row, fromStore))) form.add(Component.empty(), resolve, 20);
        List<LootTableNameEdit> accepted = rows.stream()
                .filter(row -> row.status() == LootTableManagementActions.ImportStatus.ADD
                        || row.status() == LootTableManagementActions.ImportStatus.UPDATE
                        || fromStore && (row.status() == LootTableManagementActions.ImportStatus.UNCHANGED
                        || row.status() == LootTableManagementActions.ImportStatus.RESOURCE))
                .map(row -> nameFromImport(row.edit(), resolved, fromStore)).toList();
        UiModal modal = UiModal.create(theme, font())
                .title(Component.translatable(PREFIX + "import_preview.title").append(" · ").append(language))
                .preferredWidth(440).contentWidget(form, listHeight + 27)
                .cancel(Component.translatable("gui.cancel"));
        if (!accepted.isEmpty()) modal.confirm(Component.translatable(PREFIX + "import_to_drafts"),
                () -> stageImport(accepted));
        modals.push(modal);
    }

    private static LootTableNameEdit nameFromImport(LootTableNameEdit edit, Map<String, ResourceLocation> resolved,
                                                    boolean fromStore) {
        if (!fromStore) return edit;
        String oldKey = resolved.entrySet().stream().filter(entry -> entry.getValue().equals(edit.tableId()))
                .map(Map.Entry::getKey).findFirst().orElse("");
        return new LootTableNameEdit(edit.tableId(), edit.language(), edit.value(), edit.expected(), oldKey);
    }

    private void chooseLegacyId(Map<String, JsonElement> data, String language, Map<String, ResourceLocation> resolved,
                                @Nullable LootTableManagementActions.ImportRow row, boolean fromStore) {
        if (row == null || !canResolveLegacy(row, fromStore)) return;
        if (row.candidates().isEmpty()) {
            enterLegacyId(data, language, resolved, row.key());
            return;
        }
        UiListView<ResourceLocation> tables = new UiListView<>(theme,
                (graphics, font, id, index, bounds, selected, hovered, focused) ->
                        graphics.drawString(font, id.toString(), bounds.x(), bounds.y() + 5,
                                UiTextScheme.PARCHMENT.body(), false));
        tables.setItems(row.candidates());
        tables.setSelectedIndex(0);
        modals.push(UiModal.create(theme, font()).title(Component.translatable(PREFIX + "resolve_legacy"))
                .contentWidget(tables, Math.min(140, 20 * row.candidates().size()))
                .confirm(Component.translatable("gui.done"), () -> {
                    ResourceLocation id = tables.selectedItem();
                    if (id == null) return;
                    resolved.put(row.key(), id);
                    modals.closeTop();
                    openImportPreview(data, language, resolved, fromStore);
                }).cancel(Component.translatable("gui.cancel")));
    }

    private static boolean canResolveLegacy(@Nullable LootTableManagementActions.ImportRow row, boolean fromStore) {
        return row != null && (!row.candidates().isEmpty()
                || fromStore && row.status() == LootTableManagementActions.ImportStatus.UNMATCHED);
    }

    private void enterLegacyId(Map<String, JsonElement> data, String language,
                               Map<String, ResourceLocation> resolved, String key) {
        UiTextInput input = new UiTextInput(theme, font(), Component.translatable(PREFIX + "full_table_id"));
        UiControl apply = new UiControl();
        apply.setTheme(theme, UiControlRole.PRIMARY);
        apply.setFocusable(true);
        UiFormBody form = new UiFormBody(theme, TextMeasurer.of(font()));
        form.add(Component.translatable(PREFIX + "full_table_id"), input, 20);
        form.add(Component.empty(), apply, 20);
        apply.configure(font(), Component.translatable("gui.done"), null, List.of(), () -> {
            resolved.put(key, ResourceLocation.parse(input.value().trim()));
            modals.closeTop();
            modals.closeTop();
            openImportPreview(data, language, resolved, true);
        });
        Set<String> ids = new HashSet<>();
        LootTableManagementClientState.entries().forEach(entry -> ids.add(entry.tableId().toString()));
        input.setOnValueChanged(() -> apply.setEnabled(ids.contains(input.value().trim())));
        apply.setEnabled(false);
        modals.push(UiModal.create(theme, font()).title(Component.translatable(PREFIX + "resolve_legacy"))
                .contentWidget(form, 72).cancel(Component.translatable("gui.cancel")));
    }

    private void stageImport(List<LootTableNameEdit> accepted) {
        boolean overlaps = accepted.stream().anyMatch(edit -> nameDrafts.containsKey(new DraftKey(edit.language(), edit.tableId())));
        if (!overlaps) {
            addImportedDrafts(accepted, false);
            return;
        }
        modals.push(UiModal.create(theme, font()).title(Component.translatable(PREFIX + "import_draft_conflict"))
                .message(Component.translatable(PREFIX + "import_draft_message"))
                .confirm(Component.translatable(PREFIX + "replace_drafts"), () -> addImportedDrafts(accepted, true))
                .confirm(Component.translatable(PREFIX + "keep_drafts"), () -> addImportedDrafts(accepted, false))
                .cancel(Component.translatable("gui.cancel")));
    }

    private void addImportedDrafts(List<LootTableNameEdit> accepted, boolean replace) {
        for (LootTableNameEdit edit : accepted) {
            DraftKey key = new DraftKey(edit.language(), edit.tableId());
            if (replace || !nameDrafts.containsKey(key)) {
                nameDrafts.put(key, new NameDraft(edit.value(), edit.expected(), edit.legacyKey()));
            }
        }
        importStatus = Component.translatable(PREFIX + "import_staged", nameDrafts.size());
        importStatusColor = IMPORT_STATUS_OK_COLOR;
        populateName();
    }

    private void openLegacyNames() {
        String language = currentLanguageCode();
        Map<String, JsonElement> old = new LinkedHashMap<>();
        LootTableManagementClientState.unresolvedNames().getOrDefault(language, Map.of()).forEach((key, value) ->
                old.put(key, new JsonPrimitive(value)));
        openImportPreview(old, language, new LinkedHashMap<>(), true);
    }

    private void saveName() {
        if (!LootTableManagementClientState.canEdit() || nameDrafts.isEmpty() || pendingNameRequest != 0) return;
        if (nameDrafts.size() > UpdateLootTableTranslationsPayload.MAX_ENTRIES) {
            importStatus = Component.translatable(PREFIX + "import_failed.entries", UpdateLootTableTranslationsPayload.MAX_ENTRIES);
            importStatusColor = IMPORT_STATUS_ERROR_COLOR;
            return;
        }
        submittedNames = Map.copyOf(nameDrafts);
        List<LootTableNameEdit> changes = submittedNames.entrySet().stream().map(entry -> {
            DraftKey key = entry.getKey();
            NameDraft draft = entry.getValue();
            return new LootTableNameEdit(key.tableId(), key.languageCode(), draft.value(), draft.expected(), draft.legacyKey());
        }).toList();
        try {
            pendingNameRequest = LootTableManagementClientState.submitNames(changes);
            importStatus = Component.translatable(PREFIX + "names_saving");
            importStatusColor = IMPORT_STATUS_OK_COLOR;
        } catch (IllegalArgumentException exception) {
            submittedNames = Map.of();
            importStatus = Component.translatable(PREFIX + "names_too_large");
            importStatusColor = IMPORT_STATUS_ERROR_COLOR;
        }
    }

    void receiveResults() {
        if (pendingNameRequest == 0) return;
        List<Result> results = LootTableManagementClientState.takeNameResults(pendingNameRequest);
        if (results == null) {
            if (Minecraft.getInstance().level == null) {
                pendingNameRequest = 0;
                submittedNames = Map.of();
                importStatus = Component.translatable(PREFIX + "names_disconnected");
                importStatusColor = IMPORT_STATUS_ERROR_COLOR;
            }
            return;
        }
        pendingNameRequest = 0;
        for (Result result : results) {
            DraftKey key = new DraftKey(result.language(), result.tableId());
            NameDraft submitted = submittedNames.get(key);
            NameDraft draft = nameDrafts.get(key);
            if (draft == null || submitted == null) continue;
            if (result.status() == Status.SAVED || result.status() == Status.UNCHANGED) {
                if (draft.equals(submitted)) nameDrafts.remove(key);
                else nameDrafts.put(key, new NameDraft(draft.value(), result.current(), ""));
            }
        }
        submittedNames = Map.of();
        long saved = results.stream().filter(result -> result.status() == Status.SAVED).count();
        importStatus = Component.translatable(PREFIX + "names_result", saved, nameDrafts.size());
        importStatusColor = nameDrafts.isEmpty() ? IMPORT_STATUS_OK_COLOR : IMPORT_STATUS_ERROR_COLOR;
        populateName();
        showNameResults(results);
    }

    private void showNameResults(List<Result> results) {
        UiListView<Result> rows = new UiListView<>(theme,
                (graphics, font, row, index, bounds, selected, hovered, focused) -> {
                    Component label = Component.translatable(PREFIX + "name_result." + row.status().name().toLowerCase(Locale.ROOT),
                            row.tableId(), row.language(), row.current());
                    graphics.drawString(font, TextScroll.trimToWidth(font, label.getString(), bounds.width()),
                            bounds.x(), bounds.y() + 5, UiTextScheme.PARCHMENT.body(), false);
                });
        rows.setItems(results);
        rows.setItemKey(row -> row.tableId() + "/" + row.language());
        modals.push(UiModal.create(theme, font()).title(importStatus)
                .preferredWidth(440).contentWidget(rows, Math.max(40, Math.min(180, height.getAsInt() - 100)))
                .confirm(Component.translatable(PREFIX + "review_name_conflict"), () -> {
                    Result conflict = rows.selectedItem();
                    if (conflict == null || conflict.status() != Status.CONFLICT) return;
                    DraftKey key = new DraftKey(conflict.language(), conflict.tableId());
                    NameDraft draft = nameDrafts.get(key);
                    if (draft == null) return;
                    modals.push(UiModal.create(theme, font())
                            .title(Component.literal(conflict.tableId().toString()))
                            .message(Component.translatable(PREFIX + "name_conflict_compare", conflict.current(), draft.value()))
                            .confirm(Component.translatable(LootTrackingPreviewDialog.KEY + "keep_changes"), () -> {
                                nameDrafts.put(key, new NameDraft(draft.value(), conflict.current(), draft.legacyKey()));
                                populateName();
                            })
                            .cancel(Component.translatable("gui.cancel")));
                }).cancel(Component.translatable("gui.done")));
    }

    void populateName() {
        ResourceLocation id = selected.get();
        String value = "";
        Component hint = Component.empty();
        if (id != null && hasValidLanguageCode()) {
            DraftKey key = new DraftKey(currentLanguageCode(), id);
            NameDraft draft = nameDrafts.get(key);
            value = draft == null ? storedName(key) : draft.value();
            if (value.isEmpty()) hint = Component.literal(
                    ClientLootTableLanguageStore.resolve(id, currentLanguageCode()).value());
        }
        populatingName = true;
        nameBox.setValue(value);
        nameBox.setHint(hint);
        populatingName = false;
    }

    private void onNameInputChanged(String value) {
        ResourceLocation id = selected.get();
        if (populatingName || !hasValidLanguageCode() || id == null) return;
        DraftKey key = new DraftKey(currentLanguageCode(), id);
        String name = value.trim();
        NameDraft draft = nameDrafts.get(key);
        if (name.equals(storedName(key)) && pendingNameRequest == 0) {
            nameDrafts.remove(key);
        } else {
            nameDrafts.put(key, new NameDraft(name, draft == null ? storedName(key) : draft.expected(),
                    draft == null || !name.equals(draft.value()) ? "" : draft.legacyKey()));
        }
    }

    private String storedName(DraftKey key) {
        return ClientLootTableLanguageStore.serverTranslation(key.languageCode(), key.tableId());
    }

    private record NameDraft(String value, String expected, String legacyKey) {}

    private void initializeLanguageCodes() {
        this.languageCodes.clear();
        Minecraft.getInstance().getLanguageManager().getLanguages().keySet().stream().sorted().forEach(languageCodes::add);
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
    }

    private void selectLanguage(String languageCode) {
        this.selectedLanguageCode = languageCode;
        this.languageBox.setValue(languageCode);
        populateName();
    }

    private boolean hasValidLanguageCode() {
        return isValidLanguageCode(currentLanguageCode());
    }

    private String validLanguageCodeOrFallback() {
        return hasValidLanguageCode() ? currentLanguageCode() : this.selectedLanguageCode;
    }

    // 语言码口径与导入文件名推断共用同一份实现
    private static boolean isValidLanguageCode(String languageCode) {
        return LootTableNameEdit.isLanguage(languageCode);
    }

    private Component nameSourceLabel(ResourceLocation tableId) {
        var name = ClientLootTableLanguageStore.resolve(tableId, currentLanguageCode());
        return switch (name.source()) {
            case RESOURCE -> Component.translatable(PREFIX + "source.resource", name.sourcePackId());
            case SERVER -> Component.translatable(PREFIX + "source.server");
            case ENGLISH_RESOURCE -> Component.translatable(PREFIX + "source.fallback_resource", name.sourcePackId());
            case ENGLISH_SERVER -> Component.translatable(PREFIX + "source.fallback_server");
            case FALLBACK -> Component.translatable(PREFIX + "source.missing");
        };
    }

    void importJson() {
        String selectedPath = LootTableManagementActions.chooseImportFile();
        if (selectedPath == null) return;
        switch (LootTableManagementActions.prepareImport(Path.of(selectedPath), validLanguageCodeOrFallback())) {
            case LootTableManagementActions.ImportResult.Failed(var reason) -> {
                importStatus = importFailureMessage(reason);
                importStatusColor = IMPORT_STATUS_ERROR_COLOR;
            }
            case LootTableManagementActions.ImportResult.Ready(var parsed) -> openImport(parsed);
        }
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
     * 页面内未提交名称的复合键。
     */
    private record DraftKey(String languageCode, ResourceLocation tableId) {
    }

}
