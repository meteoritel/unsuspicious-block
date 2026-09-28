package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControlStyle;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiScrollView;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考古笔记左页目录——渲染分类网格与可连续滚动的战利品表层级列表。
 *
 * <p>滚动偏移与滚动条由 {@link UiScrollView} 统一持有（像素口径），本类只负责把「条目数」口径的公开 API
 * 换算成像素：分类模式每行 2 项、步长 = 卡片高度 + 纵向间距；表格模式每行 1 项、步长 = 行高 + 行间距。
 * 公开 API 与迁移前的像素表现保持一致。</p>
 */
public final class CatalogPanel {
    private static final ResourceLocation ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/catalog_entry.png");
    private static final int[] UNLOCKED_STATE_V = {0, 19, 38};
    private static final int LOCKED_STATE_V = 57;
    // 子表行名称与 ∈ 标记：原 #6B7D46 在纸面 #E8DCBC 上仅 3.31:1，压暗到 #4F6033 后为 5.04:1，
    // 保留"子表 = 绿"的色相语义，不与父行的暖棕混淆。
    private static final int CHILD_COLOR = 0xFF4F6033;
    // 与语义色表 BODY 同值，直接引用以消除重复字面量（值未变）
    private static final int NORMAL_COLOR = UiTextPalette.Parchment.BODY;
    private static final int SELECTED_COLOR = 0x7B3E18;
    // 收藏星标：原字面量 #C8A014 在纸面 #E8DCBC 上仅 1.81:1，改引语义色表的 ACCENT（压暗后 4.82:1）
    private static final int FAVORITE_COLOR = UiTextPalette.Parchment.ACCENT;
    private static final int CARD_BORDER = 0x8B6914;
    private static final int CARD_BG = 0x18A67C42;
    private static final int CARD_HOVER = 0x30C8A050;
    private static final int CARD_WIDTH = 64;
    private static final int CARD_HEIGHT = 49;
    private static final int CARD_GAP_X = 5;
    private static final int CARD_GAP_Y = 5;
    private static final int SCROLLBAR_WIDTH = 3;
    private static final int SCROLLBAR_GAP = 2;
    private static final int SCROLLBAR_TRACK_COLOR = 0x406B4C2A;
    private static final int SCROLLBAR_THUMB_COLOR = 0xB08B6914;
    private static final int SCROLLBAR_THUMB_HOVER_COLOR = 0xD07B3E18;
    private static final UiControlStyle SCROLLBAR_STYLE = scrollbarStyle(SCROLLBAR_THUMB_COLOR);
    private static final UiControlStyle SCROLLBAR_HOVER_STYLE = scrollbarStyle(SCROLLBAR_THUMB_HOVER_COLOR);
    public static final int CATEGORY_GRID_WIDTH = CARD_WIDTH * 2 + CARD_GAP_X;

    private final JournalBookBackground.BookLayout layout;
    private final UiScrollView view = new UiScrollView();
    private final List<CategoryEntryData> categories = new ArrayList<>();
    private final List<CatalogEntryData> entries = new ArrayList<>();
    private final Map<ResourceLocation, Integer> categoryScrollTicks = new HashMap<>();
    private final Map<ResourceLocation, Integer> entryScrollTicks = new HashMap<>();
    // 逐帧文案缓存：CategoryEntryData / CatalogEntryData 都是不可变 record，同一实例的文案只取决于语言代码。
    // 失效点：setCategories / setEntries（换数据）或语言代码变化（整表重算）。
    private final Map<Object, String> nameTextCache = new IdentityHashMap<>();
    private final Map<Object, String> progressTextCache = new IdentityHashMap<>();
    // 已测宽度缓存（P-10）：与文案缓存同一失效事件（语言/字体实例、setCategories/setEntries）
    private final PanelTextMetrics metrics = new PanelTextMetrics();
    private String cachedLanguage = "";
    private Mode mode = Mode.CATEGORIES;

    // 视口恒定贴着列表右边界，滚动条常开（UiScrollView 内部自带 maxOffset > 0 才显示）。
    public CatalogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.view.setScrollbarVisible(true);
        syncViewport();
        syncContentHeight();
    }

    // 迁移前 setCategories / setEntries 只换列表、不重置偏移：条目数偏移会跨模式直接带到新模式，
    // 故先按旧模式取出条目偏移，换完模式再按新模式步长换算，避免像素口径直接沿用而落到不同行。
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
    }

    public void setEntries(List<CatalogEntryData> values) {
        int carriedOffset = firstVisibleIndex();
        this.mode = Mode.TABLES;
        this.entries.clear();
        this.entries.addAll(values);
        this.nameTextCache.clear();
        this.progressTextCache.clear();
        this.metrics.clear();
        syncContentHeight();
        this.view.setOffset(itemsToPixels(carriedOffset));
    }

    public Mode mode() {
        return this.mode;
    }

    // 保留旧算法与两个 lookahead 常量，钳制交给 UiScrollView。
    public void ensureIndexVisible(int index) {
        if (index < 0) return;
        int first = firstVisibleIndex();
        int visible = visibleItemCount();
        if (index < first) {
            this.view.setOffset(itemsToPixels(index));
        } else if (index >= first + visible) {
            int trailingOffset = index - visible + (this.mode == Mode.CATEGORIES ? 2 : 1);
            this.view.setOffset(itemsToPixels(trailingOffset));
        }
    }

    // 行滚动换算成像素位移：分类模式一行 = 2 项。
    public void scrollByRows(int rowDelta) {
        this.view.setOffset(this.view.offset() + rowDelta * stride());
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
        if (this.mode == Mode.CATEGORIES) {
            int from = firstVisibleIndex();
            int to = Math.min(this.categories.size(), from + visibleItemCount());
            for (int index = from; index < to; index++) {
                Rect rect = categoryRect(index - from);
                if (rect.contains(mouseX, mouseY)) return new ClickResult(index, false);
            }
            return ClickResult.NONE;
        }
        int from = firstVisibleIndex();
        int to = Math.min(this.entries.size(), from + visibleItemCount());
        for (int index = from; index < to; index++) {
            int y = listTop() + (index - from) * rowStride();
            if (mouseX >= buttonX() && mouseX <= buttonX() + buttonWidth()
                    && mouseY >= y && mouseY < y + JournalLayout.CATALOG_ROW_HEIGHT) {
                CatalogEntryData entry = this.entries.get(index);
                boolean toggle = entry.hasChildren() && mouseX < buttonX() + 13 + entry.depth() * 8;
                return new ClickResult(index, toggle);
            }
        }
        return ClickResult.NONE;
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
        if (this.mode == Mode.CATEGORIES) renderCategories(graphics, font, selectedIndex, mouseX, mouseY);
        else renderEntries(graphics, font, selectedIndex, mouseX, mouseY);
        // 高亮条件与迁移前一致：拖拽中或指针落在滑块上。滑块几何由 UiScrollView 提供，不再复算。
        this.view.renderScrollbar(graphics, this.view.isDragging() || this.view.hitThumb(mouseX, mouseY)
                ? SCROLLBAR_HOVER_STYLE : SCROLLBAR_STYLE);
    }

    private void renderCategories(GuiGraphics graphics, Font font, int selectedIndex, int mouseX, int mouseY) {
        int from = firstVisibleIndex();
        int to = Math.min(this.categories.size(), from + visibleItemCount());
        for (int index = from; index < to; index++) {
            CategoryEntryData category = this.categories.get(index);
            Rect rect = categoryRect(index - from);
            boolean hovered = rect.contains(mouseX, mouseY);
            boolean selected = index == selectedIndex;
            int bg = hovered || selected ? CARD_HOVER : CARD_BG;
            graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), bg);
            drawBorder(graphics, rect, selected ? SELECTED_COLOR : CARD_BORDER);
            graphics.renderItem(category.icon(), rect.x() + (rect.width() - 16) / 2, rect.y() + 3);
            int ticks = hovered ? this.categoryScrollTicks.merge(category.id(), 1, Integer::sum) : 0;
            if (!hovered) this.categoryScrollTicks.put(category.id(), 0);
            PanelTextMetrics.Measured name = this.metrics.measure(categoryNameText(category), font);
            ScrollTextHelper.draw(graphics, font, name.text(), name.width(), rect.x() + 4, rect.y() + 22,
                    rect.width() - 8, NORMAL_COLOR, hovered, ticks, true);
            PanelTextMetrics.Measured progress = this.metrics.measure(categoryProgressText(category), font);
            ScrollTextHelper.draw(graphics, font, progress.text(), progress.width(),
                    rect.x() + 4, rect.bottom() - 13,
                    rect.width() - 8, UiTextPalette.Parchment.LABEL, hovered, ticks, true);
        }
    }

    private void renderEntries(GuiGraphics graphics, Font font, int selectedIndex, int mouseX, int mouseY) {
        int from = firstVisibleIndex();
        int to = Math.min(this.entries.size(), from + visibleItemCount());
        for (int index = from; index < to; index++) {
            CatalogEntryData entry = this.entries.get(index);
            int y = listTop() + (index - from) * rowStride();
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
            graphics.drawString(font, entry.expanded() ? "▼" : "▶", markerX, y + 6, NORMAL_COLOR, false);
        }
        int textX = markerX + (entry.hasChildren() ? 10 : 3);
        int rightReserve = (entry.child() ? 12 : 0) + (entry.favorite() ? 9 : 0) + 5;
        String text = entry.unlocked() ? entryNameText(entry) : "";
        int ticks = hovered ? this.entryScrollTicks.merge(entry.id(), 1, Integer::sum) : 0;
        if (!hovered) this.entryScrollTicks.put(entry.id(), 0);
        PanelTextMetrics.Measured measured = this.metrics.measure(text, font);
        ScrollTextHelper.draw(graphics, font, measured.text(), measured.width(), textX, y + 7,
                Math.max(8, x + buttonWidth() - rightReserve - textX),
                entry.child() ? CHILD_COLOR : selected ? SELECTED_COLOR : NORMAL_COLOR,
                hovered, ticks, false);
        int right = x + buttonWidth() - 5;
        if (entry.favorite()) {
            graphics.drawString(font, "★", right - 6, y + 6, FAVORITE_COLOR, false);
            right -= 9;
        }
        if (entry.child()) graphics.drawString(font, "∈", right - 6, y + 6, CHILD_COLOR, false);
    }

    // 语言代码变化时丢弃逐帧文案缓存：文字随语言变，滚动与悬停状态不随。
    // 资源重载而语言代码不变的情况仍由 setCategories / setEntries 兜底（与 ScenarioPanel.sceneLabel 同口径）。
    private void refreshTextCacheLanguage() {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!language.equals(this.cachedLanguage)) {
            this.cachedLanguage = language;
            this.nameTextCache.clear();
            this.progressTextCache.clear();
        }
    }

    // 分类名文案按数据实例缓存（Component 的解析结果只随语言变化）
    private String categoryNameText(CategoryEntryData category) {
        return this.nameTextCache.computeIfAbsent(category, ignored -> category.name().getString());
    }

    // 分类进度文案按数据实例缓存（数值来自不可变 record，只有语言会变）
    private String categoryProgressText(CategoryEntryData category) {
        return this.progressTextCache.computeIfAbsent(category, ignored -> Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.category.progress",
                category.unlocked(), category.total()).getString());
    }

    // 目录条目名文案按数据实例缓存
    private String entryNameText(CatalogEntryData entry) {
        return this.nameTextCache.computeIfAbsent(entry, ignored -> entry.displayName().getString());
    }

    private static void drawBorder(GuiGraphics graphics, Rect rect, int color) {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.y() + 1, color);
        graphics.fill(rect.x(), rect.bottom() - 1, rect.right(), rect.bottom(), color);
        graphics.fill(rect.x(), rect.y(), rect.x() + 1, rect.bottom(), color);
        graphics.fill(rect.right() - 1, rect.y(), rect.right(), rect.bottom(), color);
    }

    private Rect categoryRect(int visualIndex) {
        int col = visualIndex % 2;
        int row = visualIndex / 2;
        int x = this.layout.leftPageX() + (this.layout.leftPageWidth() - CATEGORY_GRID_WIDTH) / 2;
        return new Rect(x + col * (CARD_WIDTH + CARD_GAP_X),
                listTop() + row * (CARD_HEIGHT + CARD_GAP_Y), CARD_WIDTH, CARD_HEIGHT);
    }

    // 点击滚动条轨道会跳转，点击滑块后可持续拖拽；命中、跳转与拖拽位移全部由 UiScrollView 负责。
    public boolean beginScrollbarDrag(double mouseX, double mouseY) {
        return this.view.mousePressed(mouseX, mouseY, 0);
    }

    public boolean dragScrollbar(double mouseY) {
        this.view.mouseDragged(mouseY);
        // 整个拖动期间都算消费（与迁移前一致）：即使这一帧已到顶/到底、偏移没有变化。
        return this.view.isDragging();
    }

    public void endScrollbarDrag() {
        this.view.mouseReleased();
    }

    // 只覆写滚动条结构色，其余 token 沿用羊皮纸主题；两份样式仅滑块色不同，轨道色一致。
    private static UiControlStyle scrollbarStyle(int thumbColor) {
        UiControlStyle base = UiControlStyle.PARCHMENT;
        return new UiControlStyle(base.background(), base.hoverBackground(), base.pressedBackground(),
                base.selectedBackground(), base.disabledBackground(), base.focusOutline(),
                SCROLLBAR_TRACK_COLOR, thumbColor);
    }

    // 视口 = 列表右边界右侧的滚动条列，与旧的手算轨道矩形逐像素重合。
    private void syncViewport() {
        this.view.setViewport(buttonX() + buttonWidth() + SCROLLBAR_GAP, listTop(),
                SCROLLBAR_WIDTH, listBottom() - listTop());
    }

    // 内容高度 = 视口高度 + 旧「按条目数钳制」换算出的像素可滚距离，底部不留空白的行为与迁移前一致。
    private void syncContentHeight() {
        this.view.setContentHeight(this.view.viewport().height() + maxScrollOffsetPx());
    }

    // 旧 maxScrollOffset（条目数）换算成像素：分类模式行 = maxScrollOffset / 2，表格模式行 = maxScrollOffset。
    private int maxScrollOffsetPx() {
        return (maxScrollOffset() / itemsPerRow()) * stride();
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

    private int itemCount() {
        return this.mode == Mode.CATEGORIES ? this.categories.size() : this.entries.size();
    }

    private int visibleCategoryRows() {
        return Math.max(1, (listBottom() - listTop() + CARD_GAP_Y) / (CARD_HEIGHT + CARD_GAP_Y));
    }

    private int visibleTableRows() {
        return Math.max(1, (listBottom() - listTop() + JournalLayout.CATALOG_ROW_GAP) / rowStride());
    }

    private int maxScrollOffset() {
        int overflow = Math.max(0, itemCount() - visibleItemCount());
        return this.mode == Mode.CATEGORIES ? ((overflow + 1) / 2) * 2 : overflow;
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

    private record Rect(int x, int y, int width, int height) {
        int right() { return this.x + this.width; }
        int bottom() { return this.y + this.height; }
        boolean contains(double px, double py) {
            return px >= this.x && px < right() && py >= this.y && py < bottom();
        }
    }
}
