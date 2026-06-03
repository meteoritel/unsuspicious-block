package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.support.SpecimenBoxClientState;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 标本箱界面（Screen）——纯客户端渲染层。
 * 从 SpecimenBoxClientState 读取服务端推送的权威快照，
 * 绘制目录面板、3x3 逻辑槽位网格、分页按钮与玩家物品栏。
 * 所有用户操作通过 sendMenuButton() 回传服务端处理。
 */
public class SpecimenBoxScreen extends AbstractContainerScreen<SpecimenBoxMenu> {
    private static final ResourceLocation MAIN_PANEL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/specimen_box_main_panel.png");
    private static final ResourceLocation INVENTORY_PANEL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/player_inventory.png");
    private static final ResourceLocation SLOT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/specimen_box_slot.png");
    private static final ResourceLocation CATALOG_ENTRY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/specimen_box_catalog_entry.png");
    private static final ResourceLocation UNKNOWN_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/unknown_item.png");

    // ========== 主页面常量 玩家物品栏 + 标本箱物品栏 ========== //
    private static final int MAIN_PANEL_WIDTH = 233;
    private static final int MAIN_PANEL_HEIGHT = 117;
    private static final int INVENTORY_PANEL_X = 29;
    private static final int INVENTORY_PANEL_Y = 128;
    private static final int INVENTORY_PANEL_WIDTH = 176;
    private static final int INVENTORY_PANEL_HEIGHT = 100;
    private static final int BOX_TITLE_X = 24;
    private static final int BOX_TITLE_Y = 8;
    private static final int PLAYER_TITLE_X = 35;
    private static final int PLAYER_TITLE_Y = 134;

    // ========== 目录与翻页 ========== //
    private static final int PAGE_LABEL_CENTER_X = 170;
    private static final int PAGE_LABEL_Y = 119;
    private static final int EMPTY_HINT_Y = 54;

    private static final int CATALOG_X = 24;
    private static final int CATALOG_Y = 20;
    private static final int CATALOG_WIDTH = 72;
    private static final int CATALOG_VISIBLE_ROWS = 4;
    private static final int CATALOG_ENTRY_HEIGHT = 18;
    private static final int CATALOG_ROW_SPACING = CATALOG_ENTRY_HEIGHT + 1;
    private static final int CATALOG_SCROLLBAR_X = CATALOG_X + CATALOG_WIDTH + 3;
    private static final int CATALOG_SCROLLBAR_Y = CATALOG_Y;
    private static final int CATALOG_SCROLLBAR_WIDTH = 3;
    private static final int CATALOG_SCROLLBAR_HEIGHT = CATALOG_VISIBLE_ROWS * CATALOG_ROW_SPACING - 1;

    private static final int SLOT_GRID_X = 135;
    private static final int SLOT_GRID_Y = 17;
    private static final int SLOT_SIZE = 20;
    private static final int SLOT_FRAME_SIZE = 24;
    private static final int SLOT_FRAME_OFFSET = 2;
    private static final int SLOT_COLUMN_SPACING = 28;
    private static final int SLOT_ROW_SPACING = 28;
    private static final int PAGE_PREV_X = 136;
    private static final int PAGE_PREV_Y = 116;
    private static final int PAGE_NEXT_X = 192;
    private static final int PAGE_NEXT_Y = 116;
    private static final int BUTTON_SIZE = 12;

    private SpecimenBoxClientState.Snapshot snapshot;
    private int catalogScrollStart;

    public SpecimenBoxScreen(SpecimenBoxMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = MAIN_PANEL_WIDTH;
        this.imageHeight = INVENTORY_PANEL_Y + INVENTORY_PANEL_HEIGHT;
    }

    @Override
    // 主渲染入口：绘制背景层、tooltip 与逻辑槽位悬浮提示
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
        this.renderLogicalTooltips(guiGraphics, mouseX, mouseY);
    }

    @Override
    // 渲染背景：刷新服务端快照后依次绘制主面板、物品栏面板、目录、槽位网格与翻页按钮
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        this.refreshSnapshot();

        int left = this.leftPos;
        int top = this.topPos;
        guiGraphics.blit(MAIN_PANEL_TEXTURE, left, top, 0, 0,
                MAIN_PANEL_WIDTH, MAIN_PANEL_HEIGHT, MAIN_PANEL_WIDTH, MAIN_PANEL_HEIGHT);
        guiGraphics.blit(INVENTORY_PANEL_TEXTURE, left + INVENTORY_PANEL_X, top + INVENTORY_PANEL_Y, 0, 0,
                INVENTORY_PANEL_WIDTH, INVENTORY_PANEL_HEIGHT, INVENTORY_PANEL_WIDTH, INVENTORY_PANEL_HEIGHT);

        this.renderCatalogPanel(guiGraphics, left, top, mouseX, mouseY);
        this.renderSlotGrid(guiGraphics, left, top, mouseX, mouseY);
        this.renderPageButtons(guiGraphics, left, top, mouseX, mouseY);
    }

    @Override
    // 渲染前景标签：标题、玩家物品栏标题、空白提示与分页信息
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, BOX_TITLE_X, BOX_TITLE_Y, 0xFFF4DD, false);
        guiGraphics.drawString(this.font, this.playerInventoryTitle, PLAYER_TITLE_X, PLAYER_TITLE_Y, 0x4A4A4A, false);

        if (this.selectedTable() == null) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("screen.unsuspiciousblock.specimen_box.empty"),
                    PAGE_LABEL_CENTER_X, EMPTY_HINT_Y, 0xA18364);
            return;
        }
        if (this.pageCount() > 1) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.page",
                            this.pageIndex() + 1, this.pageCount()),
                    PAGE_LABEL_CENTER_X, PAGE_LABEL_Y, 0xF2E2C6);
        }
    }

    @Override
    // 鼠标点击事件：刷新快照后处理自定义点击（翻页、选表、逻辑槽位交互）
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.refreshSnapshot();
        if (this.handleCustomClick(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    // 鼠标滚轮事件：目录区域滚动翻行
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int catalogHeight = CATALOG_VISIBLE_ROWS * CATALOG_ROW_SPACING - 1;
        if (this.isPointInside(this.leftPos + CATALOG_X, this.topPos + CATALOG_Y,
                CATALOG_WIDTH, catalogHeight, mouseX, mouseY)) {
            int deltaRows = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
            if (deltaRows != 0) {
                this.scrollCatalogBy(deltaRows);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    // 键盘事件：上/下方向键切换选中表
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        this.refreshSnapshot();
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            int tableCount = this.tableCount();
            if (tableCount <= 0) {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            int selected = this.selectedTableIndex();
            int step = keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
            int targetIndex = Mth.clamp(Math.max(0, selected) + step, 0, tableCount - 1);
            this.ensureCatalogIndexVisible(targetIndex);
            this.sendMenuButton(SpecimenBoxMenu.absoluteTableButtonId(targetIndex));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    // 关闭界面时清理客户端快照缓存
    public void removed() {
        SpecimenBoxClientState.clear(this.menu.containerId);
        super.removed();
    }

    // 处理自定义点击：翻页按钮 → 选中表 → 逻辑槽位存取（通过 sendMenuButton 回传服务端）
    private boolean handleCustomClick(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) {
            return false;
        }

        int left = this.leftPos;
        int top = this.topPos;
        if (button == 0) {
            boolean canSelectPrevPage = this.pageIndex() > 0;
            boolean canSelectNextPage = this.pageIndex() + 1 < this.pageCount();
            if (canSelectPrevPage
                    && this.isPointInside(left + PAGE_PREV_X, top + PAGE_PREV_Y, BUTTON_SIZE, BUTTON_SIZE, mouseX, mouseY)) {
                return this.sendMenuButton(SpecimenBoxMenu.BUTTON_PREV_PAGE);
            }
            if (canSelectNextPage
                    && this.isPointInside(left + PAGE_NEXT_X, top + PAGE_NEXT_Y, BUTTON_SIZE, BUTTON_SIZE, mouseX, mouseY)) {
                return this.sendMenuButton(SpecimenBoxMenu.BUTTON_NEXT_PAGE);
            }

            int visibleStart = this.catalogScrollStart;
            int visibleEnd = Math.min(visibleStart + CATALOG_VISIBLE_ROWS, this.tableCount());
            for (int index = visibleStart; index < visibleEnd; index++) {
                int row = index - visibleStart;
                int rowX = left + CATALOG_X;
                int rowY = top + CATALOG_Y + row * CATALOG_ROW_SPACING;
                if (this.isPointInside(rowX, rowY, CATALOG_WIDTH, CATALOG_ENTRY_HEIGHT, mouseX, mouseY)) {
                    return this.sendMenuButton(SpecimenBoxMenu.absoluteTableButtonId(index));
                }
            }
        }

        List<SpecimenBoxClientState.LogicalSlotView> logicalSlots = this.logicalSlots();
        for (int slot = 0; slot < logicalSlots.size(); slot++) {
            if (this.isPointInsideLogicalSlot(left, top, slot, mouseX, mouseY)) {
                return this.sendMenuButton(SpecimenBoxMenu.logicalSlotButtonId(slot, button == 1));
            }
        }
        return false;
    }

    // 渲染目录面板：按 visibleStart → visibleEnd 显示表项，选中态/悬浮态纹理不同
    private void renderCatalogPanel(GuiGraphics guiGraphics, int left, int top, int mouseX, int mouseY) {
        int visibleStart = this.catalogScrollStart;
        int visibleEnd = Math.min(visibleStart + CATALOG_VISIBLE_ROWS, this.tableCount());
        for (int index = visibleStart; index < visibleEnd; index++) {
            int row = index - visibleStart;
            int rowX = left + CATALOG_X;
            int rowY = top + CATALOG_Y + row * CATALOG_ROW_SPACING;
            boolean selected = index == this.selectedTableIndex();
            boolean hovered = this.isPointInside(rowX, rowY, CATALOG_WIDTH, CATALOG_ENTRY_HEIGHT, mouseX, mouseY);
            int v = selected ? CATALOG_ENTRY_HEIGHT * 2 : hovered ? CATALOG_ENTRY_HEIGHT : 0;
            guiGraphics.blit(CATALOG_ENTRY_TEXTURE, rowX, rowY, 0, v,
                    CATALOG_WIDTH, CATALOG_ENTRY_HEIGHT, CATALOG_WIDTH, CATALOG_ENTRY_HEIGHT * 3);

            String label = this.font.plainSubstrByWidth(this.tableAt(index).displayName().getString(), CATALOG_WIDTH - 8);
            guiGraphics.drawString(this.font, label, rowX + 4, rowY + 5, selected ? 0xFFF9ED : 0xF2E2C7, false);
        }
        this.renderCatalogScrollbar(guiGraphics, left, top);
    }

    // 渲染目录滚动条：当表项数超过可见行数时绘制滑条
    private void renderCatalogScrollbar(GuiGraphics guiGraphics, int left, int top) {
        int tableCount = this.tableCount();
        if (tableCount <= CATALOG_VISIBLE_ROWS) {
            return;
        }

        int trackX = left + CATALOG_SCROLLBAR_X;
        int trackY = top + CATALOG_SCROLLBAR_Y;
        int trackBottom = trackY + CATALOG_SCROLLBAR_HEIGHT;
        guiGraphics.fill(trackX, trackY, trackX + CATALOG_SCROLLBAR_WIDTH, trackBottom, 0x4C2A1D10);

        int knobHeight = Math.max(8, Math.round((float) CATALOG_SCROLLBAR_HEIGHT * CATALOG_VISIBLE_ROWS / tableCount));
        int maxScrollStart = tableCount - CATALOG_VISIBLE_ROWS;
        int maxKnobOffset = CATALOG_SCROLLBAR_HEIGHT - knobHeight;
        int knobOffset = Math.round((float) this.catalogScrollStart * maxKnobOffset / maxScrollStart);
        int knobY = trackY + Mth.clamp(knobOffset, 0, maxKnobOffset);
        guiGraphics.fill(trackX, knobY, trackX + CATALOG_SCROLLBAR_WIDTH, knobY + knobHeight, 0xFFDFC69A);
    }

    // 渲染 3×3 逻辑槽位网格：已解锁且有库存的物品 → 渲染物品 + 数量；未解锁 → 问号图标
    private void renderSlotGrid(GuiGraphics guiGraphics, int left, int top, int mouseX, int mouseY) {
        List<SpecimenBoxClientState.LogicalSlotView> logicalSlots = this.logicalSlots();
        for (int slot = 0; slot < SpecimenBoxMenu.LOGICAL_SLOTS_PER_PAGE; slot++) {
            int slotX = slotX(left, slot);
            int slotY = slotY(top, slot);
            boolean hovered = this.isPointInsideLogicalSlot(left, top, slot, mouseX, mouseY);
            int textureV = hovered ? SLOT_FRAME_SIZE : 0;
            guiGraphics.blit(SLOT_TEXTURE, slotX - SLOT_FRAME_OFFSET, slotY - SLOT_FRAME_OFFSET, 0, textureV,
                    SLOT_FRAME_SIZE, SLOT_FRAME_SIZE, SLOT_FRAME_SIZE, SLOT_FRAME_SIZE * 2);

            if (slot >= logicalSlots.size()) {
                continue;
            }

            SpecimenBoxClientState.LogicalSlotView slotView = logicalSlots.get(slot);
            ItemStack previewStack = slotView.signature() != null ? slotView.signature().createPreviewStack() : ItemStack.EMPTY;
            if (!slotView.unlocked() || previewStack.isEmpty()) {
                guiGraphics.blit(UNKNOWN_TEXTURE, slotX + 2, slotY + 2, 0, 0, 16, 16, 16, 16);
                this.renderStoredCount(guiGraphics, slotX, slotY, slotView.storedCount());
                continue;
            }

            if (slotView.storedCount() > 0) {
                guiGraphics.renderItem(previewStack, slotX + 2, slotY + 2);
                this.renderStoredCount(guiGraphics, slotX, slotY, slotView.storedCount());
            } else {
                this.renderGhostItem(guiGraphics, previewStack, slotX + 2, slotY + 2);
            }
        }
    }

    // 以半透明方式渲染"幽灵"物品（已解锁但未存入的预览）
    private void renderGhostItem(GuiGraphics guiGraphics, ItemStack stack, int x, int y) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.35F);
        guiGraphics.renderItem(stack, x, y);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        guiGraphics.fill(x, y, x + 16, y + 16, 0x33F7ECCE);
    }

    // 在逻辑槽位右上角绘制已存储的数量
    private void renderStoredCount(GuiGraphics guiGraphics, int slotX, int slotY, int storedCount) {
        if (storedCount <= 0) {
            return;
        }
        String countText = Integer.toString(storedCount);
        guiGraphics.drawString(this.font, countText,
                slotX + SLOT_SIZE - 1 - this.font.width(countText), slotY + 10,
                0xFFF6D9, true);
    }

    // 渲染 < > 翻页按钮（超过一页时显示）
    private void renderPageButtons(GuiGraphics guiGraphics, int left, int top, int mouseX, int mouseY) {
        if (this.pageCount() <= 1) {
            return;
        }
        this.renderArrowButton(guiGraphics, left + PAGE_PREV_X, top + PAGE_PREV_Y,
                this.pageIndex() > 0, mouseX, mouseY, "<");
        this.renderArrowButton(guiGraphics, left + PAGE_NEXT_X, top + PAGE_NEXT_Y,
                this.pageIndex() + 1 < this.pageCount(), mouseX, mouseY, ">"
        );
    }

    // 渲染单个箭头按钮（含边框、填充与居中文字）
    private void renderArrowButton(GuiGraphics guiGraphics, int x, int y, boolean active, int mouseX, int mouseY, String label) {
        boolean hovered = this.isPointInside(x, y, BUTTON_SIZE, BUTTON_SIZE, mouseX, mouseY);
        int border = active ? (hovered ? 0xFFF0D4A2 : 0xFFD2AF7E) : 0xFF70553C;
        int fill = active ? (hovered ? 0xC47A5A39 : 0xB059422B) : 0x8A352618;
        guiGraphics.fill(x, y, x + BUTTON_SIZE, y + BUTTON_SIZE, border);
        guiGraphics.fill(x + 1, y + 1, x + BUTTON_SIZE - 1, y + BUTTON_SIZE - 1, fill);
        guiGraphics.drawCenteredString(this.font, label, x + BUTTON_SIZE / 2, y + 2, active ? 0xFFF8EC : 0xFFB49C81);
    }

    // 渲染逻辑槽位与目录表项的悬浮 tooltip
    private void renderLogicalTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<SpecimenBoxClientState.LogicalSlotView> logicalSlots = this.logicalSlots();
        for (int slot = 0; slot < logicalSlots.size(); slot++) {
            if (!this.isPointInsideLogicalSlot(this.leftPos, this.topPos, slot, mouseX, mouseY)) {
                continue;
            }

            SpecimenBoxClientState.LogicalSlotView slotView = logicalSlots.get(slot);
            List<Component> tooltip = new ArrayList<>();
            if (!slotView.unlocked()) {
                tooltip.add(Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_entry"));
                tooltip.add(Component.translatable("screen.unsuspiciousblock.archaeology_journal.pending_analysis"));
            } else {
                tooltip.add(slotView.displayName());
                tooltip.add(Component.translatable("screen.unsuspiciousblock.specimen_box.stored", slotView.storedCount()));
                tooltip.add(Component.translatable("screen.unsuspiciousblock.archaeology_journal.acquired", slotView.journalCount()));
            }
            guiGraphics.renderTooltip(this.font, tooltip, Optional.empty(), mouseX, mouseY);
            return;
        }

        int visibleStart = this.catalogScrollStart;
        int visibleEnd = Math.min(visibleStart + CATALOG_VISIBLE_ROWS, this.tableCount());
        for (int index = visibleStart; index < visibleEnd; index++) {
            int row = index - visibleStart;
            int rowX = this.leftPos + CATALOG_X;
            int rowY = this.topPos + CATALOG_Y + row * CATALOG_ROW_SPACING;
            if (this.isPointInside(rowX, rowY, CATALOG_WIDTH, CATALOG_ENTRY_HEIGHT, mouseX, mouseY)) {
                guiGraphics.renderTooltip(this.font, this.tableAt(index).displayName(), mouseX, mouseY);
                return;
            }
        }
    }

    // 从 SpecimenBoxClientState 刷新当前快照，并确保选中表在可视范围内
    private void refreshSnapshot() {
        SpecimenBoxClientState.Snapshot previousSnapshot = this.snapshot;
        this.snapshot = SpecimenBoxClientState.getSnapshot(this.menu.containerId);
        this.clampCatalogScrollStart();

        int selectedIndex = this.selectedTableIndex();
        if (this.snapshot != previousSnapshot && selectedIndex >= 0) {
            this.ensureCatalogIndexVisible(selectedIndex);
        }
    }

    // 获取当前快照中的表项数量
    private int tableCount() {
        return this.snapshot != null ? this.snapshot.tables().size() : 0;
    }

    // 获取当前选中的表索引（安全 clamp）
    private int selectedTableIndex() {
        if (this.snapshot == null || this.snapshot.tables().isEmpty()) {
            return -1;
        }
        return Mth.clamp(this.snapshot.selectedTableIndex(), 0, this.snapshot.tables().size() - 1);
    }

    // 获取当前页码
    private int pageIndex() {
        if (this.snapshot == null) {
            return 0;
        }
        return Mth.clamp(this.snapshot.pageIndex(), 0, this.pageCount() - 1);
    }

    // 获取总页数
    private int pageCount() {
        return this.snapshot != null ? Math.max(1, this.snapshot.pageCount()) : 1;
    }

    // 获取当前页的逻辑槽位列表
    private List<SpecimenBoxClientState.LogicalSlotView> logicalSlots() {
        return this.snapshot != null ? this.snapshot.logicalSlots() : List.of();
    }

    // 获取快照中指定索引的表
    private SpecimenBoxClientState.TableView tableAt(int index) {
        return this.snapshot.tables().get(index);
    }

    // 获取当前选中的表
    private SpecimenBoxClientState.TableView selectedTable() {
        int selectedIndex = this.selectedTableIndex();
        if (selectedIndex < 0) {
            return null;
        }
        return this.tableAt(selectedIndex);
    }

    // 通过 GameMode 向服务端发送菜单按钮点击
    private boolean sendMenuButton(int buttonId) {
        if (this.minecraft == null || this.minecraft.gameMode == null) {
            return false;
        }
        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
        return true;
    }

    // 将 catalogScrollStart 限制在合法范围内
    private void clampCatalogScrollStart() {
        int maxStart = Math.max(0, this.tableCount() - CATALOG_VISIBLE_ROWS);
        this.catalogScrollStart = Mth.clamp(this.catalogScrollStart, 0, maxStart);
    }

    // 按指定行数滚动目录
    private void scrollCatalogBy(int deltaRows) {
        if (deltaRows == 0) {
            return;
        }
        this.catalogScrollStart += deltaRows;
        this.clampCatalogScrollStart();
    }

    // 调整 scrollStart 确保指定索引的表在可视区域内
    private void ensureCatalogIndexVisible(int selectedIndex) {
        if (selectedIndex < this.catalogScrollStart) {
            this.catalogScrollStart = selectedIndex;
        } else if (selectedIndex > this.catalogScrollStart + CATALOG_VISIBLE_ROWS - 1) {
            this.catalogScrollStart = selectedIndex - CATALOG_VISIBLE_ROWS + 1;
        }
        this.clampCatalogScrollStart();
    }

    // 检测点是否在矩形区域内
    private boolean isPointInside(int x, int y, int width, int height, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // 检测点是否在指定逻辑槽位的区域内
    private boolean isPointInsideLogicalSlot(int left, int top, int slot, double mouseX, double mouseY) {
        int slotX = slotX(left, slot) - SLOT_FRAME_OFFSET;
        int slotY = slotY(top, slot) - SLOT_FRAME_OFFSET;
        return this.isPointInside(slotX, slotY, SLOT_FRAME_SIZE, SLOT_FRAME_SIZE, mouseX, mouseY);
    }

    // 计算逻辑槽位的 X 坐标（基于列号）
    private static int slotX(int left, int slot) {
        int column = slot % 3;
        return left + SLOT_GRID_X + column * SLOT_COLUMN_SPACING;
    }

    // 计算逻辑槽位的 Y 坐标（基于行号）
    private static int slotY(int top, int slot) {
        int row = slot / 3;
        return top + SLOT_GRID_Y + row * SLOT_ROW_SPACING;
    }
}
