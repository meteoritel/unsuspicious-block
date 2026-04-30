package com.meteorite.unsuspiciousblock.client.ui.journal;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/** 左侧战利品表目录面板 —— 可滚动列表（类似村民交易面板） */
public final class CatalogPanel {
    private static final int ROW_HEIGHT = 22;
    private static final int TEXTURE_WIDTH = 160;
    private static final int TEXTURE_HEIGHT = 66;
    private static final int TITLE_OFFSET_Y = 6;
    private static final int LIST_TOP_OFFSET = 28;
    private static final int LIST_BOTTOM_PAD = 6;
    private static final int SCROLLBAR_WIDTH = 4;
    private static final int SCROLLBAR_MIN_HANDLE = 16;

    private static final ResourceLocation ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/catalog_entry.png");

    private final List<CatalogEntry> entries = new ArrayList<>();
    private final JournalBookBackground.BookLayout layout;
    private double scrollOffset;

    public CatalogPanel(List<CatalogEntry> initialEntries, JournalBookBackground.BookLayout layout) {
        this.entries.addAll(initialEntries);
        this.layout = layout;
        this.scrollOffset = 0.0;
    }

    /** 更新条目列表并重置滚动 */
    public void setEntries(List<CatalogEntry> entries) {
        this.entries.clear();
        this.entries.addAll(entries);
        this.scrollOffset = 0.0;
    }

    /** 确保指定索引可见 */
    public void ensureIndexVisible(int index) {
        if (index < 0 || index >= entries.size()) return;
        int listTop = layout.leftPageY() + LIST_TOP_OFFSET;
        int listBottom = layout.leftPageBottom() - LIST_BOTTOM_PAD;
        int listHeight = listBottom - listTop;
        int itemTop = listTop + index * ROW_HEIGHT;
        int itemBottom = itemTop + ROW_HEIGHT;
        if (itemTop < listTop + scrollOffset) {
            scrollOffset = Math.max(0, itemTop - listTop);
        } else if (itemBottom > listTop + scrollOffset + listHeight) {
            scrollOffset = Math.min(maxScroll(), itemBottom - listTop - listHeight);
        }
        scrollOffset = Mth.clamp(scrollOffset, 0.0, maxScroll());
    }

    public void mouseScrolled(double scrollY) {
        setScrollOffset(scrollOffset - scrollY * ROW_HEIGHT);
    }

    private void setScrollOffset(double offset) {
        scrollOffset = Mth.clamp(offset, 0.0, maxScroll());
    }

    private double maxScroll() {
        int listTop = layout.leftPageY() + LIST_TOP_OFFSET;
        int listBottom = layout.leftPageBottom() - LIST_BOTTOM_PAD;
        int listHeight = listBottom - listTop;
        return Math.max(0.0, entries.size() * ROW_HEIGHT - listHeight);
    }

    /** 渲染目录面板 */
    public void render(GuiGraphics guiGraphics, Font font, int selectedIndex, int mouseX, int mouseY) {
        int buttonX = layout.leftPageX() + 4;
        int buttonWidth = layout.leftPageWidth() - 12; // 留出滚动条空间
        int listTop = layout.leftPageY() + LIST_TOP_OFFSET;
        int listBottom = layout.leftPageBottom() - LIST_BOTTOM_PAD;
        int listHeight = listBottom - listTop;

        // 标题
        guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.catalog"),
                buttonX + 4, layout.leftPageY() + TITLE_OFFSET_Y, 0x4A3320, false);

        // 空状态
        if (entries.isEmpty()) {
            guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_catalog"),
                    buttonX + 4, listTop, 0x7A6247, false);
            return;
        }

        double maxScroll = maxScroll();
        int totalContentHeight = entries.size() * ROW_HEIGHT;

        // 渲染可见条目
        int firstVisible = Math.max(0, (int) scrollOffset / ROW_HEIGHT);
        int lastVisible = Math.min(entries.size() - 1,
                (int) (scrollOffset + listHeight) / ROW_HEIGHT + 1);

        for (int i = firstVisible; i <= lastVisible && i < entries.size(); i++) {
            CatalogEntry entry = entries.get(i);
            int rowY = listTop + i * ROW_HEIGHT - (int) scrollOffset;
            boolean selected = i == selectedIndex;
            boolean hovered = mouseX >= buttonX && mouseX <= buttonX + buttonWidth
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            int state = selected ? 2 : (hovered ? 1 : 0);
            guiGraphics.blit(ENTRY_TEXTURE,
                    buttonX, rowY, buttonWidth, ROW_HEIGHT,
                    0, state * ROW_HEIGHT,
                    TEXTURE_WIDTH, ROW_HEIGHT,
                    TEXTURE_WIDTH, TEXTURE_HEIGHT);

            String displayText = entry.unlocked() ? entry.displayName().getString() : "???";
            int textColor = entry.unlocked() ? (selected ? 0x7B3E18 : 0x5A422C) : 0x777777;
            String clipped = truncate(font, displayText, buttonWidth - 16);
            guiGraphics.drawString(font, clipped, buttonX + 8, rowY + 7, textColor, false);
        }

        // 滚动条
        if (maxScroll > 0.0) {
            int scrollbarX = layout.leftPageRight() - SCROLLBAR_WIDTH - 4;
            guiGraphics.fill(scrollbarX, listTop, scrollbarX + SCROLLBAR_WIDTH, listBottom, 0x18FFFFFF);
            int handleHeight = Math.max(SCROLLBAR_MIN_HANDLE,
                    (int) ((long) listHeight * listHeight / totalContentHeight));
            int handleTravel = listHeight - handleHeight;
            int handleY = listTop + (int) (scrollOffset * handleTravel / maxScroll);
            guiGraphics.fill(scrollbarX, handleY, scrollbarX + SCROLLBAR_WIDTH, handleY + handleHeight, 0x60FFFFFF);
        }
    }

    /** 处理点击，返回被点击条目的索引，或 -1 */
    public int handleClick(double mouseX, double mouseY) {
        int listTop = layout.leftPageY() + LIST_TOP_OFFSET;
        int listBottom = layout.leftPageBottom() - LIST_BOTTOM_PAD;
        int buttonX = layout.leftPageX() + 4;
        int buttonWidth = layout.leftPageWidth() - 12;

        // 滚动条快速跳转
        double maxScroll = maxScroll();
        if (maxScroll > 0.0) {
            int scrollbarX = layout.leftPageRight() - SCROLLBAR_WIDTH - 4;
            if (mouseX >= scrollbarX && mouseX <= scrollbarX + SCROLLBAR_WIDTH
                    && mouseY >= listTop && mouseY <= listBottom) {
                double ratio = (mouseY - listTop) / (listBottom - listTop);
                setScrollOffset(ratio * maxScroll);
                return -1;
            }
        }

        // 条目点击
        for (int i = 0; i < entries.size(); i++) {
            int rowY = listTop + i * ROW_HEIGHT - (int) scrollOffset;
            if (mouseX >= buttonX && mouseX <= buttonX + buttonWidth
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    /** 判断鼠标是否在目录面板区域内 */
    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= layout.leftPageX() && mouseX <= layout.leftPageRight()
                && mouseY >= layout.leftPageY() && mouseY <= layout.leftPageBottom();
    }

    private static String truncate(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) return text;
        if (font.width(text) <= maxWidth) return text;
        int ellipsisWidth = font.width("…");
        if (maxWidth <= ellipsisWidth) return "";
        return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth) + "…";
    }

    public record CatalogEntry(ResourceLocation id, Component displayName, boolean unlocked) {
    }
}
