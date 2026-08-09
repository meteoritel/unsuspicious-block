package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.PotteryPreviewRenderer;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.pottery.PotteryWheelMenu;
import com.meteorite.unsuspiciousblock.blockentity.PotteryWheelBlockEntity;
import com.meteorite.unsuspiciousblock.client.ui.widget.PotteryWheelModeButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.PotDecorations;
import org.jetbrains.annotations.NotNull;

/** 纹饰陶轮台界面，右侧放大显示陶罐或复制陶片的结果预览。 */
public class PotteryWheelScreen extends AbstractContainerScreen<PotteryWheelMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/pottery_wheel_gui.png");
    private static final ResourceLocation WATER_BOTTLE_SLOT = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/item/water_bottle_slot.png");
    private static final float SLOT_GHOST_ALPHA = 0.6F;
    private static final int CLAY_GHOST_MASK = 0xDB8B8B8B;
    private static final int PROGRESS_X = 110;
    private static final int PROGRESS_Y = 36;
    private static final int PROGRESS_TEXTURE_X = 176;
    private static final int PROGRESS_TEXTURE_Y = 16;
    private static final int PROGRESS_WIDTH = 20;
    private static final int PROGRESS_HEIGHT = 16;
    private static final int PREVIEW_X = 134;
    private static final int PREVIEW_Y = 17;
    private static final int PREVIEW_SIZE = 38;

    private final PotteryPreviewRenderer previewRenderer = new PotteryPreviewRenderer();
    private float manualRotation;
    private boolean draggingPreview;
    private double previousMouseX;
    private PotteryWheelModeButton modeButton;

    public PotteryWheelScreen(PotteryWheelMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.titleLabelX = 19;
        this.inventoryLabelY = 72;
    }

    @Override
    protected void init() {
        super.init();
        modeButton = addRenderableWidget(new PotteryWheelModeButton(leftPos + 8, topPos + 17, nextMode -> {
            menu.setClientControlMode(nextMode);
            if (Minecraft.getInstance().gameMode != null) {
                Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, 0);
            }
        }));
        updateModeButton();
    }

    private void updateModeButton() {
        if (modeButton == null) return;
        PotteryWheelBlockEntity.ControlMode mode = menu.getControlMode();
        String key = switch (mode) {
            case ENABLED -> "screen.unsuspiciousblock.pottery_wheel.control.enabled";
            case DISABLED -> "screen.unsuspiciousblock.pottery_wheel.control.disabled";
            case REDSTONE_ENABLED -> "screen.unsuspiciousblock.pottery_wheel.control.redstone_enabled";
        };
        modeButton.setMode(mode, Component.translatable(key));
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
        if (!menu.getSlot(4).hasItem()) {
            renderGhostItem(graphics, new ItemStack(Blocks.CLAY), leftPos + 44, topPos + 36);
        }
        if (!menu.getSlot(5).hasItem()) {
            graphics.setColor(1.0F, 1.0F, 1.0F, SLOT_GHOST_ALPHA);
            graphics.blit(WATER_BOTTLE_SLOT, leftPos + 89, topPos + 36, 0, 0, 16, 16, 16, 16);
            graphics.flush();
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        int progressWidth = menu.getScaledProgress(PROGRESS_WIDTH);
        if (progressWidth > 0) {
            graphics.blit(TEXTURE, leftPos + PROGRESS_X, topPos + PROGRESS_Y,
                    PROGRESS_TEXTURE_X, PROGRESS_TEXTURE_Y,
                    progressWidth, PROGRESS_HEIGHT, 256, 256);
        }
        // 预览必须在槽位和 tooltip 之前绘制，避免物品图标覆盖 tooltip。
        renderPreview(graphics);
    }

    // 使用真实黏土方块图标绘制低透明度槽位提示
    private void renderGhostItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 200.0F);
        graphics.fill(x, y, x + 16, y + 16, CLAY_GHOST_MASK);
        graphics.flush();
        graphics.pose().popPose();
    }

    // 陶罐使用 BlockEntityRenderer，陶片使用放大的物品渲染器
    private void renderPreview(GuiGraphics graphics) {
        ItemStack previewResult = menu.getPreviewResult();
        if (previewResult.isEmpty()) return;
        if (previewResult.is(ModItems.UNFIRED_DECORATED_POT)) {
            ItemStack decoratedPotPreview = Items.DECORATED_POT.getDefaultInstance();
            decoratedPotPreview.set(DataComponents.POT_DECORATIONS,
                    previewResult.getOrDefault(DataComponents.POT_DECORATIONS, PotDecorations.EMPTY));
            renderDecoratedPot(graphics, decoratedPotPreview);
            return;
        }
        if (previewResult.is(Items.DECORATED_POT)) {
            renderDecoratedPot(graphics, previewResult);
            return;
        }
        ItemStack preview = previewResult;
        if (previewResult.is(ModItems.UNFIRED_DECORATED_SHERD)) {
            PotDecorations decorations = previewResult.get(DataComponents.POT_DECORATIONS);
            if (decorations != null && !decorations.ordered().isEmpty()) {
                preview = new ItemStack(decorations.ordered().getFirst());
            }
        }
        graphics.pose().pushPose();
        graphics.pose().translate(leftPos + 137, topPos + 20, 0.0F);
        graphics.pose().scale(2.0F, 2.0F, 1.0F);
        graphics.renderItem(preview, 0, 0);
        graphics.pose().popPose();
        // 立即提交预览，防止延迟批次在 tooltip 之后才绘制。
        graphics.flush();
    }

    // 调用原版 DecoratedPotRenderer，保证四面纹饰与实际输出一致
    private void renderDecoratedPot(GuiGraphics graphics, ItemStack output) {
        graphics.enableScissor(leftPos + PREVIEW_X, topPos + PREVIEW_Y,
                leftPos + PREVIEW_X + PREVIEW_SIZE, topPos + PREVIEW_Y + PREVIEW_SIZE);
        previewRenderer.render(graphics, output,
                leftPos + 153, topPos + 50, 24.0F, manualRotation);
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isInsidePreview(mouseX, mouseY)) {
            draggingPreview = true;
            previousMouseX = mouseX;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingPreview && button == 0) {
            manualRotation += (float) ((mouseX - previousMouseX) * 4.0D);
            previousMouseX = mouseX;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingPreview) {
            draggingPreview = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // 预览框仅负责观察，不再承担结果取出交互
    private boolean isInsidePreview(double mouseX, double mouseY) {
        return mouseX >= leftPos + PREVIEW_X && mouseX < leftPos + PREVIEW_X + PREVIEW_SIZE
                && mouseY >= topPos + PREVIEW_Y && mouseY < topPos + PREVIEW_Y + PREVIEW_SIZE;
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateModeButton();
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (modeButton != null) modeButton.renderTooltip(graphics, mouseX, mouseY);
    }

}
