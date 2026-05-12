package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState.ExcavationLogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 日志详情面板——展示单条日志的完整来源与战利品信息 */
public final class LogDetailPanel {
    private static final int LABEL_COLOR = 0x5A422C;
    private static final int TEXT_COLOR = 0x4A3320;
    private static final int MUTED_COLOR = 0x7A6247;
    private static final int ICON_SIZE = 16;
    private static final int ICON_GAP = 2;
    private static final int SECTION_GAP = 6;
    private static final int HEADER_GAP = 14;
    private static final int DEFAULT_LINE_HEIGHT = 9;

    private final JournalBookBackground.BookLayout layout;
    @Nullable
    private ExcavationLogEntry entry;
    private final List<IconSlot> tooltipSlots = new ArrayList<>();
    private Bounds backBounds = Bounds.EMPTY;
    private int page;

    public LogDetailPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void setEntry(@Nullable ExcavationLogEntry entry) {
        this.entry = entry;
        this.tooltipSlots.clear();
        this.backBounds = Bounds.EMPTY;
        this.page = 0;
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        this.tooltipSlots.clear();

        int leftX = this.layout.rightPageX() + 8;
        int contentWidth = this.layout.rightPageWidth() - 20;
        int y = this.layout.rightPageY() + JournalLayout.LOG_TOP;

        Component backText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_back_to_list");
        this.backBounds = new Bounds(leftX, y, font.width(backText), font.lineHeight);
        guiGraphics.drawString(font, backText, leftX, y,
                this.backBounds.contains(mouseX, mouseY) ? LABEL_COLOR : MUTED_COLOR, false);
        y += HEADER_GAP;

        if (this.entry == null) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_empty"),
                    leftX, y, MUTED_COLOR, false);
            return;
        }

        ItemStack sourceStack = LogPanel.createSourceStack(this.entry.sourceBlockId());
        int sourceTextX = leftX;
        int sourceTextWidth = contentWidth;
        if (!sourceStack.isEmpty()) {
            guiGraphics.renderItem(sourceStack, leftX, y);
            guiGraphics.renderItemDecorations(font, sourceStack, leftX, y);
            this.tooltipSlots.add(new IconSlot(leftX, y, sourceStack.copy()));
            sourceTextX += ICON_SIZE + 4;
            sourceTextWidth -= ICON_SIZE + 4;
        }
        int sourceHeight = renderWrappedText(guiGraphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_source_block_value",
                        LogPanel.formatBlockName(this.entry.sourceBlockId())),
                sourceTextX, y, sourceTextWidth, 2, TEXT_COLOR);
        y += Math.max(sourceHeight, sourceStack.isEmpty() ? 0 : ICON_SIZE) + SECTION_GAP;

        y += renderWrappedText(guiGraphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_trigger_type_value",
                        LogPanel.formatTriggerType(this.entry.triggerType())),
                leftX, y, contentWidth, 1, TEXT_COLOR) + SECTION_GAP;

        guiGraphics.drawString(font,
                LogPanel.formatGameTime("screen.unsuspiciousblock.archaeology_journal.log_created_time_value",
                        this.entry.createdGameTime(), this.entry.createdDayTime()),
                leftX, y, TEXT_COLOR, false);
        y += font.lineHeight + 2;

        guiGraphics.drawString(font,
                LogPanel.formatGameTime("screen.unsuspiciousblock.archaeology_journal.log_updated_time_value",
                        this.entry.lastUpdatedGameTime(), this.entry.lastUpdatedDayTime()),
                leftX, y, TEXT_COLOR, false);
        y += font.lineHeight + SECTION_GAP;

        y += renderWrappedText(guiGraphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_structure_value",
                        LogPanel.formatStructureName(this.entry.structureId())),
                leftX, y, contentWidth, 2, TEXT_COLOR) + 2;

        y += renderWrappedText(guiGraphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_biome_value",
                        LogPanel.formatBiomeName(this.entry.biomeId())),
                leftX, y, contentWidth, 2, TEXT_COLOR) + 2;

        guiGraphics.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_position_value",
                        this.entry.pos().getX(), this.entry.pos().getY(), this.entry.pos().getZ()),
                leftX, y, TEXT_COLOR, false);
        y += font.lineHeight + SECTION_GAP;

        int rowsPerSection = rowsPerSection(y, font.lineHeight);
        int iconsPerRow = iconsPerRow(contentWidth);
        this.page = Mth.clamp(this.page, 0, Math.max(0, pageCount() - 1));

        guiGraphics.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_expected_loot"),
                leftX, y, LABEL_COLOR, false);
        y += font.lineHeight + 2;
        y += renderLootIcons(guiGraphics, font, leftX, y, contentWidth,
                this.entry.expectedLoot(), this.page, rowsPerSection, iconsPerRow) + SECTION_GAP;

        guiGraphics.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_actual_loot"),
                leftX, y, LABEL_COLOR, false);
        y += font.lineHeight + 2;
        renderLootIcons(guiGraphics, font, leftX, y, contentWidth,
                this.entry.actualLoot(), this.page, rowsPerSection, iconsPerRow);
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= this.layout.rightPageX() && mouseX <= this.layout.rightPageRight()
                && mouseY >= this.layout.rightPageY() && mouseY <= this.layout.rightPageBottom();
    }

    public boolean handleClick(double mouseX, double mouseY) {
        return this.backBounds.contains(mouseX, mouseY);
    }

    @Nullable
    public ItemStack getTooltipStack(double mouseX, double mouseY) {
        for (IconSlot slot : this.tooltipSlots) {
            if (slot.contains(mouseX, mouseY)) {
                return slot.stack();
            }
        }
        return null;
    }

    public int pageCount() {
        if (this.entry == null) {
            return 1;
        }
        int lineHeight = currentLineHeight();
        int contentWidth = this.layout.rightPageWidth() - 20;
        int iconsPerRow = iconsPerRow(contentWidth);
        int rowsPerSection = rowsPerSection(detailContentTop(lineHeight), lineHeight);
        int expectedPages = lootPageCount(this.entry.expectedLoot(), iconsPerRow, rowsPerSection);
        int actualPages = lootPageCount(this.entry.actualLoot(), iconsPerRow, rowsPerSection);
        return Math.max(1, Math.max(expectedPages, actualPages));
    }

    public int getPage() {
        return this.page;
    }

    public void changePage(int delta) {
        this.page = Mth.clamp(this.page + delta, 0, Math.max(0, pageCount() - 1));
    }

    public void setPage(int page) {
        this.page = Mth.clamp(page, 0, Math.max(0, pageCount() - 1));
    }

    private int renderLootIcons(GuiGraphics guiGraphics, Font font, int leftX, int topY, int width,
                                Map<String, Integer> lootMap, int page, int rowsPerPage, int iconsPerRow) {
        List<Map.Entry<String, Integer>> entries = renderableLootEntries(lootMap);
        if (entries.isEmpty()) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_loot_empty"),
                    leftX, topY, MUTED_COLOR, false);
            return font.lineHeight;
        }

        int stride = ICON_SIZE + ICON_GAP;
        int iconsPerPage = Math.max(1, iconsPerRow * rowsPerPage);
        int from = Math.min(entries.size(), page * iconsPerPage);
        int to = Math.min(entries.size(), from + iconsPerPage);
        int renderedCount = 0;
        for (int i = from; i < to; i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            ItemStack stack = LogPanel.createLootStack(entry.getKey(), entry.getValue());
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            int row = renderedCount / iconsPerRow;
            int col = renderedCount % iconsPerRow;
            int iconX = leftX + col * stride;
            int iconY = topY + row * stride;
            String countText = entry.getValue() > 1 ? Integer.toString(entry.getValue()) : null;
            guiGraphics.renderItem(stack, iconX, iconY);
            guiGraphics.renderItemDecorations(font, stack, iconX, iconY, countText);
            this.tooltipSlots.add(new IconSlot(iconX, iconY, stack.copy()));
            renderedCount++;
        }

        if (renderedCount == 0) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_loot_empty"),
                    leftX, topY, MUTED_COLOR, false);
            return font.lineHeight;
        }

        int rows = (renderedCount + iconsPerRow - 1) / iconsPerRow;
        return rows * stride - ICON_GAP;
    }

    private int lootPageCount(Map<String, Integer> lootMap, int iconsPerRow, int rowsPerPage) {
        List<Map.Entry<String, Integer>> entries = renderableLootEntries(lootMap);
        if (entries.isEmpty()) {
            return 1;
        }
        int iconsPerPage = Math.max(1, iconsPerRow * rowsPerPage);
        return Math.max(1, (entries.size() + iconsPerPage - 1) / iconsPerPage);
    }

    private static List<Map.Entry<String, Integer>> renderableLootEntries(Map<String, Integer> lootMap) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : LogPanel.sortedLootEntries(lootMap)) {
            ItemStack stack = LogPanel.createLootStack(entry.getKey(), entry.getValue());
            if (stack != null && !stack.isEmpty()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private int iconsPerRow(int width) {
        return Math.max(1, (width + ICON_GAP) / (ICON_SIZE + ICON_GAP));
    }

    private int rowsPerSection(int topY, int lineHeight) {
        int remainingHeight = this.layout.rightPageBottom() - topY - 4;
        int labelHeight = lineHeight + 2;
        int rows = (remainingHeight - labelHeight * 2 - SECTION_GAP) / ((ICON_SIZE + ICON_GAP) * 2);
        return Math.max(1, rows);
    }

    private int detailContentTop(int lineHeight) {
        return this.layout.rightPageY() + JournalLayout.LOG_TOP
                + HEADER_GAP
                + Math.max(lineHeight * 2, ICON_SIZE) + SECTION_GAP
                + lineHeight + SECTION_GAP
                + lineHeight + 2
                + lineHeight + SECTION_GAP
                + lineHeight * 2 + 2
                + lineHeight * 2 + 2
                + lineHeight + SECTION_GAP;
    }

    private int currentLineHeight() {
        Font font = Minecraft.getInstance().font;
        return font != null ? font.lineHeight : DEFAULT_LINE_HEIGHT;
    }

    private static int renderWrappedText(GuiGraphics guiGraphics, Font font, Component text,
                                         int x, int y, int width, int maxLines, int color) {
        if (width <= 0 || maxLines <= 0) {
            return 0;
        }
        List<FormattedCharSequence> lines = font.split(text, width);
        if (lines.isEmpty()) {
            return 0;
        }
        int lineCount = Math.min(maxLines, lines.size());
        for (int i = 0; i < lineCount; i++) {
            guiGraphics.drawString(font, lines.get(i), x, y + i * font.lineHeight, color, false);
        }
        return lineCount * font.lineHeight;
    }

    private record IconSlot(int x, int y, ItemStack stack) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX < this.x + ICON_SIZE
                    && mouseY >= this.y && mouseY < this.y + ICON_SIZE;
        }
    }

    private record Bounds(int x, int y, int width, int height) {
        private static final Bounds EMPTY = new Bounds(0, 0, 0, 0);

        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX <= this.x + this.width
                    && mouseY >= this.y && mouseY <= this.y + this.height;
        }
    }
}
