package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.render.UiRenderLayers;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalFormatHelper;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.meteosuikit.api.control.UiPaginationState;
import com.meteorite.unsuspiciousblock.client.ui.widget.CopyCoordinateButton;
import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputContainer;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.layout.UiGridMetrics;
import com.meteorite.meteosuikit.api.layout.UiLayoutNode;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalControls;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.mojang.blaze3d.systems.RenderSystem;
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
import java.util.Objects;
import java.util.function.Consumer;

/** 日志详情面板——标签值元信息卡片、增强型战利品收集清单（角标+总进度条） */
public final class LogDetailPanel implements PagePanel, UiInputContainer {
    private final UiControl copyButton = new UiControl();
    private final UiChildren<UiControl> contentControls = new UiChildren<>();
    private boolean contentDirty = true;
    private int contentLineHeight;

    @Override
    public UiChildren<UiControl> inputChildren() {
        return contentControls;
    }

    @Override
    public void prepareUi() {
        Font font = Minecraft.getInstance().font;
        if (!contentDirty && contentLineHeight == font.lineHeight) {
            return;
        }
        contentDirty = false;
        contentLineHeight = font.lineHeight;
        tooltipSlots.clear();
        copyButton.setVisible(entry != null);
        if (entry == null) {
            return;
        }
        copyButton.setLayoutKey("log/coordinates/" + entry.entryId());
        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int width = layout.rightPageWidth() - 20;
        copyButton.setBounds(layout.rightPageX() + 8 + width - pad - CopyCoordinateButton.WIDTH,
                spacetimeTop() + pad + (JournalLayout.LOG_DETAIL_META_ROWS - 1)
                        * (font.lineHeight + JournalLayout.LOG_DETAIL_META_LINE_GAP),
                CopyCoordinateButton.WIDTH, CopyCoordinateButton.HEIGHT);
        CopyCoordinateButton.configure(copyButton, entry);
        LootGrid grid = lootGrid(font.lineHeight, width);
        int from = Math.min(cachedLoot.size(), pagination.getPage() * grid.pageSize());
        int to = Math.min(cachedLoot.size(), from + grid.pageSize());
        for (int i = from; i < to; i++) {
            tooltipSlots.add(new IconSlot(grid.cellBounds(i - from), cachedLoot.get(i)));
        }
    }

    @Override
    public UiPointerHit hitTest(double x, double y) {
        UiPointerHit hit = contentControls.hitTest(x, y);
        if (hit != null) {
            return hit.within(this);
        }
        IconSlot slot = findTooltipSlot(x, y);
        if (slot == null) {
            return null;
        }
        ItemStack stack = slot.entry().stack();
        return UiPointerHit.leaf(this, x, y)
                .withRow(this, 0, stack, slot.bounds())
                .withPresentation((g, font, px, py) -> g.renderTooltip(font, stack, px, py));
    }

    /** 战利品布局复用实际悬停槽位，和输入命中保持同一几何。 */
    @Override
    public List<UiLayoutNode> layoutNodes() {
        List<UiLayoutNode> nodes = new ArrayList<>(contentControls.layoutNodes());
        if (entry == null) {
            return nodes;
        }
        for (IconSlot slot : tooltipSlots) {
            nodes.add(new UiLayoutNode(UiLayoutNode.Kind.ROW, slot.bounds(), slot.entry().stack().getHoverName(),
                    0, 0, true, true, List.of()).identified(
                    "log/loot/" + entry.entryId() + "/" + slot.entry().signatureKey(), this));
        }
        return nodes;
    }
    public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) { copyButton.render(graphics, font, pointer); }

    private static final int ICON_SIZE = 20;
    private static final int ICON_GAP = 2;
    private static final int LABEL_GAP = JournalLayout.LOG_DETAIL_META_LABEL_GAP;
    private static final int LOOT_PROGRESS_RESERVE = JournalLayout.LOG_DETAIL_PROGRESS_BAR_GAP
            + JournalLayout.LOG_DETAIL_PROGRESS_BAR_HEIGHT + JournalLayout.LOG_DETAIL_CARD_PAD;

    private JournalBookBackground.BookLayout layout;
    private final UiPaginationState pagination = new UiPaginationState(this::computePageCount);
    @Nullable
    private ExcavationLogEntry entry;
    @Nullable
    private ResourceLocation tableId;
    // 缓存：以 expectedLoot 为基准构建的合并展示列表
    private List<LootDisplayEntry> cachedLoot = List.of();
    // 战利品整体进度统计（角标/进度条用）
    private int obtainedLootCount;
    private final List<IconSlot> tooltipSlots = new ArrayList<>();
    // 返回列表按钮（UiControl widget，由外部 screen 注册）
    @Nullable
    private UiControl backButton;
    // 备注编辑按钮（UiControl widget，由外部 screen 注册）
    @Nullable
    private UiControl noteButton;
    // 删除当前条目按钮（UiControl widget，由外部 screen 注册）
    @Nullable
    private UiControl deleteButton;
    // 元信息行悬停追踪：用于文字超宽滚动
    private int hoveredMetaRow = -1;
    private final int[] metaRowScrollTicks = new int[JournalLayout.LOG_DETAIL_META_ROWS];

    public void applyLayout(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        contentDirty = true;
    }

    public LogDetailPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        copyButton.setFocusable(true);
        contentControls.add(copyButton);
    }

    public void setEntry(@Nullable ExcavationLogEntry entry, @Nullable ResourceLocation tableId) {
        if (Objects.equals(this.tableId, tableId) && Objects.equals(this.entry, entry)) return;
        boolean sameEntry = this.entry != null && entry != null && Objects.equals(this.tableId, tableId)
                && this.entry.entryId().equals(entry.entryId());
        this.entry = entry;
        this.tableId = tableId;
        this.cachedLoot = entry != null ? buildLootDisplay(entry) : List.of();
        this.obtainedLootCount = 0;
        for (LootDisplayEntry loot : this.cachedLoot) {
            if (loot.isFullyObtained()) {
                this.obtainedLootCount++;
            }
        }
        contentDirty = true;
        this.hoveredMetaRow = -1;
        Arrays.fill(this.metaRowScrollTicks, 0);
        if (sameEntry) this.pagination.pageCount();
        else this.pagination.reset();
    }

    // 创建并注册返回列表按钮（kit 控件），由外部 screen.rebuildWidgets 调用
    // registrar：将 widget 注册到 screen 的回调，避免 panel 包直接依赖 screen 包
    public void createBackButton(Consumer<UiControl> registrar, Runnable onBack) {
        int leftX = this.layout.rightPageX() + 8;
        int y = this.layout.rightPageY() + JournalLayout.LOG_TOP;
        Component tooltip = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_back_to_list");
        this.backButton = JournalControls.iconButton(
                leftX, y,
                JournalLayout.LOG_DETAIL_BACK_BTN_SIZE,
                UiAtlas.Icon.BACK,
                tooltip,
                onBack);
        this.backButton.setLayoutKey("journal/log-back");
        registrar.accept(this.backButton);
    }

    // 创建并注册备注编辑按钮（kit 控件），紧邻返回按钮右侧
    public void createNoteButton(Consumer<UiControl> registrar, Runnable onNoteClick) {
        int leftX = this.layout.rightPageX() + 8;
        int y = this.layout.rightPageY() + JournalLayout.LOG_TOP;
        int btnSize = JournalLayout.LOG_DETAIL_BACK_BTN_SIZE;
        // 紧邻返回按钮右侧，间隔 2px
        int btnX = leftX + btnSize + 2;
        Component tooltip = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_note_button_tooltip");
        this.noteButton = JournalControls.iconButton(
                btnX, y,
                btnSize,
                UiAtlas.Icon.PENCIL,
                tooltip,
                onNoteClick);
        registrar.accept(this.noteButton);
    }

    // 创建并注册删除当前条目按钮，紧邻备注按钮右侧
    public void createDeleteButton(Consumer<UiControl> registrar, Runnable onDeleteClick) {
        int leftX = this.layout.rightPageX() + 8;
        int y = this.layout.rightPageY() + JournalLayout.LOG_TOP;
        int btnSize = JournalLayout.LOG_DETAIL_BACK_BTN_SIZE;
        int btnX = leftX + (btnSize + 2) * 2;
        this.deleteButton = JournalControls.iconButton(
                btnX, y, btnSize, UiAtlas.Icon.CLOSE,
                Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.log_delete.entry_tooltip"),
                onDeleteClick);
        registrar.accept(this.deleteButton);
    }

    @Nullable
    public UiControl getBackButton() {
        return this.backButton;
    }

    @Nullable
    public UiControl getNoteButton() {
        return this.noteButton;
    }

    @Nullable
    public UiControl getDeleteButton() {
        return this.deleteButton;
    }

    @Nullable
    public ExcavationLogEntry getEntry() {
        return this.entry;
    }

    @Nullable
    public ResourceLocation getTableId() {
        return this.tableId;
    }

    public void render(GuiGraphics guiGraphics, Font font, UiPointer pointer) {

        int leftX = this.layout.rightPageX() + 8;
        int contentWidth = this.layout.rightPageWidth() - 20;
        // 返回按钮由 UiControl widget 渲染，这里仅占位
        int y = spacetimeTop();

        ExcavationLogEntry entry = this.entry;
        if (entry == null) {
            guiGraphics.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_detail_empty"),
                    leftX, y, JournalLayout.LOG_DETAIL_UNKNOWN_COLOR, false);
            return;
        }

        // 1. Spacetime 卡片：时间/结构/群系/坐标
        y += renderSpacetimeCard(guiGraphics, font, leftX, y, contentWidth, entry, pointer)
                + JournalLayout.LOG_DETAIL_CARD_GAP;

        // 2. Loot 卡片：合并收集清单
        renderLootCard(guiGraphics, font, leftX, y, contentWidth);
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return PagePanel.containsPageBounds(mouseX, mouseY,
                this.layout.rightPageX(), this.layout.rightPageY(),
                this.layout.rightPageRight(), this.layout.rightPageBottom());
    }

    @Nullable
    private IconSlot findTooltipSlot(double mouseX, double mouseY) {
        for (IconSlot slot : this.tooltipSlots) {
            if (slot.bounds().contains(mouseX, mouseY)) {
                return slot;
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
        contentDirty = true;
    }

    public void setPage(int page) {
        this.pagination.setPage(page);
        contentDirty = true;
    }

    // —— Spacetime 卡片：标签+值文字布局，6 行 ——
    private int renderSpacetimeCard(GuiGraphics g, Font font, int x, int y, int w,
                                  ExcavationLogEntry entry, UiPointer pointer) {
        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int lineHeight = font.lineHeight;
        int lineGap = JournalLayout.LOG_DETAIL_META_LINE_GAP;
        int cardH = spacetimeHeight(lineHeight);

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
        // 坐标显示统一走 CopyCoordinateButton 的格式化入口（与日志列表页同一写法，I-11）
        String coordsValue = CopyCoordinateButton.formatCoordinates(pos.getX(), pos.getY(), pos.getZ());

        // 动态计算标签列宽度：取所有标签的最大宽度，保证值列对齐
        int labelWidth = maxWidth(font, createdLabel, updatedLabel, structLabel, biomeLabel, dimensionLabel, coordsLabel);
        int valueX = x + pad + labelWidth + LABEL_GAP;
        int copyBtnW = CopyCoordinateButton.WIDTH;
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
                JournalLayout.LOG_DETAIL_VALUE_COLOR, pointer, prevHovered) + lineGap;

        // 行2：更新时间
        ty += drawMetaRow(g, font, 1, updatedLabel.getString(), updatedValue,
                x + pad, ty, valueX, valueW, rowFullWidth,
                JournalLayout.LOG_DETAIL_VALUE_COLOR, pointer, prevHovered) + lineGap;

        // 行3：结构/特征/未知（未知时降低视觉权重）
        int structColor = structInfo.isUnknown()
                ? JournalLayout.LOG_DETAIL_UNKNOWN_COLOR
                : JournalLayout.LOG_DETAIL_VALUE_COLOR;
        ty += drawMetaRow(g, font, 2, structLabel.getString(), structInfo.displayName(),
                x + pad, ty, valueX, valueW, rowFullWidth,
                structColor, pointer, prevHovered) + lineGap;

        // 行4：群系
        ty += drawMetaRow(g, font, 3, biomeLabel.getString(), biomeValue,
                x + pad, ty, valueX, valueW, rowFullWidth,
                JournalLayout.LOG_DETAIL_VALUE_COLOR, pointer, prevHovered) + lineGap;

        // 行5：维度
        ty += drawMetaRow(g, font, 4, dimensionLabel.getString(), dimValue,
                x + pad, ty, valueX, valueW, rowFullWidth,
                JournalLayout.LOG_DETAIL_VALUE_COLOR, pointer, prevHovered) + lineGap;

        // 行6：坐标 + 复制按钮（放在坐标行最右侧）
        drawMetaRow(g, font, 5, coordsLabel.getString(), coordsValue,
                x + pad, ty, valueX, lastRowValueW, rowFullWidth,
                JournalLayout.LOG_DETAIL_VALUE_COLOR, pointer, prevHovered);
        return cardH;
    }

    // 绘制单行元信息：标签（固定宽度，次要色）+ 值（剩余宽度，主要色，超宽滚动）
    private int drawMetaRow(GuiGraphics g, Font font, int rowIndex, String label, String value,
                            int rowX, int rowY, int valueX, int valueW, int rowFullWidth,
                            int valueColor, UiPointer pointer, int prevHovered) {
        int rowH = font.lineHeight;

        // 检测当前行是否被悬停（行高度 = lineHeight，但适当放宽垂直判定范围以提升体验）
        boolean hovered = pointer.contains(new UiRect(rowX, rowY - 1, rowFullWidth, rowH + 2));
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
        TextScroll.draw(g, font, value, valueX, rowY, valueW, valueColor,
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
        LootGrid grid = lootGrid(font.lineHeight, w);
        int totalLoot = cachedLoot.size();
        boolean hasLoot = totalLoot > 0;

        // 标题行：收集清单 [完成标志]
        Component title = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_collection");
        boolean allCompleted = hasLoot && obtainedLootCount == totalLoot;
        int titleY = y + pad;

        int cardH = grid.y() - y + (hasLoot ? grid.availableHeight() + LOOT_PROGRESS_RESERVE : 0);
        drawCardBackground(g, x, y, w, cardH);

        // 绘制标题与完成标记，统一限制在卡片内边距范围内避免溢出书页右侧
        int innerW = Math.max(0, w - pad * 2);
        if (allCompleted) {
            Component badge = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_completed");
            String badgeText = badge.getString();
            int badgeW = font.width(badgeText);
            int shownBadgeW = Math.min(badgeW, innerW);
            int titleMaxW = Math.max(0, innerW - shownBadgeW - 6);
            TextScroll.draw(g, font, title.getString(), x + pad, titleY, titleMaxW,
                    JournalLayout.LOG_DETAIL_LABEL_COLOR, false, 0, false);
            int badgeX = x + w - pad - shownBadgeW;
            TextScroll.draw(g, font, badgeText, badgeX, titleY, shownBadgeW,
                    JournalLayout.LOG_DETAIL_COMPLETION_COLOR, false, 0, false);
        } else {
            TextScroll.draw(g, font, title.getString(), x + pad, titleY, innerW,
                    JournalLayout.LOG_DETAIL_LABEL_COLOR, false, 0, false);
        }

        if (!hasLoot) {
            g.drawString(font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_loot_empty"),
                    grid.x(), grid.y(), JournalLayout.LOG_DETAIL_UNKNOWN_COLOR, false);
            return;
        }

        int from = Math.min(totalLoot, pagination.getPage() * grid.pageSize());
        int to = Math.min(totalLoot, from + grid.pageSize());
        for (int i = from; i < to; i++) {
            LootDisplayEntry loot = this.cachedLoot.get(i);
            UiRect cell = grid.cellBounds(i - from);
            int iconX = cell.x();
            int iconY = cell.y();
            ItemStack stack = loot.stack();

            int actual = loot.actualCount();
            int expected = loot.expectedCount();

            // 状态(1)：已解析 未获得——以低透明度渲染物品贴图呈现幽灵虚影，不再覆盖遮罩
            if (actual == 0) {
                // 先按当前颜色提交已入队的不透明内容（前面的图标与角标），
                // 否则它们会跟着下面的半透明着色器颜色一起被染色。
                g.flush();
                RenderSystem.enableBlend();
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.35F);
            }

            UiRenderLayers.item(g, cell, local -> {
                local.pose().pushPose();
                try {
                    local.pose().translate(iconX, iconY, 0);
                    local.pose().scale(ICON_SIZE / 16f, ICON_SIZE / 16f, 1);
                    local.renderItem(stack, 0, 0);
                } finally {
                    local.pose().popPose();
                }
            });

            // 着色器颜色在提交时生效，半透明图标须先提交再复位颜色。
            if (actual == 0) {
                g.flush();
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                RenderSystem.disableBlend();
            } else if (loot.isFullyObtained()) {
                // 状态(3)：已解析 全部获得——显示实际获得数量（精掘翻倍后 actual 可能大于 expected），绿色
                drawBadge(g, font, iconX, iconY, String.valueOf(actual),
                        JournalLayout.LOG_DETAIL_LOOT_BADGE_FULL_COLOR);
            } else {
                // 状态(2)：已解析 未全部获得——显示 n/m，棕黄色
                drawBadge(g, font, iconX, iconY, actual + "/" + expected,
                        JournalLayout.LOG_DETAIL_LOOT_BADGE_PARTIAL_COLOR);
            }
        }

        // 底部进度条 + 文字（文字直接渲染在进度条本体上）
        int barWidth = w - pad * 2;
        int barY = grid.pageBottom() - JournalLayout.LOG_DETAIL_PROGRESS_BAR_HEIGHT - pad;
        drawProgressBar(g, font, grid.x(), barY, barWidth, obtainedLootCount, totalLoot);
    }

    // 角标使用独立前景层和原版文字阴影。
    private static void drawBadge(GuiGraphics g, Font font, int iconX, int iconY, String text, int color) {
        int badgeX = iconX + ICON_SIZE - font.width(text);
        int badgeY = iconY + ICON_SIZE - font.lineHeight;
        UiRenderLayers.draw(g, UiRenderLayers.FOREGROUND,
                () -> g.drawString(font, text, badgeX, badgeY, color, true));
    }

    // 绘制底部总进度条，"Collected X/Y" 文字直接渲染在进度条本体上
    private static void drawProgressBar(GuiGraphics g, Font font, int x, int y, int width,
                                        int obtained, int total) {
        Component progressText = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_loot_progress", obtained, total);
        JournalPalette.drawProgressBar(g, font, x, y, width,
                JournalLayout.LOG_DETAIL_PROGRESS_BAR_HEIGHT, obtained, total, progressText, false, 0);
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
            if (stack != null) {
                result.add(new LootDisplayEntry(key, expectedCount, actualCount, stack));
            }
        }
        return result;
    }

    private record LootGrid(UiGridMetrics metrics, int x, int y, int pageBottom, int availableHeight, int pageSize) {
        private UiRect cellBounds(int index) {
            UiRect cell = metrics.cellBounds(index);
            return new UiRect(x + cell.x(), y + cell.y(), cell.width(), cell.height());
        }
    }

    private LootGrid lootGrid(int lineHeight, int cardWidth) {
        int pad = JournalLayout.LOG_DETAIL_CARD_PAD;
        int gridX = layout.rightPageX() + 8 + pad;
        int gridY = spacetimeTop() + spacetimeHeight(lineHeight) + JournalLayout.LOG_DETAIL_CARD_GAP
                + pad + lineHeight + JournalLayout.LOG_DETAIL_TITLE_TO_GRID_GAP;
        int pageBottom = layout.rightPageBottom() - 4;
        int stride = ICON_SIZE + ICON_GAP;
        int availableHeight = Math.max(0, pageBottom - gridY - LOOT_PROGRESS_RESERVE);
        int columns = Math.max(1, (cardWidth - pad * 2 + ICON_GAP) / stride);
        int rows = Math.max(1, availableHeight / stride);
        UiGridMetrics metrics = new UiGridMetrics(columns, ICON_SIZE, ICON_SIZE, ICON_GAP, ICON_GAP);
        return new LootGrid(metrics, gridX, gridY, pageBottom, availableHeight, columns * rows);
    }

    // —— 分页计算 ——
    private int computePageCount() {
        if (this.cachedLoot.isEmpty()) {
            return 1;
        }
        Font font = Minecraft.getInstance().font;
        int contentWidth = this.layout.rightPageWidth() - 20;
        LootGrid grid = lootGrid(font.lineHeight, contentWidth);
        if (grid.availableHeight() == 0) {
            return 1;
        }
        return 1 + (this.cachedLoot.size() - 1) / grid.pageSize();
    }

    private int spacetimeTop() {
        return layout.rightPageY() + JournalLayout.LOG_TOP + JournalLayout.LOG_DETAIL_BACK_BTN_SIZE + 4;
    }

    private static int spacetimeHeight(int lineHeight) {
        return JournalLayout.LOG_DETAIL_CARD_PAD * 2
                + lineHeight * JournalLayout.LOG_DETAIL_META_ROWS
                + JournalLayout.LOG_DETAIL_META_LINE_GAP * (JournalLayout.LOG_DETAIL_META_ROWS - 1);
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

    private record IconSlot(UiRect bounds, LootDisplayEntry entry) {
    }

}
