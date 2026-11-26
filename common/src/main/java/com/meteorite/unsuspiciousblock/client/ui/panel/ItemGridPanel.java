package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiControlGroup;
import com.meteorite.meteosuikit.api.control.UiPaginationState;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputContainer;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.layout.UiGridMetrics;
import com.meteorite.meteosuikit.api.render.UiRenderLayers;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.state.ScenarioTableSnapshot;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalTooltipBuilder;
import com.meteorite.unsuspiciousblock.client.ui.text.PanelTextMetrics;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalControls;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalTextColors;
import com.meteorite.unsuspiciousblock.client.ui.theme.ScenarioAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalRichTooltip;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalTooltipSpec;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.DeclaredChance;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.FunctionObservationSummary;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ProbabilityFormat;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 右侧物品网格面板 —— 渲染物品图标、名称、概率与数量角标，补充信息通过 tooltip 展示
 */
public final class ItemGridPanel implements PagePanel, UiInputContainer {
    private static final UiGridMetrics GRID = new UiGridMetrics(JournalLayout.GRID_CELLS_PER_ROW,
            JournalLayout.GRID_CELL_WIDTH, JournalLayout.GRID_CELL_HEIGHT, JournalLayout.GRID_COLUMN_GAP, 0);
    private static final String TAG_BACK_TOOLTIP = "screen.unsuspiciousblock.archaeology_journal.tag_group_back_tooltip";
    private final UiControlGroup controls = new UiControlGroup();
    private boolean controlsDirty = true;
    private Runnable onNavigate = () -> {
    };
    private Consumer<SimulationAssistTarget> onRecommend = target -> {
    };

    public void setActions(Runnable navigate, Consumer<SimulationAssistTarget> recommend) {
        onNavigate = navigate;
        onRecommend = recommend;
    }

    @Override
    public UiChildren<UiControl> inputChildren() {
        return controls.inputChildren();
    }

    @Override
    public void prepareUi() {
        if (!controlsDirty) {
            return;
        }
        controlsDirty = false;
        controls.beginUpdate();
        for (GridCell cell : visibleCells) {
            switch (cell.content()) {
                case TagBack ignored -> configureNavigation(cell, Component.translatable(TAG_BACK_TOOLTIP),
                        () -> openTagFromStart(null));
                case TagGroup group -> {
                    if (group.unlocked()) {
                        configureNavigation(cell, Component.literal(group.id().toString()),
                                () -> openTagFromStart(group.id()));
                    }
                }
                case ChildTableEntry child -> {
                    if (child.unlocked()) {
                        configureNavigation(cell, child.displayName(), () -> navigationTarget = child.tableId());
                        configureRecommendation(cell, "recommend:" + child.tableId(),
                                () -> onRecommend.accept(SimulationAssistTarget.childTable(child.tableId())));
                    }
                }
                case GridItem item -> {
                    if (item.unlocked() && item.probability() instanceof Probability.NeedsCondition) {
                        String storedKey = item.signature().toStoredKey();
                        configureRecommendation(cell, "recommend:item:" + storedKey,
                                () -> onRecommend.accept(SimulationAssistTarget.item(storedKey)));
                    }
                }
            }
        }
        controls.endUpdate();
    }

    private void configureNavigation(GridCell cell, Component label, Runnable select) {
        configureHotspot(cell.key(), cell.bounds(), label, () -> {
            select.run();
            rebuildVisibleCells();
            resetHoverState();
            onNavigate.run();
        });
    }

    private void configureRecommendation(GridCell cell, String key, Runnable recommend) {
        configureHotspot(key, recommendationBounds(cell), ScenarioSession.text("recommend.title"), recommend);
    }

    private void configureHotspot(String key, UiRect bounds, Component label, Runnable action) {
        UiControl control = controls.obtain(key);
        control.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        JournalControls.configureHotspot(control, label, action);
    }

    public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
        controls.render(graphics, font, pointer);
    }

    @Override
    public UiPointerHit hitTest(double x, double y) {
        GridCell cell = cellAt(x, y);
        if (cell == null) return null;
        UiPointerHit hit = controls.hitTest(x, y);
        if (hit == null) hit = UiPointerHit.leaf(this, x, y);
        ItemStack stack = cell.content() instanceof GridItem item && item.unlocked()
                ? item.stack() : ItemStack.EMPTY;
        Object identity = stack.isEmpty() ? cell.key() : stack;
        hit = hit.within(this).withRow(this, cell.index(), identity, cell.bounds());
        return switch (cell.content()) {
            case GridItem item -> {
                if (item.unlocked() && item.probability() instanceof Probability.NeedsCondition
                        && recommendationBounds(cell).contains(x, y)) {
                    Component name = item.displayName() != null ? item.displayName() : stack.getHoverName();
                    yield JournalRichTooltip.attach(hit, JournalTooltipBuilder.buildRecommendRich(name));
                }
                yield JournalRichTooltip.attach(hit, JournalTooltipBuilder.buildRich(tooltipData(item, stack)));
            }
            case TagBack(ResourceLocation tagId) ->
                    JournalRichTooltip.attach(hit, JournalTooltipBuilder.buildTagBackRich(tagId));
            case TagGroup group -> JournalRichTooltip.attach(hit, tagTooltipSpec(group));
            case ChildTableEntry child -> JournalRichTooltip.attach(hit,
                    childTableTooltipSpec(child, recommendationBounds(cell).contains(x, y)));
        };
    }

    /**
     * 网格内容按业务类型区分，返回入口保留所属 tag。
     */
    private sealed interface GridContent permits GridItem, TagGroup, ChildTableEntry, TagBack {
    }

    /**
     * tag 成员页的返回入口。
     */
    private record TagBack(ResourceLocation tagId) implements GridContent {
    }

    /**
     * 几何、业务身份及内容来自同一个单元投影。
     */
    private record GridCell(int index, UiRect bounds, GridContent content) {
        String key() {
            return switch (content) {
                case TagBack back -> "back:" + back.tagId();
                case TagGroup tag -> "tag:" + tag.id();
                case ChildTableEntry child -> "table:" + child.tableId();
                case GridItem item -> "item:" + item.signature().toStoredKey();
            };
        }
    }

    private void rebuildVisibleCells() {
        List<GridContent> pageContents = new ArrayList<>();
        if (activeTag != null) {
            TagGroup group = tagGroups.get(activeTag);
            pageContents.add(new TagBack(activeTag));
            if (group != null) {
                int from = getPage() * TAG_GROUP_MEMBERS_PER_PAGE;
                int to = Math.min(group.members().size(), from + TAG_GROUP_MEMBERS_PER_PAGE);
                pageContents.addAll(group.members().subList(from, to));
            }
        } else {
            List<GridContent> allContents = new ArrayList<>(tagGroupView);
            allContents.addAll(childTables);
            allContents.addAll(directItems);
            int from = getPage() * JournalLayout.GRID_ITEMS_PER_PAGE;
            int to = Math.min(allContents.size(), from + JournalLayout.GRID_ITEMS_PER_PAGE);
            pageContents.addAll(allContents.subList(from, to));
        }
        List<GridCell> cells = new ArrayList<>(pageContents.size());
        for (int index = 0; index < pageContents.size(); index++) {
            cells.add(new GridCell(index, cellBounds(index), pageContents.get(index)));
        }
        visibleCells = List.copyOf(cells);
    }

    private UiRect cellBounds(int index) {
        UiRect r = GRID.cellBounds(index);
        return new UiRect(layout.rightPageX() + JournalLayout.GRID_LEFT_PAD + r.x(),
                layout.rightPageY() + JournalLayout.GRID_TOP + r.y(), r.width(), r.height());
    }

    private @Nullable GridCell cellAt(double x, double y) {
        int index = GRID.indexAt(x - layout.rightPageX() - JournalLayout.GRID_LEFT_PAD,
                y - layout.rightPageY() - JournalLayout.GRID_TOP, visibleCells.size());
        return index < 0 ? null : visibleCells.get(index);
    }

    private static UiRect recommendationBounds(GridCell cell) {
        UiRect r = cell.bounds();
        return new UiRect(r.right() - 13, r.y() + 1, 12, 13);
    }

    private void openTagFromStart(@Nullable ResourceLocation tag) {
        activeTag = tag;
        pagination.reset();
    }

    private static final int ICON_SIZE = 18;            // 略放大的物品图标尺寸
    private static final int ICON_TOP = 3;              // 图标距格子顶部偏移
    private static final int NAME_Y_OFFSET = 25;        // 物品名文字 y 偏移
    private static final int PROB_Y_OFFSET = 37;        // 概率文字 y 偏移
    private static final int CORNER_BADGE_INSET = 2;
    private static final int CORNER_BADGE_GAP = 2;
    // 物品名/数量角标颜色
    private static final int NAME_COLOR = JournalTextColors.NAME;
    private static final int PROB_COLOR = JournalTextColors.PROB_DEFAULT;
    private static final int PROB_COLOR_PROBABILISTIC = JournalTextColors.PROB_PROBABILISTIC;
    private static final int PROB_COLOR_RUNTIME = JournalTextColors.PROB_RUNTIME;
    private static final int PROB_COLOR_UNKNOWN = JournalTextColors.PROB_UNKNOWN;
    private static final int PENDING_COLOR = JournalTextColors.PROB_UNKNOWN;
    private static final int BADGE_COLOR_NORMAL = JournalPalette.ITEM_COUNT_NORMAL;
    private static final int BADGE_COLOR_ABBR = JournalPalette.ITEM_COUNT_ABBR;
    private static final int TAG_GROUP_TEXT_COLOR = JournalTextColors.COLLECTION;
    private static final int TAG_GROUP_MEMBERS_PER_PAGE = JournalLayout.GRID_ITEMS_PER_PAGE - 1;

    private final List<GridItem> items = new ArrayList<>();
    private final List<GridItem> directItems = new ArrayList<>();
    private final List<ChildTableEntry> childTables = new ArrayList<>();
    private Map<String, ScenarioTableSnapshot.Target> targets = Map.of();
    private final LinkedHashMap<ResourceLocation, TagGroup> tagGroups = new LinkedHashMap<>();
    private JournalBookBackground.BookLayout layout;
    private final UiPaginationState pagination = new UiPaginationState(this::computePageCount);
    @Nullable
    private ResourceLocation activeTag;
    @Nullable
    private ResourceLocation navigationTarget;
    // 每个可视格子的滚动文字状态（物品名走马灯），按 visualIndex 索引
    private final int[] slotScrollTicks = new int[JournalLayout.GRID_ITEMS_PER_PAGE];
    private final boolean[] slotWasHovered = new boolean[JournalLayout.GRID_ITEMS_PER_PAGE];
    // 绘制用的不可变条目视图：在 rebuildTagGroups 中随数据重建，逐帧只读，不再每帧 List.copyOf。
    private List<TagGroup> tagGroupView = List.of();
    private List<ResourceLocation> tagIdView = List.of();
    private List<GridCell> visibleCells = List.of();
    private String locatedKind = "", locatedTarget = "";
    // 逐帧格子文案缓存：GridItem / ChildTableEntry 都是不可变数据，同一实例的文案只取决于语言资源实例。
    // 失效点：setTable（换表数据）或语言资源实例变化（整表重算）。
    private final Map<Object, String> probabilityTextCache = new IdentityHashMap<>();
    private final Map<Object, String> displayNameTextCache = new IdentityHashMap<>();
    // 已测宽度缓存：与文案缓存同一失效事件（语言/字体实例、setTable）
    private final PanelTextMetrics metrics = new PanelTextMetrics();
    private Language cachedLanguage;

    public void applyLayout(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        rebuildVisibleCells();
        controlsDirty = true;
    }

    public ItemGridPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        pagination.reset();
    }

    // 设置当前展示的战利品表数据
    public void setTable(List<GridItem> items, List<ChildTableEntry> childTables, Map<String, ScenarioTableSnapshot.Target> targets) {
        this.targets = Map.copyOf(targets);
        this.items.clear();
        this.items.addAll(items);
        this.childTables.clear();
        this.childTables.addAll(childTables);
        this.navigationTarget = null;
        this.probabilityTextCache.clear();
        this.displayNameTextCache.clear();
        this.metrics.clear();
        rebuildTagGroups();
        if (this.activeTag != null && (this.tagGroups.get(this.activeTag) == null
                || !this.tagGroups.get(this.activeTag).unlocked())) {
            this.activeTag = null;
        }
        rebuildVisibleCells();
        resetHoverState();
    }

    private int computePageCount() {
        if (this.activeTag != null) {
            TagGroup group = this.tagGroups.get(this.activeTag);
            int memberCount = group != null ? group.members().size() : 0;
            return Math.max(1, (memberCount + TAG_GROUP_MEMBERS_PER_PAGE - 1)
                    / TAG_GROUP_MEMBERS_PER_PAGE);
        }
        int entryCount = this.tagGroups.size() + this.childTables.size() + this.directItems.size();
        return Math.max(1, (entryCount + JournalLayout.GRID_ITEMS_PER_PAGE - 1)
                / JournalLayout.GRID_ITEMS_PER_PAGE);
    }

    public int pageCount() {
        return pagination.pageCount();
    }

    public int getPage() {
        return pagination.getPage();
    }

    public boolean locateTarget(String kind, String target) {
        locatedKind = kind;
        locatedTarget = target;
        int index = -1;
        if (kind.equals("tag")) {
            index = tagIdView.indexOf(ResourceLocation.tryParse(target));
        } else if (kind.equals("table")) {
            for (int position = 0; position < childTables.size(); position++)
                if (childTables.get(position).tableId().toString().equals(target)) index = tagGroups.size() + position;
        } else {
            for (int position = 0; position < directItems.size(); position++)
                if (directItems.get(position).signature().toStoredKey().equals(target))
                    index = tagGroups.size() + childTables.size() + position;
            if (index < 0) {
                for (var entry : tagGroups.entrySet()) {
                    var members = entry.getValue().members();
                    for (int position = 0; position < members.size(); position++) {
                        if (!members.get(position).signature().toStoredKey().equals(target)) continue;
                        if (!entry.getValue().unlocked()) return false;
                        showPage(entry.getKey(), position / TAG_GROUP_MEMBERS_PER_PAGE);
                        return true;
                    }
                }
            }
        }
        if (index < 0) return false;
        showPage(null, index / JournalLayout.GRID_ITEMS_PER_PAGE);
        return true;
    }

    public void changePage(int delta) {
        if (pagination.changePage(delta)) {
            rebuildVisibleCells();
            resetHoverState();
        }
    }

    public void resetPage() {
        pagination.reset();
        this.activeTag = null;
        rebuildVisibleCells();
        resetHoverState();
    }

    // 直接设置页码（resize 后恢复用）
    public void setPage(int page) {
        showPage(activeTag, page);
    }

    private void showPage(@Nullable ResourceLocation tag, int page) {
        boolean tagChanged = !Objects.equals(activeTag, tag);
        activeTag = tag;
        boolean pageChanged = pagination.setPage(page);
        rebuildVisibleCells();
        if (tagChanged || pageChanged) {
            resetHoverState();
        }
    }

    @Nullable
    public ResourceLocation getActiveTag() {
        return this.activeTag;
    }

    public void setActiveTag(@Nullable ResourceLocation tagId) {
        this.activeTag = tagId != null && this.tagGroups.containsKey(tagId)
                && this.tagGroups.get(tagId).unlocked() ? tagId : null;
        pagination.getPage();
        rebuildVisibleCells();
        resetHoverState();
    }

    // 判断鼠标是否在物品网格面板区域内：边界口径统一走 PagePanel.containsPageBounds（R-4）
    public boolean containsMouse(double mouseX, double mouseY) {
        return PagePanel.containsPageBounds(mouseX, mouseY,
                layout.rightPageX(), layout.rightPageY(),
                layout.rightPageRight(), layout.rightPageBottom());
    }

    // 渲染物品网格（仅图标，不含进度/页码）
    public void render(GuiGraphics guiGraphics, Font font, UiPointer pointer) {
        refreshTextCacheLanguage();
        this.metrics.beginFrame(font);
        if (this.items.isEmpty() && this.childTables.isEmpty()) {
            int leftX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
            guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_entries"),
                    leftX, layout.rightPageY() + JournalLayout.GRID_TOP, UiTextScheme.PARCHMENT.label(), false);
            return;
        }

        // 网格区域
        int gridWidth = JournalLayout.GRID_CELLS_PER_ROW * JournalLayout.GRID_CELL_WIDTH
                + JournalLayout.GRID_COLUMN_GAP;
        int gridX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
        int gridY = layout.rightPageY() + JournalLayout.GRID_TOP;

        for (GridCell cell : visibleCells) {
            int visualIndex = cell.index();
            int cellX = cell.bounds().x();
            int cellY = cell.bounds().y();
            boolean hovered = pointer.contains(cell.bounds());
            updateHoverState(visualIndex, hovered);
            switch (cell.content()) {
                case TagBack back -> renderTagBackCell(guiGraphics, font, cellX, cellY,
                        hovered, back.tagId(), slotScrollTicks[visualIndex]);
                case TagGroup tag -> {
                    renderTagGroupCell(guiGraphics, font, cellX, cellY, tag,
                            hovered, slotScrollTicks[visualIndex]);
                    renderLocation(guiGraphics, cellX, cellY, "tag", tag.id().toString());
                }
                case ChildTableEntry child -> {
                    renderChildTableCell(guiGraphics, font, cellX, cellY, child,
                            hovered, slotScrollTicks[visualIndex]);
                    renderLocation(guiGraphics, cellX, cellY, "table", child.tableId().toString());
                }
                case GridItem item -> {
                    renderItemCell(guiGraphics, font, cellX, cellY, item,
                            hovered, slotScrollTicks[visualIndex]);
                    renderLocation(guiGraphics, cellX, cellY, "item", item.signature().toStoredKey());
                }
            }
        }
        int visibleEntryCount = visibleCells.size();

        // 行间分隔线
        int visibleRows = (visibleEntryCount + JournalLayout.GRID_CELLS_PER_ROW - 1)
                / JournalLayout.GRID_CELLS_PER_ROW;
        for (int r = 1; r < visibleRows; r++) {
            int sepY = gridY + r * JournalLayout.GRID_CELL_HEIGHT;
            guiGraphics.fill(gridX, sepY, gridX + gridWidth, sepY + 1, JournalLayout.GRID_SEPARATOR_COLOR);
        }
    }

    private void renderLocation(GuiGraphics graphics, int cellX, int cellY, String kind, String target) {
        if (!locatedKind.equals(kind) || !locatedTarget.equals(target)) return;
        int right = cellX + JournalLayout.GRID_CELL_WIDTH;
        int bottom = cellY + JournalLayout.GRID_CELL_HEIGHT;
        int color = UiTextScheme.PARCHMENT.accent();
        graphics.fill(cellX, cellY, right, cellY + 1, color);
        graphics.fill(cellX, bottom - 1, right, bottom, color);
        graphics.fill(cellX, cellY, cellX + 1, bottom, color);
        graphics.fill(right - 1, cellY, right, bottom, color);
    }

    private void updateHoverState(int visualIndex, boolean hovered) {
        if (!slotWasHovered[visualIndex] && hovered) {
            slotScrollTicks[visualIndex] = 0;
        }
        slotWasHovered[visualIndex] = hovered;
        if (hovered) {
            slotScrollTicks[visualIndex]++;
        }
    }

    private void renderTagGroupCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                    TagGroup group, boolean hovered, int scrollTicks) {
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        int cellH = JournalLayout.GRID_CELL_HEIGHT;
        (hovered ? ScenarioAtlas.CARD_HOVER : ScenarioAtlas.CARD).render(guiGraphics,
                new UiRect(cellX, cellY, cellW, cellH));

        if (!group.unlocked()) {
            renderLockedCollectionCell(guiGraphics, font, cellX, cellY);
            return;
        }

        renderTagPreview(guiGraphics, group, cellX + cellW / 2, cellY + ICON_TOP);
        PanelTextMetrics.Measured groupId = this.metrics.measure(group.id().toString(), font);
        TextScroll.draw(guiGraphics, font, groupId.text(), groupId.width(),
                cellX + 3, cellY + NAME_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);

        int discovered = group.discoveredCount();
        Component progress = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.tag_group_progress_short",
                discovered, group.members().size());
        int progressWidth = this.metrics.measure(progress.getString(), font).width();
        guiGraphics.drawString(font, progress, cellX + (cellW - progressWidth) / 2,
                cellY + PROB_Y_OFFSET, TAG_GROUP_TEXT_COLOR, false);

        if (!group.hasHighlightedMember()) {
            guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH, JournalPalette.DIM_MASK);
        }
    }

    private void renderTagPreview(GuiGraphics guiGraphics, TagGroup group, int centerX, int iconY) {
        renderPreviewStacks(guiGraphics, group.previewStacks(), centerX, iconY);
    }

    private void renderChildTableCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                      ChildTableEntry child, boolean hovered, int scrollTicks) {
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        int cellH = JournalLayout.GRID_CELL_HEIGHT;
        (hovered ? ScenarioAtlas.CARD_HOVER : ScenarioAtlas.CARD).render(guiGraphics,
                new UiRect(cellX, cellY, cellW, cellH));
        if (!child.unlocked()) {
            renderLockedCollectionCell(guiGraphics, font, cellX, cellY);
            return;
        }
        renderPreviewStacks(guiGraphics, child.previewItems(), cellX + cellW / 2, cellY + ICON_TOP);
        if (child.luckAffected()) {
            UiAtlas.LUCK_ICON.render(guiGraphics, luckBadgeX(cellX, cellW, true), cellY + CORNER_BADGE_INSET);
        }
        UiAtlas.icon(UiAtlas.Icon.RECOMMEND)
                .render(guiGraphics, cellX + cellW - 12, cellY + 2);
        PanelTextMetrics.Measured childName = this.metrics.measure(
                displayNameText(child, child.displayName()), font);
        TextScroll.draw(guiGraphics, font, childName.text(), childName.width(),
                cellX + 3, cellY + NAME_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);
        PanelTextMetrics.Measured probability = this.metrics.measure(
                probabilityText(child, child.probability(), List.of()), font);
        TextScroll.draw(guiGraphics, font, probability.text(), probability.width(),
                cellX + 3, cellY + PROB_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);
    }

    private void renderPreviewStacks(GuiGraphics guiGraphics, List<ItemStack> stacks, int centerX, int iconY) {
        if (stacks.isEmpty()) {
            UiAtlas.UNKNOWN_ITEM.renderScaled(guiGraphics,
                    new UiRect(centerX - ICON_SIZE / 2, iconY, ICON_SIZE, ICON_SIZE),
                    () -> UnsuspiciousUiTheme.PARCHMENT);
            return;
        }
        int totalWidth = ICON_SIZE + (stacks.size() - 1) * 8;
        int startX = centerX - totalWidth / 2;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            int iconX = startX + i * 8;
            UiRenderLayers.item(guiGraphics, new UiRect(iconX, iconY, ICON_SIZE, ICON_SIZE), local -> {
                local.pose().pushPose();
                try {
                    local.pose().translate(iconX, iconY, 0);
                    local.pose().scale(ICON_SIZE / 16f, ICON_SIZE / 16f, 1);
                    local.renderItem(stack, 0, 0);
                } finally {
                    local.pose().popPose();
                }
            });
        }
    }

    // 未解锁合集与普通未发现物品共用未知图标；在首个成员被发现前不透露名称与概率。
    private void renderLockedCollectionCell(GuiGraphics graphics, Font font, int cellX, int cellY) {
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        UiAtlas.UNKNOWN_ITEM.renderScaled(graphics, new UiRect(cellX + (cellW - ICON_SIZE) / 2,
                cellY + ICON_TOP, ICON_SIZE, ICON_SIZE), () -> UnsuspiciousUiTheme.PARCHMENT);
        Component label = Component.translatable("screen.unsuspiciousblock.archaeology_journal.collection_locked");
        int labelWidth = this.metrics.measure(label.getString(), font).width();
        graphics.drawString(font, label, cellX + (cellW - labelWidth) / 2,
                cellY + NAME_Y_OFFSET, PENDING_COLOR, false);
    }

    private void renderTagBackCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                   boolean hovered, ResourceLocation tagId, int scrollTicks) {
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        int cellH = JournalLayout.GRID_CELL_HEIGHT;
        (hovered ? ScenarioAtlas.CARD_HOVER : ScenarioAtlas.CARD).render(guiGraphics,
                new UiRect(cellX, cellY, cellW, cellH));
        UiAtlas.icon(UiAtlas.Icon.BACK).render(guiGraphics,
                cellX + (cellW - UiAtlas.ICON_SIZE) / 2, cellY + ICON_TOP + 4);
        PanelTextMetrics.Measured tagText = this.metrics.measure(tagId.toString(), font);
        TextScroll.draw(guiGraphics, font, tagText.text(), tagText.width(),
                cellX + 3, cellY + NAME_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);
        Component back = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.tag_group_back");
        int backWidth = this.metrics.measure(back.getString(), font).width();
        guiGraphics.drawString(font, back, cellX + (cellW - backWidth) / 2,
                cellY + PROB_Y_OFFSET, TAG_GROUP_TEXT_COLOR, false);
    }

    private void renderItemCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                GridItem item, boolean hovered, int scrollTicks) {
        boolean unlocked = item.unlocked();
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        int cellH = JournalLayout.GRID_CELL_HEIGHT;
        int centerX = cellX + cellW / 2;
        int iconX = cellX + (cellW - ICON_SIZE) / 2;
        int iconY = cellY + ICON_TOP;

        // 单元格背景
        int bgColor = unlocked
                ? (hovered ? JournalPalette.CARD_SUBTLE_HOVER : 0x00000000)
                : (hovered ? JournalPalette.CARD_MUTED_HOVER : 0x00000000);
        if (bgColor != 0) {
            guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH, bgColor);
        }

        if (!unlocked) {
            // 未解锁：渲染未知物品图标（放大到 ICON_SIZE），下方显示"未解析"
            UiAtlas.UNKNOWN_ITEM.renderScaled(guiGraphics, new UiRect(iconX, iconY, ICON_SIZE, ICON_SIZE),
                    () -> UnsuspiciousUiTheme.PARCHMENT);
            Component pendingText = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.pending_analysis");
            int textW = this.metrics.measure(pendingText.getString(), font).width();
            guiGraphics.drawString(font, pendingText, centerX - textW / 2,
                    cellY + NAME_Y_OFFSET, PENDING_COLOR, false);
            return;
        }

        ItemStack stack = item.stack();
        UiRenderLayers.item(guiGraphics, new UiRect(iconX, iconY, ICON_SIZE, ICON_SIZE), local -> {
            local.pose().pushPose();
            try {
                local.pose().translate(iconX, iconY, 0);
                local.pose().scale(ICON_SIZE / 16f, ICON_SIZE / 16f, 1);
                local.renderItem(stack, 0, 0);
                local.renderItemDecorations(font, stack, 0, 0, "");
            } finally {
                local.pose().popPose();
            }
        });

        // 数量角标（图标右下角；大数模糊化为 1.2K/1.2M 等）
        if (item.count() > 0) {
            String countText = formatCountBadge(item.count());
            int badgeColor = item.count() >= 1000 ? BADGE_COLOR_ABBR : BADGE_COLOR_NORMAL;
            int badgeX = iconX + ICON_SIZE - this.metrics.measure(countText, font).width();
            int badgeY = iconY + ICON_SIZE - font.lineHeight + 1;
            UiRenderLayers.draw(guiGraphics, UiRenderLayers.FOREGROUND,
                    () -> guiGraphics.drawString(font, countText, badgeX, badgeY, badgeColor, true));
        }
        boolean hasRecommendationBadge = item.probability() instanceof Probability.NeedsCondition;
        if (hasRecommendationBadge) {
            UiAtlas.icon(UiAtlas.Icon.RECOMMEND)
                    .render(guiGraphics, cellX + cellW - 12, cellY + 2);
        }
        if (item.acquisitionPaths().stream().anyMatch(LootAcquisitionPath::luckAffected)) {
            UiAtlas.LUCK_ICON.render(guiGraphics, luckBadgeX(cellX, cellW, hasRecommendationBadge),
                    cellY + CORNER_BADGE_INSET);
        }

        // 物品名（悬停时走马灯滚动，与 intro/Catalog 风格一致；非悬停时居中静态）
        int nameMaxWidth = cellW - 6;
        PanelTextMetrics.Measured name = this.metrics.measure(
                displayNameText(item, item.displayName()), font);
        TextScroll.draw(guiGraphics, font, name.text(), name.width(),
                cellX + 3, cellY + NAME_Y_OFFSET, nameMaxWidth,
                NAME_COLOR, hovered, scrollTicks, true);

        // 概率（居中，颜色根据状态与不确定性等级区分）——显示优先级链见 formatProbability
        PanelTextMetrics.Measured probText = this.metrics.measure(
                probabilityText(item, item.probability(), item.declaredChances()), font);
        int probColor = probabilityColor(item.probability(), item.uncertaintyLevel(),
                !item.declaredChances().isEmpty());
        TextScroll.draw(guiGraphics, font, probText.text(), probText.width(),
                cellX + 3, cellY + PROB_Y_OFFSET, cellW - 6,
                probColor, hovered, scrollTicks, true);

        // 搜索不匹配：覆盖暖棕半透明遮罩降低视觉权重
        if (!item.highlighted()) {
            guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH, JournalPalette.DIM_MASK);
        }
    }

    // 格式化数量为角标文本：<1000 原样，1K+ 用 1.2K/1.2M/1.2B 简写
    private static String formatCountBadge(int count) {
        if (count < 1000) return Integer.toString(count);
        if (count < 1_000_000) return formatOneDecimal(count / 1000.0) + "K";
        if (count < 1_000_000_000) return formatOneDecimal(count / 1_000_000.0) + "M";
        return formatOneDecimal(count / 1_000_000_000.0) + "B";
    }

    private static int luckBadgeX(int cellX, int cellWidth, boolean hasRecommendationBadge) {
        int rightInset = CORNER_BADGE_INSET;
        if (hasRecommendationBadge) {
            rightInset += UiAtlas.ICON_SIZE + CORNER_BADGE_GAP;
        }
        return cellX + cellWidth - UiAtlas.ICON_SIZE - rightInset;
    }

    // 保留 1 位小数，去除整数的 .0 后缀
    private static String formatOneDecimal(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    /**
     * 网格概率文案的**显示优先级链**（决策 44）：
     * <ol>
     *   <li>可适用性状态优先——「需要条件」与未知一律不显示任何数字（含声明触发率），
     *       否则一个"当前拿不到"的条目会顶着最有利场景的数字出现；</li>
     *   <li>模拟状态可展示（已测量 / 静态不可达）时优先显示**声明触发率**——
     *       {@code random_chance} 类条目的模拟值常是零命中，声明值反而是唯一有信息量的数字；</li>
     *   <li>最后才是模拟值本身。</li>
     * </ol>
     * 客户端只做这一层渲染分派，四种状态的判定全部来自服务端派生结果。
     */
    private static Component formatProbability(@Nullable Probability probability,
                                               List<DeclaredChance> declaredChances) {
        if (probability == null) {
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_question");
        }
        if (!probability.isDisplayable()) {
            // 需要条件 / 未知：状态词直接表达"现在没有数字可给"
            return ProbabilityFormat.formatComponent(probability);
        }
        if (!declaredChances.isEmpty()) {
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_trigger_short",
                    ProbabilityFormat.formatDeclaredChances(declaredChances));
        }
        return ProbabilityFormat.formatComponent(probability);
    }

    // 概率文字的颜色：状态词用灰色，数值沿用不确定性等级的既有色相语义
    private static int probabilityColor(@Nullable Probability probability,
                                        LootConditionHandler.UncertaintyLevel uncertaintyLevel,
                                        boolean hasDeclaredChance) {
        if (probability == null || !probability.isDisplayable()) {
            return PROB_COLOR_UNKNOWN;
        }
        if (hasDeclaredChance) {
            return PROB_COLOR_PROBABILISTIC;
        }
        return switch (uncertaintyLevel) {
            case PROBABILISTIC -> PROB_COLOR_PROBABILISTIC;
            case RUNTIME -> PROB_COLOR_RUNTIME;
            case NONE -> PROB_COLOR;
        };
    }

    private static TooltipData tooltipData(GridItem item, ItemStack stack) {
        if (!item.unlocked()) return new TooltipData(ItemStack.EMPTY, null, -1, null, List.of(), false,
                LootConditionHandler.UncertaintyLevel.NONE, false, List.of(), 0, null, null, null);
        return new TooltipData(stack, item.tooltipHint(), item.count(), item.probability(), item.acquisitionPaths(),
                item.injected(), item.uncertaintyLevel(), true, item.declaredChances(),
                item.simulationCount(), item.observedFunctions(), item.displayName(), item.signature());
    }

    @Nullable
    public ResourceLocation consumeNavigationTarget() {
        ResourceLocation target = this.navigationTarget;
        this.navigationTarget = null;
        return target;
    }

    private static JournalTooltipSpec tagTooltipSpec(TagGroup group) {
        if (!group.unlocked()) {
            return JournalTooltipSpec.builder()
                    .line(Component.translatable("screen.unsuspiciousblock.archaeology_journal.collection_locked"),
                            TooltipBuilder.LABEL)
                    .line(Component.translatable("screen.unsuspiciousblock.archaeology_journal.tooltip.undiscovered_hint"),
                            TooltipBuilder.HINT)
                    .build();
        }
        return JournalTooltipBuilder.buildTagRich(group.id());
    }

    private static JournalTooltipSpec childTableTooltipSpec(ChildTableEntry child, boolean recommendationHovered) {
        if (!child.unlocked()) {
            return JournalTooltipSpec.builder()
                    .line(Component.translatable("screen.unsuspiciousblock.archaeology_journal.collection_locked"),
                            TooltipBuilder.LABEL)
                    .line(Component.translatable("screen.unsuspiciousblock.archaeology_journal.tooltip.undiscovered_hint"),
                            TooltipBuilder.HINT)
                    .build();
        }
        if (recommendationHovered) {
            return JournalTooltipBuilder.buildRecommendRich(child.displayName());
        }
        return JournalTooltipBuilder.buildChildTableRich(child.displayName(), child.tableId(), child.probability(),
                child.conditions(), child.luckAffected());
    }

    private void rebuildTagGroups() {
        this.directItems.clear();
        this.tagGroups.clear();
        LinkedHashMap<ResourceLocation, LinkedHashMap<String, GridItem>> groupedItems = new LinkedHashMap<>();
        for (GridItem item : this.items) {
            boolean hasDirectPath = item.acquisitionPaths().isEmpty();
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                ResourceLocation tagId = path.sourceItemTag();
                if (tagId == null) {
                    hasDirectPath = true;
                    continue;
                }
                groupedItems.computeIfAbsent(tagId, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(item.signature().toStoredKey(), item);
            }
            if (hasDirectPath) {
                this.directItems.add(item);
            }
        }
        for (Map.Entry<ResourceLocation, LinkedHashMap<String, GridItem>> entry : groupedItems.entrySet()) {
            List<GridItem> members = List.copyOf(entry.getValue().values());
            this.tagGroups.put(entry.getKey(), new TagGroup(
                    entry.getKey(),
                    members,
                    targets.getOrDefault("tag#" + entry.getKey(), new ScenarioTableSnapshot.Target(false, Component.empty())).visible(),
                    (int) members.stream().filter(GridItem::unlocked).count(),
                    members.stream().anyMatch(GridItem::highlighted),
                    members.stream().filter(GridItem::unlocked).limit(3).map(GridItem::stack).toList()));
        }
        this.tagGroupView = List.copyOf(this.tagGroups.values());
        this.tagIdView = List.copyOf(this.tagGroups.keySet());
    }

    // 语言资源实例变化时丢弃逐帧文案缓存：文字随语言变，几何与数据不随。
    private void refreshTextCacheLanguage() {
        Language language = Language.getInstance();
        if (language != this.cachedLanguage) {
            this.cachedLanguage = language;
            this.probabilityTextCache.clear();
            this.displayNameTextCache.clear();
        }
    }

    // 概率文案按数据实例缓存：实例字段（概率/声明触发率/不确定性等级）不可变，只随语言变化。
    private String probabilityText(Object key, @Nullable Probability probability,
                                   List<DeclaredChance> declaredChances) {
        return this.probabilityTextCache.computeIfAbsent(key,
                ignored -> formatProbability(probability, declaredChances).getString());
    }

    // 名称文案按数据实例缓存：Component 的解析结果只随语言变化。
    private String displayNameText(Object key, Component name) {
        return this.displayNameTextCache.computeIfAbsent(key, ignored -> name.getString());
    }

    private void resetHoverState() {
        controlsDirty = true;
        Arrays.fill(this.slotScrollTicks, 0);
        Arrays.fill(this.slotWasHovered, false);
    }

    /**
     * tag 分组入口所需的不可变展示数据。
     * <p>成员都是不可变 record，因此 unlocked / discoveredCount / hasHighlightedMember 与预览栈
     * 在构造时一次算出，绘制路径只读字段，不再逐帧 stream。</p>
     */
    private record TagGroup(ResourceLocation id, List<GridItem> members, boolean unlocked,
                            int discoveredCount, boolean hasHighlightedMember,
                            List<ItemStack> previewStacks) implements GridContent {
    }

    /**
     * 子表导航入口展示数据。
     */
    public record ChildTableEntry(ResourceLocation tableId, Component displayName, boolean unlocked,
                                  Probability probability,
                                  List<LootConditionInfo> conditions,
                                  List<ItemStack> previewItems,
                                  boolean luckAffected,
                                  int simulationCount) implements GridContent {
        public ChildTableEntry {
            conditions = List.copyOf(conditions);
            previewItems = List.copyOf(previewItems);
        }
    }

    /**
     * Tooltip 数据：携带物品堆叠、附魔等原版 tooltip 之外需要追加的统计信息。
     * <p>
     * count 为 -1 表示无获取统计（如日志详情页），不追加 "Acquired" 行；
     * probability 为 null 时不追加 "Drop Chance" 行。
     * discovered=false 时只展示未发现状态，不携带物品身份、概率或获取路径。
     */
    public record TooltipData(ItemStack stack, @Nullable Component hint, int count, @Nullable Probability probability,
                              List<LootAcquisitionPath> acquisitionPaths,
                              boolean injected,
                              LootConditionHandler.UncertaintyLevel uncertaintyLevel,
                              boolean discovered,
                              List<DeclaredChance> declaredChances,
                              int simulationCount,
                              @Nullable FunctionObservationSummary observedFunctions,
                              @Nullable Component displayName,
                              @Nullable LootResultSignature signature) {
    }

    // 物品网格条目；highlighted 标记搜索匹配（true = 匹配/无搜索，false = 搜索不匹配）
    public record GridItem(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                           Probability probability, boolean unlocked, int count,
                           LootResultSignature signature, boolean highlighted,
                           List<LootAcquisitionPath> acquisitionPaths,
                           boolean injected,
                           LootConditionHandler.UncertaintyLevel uncertaintyLevel,
                           List<DeclaredChance> declaredChances,
                           int simulationCount,
                           @Nullable FunctionObservationSummary observedFunctions) implements GridContent {

        public ItemStack stack() {
            if (this.signature != null) {
                ItemStack preview = this.signature.createPreviewStack();
                if (!preview.isEmpty()) {
                    return preview;
                }
            }
            return new ItemStack(BuiltInRegistries.ITEM.get(this.id));
        }
    }
}
