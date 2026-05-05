package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/** 左侧战利品表目录面板 —— 自定义可滚动列表 */
public final class CatalogPanel {

    private static final ResourceLocation ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/catalog_entry.png");
    private static final int[] STATE_V = {0, 20, 39};
    private static final int[] STATE_H = {19, 18, 18};
    private static final int TEXT_INNER_PAD = 8;
    private static final int TEXT_Y_OFFSET = 7;
    private static final int SCROLL_PAUSE_WIDTH = 20;
    private static final int SCROLL_SPEED = 1;

    private final List<CatalogEntry> entries = new ArrayList<>();
    private final JournalBookBackground.BookLayout layout;
    private double scrollOffset;

    public CatalogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void setEntries(List<CatalogEntryData> entries) {
        this.entries.clear();
        for (CatalogEntryData entry : entries) {
            this.entries.add(new CatalogEntry(entry.displayName()));
        }
        this.scrollOffset = Mth.clamp(this.scrollOffset, 0.0, maxScroll());
    }

    public void ensureIndexVisible(int index) {
        if (index < 0 || index >= entries.size()) {
            return;
        }

        int listHeight = listHeight();
        int itemTop = index * JournalLayout.CATALOG_ROW_HEIGHT;
        int itemBottom = itemTop + JournalLayout.CATALOG_ROW_HEIGHT;
        if (itemTop < this.scrollOffset) {
            this.scrollOffset = itemTop;
        } else if (itemBottom > this.scrollOffset + listHeight) {
            this.scrollOffset = itemBottom - listHeight;
        }
        this.scrollOffset = Mth.clamp(this.scrollOffset, 0.0, maxScroll());
    }

    public void mouseScrolled(double scrollY) {
        setScrollOffset(this.scrollOffset - scrollY * JournalLayout.CATALOG_ROW_HEIGHT);
    }

    public int handleClick(double mouseX, double mouseY) {
        int listTop = listTop();
        int listBottom = listBottom();
        int buttonX = buttonX();
        int buttonWidth = buttonWidth();

        double maxScroll = maxScroll();
        if (maxScroll > 0.0) {
            int scrollbarX = scrollbarX();
            if (mouseX >= scrollbarX && mouseX <= scrollbarX + JournalLayout.CATALOG_SCROLLBAR_WIDTH
                    && mouseY >= listTop && mouseY <= listBottom) {
                double ratio = (mouseY - listTop) / Math.max(1.0, listBottom - listTop);
                setScrollOffset(ratio * maxScroll);
                return -1;
            }
        }

        for (int i = 0; i < entries.size(); i++) {
            int rowY = listTop + i * JournalLayout.CATALOG_ROW_HEIGHT - (int) this.scrollOffset;
            if (rowY + JournalLayout.CATALOG_ROW_HEIGHT <= listTop || rowY >= listBottom) {
                continue;
            }
            if (mouseX >= buttonX && mouseX <= buttonX + buttonWidth
                    && mouseY >= rowY && mouseY < rowY + JournalLayout.CATALOG_ROW_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= listLeft() && mouseX <= listRight()
                && mouseY >= listTop() && mouseY <= listBottom();
    }

    public void render(GuiGraphics guiGraphics, Font font, int selectedIndex, int mouseX, int mouseY) {
        if (entries.isEmpty()) {
            return;
        }

        int buttonX = buttonX();
        int buttonWidth = buttonWidth();
        int listTop = listTop();
        int listBottom = listBottom();
        int listHeight = listHeight();

        int firstVisible = Math.max(0, (int) this.scrollOffset / JournalLayout.CATALOG_ROW_HEIGHT);
        int lastVisible = Math.min(entries.size() - 1,
                (int) ((this.scrollOffset + listHeight) / JournalLayout.CATALOG_ROW_HEIGHT) + 1);

        for (int i = firstVisible; i <= lastVisible && i < entries.size(); i++) {
            CatalogEntry entry = entries.get(i);
            int rowY = listTop + i * JournalLayout.CATALOG_ROW_HEIGHT - (int) this.scrollOffset;
            if (rowY + JournalLayout.CATALOG_ROW_HEIGHT <= listTop || rowY >= listBottom) {
                continue;
            }
            boolean selected = i == selectedIndex;
            boolean hovered = mouseX >= buttonX && mouseX <= buttonX + buttonWidth
                    && mouseY >= rowY && mouseY < rowY + JournalLayout.CATALOG_ROW_HEIGHT;
            renderEntry(guiGraphics, font, entry, buttonX, rowY, buttonWidth, hovered, selected);
        }

        renderScrollbar(guiGraphics, listTop, listBottom, listHeight);
    }

    private void renderEntry(GuiGraphics guiGraphics, Font font, CatalogEntry entry,
                             int x, int y, int width, boolean hovered, boolean selected) {
        int state = selected ? 2 : (hovered ? 1 : 0);
        int rowHeight = JournalLayout.CATALOG_ROW_HEIGHT;
        guiGraphics.blit(ENTRY_TEXTURE,
                x, y, width, rowHeight,
                0, STATE_V[state],
                JournalLayout.CATALOG_TEXTURE_WIDTH, STATE_H[state],
                JournalLayout.CATALOG_TEXTURE_WIDTH, JournalLayout.CATALOG_TEXTURE_HEIGHT);

        if (!entry.wasHovered && hovered) {
            entry.scrollTicks = 0;
        }
        entry.wasHovered = hovered;
        if (hovered) {
            entry.scrollTicks++;
        }

        String displayText = entry.displayName.getString();
        int textColor = selected ? 0x7B3E18 : 0x5A422C;
        int textMaxWidth = width - TEXT_INNER_PAD * 2;
        int textWidth = font.width(displayText);
        if (textWidth <= textMaxWidth) {
            int textX = x + (width - textWidth) / 2;
            guiGraphics.drawString(font, displayText, textX, y + TEXT_Y_OFFSET, textColor, false);
            return;
        }

        guiGraphics.enableScissor(x + TEXT_INNER_PAD, y, x + width - TEXT_INNER_PAD, y + rowHeight);
        int overflow = textWidth - textMaxWidth;
        int offset = 0;
        if (hovered && overflow > 0) {
            offset = (entry.scrollTicks * SCROLL_SPEED / 2) % (overflow + SCROLL_PAUSE_WIDTH * 2);
            if (offset > overflow + SCROLL_PAUSE_WIDTH) {
                offset = overflow + SCROLL_PAUSE_WIDTH * 2 - offset;
            }
            if (offset > overflow) {
                offset = overflow;
            }
        }
        int textX = x + TEXT_INNER_PAD - offset;
        guiGraphics.drawString(font, displayText, textX, y + TEXT_Y_OFFSET, textColor, false);
        guiGraphics.disableScissor();
    }

    private void renderScrollbar(GuiGraphics guiGraphics, int listTop, int listBottom, int listHeight) {
        double maxScroll = maxScroll();
        if (maxScroll <= 0.0) {
            return;
        }

        int scrollbarX = scrollbarX();
        int totalContentHeight = entries.size() * JournalLayout.CATALOG_ROW_HEIGHT;
        guiGraphics.fill(scrollbarX, listTop, scrollbarX + JournalLayout.CATALOG_SCROLLBAR_WIDTH, listBottom, 0x18FFFFFF);
        int handleHeight = Math.max(JournalLayout.CATALOG_SCROLLBAR_MIN_HANDLE,
                (int) ((long) listHeight * listHeight / totalContentHeight));
        int handleTravel = listHeight - handleHeight;
        int handleY = listTop + (int) (this.scrollOffset * handleTravel / maxScroll);
        guiGraphics.fill(scrollbarX, handleY,
                scrollbarX + JournalLayout.CATALOG_SCROLLBAR_WIDTH, handleY + handleHeight, 0x60FFFFFF);
    }

    private void setScrollOffset(double offset) {
        this.scrollOffset = Mth.clamp(offset, 0.0, maxScroll());
    }

    private double maxScroll() {
        return Math.max(0.0, entries.size() * JournalLayout.CATALOG_ROW_HEIGHT - listHeight());
    }

    private int listLeft() {
        return this.layout.leftPageX() + JournalLayout.CATALOG_LEFT_PAD;
    }

    private int listRight() {
        return this.layout.leftPageRight() - JournalLayout.CATALOG_LEFT_PAD;
    }

    private int listTop() {
        return this.layout.leftPageY() + JournalLayout.CATALOG_LIST_TOP;
    }

    private int listBottom() {
        return this.layout.leftPageBottom() - JournalLayout.CATALOG_LIST_BOTTOM_PAD;
    }

    private int listHeight() {
        return listBottom() - listTop();
    }

    private int buttonX() {
        return listLeft();
    }

    private int buttonWidth() {
        return scrollbarX() - buttonX() - 2;
    }

    private int scrollbarX() {
        return listRight() - JournalLayout.CATALOG_SCROLLBAR_WIDTH;
    }

    public record CatalogEntryData(ResourceLocation id, Component displayName) {
    }

    private static final class CatalogEntry {
        private final Component displayName;
        private int scrollTicks;
        private boolean wasHovered;

        private CatalogEntry(Component displayName) {
            this.displayName = displayName;
        }
    }
}
