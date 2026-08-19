package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.world.GameTimeFormatHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 考古笔记分类首页的信息面板，负责汇总并渲染玩家统计与最近发现。
 */
public final class WelcomeStatsPanel {
    private static final int TITLE_COLOR = 0x3A2818;
    private static final int LABEL_COLOR = 0x8A7358;
    private static final int VALUE_COLOR = 0x5A3D23;
    private static final int SEPARATOR_COLOR = 0x608B6914;
    private static final int COLUMN_GAP = 8;
    private static final int ITEM_SIZE = 16;
    private static final int ITEM_GAP = 2;

    private final JournalBookBackground.BookLayout layout;
    private long lastLogRevision = Long.MIN_VALUE;
    private long lastCatalogRevision = Long.MIN_VALUE;
    private long lifetimeEntries;
    private int currentEntries;
    private int notedEntries;
    private Component latestRecord = Component.translatable(
            "screen.unsuspiciousblock.archaeology_journal.welcome.none");
    private List<ItemStack> latestItems = List.of();
    private boolean latestRecordHovered;
    private boolean latestRecordTrimmed;
    private int hoveredItemIndex = -1;

    public WelcomeStatsPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        refreshSnapshot();

        int pageX = this.layout.rightPageX();
        int pageRight = this.layout.rightPageRight();
        int titleX = pageX + 8;
        int titleWidth = this.layout.rightPageWidth() - 16;
        int titleY = this.layout.rightPageY() + 18;
        drawCenteredTrimmed(graphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.welcome.title"),
                titleX, titleY, titleWidth, TITLE_COLOR);

        int greetingY = titleY + 19;
        drawCenteredTrimmed(graphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.welcome.greeting"),
                titleX, greetingY, titleWidth, LABEL_COLOR);

        int separatorLeft = pageX + 14;
        int separatorRight = pageRight - 14;
        int firstSeparatorY = greetingY + font.lineHeight + 8;
        graphics.fill(separatorLeft, firstSeparatorY, separatorRight, firstSeparatorY + 1, SEPARATOR_COLOR);

        int contentX = pageX + 12;
        int contentWidth = this.layout.rightPageWidth() - 24;
        int columnWidth = (contentWidth - COLUMN_GAP) / 2;
        int rightColumnX = contentX + columnWidth + COLUMN_GAP;
        int firstRowY = firstSeparatorY + 14;

        ArchaeologyJournalLogState logState = ArchaeologyJournalClientState.getLogState();
        drawMetric(graphics, font, contentX, firstRowY, columnWidth,
                "screen.unsuspiciousblock.archaeology_journal.welcome.stat.lifetime_entries",
                Long.toString(this.lifetimeEntries));
        drawMetric(graphics, font, rightColumnX, firstRowY, columnWidth,
                "screen.unsuspiciousblock.archaeology_journal.welcome.stat.chest_opens",
                Long.toString(logState.getLifetimeEntryCount(LootSourceType.LOOT_CONTAINER)));
        drawMetric(graphics, font, contentX, firstRowY + 17, columnWidth,
                "screen.unsuspiciousblock.archaeology_journal.welcome.stat.current_entries",
                Integer.toString(this.currentEntries));
        drawMetric(graphics, font, rightColumnX, firstRowY + 17, columnWidth,
                "screen.unsuspiciousblock.archaeology_journal.welcome.stat.noted_entries",
                Integer.toString(this.notedEntries));

        int recentY = firstRowY + 43;
        this.latestRecordTrimmed = drawLabelAndValue(graphics, font, contentX, recentY, contentWidth,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.welcome.latest_record"),
                this.latestRecord);
        this.latestRecordHovered = mouseX >= contentX && mouseX < contentX + contentWidth
                && mouseY >= recentY && mouseY < recentY + font.lineHeight;

        int discoveryY = recentY + 22;
        Component discoveryLabel = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.welcome.latest_discovery");
        graphics.drawString(font, discoveryLabel.getString(), contentX, discoveryY, LABEL_COLOR, false);
        int itemsX = contentX;
        int itemsY = discoveryY + 14;
        this.hoveredItemIndex = -1;
        if (this.latestItems.isEmpty()) {
            graphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.welcome.none"),
                    itemsX, itemsY + 4, VALUE_COLOR, false);
        } else {
            int itemsPerRow = Math.max(1, (pageRight - 12 - contentX) / (ITEM_SIZE + ITEM_GAP));
            for (int index = 0; index < this.latestItems.size(); index++) {
                int itemX = itemsX + (index % itemsPerRow) * (ITEM_SIZE + ITEM_GAP);
                int itemY = itemsY + (index / itemsPerRow) * (ITEM_SIZE + 2);
                graphics.renderItem(this.latestItems.get(index), itemX, itemY);
                if (mouseX >= itemX && mouseX < itemX + ITEM_SIZE
                        && mouseY >= itemY && mouseY < itemY + ITEM_SIZE) {
                    this.hoveredItemIndex = index;
                }
            }
        }

        int secondSeparatorY = this.layout.rightPageBottom() - 38;
        graphics.fill(separatorLeft, secondSeparatorY, separatorRight, secondSeparatorY + 1, SEPARATOR_COLOR);
    }

    public void renderTooltip(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (this.hoveredItemIndex >= 0 && this.hoveredItemIndex < this.latestItems.size()) {
            graphics.renderTooltip(font, this.latestItems.get(this.hoveredItemIndex), mouseX, mouseY);
        } else if (this.latestRecordHovered && this.latestRecordTrimmed) {
            graphics.renderTooltip(font, this.latestRecord, mouseX, mouseY);
        }
    }

    private void refreshSnapshot() {
        long logRevision = ArchaeologyJournalClientState.getLogRevision();
        long catalogRevision = ArchaeologyJournalClientState.getCatalogRevision();
        if (logRevision == this.lastLogRevision && catalogRevision == this.lastCatalogRevision) {
            return;
        }

        ArchaeologyJournalLogState logState = ArchaeologyJournalClientState.getLogState();
        this.lifetimeEntries = logState.getLifetimeEntryCount(LootSourceType.ARCHAEOLOGY);
        this.currentEntries = logState.getTotalEntryCount();
        this.notedEntries = logState.getTables().values().stream()
                .mapToInt(ArchaeologyJournalLogState.TableLogHistory::getNotedEntryCount)
                .sum();

        LatestEntry latest = findLatestEntry(logState);
        if (latest == null) {
            this.latestRecord = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.welcome.none");
            this.latestItems = List.of();
        } else {
            int day = GameTimeFormatHelper.fromTime(
                    latest.entry().lastUpdatedGameTime(), latest.entry().lastUpdatedDayTime()).day();
            TableDefinition definition = ArchaeologyJournalClientState.getCatalog().get(latest.tableId());
            Component tableName = definition != null
                    ? definition.displayName()
                    : Component.literal(latest.tableId().getPath().replace('_', ' '));
            this.latestRecord = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.welcome.latest_record_value", day, tableName);
            Map<String, Integer> lootMap = latest.entry().actualLoot().isEmpty()
                    ? latest.entry().expectedLoot() : latest.entry().actualLoot();
            ArrayList<ItemStack> itemStacks = new ArrayList<>();
            for (var loot : JournalFormatHelper.sortedLootEntries(lootMap)) {
                ItemStack stack = JournalFormatHelper.createLootStack(loot.getKey(), loot.getValue());
                if (stack != null && !stack.isEmpty()) {
                    itemStacks.add(stack);
                }
            }
            this.latestItems = List.copyOf(itemStacks);
        }

        this.lastLogRevision = logRevision;
        this.lastCatalogRevision = catalogRevision;
    }

    @Nullable
    private static LatestEntry findLatestEntry(ArchaeologyJournalLogState logState) {
        Comparator<ExcavationLogEntry> comparator = Comparator
                .comparingLong(ExcavationLogEntry::lastUpdatedGameTime)
                .thenComparingLong(ExcavationLogEntry::lastUpdatedDayTime)
                .thenComparingLong(ExcavationLogEntry::createdGameTime)
                .thenComparingLong(ExcavationLogEntry::createdDayTime)
                .thenComparing(ExcavationLogEntry::entryId);
        LatestEntry latest = null;
        for (Map.Entry<ResourceLocation, ArchaeologyJournalLogState.TableLogHistory> table
            : logState.getTables().entrySet()) {
            for (ExcavationLogEntry entry : table.getValue().getEntries()) {
                if (entry.tableStack() != null && !entry.tableStack().isEmpty()
                        && !table.getKey().equals(entry.tableStack().getFirst())) {
                    continue;
                }
                if (latest == null || comparator.compare(entry, latest.entry()) > 0) {
                    latest = new LatestEntry(table.getKey(), entry);
                }
            }
        }
        return latest;
    }

    private static void drawMetric(GuiGraphics graphics, Font font, int x, int y, int width,
                                   String labelKey, String value) {
        Component label = Component.translatable(labelKey);
        int valueWidth = font.width(value);
        int labelWidth = Math.max(0, width - valueWidth - 4);
        String labelText = font.plainSubstrByWidth(label.getString(), labelWidth);
        graphics.drawString(font, labelText, x, y, LABEL_COLOR, false);
        graphics.drawString(font, value, x + width - valueWidth, y, VALUE_COLOR, false);
    }

    private static boolean drawLabelAndValue(GuiGraphics graphics, Font font, int x, int y, int width,
                                             Component label, Component value) {
        String labelText = label.getString();
        graphics.drawString(font, labelText, x, y, LABEL_COLOR, false);
        int valueX = x + font.width(labelText) + 5;
        int valueWidth = Math.max(0, x + width - valueX);
        String valueText = font.plainSubstrByWidth(value.getString(), valueWidth);
        graphics.drawString(font, valueText, valueX, y, VALUE_COLOR, false);
        return font.width(value) > valueWidth;
    }

    private static void drawCenteredTrimmed(GuiGraphics graphics, Font font, Component text,
                                             int x, int y, int width, int color) {
        String rendered = font.plainSubstrByWidth(text.getString(), width);
        graphics.drawString(font, rendered, x + (width - font.width(rendered)) / 2, y, color, false);
    }

    private record LatestEntry(ResourceLocation tableId, ExcavationLogEntry entry) {
    }
}
