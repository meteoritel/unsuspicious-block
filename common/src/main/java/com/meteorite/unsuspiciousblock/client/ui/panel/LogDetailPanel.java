package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.helper.JournalFormatHelper;
import com.meteorite.unsuspiciousblock.client.ui.helper.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.PaginationState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 日志详情面板——图标键值对元信息卡片、增强型战利品收集清单（角标+总进度条） */
public final class LogDetailPanel implements PagePanel {
    private static final int LABEL_COLOR = 0x5A422C;
    private static final int TEXT_COLOR = 0x4A3320;
    private static final int MUTED_COLOR = 0x7A6247;
    private static final int ICON_SIZE = 16;
    private static final int ICON_GAP = 2;

    // Spacetime 卡片引导图标（原版物品，贴合古卷主题，避免 Unicode 兼容问题）
    // 懒加载：避免类静态初始化时注册表未就绪
    private static ItemStack clockIcon() {
        return iconOf("clock");
    }
    private static ItemStack compassIcon() {
        return iconOf("compass");
    }
    private static ItemStack saplingIcon() {
        return iconOf("oak_sapling");
    }
    private static ItemStack eyeIcon() {
        return iconOf("ender_eye");
    }

    // 通过注册表解析原版物品图标，失败时返回 EMPTY
    private static ItemStack iconOf(String path) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(ResourceLocation.withDefaultNamespace(path));
        return new ItemStack(item);
    }

    private final JournalBookBackground.BookLayout layout;
    private final PaginationState pagination = new PaginationState(this::computePageCount);
    @Nullable
    private ExcavationLogEntry entry;
    // 缓存：以 expectedLoot 为基准构建的合并展示列表
    private List<LootDisplayEntry> cachedLoot = List.of();
    // 战利品整体进度统计（角标/进度条用）
    private int totalLootCount;
    private int obtainedLootCount;
    private final List<IconSlot> renderTooltipSlots = new ArrayList<>();
    @Nullable
    private Bounds backBounds = Bounds.EMPTY;

    public LogDetailPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void setEntry(@Nullable ExcavationLogEntry entry) {
        this.entry = entry;
        this.cachedLoot = entry != null ? buildLootDisplay(entry) : List.of();
        this.totalLootCount = this.cachedLoot.size();
        this.obtainedLootCount = 0;
        for (LootDisplayEntry loot : this.cachedLoot) {
            if (loot.isFullyObtained()) {
                this.obtainedLootCount++;
            }
        }
        this.renderTooltipSlots.clear();
        this.backBounds = Bounds.EMPTY;
        this.pagination.reset();
    }

    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        this.renderTooltipSlots.clear();

        int leftX = this.layout.rightPageX() + 8;
        int contentWidth = this.layout.rightPageWidth() - 20;
        int y = this.layout.rightPageY() + JournalLayout.LOG_TOP;

        // 1. 返回按钮（带暖色背景）
        y += renderBackButton(guiGraphics, font, leftX, y, mouseX, mouseY) + 4;

        ExcavationLogEntry entry = this.entry;
        if (entry == null) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_empty"),
                    leftX, y, MUTED_COLOR, false);
            return;
        }

        // 2. Source 卡片：来源方块 + 触发方式（合并一行）
        y += renderSourceCard(guiGraphics, font, leftX, y, contentWidth, entry)
                + JournalLayout.LOG_DETAIL_CARD_GAP;

        // 3. Spacetime 卡片：时间/结构/群系/坐标
        y += renderSpacetimeCard(guiGraphics, font, leftX, y, contentWidth, entry)
                + JournalLayout.LOG_DETAIL_CARD_GAP;

        // 4. Loot 卡片：合并收集清单
        renderLootCard(guiGraphics, font, leftX, y, contentWidth, mouseX, mouseY);
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

    // —— 返回按钮 ——
    private int renderBackButton(GuiGraphics g, Font font, int x, int y, int mouseX, int mouseY) {
        Component text = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_back_to_list");
        int padX = JournalLayout.LOG_DETAIL_BACK_BTN_PAD_X;
        int padY = JournalLayout.LOG_DETAIL_BACK_BTN_PAD_Y;
        int w = font.width(text) + padX * 2;
        int h = font.lineHeight + padY * 2;

        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        int bgColor = hovered ? 0x30A08060 : JournalLayout.LOG_DETAIL_CARD_BG;
        g.fill(x, y, x + w, y + h, bgColor);
        // 底部暖色边线
        g.fill(x, y + h - 1, x + w, y + h, JournalLayout.LOG_DETAIL_CARD_BORDER);
        g.drawString(font, text, x + padX, y + padY,
                hovered ? LABEL_COLOR : MUTED_COLOR, false);

        this.backBounds = new Bounds(x, y, w, h);
        return h;
    }

    // —— Source 卡片 ——
    private int renderSourceCard(GuiGraphics g, Font font, int x, int y, int w, ExcavationLogEntry entry) {
        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        ItemStack sourceStack = entry.lootSource() != null ? entry.lootSource().iconItem() : ItemStack.EMPTY;
        int innerHeight = Math.max(font.lineHeight, sourceStack.isEmpty() ? 0 : ICON_SIZE);
        int cardH = pad + innerHeight + pad;

        drawCardBackground(g, x, y, w, cardH);

        int contentY = y + pad;
        int textX = x + pad;
        int textWidth = w - pad * 2;

        if (!sourceStack.isEmpty()) {
            g.renderItem(sourceStack, x + pad, contentY);
            g.renderItemDecorations(font, sourceStack, x + pad, contentY);
            this.renderTooltipSlots.add(new IconSlot(x + pad, contentY, sourceStack.copy()));
            textX += ICON_SIZE + 4;
            textWidth -= ICON_SIZE + 4;
        }

        // 战利品来源名称
        String lootSourceName = JournalFormatHelper.formatLootSource(entry.lootSource()).getString();
        int lineH = renderWrappedText(g, font, Component.literal(lootSourceName),
                textX, contentY + (innerHeight - font.lineHeight) / 2, textWidth, 1, TEXT_COLOR);

        return cardH;
    }

    // —— Spacetime 卡片：图标键值对，4 行结构化布局 ——
    private int renderSpacetimeCard(GuiGraphics g, Font font, int x, int y, int w, ExcavationLogEntry entry) {
        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int lineHeight = font.lineHeight;
        int metaIconSize = JournalLayout.LOG_DETAIL_META_ICON_SIZE;
        int lineGap = JournalLayout.LOG_DETAIL_META_LINE_GAP;
        int rowHeight = metaIconSize;
        // 4 行：时间 / 结构 / 群系 / 维度·坐标
        int cardH = pad + rowHeight * 4 + lineGap * 3 + pad;

        drawCardBackground(g, x, y, w, cardH);

        int ix = x + pad;                  // 图标 X
        int tx = x + pad + metaIconSize + JournalLayout.LOG_DETAIL_META_ICON_GAP; // 文字 X
        int tw = w - pad * 2 - metaIconSize - JournalLayout.LOG_DETAIL_META_ICON_GAP;
        int ty = y + pad + (rowHeight - lineHeight) / 2; // 文字基线 Y（图标竖直居中）

        // 行1：clock 图标 + 创建→更新时间范围
        String createdTime = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_created_time_value",
                entry.createdGameTime(), entry.createdDayTime()).getString();
        String updatedTime = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_updated_time_value",
                entry.lastUpdatedGameTime(), entry.lastUpdatedDayTime()).getString();
        boolean sameTime = entry.createdGameTime() == entry.lastUpdatedGameTime()
                && entry.createdDayTime() == entry.lastUpdatedDayTime();
        String timeLine = sameTime ? createdTime : createdTime + " → " + updatedTime;
        drawMetaRow(g, font, clockIcon(), ix, ty, metaIconSize, timeLine, tx, tw, TEXT_COLOR);
        ty += rowHeight + lineGap;

        // 行2：compass 图标 + 结构（标签 + 值）
        ty += drawLabelValueRow(g, font, compassIcon(), ix, ty, metaIconSize,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_structure_value",
                        JournalFormatHelper.formatStructureName(entry.structureId())),
                tx, tw, rowHeight) + lineGap;

        // 行3：sapling 图标 + 群系（标签 + 值）
        ty += drawLabelValueRow(g, font, saplingIcon(), ix, ty, metaIconSize,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_biome_value",
                        JournalFormatHelper.formatBiomeName(entry.biomeId())),
                tx, tw, rowHeight) + lineGap;

        // 行4：ender_eye 图标 + 维度 · 坐标（合并一行）
        String dimensionText = JournalFormatHelper.formatDimensionName(entry.dimensionId());
        var pos = entry.pos();
        String dimPosLine = dimensionText + " · (" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
        drawMetaRow(g, font, eyeIcon(), ix, ty, metaIconSize, dimPosLine, tx, tw, TEXT_COLOR);

        return cardH;
    }

    // 绘制单行元信息：图标 + 纯文本（文字超宽滚动）
    private static void drawMetaRow(GuiGraphics g, Font font, ItemStack icon, int iconX, int textY,
                                    int iconSize, String text, int textX, int textWidth, int color) {
        if (!icon.isEmpty()) {
            // 图标相对文字基线竖直居中（iconSize=16 略大于 lineHeight≈9，向下偏移）
            int iconY = textY + (font.lineHeight - iconSize) / 2 + 1;
            g.renderItem(icon, iconX, iconY);
        }
        // 文字不滚动（详情卡片紧凑，超宽极少，按需截断足够）
        ScrollTextHelper.draw(g, font, text, textX, textY, textWidth, color, false, 0, false);
    }

    // 绘制标签+值行（标签用 MUTED，值用 TEXT 颜色层次）
    // 这里值本身是 i18n 模板（如 "结构：%s"），直接按 TEXT_COLOR 渲染，超宽滚动
    private static int drawLabelValueRow(GuiGraphics g, Font font, ItemStack icon, int iconX, int textY,
                                         int iconSize, Component text, int textX, int textWidth, int rowHeight) {
        if (!icon.isEmpty()) {
            int iconY = textY + (font.lineHeight - iconSize) / 2 + 1;
            g.renderItem(icon, iconX, iconY);
            g.renderItemDecorations(font, icon, iconX, iconY);
        }
        String plain = text.getString();
        ScrollTextHelper.draw(g, font, plain, textX, textY, textWidth, TEXT_COLOR, false, 0, false);
        return rowHeight;
    }

    // —— Loot 卡片：增强型图标网格（角标）+ 底部总进度条 ——
    private void renderLootCard(GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY) {
        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int lineHeight = font.lineHeight;
        int stride = ICON_SIZE + ICON_GAP;

        // 标题行：收集清单 [完成标志]
        Component title = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_collection");
        boolean allCompleted = isAllLootCompleted();
        int titleWidth = font.width(title);
        int titleY = y + pad;
        g.drawString(font, title, x + pad, titleY, LABEL_COLOR, false);

        if (allCompleted) {
            Component badge = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_completed");
            int badgeX = x + pad + titleWidth + 6;
            g.drawString(font, badge, badgeX, titleY, JournalLayout.LOG_DETAIL_COMPLETION_COLOR, false);
        }

        int labelH = lineHeight + 2;
        int gridTop = titleY + labelH;

        // 底部进度条预留高度（进度条 + 间距 + 文字行）
        int progressBarH = JournalLayout.LOG_DETAIL_PROGRESS_BAR_HEIGHT;
        int progressGap = JournalLayout.LOG_DETAIL_PROGRESS_BAR_GAP;
        int progressTextH = this.cachedLoot.isEmpty() ? 0 : lineHeight;
        int bottomReserve = this.cachedLoot.isEmpty() ? 0 : (progressTextH + progressGap + progressBarH + progressGap);

        int availableHeight = (this.layout.rightPageY() + this.layout.rightPageHeight()) - gridTop - 4 - bottomReserve;
        if (availableHeight <= 0) {
            return;
        }

        int iconsPerRow = Math.max(1, (w - pad * 2 + ICON_GAP) / stride);
        int rowsPerPage = Math.max(1, availableHeight / stride);
        int iconsPerPage = Math.max(1, iconsPerRow * rowsPerPage);

        int page = this.pagination.getPage();
        int from = Math.min(this.cachedLoot.size(), page * iconsPerPage);
        int to = Math.min(this.cachedLoot.size(), from + iconsPerPage);

        int gridX = x + pad;
        for (int i = from; i < to; i++) {
            LootDisplayEntry loot = this.cachedLoot.get(i);
            int row = (i - from) / iconsPerRow;
            int col = (i - from) % iconsPerRow;
            int iconX = gridX + col * stride;
            int iconY = gridTop + row * stride;

            ItemStack stack = loot.stack.copy();
            stack.setCount(loot.displayCount());

            // 渲染物品图标
            g.renderItem(stack, iconX, iconY);
            String countText = loot.displayCount() > 1 ? Integer.toString(loot.displayCount()) : null;
            g.renderItemDecorations(font, stack, iconX, iconY, countText);

            if (loot.isFullyObtained()) {
                // 完全获得：右上角绿色 ✓ 角标 + 加入 tooltip
                drawBadge(g, font, iconX, iconY, "✓", JournalLayout.LOG_DETAIL_COMPLETION_COLOR);
                this.renderTooltipSlots.add(new IconSlot(iconX, iconY, stack.copy()));
            } else if (loot.actualCount() > 0) {
                // 部分获得：右上角黄色 actual/expected 角标 + 加入 tooltip
                drawBadge(g, font, iconX, iconY,
                        loot.actualCount() + "/" + loot.expectedCount(),
                        JournalLayout.LOG_DETAIL_LOOT_BADGE_PARTIAL_COLOR);
                this.renderTooltipSlots.add(new IconSlot(iconX, iconY, stack.copy()));
            } else {
                // 未获得：覆盖 ghost 半透明遮罩
                g.fill(iconX, iconY, iconX + ICON_SIZE, iconY + ICON_SIZE,
                        JournalLayout.LOG_DETAIL_GHOST_OVERLAY);
            }
        }

        if (this.cachedLoot.isEmpty()) {
            g.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_loot_empty"),
                    gridX, gridTop, MUTED_COLOR, false);
            return;
        }

        // 底部进度条 + 文字（紧贴右页底部）
        int pageBottom = this.layout.rightPageY() + this.layout.rightPageHeight() - 4;
        int barY = pageBottom - progressBarH;
        int textY = barY - progressGap - lineHeight;
        int barWidth = w - pad * 2;
        int barX = x + pad;
        drawProgressBar(g, font, barX, barY, barWidth, textY, this.obtainedLootCount, this.totalLootCount);
    }

    // 绘制物品角标（右上角小字符，带深色描边背景便于辨识）
    private static void drawBadge(GuiGraphics g, Font font, int iconX, int iconY, String text, int color) {
        // 右上角偏移：图标16px，角标贴右上
        int badgeX = iconX + ICON_SIZE - font.width(text);
        int badgeY = iconY - 2;
        // 深色描边（四向偏移）提升浅色背景可读性
        g.drawString(font, text, badgeX - 1, badgeY, 0xFF000000, false);
        g.drawString(font, text, badgeX + 1, badgeY, 0xFF000000, false);
        g.drawString(font, text, badgeX, badgeY - 1, 0xFF000000, false);
        g.drawString(font, text, badgeX, badgeY + 1, 0xFF000000, false);
        g.drawString(font, text, badgeX, badgeY, color, false);
    }

    // 绘制底部总进度条 + "已收集 X/Y" 文本
    private static void drawProgressBar(GuiGraphics g, Font font, int x, int y, int width,
                                        int textY, int obtained, int total) {
        if (total <= 0) {
            return;
        }
        int barH = JournalLayout.LOG_DETAIL_PROGRESS_BAR_HEIGHT;
        // 进度文字（右对齐，颜色随完成度变化）
        Component progressText = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_loot_progress", obtained, total);
        boolean allDone = obtained >= total;
        int textColor = allDone ? JournalLayout.LOG_DETAIL_COMPLETION_COLOR : MUTED_COLOR;
        int textWidth = font.width(progressText);
        g.drawString(font, progressText, x + width - textWidth, textY, textColor, false);

        // 进度条底色
        g.fill(x, y, x + width, y + barH, JournalLayout.LOG_DETAIL_PROGRESS_BAR_BG);
        // 进度条前景（完成时用绿色，否则暖棕）
        int fillWidth = (int) ((long) width * obtained / total);
        int fillColor = allDone ? JournalLayout.LOG_DETAIL_COMPLETION_COLOR : JournalLayout.LOG_DETAIL_PROGRESS_BAR_FILL;
        if (fillWidth > 0) {
            g.fill(x, y, x + fillWidth, y + barH, fillColor);
        }
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
        int backHeight = lineHeight + JournalLayout.LOG_DETAIL_BACK_BTN_PAD_Y * 2;
        int sourceCardH = JournalLayout.LOG_DETAIL_CARD_PAD * 2 + ICON_SIZE;
        // Spacetime 卡片：4 行图标键值对（行高=max(图标16, lineHeight) + 行间距）
        int metaRowH = JournalLayout.LOG_DETAIL_META_ICON_SIZE;
        int spacetimeCardH = JournalLayout.LOG_DETAIL_CARD_PAD * 2
                + metaRowH * JournalLayout.LOG_DETAIL_META_ROWS
                + JournalLayout.LOG_DETAIL_META_LINE_GAP * (JournalLayout.LOG_DETAIL_META_ROWS - 1);
        int labelH = lineHeight + 2;
        int fixedHeight = backHeight + 4
                + sourceCardH + JournalLayout.LOG_DETAIL_CARD_GAP
                + spacetimeCardH + JournalLayout.LOG_DETAIL_CARD_GAP
                + labelH;

        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int gridTop = this.layout.rightPageY() + JournalLayout.LOG_TOP + fixedHeight;
        // 底部进度条预留（与 render 一致）
        int progressBarH = JournalLayout.LOG_DETAIL_PROGRESS_BAR_HEIGHT;
        int progressGap = JournalLayout.LOG_DETAIL_PROGRESS_BAR_GAP;
        int bottomReserve = lineHeight + progressGap + progressBarH + progressGap;
        int availableHeight = (this.layout.rightPageY() + this.layout.rightPageHeight()) - gridTop - 4 - bottomReserve;
        if (availableHeight <= 0) {
            return 1;
        }

        int stride = ICON_SIZE + ICON_GAP;
        int iconsPerRow = Math.max(1, (contentWidth - pad * 2 + ICON_GAP) / stride);
        int rowsPerPage = Math.max(1, availableHeight / stride);
        int iconsPerPage = Math.max(1, iconsPerRow * rowsPerPage);
        return Math.max(1, (this.cachedLoot.size() + iconsPerPage - 1) / iconsPerPage);
    }

    // —— 卡片背景 ——
    private static void drawCardBackground(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, JournalLayout.LOG_DETAIL_CARD_BG);
        // 顶部 1px 暖色边框
        g.fill(x, y, x + w, y + 1, JournalLayout.LOG_DETAIL_CARD_BORDER);
    }

    // —— 文字折行 ——
    private static int renderWrappedText(GuiGraphics g, Font font, Component text,
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
            g.drawString(font, lines.get(i), x, y + i * font.lineHeight, color, false);
        }
        return lineCount * font.lineHeight;
    }

    private record LootDisplayEntry(String signatureKey, int expectedCount, int actualCount, ItemStack stack) {
        boolean isFullyObtained() {
            return actualCount >= expectedCount;
        }

        int displayCount() {
            return Math.max(1, Math.min(expectedCount, stack.getMaxStackSize()));
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
