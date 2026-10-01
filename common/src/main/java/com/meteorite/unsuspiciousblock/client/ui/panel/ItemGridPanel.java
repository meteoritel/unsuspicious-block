package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.support.ScrollTextHelper;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalTooltipBuilder;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.DeclaredChance;
import com.meteorite.unsuspiciousblock.loottable.simulation.ProbabilityFormat;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.simulation.FunctionObservationSummary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    // 与语义色表 NAME 同值，直接引用以消除重复字面量（值未变）
    private static final int NAME_COLOR = UiTextPalette.Parchment.NAME;
    // 模拟值：原 #8B5A2B 在纸面底色 #E8DCBC 上仅 4.28:1，压暗到 #7F4E1F 后为 5.11:1。
    private static final int PROB_COLOR = 0xFF7F4E1F;
    // 概率文字画在羊皮纸底色（含格子半透明填充，实测约 #E8DCBC）上，且不带文字阴影，
    // 因此亮色系对比度不足：亮金 #C8A014 仅约 2.1:1、亮橙红 #C06040 仅约 3.4:1，实际看不清。
    // 这里压暗到 4.5:1 以上（WCAG 相对亮度公式），同时保留"金 = 概率型条件""橙红 = 运行时条件"的色相语义。
    private static final int PROB_COLOR_PROBABILISTIC = 0xFF7A5700; // 深金色——有概率型条件（约 4.8:1）
    private static final int PROB_COLOR_RUNTIME = 0xFF9E4326;       // 深橙红——有运行时条件（约 4.7:1）
    private static final int PROB_COLOR_UNKNOWN = 0xFF4A4038;       // 深暖灰——状态词（需要条件／问号）约 7.4:1，浅色纸面上必须读得清
    // 「待解析／未解锁」与「需要条件／问号」同属"给不出数字"的状态词，直接复用同一深暖灰（7.40:1），
    // 不再单独取只有 4.20:1 的 #7A6247。
    private static final int PENDING_COLOR = PROB_COLOR_UNKNOWN;
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
    // 绘制用的不可变条目视图：在 rebuildTagGroups 中随数据重建，逐帧只读，不再每帧 List.copyOf。
    private List<TagGroup> tagGroupView = List.of();
    private List<ResourceLocation> tagIdView = List.of();
    private String locatedKind = "", locatedTarget = "";
    // 逐帧格子文案缓存：GridItem / ChildTableEntry 都是不可变数据，同一实例的文案只取决于语言代码。
    // 失效点：setTable（换表数据）或语言代码变化（整表重算）。
    private final Map<Object, String> probabilityTextCache = new IdentityHashMap<>();
    private final Map<Object, String> displayNameTextCache = new IdentityHashMap<>();
    // 已测宽度缓存（P-10）：与文案缓存同一失效事件（语言/字体实例、setTable）
    private final PanelTextMetrics metrics = new PanelTextMetrics();
    private String cachedLanguage = "";

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
        this.probabilityTextCache.clear();
        this.displayNameTextCache.clear();
        this.metrics.clear();
        rebuildTagGroups();
        if (this.activeTag != null && (this.tagGroups.get(this.activeTag) == null
                || !this.tagGroups.get(this.activeTag).unlocked())) {
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

    public boolean locateTarget(String kind, String target) {
        locatedKind = kind; locatedTarget = target;
        int index = -1;
        if (kind.equals("tag")) {
            index = tagIdView.indexOf(ResourceLocation.tryParse(target));
        } else if (kind.equals("table")) {
            for (int position = 0; position < childTables.size(); position++)
                if (childTables.get(position).tableId().toString().equals(target)) index = tagGroups.size() + position;
        } else {
            for (int position = 0; position < directItems.size(); position++)
                if (directItems.get(position).signature().toStoredKey().equals(target))
                    index = tagGroups.size() + childTables.size() + position;
            if (index < 0) {
                for (var entry : tagGroups.entrySet()) {
                    var members = entry.getValue().members();
                    for (int position = 0; position < members.size(); position++) {
                        if (!members.get(position).signature().toStoredKey().equals(target)) continue;
                        if (!entry.getValue().unlocked()) return false;
                        activeTag = entry.getKey();
                        setPage(position / TAG_GROUP_MEMBERS_PER_PAGE);
                        resetHoverState();
                        return true;
                    }
                }
            }
        }
        if (index < 0) return false;
        activeTag = null;
        setPage(index / JournalLayout.GRID_ITEMS_PER_PAGE);
        resetHoverState();
        return true;
    }

    public boolean targetVisible(String kind, String target) {
        if (kind.equals("table")) return childTables.stream().anyMatch(child -> child.unlocked()
                && child.tableId().toString().equals(target));
        if (kind.equals("tag")) {
            var group = tagGroups.get(ResourceLocation.tryParse(target));
            return group != null && group.unlocked();
        }
        return items.stream().anyMatch(item -> item.unlocked() && item.signature().toStoredKey().equals(target));
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
        this.activeTag = tagId != null && this.tagGroups.containsKey(tagId)
                && this.tagGroups.get(tagId).unlocked() ? tagId : null;
        this.page = Mth.clamp(this.page, 0, Math.max(0, pageCount() - 1));
        resetHoverState();
    }

    // 判断鼠标是否在物品网格面板区域内：边界口径统一走 PagePanel.containsPageBounds（R-4）
    public boolean containsMouse(double mouseX, double mouseY) {
        return PagePanel.containsPageBounds(mouseX, mouseY,
                layout.rightPageX(), layout.rightPageY(),
                layout.rightPageRight(), layout.rightPageBottom());
    }

    // 渲染物品网格（仅图标，不含进度/页码）
    public void render(GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        refreshTextCacheLanguage();
        this.metrics.beginFrame(font);
        if (this.items.isEmpty() && this.childTables.isEmpty()) {
            int leftX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
            guiGraphics.drawString(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.empty_entries"),
                    leftX, layout.rightPageY() + JournalLayout.GRID_TOP, UiTextPalette.Parchment.LABEL, false);
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
            List<TagGroup> groups = this.tagGroupView;
            for (int i = from; i < to; i++) {
                int visualIndex = i - from;
                int cellX = cellX(gridX, visualIndex);
                int cellY = cellY(gridY, visualIndex);
                boolean hovered = isMouseOverCell(cellX, cellY, mouseX, mouseY);
                updateHoverState(visualIndex, hovered);
                if (i < groupCount) {
                    renderTagGroupCell(guiGraphics, font, cellX, cellY,
                            groups.get(i), hovered, slotScrollTicks[visualIndex]);
                    renderLocation(guiGraphics, cellX, cellY, "tag", groups.get(i).id().toString());
                } else if (i < groupCount + childCount) {
                    renderChildTableCell(guiGraphics, font, cellX, cellY,
                            this.childTables.get(i - groupCount), hovered,
                            slotScrollTicks[visualIndex]);
                    renderLocation(guiGraphics, cellX, cellY, "table", this.childTables.get(i - groupCount).tableId().toString());
                } else {
                    renderItemCell(guiGraphics, font, cellX, cellY,
                            this.directItems.get(i - groupCount - childCount), hovered,
                            slotScrollTicks[visualIndex]);
                    renderLocation(guiGraphics, cellX, cellY, "item", this.directItems.get(i - groupCount - childCount).signature().toStoredKey());
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
        renderLocation(guiGraphics, cellX, cellY, "item", item.signature().toStoredKey());
    }

    private void renderLocation(GuiGraphics graphics, int cellX, int cellY, String kind, String target) {
        if (!locatedKind.equals(kind) || !locatedTarget.equals(target)) return;
        int right = cellX + JournalLayout.GRID_CELL_WIDTH;
        int bottom = cellY + JournalLayout.GRID_CELL_HEIGHT;
        int color = UiTextPalette.Parchment.ACCENT;
        graphics.fill(cellX, cellY, right, cellY + 1, color);
        graphics.fill(cellX, bottom - 1, right, bottom, color);
        graphics.fill(cellX, cellY, cellX + 1, bottom, color);
        graphics.fill(right - 1, cellY, right, bottom, color);
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

        if (!group.unlocked()) {
            renderLockedCollectionCell(guiGraphics, font, cellX, cellY);
            return;
        }

        renderTagPreview(guiGraphics, group, cellX + cellW / 2, cellY + ICON_TOP);
        PanelTextMetrics.Measured groupId = this.metrics.measure(group.id().toString(), font);
        ScrollTextHelper.draw(guiGraphics, font, groupId.text(), groupId.width(),
                cellX + 3, cellY + NAME_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);

        int discovered = group.discoveredCount();
        Component progress = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.tag_group_progress_short",
                discovered, group.members().size());
        int progressWidth = this.metrics.measure(progress.getString(), font).width();
        guiGraphics.drawString(font, progress, cellX + (cellW - progressWidth) / 2,
                cellY + PROB_Y_OFFSET, TAG_GROUP_TEXT_COLOR, false);

        if (!group.hasHighlightedMember()) {
            guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH, 0x80808080);
        }
    }

    private void renderTagPreview(GuiGraphics guiGraphics, TagGroup group, int centerX, int iconY) {
        renderPreviewStacks(guiGraphics, group.previewStacks(), centerX, iconY);
    }

    private void renderChildTableCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                      ChildTableEntry child, boolean hovered, int scrollTicks) {
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        int cellH = JournalLayout.GRID_CELL_HEIGHT;
        guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH,
                hovered ? 0x306B7D46 : TAG_GROUP_BG_COLOR);
        guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + 1, TAG_GROUP_BORDER_COLOR);
        guiGraphics.fill(cellX, cellY + cellH - 1, cellX + cellW, cellY + cellH, TAG_GROUP_BORDER_COLOR);
        if (!child.unlocked()) {
            renderLockedCollectionCell(guiGraphics, font, cellX, cellY);
            return;
        }
        renderPreviewStacks(guiGraphics, child.previewItems(), cellX + cellW / 2, cellY + ICON_TOP);
        com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi.icon(
                com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi.Icon.RECOMMEND)
                .render(guiGraphics, cellX + cellW - 11, cellY + 3);
        PanelTextMetrics.Measured childName = this.metrics.measure(
                displayNameText(child, child.displayName()), font);
        ScrollTextHelper.draw(guiGraphics, font, childName.text(), childName.width(),
                cellX + 3, cellY + NAME_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);
        PanelTextMetrics.Measured probability = this.metrics.measure(
                probabilityText(child, child.probability(), List.of()), font);
        ScrollTextHelper.draw(guiGraphics, font, probability.text(), probability.width(),
                cellX + 3, cellY + PROB_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);
    }

    private void renderPreviewStacks(GuiGraphics guiGraphics, List<ItemStack> stacks, int centerX, int iconY) {
        if (stacks.isEmpty()) {
            guiGraphics.blit(UNKNOWN_TEXTURE, centerX - ICON_SIZE / 2, iconY, ICON_SIZE, ICON_SIZE,
                    0f, 0f, UNKNOWN_TEXTURE_SIZE, UNKNOWN_TEXTURE_SIZE,
                    UNKNOWN_TEXTURE_SIZE, UNKNOWN_TEXTURE_SIZE);
            return;
        }
        int totalWidth = ICON_SIZE + (stacks.size() - 1) * 8;
        int startX = centerX - totalWidth / 2;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            int iconX = startX + i * 8;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(iconX, iconY, i * 5f);
            float scale = (float) ICON_SIZE / 16f;
            guiGraphics.pose().scale(scale, scale, 1f);
            guiGraphics.renderItem(stack, 0, 0);
            guiGraphics.pose().popPose();
        }
    }

    // 未解锁合集与普通未发现物品共用未知图标；在首个成员被发现前不透露名称与概率。
    private void renderLockedCollectionCell(GuiGraphics graphics, Font font, int cellX, int cellY) {
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        graphics.blit(UNKNOWN_TEXTURE, cellX + (cellW - ICON_SIZE) / 2, cellY + ICON_TOP,
                ICON_SIZE, ICON_SIZE, 0f, 0f, UNKNOWN_TEXTURE_SIZE, UNKNOWN_TEXTURE_SIZE,
                UNKNOWN_TEXTURE_SIZE, UNKNOWN_TEXTURE_SIZE);
        Component label = Component.translatable("screen.unsuspiciousblock.archaeology_journal.collection_locked");
        int labelWidth = this.metrics.measure(label.getString(), font).width();
        graphics.drawString(font, label, cellX + (cellW - labelWidth) / 2,
                cellY + NAME_Y_OFFSET, PENDING_COLOR, false);
    }

    private void renderTagBackCell(GuiGraphics guiGraphics, Font font, int cellX, int cellY,
                                   boolean hovered, ResourceLocation tagId, int scrollTicks) {
        int cellW = JournalLayout.GRID_CELL_WIDTH;
        int cellH = JournalLayout.GRID_CELL_HEIGHT;
        guiGraphics.fill(cellX, cellY, cellX + cellW, cellY + cellH,
                hovered ? 0x306B7D46 : TAG_GROUP_BG_COLOR);
        Component backIcon = Component.literal("<");
        int backIconWidth = this.metrics.measure(backIcon.getString(), font).width();
        guiGraphics.drawString(font, backIcon,
                cellX + (cellW - backIconWidth) / 2, cellY + ICON_TOP + 5,
                TAG_GROUP_TEXT_COLOR, false);
        PanelTextMetrics.Measured tagText = this.metrics.measure(tagId.toString(), font);
        ScrollTextHelper.draw(guiGraphics, font, tagText.text(), tagText.width(),
                cellX + 3, cellY + NAME_Y_OFFSET, cellW - 6,
                TAG_GROUP_TEXT_COLOR, hovered, scrollTicks, true);
        Component back = Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.tag_group_back");
        int backWidth = this.metrics.measure(back.getString(), font).width();
        guiGraphics.drawString(font, back, cellX + (cellW - backWidth) / 2,
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
            int textW = this.metrics.measure(pendingText.getString(), font).width();
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
            int badgeX = iconX + ICON_SIZE - this.metrics.measure(countText, font).width();
            int badgeY = iconY + ICON_SIZE - font.lineHeight + 1;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0f, 0f, 200f);
            guiGraphics.drawString(font, countText, badgeX, badgeY, badgeColor, true);
            guiGraphics.pose().popPose();
        }

        // 物品名（悬停时走马灯滚动，与 intro/Catalog 风格一致；非悬停时居中静态）
        int nameMaxWidth = cellW - 6;
        PanelTextMetrics.Measured name = this.metrics.measure(
                displayNameText(item, item.displayName()), font);
        ScrollTextHelper.draw(guiGraphics, font, name.text(), name.width(),
                cellX + 3, cellY + NAME_Y_OFFSET, nameMaxWidth,
                NAME_COLOR, hovered, scrollTicks, true);

        // 概率（居中，颜色根据状态与不确定性等级区分）——显示优先级链见 formatProbability
        PanelTextMetrics.Measured probText = this.metrics.measure(
                probabilityText(item, item.probability(), item.declaredChances()), font);
        int probColor = probabilityColor(item.probability(), item.uncertaintyLevel(),
                !item.declaredChances().isEmpty());
        ScrollTextHelper.draw(guiGraphics, font, probText.text(), probText.width(),
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
        String s = String.format(Locale.ROOT, "%.1f", v);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    /**
     * 网格概率文案的**显示优先级链**（决策 44）：
     * <ol>
     *   <li>可适用性状态优先——「需要条件」与未知一律不显示任何数字（含声明触发率），
     *       否则一个"当前拿不到"的条目会顶着最有利场景的数字出现；</li>
     *   <li>模拟状态可展示（已测量 / 静态不可达）时优先显示**声明触发率**——
     *       {@code random_chance} 类条目的模拟值常是零命中，声明值反而是唯一有信息量的数字；</li>
     *   <li>最后才是模拟值本身。</li>
     * </ol>
     * 客户端只做这一层渲染分派，四种状态的判定全部来自服务端派生结果。
     */
    private static Component formatProbability(@Nullable Probability probability,
                                               List<DeclaredChance> declaredChances) {
        if (probability == null) {
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_question");
        }
        if (!probability.isDisplayable()) {
            // 需要条件 / 未知：状态词直接表达"现在没有数字可给"
            return ProbabilityFormat.formatComponent(probability);
        }
        if (!declaredChances.isEmpty()) {
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_trigger_short",
                    ProbabilityFormat.formatDeclaredChances(declaredChances));
        }
        return ProbabilityFormat.formatComponent(probability);
    }

    // 概率文字的颜色：状态词用灰色，数值沿用不确定性等级的既有色相语义
    private static int probabilityColor(@Nullable Probability probability,
                                        LootConditionHandler.UncertaintyLevel uncertaintyLevel,
                                        boolean hasDeclaredChance) {
        if (probability == null || !probability.isDisplayable()) {
            return PROB_COLOR_UNKNOWN;
        }
        if (hasDeclaredChance) {
            return PROB_COLOR_PROBABILISTIC;
        }
        return switch (uncertaintyLevel) {
            case PROBABILISTIC -> PROB_COLOR_PROBABILISTIC;
            case RUNTIME -> PROB_COLOR_RUNTIME;
            default -> PROB_COLOR;
        };
    }

    @Nullable
    private GridItem hoveredItem(double mouseX, double mouseY) {
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
        return hoveredItem;
    }

    @Nullable
    public ResourceLocation childRecommendationTarget(double mouseX, double mouseY) {
        if (activeTag != null) return null;
        int gridX = layout.rightPageX() + JournalLayout.GRID_LEFT_PAD;
        int gridY = layout.rightPageY() + JournalLayout.GRID_TOP;
        int from = page * JournalLayout.GRID_ITEMS_PER_PAGE;
        int end = Math.min(tagGroups.size() + childTables.size(), from + JournalLayout.GRID_ITEMS_PER_PAGE);
        for (int index = Math.max(from, tagGroups.size()); index < end; index++) {
            ChildTableEntry child = childTables.get(index - tagGroups.size());
            int left = cellX(gridX, index - from) + JournalLayout.GRID_CELL_WIDTH - 13;
            int top = cellY(gridY, index - from) + 1;
            if (child.unlocked() && mouseX >= left && mouseX < left + 12 && mouseY >= top && mouseY < top + 13) {
                return child.tableId();
            }
        }
        return null;
    }

    public TooltipData getTooltipData(double mouseX, double mouseY) {
        GridItem hoveredItem = hoveredItem(mouseX, mouseY);
        if (hoveredItem == null) {
            return null;
        }
        if (!hoveredItem.unlocked()) {
            return new TooltipData(ItemStack.EMPTY, null, -1, null, List.of(), false,
                    LootConditionHandler.UncertaintyLevel.NONE, false, List.of(), List.of(), 0, null,
                    null, null);
        }
        return new TooltipData(hoveredItem.stack(), hoveredItem.tooltipHint(),
                hoveredItem.count(), hoveredItem.probability(), hoveredItem.acquisitionPaths(),
                hoveredItem.injected(), hoveredItem.uncertaintyLevel(), true,
                hoveredItem.scenarioProbabilities(), hoveredItem.declaredChances(),
                hoveredItem.simulationCount(), hoveredItem.observedFunctions(),
                hoveredItem.displayName(), hoveredItem.signature());
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
        List<ResourceLocation> tagIds = this.tagIdView;
        for (int i = from; i < to; i++) {
            int visualIndex = i - from;
            int cellX = cellX(gridX, visualIndex);
            int cellY = cellY(gridY, visualIndex);
            if (isMouseOverCell(cellX, cellY, mouseX, mouseY)) {
                if (i < groupCount) {
                    ResourceLocation tagId = tagIds.get(i);
                    if (this.tagGroups.get(tagId).unlocked()) {
                        this.activeTag = tagId;
                        this.page = 0;
                        resetHoverState();
                    }
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
        if (childRecommendationTarget(mouseX, mouseY) != null) return List.of(
                com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState.text("recommend.title"));
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
        List<TagGroup> groups = this.tagGroupView;
        for (int i = from; i < to; i++) {
            int visualIndex = i - from;
            if (!isMouseOverCell(cellX(gridX, visualIndex), cellY(gridY, visualIndex), mouseX, mouseY)) {
                continue;
            }
            if (i < groupCount) {
                TagGroup group = groups.get(i);
                if (!group.unlocked()) return List.of(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.collection_locked"));
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
            if (!child.unlocked()) return List.of(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.collection_locked"));
            return JournalTooltipBuilder.buildChildTable(
                    child.displayName(), child.tableId(), child.probability(),
                    child.scenarioProbabilities(), child.conditions(), child.luckAffected());
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
            List<GridItem> members = List.copyOf(entry.getValue().values());
            this.tagGroups.put(entry.getKey(), new TagGroup(
                    entry.getKey(),
                    members,
                    members.stream().anyMatch(GridItem::unlocked),
                    (int) members.stream().filter(GridItem::unlocked).count(),
                    members.stream().anyMatch(GridItem::highlighted),
                    members.stream().filter(GridItem::unlocked).limit(3).map(GridItem::stack).toList()));
        }
        this.tagGroupView = List.copyOf(this.tagGroups.values());
        this.tagIdView = List.copyOf(this.tagGroups.keySet());
    }

    // 语言代码变化时丢弃逐帧文案缓存：文字随语言变，几何与数据不随。
    // 资源重载而语言代码不变的情况仍由目录增量/setTable 兜底（与 ScenarioPanel.sceneLabel 同口径）。
    private void refreshTextCacheLanguage() {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!language.equals(this.cachedLanguage)) {
            this.cachedLanguage = language;
            this.probabilityTextCache.clear();
            this.displayNameTextCache.clear();
        }
    }

    // 概率文案按数据实例缓存：实例字段（概率/声明触发率/不确定性等级）不可变，只随语言变化。
    private String probabilityText(Object key, @Nullable Probability probability,
                                   List<DeclaredChance> declaredChances) {
        return this.probabilityTextCache.computeIfAbsent(key,
                ignored -> formatProbability(probability, declaredChances).getString());
    }

    // 名称文案按数据实例缓存：Component 的解析结果只随语言变化。
    private String displayNameText(Object key, Component name) {
        return this.displayNameTextCache.computeIfAbsent(key, ignored -> name.getString());
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

    /**
     * tag 分组入口所需的不可变展示数据。
     * <p>成员都是不可变 record，因此 unlocked / discoveredCount / hasHighlightedMember 与预览栈
     * 在构造时一次算出，绘制路径只读字段，不再逐帧 stream。</p>
     */
    private record TagGroup(ResourceLocation id, List<GridItem> members, boolean unlocked,
                            int discoveredCount, boolean hasHighlightedMember,
                            List<ItemStack> previewStacks) {
    }

    /** 子表导航入口展示数据。 */
    public record ChildTableEntry(ResourceLocation tableId, Component displayName, boolean unlocked,
                                  Probability probability,
                                  List<ScenarioProbability> scenarioProbabilities,
                                  List<LootConditionInfo> conditions,
                                  List<ItemStack> previewItems,
                                  boolean luckAffected,
                                  int simulationCount) {
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
     * discovered=false 时只展示未发现状态，不携带物品身份、概率或获取路径。
     */
    public record TooltipData(ItemStack stack, @Nullable Component hint, int count, @Nullable Probability probability,
                               List<LootAcquisitionPath> acquisitionPaths,
                               boolean injected,
                               LootConditionHandler.UncertaintyLevel uncertaintyLevel,
                               boolean discovered,
                               List<ScenarioProbability> scenarioProbabilities,
                               List<DeclaredChance> declaredChances,
                               int simulationCount,
                               @Nullable FunctionObservationSummary observedFunctions,
                               @Nullable Component displayName,
                               @Nullable LootResultSignature signature) {
        // 便利构造：仅 stack + hint（无统计信息，如日志详情页）
        public TooltipData(ItemStack stack, @Nullable Component hint) {
            this(stack, hint, -1, null, List.of(), false,
                    LootConditionHandler.UncertaintyLevel.NONE, true, List.of(), List.of(), 0, null, null, null);
        }

        // 兼容构造：无函数观测摘要
        public TooltipData(ItemStack stack, @Nullable Component hint, int count, @Nullable Probability probability,
                           List<LootAcquisitionPath> acquisitionPaths, boolean injected,
                           LootConditionHandler.UncertaintyLevel uncertaintyLevel, boolean discovered,
                           List<ScenarioProbability> scenarioProbabilities,
                           List<DeclaredChance> declaredChances, int simulationCount) {
            this(stack, hint, count, probability, acquisitionPaths, injected, uncertaintyLevel, discovered,
                    scenarioProbabilities, declaredChances, simulationCount, null, null, null);
        }
    }

    // 物品网格条目；highlighted 标记搜索匹配（true = 匹配/无搜索，false = 搜索不匹配）
    public record GridItem(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                           Probability probability, boolean unlocked, int count,
                           LootResultSignature signature, boolean highlighted,
                           List<LootAcquisitionPath> acquisitionPaths,
                           boolean injected,
                           LootConditionHandler.UncertaintyLevel uncertaintyLevel,
                           List<ScenarioProbability> scenarioProbabilities,
                           List<DeclaredChance> declaredChances,
                           int simulationCount,
                           @Nullable FunctionObservationSummary observedFunctions) {

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
