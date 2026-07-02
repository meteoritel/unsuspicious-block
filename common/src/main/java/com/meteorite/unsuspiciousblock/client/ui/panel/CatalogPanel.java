package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.support.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/** 左侧战利品表目录面板 —— 分页目录列表 */
public final class CatalogPanel {

    private static final ResourceLocation ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/catalog_entry.png");
    private static final int[] UNLOCKED_STATE_V = {0, 19, 38};
    private static final int[] UNLOCKED_STATE_H = {19, 19, 19};
    private static final int LOCKED_STATE_V = 57;
    private static final int LOCKED_STATE_H = 19;
    private static final int TEXT_INNER_PAD = 8;
    private static final int TEXT_Y_OFFSET = 7;
    // 收藏星标渲染参数
    private static final int FAVORITE_STAR_RIGHT_PAD = 4;
    private static final int FAVORITE_STAR_COLOR = 0xFFC8A014;

    private final List<CatalogEntry> entries = new ArrayList<>();
    private final JournalBookBackground.BookLayout layout;
    private int page;

    public CatalogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void setEntries(List<CatalogEntryData> entries) {
        this.entries.clear();
        for (CatalogEntryData entry : entries) {
            this.entries.add(new CatalogEntry(entry.id(), entry.displayName(), entry.unlocked(), entry.favorite()));
        }
        this.page = Mth.clamp(this.page, 0, Math.max(0, pageCount() - 1));
    }

    public void ensureIndexVisible(int index) {
        if (index < 0 || index >= entries.size()) {
            return;
        }
        this.page = Mth.clamp(index / itemsPerPage(), 0, Math.max(0, pageCount() - 1));
    }

    public void mouseScrolled(double scrollY) {
        if (pageCount() > 1) {
            changePage(scrollY < 0.0 ? 1 : -1);
        }
    }

    public void changePage(int delta) {
        this.page = Mth.clamp(this.page + delta, 0, Math.max(0, pageCount() - 1));
    }

    public int pageCount() {
        if (entries.isEmpty()) {
            return 1;
        }
        int itemsPerPage = itemsPerPage();
        return (entries.size() + itemsPerPage - 1) / itemsPerPage;
    }

    public int getPage() {
        return this.page;
    }

    public void setPage(int page) {
        this.page = Mth.clamp(page, 0, Math.max(0, pageCount() - 1));
    }

    public int handleClick(double mouseX, double mouseY) {
        int from = this.page * itemsPerPage();
        int to = Math.min(entries.size(), from + itemsPerPage());
        int buttonX = buttonX();
        int buttonWidth = buttonWidth();

        for (int i = from; i < to; i++) {
            int visualIndex = i - from;
            int rowY = listTop() + visualIndex * rowStride();
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
        int from = this.page * itemsPerPage();
        int to = Math.min(entries.size(), from + itemsPerPage());

        for (int i = from; i < to; i++) {
            CatalogEntry entry = entries.get(i);
            int visualIndex = i - from;
            int rowY = listTop() + visualIndex * rowStride();
            boolean selected = i == selectedIndex;
            boolean hovered = mouseX >= buttonX && mouseX <= buttonX + buttonWidth
                    && mouseY >= rowY && mouseY < rowY + JournalLayout.CATALOG_ROW_HEIGHT;
            renderEntry(guiGraphics, font, entry, buttonX, rowY, buttonWidth, hovered, selected);
        }
    }

    private void renderEntry(GuiGraphics guiGraphics, Font font, CatalogEntry entry,
                             int x, int y, int width, boolean hovered, boolean selected) {
        int rowHeight = JournalLayout.CATALOG_ROW_HEIGHT;
        if (entry.unlocked) {
            int state = selected ? 2 : (hovered ? 1 : 0);
            guiGraphics.blit(ENTRY_TEXTURE,
                    x, y, width, rowHeight,
                    0, UNLOCKED_STATE_V[state],
                    JournalLayout.CATALOG_TEXTURE_WIDTH, UNLOCKED_STATE_H[state],
                    JournalLayout.CATALOG_TEXTURE_WIDTH, JournalLayout.CATALOG_TEXTURE_HEIGHT);
        } else {
            guiGraphics.blit(ENTRY_TEXTURE,
                    x, y, width, rowHeight,
                    0, LOCKED_STATE_V,
                    JournalLayout.CATALOG_TEXTURE_WIDTH, LOCKED_STATE_H,
                    JournalLayout.CATALOG_TEXTURE_WIDTH, JournalLayout.CATALOG_TEXTURE_HEIGHT);
        }

        if (!entry.wasHovered && hovered) {
            entry.scrollTicks = 0;
        }
        entry.wasHovered = hovered;
        if (hovered) {
            entry.scrollTicks++;
        }

        String displayText = entry.unlocked
                ? entry.displayName.getString()
                : "";
        int textColor;
        if (entry.unlocked) {
            textColor = selected ? 0x7B3E18 : 0x5A422C;
        } else {
            textColor = selected ? 0x6E4D34 : 0x7A6247;
        }
        // 收藏条目右侧预留给星标的空间，避免长名滚动遮挡星标
        int textMaxWidth = width - TEXT_INNER_PAD * 2 - (entry.favorite ? 10 : 0);
        ScrollTextHelper.draw(guiGraphics, font, displayText,
                x + TEXT_INNER_PAD, y, y + TEXT_Y_OFFSET, textMaxWidth, JournalLayout.CATALOG_ROW_HEIGHT,
                textColor, hovered, entry.scrollTicks, true);

        // 收藏星标：仅对已收藏条目绘制
        if (entry.favorite) {
            String star = "★";
            int starWidth = font.width(star);
            int starX = x + width - starWidth - FAVORITE_STAR_RIGHT_PAD;
            int starY = y + (rowHeight - 8) / 2;
            guiGraphics.drawString(font, star, starX, starY, FAVORITE_STAR_COLOR, false);
        }
    }

    private int itemsPerPage() {
        return Math.max(1, (listHeight() + JournalLayout.CATALOG_ROW_GAP) / rowStride());
    }

    private int rowStride() {
        return JournalLayout.CATALOG_ROW_HEIGHT + JournalLayout.CATALOG_ROW_GAP;
    }

    private int listLeft() {
        return buttonX();
    }

    private int listRight() {
        return buttonX() + buttonWidth();
    }

    private int listTop() {
        return this.layout.leftPageY() + JournalLayout.CATALOG_LIST_TOP;
    }

    private int listBottom() {
        return this.layout.leftPageY() + JournalLayout.CATALOG_LIST_BOTTOM;
    }

    private int listHeight() {
        return listBottom() - listTop();
    }

    private int buttonX() {
        return this.layout.leftPageX() + (this.layout.leftPageWidth() - JournalLayout.CATALOG_TEXTURE_WIDTH) / 2
                + JournalLayout.CATALOG_X_OFFSET;
    }

    private int buttonWidth() {
        return JournalLayout.CATALOG_TEXTURE_WIDTH;
    }

    public record CatalogEntryData(ResourceLocation id, Component displayName, boolean unlocked, boolean favorite) {
    }

    private static final class CatalogEntry {
        private final ResourceLocation id;
        private final Component displayName;
        private final boolean unlocked;
        private final boolean favorite;
        private int scrollTicks;
        private boolean wasHovered;

        private CatalogEntry(ResourceLocation id, Component displayName, boolean unlocked, boolean favorite) {
            this.id = id;
            this.displayName = displayName;
            this.unlocked = unlocked;
            this.favorite = favorite;
        }
    }
}
