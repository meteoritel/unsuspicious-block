package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.style.UiTextScheme;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalRichTooltip;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.layout.UiLayoutSource;
import com.meteorite.meteosuikit.api.layout.UiLayoutNode;
import com.meteorite.meteosuikit.api.layout.UiGridMetrics;
import com.meteorite.meteosuikit.api.render.UiIcon;
import net.minecraft.client.Minecraft;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.core.world.GameTimeFormatHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 考古笔记分类首页的信息面板，负责汇总并渲染玩家统计与最近发现。
 */
public final class WelcomeStatsPanel implements UiInputTarget, UiLayoutSource {
    // 与语义色表同值，直接引用以消除重复字面量（值未变）
    private static final int TITLE_COLOR = UiTextScheme.PARCHMENT.title();
    // 次要标签：原字面量 0x8A7358 在书页底色 #E8DCBC 上只有 3.29:1，改引语义色表的 LABEL（5.03:1）。
    private static final int LABEL_COLOR = UiTextScheme.PARCHMENT.label();
    private static final int VALUE_COLOR = UiTextScheme.PARCHMENT.body();
    private static final int SEPARATOR_COLOR = JournalPalette.SEPARATOR;
    private static final int COLUMN_GAP = 8;
    private static final int ITEM_SIZE = 16;
    private static final int ITEM_GAP = 4;

    private JournalBookBackground.BookLayout layout;
    private long lastConnectionEpoch = Long.MIN_VALUE;
    private long lastLogRevision = Long.MIN_VALUE;
    private long lastNameRevision = Long.MIN_VALUE;
    private long lifetimeEntries;
    private int currentEntries;
    private int notedEntries;
    private Component latestRecord = Component.translatable(
            "screen.unsuspiciousblock.archaeology_journal.welcome.none");
    private List<ItemStack> latestItems = List.of();

    public WelcomeStatsPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void applyLayout(JournalBookBackground.BookLayout layout) { this.layout = layout; }

    public void render(GuiGraphics graphics, Font font) {
        refreshSnapshot();

        int pageX = this.layout.rightPageX();
        int pageRight = this.layout.rightPageRight();
        int titleX = pageX + 8;
        int titleWidth = this.layout.rightPageWidth() - 16;
        int titleY = this.layout.rightPageY() + 18;
        drawCenteredTrimmed(graphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.welcome.title"),
                titleX, titleY, titleWidth, TITLE_COLOR);

        int separatorLeft = pageX + 14;
        int separatorRight = pageRight - 14;
        int firstSeparatorY = titleY + font.lineHeight + 8;
        graphics.fill(separatorLeft, firstSeparatorY, separatorRight, firstSeparatorY + 1, SEPARATOR_COLOR);

        int contentX = pageX + 12;
        int contentWidth = this.layout.rightPageWidth() - 24;
        int columnWidth = (contentWidth - COLUMN_GAP) / 2;
        int rightColumnX = contentX + columnWidth + COLUMN_GAP;
        int firstRowY = firstSeparatorY + 10;

        drawMetricCard(graphics, font, contentX, firstRowY, columnWidth,
                "screen.unsuspiciousblock.archaeology_journal.welcome.stat.lifetime_entries",
                Long.toString(this.lifetimeEntries));
        drawMetricCard(graphics, font, rightColumnX, firstRowY, columnWidth,
                "screen.unsuspiciousblock.archaeology_journal.welcome.stat.chest_opens",
                Long.toString(JournalClientConnection.log().totals().containers()));
        drawMetricCard(graphics, font, contentX, firstRowY + 20, columnWidth,
                "screen.unsuspiciousblock.archaeology_journal.welcome.stat.current_entries",
                Integer.toString(this.currentEntries));
        drawMetricCard(graphics, font, rightColumnX, firstRowY + 20, columnWidth,
                "screen.unsuspiciousblock.archaeology_journal.welcome.stat.noted_entries",
                Integer.toString(this.notedEntries));

        int recentY = recentY(font);
        Component recentLabel = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.welcome.latest_record");
        graphics.drawString(font, recentLabel.getString(), contentX, recentY, LABEL_COLOR, false);
        int recordValY = recentY + font.lineHeight + 2;
        String valText = TextScroll.trimToWidth(font, this.latestRecord.getString(), contentWidth);
        graphics.drawString(font, valText, contentX, recordValY, VALUE_COLOR, false);

        int discoveryY = discoveryY(font);
        Component discoveryLabel = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.welcome.latest_discovery");
        graphics.drawString(font, discoveryLabel.getString(), contentX, discoveryY, LABEL_COLOR, false);
        int itemsY = discoveryY + 14;
        if (this.latestItems.isEmpty()) {
            graphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.welcome.none"),
                    contentX, itemsY + 4, VALUE_COLOR, false);
        } else {
            UiGridMetrics grid = itemGrid();
            for (int index = 0; index < latestItems.size(); index++) {
                UiRect cell = grid.cellBounds(index);
                JournalPalette.drawSlot(graphics, contentX + cell.x() - 1, itemsY + cell.y() - 1, ITEM_SIZE + 2, false);
                new UiIcon.Item(latestItems.get(index)).render(graphics, contentX + cell.x(), itemsY + cell.y());
            }
        }

        int secondSeparatorY = this.layout.rightPageBottom() - 38;
        graphics.fill(separatorLeft, secondSeparatorY, separatorRight, secondSeparatorY + 1, SEPARATOR_COLOR);
    }

    private int recentY(Font font) {
        return layout.rightPageY() + 85 + font.lineHeight;
    }

    private int discoveryY(Font font) {
        return recentY(font) + font.lineHeight * 2 + 10;
    }

    private UiGridMetrics itemGrid() {
        return new UiGridMetrics(Math.max(1, (layout.rightPageWidth() - 24) / (ITEM_SIZE + ITEM_GAP)), ITEM_SIZE, ITEM_SIZE, ITEM_GAP, 4);
    }

    private UiRect itemBounds(int index, Font font) {
        UiRect cell = itemGrid().cellBounds(index);
        return new UiRect(layout.rightPageX() + 12 + cell.x(), discoveryY(font) + 14 + cell.y(), ITEM_SIZE, ITEM_SIZE);
    }

    /**
     * 欢迎页实际物品位置随同一输入成员贡献，展示行不声明独立动作。
     */
    @Override
    public List<UiLayoutNode> layoutNodes() {
        refreshSnapshot();
        Font font = Minecraft.getInstance().font;
        List<UiLayoutNode> items = new ArrayList<>();
        for (int index = 0; index < latestItems.size(); index++) {
            UiRect cell = itemBounds(index, font);
            items.add(new UiLayoutNode(UiLayoutNode.Kind.ROW, cell, latestItems.get(index).getHoverName(),
                    0, 0, true, true, List.of()));
        }
        UiRect page = new UiRect(layout.rightPageX(), layout.rightPageY(), layout.rightPageWidth(),
                layout.rightPageBottom() - layout.rightPageY());
        return List.of(new UiLayoutNode(UiLayoutNode.Kind.FRAME, page, Component.empty(), 0, 0,
                true, true, items, "journal/welcome", page, this));
    }

    @Override
    public UiPointerHit hitTest(double x, double y) {
        refreshSnapshot();
        Font font = Minecraft.getInstance().font;
        int left = layout.rightPageX() + 12;
        int recentY = recentY(font);
        for (int index = 0; index < latestItems.size(); index++) {
            UiRect bounds = itemBounds(index, font);
            if (bounds.contains(x, y)) {
                ItemStack stack = latestItems.get(index);
                return UiPointerHit.leaf(this, x, y).withRow(this, index, stack, bounds)
                        .withPresentation((graphics, textFont, px, py) -> graphics.renderTooltip(textFont, stack, px, py));
            }
        }
        UiRect recent = new UiRect(left, recentY, layout.rightPageWidth() - 24, font.lineHeight * 2 + 4);
        if (!recent.contains(x, y)) {
            return null;
        }
        return JournalRichTooltip.attachText(UiPointerHit.leaf(this, x, y)
                .withTextTooltip(List.of(latestRecord.copy().withStyle(TooltipBuilder.BODY)), recent));
    }

    private void refreshSnapshot() {
        long epoch = JournalClientConnection.epoch();
        long logRevision = JournalClientConnection.log().summaryRevision();
        long nameRevision = ClientLootTableLanguageStore.revision();
        if (epoch == this.lastConnectionEpoch && logRevision == this.lastLogRevision && nameRevision == this.lastNameRevision) {
            return;
        }

        var totals = JournalClientConnection.log().totals();
        this.lifetimeEntries = totals.archaeology();
        this.currentEntries = totals.count();
        this.notedEntries = totals.noted();
        var latest = totals.latest();
        if (latest == null) {
            this.latestRecord = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.welcome.none");
            this.latestItems = List.of();
        } else {
            int day = GameTimeFormatHelper.fromTime(
                    latest.updated().gameTime(), latest.updated().dayTime()).day();
            Component tableName = ClientLootTableLanguageStore.displayName(latest.tableId());
            this.latestRecord = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.welcome.latest_record_value", day, tableName);
            ArrayList<ItemStack> itemStacks = new ArrayList<>();
            for (var loot : JournalFormatHelper.sortedLootEntries(latest.loot())) {
                ItemStack stack = JournalFormatHelper.createLootStack(loot.getKey(), loot.getValue());
                if (stack != null && !stack.isEmpty()) {
                    itemStacks.add(stack);
                }
            }
            this.latestItems = List.copyOf(itemStacks);
        }

        this.lastConnectionEpoch = epoch;
        this.lastLogRevision = logRevision;
        this.lastNameRevision = nameRevision;
    }

    private static void drawMetricCard(GuiGraphics graphics, Font font, int x, int y, int width,
                                       String labelKey, String value) {
        JournalPalette.drawStatPill(graphics, x - 2, y - 3, width + 4, font.lineHeight + 6, false);
        Component label = Component.translatable(labelKey);
        int valueWidth = font.width(value);
        int labelWidth = Math.max(0, width - valueWidth - 4);
        String labelText = TextScroll.trimToWidth(font, label.getString(), labelWidth);
        graphics.drawString(font, labelText, x + 1, y, LABEL_COLOR, false);
        graphics.drawString(font, value, x + width - valueWidth - 1, y, UiTextScheme.PARCHMENT.title(), false);
    }


    private static void drawCenteredTrimmed(GuiGraphics graphics, Font font, Component text,
                                            int x, int y, int width, int color) {
        String rendered = TextScroll.trimToWidth(font, text.getString(), width);
        graphics.drawString(font, rendered, x + (width - font.width(rendered)) / 2, y, color, false);
    }
}
