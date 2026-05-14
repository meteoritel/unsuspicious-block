package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.support.SpecimenBoxClientState;
import com.meteorite.unsuspiciousblock.menu.SpecimenBoxMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 标本箱界面——使用权威菜单快照渲染目录、分页与 2x3 逻辑槽位。 */
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
    private static final int MAIN_PANEL_WIDTH = 198;
    private static final int MAIN_PANEL_HEIGHT = 124;
    private static final int INVENTORY_PANEL_X = 11;
    private static final int INVENTORY_PANEL_Y = 132;
    private static final int INVENTORY_PANEL_WIDTH = 176;
    private static final int INVENTORY_PANEL_HEIGHT = 100;

    // ========== 标题与翻页 ========== //
    private static final int TITLE_X = 12;
    private static final int TITLE_Y = 10;
    private static final int PLAYER_TITLE_X = 20;
    private static final int PLAYER_TITLE_Y = 137;
    private static final int RIGHT_HEADER_CENTER_X = 148;
    private static final int RIGHT_HEADER_Y = 12;
    private static final int PAGE_LABEL_CENTER_X = 148;
    private static final int PAGE_LABEL_Y = 99;

    private static final int CATALOG_X = 13;
    private static final int CATALOG_Y = 31;
    private static final int CATALOG_WIDTH = 72;
    private static final int CATALOG_VISIBLE_ROWS = 5;
    private static final int CATALOG_ROW_HEIGHT = 18;
    private static final int CATALOG_PREV_X = 82;
    private static final int CATALOG_PREV_Y = 9;
    private static final int CATALOG_NEXT_X = 95;
    private static final int CATALOG_NEXT_Y = 9;

    private static final int SLOT_GRID_X = 111;
    private static final int SLOT_GRID_Y = 34;
    private static final int SLOT_SIZE = 20;
    private static final int SLOT_FRAME_SIZE = 24;
    private static final int SLOT_FRAME_OFFSET = 2;
    private static final int SLOT_COLUMN_SPACING = 28;
    private static final int SLOT_ROW_SPACING = 32;
    private static final int PAGE_PREV_X = 118;
    private static final int PAGE_PREV_Y = 97;
    private static final int PAGE_NEXT_X = 166;
    private static final int PAGE_NEXT_Y = 97;
    private static final int BUTTON_SIZE = 12;

    private SpecimenBoxClientState.Snapshot snapshot;

    public SpecimenBoxScreen(SpecimenBoxMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = MAIN_PANEL_WIDTH;
        this.imageHeight = INVENTORY_PANEL_Y + INVENTORY_PANEL_HEIGHT;
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
        this.renderLogicalTooltips(guiGraphics, mouseX, mouseY);
    }

    @Override
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
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, TITLE_X, TITLE_Y, 0xFFF4DD, false);
        guiGraphics.drawString(this.font, this.playerInventoryTitle, PLAYER_TITLE_X, PLAYER_TITLE_Y, 0x4A4A4A, false);

        if (this.selectedTable() != null) {
            String tableName = this.font.plainSubstrByWidth(Objects.requireNonNull(this.selectedTable()).displayName().getString(), 84);
            guiGraphics.drawCenteredString(this.font, tableName, RIGHT_HEADER_CENTER_X, RIGHT_HEADER_Y, 0xFFF3DD);
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.page",
                            this.pageIndex() + 1, this.pageCount()),
                    PAGE_LABEL_CENTER_X, PAGE_LABEL_Y, 0xF2E2C6);
        } else {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("screen.unsuspiciousblock.specimen_box.empty"),
                    RIGHT_HEADER_CENTER_X, 76, 0xA18364);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.refreshSnapshot();
        if (this.handleCustomClick(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void removed() {
        SpecimenBoxClientState.clear(this.menu.containerId);
        super.removed();
    }

    private boolean handleCustomClick(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) {
            return false;
        }

        int left = this.leftPos;
        int top = this.topPos;
        if (button == 0) {
            boolean canSelectPrevTable = this.selectedTableIndex() > 0;
            boolean canSelectNextTable = this.selectedTableIndex() >= 0 && this.selectedTableIndex() < this.tableCount() - 1;
            boolean canSelectPrevPage = this.pageIndex() > 0;
            boolean canSelectNextPage = this.pageIndex() + 1 < this.pageCount();
            if (canSelectPrevTable
                    && this.isPointInside(left + CATALOG_PREV_X, top + CATALOG_PREV_Y, BUTTON_SIZE, BUTTON_SIZE, mouseX, mouseY)) {
                return this.sendMenuButton(SpecimenBoxMenu.BUTTON_PREV_TABLE);
            }
            if (canSelectNextTable
                    && this.isPointInside(left + CATALOG_NEXT_X, top + CATALOG_NEXT_Y, BUTTON_SIZE, BUTTON_SIZE, mouseX, mouseY)) {
                return this.sendMenuButton(SpecimenBoxMenu.BUTTON_NEXT_TABLE);
            }
            if (canSelectPrevPage
                    && this.isPointInside(left + PAGE_PREV_X, top + PAGE_PREV_Y, BUTTON_SIZE, BUTTON_SIZE, mouseX, mouseY)) {
                return this.sendMenuButton(SpecimenBoxMenu.BUTTON_PREV_PAGE);
            }
            if (canSelectNextPage
                    && this.isPointInside(left + PAGE_NEXT_X, top + PAGE_NEXT_Y, BUTTON_SIZE, BUTTON_SIZE, mouseX, mouseY)) {
                return this.sendMenuButton(SpecimenBoxMenu.BUTTON_NEXT_PAGE);
            }

            int visibleStart = this.visibleTableStart();
            int visibleEnd = Math.min(visibleStart + CATALOG_VISIBLE_ROWS, this.tableCount());
            for (int index = visibleStart; index < visibleEnd; index++) {
                int row = index - visibleStart;
                int rowX = left + CATALOG_X;
                int rowY = top + CATALOG_Y + row * CATALOG_ROW_HEIGHT;
                if (this.isPointInside(rowX, rowY, CATALOG_WIDTH, CATALOG_ROW_HEIGHT, mouseX, mouseY)) {
                    return this.sendMenuButton(SpecimenBoxMenu.visibleTableButtonId(row));
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

    private void renderCatalogPanel(GuiGraphics guiGraphics, int left, int top, int mouseX, int mouseY) {
        this.renderArrowButton(guiGraphics, left + CATALOG_PREV_X, top + CATALOG_PREV_Y,
                this.selectedTableIndex() > 0, mouseX, mouseY, "<");
        this.renderArrowButton(guiGraphics, left + CATALOG_NEXT_X, top + CATALOG_NEXT_Y,
                this.selectedTableIndex() >= 0 && this.selectedTableIndex() < this.tableCount() - 1,
                mouseX, mouseY, ">"
        );

        int visibleStart = this.visibleTableStart();
        int visibleEnd = Math.min(visibleStart + CATALOG_VISIBLE_ROWS, this.tableCount());
        for (int index = visibleStart; index < visibleEnd; index++) {
            int row = index - visibleStart;
            int rowX = left + CATALOG_X;
            int rowY = top + CATALOG_Y + row * CATALOG_ROW_HEIGHT;
            boolean selected = index == this.selectedTableIndex();
            boolean hovered = this.isPointInside(rowX, rowY, CATALOG_WIDTH, CATALOG_ROW_HEIGHT, mouseX, mouseY);
            int v = selected ? CATALOG_ROW_HEIGHT * 2 : hovered ? CATALOG_ROW_HEIGHT : 0;
            guiGraphics.blit(CATALOG_ENTRY_TEXTURE, rowX, rowY, 0, v,
                    CATALOG_WIDTH, CATALOG_ROW_HEIGHT, CATALOG_WIDTH, CATALOG_ROW_HEIGHT * 3);

            String label = this.font.plainSubstrByWidth(this.tableAt(index).displayName().getString(), CATALOG_WIDTH - 8);
            guiGraphics.drawString(this.font, label, rowX + 4, rowY + 5, selected ? 0xFFF9ED : 0xF2E2C7, false);
        }
    }

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

    private void renderGhostItem(GuiGraphics guiGraphics, ItemStack stack, int x, int y) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.35F);
        guiGraphics.renderItem(stack, x, y);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        guiGraphics.fill(x, y, x + 16, y + 16, 0x33F7ECCE);
    }

    private void renderStoredCount(GuiGraphics guiGraphics, int slotX, int slotY, int storedCount) {
        if (storedCount <= 0) {
            return;
        }
        String countText = Integer.toString(storedCount);
        guiGraphics.drawString(this.font, countText,
                slotX + SLOT_SIZE - 1 - this.font.width(countText), slotY + 10,
                0xFFF6D9, true);
    }

    private void renderPageButtons(GuiGraphics guiGraphics, int left, int top, int mouseX, int mouseY) {
        this.renderArrowButton(guiGraphics, left + PAGE_PREV_X, top + PAGE_PREV_Y,
                this.pageIndex() > 0, mouseX, mouseY, "<");
        this.renderArrowButton(guiGraphics, left + PAGE_NEXT_X, top + PAGE_NEXT_Y,
                this.pageIndex() + 1 < this.pageCount(), mouseX, mouseY, ">"
        );
    }

    private void renderArrowButton(GuiGraphics guiGraphics, int x, int y, boolean active, int mouseX, int mouseY, String label) {
        boolean hovered = this.isPointInside(x, y, BUTTON_SIZE, BUTTON_SIZE, mouseX, mouseY);
        int border = active ? (hovered ? 0xFFF0D4A2 : 0xFFD2AF7E) : 0xFF70553C;
        int fill = active ? (hovered ? 0xC47A5A39 : 0xB059422B) : 0x8A352618;
        guiGraphics.fill(x, y, x + BUTTON_SIZE, y + BUTTON_SIZE, border);
        guiGraphics.fill(x + 1, y + 1, x + BUTTON_SIZE - 1, y + BUTTON_SIZE - 1, fill);
        guiGraphics.drawCenteredString(this.font, label, x + BUTTON_SIZE / 2, y + 2, active ? 0xFFF8EC : 0xFFB49C81);
    }

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

        int visibleStart = this.visibleTableStart();
        int visibleEnd = Math.min(visibleStart + CATALOG_VISIBLE_ROWS, this.tableCount());
        for (int index = visibleStart; index < visibleEnd; index++) {
            int row = index - visibleStart;
            int rowX = this.leftPos + CATALOG_X;
            int rowY = this.topPos + CATALOG_Y + row * CATALOG_ROW_HEIGHT;
            if (this.isPointInside(rowX, rowY, CATALOG_WIDTH, CATALOG_ROW_HEIGHT, mouseX, mouseY)) {
                guiGraphics.renderTooltip(this.font, this.tableAt(index).displayName(), mouseX, mouseY);
                return;
            }
        }
    }

    private void refreshSnapshot() {
        this.snapshot = SpecimenBoxClientState.getSnapshot(this.menu.containerId);
    }

    private int tableCount() {
        return this.snapshot != null ? this.snapshot.tables().size() : 0;
    }

    private int selectedTableIndex() {
        if (this.snapshot == null || this.snapshot.tables().isEmpty()) {
            return -1;
        }
        return Mth.clamp(this.snapshot.selectedTableIndex(), 0, this.snapshot.tables().size() - 1);
    }

    private int pageIndex() {
        if (this.snapshot == null) {
            return 0;
        }
        return Mth.clamp(this.snapshot.pageIndex(), 0, this.pageCount() - 1);
    }

    private int pageCount() {
        return this.snapshot != null ? Math.max(1, this.snapshot.pageCount()) : 1;
    }

    private List<SpecimenBoxClientState.LogicalSlotView> logicalSlots() {
        return this.snapshot != null ? this.snapshot.logicalSlots() : List.of();
    }

    private SpecimenBoxClientState.TableView tableAt(int index) {
        return this.snapshot.tables().get(index);
    }

    private SpecimenBoxClientState.TableView selectedTable() {
        int selectedIndex = this.selectedTableIndex();
        if (selectedIndex < 0) {
            return null;
        }
        return this.tableAt(selectedIndex);
    }

    private boolean sendMenuButton(int buttonId) {
        if (this.minecraft == null || this.minecraft.gameMode == null) {
            return false;
        }
        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
        return true;
    }

    private int visibleTableStart() {
        int tableCount = this.tableCount();
        int selectedIndex = this.selectedTableIndex();
        if (tableCount <= CATALOG_VISIBLE_ROWS || selectedIndex < 0) {
            return 0;
        }
        return Mth.clamp(selectedIndex - CATALOG_VISIBLE_ROWS / 2, 0, tableCount - CATALOG_VISIBLE_ROWS);
    }

    private boolean isPointInside(int x, int y, int width, int height, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private boolean isPointInsideLogicalSlot(int left, int top, int slot, double mouseX, double mouseY) {
        int slotX = slotX(left, slot) - SLOT_FRAME_OFFSET;
        int slotY = slotY(top, slot) - SLOT_FRAME_OFFSET;
        return this.isPointInside(slotX, slotY, SLOT_FRAME_SIZE, SLOT_FRAME_SIZE, mouseX, mouseY);
    }

    private static int slotX(int left, int slot) {
        int column = slot % 3;
        return left + SLOT_GRID_X + column * SLOT_COLUMN_SPACING;
    }

    private static int slotY(int top, int slot) {
        int row = slot / 3;
        return top + SLOT_GRID_Y + row * SLOT_ROW_SPACING;
    }
}
