package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.client.ui.kit.TextScroll;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncLootTableManagementPayload;
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
    private static final long TEXT_SCROLL_PAUSE_MILLIS = 1_200L;
    private static final long TEXT_SCROLL_PIXEL_MILLIS = 35L;
    private static final int IMPORT_STATUS_OK_COLOR = 0x78D66A;
    private static final int IMPORT_STATUS_ERROR_COLOR = 0xFF5555;

    private final Screen parent;
    /** 客户端动作层：协议组包、原生文件对话框与导入流程都在这里，Screen 只转调。 */
    private final LootTableManagementActions actions = new LootTableManagementActions();
    /** 列表视口与滚动几何；与内部语言选择窗口共用同一份实现。 */
    private final ListScrollState list = new ListScrollState(ROW_HEIGHT, 0xFF202020, 0xFF909090, 0xFFE0E0E0);
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
    private int importStatusColor = IMPORT_STATUS_OK_COLOR;

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

        this.actions.requestSnapshot();
        refreshSnapshot();
        syncControls();
    }

    // 窗口 resize 会经 rebuildWidgets -> init() 重建三个输入框；先取出未提交草稿再放回，避免静默丢输入
    @Override
    protected void repositionElements() {
        String searchDraft = this.searchBox != null ? this.searchBox.getValue() : "";
        String languageDraft = this.languageBox != null ? this.languageBox.getValue() : "";
        String nameDraft = this.nameBox != null ? this.nameBox.getValue() : "";
        super.repositionElements();
        if (this.searchBox != null) {
            this.searchBox.setValue(searchDraft);
        }
        if (this.languageBox != null) {
            this.languageBox.setValue(languageDraft);
        }
        if (this.nameBox != null) {
            this.populatingName = true;
            this.nameBox.setValue(nameDraft);
            this.populatingName = false;
        }
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

        syncListGeometry();
        for (int index = this.list.scrollRow(); index < this.list.visibleEndIndex(); index++) {
            renderTreeRow(graphics, this.visibleRows.get(index),
                    left + INNER_MARGIN, this.list.rowY(index), listWidth, mouseX, mouseY);
        }
        this.list.renderScrollbar(graphics);
        renderEmptyState(graphics, left, listWidth);
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

    // 筛选/搜索无匹配或服务端尚未下发条目时，在列表区居中给出本地化空态，避免看起来像界面损坏
    private void renderEmptyState(GuiGraphics graphics, int left, int listWidth) {
        if (!this.visibleRows.isEmpty()) return;
        boolean filtered = this.filter != Filter.ALL
                || (this.searchBox != null && !this.searchBox.getValue().isBlank());
        Component message = Component.translatable(filtered
                ? "screen.unsuspiciousblock.loot_table_management.empty_search"
                : "screen.unsuspiciousblock.loot_table_management.empty");
        graphics.drawCenteredString(this.font, message, left + INNER_MARGIN + listWidth / 2,
                listTop() + Math.max(0, listHeight() - this.font.lineHeight) / 2, 0xAAAAAA);
    }

    private void renderDetails(GuiGraphics graphics, int x, int top, int width) {
        SyncLootTableManagementPayload.Entry selected = selectedEntry();
        graphics.drawString(this.font,
                Component.translatable("screen.unsuspiciousblock.loot_table_management.details"),
                x, top + 32, 0xFFFFFF, false);
        if (!this.nameDrafts.isEmpty()) {
            Component draftStatus = Component.translatable(
                    "screen.unsuspiciousblock.loot_table_management.source.draft", this.nameDrafts.size());
            graphics.drawString(this.font, draftStatus,
                    x + width - this.font.width(draftStatus), top + 9, 0xFFAA00, false);
        }
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
                    x, warningY + 12, width, this.importStatusColor, true);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        syncListGeometry();
        if (button == 0 && !isOverTextBox(mouseX, mouseY)) this.setFocused(null);
        if (button == 0 && this.list.hitScrollbar(mouseX, mouseY) && this.list.maxScrollRow() > 0) {
            this.list.beginDrag(mouseY);
            return true;
        }
        if (button == 0 && this.list.contains(mouseX, mouseY)) {
            int index = this.list.rowIndexAt(mouseY);
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
        if (this.list.isDragging() && button == 0) {
            syncListGeometry();
            this.list.dragTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.list.isDragging()) {
            this.list.endDrag();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalDelta, double verticalDelta) {
        syncListGeometry();
        if (this.list.contains(mouseX, mouseY) && verticalDelta != 0.0) {
            int direction = verticalDelta > 0.0 ? -1 : 1;
            this.list.scrollByRows(direction * 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalDelta, verticalDelta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE
                && !(getFocused() instanceof EditBox) && !(getFocused() instanceof net.minecraft.client.gui.components.MultiLineEditBox)) {
            onClose(); return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
        // 行数变化后重新钳制偏移：同时按当前面板尺寸刷新视口，resize 后的首次重建也拿到正确容量
        syncListGeometry();
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

    // 语言码口径与导入文件名推断共用同一份实现
    private static boolean isValidLanguageCode(String languageCode) {
        return LootTableImportValidator.isValidLanguageCode(languageCode);
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

    private void openImportPreview(LootTableManagementActions.ImportPreview preview) {
        Component message = Component.translatable(
                "screen.unsuspiciousblock.loot_table_management.import_preview.message",
                preview.languageCode(), preview.added(), preview.updated(), preview.resourceSkipped(),
                preview.unmatched(), preview.invalid());
        Minecraft.getInstance().setScreen(new ConfirmScreen(confirmed -> {
            Minecraft.getInstance().setScreen(this);
            if (confirmed && !preview.entries().isEmpty()) {
                this.actions.submitImport(preview);
                this.importStatus = Component.translatable(
                        "screen.unsuspiciousblock.loot_table_management.import_submitted",
                        preview.entries().size());
                this.importStatusColor = IMPORT_STATUS_OK_COLOR;
            }
        }, Component.translatable("screen.unsuspiciousblock.loot_table_management.import_preview.title"), message));
    }

    // 列表视口随面板尺寸变化：把矩形与当前行数交给共用几何组件，它会重新钳制偏移
    private void syncListGeometry() {
        this.list.setViewport(panelLeft() + INNER_MARGIN, listTop(), listWidth(), listHeight());
        this.list.setRowCount(this.visibleRows.size());
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

    private static String readableName(ResourceLocation id) {
        String path = id.getPath();
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        return path.replace('_', ' ').replace('-', ' ');
    }

    private void renderOverflowText(GuiGraphics graphics, String value, int x, int y,
                                    int maxWidth, int color, boolean scrolling) {
        int textWidth = this.font.width(value);
        if (textWidth <= maxWidth) {
            graphics.drawString(this.font, value, x, y, color, false);
            return;
        }
        if (!scrolling) {
            graphics.drawString(this.font, TextScroll.trimToWidth(this.font, value, maxWidth), x, y, color, false);
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

        private final LootTableManagementScreen parent;
        private final List<String> languageCodes;
        /** 与外层管理页共用同一份列表滚动几何。 */
        private final ListScrollState list = new ListScrollState(ROW_HEIGHT, 0xFF202020, 0xFF909090, 0xFF909090);
        private String selectedLanguageCode;

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
            syncListGeometry();
            int selectedIndex = this.languageCodes.indexOf(this.selectedLanguageCode);
            if (selectedIndex >= 0) {
                this.list.setScrollRow(selectedIndex - this.list.visibleRowCapacity() / 2);
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

            syncListGeometry();
            int end = this.list.visibleEndIndex();
            for (int index = this.list.scrollRow(); index < end; index++) {
                int y = this.list.rowY(index);
                String code = this.languageCodes.get(index);
                boolean selected = code.equals(this.selectedLanguageCode);
                boolean hovered = mouseX >= left && mouseX < left + width - SCROLLBAR_WIDTH
                        && mouseY >= y && mouseY < y + ROW_HEIGHT;
                if (selected || hovered) {
                    graphics.fill(left, y, left + width - SCROLLBAR_WIDTH, y + ROW_HEIGHT,
                            selected ? 0xFF4C6278 : 0xFF353535);
                }
                graphics.drawString(this.font,
                        TextScroll.trimToWidth(this.font, languageOptionLabel(code).getString(),
                                width - SCROLLBAR_WIDTH - 8),
                        left + 4, y + 6, selected ? 0xFFFFFF : 0xD0D0D0, false);
            }
            this.list.renderScrollbar(graphics);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            syncListGeometry();
            if (button == 0 && this.list.containsRowArea(mouseX, mouseY)) {
                int index = this.list.rowIndexAt(mouseY);
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
            syncListGeometry();
            if (this.list.containsRowArea(mouseX, mouseY) && verticalDelta != 0.0) {
                this.list.scrollByRows(verticalDelta > 0.0 ? -3 : 3);
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

        @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) { onClose(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // 语言列表的视口随窗口尺寸变化：几何统一交给共用几何组件
        private void syncListGeometry() {
            this.list.setViewport(listLeft(), LIST_TOP, listWidth(), listHeight());
            this.list.setRowCount(this.languageCodes.size());
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

    }

    /**
     * 列表滚动几何——管理页与内部语言选择窗口共用同一份"视口 + 行高 + 偏移"计算与滚动条绘制。
     * <p>
     * 两处原先各写一套 isOverList/maxScrollRow/clampScroll/renderScrollbar，任何一处改口径都会失配。
     * kit 的 {@code UiScrollView} 面向 UiControl 控件树并自带 UiControlStyle，而本页直接按行绘制文本，
     * 因此抽这个私有组件而不是套 kit；颜色与行高由调用方给出，以保留各自的视觉。
     */
    private static final class ListScrollState {
        private static final int MIN_THUMB_HEIGHT = 12;

        private final int rowHeight;
        private final int trackColor;
        private final int thumbColor;
        private final int draggingThumbColor;

        private int x;
        private int y;
        private int width;
        private int height;
        private int rowCount;
        private int scrollRow;
        private boolean dragging;

        ListScrollState(int rowHeight, int trackColor, int thumbColor, int draggingThumbColor) {
            this.rowHeight = Math.max(1, rowHeight);
            this.trackColor = trackColor;
            this.thumbColor = thumbColor;
            this.draggingThumbColor = draggingThumbColor;
        }

        // 视口矩形与行数都会重新钳制偏移：内容变短后偏移不会停在越界位置
        void setViewport(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.scrollRow = clamp(this.scrollRow);
        }

        void setRowCount(int rowCount) {
            this.rowCount = Math.max(0, rowCount);
            this.scrollRow = clamp(this.scrollRow);
        }

        int scrollRow() { return this.scrollRow; }

        void setScrollRow(int value) { this.scrollRow = clamp(value); }

        void scrollByRows(int delta) { setScrollRow(this.scrollRow + delta); }

        int visibleRowCapacity() { return Math.max(1, this.height / this.rowHeight); }

        int maxScrollRow() { return Math.max(0, this.rowCount - visibleRowCapacity()); }

        int clamp(int value) { return Math.clamp(value, 0, maxScrollRow()); }

        int visibleEndIndex() { return Math.min(this.rowCount, this.scrollRow + visibleRowCapacity()); }

        int rowIndexAt(double mouseY) { return this.scrollRow + (int) ((mouseY - this.y) / this.rowHeight); }

        int rowY(int index) { return this.y + (index - this.scrollRow) * this.rowHeight; }

        // 整块列表区域（含滚动条列）：管理页的滚轮与列表判定沿用它
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.width
                    && mouseY >= this.y && mouseY < this.y + this.height;
        }

        // 只算行区域（排除滚动条列）：语言选择窗口的点击口径
        boolean containsRowArea(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.width - SCROLLBAR_WIDTH
                    && mouseY >= this.y && mouseY < this.y + this.height;
        }

        int scrollbarX() { return this.x + this.width - SCROLLBAR_WIDTH; }

        boolean hitScrollbar(double mouseX, double mouseY) {
            return mouseX >= scrollbarX() && mouseX < scrollbarX() + SCROLLBAR_WIDTH
                    && mouseY >= this.y && mouseY < this.y + this.height;
        }

        boolean isDragging() { return this.dragging; }

        // 按下滚动条进入拖动：滑块中心先对齐指针，之后按位移映射偏移
        void beginDrag(double mouseY) {
            this.dragging = true;
            dragTo(mouseY);
        }

        void dragTo(double mouseY) {
            int maxScroll = maxScrollRow();
            if (maxScroll <= 0) {
                this.scrollRow = 0;
                return;
            }
            int travel = this.height - thumbHeight();
            double relative = mouseY - this.y - thumbHeight() / 2.0;
            this.scrollRow = clamp((int) Math.round(relative * maxScroll / Math.max(1, travel)));
        }

        void endDrag() { this.dragging = false; }

        int thumbHeight() {
            if (this.rowCount == 0) return this.height;
            return Math.max(MIN_THUMB_HEIGHT, this.height * visibleRowCapacity() / this.rowCount);
        }

        void renderScrollbar(GuiGraphics graphics) {
            int trackX = scrollbarX();
            graphics.fill(trackX, this.y, trackX + SCROLLBAR_WIDTH, this.y + this.height, this.trackColor);
            int maxScroll = maxScrollRow();
            if (maxScroll <= 0) return;
            int thumbHeight = thumbHeight();
            int thumbY = this.y + (this.height - thumbHeight) * this.scrollRow / maxScroll;
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight,
                    this.dragging ? this.draggingThumbColor : this.thumbColor);
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
