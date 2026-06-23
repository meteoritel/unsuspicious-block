package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.helper.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.client.ui.helper.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.PaginationState;
import com.meteorite.unsuspiciousblock.client.ui.widget.CopyCoordinateButton;
import com.meteorite.unsuspiciousblock.client.ui.widget.IconButton;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 日志详情面板——标签值元信息卡片、增强型战利品收集清单（角标+总进度条） */
public final class LogDetailPanel implements PagePanel {
    private static final int ICON_SIZE = 20;
    private static final int ICON_GAP = 2;
    private static final int LABEL_GAP = JournalLayout.LOG_DETAIL_META_LABEL_GAP;

    private final JournalBookBackground.BookLayout layout;
    private final PaginationState pagination = new PaginationState(this::computePageCount);
    @Nullable
    private ExcavationLogEntry entry;
    @Nullable
    private ResourceLocation tableId;
    // 缓存：以 expectedLoot 为基准构建的合并展示列表
    private List<LootDisplayEntry> cachedLoot = List.of();
    // 战利品整体进度统计（角标/进度条用）
    private int totalLootCount;
    private int obtainedLootCount;
    private final List<IconSlot> renderTooltipSlots = new ArrayList<>();
    // 复制坐标按钮命中盒与悬停状态（缓存最近一帧，用于点击判定与 tooltip）
    @Nullable
    private Bounds copyBtnBounds = Bounds.EMPTY;
    private boolean copyBtnHovered;
    // 返回列表按钮（IconButton widget，由外部 screen 注册）
    @Nullable
    private IconButton backButton;
    // 元信息行悬停追踪：用于文字超宽滚动
    private int hoveredMetaRow = -1;
    private final int[] metaRowScrollTicks = new int[JournalLayout.LOG_DETAIL_META_ROWS];

    public LogDetailPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void setEntry(@Nullable ExcavationLogEntry entry, @Nullable ResourceLocation tableId) {
        this.entry = entry;
        this.tableId = tableId;
        this.cachedLoot = entry != null ? buildLootDisplay(entry) : List.of();
        this.totalLootCount = this.cachedLoot.size();
        this.obtainedLootCount = 0;
        for (LootDisplayEntry loot : this.cachedLoot) {
            if (loot.isFullyObtained()) {
                this.obtainedLootCount++;
            }
        }
        this.renderTooltipSlots.clear();
        this.copyBtnBounds = Bounds.EMPTY;
        this.copyBtnHovered = false;
        this.hoveredMetaRow = -1;
        Arrays.fill(this.metaRowScrollTicks, 0);
        this.pagination.reset();
    }

    // 创建并注册返回列表按钮（IconButton），由外部 screen.rebuildWidgets 调用
    // registrar：将 widget 注册到 screen 的回调，避免 panel 包直接依赖 screen 包
    public void createBackButton(Consumer<IconButton> registrar, Runnable onBack) {
        int leftX = this.layout.rightPageX() + 8;
        int y = this.layout.rightPageY() + JournalLayout.LOG_TOP;
        Component tooltip = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_back_to_list");
        this.backButton = new IconButton(
                leftX, y,
                JournalLayout.LOG_DETAIL_BACK_BTN_SIZE,
                '←',
                tooltip,
                onBack);
        registrar.accept(this.backButton);
    }

    @Nullable
    public IconButton getBackButton() {
        return this.backButton;
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        this.renderTooltipSlots.clear();
        this.copyBtnHovered = false;

        int leftX = this.layout.rightPageX() + 8;
        int contentWidth = this.layout.rightPageWidth() - 20;
        // 返回按钮由 IconButton widget 渲染，这里仅占位
        int y = this.layout.rightPageY() + JournalLayout.LOG_TOP
                + JournalLayout.LOG_DETAIL_BACK_BTN_SIZE + 4;

        ExcavationLogEntry entry = this.entry;
        if (entry == null) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_empty"),
                    leftX, y, JournalLayout.LOG_DETAIL_UNKNOWN_COLOR, false);
            return;
        }

        // 1. Spacetime 卡片：时间/结构/群系/坐标
        y += renderSpacetimeCard(guiGraphics, font, leftX, y, contentWidth, entry, mouseX, mouseY)
                + JournalLayout.LOG_DETAIL_CARD_GAP;

        // 2. Loot 卡片：合并收集清单
        renderLootCard(guiGraphics, font, leftX, y, contentWidth);
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= this.layout.rightPageX() && mouseX <= this.layout.rightPageRight()
                && mouseY >= this.layout.rightPageY() && mouseY <= this.layout.rightPageBottom();
    }

    // 仅处理复制坐标按钮点击；返回按钮由 IconButton widget 自行处理
    public boolean handleClick(double mouseX, double mouseY) {
        if (this.copyBtnBounds != null && this.copyBtnBounds.contains(mouseX, mouseY) && this.entry != null) {
            CopyCoordinateButton.copyCommand(this.entry);
            return true;
        }
        return false;
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

    // 渲染返回按钮 tooltip + 复制坐标按钮 tooltip（由外部在 super.render 之后调用）
    public void renderTooltips(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        if (this.backButton != null && this.backButton.visible) {
            this.backButton.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        if (this.copyBtnHovered) {
            CopyCoordinateButton.renderTooltip(guiGraphics, font, mouseX, mouseY);
        }
    }

    // —— Spacetime 卡片：标签+值文字布局，6 行 ——
    private int renderSpacetimeCard(GuiGraphics g, Font font, int x, int y, int w,
                                    ExcavationLogEntry entry, int mouseX, int mouseY) {
        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int lineHeight = font.lineHeight;
        int lineGap = JournalLayout.LOG_DETAIL_META_LINE_GAP;
        int rows = JournalLayout.LOG_DETAIL_META_ROWS;
        int cardH = pad + lineHeight * rows + lineGap * (rows - 1) + pad;

        drawCardBackground(g, x, y, w, cardH);

        // 预解析各行标签与值
        Component createdLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_created_label");
        Component updatedLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_updated_label");
        Component biomeLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_biome_label");
        Component dimensionLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_dimension_label");
        Component coordsLabel = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_coords_label");

        // 结构信息三级降级：structure → feature(table) → unknown
        JournalFormatHelper.StructureInfo structInfo =
                JournalFormatHelper.formatStructureOrFeature(entry.structureId(), this.tableId);
        Component structLabel = structInfo.isFeature()
                ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_feature_label")
                : Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_structure_label");

        String createdValue = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_time_short",
                entry.createdGameTime(), entry.createdDayTime()).getString();
        String updatedValue = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_time_short",
                entry.lastUpdatedGameTime(), entry.lastUpdatedDayTime()).getString();
        String biomeValue = JournalFormatHelper.formatBiomeName(entry.biomeId());
        String dimValue = JournalFormatHelper.formatDimensionName(entry.dimensionId());
        var pos = entry.pos();
        String coordsValue = pos.getX() + " " + pos.getY() + " " + pos.getZ();

        // 动态计算标签列宽度：取所有标签的最大宽度，保证值列对齐
        int labelWidth = maxWidth(font, createdLabel, updatedLabel, structLabel, biomeLabel, dimensionLabel, coordsLabel);
        int valueX = x + pad + labelWidth + LABEL_GAP;
        int copyBtnW = CopyCoordinateButton.WIDTH;
        int copyBtnH = CopyCoordinateButton.HEIGHT;
        // 最后一行需为复制按钮预留空间
        int valueW = w - pad * 2 - labelWidth - LABEL_GAP;
        int lastRowValueW = valueW - copyBtnW - LABEL_GAP;

        int ty = y + pad;
        int rowFullWidth = w - pad * 2;

        // 重置悬停追踪（每帧重新检测）
        int prevHovered = this.hoveredMetaRow;
        this.hoveredMetaRow = -1;

        // 行1：创建时间
        ty += drawMetaRow(g, font, 0, createdLabel.getString(), createdValue,
                x + pad, ty, valueX, valueW, rowFullWidth,
                JournalLayout.LOG_DETAIL_VALUE_COLOR, mouseX, mouseY, prevHovered) + lineGap;

        // 行2：更新时间
        ty += drawMetaRow(g, font, 1, updatedLabel.getString(), updatedValue,
                x + pad, ty, valueX, valueW, rowFullWidth,
                JournalLayout.LOG_DETAIL_VALUE_COLOR, mouseX, mouseY, prevHovered) + lineGap;

        // 行3：结构/特征/未知（未知时降低视觉权重）
        int structColor = structInfo.isUnknown()
                ? JournalLayout.LOG_DETAIL_UNKNOWN_COLOR
                : JournalLayout.LOG_DETAIL_VALUE_COLOR;
        ty += drawMetaRow(g, font, 2, structLabel.getString(), structInfo.displayName(),
                x + pad, ty, valueX, valueW, rowFullWidth,
                structColor, mouseX, mouseY, prevHovered) + lineGap;

        // 行4：群系
        ty += drawMetaRow(g, font, 3, biomeLabel.getString(), biomeValue,
                x + pad, ty, valueX, valueW, rowFullWidth,
                JournalLayout.LOG_DETAIL_VALUE_COLOR, mouseX, mouseY, prevHovered) + lineGap;

        // 行5：维度
        ty += drawMetaRow(g, font, 4, dimensionLabel.getString(), dimValue,
                x + pad, ty, valueX, valueW, rowFullWidth,
                JournalLayout.LOG_DETAIL_VALUE_COLOR, mouseX, mouseY, prevHovered) + lineGap;

        // 行6：坐标 + 复制按钮（放在坐标行最右侧）
        drawMetaRow(g, font, 5, coordsLabel.getString(), coordsValue,
                x + pad, ty, valueX, lastRowValueW, rowFullWidth,
                JournalLayout.LOG_DETAIL_VALUE_COLOR, mouseX, mouseY, prevHovered);
        // 渲染复制按钮（竖直居中于行内）
        int btnX = valueX + lastRowValueW + LABEL_GAP;
        int btnY = ty;
        boolean btnHovered = CopyCoordinateButton.isHit(btnX, btnY, mouseX, mouseY);
        CopyCoordinateButton.render(g, btnX, btnY, btnHovered);
        this.copyBtnBounds = new Bounds(btnX, btnY, copyBtnW, copyBtnH);
        this.copyBtnHovered = btnHovered;

        return cardH;
    }

    // 绘制单行元信息：标签（固定宽度，次要色）+ 值（剩余宽度，主要色，超宽滚动）
    private int drawMetaRow(GuiGraphics g, Font font, int rowIndex, String label, String value,
                            int rowX, int rowY, int valueX, int valueW, int rowFullWidth,
                            int valueColor, int mouseX, int mouseY, int prevHovered) {
        int rowH = font.lineHeight;

        // 检测当前行是否被悬停（行高度 = lineHeight，但适当放宽垂直判定范围以提升体验）
        boolean hovered = mouseX >= rowX && mouseX < rowX + rowFullWidth
                && mouseY >= rowY - 1 && mouseY < rowY + rowH + 1;
        if (hovered) {
            this.hoveredMetaRow = rowIndex;
            if (prevHovered != rowIndex) {
                this.metaRowScrollTicks[rowIndex] = 0;
            }
            this.metaRowScrollTicks[rowIndex]++;
        }

        // 标签：次要色，左对齐于标签列
        g.drawString(font, label, rowX, rowY, JournalLayout.LOG_DETAIL_LABEL_COLOR, false);
        // 值：主要色，超宽时悬停滚动
        ScrollTextHelper.draw(g, font, value, valueX, rowY, valueW, valueColor,
                hovered, this.metaRowScrollTicks[rowIndex], false);
        return rowH;
    }

    // 计算多个 Component 的最大文字宽度
    private static int maxWidth(Font font, Component... components) {
        int max = 0;
        for (Component c : components) {
            max = Math.max(max, font.width(c));
        }
        return max;
    }

    // —— Loot 卡片：增强型图标网格（角标）+ 底部总进度条 ——
    private void renderLootCard(GuiGraphics g, Font font, int x, int y, int w) {
        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int lineHeight = font.lineHeight;
        int stride = ICON_SIZE + ICON_GAP;

        // 标题行：收集清单 [完成标志]
        Component title = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_collection");
        boolean allCompleted = isAllLootCompleted();
        int titleY = y + pad;

        // 底部进度条预留高度（进度条 + 间距 + 卡片底内边距）
        int progressBarH = JournalLayout.LOG_DETAIL_PROGRESS_BAR_HEIGHT;
        int progressGap = JournalLayout.LOG_DETAIL_PROGRESS_BAR_GAP;
        int bottomReserve = this.cachedLoot.isEmpty() ? 0 : (progressGap + progressBarH + pad);

        int pageBottom = this.layout.rightPageY() + this.layout.rightPageHeight() - 4;
        int availableHeight = pageBottom - titleY - bottomReserve;
        // 标题行高 + 标题与网格的额外间距
        int labelH = lineHeight + JournalLayout.LOG_DETAIL_TITLE_TO_GRID_GAP;
        int gridTop = titleY + labelH;
        int gridAvailable = Math.max(0, availableHeight - labelH);

        int iconsPerRow = Math.max(1, (w - pad * 2 + ICON_GAP) / stride);
        int rowsPerPage = Math.max(1, gridAvailable / stride);
        int iconsPerPage = Math.max(1, iconsPerRow * rowsPerPage);

        int page = this.pagination.getPage();
        int from = Math.min(this.cachedLoot.size(), page * iconsPerPage);
        int to = Math.min(this.cachedLoot.size(), from + iconsPerPage);

        // 计算卡片总高度（用于绘制卡片背景）
        int gridHeight = this.cachedLoot.isEmpty() ? 0 : gridAvailable;
        int cardH = pad + labelH + gridHeight + bottomReserve;

        // 绘制卡片背景，明确区域边界
        drawCardBackground(g, x, y, w, cardH);

        // 绘制标题
        g.drawString(font, title, x + pad, titleY, JournalLayout.LOG_DETAIL_LABEL_COLOR, false);
        if (allCompleted) {
            Component badge = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_completed");
            int badgeX = x + pad + font.width(title) + 6;
            g.drawString(font, badge, badgeX, titleY, JournalLayout.LOG_DETAIL_COMPLETION_COLOR, false);
        }

        if (this.cachedLoot.isEmpty()) {
            g.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_loot_empty"),
                    x + pad, gridTop, JournalLayout.LOG_DETAIL_UNKNOWN_COLOR, false);
            return;
        }

        // 渲染图标网格
        int gridX = x + pad;
        for (int i = from; i < to; i++) {
            LootDisplayEntry loot = this.cachedLoot.get(i);
            int row = (i - from) / iconsPerRow;
            int col = (i - from) % iconsPerRow;
            int iconX = gridX + col * stride;
            int iconY = gridTop + row * stride;

            // 不再使用原版数量角标，所有角标改为自定义绘制
            ItemStack stack = loot.stack().copy();

            float scale = (float) ICON_SIZE / 16;
            g.pose().pushPose();
            g.pose().translate(iconX, iconY, 0);
            g.pose().scale(scale, scale, 1.0f);
            g.renderItem(stack, 0, 0);
            g.pose().popPose();
            // 刷新物品渲染缓冲，确保后续角标/遮罩绘制在物品之上
            g.flush();

            int actual = loot.actualCount();
            int expected = loot.expectedCount();
            if (actual == 0) {
                // 状态(1)：已解析 未获得——半透明遮罩呈现幽灵虚影，不显示数量角标
                g.fill(iconX, iconY, iconX + ICON_SIZE, iconY + ICON_SIZE,
                        JournalLayout.LOG_DETAIL_GHOST_OVERLAY);
            } else if (loot.isFullyObtained()) {
                // 状态(3)：已解析 全部获得——仅显示期望总数，绿色
                drawBadge(g, font, iconX, iconY, String.valueOf(expected),
                        JournalLayout.LOG_DETAIL_LOOT_BADGE_FULL_COLOR);
            } else {
                // 状态(2)：已解析 未全部获得——显示 n/m，棕黄色
                drawBadge(g, font, iconX, iconY, actual + "/" + expected,
                        JournalLayout.LOG_DETAIL_LOOT_BADGE_PARTIAL_COLOR);
            }
            this.renderTooltipSlots.add(new IconSlot(iconX, iconY, stack.copy()));
        }

        // 底部进度条 + 文字（文字直接渲染在进度条本体上）
        int barWidth = w - pad * 2;
        int barX = x + pad;
        int barY = pageBottom - progressBarH - pad;
        drawProgressBar(g, font, barX, barY, barWidth, this.obtainedLootCount, this.totalLootCount);
    }

    // 绘制物品角标（右下角，z=200 确保位于物品图标图层之上）
    private static void drawBadge(GuiGraphics g, Font font, int iconX, int iconY, String text, int color) {
        int badgeX = iconX + ICON_SIZE - font.width(text);
        int badgeY = iconY + ICON_SIZE - font.lineHeight;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        // 黑色描边（八向偏移）提升任何背景下的可读性
        g.drawString(font, text, badgeX - 1, badgeY, 0xFF000000, false);
        g.drawString(font, text, badgeX + 1, badgeY, 0xFF000000, false);
        g.drawString(font, text, badgeX, badgeY - 1, 0xFF000000, false);
        g.drawString(font, text, badgeX, badgeY + 1, 0xFF000000, false);
        g.drawString(font, text, badgeX - 1, badgeY - 1, 0xFF000000, false);
        g.drawString(font, text, badgeX + 1, badgeY - 1, 0xFF000000, false);
        g.drawString(font, text, badgeX - 1, badgeY + 1, 0xFF000000, false);
        g.drawString(font, text, badgeX + 1, badgeY + 1, 0xFF000000, false);
        // 主文字
        g.drawString(font, text, badgeX, badgeY, color, false);
        g.pose().popPose();
    }

    // 绘制底部总进度条，"Collected X/Y" 文字直接渲染在进度条本体上（居中，白色带阴影）
    private static void drawProgressBar(GuiGraphics g, Font font, int x, int y, int width,
                                        int obtained, int total) {
        if (total <= 0) {
            return;
        }
        int barH = JournalLayout.LOG_DETAIL_PROGRESS_BAR_HEIGHT;
        boolean allDone = obtained >= total;

        // 进度条底色（不透明，清晰可见）
        g.fill(x, y, x + width, y + barH, JournalLayout.LOG_DETAIL_PROGRESS_BAR_BG);
        // 进度条前景（完成时用绿色，否则暖棕）
        int fillWidth = (int) ((long) width * obtained / total);
        int fillColor = allDone
                ? JournalLayout.LOG_DETAIL_COMPLETION_COLOR
                : JournalLayout.LOG_DETAIL_PROGRESS_BAR_FILL;
        if (fillWidth > 0) {
            g.fill(x, y, x + fillWidth, y + barH, fillColor);
        }

        // "Collected X/Y" 文字居中渲染在进度条本体上
        Component progressText = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_loot_progress", obtained, total);
        int textWidth = font.width(progressText);
        int textX = x + (width - textWidth) / 2;
        int textY = y + (barH - font.lineHeight + 1) / 2;
        g.drawString(font, progressText, textX, textY,
                JournalLayout.LOG_DETAIL_PROGRESS_BAR_TEXT, true);
    }

    // —— 战利品构建 ——
    private List<LootDisplayEntry> buildLootDisplay(ExcavationLogEntry entry) {
        List<LootDisplayEntry> result = new ArrayList<>();
        Map<String, Integer> expected = entry.expectedLoot();
        Map<String, Integer> actual = entry.actualLoot();
        for (Map.Entry<String, Integer> e : JournalFormatHelper.sortedLootEntries(expected)) {
            String key = e.getKey();
            int expectedCount = e.getValue();
            int actualCount = actual.getOrDefault(key, 0);
            ItemStack stack = JournalFormatHelper.createLootStack(key, expectedCount);
            if (stack != null && !stack.isEmpty()) {
                result.add(new LootDisplayEntry(key, expectedCount, actualCount, stack));
            }
        }
        return result;
    }

    private boolean isAllLootCompleted() {
        if (this.cachedLoot.isEmpty()) {
            return false;
        }
        for (LootDisplayEntry loot : this.cachedLoot) {
            if (!loot.isFullyObtained()) {
                return false;
            }
        }
        return true;
    }

    // —— 分页计算 ——
    private int computePageCount() {
        if (this.entry == null || this.cachedLoot.isEmpty()) {
            return 1;
        }
        Font font = Minecraft.getInstance().font;
        int lineHeight = font.lineHeight;
        int contentWidth = this.layout.rightPageWidth() - 20;

        // 固定内容高度（与 render 中一致）
        int fixedHeight = getFixedHeight(lineHeight);

        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int gridTop = this.layout.rightPageY() + JournalLayout.LOG_TOP + fixedHeight;
        int pageBottom = this.layout.rightPageY() + this.layout.rightPageHeight() - 4;
        int progressBarH = JournalLayout.LOG_DETAIL_PROGRESS_BAR_HEIGHT;
        int progressGap = JournalLayout.LOG_DETAIL_PROGRESS_BAR_GAP;
        int bottomReserve = progressGap + progressBarH + pad;
        int gridAvailable = Math.max(0, (pageBottom - bottomReserve) - gridTop);
        if (gridAvailable <= 0) {
            return 1;
        }

        int stride = ICON_SIZE + ICON_GAP;
        int iconsPerRow = Math.max(1, (contentWidth - pad * 2 + ICON_GAP) / stride);
        int rowsPerPage = Math.max(1, gridAvailable / stride);
        int iconsPerPage = Math.max(1, iconsPerRow * rowsPerPage);
        return Math.max(1, (this.cachedLoot.size() + iconsPerPage - 1) / iconsPerPage);
    }

    private static int getFixedHeight(int lineHeight) {
        int backHeight = JournalLayout.LOG_DETAIL_BACK_BTN_SIZE + 4;
        // Spacetime 卡片：6 行文字（行高 = lineHeight + 行间距）
        int spacetimeCardH = JournalLayout.LOG_DETAIL_CARD_PAD * 2
                + lineHeight * JournalLayout.LOG_DETAIL_META_ROWS
                + JournalLayout.LOG_DETAIL_META_LINE_GAP * (JournalLayout.LOG_DETAIL_META_ROWS - 1);
        // 标题行高 + 标题与网格的额外间距
        int labelH = lineHeight + JournalLayout.LOG_DETAIL_TITLE_TO_GRID_GAP;
        int progressBarH = JournalLayout.LOG_DETAIL_PROGRESS_BAR_HEIGHT;
        int progressGap = JournalLayout.LOG_DETAIL_PROGRESS_BAR_GAP;
        int bottomReserve = progressGap + progressBarH + JournalLayout.LOG_DETAIL_CARD_PAD;
        return backHeight + spacetimeCardH + JournalLayout.LOG_DETAIL_CARD_GAP
                + JournalLayout.LOG_DETAIL_CARD_PAD + labelH + bottomReserve;
    }

    // —— 卡片背景 ——
    private static void drawCardBackground(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, JournalLayout.LOG_DETAIL_CARD_BG);
        // 顶部 1px 暖色边框
        g.fill(x, y, x + w, y + 1, JournalLayout.LOG_DETAIL_CARD_BORDER);
    }

    private record LootDisplayEntry(String signatureKey, int expectedCount, int actualCount, ItemStack stack) {
        boolean isFullyObtained() {
            return actualCount >= expectedCount;
        }
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
