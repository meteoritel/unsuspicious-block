package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateLootTableTranslationsPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateTrackedLootTablePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncLootTableManagementPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final int LANGUAGE_BUTTON_WIDTH = 20;
    private static final int LANGUAGE_CONTROL_GAP = 3;
    private static final ResourceLocation LANGUAGE_ICON = ResourceLocation.withDefaultNamespace("icon/language");
    private static final String LANGUAGE_CODE_PATTERN = "[a-z0-9_-]{2,16}";
    private static final long TEXT_SCROLL_PAUSE_MILLIS = 1_200L;
    private static final long TEXT_SCROLL_PIXEL_MILLIS = 35L;
    private static final long MAX_IMPORT_FILE_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_IMPORT_ENTRIES = 4096;

    private final Screen parent;
    private final List<SyncLootTableManagementPayload.Entry> filteredEntries = new ArrayList<>();
    private final List<TreeRow> visibleRows = new ArrayList<>();
    private final Set<String> expandedPaths = new HashSet<>();
    private final List<String> languageCodes = new ArrayList<>();
    private final Map<DraftKey, String> nameDrafts = new LinkedHashMap<>();
    private EditBox searchBox;
    private EditBox languageBox;
    private EditBox nameBox;
    private Button filterButton;
    private Button languageButton;
    private Button applyButton;
    private Button saveNameButton;
    private Button importButton;
    private Filter filter = Filter.ALL;
    private int scrollRow;
    private boolean draggingScrollbar;
    private boolean populatingName;
    private long observedRevision = -1L;
    private long textScrollStartedAt;
    private String selectedLanguageCode = "";
    @Nullable
    private ResourceLocation selectedTableId;
    @Nullable
    private ResourceLocation hoveredTableId;
    @Nullable
    private Component importStatus;

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
        int nameY = actionY - 28;
        int languageY = nameY - 36;
        this.textScrollStartedAt = System.currentTimeMillis();

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

        initializeLanguageCodes();
        this.languageBox = new EditBox(this.font, detailsX, languageY,
                detailsWidth - LANGUAGE_BUTTON_WIDTH - LANGUAGE_CONTROL_GAP, 20,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.language"));
        this.languageBox.setMaxLength(16);
        this.languageBox.setValue(this.selectedLanguageCode);
        this.languageBox.setResponder(this::onLanguageInputChanged);
        this.addRenderableWidget(this.languageBox);

        this.languageButton = this.addRenderableWidget(Button.builder(Component.empty(), button ->
                        Minecraft.getInstance().setScreen(new LanguageSelectionScreen(
                                this, this.languageCodes, validLanguageCodeOrFallback())))
                .bounds(detailsX + detailsWidth - LANGUAGE_BUTTON_WIDTH, languageY,
                        LANGUAGE_BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable(
                        "screen.unsuspiciousblock.loot_table_management.choose_language")))
                .build());

        this.nameBox = new EditBox(this.font, detailsX, nameY, detailsWidth, 20,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.localized_name"));
        this.nameBox.setMaxLength(128);
        this.nameBox.setResponder(this::onNameInputChanged);
        this.addRenderableWidget(this.nameBox);

        this.applyButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> applySelection())
                .bounds(detailsX, actionY, actionWidth, 20).build());
        this.saveNameButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.unsuspiciousblock.loot_table_management.save_name"),
                        button -> saveName())
                .bounds(detailsX + actionWidth + 5, actionY, detailsWidth - actionWidth - 5, 20).build());
        this.importButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.unsuspiciousblock.loot_table_management.import_json"),
                        button -> importJson())
                .bounds(detailsX, backY, actionWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(detailsX + actionWidth + 5, backY, detailsWidth - actionWidth - 5, 20).build());

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
        graphics.blitSprite(LANGUAGE_ICON, this.languageButton.getX() + 2,
                this.languageButton.getY() + 2, 16, 16);
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
        renderOverflowText(graphics, row.label(), contentX, y + 6, availableWidth,
                textColor, hovered || selected);
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
            renderOverflowText(graphics, selected.tableId().toString(), x, top + 51,
                    width, 0xB8B8B8, true);
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
        int warningY = top + 80;
        if (!LootTableManagementClientState.canEdit()) {
            renderOverflowText(graphics, Component.translatable(
                            "screen.unsuspiciousblock.loot_table_management.read_only").getString(),
                    x, warningY, width, 0xFFAA00, true);
        } else if (!hasValidLanguageCode()) {
            renderOverflowText(graphics, Component.translatable(
                            "screen.unsuspiciousblock.loot_table_management.invalid_language_code").getString(),
                    x, warningY, width, 0xFF5555, true);
        } else if (requiresEnglishName()) {
            renderOverflowText(graphics, Component.translatable(
                            "screen.unsuspiciousblock.loot_table_management.english_name_required").getString(),
                    x, warningY, width, 0xFFAA00, true);
        } else if (selected != null) {
            renderOverflowText(graphics, nameSourceLabel(selected.tableId()).getString(),
                    x, warningY, width, 0xAAAAAA, true);
        }
        if (this.importStatus != null) {
            renderOverflowText(graphics, this.importStatus.getString(),
                    x, warningY + 12, width, 0x78D66A, true);
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
                    this.textScrollStartedAt = System.currentTimeMillis();
                    populateName();
                    syncControls();
                }
                return true;
            }
        }

        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        // 原版在按钮回调结束后设置焦点；筛选和追踪切换是即时动作，不保留键盘焦点
        if (this.getFocused() == this.filterButton || this.getFocused() == this.languageButton
                || this.getFocused() == this.applyButton) {
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
            reconcileDrafts();
            rebuildTree();
        }
    }

    private void rebuildTree() {
        if (this.searchBox == null) return;
        String query = this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
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
        Services.NETWORK.sendToServer(new UpdateTrackedLootTablePayload(entry.tableId(), !entry.tracked()));
    }

    private void saveName() {
        if (!LootTableManagementClientState.canEdit() || this.nameDrafts.isEmpty()
                || hasInvalidEnglishDraft()) return;
        List<UpdateLootTableTranslationsPayload.Entry> entries = this.nameDrafts.entrySet().stream()
                .map(entry -> new UpdateLootTableTranslationsPayload.Entry(
                        entry.getKey().tableId(), entry.getKey().languageCode(), entry.getValue()))
                .toList();
        Services.NETWORK.sendToServer(new UpdateLootTableTranslationsPayload(entries));
    }

    private void populateName() {
        if (this.nameBox == null) return;
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
                    hint = Component.literal(fallback).withStyle(ChatFormatting.DARK_GRAY);
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

    private void syncControls() {
        if (this.applyButton == null || this.saveNameButton == null || this.importButton == null
                || this.languageButton == null || this.languageBox == null || this.nameBox == null) return;
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        boolean hasSelectionPermission = entry != null && LootTableManagementClientState.canEdit();
        boolean validLanguage = hasValidLanguageCode();
        this.applyButton.active = hasSelectionPermission;
        this.saveNameButton.active = LootTableManagementClientState.canEdit()
                && !this.nameDrafts.isEmpty() && !hasInvalidEnglishDraft();
        this.languageButton.active = hasSelectionPermission;
        this.languageBox.active = hasSelectionPermission;
        this.nameBox.active = hasSelectionPermission && validLanguage
                && resourceName(new DraftKey(currentLanguageCode(), entry.tableId())) == null;
        this.importButton.active = LootTableManagementClientState.canEdit();
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
        return this.languageBox == null ? this.selectedLanguageCode : this.languageBox.getValue().trim();
    }

    private void onLanguageInputChanged(String value) {
        boolean valid = isValidLanguageCode(value.trim());
        this.languageBox.setTextColor(valid ? 0xE0E0E0 : 0xFF5555);
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

    private static boolean isValidLanguageCode(String languageCode) {
        return languageCode != null && languageCode.matches(LANGUAGE_CODE_PATTERN);
    }

    private boolean requiresEnglishName() {
        SyncLootTableManagementPayload.Entry entry = selectedEntry();
        if (entry == null || !hasValidLanguageCode() || "en_us".equals(currentLanguageCode())
                || this.nameBox.getValue().trim().isEmpty()) {
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
            return Component.translatable("screen.unsuspiciousblock.loot_table_management.source.draft",
                    this.nameDrafts.size());
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

    private void importJson() {
        String selectedPath;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.json")).flip();
            selectedPath = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("screen.unsuspiciousblock.loot_table_management.import_dialog").getString(),
                    Services.PLATFORM.getGameDir().toAbsolutePath().toString(), filters, "JSON", false);
        }
        if (selectedPath == null) return;
        try {
            ImportPreview preview = buildImportPreview(Path.of(selectedPath));
            openImportPreview(preview);
        } catch (IOException | RuntimeException exception) {
            this.importStatus = Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.import_failed");
        }
    }

    private ImportPreview buildImportPreview(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_IMPORT_FILE_BYTES) {
            throw new IOException("Invalid or oversized JSON file");
        }
        JsonElement root;
        try (Reader reader = Files.newBufferedReader(file)) {
            root = JsonParser.parseReader(reader);
        }
        if (!root.isJsonObject()) throw new IOException("Language JSON root must be an object");

        String languageCode = inferImportLanguage(file);
        Map<String, ResourceLocation> tableByKey = new LinkedHashMap<>();
        Set<String> ambiguousKeys = new HashSet<>();
        for (SyncLootTableManagementPayload.Entry entry : LootTableManagementClientState.entries()) {
            String key = LootTableNames.createTranslationKey(entry.tableId());
            if (tableByKey.putIfAbsent(key, entry.tableId()) != null) ambiguousKeys.add(key);
        }

        List<UpdateLootTableTranslationsPayload.Entry> accepted = new ArrayList<>();
        int added = 0;
        int updated = 0;
        int resourceSkipped = 0;
        int unmatched = 0;
        int invalid = 0;
        JsonObject object = root.getAsJsonObject();
        if (object.size() > MAX_IMPORT_ENTRIES) throw new IOException("Too many JSON entries");
        for (Map.Entry<String, JsonElement> jsonEntry : object.entrySet()) {
            ResourceLocation tableId = tableByKey.get(jsonEntry.getKey());
            if (tableId == null || ambiguousKeys.contains(jsonEntry.getKey())) {
                unmatched++;
                continue;
            }
            if (!jsonEntry.getValue().isJsonPrimitive()
                    || !jsonEntry.getValue().getAsJsonPrimitive().isString()) {
                invalid++;
                continue;
            }
            String value = jsonEntry.getValue().getAsString().trim();
            if (value.isEmpty() || value.length() > 128) {
                invalid++;
                continue;
            }
            DraftKey draftKey = new DraftKey(languageCode, tableId);
            if (resourceName(draftKey) != null) {
                resourceSkipped++;
                continue;
            }
            if (storedName(draftKey).isEmpty()) added++;
            else updated++;
            accepted.add(new UpdateLootTableTranslationsPayload.Entry(tableId, languageCode, value));
        }
        return new ImportPreview(languageCode, List.copyOf(accepted),
                added, updated, resourceSkipped, unmatched, invalid);
    }

    private String inferImportLanguage(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int extension = fileName.lastIndexOf('.');
        String inferred = extension > 0 ? fileName.substring(0, extension) : fileName;
        return isValidLanguageCode(inferred) ? inferred : validLanguageCodeOrFallback();
    }

    private void openImportPreview(ImportPreview preview) {
        Component message = Component.translatable(
                "screen.unsuspiciousblock.loot_table_management.import_preview.message",
                preview.languageCode(), preview.added(), preview.updated(), preview.resourceSkipped(),
                preview.unmatched(), preview.invalid());
        Minecraft.getInstance().setScreen(new ConfirmScreen(confirmed -> {
            Minecraft.getInstance().setScreen(this);
            if (confirmed && !preview.entries().isEmpty()) {
                Services.NETWORK.sendToServer(new UpdateLootTableTranslationsPayload(preview.entries()));
                this.importStatus = Component.translatable(
                        "screen.unsuspiciousblock.loot_table_management.import_submitted",
                        preview.entries().size());
            }
        }, Component.translatable("screen.unsuspiciousblock.loot_table_management.import_preview.title"), message));
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

    private void renderOverflowText(GuiGraphics graphics, String value, int x, int y,
                                    int maxWidth, int color, boolean scrolling) {
        int textWidth = this.font.width(value);
        if (textWidth <= maxWidth) {
            graphics.drawString(this.font, value, x, y, color, false);
            return;
        }
        if (!scrolling) {
            graphics.drawString(this.font, trimToWidth(value, maxWidth), x, y, color, false);
            return;
        }

        graphics.enableScissor(x, y, x + maxWidth, y + this.font.lineHeight);
        graphics.drawString(this.font, value,
                x - scrollingTextOffset(textWidth - maxWidth), y, color, false);
        graphics.disableScissor();
    }

    private int scrollingTextOffset(int overflow) {
        long travelMillis = Math.max(1L, overflow * TEXT_SCROLL_PIXEL_MILLIS);
        long cycleMillis = TEXT_SCROLL_PAUSE_MILLIS * 2L + travelMillis * 2L;
        long elapsed = Math.max(0L, System.currentTimeMillis() - this.textScrollStartedAt) % cycleMillis;
        if (elapsed < TEXT_SCROLL_PAUSE_MILLIS) return 0;
        elapsed -= TEXT_SCROLL_PAUSE_MILLIS;
        if (elapsed < travelMillis) {
            return (int) Math.min(overflow, elapsed / TEXT_SCROLL_PIXEL_MILLIS);
        }
        elapsed -= travelMillis;
        if (elapsed < TEXT_SCROLL_PAUSE_MILLIS) return overflow;
        elapsed -= TEXT_SCROLL_PAUSE_MILLIS;
        return overflow - (int) Math.min(overflow, elapsed / TEXT_SCROLL_PIXEL_MILLIS);
    }

    /**
     * 独立的翻译语言选择窗口，不改变客户端当前显示语言。
     */
    private static final class LanguageSelectionScreen extends Screen {
        private static final int PANEL_MAX_WIDTH = 320;
        private static final int PANEL_MARGIN = 20;
        private static final int LIST_TOP = 36;
        private static final int LIST_BOTTOM_MARGIN = 38;
        private static final int ROW_HEIGHT = 20;
        private static final int SCROLLBAR_WIDTH = 5;

        private final LootTableManagementScreen parent;
        private final List<String> languageCodes;
        private String selectedLanguageCode;
        private int scrollRow;

        private LanguageSelectionScreen(LootTableManagementScreen parent, List<String> languageCodes,
                                        String selectedLanguageCode) {
            super(Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.language_select"));
            this.parent = parent;
            this.languageCodes = List.copyOf(languageCodes);
            this.selectedLanguageCode = selectedLanguageCode;
        }

        @Override
        protected void init() {
            int selectedIndex = this.languageCodes.indexOf(this.selectedLanguageCode);
            if (selectedIndex >= 0) {
                this.scrollRow = clampScroll(selectedIndex - visibleRowCapacity() / 2);
            }
            this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> confirm())
                    .bounds((this.width - 150) / 2, this.height - 28, 150, 20)
                    .build());
        }

        @Override
        public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

            int left = listLeft();
            int width = listWidth();
            int bottom = listBottom();
            graphics.fill(left - 1, LIST_TOP - 1, left + width + 1, bottom + 1, 0xFF909090);
            graphics.fill(left, LIST_TOP, left + width, bottom, 0xFF181818);

            int end = Math.min(this.languageCodes.size(), this.scrollRow + visibleRowCapacity());
            for (int index = this.scrollRow; index < end; index++) {
                int y = LIST_TOP + (index - this.scrollRow) * ROW_HEIGHT;
                String code = this.languageCodes.get(index);
                boolean selected = code.equals(this.selectedLanguageCode);
                boolean hovered = mouseX >= left && mouseX < left + width - SCROLLBAR_WIDTH
                        && mouseY >= y && mouseY < y + ROW_HEIGHT;
                if (selected || hovered) {
                    graphics.fill(left, y, left + width - SCROLLBAR_WIDTH, y + ROW_HEIGHT,
                            selected ? 0xFF4C6278 : 0xFF353535);
                }
                graphics.drawString(this.font,
                        trimToWidth(this.font, languageOptionLabel(code).getString(),
                                width - SCROLLBAR_WIDTH - 8),
                        left + 4, y + 6, selected ? 0xFFFFFF : 0xD0D0D0, false);
            }
            renderScrollbar(graphics, left + width - SCROLLBAR_WIDTH);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isOverList(mouseX, mouseY)) {
                int index = this.scrollRow + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
                if (index >= 0 && index < this.languageCodes.size()) {
                    this.selectedLanguageCode = this.languageCodes.get(index);
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalDelta,
                                     double verticalDelta) {
            if (isOverList(mouseX, mouseY) && verticalDelta != 0.0) {
                this.scrollRow = clampScroll(this.scrollRow + (verticalDelta > 0.0 ? -3 : 3));
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalDelta, verticalDelta);
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreen(this.parent);
        }

        private void confirm() {
            this.parent.selectLanguage(this.selectedLanguageCode);
            Minecraft.getInstance().setScreen(this.parent);
        }

        private void renderScrollbar(GuiGraphics graphics, int x) {
            int height = listHeight();
            graphics.fill(x, LIST_TOP, x + SCROLLBAR_WIDTH, LIST_TOP + height, 0xFF202020);
            int maxScroll = maxScrollRow();
            if (maxScroll <= 0) return;
            int thumbHeight = Math.max(12, height * visibleRowCapacity() / this.languageCodes.size());
            int thumbY = LIST_TOP + (height - thumbHeight) * this.scrollRow / maxScroll;
            graphics.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xFF909090);
        }

        private boolean isOverList(double mouseX, double mouseY) {
            return mouseX >= listLeft() && mouseX < listLeft() + listWidth() - SCROLLBAR_WIDTH
                    && mouseY >= LIST_TOP && mouseY < listBottom();
        }

        private int listLeft() {
            return (this.width - listWidth()) / 2;
        }

        private int listWidth() {
            return Math.min(PANEL_MAX_WIDTH, this.width - PANEL_MARGIN * 2);
        }

        private int listBottom() {
            return Math.max(LIST_TOP + ROW_HEIGHT, this.height - LIST_BOTTOM_MARGIN);
        }

        private int listHeight() {
            return listBottom() - LIST_TOP;
        }

        private int visibleRowCapacity() {
            return Math.max(1, listHeight() / ROW_HEIGHT);
        }

        private int maxScrollRow() {
            return Math.max(0, this.languageCodes.size() - visibleRowCapacity());
        }

        private int clampScroll(int value) {
            return Math.max(0, Math.min(maxScrollRow(), value));
        }

        private static String trimToWidth(net.minecraft.client.gui.Font font, String value, int maxWidth) {
            if (font.width(value) <= maxWidth) return value;
            String suffix = "...";
            if (maxWidth <= font.width(suffix)) {
                return font.plainSubstrByWidth(value, Math.max(0, maxWidth));
            }
            return font.plainSubstrByWidth(value, maxWidth - font.width(suffix)) + suffix;
        }
    }

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

    /** JSON 导入预览及其可提交条目。 */
    private record ImportPreview(String languageCode, List<UpdateLootTableTranslationsPayload.Entry> entries,
                                 int added, int updated, int resourceSkipped, int unmatched, int invalid) {
    }

    private record TreeRow(String label, String key, int depth, boolean expandable,
                           boolean expanded, @Nullable SyncLootTableManagementPayload.Entry entry) {
    }

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
