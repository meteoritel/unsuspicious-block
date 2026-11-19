package com.meteorite.unsuspiciousblock.client.ui.journal.catalog;

import com.meteorite.unsuspiciousblock.client.ui.text.PanelTextMetrics;
import com.meteorite.meteosuikit.api.control.UiScrollView;
import com.meteorite.meteosuikit.api.control.UiScrollContainer;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiFocusState;
import com.meteorite.meteosuikit.api.input.UiFocusTarget;
import com.meteorite.meteosuikit.api.input.UiInputContext;
import com.meteorite.meteosuikit.api.input.UiInputTarget;
import com.meteorite.meteosuikit.api.input.UiInteractionSession;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.layout.UiGridMetrics;
import com.meteorite.meteosuikit.api.layout.UiLayoutNode;
import com.meteorite.meteosuikit.api.layout.UiLayoutSource;
import com.meteorite.meteosuikit.api.render.UiNineSlice;
import com.meteorite.meteosuikit.api.render.UiRenderLayers;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalTextColors;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;

/**
 * 考古笔记左页目录——渲染分类网格与可平滑滚动的战利品表层级列表。
 *
 * <p>滚动偏移、阻尼与滚动条由 {@link UiScrollView} 统一持有（像素口径）；
 * 本类转发成员帧与退出通知，对外持久化/快照仍以条目索引为单位。</p>
 */
public final class CatalogPanel implements UiFocusTarget, UiInteractionSession.Target, UiLayoutSource {
    private BiConsumer<ClickResult, Boolean> onActivate = (click, shift) -> {
    };
    private final UiFocusState focus = new UiFocusState();
    private IntPredicate navigate = key -> false;

    public void setNavigation(IntPredicate navigate) {
        this.navigate = navigate;
    }

    @Override
    public UiFocusState focusState() {
        return focus;
    }

    @Override
    public UiRect bounds() {
        return view.viewport();
    }

    @Override
    public Component accessibleName() {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.title");
    }

    @Override
    public boolean activate() {
        return navigate.test(GLFW.GLFW_KEY_ENTER);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        return isFocused() && navigate.test(key);
    }

    private record PressedRow(Mode mode, ResourceLocation id, List<ResourceLocation> parentPath,
                              boolean toggleExpansion) { }
    private @Nullable PressedRow pressed;

    public void setOnActivate(BiConsumer<ClickResult, Boolean> action) {
        onActivate = action;
    }

    @Override
    public UiPointerHit hitTest(double x, double y) {
        if (!containsMouse(x, y)) return null;
        UiPointerHit hit = container.hitTest(x, y);
        return hit == null ? null : hit.within(this);
    }

    private UiPointerHit hitContent(double x, double y) {
        int index = hoveredIndex(x, y);
        UiPointerHit hit = UiPointerHit.leaf(this, x, y);
        return index < 0 ? hit : hit.withRow(this, index, index, screenBounds(index).intersect(view.viewport()));
    }

    @Override
    public boolean mousePressed(UiInputContext context) {
        if (context.hits(view)) return container.mousePressed(context);
        if (context.button() != 0) return false;
        pressed = identityOf(handleClick(context.pointerX(), context.pointerY()));
        if (pressed == null) return false;
        context.scope().interaction().begin(this, UiInteractionSession.Source.POINTER, context.button());
        return true;
    }

    @Override
    public boolean mouseReleased(UiInputContext context) {
        ClickResult clicked = handleClick(context.pointerX(), context.pointerY());
        PressedRow original = pressed;
        pressed = null;
        if (original != null && original.equals(identityOf(clicked))) onActivate.accept(clicked, context.shift());
        return true;
    }

    @Override
    public void interactionEnded(UiInteractionSession.EndReason reason) {
        pressed = null;
    }

    @Override
    public boolean mouseScrolled(UiInputContext context, double sx, double sy) {
        return container.mouseScrolled(context, sx, sy);
    }

    private @Nullable PressedRow identityOf(ClickResult click) {
        if (click.index() < 0) return null;
        if (mode == Mode.CATEGORIES) {
            return new PressedRow(mode, categories.get(click.index()).id(), List.of(), false);
        }
        CatalogEntryData entry = entries.get(click.index());
        return new PressedRow(mode, entry.id(), entry.parentPath(), click.toggleExpansion());
    }

    private static final ResourceLocation ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/catalog_entry.png");
    private static final int[] UNLOCKED_STATE_V = {0, 19, 38};
    private static final int LOCKED_STATE_V = 57;
    private static final int CHILD_COLOR = JournalTextColors.COLLECTION;
    private static final int NORMAL_COLOR = UiTextScheme.PARCHMENT.body();
    private static final int SELECTED_COLOR = JournalTextColors.SELECTED;
    private static final int CARD_BORDER = JournalPalette.BORDER_WARM;
    private static final int CARD_BG = JournalPalette.CARD_BG;
    private static final int CARD_HOVER = JournalPalette.CARD_HOVER;
    private static final int CARD_WIDTH = 70;
    private static final int CARD_HEIGHT = 50;
    private static final int CARD_GAP_X = 4;
    private static final int CARD_GAP_Y = 5;
    private static final int SCROLLBAR_WIDTH = 3;
    private static final int SCROLLBAR_GAP = 2;
    private static final UiGridMetrics CATEGORY_METRICS = new UiGridMetrics(2, CARD_WIDTH, CARD_HEIGHT, CARD_GAP_X, CARD_GAP_Y);
    private static final UiGridMetrics TABLE_METRICS = new UiGridMetrics(1,
            JournalLayout.CATALOG_TEXTURE_WIDTH - SCROLLBAR_WIDTH - SCROLLBAR_GAP - 1,
            JournalLayout.CATALOG_ROW_HEIGHT, 0, JournalLayout.CATALOG_ROW_GAP);
    public static final int CATEGORY_GRID_WIDTH = CARD_WIDTH * 2 + CARD_GAP_X;

    private JournalBookBackground.BookLayout layout;
    private final UiScrollView view = new UiScrollView();
    private final UiScrollContainer container = UiScrollContainer.forPixelWheel(view, new UiInputTarget() {
        @Override public UiPointerHit hitTest(double x, double y) { return hitContent(x, y); }
    });
    private final List<CategoryEntryData> categories = new ArrayList<>();
    private final List<CatalogEntryData> entries = new ArrayList<>();
    private final Map<ResourceLocation, Integer> categoryScrollTicks = new HashMap<>();
    private final Map<ResourceLocation, Integer> entryScrollTicks = new HashMap<>();
    private final Map<Object, String> nameTextCache = new IdentityHashMap<>();
    private final Map<Object, String> progressTextCache = new IdentityHashMap<>();
    private final PanelTextMetrics metrics = new PanelTextMetrics();
    private Language cachedLanguage;
    private Mode mode = Mode.CATEGORIES;
    private @Nullable ResourceLocation directoryCategory;

    public CatalogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.view.setScrollbarVisible(true);
        syncViewport();
        syncContentHeight();
    }

    public void applyLayout(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        syncViewport();
    }

    /**
     * 由活动成员树传入同一帧时间，滚动几何由 kit 更新。
     */
    @Override
    public void advanceFrame(long nowMillis) {
        container.advanceFrame(nowMillis);
    }

    /**
     * 退出时保留实际阅读位置，并结清滚动与条目按压。
     */
    @Override
    public void scopeExited(UiInteractionSession.EndReason reason) {
        container.scopeExited(reason);
        pressed = null;
    }

    public void setCategories(List<CategoryEntryData> values) {
        int carriedOffset = firstVisibleIndex();
        if (mode != Mode.CATEGORIES) pressed = null;
        directoryCategory = null;
        this.mode = Mode.CATEGORIES;
        this.categories.clear();
        this.categories.addAll(values);
        this.nameTextCache.clear();
        this.progressTextCache.clear();
        this.metrics.clear();
        syncContentHeight();
        this.view.setOffset(itemsToPixels(carriedOffset));
    }

    public void setEntries(@Nullable ResourceLocation category, List<CatalogEntryData> values) {
        boolean sameMode = this.mode == Mode.TABLES;
        if (!sameMode || !Objects.equals(directoryCategory, category)) pressed = null;
        directoryCategory = category;
        int carriedPixelOffset = this.view.offset();
        int carriedItemOffset = firstVisibleIndex();
        this.mode = Mode.TABLES;
        this.entries.clear();
        this.entries.addAll(values);
        this.nameTextCache.clear();
        this.progressTextCache.clear();
        this.metrics.clear();
        syncContentHeight();
        this.view.setOffset(sameMode ? carriedPixelOffset : itemsToPixels(carriedItemOffset));
    }

    public Mode mode() {
        return this.mode;
    }

    /**
     * 使用绘制时同一视口像素区间与展示语义，未解锁表不导出真实身份。
     */
    @Override
    public List<UiLayoutNode> layoutNodes() {
        List<UiLayoutNode> nodes = new ArrayList<>();
        int count = mode == Mode.CATEGORIES ? categories.size() : entries.size();
        var range = visibleRange(count);
        for (int index = range.from(); index < range.to(); index++) {
            boolean category = mode == Mode.CATEGORIES;
            UiRect rect = screenBounds(index);
            boolean unlocked = category || entries.get(index).unlocked();
            Component text = category ? categories.get(index).name() : unlocked ? entries.get(index).displayName() : Component.empty();
            String key = category ? "category/" + categories.get(index).id()
                    : unlocked ? "table/" + entries.get(index).id() : "";
            nodes.add(new UiLayoutNode(UiLayoutNode.Kind.CONTROL, rect, text, 0, 0, unlocked, true, List.of())
                    .identified(key, this).clippedTo(view.viewport()));
        }
        return List.copyOf(nodes);
    }

    /**
     * 立即露出索引所在条目，用于恢复阅读位置。
     */
    public void ensureIndexVisible(int index) {
        if (index < 0) return;
        view.ensureVisible(contentBounds(index));
    }

    /**
     * 平滑露出键盘导航条目，连续导航从 kit 的待完成目标累积。
     */
    public void ensureIndexVisibleSmoothly(int index) {
        if (index < 0) return;
        view.ensureVisibleSmoothly(contentBounds(index));
    }

    private UiRect contentBounds(int index) {
        return (mode == Mode.CATEGORIES ? CATEGORY_METRICS : TABLE_METRICS).cellBounds(index);
    }

    public int visibleItemCount() {
        return this.mode == Mode.CATEGORIES ? visibleCategoryRows() * 2 : visibleTableRows();
    }

    public int getScrollOffset() {
        return firstVisibleIndex();
    }

    public void setScrollOffset(int scrollOffset) {
        this.view.setOffset(itemsToPixels(scrollOffset));
    }

    public ClickResult handleClick(double mouseX, double mouseY) {
        if (!view.containsContent(mouseX, mouseY)) return ClickResult.NONE;
        double y = view.toContentY(mouseY);
        if (this.mode == Mode.CATEGORIES) {
            int index = CATEGORY_METRICS.indexAt(mouseX - categoryLeft(), y, categories.size());
            return index < 0 ? ClickResult.NONE : new ClickResult(index, false);
        }
        if (mouseX < buttonX() || mouseX >= buttonX() + buttonWidth()) return ClickResult.NONE;
        int index = TABLE_METRICS.indexAt(mouseX - buttonX(), y, entries.size());
        if (index < 0) return ClickResult.NONE;
        CatalogEntryData entry = entries.get(index);
        return new ClickResult(index, entry.hasChildren() && mouseX < buttonX() + 15 + entry.depth() * 8);
    }

    public int hoveredIndex(double mouseX, double mouseY) {
        return handleClick(mouseX, mouseY).index();
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return view.contains(mouseX, mouseY);
    }

    public void render(GuiGraphics graphics, Font font, int selectedIndex, UiPointer pointer) {
        refreshTextCacheLanguage();
        this.metrics.beginFrame(font);
        container.render(graphics, pointer, () -> UnsuspiciousUiTheme.PARCHMENT, clipped -> {
            if (mode == Mode.CATEGORIES) renderCategories(graphics, font, selectedIndex, clipped);
            else renderEntries(graphics, font, selectedIndex, clipped);
        });
    }

    private void renderCategories(GuiGraphics graphics, Font font, int selectedIndex, UiPointer pointer) {
        var range = visibleRange(categories.size());
        for (int index = range.from(); index < range.to(); index++) {
            CategoryEntryData category = this.categories.get(index);
            UiRect rect = screenBounds(index);
            boolean hovered = pointer.contains(rect);
            boolean selected = index == selectedIndex;
            UiNineSlice cardSlice = selected ? UiAtlas.CATEGORY_CARD_SELECTED
                    : hovered ? UiAtlas.CATEGORY_CARD_HOVER : UiAtlas.CATEGORY_CARD_NORMAL;
            cardSlice.render(graphics, rect);

            int iconSize = 16;
            int iconX = rect.x() + (rect.width() - iconSize) / 2;
            int iconY = rect.y() + 5;
            UiRenderLayers.item(graphics, new UiRect(iconX, iconY, iconSize, iconSize),
                    local -> local.renderItem(category.icon(), iconX, iconY));

            int ticks = hovered ? this.categoryScrollTicks.merge(category.id(), 1, Integer::sum) : 0;
            if (!hovered) this.categoryScrollTicks.put(category.id(), 0);
            PanelTextMetrics.Measured name = this.metrics.measure(categoryNameText(category), font);
            TextScroll.draw(graphics, font, name.text(), name.width(), rect.x() + 3, rect.y() + 25,
                    rect.width() - 6, NORMAL_COLOR, hovered, ticks, true);
            PanelTextMetrics.Measured progress = this.metrics.measure(categoryProgressText(category), font);
            TextScroll.draw(graphics, font, progress.text(), progress.width(),
                    rect.x() + 3, rect.bottom() - 14,
                    rect.width() - 6, UiTextScheme.PARCHMENT.label(), hovered, ticks, true);

            // 卡片底部 2 像素精致迷你进度条
            if (category.total() > 0) {
                int barWidth = rect.width() - 8;
                int filled = Math.clamp(Math.round(barWidth * (category.unlocked() / (float) category.total())), 0, barWidth);
                int barY = rect.bottom() - 3;
                graphics.fill(rect.x() + 4, barY, rect.x() + 4 + barWidth, barY + 2, 0x258B6914);
                if (filled > 0) {
                    int barColor = category.unlocked() >= category.total() ? JournalPalette.PROGRESS_FILL_DONE : JournalPalette.PROGRESS_FILL_ACTIVE;
                    graphics.fill(rect.x() + 4, barY, rect.x() + 4 + filled, barY + 2, barColor);
                }
            }
        }
    }

    private void renderEntries(GuiGraphics graphics, Font font, int selectedIndex, UiPointer pointer) {
        var range = visibleRange(entries.size());
        for (int index = range.from(); index < range.to(); index++) {
            CatalogEntryData entry = this.entries.get(index);
            UiRect rect = screenBounds(index);
            renderEntry(graphics, font, entry, rect.x(), rect.y(), pointer.contains(rect), index == selectedIndex);
        }
    }

    private void renderEntry(GuiGraphics graphics, Font font, CatalogEntryData entry,
                             int x, int y, boolean hovered, boolean selected) {
        int state = entry.unlocked() ? (selected ? 2 : hovered ? 1 : 0) : 3;
        int v = state == 3 ? LOCKED_STATE_V : UNLOCKED_STATE_V[state];
        graphics.blit(ENTRY_TEXTURE, x, y, buttonWidth(), JournalLayout.CATALOG_ROW_HEIGHT,
                0, v, JournalLayout.CATALOG_TEXTURE_WIDTH, 19,
                JournalLayout.CATALOG_TEXTURE_WIDTH, JournalLayout.CATALOG_TEXTURE_HEIGHT);
        int indent = entry.depth() * 8;
        int markerX = x + 5 + indent;
        if (entry.hasChildren()) {
            UiAtlas.icon(entry.expanded() ? UiAtlas.Icon.TREE_EXPANDED : UiAtlas.Icon.TREE_COLLAPSED)
                    .render(graphics, markerX, y + 5);
        }
        int textX = markerX + (entry.hasChildren() ? 10 : 3);
        int rightReserve = (entry.child() ? 11 : 0) + (entry.favorite() ? 11 : 0) + 5;
        String text = entry.unlocked() ? entryNameText(entry) : "";
        int ticks = hovered ? this.entryScrollTicks.merge(entry.id(), 1, Integer::sum) : 0;
        if (!hovered) this.entryScrollTicks.put(entry.id(), 0);
        PanelTextMetrics.Measured measured = this.metrics.measure(text, font);
        TextScroll.draw(graphics, font, measured.text(), measured.width(), textX, y + 7,
                Math.max(8, x + buttonWidth() - rightReserve - textX),
                entry.child() ? CHILD_COLOR : selected ? SELECTED_COLOR : NORMAL_COLOR,
                hovered, ticks, false);
        int right = x + buttonWidth() - 5;
        if (entry.favorite()) {
            UiAtlas.icon(UiAtlas.Icon.FAVORITE_BADGE).render(graphics, right - 10, y + 5);
            right -= 11;
        }
        if (entry.child()) {
            UiAtlas.icon(UiAtlas.Icon.CHILD_BADGE).render(graphics, right - 10, y + 5);
        }
    }

    // 精确计算与当前视口像素区间 [offset, offset + viewportHeight) 相交的条目索引区间 [from, to)。
    // 保证最下方条目在平滑滚动过程中按视口裁剪边完整进入与退出，不会提前消失。
    private UiGridMetrics.VisibleCells visibleRange(int totalCount) {
        UiGridMetrics geometry = mode == Mode.CATEGORIES ? CATEGORY_METRICS : TABLE_METRICS;
        return geometry.visibleCells(view.offset(), view.viewport().height(), totalCount);
    }

    private int scrollStep() {
        return this.mode == Mode.CATEGORIES ? (CARD_HEIGHT + CARD_GAP_Y) / 2 : rowStride();
    }

    private void refreshTextCacheLanguage() {
        Language language = Language.getInstance();
        if (language != this.cachedLanguage) {
            this.cachedLanguage = language;
            this.nameTextCache.clear();
            this.progressTextCache.clear();
        }
    }

    private String categoryNameText(CategoryEntryData category) {
        return this.nameTextCache.computeIfAbsent(category, ignored -> category.name().getString());
    }

    private String categoryProgressText(CategoryEntryData category) {
        return this.progressTextCache.computeIfAbsent(category, ignored -> Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.category.progress",
                category.unlocked(), category.total()).getString());
    }

    private String entryNameText(CatalogEntryData entry) {
        return this.nameTextCache.computeIfAbsent(entry, ignored -> entry.displayName().getString());
    }

    private static void drawBorder(GuiGraphics graphics, UiRect rect, int color) {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.y() + 1, color);
        graphics.fill(rect.x(), rect.bottom() - 1, rect.right(), rect.bottom(), color);
        graphics.fill(rect.x(), rect.y(), rect.x() + 1, rect.bottom(), color);
        graphics.fill(rect.right() - 1, rect.y(), rect.right(), rect.bottom(), color);
    }

    private int categoryLeft() {
        return layout.leftPageX() + (layout.leftPageWidth() - CATEGORY_GRID_WIDTH) / 2;
    }

    private UiRect screenBounds(int index) {
        UiRect cell = contentBounds(index);
        return new UiRect(cell.x() + (mode == Mode.CATEGORIES ? categoryLeft() : buttonX()),
                view.toScreenY(cell.y()), cell.width(), cell.height());
    }

    private void syncViewport() {
        int left = Math.min(buttonX(), categoryLeft());
        this.view.setViewport(left, listTop(), buttonX() + buttonWidth() + SCROLLBAR_GAP + SCROLLBAR_WIDTH - left, listBottom() - listTop());
        this.view.setScrollbarWidth(SCROLLBAR_WIDTH);
        this.view.setStep(scrollStep());
    }

    private void syncContentHeight() {
        this.view.setContentHeight(mode == Mode.CATEGORIES ? CATEGORY_METRICS.contentHeight(categories.size())
                : TABLE_METRICS.contentHeight(entries.size()));
        this.view.setStep(scrollStep());
    }

    private int itemsToPixels(int items) {
        return (items / itemsPerRow()) * stride();
    }

    private int firstVisibleIndex() {
        return (this.view.offset() / stride()) * itemsPerRow();
    }

    private int itemsPerRow() {
        return this.mode == Mode.CATEGORIES ? 2 : 1;
    }

    private int stride() {
        return this.mode == Mode.CATEGORIES ? CARD_HEIGHT + CARD_GAP_Y : rowStride();
    }

    private int visibleCategoryRows() {
        return Math.max(1, (listBottom() - listTop() + CARD_GAP_Y) / (CARD_HEIGHT + CARD_GAP_Y));
    }

    private int visibleTableRows() {
        return Math.max(1, (listBottom() - listTop() + JournalLayout.CATALOG_ROW_GAP) / rowStride());
    }

    private int rowStride() {
        return JournalLayout.CATALOG_ROW_HEIGHT + JournalLayout.CATALOG_ROW_GAP;
    }

    private int listTop() {
        return this.layout.leftPageY() + JournalLayout.CATALOG_LIST_TOP;
    }

    private int listBottom() {
        return this.layout.leftPageY() + JournalLayout.CATALOG_LIST_BOTTOM;
    }

    private int buttonX() {
        return this.layout.leftPageX() + (this.layout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                + JournalLayout.CATALOG_X_OFFSET;
    }

    private int buttonWidth() {
        return JournalLayout.CATALOG_TEXTURE_WIDTH - SCROLLBAR_WIDTH - SCROLLBAR_GAP - 1;
    }

    public enum Mode {CATEGORIES, TABLES}

    public record ClickResult(int index, boolean toggleExpansion) {
        public static final ClickResult NONE = new ClickResult(-1, false);
    }

}
