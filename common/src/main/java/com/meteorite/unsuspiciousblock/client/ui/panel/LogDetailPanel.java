package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.helper.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.PaginationState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 日志详情面板——展示单条日志的完整来源与战利品信息 */
public final class LogDetailPanel implements PagePanel {
    private static final int LABEL_COLOR = 0x5A422C;
    private static final int TEXT_COLOR = 0x4A3320;
    private static final int MUTED_COLOR = 0x7A6247;
    private static final int ICON_SIZE = 16;
    private static final int ICON_GAP = 2;
    private static final int SECTION_GAP = 6;
    private static final int HEADER_GAP = 14;

    private final JournalBookBackground.BookLayout layout;
    private final PaginationState pagination = new PaginationState(this::computePageCount);
    @Nullable
    private ExcavationLogEntry entry;
    // 缓存：避免每帧重建
    private List<Map.Entry<String, Integer>> cachedExpectedLoot = List.of();
    private List<Map.Entry<String, Integer>> cachedActualLoot = List.of();
    private final List<IconSlot> renderTooltipSlots = new ArrayList<>();
    @Nullable
    private Bounds backBounds = Bounds.EMPTY;

    public LogDetailPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void setEntry(@Nullable ExcavationLogEntry entry) {
        this.entry = entry;
        this.cachedExpectedLoot = entry != null
                ? renderableLootEntries(entry.expectedLoot()) : List.of();
        this.cachedActualLoot = entry != null
                ? renderableLootEntries(entry.actualLoot()) : List.of();
        this.renderTooltipSlots.clear();
        this.backBounds = Bounds.EMPTY;
        this.pagination.reset();
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        this.renderTooltipSlots.clear();

        int leftX = this.layout.rightPageX() + 8;
        int contentWidth = this.layout.rightPageWidth() - 20;
        int y = this.layout.rightPageY() + JournalLayout.LOG_TOP;

        Component backText = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_back_to_list");
        this.backBounds = new Bounds(leftX, y, font.width(backText), font.lineHeight);
        guiGraphics.drawString(font, backText, leftX, y,
                this.backBounds.contains(mouseX, mouseY) ? LABEL_COLOR : MUTED_COLOR, false);
        y += HEADER_GAP;

        ExcavationLogEntry entry = this.entry;
        if (entry == null) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_empty"),
                    leftX, y, MUTED_COLOR, false);
            return;
        }

        ItemStack sourceStack = JournalFormatHelper.createSourceStack(entry.sourceBlockId());
        int sourceTextX = leftX;
        int sourceTextWidth = contentWidth;
        if (!sourceStack.isEmpty()) {
            guiGraphics.renderItem(sourceStack, leftX, y);
            guiGraphics.renderItemDecorations(font, sourceStack, leftX, y);
            this.renderTooltipSlots.add(new IconSlot(leftX, y, sourceStack.copy()));
            sourceTextX += ICON_SIZE + 4;
            sourceTextWidth -= ICON_SIZE + 4;
        }
        int sourceHeight = renderWrappedText(guiGraphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_source_block_value",
                        JournalFormatHelper.formatBlockName(entry.sourceBlockId())),
                sourceTextX, y, sourceTextWidth, 2, TEXT_COLOR);
        y += Math.max(sourceHeight, sourceStack.isEmpty() ? 0 : ICON_SIZE) + SECTION_GAP;

        y += renderWrappedText(guiGraphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_trigger_type_value",
                        JournalFormatHelper.formatTriggerType(entry.triggerType())),
                leftX, y, contentWidth, 1, TEXT_COLOR) + SECTION_GAP;

        guiGraphics.drawString(font,
                JournalFormatHelper.formatGameTime("screen.unsuspiciousblock.archaeology_journal.log_created_time_value",
                        entry.createdGameTime(), entry.createdDayTime()),
                leftX, y, TEXT_COLOR, false);
        y += font.lineHeight + 2;

        guiGraphics.drawString(font,
                JournalFormatHelper.formatGameTime("screen.unsuspiciousblock.archaeology_journal.log_updated_time_value",
                        entry.lastUpdatedGameTime(), entry.lastUpdatedDayTime()),
                leftX, y, TEXT_COLOR, false);
        y += font.lineHeight + SECTION_GAP;

        y += renderWrappedText(guiGraphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_structure_value",
                        JournalFormatHelper.formatStructureName(entry.structureId())),
                leftX, y, contentWidth, 2, TEXT_COLOR) + 2;

        y += renderWrappedText(guiGraphics, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_biome_value",
                        JournalFormatHelper.formatBiomeName(entry.biomeId())),
                leftX, y, contentWidth, 2, TEXT_COLOR) + 2;

        guiGraphics.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_position_value",
                        entry.pos().getX(), entry.pos().getY(), entry.pos().getZ()),
                leftX, y, TEXT_COLOR, false);
        y += font.lineHeight + SECTION_GAP;

        int rowsPerSection = rowsPerSection(y, font.lineHeight);
        int iconsPerRow = iconsPerRow(contentWidth);
        int page = this.pagination.getPage();

        guiGraphics.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_expected_loot"),
                leftX, y, LABEL_COLOR, false);
        y += font.lineHeight + 2;
        y += renderLootIcons(guiGraphics, font, leftX, y, contentWidth,
                this.cachedExpectedLoot, page, rowsPerSection, iconsPerRow) + SECTION_GAP;

        guiGraphics.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_actual_loot"),
                leftX, y, LABEL_COLOR, false);
        y += font.lineHeight + 2;
        renderLootIcons(guiGraphics, font, leftX, y, contentWidth,
                this.cachedActualLoot, page, rowsPerSection, iconsPerRow);
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= this.layout.rightPageX() && mouseX <= this.layout.rightPageRight()
                && mouseY >= this.layout.rightPageY() && mouseY <= this.layout.rightPageBottom();
    }

    public boolean handleClick(double mouseX, double mouseY) {
        return this.backBounds != null && this.backBounds.contains(mouseX, mouseY);
    }

    @Nullable
    public ItemStack getTooltipStack(double mouseX, double mouseY) {
        for (IconSlot slot : this.renderTooltipSlots) {
            if (slot.contains(mouseX, mouseY)) {
                return slot.stack();
            }
        }
        return null;
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

    // 渲染战利品图标，使用预缓存列表而非原始 Map
    private int renderLootIcons(GuiGraphics guiGraphics, Font font, int leftX, int topY, int width,
                                List<Map.Entry<String, Integer>> cachedEntries, int page, int rowsPerPage, int iconsPerRow) {
        if (cachedEntries.isEmpty()) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_loot_empty"),
                    leftX, topY, MUTED_COLOR, false);
            return font.lineHeight;
        }

        int stride = ICON_SIZE + ICON_GAP;
        int iconsPerPage = Math.max(1, iconsPerRow * rowsPerPage);
        int from = Math.min(cachedEntries.size(), page * iconsPerPage);
        int to = Math.min(cachedEntries.size(), from + iconsPerPage);
        int renderedCount = 0;
        for (int i = from; i < to; i++) {
            Map.Entry<String, Integer> lootEntry = cachedEntries.get(i);
            ItemStack stack = JournalFormatHelper.createLootStack(lootEntry.getKey(), lootEntry.getValue());
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            int row = renderedCount / iconsPerRow;
            int col = renderedCount % iconsPerRow;
            int iconX = leftX + col * stride;
            int iconY = topY + row * stride;
            String countText = lootEntry.getValue() > 1 ? Integer.toString(lootEntry.getValue()) : null;
            guiGraphics.renderItem(stack, iconX, iconY);
            guiGraphics.renderItemDecorations(font, stack, iconX, iconY, countText);
            this.renderTooltipSlots.add(new IconSlot(iconX, iconY, stack.copy()));
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

    private int lootPageCount(List<Map.Entry<String, Integer>> cachedEntries, int iconsPerRow, int rowsPerPage) {
        if (cachedEntries.isEmpty()) {
            return 1;
        }
        int iconsPerPage = Math.max(1, iconsPerRow * rowsPerPage);
        return Math.max(1, (cachedEntries.size() + iconsPerPage - 1) / iconsPerPage);
    }

    private static List<Map.Entry<String, Integer>> renderableLootEntries(Map<String, Integer> lootMap) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : JournalFormatHelper.sortedLootEntries(lootMap)) {
            ItemStack stack = JournalFormatHelper.createLootStack(entry.getKey(), entry.getValue());
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
        return font.lineHeight;
    }

    private int computePageCount() {
        ExcavationLogEntry currentEntry = this.entry;
        if (currentEntry == null) {
            return 1;
        }
        int lineHeight = currentLineHeight();
        int contentWidth = this.layout.rightPageWidth() - 20;
        int iconsPerRow = iconsPerRow(contentWidth);
        int rowsPerSection = rowsPerSection(detailContentTop(lineHeight), lineHeight);
        int expectedPages = lootPageCount(this.cachedExpectedLoot, iconsPerRow, rowsPerSection);
        int actualPages = lootPageCount(this.cachedActualLoot, iconsPerRow, rowsPerSection);
        return Math.max(1, Math.max(expectedPages, actualPages));
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