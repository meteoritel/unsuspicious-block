package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.text.PanelTextMetrics;
import com.meteorite.meteosuikit.api.style.UiTextScheme;

import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.layout.UiRowMetrics;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.render.UiRenderLayers;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.meteosuikit.api.control.UiPaginationState;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 考古信息面板 —— 玩家个人进度：进度条 + 已发现物品列表 + 模组来源
 */
public final class DetailOverlayPanel implements PagePanel {
    private static final int BAR_HEIGHT = 12;
    // 与语义色表 BODY 同值，直接引用以消除重复字面量（值未变）
    private static final int LABEL_COLOR = UiTextScheme.PARCHMENT.body();
    // 空态等弱化文本：原字面量 #7A6247 在书页底色 #E8DCBC 上只有 4.20:1，改引语义色表 LABEL（5.03:1）。
    private static final int MUTED_COLOR = UiTextScheme.PARCHMENT.label();
    private static final int ITEM_ROW_HEIGHT = 16;
    private static final int FOOTER_HEIGHT = 32;
    private static final int MOD_SOURCE_TOP_OFFSET = 42;

    private JournalBookBackground.BookLayout layout;
    private final UiPaginationState pagination = new UiPaginationState(this::computePageCount);
    private int parsedCount;
    private int totalCount;
    private String modSource;
    private int progressLabelScrollTicks;
    private int discoveredLabelScrollTicks;
    private int emptyStateScrollTicks;
    private List<DiscoveredItemEntry> unlockedItems = List.of();
    // 已测宽度缓存（P-10）：与 setData 同一失效事件（外加语言/字体实例变化）
    private final PanelTextMetrics metrics = new PanelTextMetrics();

    public void applyLayout(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public DetailOverlayPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.parsedCount = 0;
        this.totalCount = 0;
        this.modSource = "";
    }

    public void setData(ResourceLocation tableId, int parsedCount, int totalCount,
                        List<IntroItem> allItems) {
        this.parsedCount = parsedCount;
        this.totalCount = totalCount;
        this.metrics.clear();
        this.pagination.reset();
        if (tableId != null) {
            this.modSource = Services.PLATFORM.getModDisplayName(tableId.getNamespace());
        } else {
            this.modSource = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.mod_source_unknown").getString();
        }
        this.unlockedItems = new ArrayList<>();
        List<DiscoveredItemEntry> highlightedEntries = new ArrayList<>();
        List<DiscoveredItemEntry> nonHighlightedEntries = new ArrayList<>();
        for (IntroItem item : allItems) {
            if (item.unlocked()) {
                DiscoveredItemEntry entry = new DiscoveredItemEntry(item);
                if (item.highlighted()) {
                    highlightedEntries.add(entry);
                } else {
                    nonHighlightedEntries.add(entry);
                }
            }
        }
        this.unlockedItems.addAll(highlightedEntries);
        this.unlockedItems.addAll(nonHighlightedEntries);
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return PagePanel.containsPageBounds(mouseX, mouseY,
                this.layout.rightPageX(), this.layout.rightPageY(),
                this.layout.rightPageRight(), this.layout.rightPageBottom());
    }

    public void render(GuiGraphics guiGraphics, Font font, UiPointer pointer) {
        this.metrics.beginFrame(font);
        int leftX = this.layout.rightPageX() + 8;
        int contentWidth = this.layout.rightPageWidth() - 20;
        int y = this.layout.rightPageY() + JournalLayout.INTRO_TOP;

        // 解析进度标题
        Component progressLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.parse_progress");
        PanelTextMetrics.Measured progressLabelText = this.metrics.measure(progressLabel.getString(), font);
        int progressLabelWidth = progressLabelText.width();
        boolean progressLabelHovered = pointer.contains(new UiRect(leftX, y, contentWidth, font.lineHeight));
        this.progressLabelScrollTicks = progressLabelHovered ? this.progressLabelScrollTicks + 1 : 0;
        TextScroll.draw(guiGraphics, font, progressLabelText.text(), progressLabelWidth,
                leftX, y, contentWidth,
                LABEL_COLOR, progressLabelHovered, this.progressLabelScrollTicks, false);

        y += 14;

        // 进度条本体（与目录、日志详情统一使用 JournalPalette 羊皮纸色系进度条）
        int barTop = y;
        Component barLabel = Component.empty();
        if (this.totalCount > 0) {
            int percent = (int) ((double) this.parsedCount / this.totalCount * 100.0);
            barLabel = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.parse_progress_value",
                    percent, this.parsedCount, this.totalCount);
        }
        JournalPalette.drawProgressBar(guiGraphics, font, leftX, barTop, contentWidth, BAR_HEIGHT,
                this.parsedCount, this.totalCount, barLabel, false, 0);
        y += BAR_HEIGHT + 10;

        // 已发现物品列表
        Component discoveredLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.discovered_items");
        boolean discoveredLabelHovered = pointer.contains(new UiRect(leftX, y, contentWidth, font.lineHeight));
        this.discoveredLabelScrollTicks = discoveredLabelHovered ? this.discoveredLabelScrollTicks + 1 : 0;
        PanelTextMetrics.Measured discoveredText = this.metrics.measure(discoveredLabel.getString(), font);
        TextScroll.draw(guiGraphics, font, discoveredText.text(), discoveredText.width(),
                leftX, y, contentWidth,
                LABEL_COLOR, discoveredLabelHovered, this.discoveredLabelScrollTicks, false);
        y = listStartY(y);

        if (this.unlockedItems.isEmpty()) {
            int emptyWidth = contentWidth - 4;
            boolean emptyHovered = pointer.contains(new UiRect(leftX + 2, y, emptyWidth, font.lineHeight));
            this.emptyStateScrollTicks = emptyHovered ? this.emptyStateScrollTicks + 1 : 0;
            PanelTextMetrics.Measured emptyText = this.metrics.measure(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.no_discoveries").getString(), font);
            TextScroll.draw(guiGraphics, font, emptyText.text(), emptyText.width(),
                    leftX + 2, y, emptyWidth, MUTED_COLOR, emptyHovered, this.emptyStateScrollTicks, false);
            y += 12;
        } else {
            this.emptyStateScrollTicks = 0;
            int maxVisibleItems = maxVisibleItems(y);
            int page = this.pagination.getPage();
            int from = page * maxVisibleItems;
            int to = Math.min(this.unlockedItems.size(), from + maxVisibleItems);
            int showCount = Math.max(0, to - from);
            int countColumnWidth = 0;
            for (int i = from; i < to; i++) {
                countColumnWidth = Math.max(countColumnWidth, this.metrics.measure(
                        formatCount(this.unlockedItems.get(i).item.count()), font).width());
            }

            for (int i = 0; i < showCount; i++) {
                DiscoveredItemEntry entry = this.unlockedItems.get(from + i);
                IntroItem item = entry.item;
                int rowY = y + i * ITEM_ROW_HEIGHT;

                // 图标
                ItemStack stack = item.stack();
                UiRenderLayers.item(guiGraphics, new UiRect(leftX + 2, rowY - 1, 16, 16), local -> {
                    local.renderItem(stack, leftX + 2, rowY - 1);
                    local.renderItemDecorations(font, stack, leftX + 2, rowY - 1);
                });

                // 获得次数
                PanelTextMetrics.Measured count = this.metrics.measure(formatCount(item.count()), font);
                int countX = leftX + contentWidth - countColumnWidth;
                guiGraphics.drawString(font, count.text(), countX + countColumnWidth - count.width(),
                        rowY + 2, LABEL_COLOR, false);

                // 名称
                int nameX = leftX + 22;
                int nameMaxWidth = Math.max(0, countX - nameX - 4);
                boolean hovered = pointer.contains(new UiRect(nameX, rowY, nameMaxWidth, ITEM_ROW_HEIGHT));
                if (!entry.wasHovered && hovered) {
                    entry.scrollTicks = 0;
                }
                entry.wasHovered = hovered;
                if (hovered) {
                    entry.scrollTicks++;
                }
                // 名称（搜索不匹配时变暗）
                int nameColor = entry.highlighted ? LABEL_COLOR : MUTED_COLOR;
                PanelTextMetrics.Measured name = this.metrics.measure(item.displayName().getString(), font);
                TextScroll.draw(guiGraphics, font, name.text(), name.width(),
                        nameX, rowY + 2, nameMaxWidth, nameColor, hovered, entry.scrollTicks, false);
            }
            y += showCount * ITEM_ROW_HEIGHT + 6;
        }

        // 模组来源：名称足够短时与标签同行，过长时从下一行开始换行
        y = Math.max(y, this.layout.rightPageBottom() - MOD_SOURCE_TOP_OFFSET);
        Component modLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.mod_source");
        PanelTextMetrics.Measured labelText = this.metrics.measure(modLabel.getString(), font);
        int labelWidth = labelText.width();
        int gap = this.metrics.measure(" ", font).width();
        int sourceWidth = this.metrics.measure(this.modSource, font).width();
        guiGraphics.drawString(font, labelText.text(), leftX, y, LABEL_COLOR, false);
        if (labelWidth + gap + sourceWidth <= contentWidth) {
            guiGraphics.drawString(font, this.modSource, leftX + labelWidth + gap, y, LABEL_COLOR, false);
        } else {
            int sourceY = y + font.lineHeight + 2;
            List<FormattedCharSequence> sourceLines = font.split(
                    Component.literal(this.modSource), contentWidth);
            for (int index = 0; index < sourceLines.size(); index++) {
                guiGraphics.drawString(font, sourceLines.get(index), leftX,
                        sourceY + index * (font.lineHeight + 2), LABEL_COLOR, false);
            }
        }
    }

    public int pageCount() {
        return this.pagination.pageCount();
    }

    public int getPage() {
        return this.pagination.getPage();
    }

    public void changePage(int delta) {
        this.pagination.changePage(delta);
    }

    public void setPage(int page) {
        this.pagination.setPage(page);
    }

    @Override
    public UiPointerHit hitTest(double x, double y) {
        int leftX = this.layout.rightPageX() + 8;
        int contentWidth = this.layout.rightPageWidth() - 20;
        int listY = listStartY(this.layout.rightPageY() + JournalLayout.INTRO_TOP
                + 14 + BAR_HEIGHT + 10);
        if (x < leftX || x >= leftX + contentWidth) {
            return null;
        }
        int maxVisibleItems = maxVisibleItems(listY);
        int visibleIndex = UiRowMetrics.fixedRowIndex(y - listY, ITEM_ROW_HEIGHT, ITEM_ROW_HEIGHT, maxVisibleItems);
        if (visibleIndex < 0) {
            return null;
        }
        int itemIndex = this.pagination.getPage() * maxVisibleItems + visibleIndex;
        if (itemIndex >= this.unlockedItems.size()) {
            return null;
        }
        ItemStack stack = this.unlockedItems.get(itemIndex).item.stack();
        if (stack.isEmpty()) {
            return null;
        }
        UiRect bounds = new UiRect(leftX, listY + visibleIndex * ITEM_ROW_HEIGHT,
                contentWidth, ITEM_ROW_HEIGHT);
        return UiPointerHit.leaf(this, x, y).withRow(this, visibleIndex, stack, bounds)
                .withPresentation((g, font, px, py) -> g.renderTooltip(font, stack, px, py));
    }

    private int maxVisibleItems(int listStartY) {
        return Math.max(1, (this.layout.rightPageBottom() - listStartY - FOOTER_HEIGHT) / ITEM_ROW_HEIGHT);
    }

    private int listStartY(int discoveredLabelY) {
        return discoveredLabelY + 14;
    }

    private int computePageCount() {
        if (this.unlockedItems.isEmpty()) {
            return 1;
        }
        int listStartY = listStartY(this.layout.rightPageY() + JournalLayout.INTRO_TOP + 14 + BAR_HEIGHT + 10);
        int maxVisibleItems = maxVisibleItems(listStartY);
        return Math.max(1, (this.unlockedItems.size() + maxVisibleItems - 1) / maxVisibleItems);
    }

    private static String formatCount(int count) {
        return "×" + count;
    }

    private static final class DiscoveredItemEntry {
        private final IntroItem item;
        private final boolean highlighted;
        private int scrollTicks;
        private boolean wasHovered;

        private DiscoveredItemEntry(IntroItem item) {
            this.item = item;
            this.highlighted = item.highlighted();
        }
    }

    /**
     * Intro 列表使用的轻量物品引用，记录值来自当前选中表。
     */
    public record IntroItem(ResourceLocation id, Component displayName, LootResultSignature signature,
                            boolean unlocked, int count, boolean highlighted) {
        public ItemStack stack() {
            ItemStack preview = this.signature.createPreviewStack();
            return preview.isEmpty() ? new ItemStack(BuiltInRegistries.ITEM.get(this.id)) : preview;
        }
    }
}
