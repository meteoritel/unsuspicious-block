package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.support.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalTooltipBuilder;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 右侧物品网格面板 —— 渲染物品图标、名称、概率与数量角标，补充信息通过 tooltip 展示 */
public final class ItemGridPanel implements PagePanel {

    private static final ResourceLocation UNKNOWN_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/unknown_item.png");
    private static final int ICON_SIZE = 18;            // 略放大的物品图标尺寸
    private static final int UNKNOWN_TEXTURE_SIZE = 16; // 未知图标纹理原始尺寸
    private static final int ICON_TOP = 3;              // 图标距格子顶部偏移
    private static final int NAME_Y_OFFSET = 25;        // 物品名文字 y 偏移
    private static final int PROB_Y_OFFSET = 37;        // 概率文字 y 偏移
    // 物品名/数量角标颜色
    private static final int NAME_COLOR = 0xFF3A2A1A;
    private static final int PROB_COLOR = 0xFF8B5A2B;
    private static final int PROB_COLOR_PROBABILISTIC = 0xFFC8A014; // 金色——有概率型条件
    private static final int PROB_COLOR_RUNTIME = 0xFFC06040;       // 橙红色——有运行时条件
    private static final int PENDING_COLOR = 0xFF7A6247;
    private static final int BADGE_COLOR_NORMAL = 0xFFFFFFFF;
    private static final int BADGE_COLOR_ABBR = 0xFFFFC060;
    private static final int TAG_GROUP_BORDER_COLOR = 0x806B7D46;
    private static final int TAG_GROUP_BG_COLOR = 0x186B7D46;
    private static final int TAG_GROUP_TEXT_COLOR = 0xFF3F5128;
    private static final int TAG_GROUP_MEMBERS_PER_PAGE = JournalLayout.GRID_ITEMS_PER_PAGE - 1;

    private final List<GridItem> items = new ArrayList<>();
    private final List<GridItem> directItems = new ArrayList<>();
    private final List<ChildTableEntry> childTables = new ArrayList<>();
    private final LinkedHashMap<ResourceLocation, TagGroup> tagGroups = new LinkedHashMap<>();
    private final JournalBookBackground.BookLayout layout;
    private int page;
    @Nullable
    private ResourceLocation activeTag;
    @Nullable
    private ResourceLocation navigationTarget;
    // 每个可视格子的滚动文字状态（物品名走马灯），按 visualIndex 索引
    private final int[] slotScrollTicks = new int[JournalLayout.GRID_ITEMS_PER_PAGE];
    private final boolean[] slotWasHovered = new boolean[JournalLayout.GRID_ITEMS_PER_PAGE];

    public ItemGridPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        this.page = 0;
    }

    // 设置当前展示的战利品表数据
    public void setTable(List<GridItem> items, List<ChildTableEntry> childTables) {
        this.items.clear();
        this.items.addAll(items);
        this.childTables.clear();
        this.childTables.addAll(childTables);
        this.navigationTarget = null;
        rebuildTagGroups();
        if (this.activeTag != null && !this.tagGroups.containsKey(this.activeTag)) {
            this.activeTag = null;
        }
        this.page = Mth.clamp(this.page, 0, Math.max(0, pageCount() - 1));
        resetHoverState();
    }

    public int pageCount() {
        if (this.activeTag != null) {
            TagGroup group = this.tagGroups.get(this.activeTag);
            int memberCount = group != null ? group.members().size() : 0;
            return Math.max(1, (memberCount + TAG_GROUP_MEMBERS_PER_PAGE - 1)
                    / TAG_GROUP_MEMBERS_PER_PAGE);
        }
        int entryCount = this.tagGroups.size() + this.childTables.size() + this.directItems.size();
        return Math.max(1, (entryCount + JournalLayout.GRID_ITEMS_PER_PAGE - 1)
                / JournalLayout.GRID_ITEMS_PER_PAGE);
    }

    public int getPage() {
        return page;
    }

    public void changePage(int delta) {
        int nextPage = Mth.clamp(page + delta, 0, Math.max(0, pageCount() - 1));
        if (nextPage != this.page) {
            this.page = nextPage;
            resetHoverState();
        }
    }

    public void resetPage() {
        this.page = 0;
        this.activeTag = null;
        resetHoverState();
    }

    // 直接设置页码（resize 后恢复用）
    public void setPage(int page) {
        int nextPage = Mth.clamp(page, 0, Math.max(0, pageCount() - 1));
        if (nextPage != this.page) {
            this.page = nextPage;
            resetHoverState();
        }
    }

    @Nullable
    public ResourceLocation getActiveTag() {
        return this.activeTag;
    }

    public void setActiveTag(@Nullable ResourceLocation tagId) {
        this.activeTag = tagId != null && this.tagGroups.containsKey(tagId) ? tagId : null;
        this.page = Mth.clamp(this.page, 0, Math.max(0, pageCount() - 1));
        resetHoverState();
    }

    // 判断鼠标是否在物品网格面板区域内
    public boolean containsMouse(double mouseX, double mouseY) {
        return mouseX >= layout.rightPageX() && mouseX <= layout.rightPageRight()
                && mouseY >= layout.rightPageY() && mouseY <= layout.rightPageBottom();
    }

    // 渲染物品网格（仅图标，不含进度/页码）
    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        if (this.items.isEmpty() && this.childTables.isEmpty()) {
            int leftX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
            guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_entries"),
                    leftX, layout.rightPageY() + JournalLayout.GRID_TOP, 0x7A6247, false);
            return;
        }

        // 网格区域
        int gridWidth = JournalLayout.GRID_CELLS_PER_ROW * JournalLayout.GRID_CELL_WIDTH
                + JournalLayout.GRID_COLUMN_GAP;
        int gridX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
        int gridY = layout.rightPageY() + JournalLayout.GRID_TOP;

        int visibleEntryCount;
        if (this.activeTag != null) {
            TagGroup group = this.tagGroups.get(this.activeTag);
            List<GridItem> members = group != null ? group.members() : List.of();
            int from = page * TAG_GROUP_MEMBERS_PER_PAGE;
            int to = Math.min(members.size(), from + TAG_GROUP_MEMBERS_PER_PAGE);
            boolean backHovered = isMouseOverCell(gridX, gridY, mouseX, mouseY);
            updateHoverState(0, backHovered);
            renderTagBackCell(guiGraphics, font, gridX, gridY,
                    backHovered, this.activeTag, slotScrollTicks[0]);
            for (int i = from; i < to; i++) {
                int visualIndex = i - from + 1;
                renderVisibleItem(guiGraphics, font, gridX, gridY, visualIndex,
                        members.get(i), mouseX, mouseY);
            }
            visibleEntryCount = to - from + 1;
        } else {
            int groupCount = this.tagGroups.size();
            int childCount = this.childTables.size();
            int entryCount = groupCount + childCount + this.directItems.size();
            int from = page * JournalLayout.GRID_ITEMS_PER_PAGE;
            int to = Math.min(entryCount, from + JournalLayout.GRID_ITEMS_PER_PAGE);
            List<TagGroup> groups = List.copyOf(this.tagGroups.values());
            for (int i = from; i < to; i++) {
                int visualIndex = i - from;
                int cellX = cellX(gridX, visualIndex);
                int cellY = cellY(gridY, visualIndex);
                boolean hovered = isMouseOverCell(cellX, cellY, mouseX, mouseY);
                updateHoverState(visualIndex, hovered);
                if (i < groupCount) {
                    renderTagGroupCell(guiGraphics, font, cellX, cellY,
                            groups.get(i), hovered, slotScrollTicks[visualIndex]);
                } else if (i < groupCount + childCount) {
                    renderChildTableCell(guiGraphics, font, cellX, cellY,
                            this.childTables.get(i - groupCount), hovered,
                            slotScrollTicks[visualIndex]);
                } else {
                    renderItemCell(guiGraphics, font, cellX, cellY,
                            this.directItems.get(i - groupCount - childCount), hovered,
                            slotScrollTicks[visualIndex]);
                }
            }
            visibleEntryCount = to - from;
        }

        // 行间分隔线
        int visibleRows = (visibleEntryCount + JournalLayout.GRID_CELLS_PER_ROW - 1)
                / JournalLayout.GRID_CELLS_PER_ROW;
        for (int r = 1; r < visibleRows; r++) {
            int sepY = gridY + r * JournalLayout.GRID_CELL_HEIGHT;
            guiGraphics.fill(gridX, sepY, gridX + gridWidth, sepY + 1, JournalLayout.GRID_SEPARATOR_COLOR);
        }
    }

    private void renderVisibleItem(GuiGraphics guiGraphics, Font font, int gridX, int gridY,
                                   int visualIndex, GridItem item, int mouseX, int mouseY) {
        int cellX = cellX(gridX, visualIndex);
        int cellY = cellY(gridY, visualIndex);
        boolean hovered = isMouseOverCell(cellX, cellY, mouseX, mouseY);
        updateHoverState(visualIndex, hovered);
        renderItemCell(guiGraphics, font, cellX, cellY, item, hovered, slotScrollTicks[visualIndex]);
    }

    private void updateHoverState(int visualIndex, boolean hovered) {
        if (!slotWasHovered[visualIndex] && hovered) {
            slotScrollTicks[visualIndex] = 0;
        }
        slotWasHovered[visualIndex] = hovered;
        if (hovered) {
            slotScrollTicks[visualIndex]++;
        }
    }

    private void renderTagGroupCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                    TagGroup group, boolean hovered, int scrollTicks) {
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        int cellH = JournalLayout.GRID_CELL_HEIGHT;
        guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH,
                hovered ? 0x306B7D46 : TAG_GROUP_BG_COLOR);
        guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + 1, TAG_GROUP_BORDER_COLOR);
        guiGraphics.fill(cellX, cellY + cellH - 1, cellX + cellW, cellY + cellH, TAG_GROUP_BORDER_COLOR);

        renderTagPreview(guiGraphics, group, cellX + cellW / 2, cellY + ICON_TOP);
        ScrollTextHelper.draw(guiGraphics, font, group.id().toString(),
                cellX + 3, cellY + NAME_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);

        int discovered = group.discoveredCount();
        Component progress = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.tag_group_progress_short",
                discovered, group.members().size());
        int progressWidth = font.width(progress);
        guiGraphics.drawString(font, progress, cellX + (cellW - progressWidth) / 2,
                cellY + PROB_Y_OFFSET, TAG_GROUP_TEXT_COLOR, false);

        if (!group.hasHighlightedMember()) {
            guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH, 0x80808080);
        }
    }

    private void renderTagPreview(GuiGraphics guiGraphics, TagGroup group, int centerX, int iconY) {
        renderPreview(guiGraphics, group.members(), centerX, iconY);
    }

    private void renderChildTableCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                      ChildTableEntry child, boolean hovered, int scrollTicks) {
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        int cellH = JournalLayout.GRID_CELL_HEIGHT;
        guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH,
                hovered ? 0x306B7D46 : TAG_GROUP_BG_COLOR);
        guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + 1, TAG_GROUP_BORDER_COLOR);
        guiGraphics.fill(cellX, cellY + cellH - 1, cellX + cellW, cellY + cellH, TAG_GROUP_BORDER_COLOR);
        renderPreview(guiGraphics, child.previewItems(), cellX + cellW / 2, cellY + ICON_TOP);
        ScrollTextHelper.draw(guiGraphics, font, child.displayName().getString(),
                cellX + 3, cellY + NAME_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);
        Component probability = formatProbability(
                child.probability(), LootConditionHandler.UncertaintyLevel.NONE,
                child.scenarioProbabilities());
        ScrollTextHelper.draw(guiGraphics, font, probability.getString(),
                cellX + 3, cellY + PROB_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);
    }

    private void renderPreview(GuiGraphics guiGraphics, List<GridItem> items, int centerX, int iconY) {
        List<GridItem> discovered = items.stream().filter(GridItem::unlocked).limit(3).toList();
        if (discovered.isEmpty()) {
            guiGraphics.blit(UNKNOWN_TEXTURE, centerX - ICON_SIZE / 2, iconY, ICON_SIZE, ICON_SIZE,
                    0f, 0f, UNKNOWN_TEXTURE_SIZE, UNKNOWN_TEXTURE_SIZE,
                    UNKNOWN_TEXTURE_SIZE, UNKNOWN_TEXTURE_SIZE);
            return;
        }
        int totalWidth = ICON_SIZE + (discovered.size() - 1) * 8;
        int startX = centerX - totalWidth / 2;
        for (int i = 0; i < discovered.size(); i++) {
            ItemStack stack = discovered.get(i).stack();
            int iconX = startX + i * 8;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(iconX, iconY, i * 5f);
            float scale = (float) ICON_SIZE / 16f;
            guiGraphics.pose().scale(scale, scale, 1f);
            guiGraphics.renderItem(stack, 0, 0);
            guiGraphics.pose().popPose();
        }
    }

    private void renderTagBackCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                   boolean hovered, ResourceLocation tagId, int scrollTicks) {
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        int cellH = JournalLayout.GRID_CELL_HEIGHT;
        guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH,
                hovered ? 0x306B7D46 : TAG_GROUP_BG_COLOR);
        Component backIcon = Component.literal("<");
        guiGraphics.drawString(font, backIcon,
                cellX + (cellW - font.width(backIcon)) / 2, cellY + ICON_TOP + 5,
                TAG_GROUP_TEXT_COLOR, false);
        ScrollTextHelper.draw(guiGraphics, font, tagId.toString(),
                cellX + 3, cellY + NAME_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);
        Component back = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.tag_group_back");
        guiGraphics.drawString(font, back, cellX + (cellW - font.width(back)) / 2,
                cellY + PROB_Y_OFFSET, TAG_GROUP_TEXT_COLOR, false);
    }

    private void renderItemCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                GridItem item, boolean hovered, int scrollTicks) {
        boolean unlocked = item.unlocked();
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        int cellH = JournalLayout.GRID_CELL_HEIGHT;
        int centerX = cellX + cellW / 2;
        int iconX = cellX + (cellW - ICON_SIZE) / 2;
        int iconY = cellY + ICON_TOP;

        // 单元格背景
        int bgColor = unlocked ? (hovered ? 0x22C8B090 : 0x00000000) : (hovered ? 0x22776456 : 0x00000000);
        if (bgColor != 0) {
            guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH, bgColor);
        }

        if (!unlocked) {
            // 未解锁：渲染未知物品图标（放大到 ICON_SIZE），下方显示"未解析"
            guiGraphics.blit(UNKNOWN_TEXTURE, iconX, iconY, ICON_SIZE, ICON_SIZE,
                    0f, 0f, UNKNOWN_TEXTURE_SIZE, UNKNOWN_TEXTURE_SIZE,
                    UNKNOWN_TEXTURE_SIZE, UNKNOWN_TEXTURE_SIZE);
            Component pendingText = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.pending_analysis");
            int textW = font.width(pendingText);
            guiGraphics.drawString(font, pendingText, centerX - textW / 2,
                    cellY + NAME_Y_OFFSET, PENDING_COLOR, false);
            return;
        }

        ItemStack stack = item.stack();
        // 物品图标（略放大：renderItem 固定 16px，通过 pose 缩放到 ICON_SIZE）
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(iconX, iconY, 0);
        float scale = (float) ICON_SIZE / 16f;
        guiGraphics.pose().scale(scale, scale, 1f);
        guiGraphics.renderItem(stack, 0, 0);
        // 附魔光效/耐久条，传 null 禁用原版 count 显示（改由角标渲染）
        guiGraphics.renderItemDecorations(font, stack, 0, 0, null);
        guiGraphics.pose().popPose();

        // 数量角标（图标右下角；大数模糊化为 1.2K/1.2M 等）
        // 提升 z 到 200+，确保文字在物品图标（z=150）之上
        if (item.count() > 0) {
            String countText = formatCountBadge(item.count());
            int badgeColor = item.count() >= 1000 ? BADGE_COLOR_ABBR : BADGE_COLOR_NORMAL;
            int badgeX = iconX + ICON_SIZE - font.width(countText);
            int badgeY = iconY + ICON_SIZE - font.lineHeight + 1;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0f, 0f, 200f);
            guiGraphics.drawString(font, countText, badgeX, badgeY, badgeColor, true);
            guiGraphics.pose().popPose();
        }

        // 物品名（悬停时走马灯滚动，与 intro/Catalog 风格一致；非悬停时居中静态）
        int nameMaxWidth = cellW - 6;
        ScrollTextHelper.draw(guiGraphics, font, item.displayName().getString(),
                cellX + 3, cellY + NAME_Y_OFFSET, nameMaxWidth,
                NAME_COLOR, hovered, scrollTicks, true);

        // 概率（居中，颜色根据不确定性等级区分）
        Component probComp = formatProbability(item.probability(), item.uncertaintyLevel(),
                item.scenarioProbabilities());
        int probColor = switch (item.uncertaintyLevel()) {
            case PROBABILISTIC -> PROB_COLOR_PROBABILISTIC;
            case RUNTIME -> PROB_COLOR_RUNTIME;
            default -> PROB_COLOR;
        };
        ScrollTextHelper.draw(guiGraphics, font, probComp.getString(),
                cellX + 3, cellY + PROB_Y_OFFSET, cellW - 6,
                probColor, hovered, scrollTicks, true);

        // 搜索不匹配：覆盖半透明灰色遮罩降低视觉权重
        if (!item.highlighted()) {
            guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH, 0x80808080);
        }
    }

    // 格式化数量为角标文本：<1000 原样，1K+ 用 1.2K/1.2M/1.2B 简写
    private static String formatCountBadge(int count) {
        if (count < 0) return "";
        if (count < 1000) return Integer.toString(count);
        if (count < 1_000_000) return formatOneDecimal(count / 1000.0) + "K";
        if (count < 1_000_000_000) return formatOneDecimal(count / 1_000_000.0) + "M";
        return formatOneDecimal(count / 1_000_000_000.0) + "B";
    }

    // 保留 1 位小数，去除整数的 .0 后缀
    private static String formatOneDecimal(double v) {
        String s = String.format("%.1f", v);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    // 格式化概率为显示用 Component
    private static Component formatProbability(@Nullable String probability,
                                               LootConditionHandler.UncertaintyLevel uncertaintyLevel,
                                               List<ScenarioProbability> scenarioProbabilities) {
        if (hasDistinctScenarioProbabilities(scenarioProbabilities)) {
            return probability == null || probability.equals("?")
                    ? Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_conditional")
                    : Component.literal(probability);
        }
        if (probability == null || probability.equals("?")) {
            if (uncertaintyLevel != LootConditionHandler.UncertaintyLevel.NONE) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.probability_conditional");
            }
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_unknown_short");
        }
        return switch (uncertaintyLevel) {
            case PROBABILISTIC -> Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_estimated_short", probability);
            case RUNTIME -> Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_conditional_short", probability);
            default -> Component.literal(probability);
        };
    }

    private static boolean hasDistinctScenarioProbabilities(List<ScenarioProbability> probabilities) {
        return probabilities.stream().map(ScenarioProbability::probability).distinct().limit(2).count() > 1;
    }

    @Nullable
    public TooltipData getTooltipData(double mouseX, double mouseY) {
        int gridX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
        int gridY = layout.rightPageY() + JournalLayout.GRID_TOP;
        GridItem hoveredItem = null;
        if (this.activeTag != null) {
            TagGroup group = this.tagGroups.get(this.activeTag);
            List<GridItem> members = group != null ? group.members() : List.of();
            int from = this.page * TAG_GROUP_MEMBERS_PER_PAGE;
            int to = Math.min(members.size(), from + TAG_GROUP_MEMBERS_PER_PAGE);
            for (int i = from; i < to; i++) {
                int visualIndex = i - from + 1;
                if (isMouseOverCell(cellX(gridX, visualIndex), cellY(gridY, visualIndex), mouseX, mouseY)) {
                    hoveredItem = members.get(i);
                    break;
                }
            }
        } else {
            int nonItemCount = this.tagGroups.size() + this.childTables.size();
            int entryCount = nonItemCount + this.directItems.size();
            int from = this.page * JournalLayout.GRID_ITEMS_PER_PAGE;
            int to = Math.min(entryCount, from + JournalLayout.GRID_ITEMS_PER_PAGE);
            for (int i = Math.max(from, nonItemCount); i < to; i++) {
                int visualIndex = i - from;
                if (isMouseOverCell(cellX(gridX, visualIndex), cellY(gridY, visualIndex), mouseX, mouseY)) {
                    hoveredItem = this.directItems.get(i - nonItemCount);
                    break;
                }
            }
        }
        if (hoveredItem == null) {
            return null;
        }
        if (!hoveredItem.unlocked()) {
            return new TooltipData(ItemStack.EMPTY, null, -1, hoveredItem.probability(),
                    hoveredItem.acquisitionPaths(), hoveredItem.injected(),
                    hoveredItem.uncertaintyLevel(), false, hoveredItem.scenarioProbabilities());
        }
        return new TooltipData(hoveredItem.stack(), hoveredItem.tooltipHint(),
                hoveredItem.count(), hoveredItem.probability(), hoveredItem.acquisitionPaths(),
                hoveredItem.injected(), hoveredItem.uncertaintyLevel(), true,
                hoveredItem.scenarioProbabilities());
    }

    // 处理 tag 分组入口与返回入口点击；普通物品格不消费点击。
    public boolean handleClick(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        int gridX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
        int gridY = layout.rightPageY() + JournalLayout.GRID_TOP;
        if (this.activeTag != null) {
            if (isMouseOverCell(gridX, gridY, mouseX, mouseY)) {
                this.activeTag = null;
                this.page = 0;
                resetHoverState();
                return true;
            }
            return false;
        }

        int groupCount = this.tagGroups.size();
        int childCount = this.childTables.size();
        int from = this.page * JournalLayout.GRID_ITEMS_PER_PAGE;
        int navigationCount = groupCount + childCount;
        int to = Math.min(navigationCount, from + JournalLayout.GRID_ITEMS_PER_PAGE);
        if (from >= navigationCount) {
            return false;
        }
        List<ResourceLocation> tagIds = List.copyOf(this.tagGroups.keySet());
        for (int i = from; i < to; i++) {
            int visualIndex = i - from;
            int cellX = cellX(gridX, visualIndex);
            int cellY = cellY(gridY, visualIndex);
            if (isMouseOverCell(cellX, cellY, mouseX, mouseY)) {
                if (i < groupCount) {
                    this.activeTag = tagIds.get(i);
                    this.page = 0;
                    resetHoverState();
                } else {
                    this.navigationTarget = this.childTables.get(i - groupCount).tableId();
                }
                return true;
            }
        }
        return false;
    }

    @Nullable
    public ResourceLocation consumeNavigationTarget() {
        ResourceLocation target = this.navigationTarget;
        this.navigationTarget = null;
        return target;
    }

    // 渲染 tag 分组入口和返回入口的说明；物品 tooltip 由 JournalTooltipBuilder 处理。
    public void renderNavigationTooltip(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        List<Component> lines = navigationTooltip(mouseX, mouseY);
        if (lines != null) {
            guiGraphics.renderTooltip(font,
                    lines.stream().map(Component::getVisualOrderText).toList(), mouseX, mouseY);
        }
    }

    @Nullable
    private List<Component> navigationTooltip(double mouseX, double mouseY) {
        int gridX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
        int gridY = layout.rightPageY() + JournalLayout.GRID_TOP;
        if (this.activeTag != null) {
            if (!isMouseOverCell(gridX, gridY, mouseX, mouseY)) {
                return null;
            }
            return List.of(
                    Component.literal(this.activeTag.toString()).withStyle(ChatFormatting.AQUA),
                    Component.translatable(
                            "screen.unsuspiciousblock.archaeology_journal.tag_group_back_tooltip")
                            .withStyle(ChatFormatting.GRAY));
        }

        int groupCount = this.tagGroups.size();
        int childCount = this.childTables.size();
        int from = this.page * JournalLayout.GRID_ITEMS_PER_PAGE;
        int navigationCount = groupCount + childCount;
        int to = Math.min(navigationCount, from + JournalLayout.GRID_ITEMS_PER_PAGE);
        if (from >= navigationCount) {
            return null;
        }
        List<TagGroup> groups = List.copyOf(this.tagGroups.values());
        for (int i = from; i < to; i++) {
            int visualIndex = i - from;
            if (!isMouseOverCell(cellX(gridX, visualIndex), cellY(gridY, visualIndex), mouseX, mouseY)) {
                continue;
            }
            if (i < groupCount) {
                TagGroup group = groups.get(i);
                return List.of(
                        Component.literal(group.id().toString()).withStyle(ChatFormatting.AQUA),
                        Component.translatable(
                                "screen.unsuspiciousblock.archaeology_journal.tag_group_progress",
                                group.discoveredCount(), group.members().size()).withStyle(ChatFormatting.GREEN),
                        Component.translatable(
                                "screen.unsuspiciousblock.archaeology_journal.tag_group_open")
                                .withStyle(ChatFormatting.GRAY));
            }
            ChildTableEntry child = this.childTables.get(i - groupCount);
            return JournalTooltipBuilder.buildChildTable(
                    child.displayName(), child.tableId(), child.probability(),
                    child.scenarioProbabilities(), child.conditions());
        }
        return null;
    }

    private void rebuildTagGroups() {
        this.directItems.clear();
        this.tagGroups.clear();
        LinkedHashMap<ResourceLocation, LinkedHashMap<String, GridItem>> groupedItems = new LinkedHashMap<>();
        for (GridItem item : this.items) {
            boolean hasDirectPath = item.acquisitionPaths().isEmpty();
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                if (path.sourceChildTable() != null) {
                    continue;
                }
                ResourceLocation tagId = path.sourceItemTag();
                if (tagId == null) {
                    hasDirectPath = true;
                    continue;
                }
                groupedItems.computeIfAbsent(tagId, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(item.signature().toStoredKey(), item);
            }
            if (hasDirectPath) {
                this.directItems.add(item);
            }
        }
        for (Map.Entry<ResourceLocation, LinkedHashMap<String, GridItem>> entry : groupedItems.entrySet()) {
            this.tagGroups.put(entry.getKey(),
                    new TagGroup(entry.getKey(), List.copyOf(entry.getValue().values())));
        }
    }

    private void resetHoverState() {
        Arrays.fill(this.slotScrollTicks, 0);
        Arrays.fill(this.slotWasHovered, false);
    }

    private static int cellX(int gridX, int visualIndex) {
        int col = visualIndex % JournalLayout.GRID_CELLS_PER_ROW;
        return gridX + col * (JournalLayout.GRID_CELL_WIDTH + JournalLayout.GRID_COLUMN_GAP);
    }

    private static int cellY(int gridY, int visualIndex) {
        int row = visualIndex / JournalLayout.GRID_CELLS_PER_ROW;
        return gridY + row * JournalLayout.GRID_CELL_HEIGHT;
    }

    private static boolean isMouseOverCell(int cellX, int cellY, double mouseX, double mouseY) {
        return mouseX >= cellX && mouseX < cellX + JournalLayout.GRID_CELL_WIDTH
                && mouseY >= cellY && mouseY < cellY + JournalLayout.GRID_CELL_HEIGHT;
    }

    /** tag 分组入口所需的不可变展示数据。 */
    private record TagGroup(ResourceLocation id, List<GridItem> members) {
        private int discoveredCount() {
            return (int) this.members.stream().filter(GridItem::unlocked).count();
        }

        private boolean hasHighlightedMember() {
            return this.members.stream().anyMatch(GridItem::highlighted);
        }
    }

    /** 子表导航入口展示数据。 */
    public record ChildTableEntry(ResourceLocation tableId, Component displayName,
                                  String probability,
                                  List<ScenarioProbability> scenarioProbabilities,
                                  List<LootConditionInfo> conditions,
                                  List<GridItem> previewItems) {
        public ChildTableEntry {
            scenarioProbabilities = List.copyOf(scenarioProbabilities);
            conditions = List.copyOf(conditions);
            previewItems = List.copyOf(previewItems);
        }
    }

    /**
     * Tooltip 数据：携带物品堆叠、附魔等原版 tooltip 之外需要追加的统计信息。
     * <p>
     * count 为 -1 表示无获取统计（如日志详情页），不追加 "Acquired" 行；
     * probability 为 null 时不追加 "Drop Chance" 行。
     * discovered=false 时不携带真实物品身份，仅展示未发现状态与获取条件。
     */
    public record TooltipData(ItemStack stack, @Nullable Component hint, int count, @Nullable String probability,
                               List<LootAcquisitionPath> acquisitionPaths,
                               boolean injected,
                               LootConditionHandler.UncertaintyLevel uncertaintyLevel,
                               boolean discovered,
                               List<ScenarioProbability> scenarioProbabilities) {
        // 便利构造：仅 stack + hint（无统计信息，如日志详情页）
        public TooltipData(ItemStack stack, @Nullable Component hint) {
            this(stack, hint, -1, null, List.of(), false,
                    LootConditionHandler.UncertaintyLevel.NONE, true, List.of());
        }

    }

    // 物品网格条目；highlighted 标记搜索匹配（true = 匹配/无搜索，false = 搜索不匹配）
    public record GridItem(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                           String probability, boolean unlocked, int count,
                           LootResultSignature signature, boolean highlighted,
                           List<LootAcquisitionPath> acquisitionPaths,
                           boolean injected,
                           LootConditionHandler.UncertaintyLevel uncertaintyLevel,
                           List<ScenarioProbability> scenarioProbabilities) {

        @Nullable
        public ResourceLocation primarySourceChildTable() {
            if (this.acquisitionPaths.stream().anyMatch(path -> path.sourceChildTable() == null)) {
                return null;
            }
            return this.acquisitionPaths.stream()
                    .map(LootAcquisitionPath::sourceChildTable)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        public ItemStack stack() {
            if (this.signature != null) {
                ItemStack preview = this.signature.createPreviewStack();
                if (!preview.isEmpty()) {
                    return preview;
                }
            }
            return new ItemStack(BuiltInRegistries.ITEM.get(this.id));
        }
    }
}
