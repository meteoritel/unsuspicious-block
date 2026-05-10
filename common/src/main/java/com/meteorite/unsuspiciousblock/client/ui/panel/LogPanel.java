package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.helper.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.GameTimeFormatHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class LogPanel {
    private static final int LABEL_COLOR = 0x5A422C;
    private static final int TEXT_COLOR = 0x4A3320;
    private static final int MUTED_COLOR = 0x7A6247;
    private static final int ENTRY_TEXT_WIDTH = 136;
    private static final int ENTRY_BG_COLOR = 0x30261C12;
    private static final int ENTRY_SEPARATOR_COLOR = 0x40A0A0A0;

    private final JournalBookBackground.BookLayout layout;
    private final List<LogEntryState> entries = new ArrayList<>();
    @Nullable
    private Long firstUnlockedGameTime;
    @Nullable
    private Long firstUnlockedDayTime;
    private int page;

    public LogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void setData(@Nullable Long firstUnlockedGameTime, @Nullable Long firstUnlockedDayTime,
                        List<ExcavationLogEntry> entries) {
        this.firstUnlockedGameTime = firstUnlockedGameTime;
        this.firstUnlockedDayTime = firstUnlockedDayTime;
        this.entries.clear();
        for (ExcavationLogEntry entry : entries) {
            this.entries.add(new LogEntryState(entry));
        }
        this.page = 0;
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = this.layout.rightPageX() + 8;
        int topY = this.layout.rightPageY() + JournalLayout.LOG_TOP;

        guiGraphics.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.first_unlock_time"),
                leftX, topY, LABEL_COLOR, false);

        Component firstUnlockValue = this.firstUnlockedGameTime == null || this.firstUnlockedDayTime == null
                ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.first_unlock_time_unknown")
                : formatGameTime("screen.unsuspiciousblock.archaeology_journal.first_unlock_time_value",
                        this.firstUnlockedGameTime, this.firstUnlockedDayTime);
        guiGraphics.drawString(font, firstUnlockValue, leftX,
                this.layout.rightPageY() + JournalLayout.LOG_FIRST_UNLOCK_VALUE_Y, TEXT_COLOR, false);

        guiGraphics.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_entries"),
                leftX, this.layout.rightPageY() + JournalLayout.LOG_LIST_LABEL_Y, LABEL_COLOR, false);

        int listStartY = this.layout.rightPageY() + JournalLayout.LOG_LIST_TOP;
        if (this.entries.isEmpty()) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_empty"),
                    leftX, listStartY, MUTED_COLOR, false);
            return;
        }

        int maxVisibleEntries = maxVisibleEntries();
        int from = this.page * maxVisibleEntries;
        int to = Math.min(this.entries.size(), from + maxVisibleEntries);
        for (int i = from; i < to; i++) {
            int rowY = listStartY + (i - from) * JournalLayout.LOG_ROW_HEIGHT;
            int entryIndex = this.entries.size() - 1 - i;
            renderEntry(guiGraphics, font, leftX, rowY, this.entries.get(entryIndex), mouseX, mouseY);
        }
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= this.layout.rightPageX() && mouseX <= this.layout.rightPageRight()
                && mouseY >= this.layout.rightPageY() && mouseY <= this.layout.rightPageBottom();
    }

    public int pageCount() {
        if (this.entries.isEmpty()) {
            return 1;
        }
        int maxVisibleEntries = maxVisibleEntries();
        return Math.max(1, (this.entries.size() + maxVisibleEntries - 1) / maxVisibleEntries);
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

    private void renderEntry(GuiGraphics guiGraphics, Font font, int leftX, int rowY,
                             LogEntryState state, int mouseX, int mouseY) {
        boolean hovered = mouseX >= leftX && mouseX <= leftX + ENTRY_TEXT_WIDTH
                && mouseY >= rowY && mouseY <= rowY + JournalLayout.LOG_ROW_HEIGHT;
        if (!state.wasHovered && hovered) {
            state.scrollTicks = 0;
        }
        state.wasHovered = hovered;
        if (hovered) {
            state.scrollTicks++;
        }

        guiGraphics.fill(leftX - 2, rowY - 2,
                leftX + ENTRY_TEXT_WIDTH + 2, rowY + JournalLayout.LOG_ROW_HEIGHT - 4, ENTRY_BG_COLOR);
        int bottomY = rowY + JournalLayout.LOG_ROW_HEIGHT - 6;
        guiGraphics.fill(leftX, bottomY, leftX + ENTRY_TEXT_WIDTH, bottomY + 1, ENTRY_SEPARATOR_COLOR);

        String itemText = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_item_value",
                formatItemName(state.entry.itemId())).getString();
        String structureText = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_structure_value",
                formatStructureName(state.entry.structureId())).getString();
        String biomeText = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_biome_value",
                formatBiomeName(state.entry.biomeId())).getString();
        String positionText = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_position_value",
                state.entry.pos().getX(), state.entry.pos().getY(), state.entry.pos().getZ()).getString();
        String timeText = formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_time_value",
                state.entry.gameTime(), state.entry.dayTime()).getString();

        ScrollTextHelper.draw(guiGraphics, font, itemText,
                leftX, rowY, ENTRY_TEXT_WIDTH, TEXT_COLOR, hovered, state.scrollTicks, false);
        ScrollTextHelper.draw(guiGraphics, font, structureText,
                leftX, rowY + 10, ENTRY_TEXT_WIDTH, TEXT_COLOR, hovered, state.scrollTicks, false);
        ScrollTextHelper.draw(guiGraphics, font, biomeText,
                leftX, rowY + 20, ENTRY_TEXT_WIDTH, TEXT_COLOR, hovered, state.scrollTicks, false);
        guiGraphics.drawString(font, positionText, leftX, rowY + 30, MUTED_COLOR, false);
        guiGraphics.drawString(font, timeText, leftX, rowY + 40, MUTED_COLOR, false);
    }

    private int maxVisibleEntries() {
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        return Math.max(1, availableHeight / JournalLayout.LOG_ROW_HEIGHT);
    }

    private static Component formatGameTime(String key, long gameTime, long dayTime) {
        GameTimeFormatHelper.GameTimeParts parts = GameTimeFormatHelper.fromTime(gameTime, dayTime);
        return Component.translatable(key, parts.day(), parts.hour(), parts.minute());
    }

    private static String formatItemName(@Nullable ResourceLocation itemId) {
        if (itemId == null) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_item").getString();
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item != Items.AIR || itemId.equals(ResourceLocation.withDefaultNamespace("air"))) {
            return new ItemStack(item).getHoverName().getString();
        }
        return formatReadableIdentifier(itemId);
    }

    private static String formatStructureName(@Nullable ResourceLocation id) {
        if (id == null) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_structure").getString();
        }
        return formatTranslatedIdentifier(id, "structure");
    }

    private static String formatBiomeName(ResourceLocation id) {
        return formatTranslatedIdentifier(id, "biome");
    }

    private static String formatTranslatedIdentifier(ResourceLocation id, String type) {
        String translationKey = type + "." + id.getNamespace() + "." + id.getPath().replace('/', '.');
        if (Language.getInstance().has(translationKey)) {
            return Component.translatable(translationKey).getString();
        }
        return formatReadableIdentifier(id);
    }

    private static String formatReadableIdentifier(ResourceLocation id) {
        String readablePath = id.getPath().replace('_', ' ');
        if ("minecraft".equals(id.getNamespace())) {
            return readablePath;
        }
        return id.getNamespace() + ":" + readablePath;
    }

    private static final class LogEntryState {
        private final ExcavationLogEntry entry;
        private int scrollTicks;
        private boolean wasHovered;

        private LogEntryState(ExcavationLogEntry entry) {
            this.entry = entry;
        }
    }
}
