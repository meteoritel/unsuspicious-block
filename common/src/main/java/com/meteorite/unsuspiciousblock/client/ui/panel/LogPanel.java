package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.client.ui.support.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryLogRef;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.unsuspiciousblock.client.ui.support.PaginationState;
import com.meteorite.unsuspiciousblock.client.ui.widget.CopyCoordinateButton;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** 日志列表面板——紧凑两行布局、精灵图集背景、搜索排序、分组视图 */
public final class LogPanel implements PagePanel {
    private static final ResourceLocation ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/log_entry.png");
    // 精灵图集状态纵向偏移：normal=0, hovered=26, selected=52
    private static final int[] STATE_V = {0, JournalLayout.LOG_ENTRY_STATE_HEIGHT, JournalLayout.LOG_ENTRY_STATE_HEIGHT * 2};

    private static final int MUTED_COLOR = 0x7A6247;
    private static final int SELECTED_TEXT_COLOR = 0x7B3E18;

    // 复制坐标按钮命中盒（缓存最近一帧的悬停按钮位置，用于 tooltip 渲染与点击判定）
    private static final int COPY_BTN_HOVER_NONE = -1;
    private int copyBtnHoverX = COPY_BTN_HOVER_NONE;
    // 当前帧悬停的条目（用于备注 tooltip 渲染），每帧 render 时重置
    @Nullable
    private LogEntryState hoveredEntryState;
    // 当前帧悬停备注图标的条目（用于编辑入口 tooltip 渲染），每帧 render 时重置
    @Nullable
    private LogEntryState noteBadgeHoveredEntry;
    // 备注图标点击回调（由外部 screen 设置，打开备注编辑界面）
    @Nullable
    private Consumer<ExcavationLogEntry> noteClickHandler;

    /** 显示行：组头或条目，混合高度分页 */
    private interface DisplayRow {
        int height();
    }

    /** 行索引区间 [start, end) */
    private record IntRange(int start, int end) {
    }

    /** 计算指定页码下可见行索引区间 [start, end) */
    private IntRange computeVisibleRows(int page) {
        if (this.displayRows.isEmpty()) {
            return new IntRange(0, 0);
        }
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        int currentY = 0;
        int currentPage = 0;
        int startRow = 0;
        int endRow = 0;

        for (int i = 0; i < this.displayRows.size(); i++) {
            int rowHeight = this.displayRows.get(i).height();
            if (currentY + rowHeight > availableHeight) {
                currentPage++;
                currentY = 0;
            }
            if (currentPage == page) {
                if (startRow == 0 && currentY == 0) {
                    startRow = i;
                }
                endRow = i + 1;
            }
            currentY += rowHeight;
            if (currentPage > page) break;
        }
        return new IntRange(startRow, endRow);
    }

    /**
     * 组头行——不可点击，显示组名 + 条目数
     */
    private record GroupHeaderRow(String groupName, int count) implements DisplayRow {

        @Override
        public int height() {
            return JournalLayout.LOG_GROUP_HEADER_HEIGHT;
        }
    }

    /**
     * 条目行——可点击，可选中
     */
    private record EntryRow(LogEntryState state) implements DisplayRow {

        @Override
        public int height() {
            return JournalLayout.LOG_ROW_HEIGHT;
        }
    }

    private final JournalBookBackground.BookLayout layout;
    private final List<LogEntryState> allEntries = new ArrayList<>();
    private final List<LogEntryState> filteredEntries = new ArrayList<>();
    private final List<DisplayRow> displayRows = new ArrayList<>();
    private final PaginationState pagination = new PaginationState(this::computePageCount);
    // 日志数据引用
    private ArchaeologyEntryLogRef logRef = ArchaeologyEntryLogRef.EMPTY;
    // 排序方向：true=降序（最新在前），false=升序（最旧在前）
    private boolean sortDescending = true;
    // 分组状态——默认按时间区间分组
    private LogGrouper.GroupMode groupMode = LogGrouper.GroupMode.TIME;
    // 时间分组参考刻：仅在玩家切换到日志页时刷新一次，避免每帧重算
    private long referenceGameTime = 0L;
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

    // 设置排序方向
    public void setSortDescending(boolean descending) {
        this.sortDescending = descending;
        applyFilterAndSort();
    }

    // 设置分组方式
    public void setGroupMode(LogGrouper.GroupMode mode) {
        this.groupMode = mode;
        applyFilterAndSort();
    }

    // 设置时间分组参考刻（仅玩家切换到日志页时调用一次）
    public void setReferenceGameTime(long gameTime) {
        this.referenceGameTime = gameTime;
        applyFilterAndSort();
    }

    // 设置选中的日志条目 ID
    public void setSelectedEntryId(@Nullable UUID entryId) {
        this.selectedEntryId = entryId;
    }

    // 设置备注图标点击回调（列表页点击铅笔图标时触发，打开备注编辑界面）
    public void setNoteClickHandler(@Nullable Consumer<ExcavationLogEntry> handler) {
        this.noteClickHandler = handler;
    }

    // 对全部条目执行排序和分组，结果写入 displayRows
    private void applyFilterAndSort() {
        this.filteredEntries.clear();
        this.filteredEntries.addAll(this.allEntries);
        // 时间排序：按 lastUpdated → created 优先级比较；降序时反转
        Comparator<LogEntryState> cmp = Comparator
                .comparingLong((LogEntryState s) -> s.entry.lastUpdatedGameTime())
                .thenComparingLong(s -> s.entry.lastUpdatedDayTime())
                .thenComparingLong(s -> s.entry.createdGameTime())
                .thenComparingLong(s -> s.entry.createdDayTime())
                .thenComparing(s -> s.entry.entryId());
        if (this.sortDescending) {
            cmp = cmp.reversed();
        }
        this.filteredEntries.sort(cmp);

        // 构建 displayRows
        this.displayRows.clear();
        if (this.groupMode == LogGrouper.GroupMode.TIME) {
            // 时间分组：桶顺序跟随排序方向——降序=1d→older（最新桶在前），升序=older→1d（最旧桶在前）
            LogGrouper.TimeBucket[] bucketOrder = LogGrouper.TimeBucket.values();
            LinkedHashMap<String, List<LogEntryState>> buckets = new LinkedHashMap<>();
            int len = bucketOrder.length;
            for (int i = 0; i < len; i++) {
                LogGrouper.TimeBucket bucket = this.sortDescending
                        ? bucketOrder[i]
                        : bucketOrder[len - 1 - i];
                buckets.put(bucket.key(), new ArrayList<>());
            }
            for (LogEntryState state : this.filteredEntries) {
                String key = LogGrouper.groupKey(this.groupMode, state.entry, this.referenceGameTime);
                buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(state);
            }
            for (var entry : buckets.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                this.displayRows.add(new GroupHeaderRow(entry.getKey(), entry.getValue().size()));
                for (LogEntryState state : entry.getValue()) {
                    this.displayRows.add(new EntryRow(state));
                }
            }
        } else if (this.groupMode == LogGrouper.GroupMode.NOTED) {
            // 已备注分组：固定 "已备注" 在前、"未备注" 在后
            LinkedHashMap<String, List<LogEntryState>> groups = new LinkedHashMap<>();
            groups.put("yes", new ArrayList<>());
            groups.put("no", new ArrayList<>());
            for (LogEntryState state : this.filteredEntries) {
                String key = LogGrouper.groupKey(this.groupMode, state.entry, this.referenceGameTime);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(state);
            }
            for (var entry : groups.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                this.displayRows.add(new GroupHeaderRow(entry.getKey(), entry.getValue().size()));
                for (LogEntryState state : entry.getValue()) {
                    this.displayRows.add(new EntryRow(state));
                }
            }
        } else {
            // 维度/群系分组：按数据顺序聚合
            LinkedHashMap<String, List<LogEntryState>> groups = new LinkedHashMap<>();
            for (LogEntryState state : this.filteredEntries) {
                String key = LogGrouper.groupKey(this.groupMode, state.entry, this.referenceGameTime);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(state);
            }
            for (var entry : groups.entrySet()) {
                this.displayRows.add(new GroupHeaderRow(entry.getKey(), entry.getValue().size()));
                for (LogEntryState state : entry.getValue()) {
                    this.displayRows.add(new EntryRow(state));
                }
            }
        }

        this.pagination.setPage(this.pagination.getPage());
    }

    @Override
    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = this.layout.rightPageX() + 8;
        int listStartY = this.layout.rightPageY() + JournalLayout.LOG_LIST_TOP;
        // 重置按钮悬停缓存（在 renderEntry 中重新填充）
        this.copyBtnHoverX = COPY_BTN_HOVER_NONE;
        this.hoveredEntryState = null;
        this.noteBadgeHoveredEntry = null;
        if (this.displayRows.isEmpty()) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_empty"),
                    leftX, listStartY, MUTED_COLOR, false);
            return;
        }

        // 计算当前页可见的 displayRows（混合高度）
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        IntRange visible = computeVisibleRows(this.pagination.getPage());

        // 渲染当前页的行
        int yOffset = 0;
        for (int i = visible.start(); i < visible.end() && i < this.displayRows.size(); i++) {
            DisplayRow row = this.displayRows.get(i);
            int rowY = listStartY + yOffset;
            if (rowY + row.height() > listStartY + availableHeight) break;

            if (row instanceof GroupHeaderRow header) {
                renderGroupHeader(guiGraphics, font, leftX, rowY, header);
            } else if (row instanceof EntryRow(LogEntryState state)) {
                renderEntry(guiGraphics, font, leftX, rowY, state, mouseX, mouseY);
            }
            yOffset += row.height();
        }
    }

    // 渲染组头行
    private void renderGroupHeader(GuiGraphics guiGraphics, Font font, int leftX, int rowY, GroupHeaderRow header) {
        int bgX = leftX - 4;
        int bgW = JournalLayout.LOG_ENTRY_TEXTURE_WIDTH;
        int bgH = JournalLayout.LOG_GROUP_HEADER_HEIGHT;

        // 绘制暖色背景条（复用详情卡片颜色）
        guiGraphics.fill(bgX, rowY, bgX + bgW, rowY + bgH, JournalLayout.LOG_GROUP_HEADER_BG);
        // 顶部边框线
        guiGraphics.fill(bgX, rowY, bgX + bgW, rowY + 1, JournalLayout.LOG_GROUP_HEADER_BORDER);

        // 组名文本
        Component groupLabel = LogGrouper.groupHeader(this.groupMode, header.groupName, header.count);
        guiGraphics.drawString(font, groupLabel, leftX + 2, rowY + 3, JournalLayout.LOG_GROUP_HEADER_COLOR, false);
    }

    @Override
    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= this.layout.rightPageX() && mouseX <= this.layout.rightPageRight()
                && mouseY >= this.layout.rightPageY() && mouseY <= this.layout.rightPageBottom();
    }

    @Nullable
    public ExcavationLogEntry handleClick(double mouseX, double mouseY) {
        // 计算当前页可见行范围
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        IntRange visible = computeVisibleRows(this.pagination.getPage());

        int leftX = this.layout.rightPageX() + 8;
        int listStartY = this.layout.rightPageY() + JournalLayout.LOG_LIST_TOP;
        int yOffset = 0;
        for (int i = visible.start(); i < visible.end() && i < this.displayRows.size(); i++) {
            DisplayRow row = this.displayRows.get(i);
            int rowY = listStartY + yOffset;
            if (rowY + row.height() > listStartY + availableHeight) break;

            // 仅条目行可点击
            if (row instanceof EntryRow(LogEntryState state)) {
                if (mouseX >= leftX - 4 && mouseX <= leftX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH
                        && mouseY >= rowY && mouseY <= rowY + JournalLayout.LOG_ROW_HEIGHT) {
                    // 优先判定复制坐标按钮命中：命中则复制传送指令到剪贴板，不进入详情
                    if (isCopyButtonHit(leftX - 4, rowY, mouseX, mouseY)) {
                        CopyCoordinateButton.copyCommand(state.entry);
                        return null;
                    }
                    // 其次判定备注图标命中：打开备注编辑界面，不进入详情
                    if (state.entry.hasNote() && this.noteClickHandler != null
                            && isNoteBadgeHit(leftX - 4, rowY, mouseX, mouseY)) {
                        this.noteClickHandler.accept(state.entry);
                        return null;
                    }
                    return state.entry;
                }
            }
            yOffset += row.height();
        }
        return null;
    }

    // 判定鼠标是否落在某条目的复制坐标按钮上
    private static boolean isCopyButtonHit(int bgX, int rowY, double mouseX, double mouseY) {
        int btnX = bgX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH
                - CopyCoordinateButton.WIDTH - JournalLayout.LOG_ENTRY_COPY_BTN_RIGHT_PAD;
        int btnY = rowY + JournalLayout.LOG_ENTRY_COPY_BTN_TOP_OFFSET;
        return CopyCoordinateButton.isHit(btnX, btnY, mouseX, mouseY);
    }

    // 判定鼠标是否落在某条目的备注图标上（命中盒比字符略大，提升可点击性）
    private static boolean isNoteBadgeHit(int bgX, int rowY, double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;
        String badge = "✎";
        int badgeX = bgX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH - font.width(badge) - 3;
        int badgeY = rowY + JournalLayout.LOG_ROW_HEIGHT - font.lineHeight - 1;
        int hitX = badgeX - 3;
        int hitY = badgeY - 2;
        int hitW = font.width(badge) + 6;
        int hitH = font.lineHeight + 3;
        return mouseX >= hitX && mouseX <= hitX + hitW
                && mouseY >= hitY && mouseY <= hitY + hitH;
    }

    @Nullable
    public ExcavationLogEntry findEntry(UUID entryId) {
        for (LogEntryState state : this.allEntries) {
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
            this.hoveredEntryState = state;
        }

        // 绘制圆角纹理背景：normal/hovered/selected
        int stateIdx = selected ? 2 : (hovered ? 1 : 0);
        int bgX = leftX - 4;
        guiGraphics.blit(ENTRY_TEXTURE, bgX, rowY,
                JournalLayout.LOG_ENTRY_TEXTURE_WIDTH, JournalLayout.LOG_ROW_HEIGHT,
                0, STATE_V[stateIdx],
                JournalLayout.LOG_ENTRY_TEXTURE_WIDTH, JournalLayout.LOG_ENTRY_STATE_HEIGHT,
                JournalLayout.LOG_ENTRY_TEXTURE_WIDTH, JournalLayout.LOG_ENTRY_TEXTURE_HEIGHT);

        // 选中态：左侧绘制 2px 色条，强化选中识别（贴背景左边缘）
        if (selected) {
            int barW = JournalLayout.LOG_LIST_SELECTED_BAR_WIDTH;
            guiGraphics.fill(bgX, rowY + 2, bgX + barW, rowY + JournalLayout.LOG_ROW_HEIGHT - 2,
                    JournalLayout.LOG_LIST_SELECTED_BAR_COLOR);
        }

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

        // 复制坐标按钮：第一行右侧
        int btnX = bgX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH
                - CopyCoordinateButton.WIDTH - JournalLayout.LOG_ENTRY_COPY_BTN_RIGHT_PAD;
        int btnY = rowY + JournalLayout.LOG_ENTRY_COPY_BTN_TOP_OFFSET;
        boolean btnHovered = CopyCoordinateButton.isHit(btnX, btnY, mouseX, mouseY);
        CopyCoordinateButton.render(guiGraphics, btnX, btnY, btnHovered);
        if (btnHovered) {
            this.copyBtnHoverX = btnX;
        }

        // 行1：时间（左侧，主色——最高优先级）
        String timeText = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_time_short",
                state.entry.createdGameTime(), state.entry.createdDayTime()).getString();
        int timeMaxWidth = Math.max(0, btnX - textX - 4);
        int timeColor = selected ? SELECTED_TEXT_COLOR : JournalLayout.LOG_ENTRY_TIME_COLOR;
        ScrollTextHelper.draw(guiGraphics, font, timeText,
                textX, rowY + 3, timeMaxWidth, timeColor, hovered, state.scrollTicks, false);

        // 行2：维度名称（x,y,z）
        var pos = state.entry.pos();
        String dimensionText = JournalFormatHelper.formatDimensionName(state.entry.dimensionId());
        String posText = String.format("(%d,%d,%d)", pos.getX(), pos.getY(), pos.getZ());
        String line2 = dimensionText + " " + posText;
        ScrollTextHelper.draw(guiGraphics, font, line2,
                textX, rowY + 14, textWidth, JournalLayout.LOG_ENTRY_DIM_POS_COLOR, hovered, state.scrollTicks, false);

        // 已备注标记：右下角绘制小铅笔字符，悬停时高亮提示可点击编辑
        if (state.entry.hasNote()) {
            String badge = "✎";
            int badgeX = bgX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH - font.width(badge) - 3;
            int badgeY = rowY + JournalLayout.LOG_ROW_HEIGHT - font.lineHeight - 1;
            boolean badgeHovered = isNoteBadgeHit(bgX, rowY, mouseX, mouseY);
            if (badgeHovered) {
                // 半透明背景 + 暖橙字符，提示可点击
                int hitX = badgeX - 3;
                int hitY = badgeY - 2;
                int hitW = font.width(badge) + 6;
                int hitH = font.lineHeight + 3;
                guiGraphics.fill(hitX, hitY, hitX + hitW, hitY + hitH,
                        JournalLayout.LOG_ENTRY_NOTE_BADGE_BG_HOVER);
                guiGraphics.drawString(font, badge, badgeX, badgeY,
                        JournalLayout.LOG_ENTRY_NOTE_BADGE_HOVER_COLOR, false);
                this.noteBadgeHoveredEntry = state;
            } else {
                guiGraphics.drawString(font, badge, badgeX, badgeY,
                        JournalLayout.LOG_ENTRY_NOTE_BADGE_COLOR, false);
            }
        }
    }

    // 渲染复制按钮与备注的悬停 tooltip（由外部在 super.render 之后调用，确保位于最上层）
    public void renderTooltips(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        // 优先渲染复制按钮 tooltip（命中按钮时）
        if (this.copyBtnHoverX != COPY_BTN_HOVER_NONE) {
            CopyCoordinateButton.renderTooltip(guiGraphics, font, mouseX, mouseY);
            return;
        }
        // 其次渲染备注图标编辑提示（悬停铅笔图标时，提示可点击编辑）
        if (this.noteBadgeHoveredEntry != null) {
            guiGraphics.renderTooltip(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_edit_from_list_tooltip"),
                    mouseX, mouseY);
            return;
        }
        // 最后渲染备注内容 tooltip（悬停已备注条目其他区域时）
        if (this.hoveredEntryState != null && this.hoveredEntryState.entry.hasNote()) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_tooltip_title"));
            // 按换行符拆分备注正文，保持玩家书写格式
            String note = this.hoveredEntryState.entry.note();
            for (String rawLine : note.split("\n", -1)) {
                if (rawLine.isEmpty()) {
                    lines.add(Component.literal(" "));
                } else {
                    lines.add(Component.literal(rawLine));
                }
            }
            guiGraphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
        }
    }

    public boolean hasVisibleEntries() {
        return !this.filteredEntries.isEmpty();
    }

    private int computePageCount() {
        if (this.displayRows.isEmpty()) {
            return 1;
        }
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        int pages = 1;
        int usedHeight = 0;
        for (DisplayRow displayRow : this.displayRows) {
            int h = displayRow.height();
            if (usedHeight + h > availableHeight) {
                pages++;
                usedHeight = 0;
            }
            usedHeight += h;
        }
        return pages;
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