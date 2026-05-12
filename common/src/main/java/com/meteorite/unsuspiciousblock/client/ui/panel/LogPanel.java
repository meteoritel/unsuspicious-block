package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.helper.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogState.TriggerType;
import com.meteorite.unsuspiciousblock.journal.GameTimeFormatHelper;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
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
import java.util.Map;
import java.util.UUID;

/** 日志列表面板——展示来源方块、结构名与创建/更新时间 */
public final class LogPanel {
    private static final int LABEL_COLOR = 0x5A422C;
    private static final int TEXT_COLOR = 0x4A3320;
    private static final int MUTED_COLOR = 0x7A6247;
    private static final int ENTRY_TEXT_WIDTH = 136;
    private static final int ENTRY_BG_COLOR = 0x30261C12;
    private static final int ENTRY_HOVER_BG_COLOR = 0x40302016;
    private static final int ENTRY_SEPARATOR_COLOR = 0x40A0A0A0;
    private static final int ENTRY_ICON_SIZE = 16;
    private static final int ENTRY_ICON_GAP = 6;

    private final JournalBookBackground.BookLayout layout;
    private final List<LogEntryState> entries = new ArrayList<>();
    @Nullable
    private Long firstUnlockedGameTime;
    @Nullable
    private Long firstUnlockedDayTime;
    @Nullable
    private TriggerType firstUnlockTriggerType;
    private int page;

    public LogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void setData(@Nullable Long firstUnlockedGameTime, @Nullable Long firstUnlockedDayTime,
                        @Nullable TriggerType firstUnlockTriggerType,
                        List<ExcavationLogEntry> entries) {
        this.firstUnlockedGameTime = firstUnlockedGameTime;
        this.firstUnlockedDayTime = firstUnlockedDayTime;
        this.firstUnlockTriggerType = firstUnlockTriggerType;
        this.entries.clear();

        List<ExcavationLogEntry> sortedEntries = new ArrayList<>(entries);
        sortedEntries.sort((left, right) -> {
            int cmp = Long.compare(right.lastUpdatedGameTime(), left.lastUpdatedGameTime());
            if (cmp != 0) {
                return cmp;
            }
            cmp = Long.compare(right.lastUpdatedDayTime(), left.lastUpdatedDayTime());
            if (cmp != 0) {
                return cmp;
            }
            cmp = Long.compare(right.createdGameTime(), left.createdGameTime());
            if (cmp != 0) {
                return cmp;
            }
            cmp = Long.compare(right.createdDayTime(), left.createdDayTime());
            if (cmp != 0) {
                return cmp;
            }
            return right.entryId().compareTo(left.entryId());
        });

        for (ExcavationLogEntry entry : sortedEntries) {
            this.entries.add(new LogEntryState(entry));
        }
        this.page = Mth.clamp(this.page, 0, Math.max(0, pageCount() - 1));
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = this.layout.rightPageX() + 8;
        int topY = this.layout.rightPageY() + JournalLayout.LOG_TOP;

        guiGraphics.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.first_unlock_time"),
                leftX, topY, LABEL_COLOR, false);

        Component firstUnlockValue = this.firstUnlockedGameTime == null
                ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.first_unlock_time_unknown")
                : formatGameTime("screen.unsuspiciousblock.archaeology_journal.first_unlock_time_value",
                        this.firstUnlockedGameTime,
                        this.firstUnlockedDayTime != null ? this.firstUnlockedDayTime : this.firstUnlockedGameTime);
        guiGraphics.drawString(font, firstUnlockValue, leftX,
                this.layout.rightPageY() + JournalLayout.LOG_FIRST_UNLOCK_VALUE_Y, TEXT_COLOR, false);

        Component firstUnlockTriggerValue = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.first_unlock_trigger_value",
                formatTriggerType(this.firstUnlockTriggerType));
        guiGraphics.drawString(font, firstUnlockTriggerValue, leftX,
                this.layout.rightPageY() + JournalLayout.LOG_FIRST_UNLOCK_TRIGGER_Y, TEXT_COLOR, false);

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
            renderEntry(guiGraphics, font, leftX, rowY, this.entries.get(i), mouseX, mouseY);
        }
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= this.layout.rightPageX() && mouseX <= this.layout.rightPageRight()
                && mouseY >= this.layout.rightPageY() && mouseY <= this.layout.rightPageBottom();
    }

    @Nullable
    public ExcavationLogEntry handleClick(double mouseX, double mouseY) {
        int from = this.page * maxVisibleEntries();
        int to = Math.min(this.entries.size(), from + maxVisibleEntries());
        int leftX = this.layout.rightPageX() + 8;
        for (int i = from; i < to; i++) {
            int rowY = this.layout.rightPageY() + JournalLayout.LOG_LIST_TOP + (i - from) * JournalLayout.LOG_ROW_HEIGHT;
            if (mouseX >= leftX - 2 && mouseX <= leftX + ENTRY_TEXT_WIDTH + 2
                    && mouseY >= rowY - 2 && mouseY <= rowY + JournalLayout.LOG_ROW_HEIGHT - 4) {
                return this.entries.get(i).entry;
            }
        }
        return null;
    }

    @Nullable
    public ExcavationLogEntry findEntry(UUID entryId) {
        for (LogEntryState state : this.entries) {
            if (state.entry.entryId().equals(entryId)) {
                return state.entry;
            }
        }
        return null;
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
        boolean hovered = mouseX >= leftX - 2 && mouseX <= leftX + ENTRY_TEXT_WIDTH + 2
                && mouseY >= rowY - 2 && mouseY <= rowY + JournalLayout.LOG_ROW_HEIGHT - 4;
        if (!state.wasHovered && hovered) {
            state.scrollTicks = 0;
        }
        state.wasHovered = hovered;
        if (hovered) {
            state.scrollTicks++;
        }

        guiGraphics.fill(leftX - 2, rowY - 2,
                leftX + ENTRY_TEXT_WIDTH + 2, rowY + JournalLayout.LOG_ROW_HEIGHT - 4,
                hovered ? ENTRY_HOVER_BG_COLOR : ENTRY_BG_COLOR);
        int bottomY = rowY + JournalLayout.LOG_ROW_HEIGHT - 6;
        guiGraphics.fill(leftX, bottomY, leftX + ENTRY_TEXT_WIDTH, bottomY + 1, ENTRY_SEPARATOR_COLOR);

        ItemStack sourceStack = createSourceStack(state.entry.sourceBlockId());
        int textX = leftX;
        int textWidth = ENTRY_TEXT_WIDTH;
        if (!sourceStack.isEmpty()) {
            int iconY = rowY + 10;
            guiGraphics.renderItem(sourceStack, leftX, iconY);
            guiGraphics.renderItemDecorations(font, sourceStack, leftX, iconY);
            textX += ENTRY_ICON_SIZE + ENTRY_ICON_GAP;
            textWidth -= ENTRY_ICON_SIZE + ENTRY_ICON_GAP;
        }

        String structureText = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_structure_value",
                formatStructureName(state.entry.structureId())).getString();
        String createdText = formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_created_time_value",
                state.entry.createdGameTime(), state.entry.createdDayTime()).getString();
        String updatedText = formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_updated_time_value",
                state.entry.lastUpdatedGameTime(), state.entry.lastUpdatedDayTime()).getString();

        ScrollTextHelper.draw(guiGraphics, font, structureText,
                textX, rowY + 2, textWidth, TEXT_COLOR, hovered, state.scrollTicks, false);
        guiGraphics.drawString(font, createdText, textX, rowY + 14, MUTED_COLOR, false);
        guiGraphics.drawString(font, updatedText, textX, rowY + 24, MUTED_COLOR, false);
    }

    private int maxVisibleEntries() {
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        return Math.max(1, availableHeight / JournalLayout.LOG_ROW_HEIGHT);
    }

    static Component formatGameTime(String key, long gameTime, long dayTime) {
        GameTimeFormatHelper.GameTimeParts parts = GameTimeFormatHelper.fromTime(gameTime, dayTime);
        return Component.translatable(key, parts.day(), parts.hour(), parts.minute());
    }

    static Component formatTriggerType(@Nullable TriggerType triggerType) {
        String name = triggerType != null ? triggerType.serializedName() : TriggerType.UNKNOWN.serializedName();
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_trigger_type." + name);
    }

    static String formatBlockName(@Nullable ResourceLocation blockId) {
        if (blockId == null) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_source_block").getString();
        }
        Item item = BuiltInRegistries.ITEM.get(blockId);
        if (item != Items.AIR || blockId.equals(ResourceLocation.withDefaultNamespace("air"))) {
            return new ItemStack(item).getHoverName().getString();
        }
        return formatTranslatedIdentifier(blockId, "block");
    }

    static String formatStructureName(@Nullable ResourceLocation id) {
        if (id == null) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_structure").getString();
        }
        return formatTranslatedIdentifier(id, "structure");
    }

    static String formatBiomeName(ResourceLocation id) {
        return formatTranslatedIdentifier(id, "biome");
    }

    static ItemStack createSourceStack(@Nullable ResourceLocation sourceBlockId) {
        if (sourceBlockId == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(sourceBlockId);
        if (item == Items.AIR && !sourceBlockId.equals(ResourceLocation.withDefaultNamespace("air"))) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }

    @Nullable
    static ItemStack createLootStack(String signatureKey, int count) {
        LootResultSignature signature = LootResultSignature.fromStoredKey(signatureKey);
        if (signature == null || count <= 0) {
            return null;
        }
        ItemStack stack = signature.createPreviewStack();
        if (stack.isEmpty()) {
            return null;
        }
        stack = stack.copy();
        stack.setCount(Math.max(1, Math.min(count, stack.getMaxStackSize())));
        return stack;
    }

    static List<Map.Entry<String, Integer>> sortedLootEntries(Map<String, Integer> lootMap) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(lootMap.entrySet());
        entries.sort((left, right) -> {
            int cmp = Integer.compare(right.getValue(), left.getValue());
            if (cmp != 0) {
                return cmp;
            }
            return left.getKey().compareTo(right.getKey());
        });
        return entries;
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
