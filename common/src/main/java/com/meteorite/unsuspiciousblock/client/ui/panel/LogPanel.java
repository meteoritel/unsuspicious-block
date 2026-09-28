package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.client.ui.support.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryLogRef;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.unsuspiciousblock.client.ui.support.PaginationState;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.client.ui.widget.CopyCoordinateButton;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 日志列表面板——紧凑两行布局、精灵图集背景、搜索排序、分组视图 */
public final class LogPanel implements PagePanel {
    private static final ResourceLocation ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/log_entry.png");
    // 精灵图集状态纵向偏移：normal=0, hovered=26, selected=52
    private static final int[] STATE_V = {0, JournalLayout.LOG_ENTRY_STATE_HEIGHT, JournalLayout.LOG_ENTRY_STATE_HEIGHT * 2};

    // 空态等弱化文本：原字面量 #7A6247 在书页底色 #E8DCBC 上只有 4.20:1，改引语义色表 LABEL（5.03:1）
    private static final int MUTED_COLOR = UiTextPalette.Parchment.LABEL;
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

    /** 计算指定页码下可见行索引区间 [start, end)；越界返回空区间 */
    private IntRange computeVisibleRows(int page) {
        if (page < 0 || page >= this.pageRanges.size()) {
            return new IntRange(0, 0);
        }
        return this.pageRanges.get(page);
    }

    // 按行高把 displayRows 切成页区间：渲染、命中与总页数读同一份结果（R-5）。
    // 切分规则（与原 computeVisibleRows 逐行判定等价）：行放不下就在该行之前断页，该行成为下一页首行。
    private List<IntRange> buildPageRanges() {
        List<IntRange> ranges = new ArrayList<>();
        if (this.displayRows.isEmpty()) {
            return ranges;
        }
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        int start = 0;
        int usedHeight = 0;
        for (int i = 0; i < this.displayRows.size(); i++) {
            int rowHeight = this.displayRows.get(i).height();
            if (usedHeight + rowHeight > availableHeight) {
                ranges.add(new IntRange(start, i));
                start = i;
                usedHeight = 0;
            }
            usedHeight += rowHeight;
        }
        ranges.add(new IntRange(start, this.displayRows.size()));
        return ranges;
    }

    /**
     * 组头行——不可点击，显示组名 + 条目数
     */
    private record GroupHeaderRow(String groupName, List<LogEntryState> entries) implements DisplayRow {

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
    // 分页几何缓存：只在 applyFilterAndSort 重建，render/handleClick 不再逐帧走一遍行高累加
    private List<IntRange> pageRanges = List.of();
    // 已测宽度缓存（P-10）：与行文案缓存同一失效事件（语言/字体实例、setData）
    private final PanelTextMetrics metrics = new PanelTextMetrics();
    private final PaginationState pagination = new PaginationState(this::computePageCount);
    // 排序方向：true=降序（最新在前），false=升序（最旧在前）
    private boolean sortDescending = true;
    // 分组状态——默认按时间区间分组
    private LogGrouper.GroupMode groupMode = LogGrouper.GroupMode.TIME;
    // 时间分组参考刻：仅在玩家切换到日志页时刷新一次，避免每帧重算
    private long referenceGameTime = 0L;
    @Nullable
    private UUID selectedEntryId;
    private boolean batchSelectionMode;
    private final Set<UUID> selectedEntryIds = new LinkedHashSet<>();

    public LogPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    // 设置日志数据
    public void setData(@Nullable ArchaeologyEntryLogRef logRef) {
        // 日志数据引用
        ArchaeologyEntryLogRef logRef1 =
                logRef != null ? logRef : ArchaeologyEntryLogRef.EMPTY;
        this.allEntries.clear();
        for (ExcavationLogEntry entry : logRef1.logEntries()) {
            this.allEntries.add(new LogEntryState(entry));
        }
        Set<UUID> availableIds = new LinkedHashSet<>();
        this.allEntries.forEach(state -> availableIds.add(state.entry.entryId()));
        this.selectedEntryIds.retainAll(availableIds);
        this.metrics.clear();
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

    public void setBatchSelectionMode(boolean enabled) {
        this.batchSelectionMode = enabled;
        if (!enabled) {
            this.selectedEntryIds.clear();
        }
        this.selectedEntryId = null;
    }

    public boolean isBatchSelectionMode() {
        return this.batchSelectionMode;
    }

    public int getSelectedEntryCount() {
        return this.selectedEntryIds.size();
    }

    public List<UUID> getSelectedEntryIds() {
        return List.copyOf(this.selectedEntryIds);
    }

    // 恢复 resize 前的批量模式，并丢弃已不在当前表中的条目。
    public void restoreBatchSelection(boolean enabled, Collection<UUID> entryIds) {
        this.batchSelectionMode = enabled;
        this.selectedEntryIds.clear();
        if (enabled && !entryIds.isEmpty()) {
            Set<UUID> availableIds = new LinkedHashSet<>();
            this.allEntries.forEach(state -> availableIds.add(state.entry.entryId()));
            for (UUID entryId : entryIds) {
                if (availableIds.contains(entryId)) {
                    this.selectedEntryIds.add(entryId);
                }
            }
        }
        this.selectedEntryId = null;
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
                this.displayRows.add(new GroupHeaderRow(entry.getKey(), List.copyOf(entry.getValue())));
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
                this.displayRows.add(new GroupHeaderRow(entry.getKey(), List.copyOf(entry.getValue())));
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
                this.displayRows.add(new GroupHeaderRow(entry.getKey(), List.copyOf(entry.getValue())));
                for (LogEntryState state : entry.getValue()) {
                    this.displayRows.add(new EntryRow(state));
                }
            }
        }

        this.pageRanges = buildPageRanges();
        this.pagination.setPage(this.pagination.getPage());
    }

    @Override
    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        this.metrics.beginFrame(font);
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
        int textX = leftX + 2;
        if (this.batchSelectionMode) {
            boolean allSelected = header.entries.stream()
                    .allMatch(state -> this.selectedEntryIds.contains(state.entry.entryId()));
            renderCheckbox(guiGraphics, leftX, rowY + 3, allSelected);
            textX += 11;
        }
        Component groupLabel = LogGrouper.groupHeader(
                this.groupMode, header.groupName, header.entries.size());
        guiGraphics.drawString(font, groupLabel, textX, rowY + 3,
                JournalLayout.LOG_GROUP_HEADER_COLOR, false);
    }

    @Override
    public boolean containsMouse(double mouseX, double mouseY) {
        return PagePanel.containsPageBounds(mouseX, mouseY,
                this.layout.rightPageX(), this.layout.rightPageY(),
                this.layout.rightPageRight(), this.layout.rightPageBottom());
    }

    @Nullable
    public ExcavationLogEntry handleClick(double mouseX, double mouseY) {
        if (this.batchSelectionMode) {
            return null;
        }
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

    // 批量模式下点击条目或组头，只切换选择状态，不进入详情页
    public boolean handleBatchSelectionClick(double mouseX, double mouseY) {
        if (!this.batchSelectionMode) {
            return false;
        }
        IntRange visible = computeVisibleRows(this.pagination.getPage());
        int leftX = this.layout.rightPageX() + 8;
        int listStartY = this.layout.rightPageY() + JournalLayout.LOG_LIST_TOP;
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        int yOffset = 0;
        for (int index = visible.start(); index < visible.end() && index < this.displayRows.size(); index++) {
            DisplayRow row = this.displayRows.get(index);
            int rowY = listStartY + yOffset;
            if (rowY + row.height() > listStartY + availableHeight) {
                break;
            }
            if (mouseX >= leftX - 4 && mouseX <= leftX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH
                    && mouseY >= rowY && mouseY <= rowY + row.height()) {
                if (row instanceof GroupHeaderRow header) {
                    toggleGroupSelection(header.entries);
                } else if (row instanceof EntryRow(LogEntryState state)) {
                    toggleEntrySelection(state.entry.entryId());
                }
                return true;
            }
            yOffset += row.height();
        }
        return false;
    }

    private void toggleGroupSelection(List<LogEntryState> entries) {
        boolean allSelected = entries.stream()
                .allMatch(state -> this.selectedEntryIds.contains(state.entry.entryId()));
        for (LogEntryState state : entries) {
            if (allSelected) {
                this.selectedEntryIds.remove(state.entry.entryId());
            } else {
                this.selectedEntryIds.add(state.entry.entryId());
            }
        }
    }

    private void toggleEntrySelection(UUID entryId) {
        if (!this.selectedEntryIds.add(entryId)) {
            this.selectedEntryIds.remove(entryId);
        }
    }

    // 判定鼠标是否落在某条目的复制坐标按钮上
    private static boolean isCopyButtonHit(int bgX, int rowY, double mouseX, double mouseY) {
        int btnX = bgX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH
                - CopyCoordinateButton.WIDTH - JournalLayout.LOG_ENTRY_COPY_BTN_RIGHT_PAD;
        int btnY = rowY + JournalLayout.LOG_ENTRY_COPY_BTN_TOP_OFFSET;
        return CopyCoordinateButton.isHit(btnX, btnY, mouseX, mouseY);
    }

    // 判定鼠标是否落在某条目的备注图标上（命中盒比字符略大，提升可点击性）
    private boolean isNoteBadgeHit(int bgX, int rowY, double mouseX, double mouseY) {
        return noteBadge(Minecraft.getInstance().font, bgX, rowY).hit(mouseX, mouseY);
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
        boolean selected = this.batchSelectionMode
                ? this.selectedEntryIds.contains(state.entry.entryId())
                : state.entry.entryId().equals(this.selectedEntryId);

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
        if (this.batchSelectionMode) {
            renderCheckbox(guiGraphics, textX, rowY + (JournalLayout.LOG_ROW_HEIGHT - 8) / 2, selected);
            textX += 12;
            textWidth -= 12;
        }
        if (!sourceStack.isEmpty()) {
            int iconY = rowY + (JournalLayout.LOG_ROW_HEIGHT - JournalLayout.LOG_ENTRY_ICON_SIZE) / 2;
            guiGraphics.renderItem(sourceStack, textX, iconY);
            guiGraphics.renderItemDecorations(font, sourceStack, textX, iconY);
            textX += JournalLayout.LOG_ENTRY_ICON_SIZE + JournalLayout.LOG_ENTRY_ICON_GAP;
            textWidth -= JournalLayout.LOG_ENTRY_ICON_SIZE + JournalLayout.LOG_ENTRY_ICON_GAP;
        }

        // 复制坐标按钮：第一行右侧
        int btnX = bgX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH
                - CopyCoordinateButton.WIDTH - JournalLayout.LOG_ENTRY_COPY_BTN_RIGHT_PAD;
        int btnY = rowY + JournalLayout.LOG_ENTRY_COPY_BTN_TOP_OFFSET;
        boolean btnHovered = !this.batchSelectionMode
                && CopyCoordinateButton.isHit(btnX, btnY, mouseX, mouseY);
        if (!this.batchSelectionMode) {
            CopyCoordinateButton.render(guiGraphics, btnX, btnY, btnHovered);
            if (btnHovered) {
                this.copyBtnHoverX = btnX;
            }
        }

        // 行1：时间（左侧，主色——最高优先级）；文案按语言缓存，不逐帧重解析翻译（P-12）
        String timeText = entryTimeText(state);
        int timeMaxWidth = Math.max(0, btnX - textX - 4);
        int timeColor = selected ? SELECTED_TEXT_COLOR : JournalLayout.LOG_ENTRY_TIME_COLOR;
        PanelTextMetrics.Measured time = this.metrics.measure(timeText, font);
        ScrollTextHelper.draw(guiGraphics, font, time.text(), time.width(),
                textX, rowY + 3, timeMaxWidth, timeColor, hovered, state.scrollTicks, false);

        // 行2：维度名称 + 坐标（坐标走统一格式化入口）
        String line2 = entryDimensionPosText(state);
        PanelTextMetrics.Measured dimensionPos = this.metrics.measure(line2, font);
        ScrollTextHelper.draw(guiGraphics, font, dimensionPos.text(), dimensionPos.width(),
                textX, rowY + 14, textWidth, JournalLayout.LOG_ENTRY_DIM_POS_COLOR, hovered, state.scrollTicks, false);

        // 已备注标记：右下角绘制小铅笔字符，悬停时高亮提示可点击编辑（几何与命中盒同一来源）
        if (state.entry.hasNote() && !this.batchSelectionMode) {
            NoteBadge badge = noteBadge(font, bgX, rowY);
            boolean badgeHovered = badge.hit(mouseX, mouseY);
            if (badgeHovered) {
                // 半透明背景 + 暖橙字符，提示可点击
                guiGraphics.fill(badge.hitX(), badge.hitY(), badge.hitX() + badge.hitW(),
                        badge.hitY() + badge.hitH(), JournalLayout.LOG_ENTRY_NOTE_BADGE_BG_HOVER);
                guiGraphics.drawString(font, badge.text(), badge.x(), badge.y(),
                        JournalLayout.LOG_ENTRY_NOTE_BADGE_HOVER_COLOR, false);
                this.noteBadgeHoveredEntry = state;
            } else {
                guiGraphics.drawString(font, badge.text(), badge.x(), badge.y(),
                        JournalLayout.LOG_ENTRY_NOTE_BADGE_COLOR, false);
            }
        }
    }

    private static void renderCheckbox(GuiGraphics guiGraphics, int x, int y, boolean checked) {
        guiGraphics.fill(x, y, x + 8, y + 8, 0xFF6E5538);
        guiGraphics.fill(x + 1, y + 1, x + 7, y + 7, 0xFFE7D4AA);
        if (checked) {
            guiGraphics.fill(x + 2, y + 2, x + 6, y + 6, 0xFF7B3E18);
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
        return Math.max(1, this.pageRanges.size());
    }

    /** 备注铅笔图标的绘制位置与命中盒——绘制、悬停底色与点击命中共用同一几何来源（R-5）。 */
    private record NoteBadge(String text, int x, int y, int hitX, int hitY, int hitW, int hitH) {
        private boolean hit(double mouseX, double mouseY) {
            return mouseX >= this.hitX && mouseX <= this.hitX + this.hitW
                    && mouseY >= this.hitY && mouseY <= this.hitY + this.hitH;
        }
    }

    // 语言中立图形符号，不属漏本地化（O-17）
    private static final String NOTE_BADGE_TEXT = "✎";

    // 行内备注图标的唯一几何来源：绘制、悬停底色与命中盒都从这里取；宽度按 P-10 缓存
    private NoteBadge noteBadge(Font font, int bgX, int rowY) {
        int badgeWidth = this.metrics.measure(NOTE_BADGE_TEXT, font).width();
        int badgeX = bgX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH - badgeWidth - 3;
        int badgeY = rowY + JournalLayout.LOG_ROW_HEIGHT - font.lineHeight - 1;
        return new NoteBadge(NOTE_BADGE_TEXT, badgeX, badgeY, badgeX - 3, badgeY - 2,
                badgeWidth + 6, font.lineHeight + 3);
    }

    // 行文案按语言缓存：entry 数据不可变（位置/时间/维度都是值），同一实例只有语言会变（P-12）。
    // 实例在 setData 中重建，因此换数据即天然失效。
    private static void syncEntryText(LogEntryState state) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (language.equals(state.cachedLanguage) && state.timeText != null) {
            return;
        }
        state.cachedLanguage = language;
        state.timeText = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_time_short",
                state.entry.createdGameTime(), state.entry.createdDayTime()).getString();
        var pos = state.entry.pos();
        state.dimensionPosText = JournalFormatHelper.formatDimensionName(state.entry.dimensionId())
                + " " + CopyCoordinateButton.formatCoordinates(pos.getX(), pos.getY(), pos.getZ());
    }

    private static String entryTimeText(LogEntryState state) {
        syncEntryText(state);
        return state.timeText;
    }

    private static String entryDimensionPosText(LogEntryState state) {
        syncEntryText(state);
        return state.dimensionPosText;
    }

    private static final class LogEntryState {
        private final ExcavationLogEntry entry;
        private int scrollTicks;
        private boolean wasHovered;
        // 逐帧行文案缓存（语言失效点见 syncEntryText）
        private String cachedLanguage = "";
        @Nullable private String timeText;
        @Nullable private String dimensionPosText;

        private LogEntryState(ExcavationLogEntry entry) {
            this.entry = entry;
        }
    }
}
