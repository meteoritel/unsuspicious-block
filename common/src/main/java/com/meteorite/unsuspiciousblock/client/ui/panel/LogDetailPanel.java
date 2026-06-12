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

/** 日志详情面板——卡片式信息分组、合并战利品收集清单 */
public final class LogDetailPanel implements PagePanel {
    private static final int LABEL_COLOR = 0x5A422C;
    private static final int TEXT_COLOR = 0x4A3320;
    private static final int MUTED_COLOR = 0x7A6247;
    private static final int ICON_SIZE = 16;
    private static final int ICON_GAP = 2;

    private final JournalBookBackground.BookLayout layout;
    private final PaginationState pagination = new PaginationState(this::computePageCount);
    @Nullable
    private ExcavationLogEntry entry;
    // 缓存：以 expectedLoot 为基准构建的合并展示列表
    private List<LootDisplayEntry> cachedLoot = List.of();
    private final List<IconSlot> renderTooltipSlots = new ArrayList<>();
    @Nullable
    private Bounds backBounds = Bounds.EMPTY;

    public LogDetailPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
    }

    public void setEntry(@Nullable ExcavationLogEntry entry) {
        this.entry = entry;
        this.cachedLoot = entry != null ? buildLootDisplay(entry) : List.of();
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

    // —— Spacetime 卡片 ——
    private int renderSpacetimeCard(GuiGraphics g, Font font, int x, int y, int w, ExcavationLogEntry entry) {
        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int lineHeight = font.lineHeight;
        // 4 行：创建/更新时间、结构、群系、坐标
        int cardH = pad + lineHeight + 2 + lineHeight + 2 + lineHeight + 2 + lineHeight + pad;

        drawCardBackground(g, x, y, w, cardH);

        int tx = x + pad;
        int ty = y + pad;
        int tw = w - pad * 2;

        // 行1：创建时间 · 更新时间
        Component created = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_created_time_value",
                entry.createdGameTime(), entry.createdDayTime());
        Component updated = JournalFormatHelper.formatGameTime(
                "screen.unsuspiciousblock.archaeology_journal.log_updated_time_value",
                entry.lastUpdatedGameTime(), entry.lastUpdatedDayTime());
        String timeLine = created.getString() + " \u00B7 " + updated.getString();
        ty += renderWrappedText(g, font, Component.literal(timeLine), tx, ty, tw, 1, TEXT_COLOR) + 2;

        // 行2：结构
        ty += renderWrappedText(g, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_structure_value",
                        JournalFormatHelper.formatStructureName(entry.structureId())),
                tx, ty, tw, 1, TEXT_COLOR) + 2;

        // 行3：群系
        ty += renderWrappedText(g, font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_biome_value",
                        JournalFormatHelper.formatBiomeName(entry.biomeId())),
                tx, ty, tw, 1, TEXT_COLOR) + 2;

        // 行4：坐标
        var pos = entry.pos();
        g.drawString(font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_position_value",
                        pos.getX(), pos.getY(), pos.getZ()),
                tx, ty, TEXT_COLOR, false);

        return cardH;
    }

    // —— Loot 卡片 ——
    private void renderLootCard(GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY) {
        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int lineHeight = font.lineHeight;

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
        int availableHeight = (this.layout.rightPageY() + this.layout.rightPageHeight()) - gridTop - 4;
        if (availableHeight <= 0) {
            return;
        }

        int stride = ICON_SIZE + ICON_GAP;
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
                // 已获得：加入 tooltip
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
        int sourceCardH = JournalLayout.LOG_DETAIL_CARD_PAD * 2 + Math.max(lineHeight, ICON_SIZE);
        int spacetimeCardH = JournalLayout.LOG_DETAIL_CARD_PAD * 2 + lineHeight * 4 + 2 * 3;
        int labelH = lineHeight + 2;
        int fixedHeight = backHeight + 4
                + sourceCardH + JournalLayout.LOG_DETAIL_CARD_GAP
                + spacetimeCardH + JournalLayout.LOG_DETAIL_CARD_GAP
                + labelH;

        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int gridTop = this.layout.rightPageY() + JournalLayout.LOG_TOP + fixedHeight;
        int availableHeight = (this.layout.rightPageY() + this.layout.rightPageHeight()) - gridTop - 4;
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
