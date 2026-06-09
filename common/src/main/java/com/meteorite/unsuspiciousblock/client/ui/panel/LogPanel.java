package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.helper.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.client.ui.helper.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryLogRef;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.PaginationState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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
    private final PaginationState pagination = new PaginationState(this::computePageCount);
    // 日志数据引用——从条目传入，内部不再直接存储原始字段
    private ArchaeologyEntryLogRef logRef = ArchaeologyEntryLogRef.EMPTY;

    public LogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    // 设置日志数据，接受 ArchaeologyEntryLogRef 替代原来的 4 个独立参数
    public void setData(@Nullable ArchaeologyEntryLogRef logRef) {
        this.logRef = logRef != null ? logRef : ArchaeologyEntryLogRef.EMPTY;
        this.entries.clear();

        List<ExcavationLogEntry> rawEntries = this.logRef.logEntries();
        List<ExcavationLogEntry> sortedEntries = new ArrayList<>(rawEntries);
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
        this.pagination.setPage(this.pagination.getPage());
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = this.layout.rightPageX() + 8;
        int topY = this.layout.rightPageY() + JournalLayout.LOG_TOP;

        guiGraphics.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.first_unlock_time"),
                leftX, topY, LABEL_COLOR, false);

        Component firstUnlockValue = this.logRef.firstUnlockedGameTime() == null
                ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.first_unlock_time_unknown")
                : JournalFormatHelper.formatGameTime("screen.unsuspiciousblock.archaeology_journal.first_unlock_time_value",
                        this.logRef.firstUnlockedGameTime(),
                        this.logRef.firstUnlockedDayTime() != null ? this.logRef.firstUnlockedDayTime() : this.logRef.firstUnlockedGameTime());
        guiGraphics.drawString(font, firstUnlockValue, leftX,
                this.layout.rightPageY() + JournalLayout.LOG_FIRST_UNLOCK_VALUE_Y, TEXT_COLOR, false);

        Component firstUnlockTriggerValue = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.first_unlock_trigger_value",
                JournalFormatHelper.formatTriggerType(this.logRef.firstUnlockTriggerType()));
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
        int page = this.pagination.getPage();
        int from = page * maxVisibleEntries;
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
        int from = this.pagination.getPage() * maxVisibleEntries();
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

        var sourceStack = JournalFormatHelper.createSourceStack(state.entry.sourceBlockId());
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
                JournalFormatHelper.formatStructureName(state.entry.structureId())).getString();
        String createdText = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_created_time_value",
                state.entry.createdGameTime(), state.entry.createdDayTime()).getString();
        String updatedText = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_updated_time_value",
                state.entry.lastUpdatedGameTime(), state.entry.lastUpdatedDayTime()).getString();

        ScrollTextHelper.draw(guiGraphics, font, structureText,
                textX, rowY + 2, textWidth, TEXT_COLOR, hovered, state.scrollTicks, false);
        guiGraphics.drawString(font, createdText, textX, rowY + 14, MUTED_COLOR, false);
        guiGraphics.drawString(font, updatedText, textX, rowY + 24, MUTED_COLOR, false);
    }

    private int maxVisibleEntries() {
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        return availableHeight / JournalLayout.LOG_ROW_HEIGHT;
    }

    private int computePageCount() {
        if (this.entries.isEmpty()) {
            return 1;
        }
        int maxVisibleEntries = maxVisibleEntries();
        return Math.max(1, (this.entries.size() + maxVisibleEntries - 1) / maxVisibleEntries);
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