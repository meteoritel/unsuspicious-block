package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.helper.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.client.ui.helper.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.entry.ArchaeologyEntryLogRef;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.LogGrouper;
import com.meteorite.unsuspiciousblock.client.ui.support.PaginationState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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

    /** 显示行：组头或条目，混合高度分页 */
    private interface DisplayRow {
        int height();
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
    private String searchFilter = "";
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

    // 设置搜索过滤文本
    public void setSearchFilter(String filter) {
        this.searchFilter = filter;
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

    public LogGrouper.GroupMode groupMode() {
        return this.groupMode;
    }

    // 设置选中的日志条目 ID
    public void setSelectedEntryId(@Nullable UUID entryId) {
        this.selectedEntryId = entryId;
    }

    // 对全部条目执行筛选、排序和分组，结果写入 displayRows
    private void applyFilterAndSort() {
        String lowerFilter = this.searchFilter.toLowerCase(Locale.ROOT);
        this.filteredEntries.clear();
        for (LogEntryState state : this.allEntries) {
            if (matchesFilter(state, lowerFilter)) {
                this.filteredEntries.add(state);
            }
        }
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
        } else {
            // 维度分组：按数据顺序聚合
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

    // 使用预计算搜索缓存进行子串匹配：结构/来源/群系/维度/物品名
    private boolean matchesFilter(LogEntryState state, String lowerFilter) {
        if (lowerFilter.isEmpty()) {
            return true;
        }
        return state.searchableText.contains(lowerFilter);
    }

    @Override
    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int leftX = this.layout.rightPageX() + 8;
        int listStartY = this.layout.rightPageY() + JournalLayout.LOG_LIST_TOP;
        // 重置按钮悬停缓存（在 renderEntry 中重新填充）
        this.copyBtnHoverX = COPY_BTN_HOVER_NONE;
        if (this.displayRows.isEmpty()) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_empty"),
                    leftX, listStartY, MUTED_COLOR, false);
            return;
        }

        // 计算当前页可见的 displayRows（混合高度）
        int availableHeight = JournalLayout.LOG_LIST_BOTTOM - JournalLayout.LOG_LIST_TOP;
        int page = this.pagination.getPage();
        int currentY = 0;
        int rowIndex = 0;
        int currentPage = 0;
        int startRow = 0;
        int endRow = 0;

        // 分页：找到当前页的起始和结束行索引
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

        // 渲染当前页的行
        int yOffset = 0;
        for (int i = startRow; i < endRow && i < this.displayRows.size(); i++) {
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
        int page = this.pagination.getPage();
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

        int leftX = this.layout.rightPageX() + 8;
        int listStartY = this.layout.rightPageY() + JournalLayout.LOG_LIST_TOP;
        int yOffset = 0;
        for (int i = startRow; i < endRow && i < this.displayRows.size(); i++) {
            DisplayRow row = this.displayRows.get(i);
            int rowY = listStartY + yOffset;
            if (rowY + row.height() > listStartY + availableHeight) break;

            // 仅条目行可点击
            if (row instanceof EntryRow(LogEntryState state)) {
                if (mouseX >= leftX - 4 && mouseX <= leftX + JournalLayout.LOG_ENTRY_TEXTURE_WIDTH
                        && mouseY >= rowY && mouseY <= rowY + JournalLayout.LOG_ROW_HEIGHT) {
                    // 优先判定复制坐标按钮命中：命中则复制 /tp @p x y z 到剪贴板，不进入详情
                    if (isCopyButtonHit(leftX - 4, rowY, mouseX, mouseY)) {
                        copyTeleportCommand(state.entry);
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
                - JournalLayout.LOG_ENTRY_COPY_BTN_WIDTH - JournalLayout.LOG_ENTRY_COPY_BTN_RIGHT_PAD;
        int btnY = rowY + JournalLayout.LOG_ENTRY_COPY_BTN_TOP_OFFSET;
        return mouseX >= btnX && mouseX <= btnX + JournalLayout.LOG_ENTRY_COPY_BTN_WIDTH
                && mouseY >= btnY && mouseY <= btnY + JournalLayout.LOG_ENTRY_COPY_BTN_HEIGHT;
    }

    // 复制 /tp @p x y z 到玩家剪贴板，播放 UI 按钮音效并通过 actionbar 反馈
    private static void copyTeleportCommand(ExcavationLogEntry entry) {
        var pos = entry.pos();
        String command = String.format("/tp @p %d %d %d", pos.getX(), pos.getY(), pos.getZ());
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.setClipboard(command);
        // 播放原版 UI 按钮点击音效，给玩家明确的操作反馈
        minecraft.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_copy_teleport_success", command), true);
        }
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
                - JournalLayout.LOG_ENTRY_COPY_BTN_WIDTH - JournalLayout.LOG_ENTRY_COPY_BTN_RIGHT_PAD;
        int btnY = rowY + JournalLayout.LOG_ENTRY_COPY_BTN_TOP_OFFSET;
        boolean btnHovered = mouseX >= btnX && mouseX <= btnX + JournalLayout.LOG_ENTRY_COPY_BTN_WIDTH
                && mouseY >= btnY && mouseY <= btnY + JournalLayout.LOG_ENTRY_COPY_BTN_HEIGHT;
        renderCopyButton(guiGraphics, btnX, btnY, btnHovered);
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

        // 行2：维度名称（x,y,z）—— 不再显示结构名（与战利品表名重复）
        var pos = state.entry.pos();
        String dimensionText = JournalFormatHelper.formatDimensionName(state.entry.dimensionId());
        String posText = String.format("(%d,%d,%d)", pos.getX(), pos.getY(), pos.getZ());
        String line2 = dimensionText + " " + posText;
        ScrollTextHelper.draw(guiGraphics, font, line2,
                textX, rowY + 14, textWidth, JournalLayout.LOG_ENTRY_DIM_POS_COLOR, hovered, state.scrollTicks, false);
    }

    // 绘制简洁的复制坐标按钮：圆角矩形背景 + 两重叠方块图标
    private void renderCopyButton(GuiGraphics guiGraphics, int btnX, int btnY, boolean hovered) {
        int w = JournalLayout.LOG_ENTRY_COPY_BTN_WIDTH;
        int h = JournalLayout.LOG_ENTRY_COPY_BTN_HEIGHT;
        int bg = hovered ? JournalLayout.LOG_ENTRY_COPY_BTN_BG_HOVER : JournalLayout.LOG_ENTRY_COPY_BTN_BG_NORMAL;
        guiGraphics.fill(btnX, btnY, btnX + w, btnY + h, bg);
        // 边框（4 条 1px 线）
        guiGraphics.fill(btnX, btnY, btnX + w, btnY + 1, JournalLayout.LOG_ENTRY_COPY_BTN_BORDER);
        guiGraphics.fill(btnX, btnY + h - 1, btnX + w, btnY + h, JournalLayout.LOG_ENTRY_COPY_BTN_BORDER);
        guiGraphics.fill(btnX, btnY, btnX + 1, btnY + h, JournalLayout.LOG_ENTRY_COPY_BTN_BORDER);
        guiGraphics.fill(btnX + w - 1, btnY, btnX + w, btnY + h, JournalLayout.LOG_ENTRY_COPY_BTN_BORDER);
        // 图标：两个重叠的方块轮廓（复制符号）
        int iconColor = hovered ? JournalLayout.LOG_ENTRY_COPY_BTN_ICON_HOVER_COLOR
                : JournalLayout.LOG_ENTRY_COPY_BTN_ICON_COLOR;
        drawSquareOutline(guiGraphics, btnX + 3, btnY + 2, 5, iconColor);
        drawSquareOutline(guiGraphics, btnX + 6, btnY + 3, 5, iconColor);
    }

    // 绘制 1px 描边的正方形：size 为边长（含描边）
    private static void drawSquareOutline(GuiGraphics guiGraphics, int x, int y, int size, int color) {
        guiGraphics.fill(x, y, x + size, y + 1, color);            // top
        guiGraphics.fill(x, y + size - 1, x + size, y + size, color); // bottom
        guiGraphics.fill(x, y, x + 1, y + size, color);            // left
        guiGraphics.fill(x + size - 1, y, x + size, y + size, color); // right
    }

    // 渲染复制按钮的悬停 tooltip（由外部在 super.render 之后调用，确保位于最上层）
    public void renderTooltips(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        if (this.copyBtnHoverX == COPY_BTN_HOVER_NONE) {
            return;
        }
        Component tooltip = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_copy_teleport_tooltip");
        guiGraphics.renderTooltip(font, tooltip, mouseX, mouseY);
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
        // 预计算搜索缓存：结构+来源+群系+维度+物品名，避免每次过滤时重复解析signature
        private final String searchableText;

        private LogEntryState(ExcavationLogEntry entry) {
            this.entry = entry;
            this.searchableText = buildSearchableText(entry);
        }

        // 仅在匹配物品名时使用，主搜索走 searchableText
        private static String buildSearchableText(ExcavationLogEntry entry) {
            StringBuilder sb = new StringBuilder();
            sb.append(JournalFormatHelper.formatStructureName(entry.structureId()).toLowerCase(Locale.ROOT)).append('\0');
            sb.append(JournalFormatHelper.formatLootSource(entry.lootSource()).getString().toLowerCase(Locale.ROOT)).append('\0');
            sb.append(JournalFormatHelper.formatBiomeName(entry.biomeId()).toLowerCase(Locale.ROOT)).append('\0');
            sb.append(JournalFormatHelper.formatDimensionName(entry.dimensionId()).toLowerCase(Locale.ROOT)).append('\0');
            appendLootItemNames(entry.expectedLoot(), sb);
            appendLootItemNames(entry.actualLoot(), sb);
            return sb.toString();
        }

        private static void appendLootItemNames(Map<String, Integer> lootMap, StringBuilder sb) {
            for (String signatureKey : lootMap.keySet()) {
                ItemStack stack = JournalFormatHelper.createLootStack(signatureKey, 1);
                if (stack != null && !stack.isEmpty()) {
                    sb.append(stack.getHoverName().getString().toLowerCase(Locale.ROOT)).append('\0');
                }
            }
        }
    }
}