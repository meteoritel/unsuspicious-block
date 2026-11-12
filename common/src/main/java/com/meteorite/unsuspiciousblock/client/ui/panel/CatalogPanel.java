package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiScrollView;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiFocusState;
import com.meteorite.meteosuikit.api.input.UiFocusTarget;
import com.meteorite.meteosuikit.api.input.UiInputContext;
import com.meteorite.meteosuikit.api.input.UiInteractionSession;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.layout.UiGridMetrics;
import com.meteorite.meteosuikit.api.layout.UiLayoutNode;
import com.meteorite.meteosuikit.api.layout.UiLayoutSource;
import com.meteorite.meteosuikit.api.layout.UiRowMetrics;
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
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;

/**
 * 考古笔记左页目录——渲染分类网格与可平滑滚动的战利品表层级列表。
 *
 * <p>滚动偏移与滚动条由 {@link UiScrollView} 统一持有（像素口径），本类维护平滑阻尼目标偏移并在每帧
 * 推进；对外持久化/快照仍以条目索引为单位。</p>
 */
public final class CatalogPanel implements UiFocusTarget, UiInteractionSession.Target, UiLayoutSource {
    private BiConsumer<ClickResult, Boolean> onActivate = (click, shift) -> { };
    private final UiFocusState focus = new UiFocusState();
    private IntPredicate navigate = key -> false;
    public void setNavigation(IntPredicate navigate) { this.navigate = navigate; }
    @Override public UiFocusState focusState() { return focus; }
    @Override public UiRect bounds() { return view.viewport(); }
    @Override public Component accessibleName() { return Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"); }
    @Override public boolean activate() { return navigate.test(GLFW.GLFW_KEY_ENTER); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) { return isFocused() && navigate.test(key); }
    private ClickResult pressed = ClickResult.NONE;
    public void setOnActivate(BiConsumer<ClickResult, Boolean> action) { onActivate = action; }
    @Override public UiPointerHit hitTest(double x, double y) {
        if (!containsMouse(x, y)) return null;
        UiPointerHit thumb = view.hitTest(x, y);
        if (thumb != null) return thumb.within(this);
        int index = hoveredIndex(x, y);
        UiPointerHit hit = UiPointerHit.leaf(this, x, y);
        return index < 0 ? hit : hit.withRow(this, index, index, view.viewport());
    }
    @Override public boolean mousePressed(UiInputContext context) {
        if (view.mousePressed(context)) {
            syncScrollImmediate();
            return true;
        }
        if (context.button() != 0) return false;
        pressed = handleClick(context.pointerX(), context.pointerY());
        if (pressed.index() < 0) return false;
        context.scope().interaction().begin(this, UiInteractionSession.Source.POINTER, 0);
        return true;
    }
    @Override public boolean mouseReleased(UiInputContext context) {
        ClickResult clicked = handleClick(context.pointerX(), context.pointerY());
        ClickResult original = pressed;
        pressed = ClickResult.NONE;
        if (original.index() >= 0 && original.equals(clicked)) onActivate.accept(clicked, context.shift());
        return true;
    }
    @Override public void interactionEnded(UiInteractionSession.EndReason reason) { pressed = ClickResult.NONE; }
    @Override public boolean mouseScrolled(UiInputContext context, double sx, double sy) {
        if (sy == 0.0 || this.view.maxOffset() <= 0) {
            return false;
        }
        this.targetOffset = Mth.clamp(this.targetOffset - sy * this.view.step(), 0.0, this.view.maxOffset());
        return true;
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
    private static final int CARD_WIDTH = 64;
    private static final int CARD_HEIGHT = 49;
    private static final int CARD_GAP_X = 5;
    private static final int CARD_GAP_Y = 5;
    private static final int SCROLLBAR_WIDTH = 3;
    private static final int SCROLLBAR_GAP = 2;
    private static final double SMOOTH_SCROLL_RATE = 18.0;
    private static final double SMOOTH_SCROLL_SNAP_PX = 0.35;
    private static final UiGridMetrics CATEGORY_METRICS = new UiGridMetrics(2, CARD_WIDTH, CARD_HEIGHT, CARD_GAP_X, CARD_GAP_Y);
    public static final int CATEGORY_GRID_WIDTH = CARD_WIDTH * 2 + CARD_GAP_X;

    private JournalBookBackground.BookLayout layout;
    private final UiScrollView view = new UiScrollView();
    private double targetOffset;
    private double animatedOffset;
    private long lastScrollFrameMs;
    private final List<CategoryEntryData> categories = new ArrayList<>();
    private final List<CatalogEntryData> entries = new ArrayList<>();
    private final Map<ResourceLocation, Integer> categoryScrollTicks = new HashMap<>();
    private final Map<ResourceLocation, Integer> entryScrollTicks = new HashMap<>();
    private final Map<Object, String> nameTextCache = new IdentityHashMap<>();
    private final Map<Object, String> progressTextCache = new IdentityHashMap<>();
    private final PanelTextMetrics metrics = new PanelTextMetrics();
    private Language cachedLanguage;
    private Mode mode = Mode.CATEGORIES;

    public CatalogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.view.setScrollbarVisible(true);
        syncViewport();
        syncContentHeight();
        syncScrollImmediate();
    }

    public void applyLayout(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        syncViewport();
        syncScrollImmediate();
    }

    /** 每渲染帧推进一次平滑滚动阻尼插值；拖动滚动条时直接跟随真实位置。 */
    public void advanceFrame() {
        long now = Util.getMillis();
        if (this.view.isDragging()) {
            this.lastScrollFrameMs = now;
            syncScrollImmediate();
            return;
        }
        int max = this.view.maxOffset();
        this.targetOffset = Mth.clamp(this.targetOffset, 0.0, max);
        if (this.lastScrollFrameMs == 0L) {
            this.lastScrollFrameMs = now;
            this.animatedOffset = this.targetOffset;
            this.view.setOffset((int) Math.round(this.animatedOffset));
            return;
        }
        double dt = Math.min(0.1, Math.max(0.0, (now - this.lastScrollFrameMs) / 1000.0));
        this.lastScrollFrameMs = now;
        double delta = this.targetOffset - this.animatedOffset;
        if (Math.abs(delta) <= SMOOTH_SCROLL_SNAP_PX) {
            this.animatedOffset = this.targetOffset;
        } else {
            double alpha = 1.0 - Math.exp(-dt * SMOOTH_SCROLL_RATE);
            this.animatedOffset += delta * alpha;
        }
        this.view.setOffset((int) Math.round(this.animatedOffset));
    }

    public void setCategories(List<CategoryEntryData> values) {
        int carriedOffset = firstVisibleIndex();
        this.mode = Mode.CATEGORIES;
        this.categories.clear();
        this.categories.addAll(values);
        this.nameTextCache.clear();
        this.progressTextCache.clear();
        this.metrics.clear();
        syncContentHeight();
        this.view.setOffset(itemsToPixels(carriedOffset));
        syncScrollImmediate();
    }

    public void setEntries(List<CatalogEntryData> values) {
        boolean sameMode = this.mode == Mode.TABLES;
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
        syncScrollImmediate();
    }

    public Mode mode() {
        return this.mode;
    }

    /** 使用绘制时同一视口像素区间与展示语义，未解锁表不导出真实身份。 */
    @Override public List<UiLayoutNode> layoutNodes() {
        List<UiLayoutNode> nodes = new ArrayList<>();
        int count = mode == Mode.CATEGORIES ? categories.size() : entries.size();
        int[] range = visibleRange(count);
        for (int index = range[0]; index < range[1]; index++) {
            boolean category = mode == Mode.CATEGORIES;
            UiRect rect = category ? categoryRect(index) : new UiRect(buttonX(), view.toScreenY(index * rowStride()),
                    buttonWidth(), JournalLayout.CATALOG_ROW_HEIGHT);
            boolean unlocked = category || entries.get(index).unlocked();
            Component text = category ? categories.get(index).name() : unlocked ? entries.get(index).displayName() : Component.empty();
            String key = category ? "category/" + categories.get(index).id()
                    : unlocked ? "table/" + entries.get(index).id() : "";
            nodes.add(new UiLayoutNode(UiLayoutNode.Kind.CONTROL, rect, text, 0, 0, unlocked, true, List.of())
                    .identified(key, this).clippedTo(view.viewport()));
        }
        return List.copyOf(nodes);
    }

    public void ensureIndexVisible(int index) {
        ensureIndexVisible(index, false);
    }

    public void ensureIndexVisible(int index, boolean smooth) {
        if (index < 0) return;
        UiRect rect = this.mode == Mode.CATEGORIES ? CATEGORY_METRICS.cellBounds(index)
                : new UiRect(0, index * rowStride(), buttonWidth(), JournalLayout.CATALOG_ROW_HEIGHT);
        if (!smooth) {
            this.view.ensureVisible(rect);
            syncScrollImmediate();
            return;
        }
        int current = this.view.offset();
        this.view.setOffset((int) Math.round(this.targetOffset));
        this.view.ensureVisible(rect);
        int nextTarget = this.view.offset();
        this.view.setOffset(current);
        this.targetOffset = nextTarget;
    }

    public int visibleItemCount() {
        return this.mode == Mode.CATEGORIES ? visibleCategoryRows() * 2 : visibleTableRows();
    }

    public int getScrollOffset() {
        return firstVisibleIndex();
    }

    public void setScrollOffset(int scrollOffset) {
        this.view.setOffset(itemsToPixels(scrollOffset));
        syncScrollImmediate();
    }

    public ClickResult handleClick(double mouseX, double mouseY) {
        if (!view.containsContent(mouseX, mouseY)) return ClickResult.NONE;
        double y = view.toContentY(mouseY);
        if (this.mode == Mode.CATEGORIES) {
            int index = CATEGORY_METRICS.indexAt(mouseX - categoryLeft(), y, categories.size());
            return index < 0 ? ClickResult.NONE : new ClickResult(index, false);
        }
        if (mouseX < buttonX() || mouseX >= buttonX() + buttonWidth()) return ClickResult.NONE;
        int index = UiRowMetrics.fixedRowIndex(y, rowStride(), JournalLayout.CATALOG_ROW_HEIGHT, entries.size());
        if (index < 0) return ClickResult.NONE;
        CatalogEntryData entry = entries.get(index);
        return new ClickResult(index, entry.hasChildren() && mouseX < buttonX() + 15 + entry.depth() * 8);
    }

    public int hoveredIndex(double mouseX, double mouseY) {
        return handleClick(mouseX, mouseY).index();
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= buttonX() && mouseX <= this.view.viewport().right()
                && mouseY >= listTop() && mouseY <= listBottom();
    }

    public void render(GuiGraphics graphics, Font font, int selectedIndex, int mouseX, int mouseY) {
        refreshTextCacheLanguage();
        this.metrics.beginFrame(font);
        UiRenderLayers.enableScissor(graphics,
                new UiRect(view.viewport().x(), listTop(), view.viewport().width(), listBottom() - listTop()));
        try {
            if (this.mode == Mode.CATEGORIES) renderCategories(graphics, font, selectedIndex, mouseX, mouseY);
            else renderEntries(graphics, font, selectedIndex, mouseX, mouseY);
        } finally {
            UiRenderLayers.disableScissor(graphics);
        }
        this.view.renderScrollbar(graphics, () -> UnsuspiciousUiTheme.PARCHMENT);
    }

    private void renderCategories(GuiGraphics graphics, Font font, int selectedIndex, int mouseX, int mouseY) {
        int[] range = visibleRange(this.categories.size());
        for (int index = range[0]; index < range[1]; index++) {
            CategoryEntryData category = this.categories.get(index);
            UiRect rect = categoryRect(index);
            boolean hovered = rect.contains(mouseX, mouseY);
            boolean selected = index == selectedIndex;
            int bg = hovered || selected ? CARD_HOVER : CARD_BG;
            graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), bg);
            drawBorder(graphics, rect, selected ? SELECTED_COLOR : CARD_BORDER);
            int iconX = rect.x() + (rect.width() - 16) / 2;
            int iconY = rect.y() + 3;
            UiRenderLayers.item(graphics, new UiRect(iconX, iconY, 16, 16),
                    local -> local.renderItem(category.icon(), iconX, iconY));
            int ticks = hovered ? this.categoryScrollTicks.merge(category.id(), 1, Integer::sum) : 0;
            if (!hovered) this.categoryScrollTicks.put(category.id(), 0);
            PanelTextMetrics.Measured name = this.metrics.measure(categoryNameText(category), font);
            TextScroll.draw(graphics, font, name.text(), name.width(), rect.x() + 4, rect.y() + 22,
                    rect.width() - 8, NORMAL_COLOR, hovered, ticks, true);
            PanelTextMetrics.Measured progress = this.metrics.measure(categoryProgressText(category), font);
            TextScroll.draw(graphics, font, progress.text(), progress.width(),
                    rect.x() + 4, rect.bottom() - 13,
                    rect.width() - 8, UiTextScheme.PARCHMENT.label(), hovered, ticks, true);
        }
    }

    private void renderEntries(GuiGraphics graphics, Font font, int selectedIndex, int mouseX, int mouseY) {
        int[] range = visibleRange(this.entries.size());
        for (int index = range[0]; index < range[1]; index++) {
            CatalogEntryData entry = this.entries.get(index);
            int y = view.toScreenY(index * rowStride());
            boolean hovered = mouseX >= buttonX() && mouseX <= buttonX() + buttonWidth()
                    && mouseY >= y && mouseY < y + JournalLayout.CATALOG_ROW_HEIGHT;
            renderEntry(graphics, font, entry, buttonX(), y, hovered, index == selectedIndex);
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
    private int[] visibleRange(int totalCount) {
        if (totalCount <= 0) {
            return new int[]{0, 0};
        }
        int stride = stride();
        int perRow = itemsPerRow();
        int offset = this.view.offset();
        int viewportHeight = this.view.viewport().height();
        int firstRow = Math.max(0, offset / stride);
        int endRow = viewportHeight <= 0 ? firstRow : (offset + viewportHeight - 1) / stride + 1;
        int from = Math.min(totalCount, firstRow * perRow);
        int to = Math.min(totalCount, endRow * perRow);
        return new int[]{from, to};
    }

    private void syncScrollImmediate() {
        this.targetOffset = this.view.offset();
        this.animatedOffset = this.targetOffset;
        this.lastScrollFrameMs = Util.getMillis();
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

    private int categoryLeft() { return layout.leftPageX() + (layout.leftPageWidth() - CATEGORY_GRID_WIDTH) / 2; }

    private UiRect categoryRect(int index) {
        UiRect cell = CATEGORY_METRICS.cellBounds(index);
        return new UiRect(categoryLeft() + cell.x(), view.toScreenY(cell.y()), cell.width(), cell.height());
    }

    private void syncViewport() {
        int left = Math.min(buttonX(), categoryLeft());
        this.view.setViewport(left, listTop(), buttonX() + buttonWidth() + SCROLLBAR_GAP + SCROLLBAR_WIDTH - left, listBottom() - listTop());
        this.view.setScrollbarWidth(SCROLLBAR_WIDTH);
        this.view.setStep(scrollStep());
    }

    private void syncContentHeight() {
        this.view.setContentHeight(mode == Mode.CATEGORIES ? CATEGORY_METRICS.contentHeight(categories.size())
                : entries.isEmpty() ? 0 : entries.size() * rowStride() - JournalLayout.CATALOG_ROW_GAP);
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

    public enum Mode { CATEGORIES, TABLES }

    public record CategoryEntryData(ResourceLocation id, Component name, Component description,
                                    ItemStack icon, int unlocked, int total) {
    }

    public record CatalogEntryData(ResourceLocation id, Component displayName, boolean unlocked, boolean favorite,
                                   int depth, boolean child, boolean hasChildren, boolean expanded,
                                   List<ResourceLocation> parentPath, List<ResourceLocation> categoryIds) {
        public CatalogEntryData {
            parentPath = List.copyOf(parentPath);
            categoryIds = List.copyOf(categoryIds);
        }
    }

    public record ClickResult(int index, boolean toggleExpansion) {
        public static final ClickResult NONE = new ClickResult(-1, false);
    }

}
