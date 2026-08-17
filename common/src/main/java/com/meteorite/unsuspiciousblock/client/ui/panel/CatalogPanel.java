package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.ScrollTextHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 考古笔记左页目录——渲染分类网格与可连续滚动的战利品表层级列表。
 */
public final class CatalogPanel {
    private static final ResourceLocation ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/catalog_entry.png");
    private static final int[] UNLOCKED_STATE_V = {0, 19, 38};
    private static final int LOCKED_STATE_V = 57;
    private static final int CHILD_COLOR = 0x6B7D46;
    private static final int NORMAL_COLOR = 0x5A422C;
    private static final int SELECTED_COLOR = 0x7B3E18;
    private static final int FAVORITE_COLOR = 0xC8A014;
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
    public static final int CATEGORY_GRID_WIDTH = CARD_WIDTH * 2 + CARD_GAP_X;

    private final JournalBookBackground.BookLayout layout;
    private final List<CategoryEntryData> categories = new ArrayList<>();
    private final List<CatalogEntryData> entries = new ArrayList<>();
    private final Map<ResourceLocation, Integer> categoryScrollTicks = new HashMap<>();
    private final Map<ResourceLocation, Integer> entryScrollTicks = new HashMap<>();
    private Mode mode = Mode.CATEGORIES;
    private int scrollOffset;
    private boolean draggingScrollbar;
    private int scrollbarDragOffset;

    public CatalogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void setCategories(List<CategoryEntryData> values) {
        this.mode = Mode.CATEGORIES;
        this.categories.clear();
        this.categories.addAll(values);
        clampScrollOffset();
    }

    public void setEntries(List<CatalogEntryData> values) {
        this.mode = Mode.TABLES;
        this.entries.clear();
        this.entries.addAll(values);
        clampScrollOffset();
    }

    public Mode mode() {
        return this.mode;
    }

    public void ensureIndexVisible(int index) {
        if (index < 0) return;
        int first = firstVisibleIndex();
        int visible = visibleItemCount();
        if (index < first) {
            this.scrollOffset = normalizeOffset(index);
        } else if (index >= first + visible) {
            int trailingOffset = index - visible + (this.mode == Mode.CATEGORIES ? 2 : 1);
            this.scrollOffset = normalizeOffset(trailingOffset);
        }
        clampScrollOffset();
    }

    public void scrollByRows(int rowDelta) {
        int itemDelta = this.mode == Mode.CATEGORIES ? rowDelta * 2 : rowDelta;
        this.scrollOffset = normalizeOffset(this.scrollOffset + itemDelta);
        clampScrollOffset();
    }

    public int visibleItemCount() {
        return this.mode == Mode.CATEGORIES ? visibleCategoryRows() * 2 : visibleTableRows();
    }

    public int getScrollOffset() {
        return this.scrollOffset;
    }

    public void setScrollOffset(int scrollOffset) {
        this.scrollOffset = normalizeOffset(scrollOffset);
        clampScrollOffset();
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
        return mouseX >= buttonX() && mouseX <= scrollbarTrackRect().right()
                && mouseY >= listTop() && mouseY <= listBottom();
    }

    public void render(GuiGraphics graphics, Font font, int selectedIndex, int mouseX, int mouseY) {
        if (this.mode == Mode.CATEGORIES) renderCategories(graphics, font, selectedIndex, mouseX, mouseY);
        else renderEntries(graphics, font, selectedIndex, mouseX, mouseY);
        renderScrollbar(graphics, mouseX, mouseY);
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
            ScrollTextHelper.draw(graphics, font, category.name().getString(), rect.x() + 4, rect.y() + 22,
                    rect.width() - 8, NORMAL_COLOR, hovered, ticks, true);
            Component progress = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.category.progress",
                    category.unlocked(), category.total());
            ScrollTextHelper.draw(graphics, font, progress.getString(), rect.x() + 4, rect.bottom() - 13,
                    rect.width() - 8, 0x7A6247, hovered, ticks, true);
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
        String text = entry.unlocked() ? entry.displayName().getString() : "";
        int ticks = hovered ? this.entryScrollTicks.merge(entry.id(), 1, Integer::sum) : 0;
        if (!hovered) this.entryScrollTicks.put(entry.id(), 0);
        ScrollTextHelper.draw(graphics, font, text, textX, y + 7,
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

    // 点击滚动条轨道会跳转，点击滑块后可持续拖拽。
    public boolean beginScrollbarDrag(double mouseX, double mouseY) {
        if (!hasOverflow() || !scrollbarTrackRect().contains(mouseX, mouseY)) return false;
        Rect thumb = scrollbarThumbRect();
        if (thumb.contains(mouseX, mouseY)) {
            this.scrollbarDragOffset = Mth.floor(mouseY) - thumb.y();
        } else {
            this.scrollbarDragOffset = thumb.height() / 2;
            updateScrollFromThumbY(Mth.floor(mouseY) - this.scrollbarDragOffset);
        }
        this.draggingScrollbar = true;
        return true;
    }

    public boolean dragScrollbar(double mouseY) {
        if (!this.draggingScrollbar) return false;
        updateScrollFromThumbY(Mth.floor(mouseY) - this.scrollbarDragOffset);
        return true;
    }

    public void endScrollbarDrag() {
        this.draggingScrollbar = false;
    }

    private void renderScrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!hasOverflow()) return;
        Rect track = scrollbarTrackRect();
        Rect thumb = scrollbarThumbRect();
        graphics.fill(track.x(), track.y(), track.right(), track.bottom(), SCROLLBAR_TRACK_COLOR);
        int color = this.draggingScrollbar || thumb.contains(mouseX, mouseY)
                ? SCROLLBAR_THUMB_HOVER_COLOR : SCROLLBAR_THUMB_COLOR;
        graphics.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), color);
    }

    private void updateScrollFromThumbY(int thumbY) {
        Rect track = scrollbarTrackRect();
        int thumbHeight = scrollbarThumbHeight();
        int travel = Math.max(1, track.height() - thumbHeight);
        int clampedY = Mth.clamp(thumbY, track.y(), track.bottom() - thumbHeight);
        int maxOffset = maxScrollOffset();
        this.scrollOffset = normalizeOffset(Math.round((clampedY - track.y()) / (float) travel * maxOffset));
        clampScrollOffset();
    }

    private Rect scrollbarTrackRect() {
        int x = buttonX() + buttonWidth() + SCROLLBAR_GAP;
        return new Rect(x, listTop(), SCROLLBAR_WIDTH, listBottom() - listTop());
    }

    private Rect scrollbarThumbRect() {
        Rect track = scrollbarTrackRect();
        int thumbHeight = scrollbarThumbHeight();
        int maxOffset = maxScrollOffset();
        int travel = track.height() - thumbHeight;
        int y = track.y() + (maxOffset > 0 ? Math.round(travel * (this.scrollOffset / (float) maxOffset)) : 0);
        return new Rect(track.x(), y, track.width(), thumbHeight);
    }

    private int scrollbarThumbHeight() {
        Rect track = scrollbarTrackRect();
        int size = itemCount();
        return Mth.clamp(Math.round(track.height() * (visibleItemCount() / (float) size)), 12, track.height());
    }

    private boolean hasOverflow() {
        return itemCount() > visibleItemCount();
    }

    private int itemCount() {
        return this.mode == Mode.CATEGORIES ? this.categories.size() : this.entries.size();
    }

    private int firstVisibleIndex() {
        return normalizeOffset(this.scrollOffset);
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

    private int normalizeOffset(int offset) {
        return this.mode == Mode.CATEGORIES ? Math.max(0, offset - Math.floorMod(offset, 2)) : Math.max(0, offset);
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

    private void clampScrollOffset() {
        this.scrollOffset = Mth.clamp(normalizeOffset(this.scrollOffset), 0, maxScrollOffset());
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
