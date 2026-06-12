package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.helper.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.client.ui.helper.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryLogRef;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.LogSorter;
import com.meteorite.unsuspiciousblock.client.ui.support.PaginationState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 日志列表面板——紧凑两行布局、精灵图集背景、搜索排序 */
public final class LogPanel implements PagePanel {
    private static final ResourceLocation ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/log_entry.png");
    // 精灵图集状态纵向偏移：normal=0, hovered=26, selected=52
    private static final int[] STATE_V = {0, JournalLayout.LOG_ENTRY_STATE_HEIGHT, JournalLayout.LOG_ENTRY_STATE_HEIGHT * 2};

    private static final int LABEL_COLOR = 0x5A422C;
    private static final int TEXT_COLOR = 0x4A3320;
    private static final int MUTED_COLOR = 0x7A6247;
    private static final int SELECTED_TEXT_COLOR = 0x7B3E18;

    private final JournalBookBackground.BookLayout layout;
    private final List<LogEntryState> allEntries = new ArrayList<>();
    private final List<LogEntryState> filteredEntries = new ArrayList<>();
    private final PaginationState pagination = new PaginationState(this::computePageCount);
    // 日志数据引用
    private ArchaeologyEntryLogRef logRef = ArchaeologyEntryLogRef.EMPTY;
    // 搜索排序状态
    private LogSorter.SortOrder sortOrder = LogSorter.SortOrder.TIME;
    private boolean sortDescending = true;
    private String searchFilter = "";
    @Nullable
    private UUID selectedEntryId;

    public LogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    // 设置日志数据
    public void setData(@Nullable ArchaeologyEntryLogRef logRef) {
        this.logRef = logRef != null ? logRef : ArchaeologyEntryLogRef.EMPTY;
        this.allEntries.clear();
        for (ExcavationLogEntry entry : this.logRef.logEntries()) {
            this.allEntries.add(new LogEntryState(entry));
        }
        applyFilterAndSort();
    }

    // 设置排序方式和方向
    public void setSortOrder(LogSorter.SortOrder order, boolean descending) {
        this.sortOrder = order;
        this.sortDescending = descending;
        applyFilterAndSort();
    }

    // 设置搜索过滤文本
    public void setSearchFilter(String filter) {
        this.searchFilter = filter;
        applyFilterAndSort();
    }

    // 设置选中的日志条目ID
    public void setSelectedEntryId(@Nullable UUID entryId) {
        this.selectedEntryId = entryId;
    }

    // 对全部条目执行筛选和排序，结果写入 filteredEntries
    private void applyFilterAndSort() {
        String lowerFilter = this.searchFilter.toLowerCase(Locale.ROOT);
        this.filteredEntries.clear();
        for (LogEntryState state : this.allEntries) {
            if (matchesFilter(state.entry, lowerFilter)) {
                this.filteredEntries.add(state);
            }
        }
        this.filteredEntries.sort((a, b) -> LogSorter.getComparator(this.sortOrder, this.sortDescending)
                .compare(a.entry, b.entry));
        this.pagination.setPage(this.pagination.getPage());
    }

    // 简单子串匹配：检查条目的结构/触发/来源/群系文本是否包含搜索词
    private boolean matchesFilter(ExcavationLogEntry entry, String lowerFilter) {
        if (lowerFilter.isEmpty()) {
            return true;
        }
        String structure = JournalFormatHelper.formatStructureName(entry.structureId()).toLowerCase(Locale.ROOT);
        if (structure.contains(lowerFilter)) {
            return true;
        }
        String lootSource = JournalFormatHelper.formatLootSource(entry.lootSource()).getString().toLowerCase(Locale.ROOT);
        if (lootSource.contains(lowerFilter)) {
            return true;
        }
        String source = JournalFormatHelper.formatLootSource(entry.lootSource()).getString().toLowerCase(Locale.ROOT);
        if (source.contains(lowerFilter)) {
            return true;
        }
        String biome = JournalFormatHelper.formatBiomeName(entry.biomeId()).toLowerCase(Locale.ROOT);
        return biome.contains(lowerFilter);
    }

    @Override
    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = this.layout.rightPageX() + 8;
        int listStartY = this.layout.rightPageY() + JournalLayout.LOG_LIST_TOP;
        if (this.filteredEntries.isEmpty()) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_empty"),
                    leftX, listStartY, MUTED_COLOR, false);
            return;
        }

        int maxVisibleEntries = maxVisibleEntries();
        int page = this.pagination.getPage();
        int from = page * maxVisibleEntries;
        int to = Math.min(this.filteredEntries.size(), from + maxVisibleEntries);
        for (int i = from; i < to; i++) {
            int rowY = listStartY + (i - from) * JournalLayout.LOG_ROW_HEIGHT;
            renderEntry(guiGraphics, font, leftX, rowY, this.filteredEntries.get(i), mouseX, mouseY);
        }
    }

    @Override
    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= this.layout.rightPageX() && mouseX <= this.layout.rightPageRight()
                && mouseY >= this.layout.rightPageY() && mouseY <= this.layout.rightPageBottom();
    }

    @Nullable
    public ExcavationLogEntry handleClick(double mouseX, double mouseY) {
        int from = this.pagination.getPage() * maxVisibleEntries();
        int to = Math.min(this.filteredEntries.size(), from + maxVisibleEntries());
        int leftX = this.layout.rightPageX() + 8;
        for (int i = from; i < to; i++) {
            int rowY = this.layout.rightPageY() + JournalLayout.LOG_LIST_TOP
                    + (i - from) * JournalLayout.LOG_ROW_HEIGHT;
            if (mouseX >= leftX - 4 && mouseX <= leftX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH
                    && mouseY >= rowY && mouseY <= rowY + JournalLayout.LOG_ROW_HEIGHT) {
                return this.filteredEntries.get(i).entry;
            }
        }
        return null;
    }

    @Nullable
    public ExcavationLogEntry findEntry(UUID entryId) {
        for (LogEntryState state : this.filteredEntries) {
            if (state.entry.entryId().equals(entryId)) {
                return state.entry;
            }
        }
        return null;
    }

    @Override
    public int pageCount() {
        return this.pagination.pageCount();
    }

    @Override
    public int getPage() {
        return this.pagination.getPage();
    }

    @Override
    public void changePage(int delta) {
        this.pagination.changePage(delta);
    }

    @Override
    public void setPage(int page) {
        this.pagination.setPage(page);
    }

    // 渲染单个日志条目
    private void renderEntry(GuiGraphics guiGraphics, Font font, int leftX, int rowY,
                             LogEntryState state, int mouseX, int mouseY) {
        boolean hovered = mouseX >= leftX - 4 && mouseX <= leftX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH
                && mouseY >= rowY && mouseY <= rowY + JournalLayout.LOG_ROW_HEIGHT;
        boolean selected = state.entry.entryId().equals(this.selectedEntryId);

        if (!state.wasHovered && hovered) {
            state.scrollTicks = 0;
        }
        state.wasHovered = hovered;
        if (hovered) {
            state.scrollTicks++;
        }

        // 绘制圆角纹理背景：normal/hovered/selected
        int stateIdx = selected ? 2 : (hovered ? 1 : 0);
        int bgX = leftX - 4;
        guiGraphics.blit(ENTRY_TEXTURE, bgX, rowY,
                JournalLayout.LOG_ENTRY_TEXTURE_WIDTH, JournalLayout.LOG_ROW_HEIGHT,
                0, STATE_V[stateIdx],
                JournalLayout.LOG_ENTRY_TEXTURE_WIDTH, JournalLayout.LOG_ENTRY_STATE_HEIGHT,
                JournalLayout.LOG_ENTRY_TEXTURE_WIDTH, JournalLayout.LOG_ENTRY_TEXTURE_HEIGHT);

        // 来源图标（竖直居中）
        var sourceStack = state.entry.lootSource() != null ? state.entry.lootSource().iconItem() : net.minecraft.world.item.ItemStack.EMPTY;
        int textX = leftX;
        int textWidth = JournalLayout.LOG_ENTRY_TEXT_WIDTH;
        if (!sourceStack.isEmpty()) {
            int iconY = rowY + (JournalLayout.LOG_ROW_HEIGHT - JournalLayout.LOG_ENTRY_ICON_SIZE) / 2;
            guiGraphics.renderItem(sourceStack, leftX, iconY);
            guiGraphics.renderItemDecorations(font, sourceStack, leftX, iconY);
            textX += JournalLayout.LOG_ENTRY_ICON_SIZE + JournalLayout.LOG_ENTRY_ICON_GAP;
            textWidth -= JournalLayout.LOG_ENTRY_ICON_SIZE + JournalLayout.LOG_ENTRY_ICON_GAP;
        }

        // 行1：结构名（左侧可滚动） + 坐标（右侧固定，带括号）
        String structureText = JournalFormatHelper.formatStructureName(state.entry.structureId());
        int nameColor = selected ? SELECTED_TEXT_COLOR : TEXT_COLOR;

        // 坐标右对齐，带括号
        var pos = state.entry.pos();
        String posText = String.format("(%d,%d,%d)", pos.getX(), pos.getY(), pos.getZ());
        int posWidth = font.width(posText);
        int posX = leftX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH - posWidth - 4;
        guiGraphics.drawString(font, posText, posX, rowY + 3, MUTED_COLOR, false);

        int structureMaxWidth = Math.max(0, posX - textX - 4);
        ScrollTextHelper.draw(guiGraphics, font, structureText,
                textX, rowY + 3, structureMaxWidth, nameColor, hovered, state.scrollTicks, false);

        // 行2：仅显示时间
        String timeText = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_time_short",
                state.entry.createdGameTime(), state.entry.createdDayTime()).getString();
        ScrollTextHelper.draw(guiGraphics, font, timeText,
                textX, rowY + 14, textWidth, MUTED_COLOR, hovered, state.scrollTicks, false);
    }

    public boolean hasVisibleEntries() {
        return !this.filteredEntries.isEmpty();
    }

    private int maxVisibleEntries() {
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        return availableHeight / JournalLayout.LOG_ROW_HEIGHT;
    }

    private int computePageCount() {
        if (this.filteredEntries.isEmpty()) {
            return 1;
        }
        int maxVisibleEntries = maxVisibleEntries();
        return Math.max(1, (this.filteredEntries.size() + maxVisibleEntries - 1) / maxVisibleEntries);
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