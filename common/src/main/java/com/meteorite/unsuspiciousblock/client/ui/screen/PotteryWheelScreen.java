package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.PotteryPreviewRenderer;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.pottery.PotteryWheelMenu;
import com.meteorite.unsuspiciousblock.blockentity.PotteryWheelBlockEntity;
import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.render.*;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.PotDecorations;
import org.jetbrains.annotations.NotNull;

/** 纹饰陶轮台界面，右侧放大显示陶罐或复制陶片的结果预览。 */
public class PotteryWheelScreen extends AbstractContainerScreen<PotteryWheelMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/pottery_wheel_gui.png");
    private static final ResourceLocation WATER_BOTTLE_SLOT = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/item/water_bottle_slot.png");
    private static final ResourceLocation CLAY_SLOT = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/item/clay_slot.png");
    private static final float SLOT_GHOST_ALPHA = 0.6F;
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
    private PotteryWheelBlockEntity.ControlMode displayedMode;
    private final UiControl modeButton = new UiControl();
    private final PreviewRotation previewRotation = new PreviewRotation();
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private final UiInputRouter input = new UiInputRouter();
    private final UiInputBridge bridge = new UiInputBridge(input);
    private static final UiIcon BACKGROUND = new UiIcon.Sprite(TEXTURE, 0, 0, 176, 166, 256, 256);

    public PotteryWheelScreen(PotteryWheelMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.titleLabelX = 19;
        this.inventoryLabelY = 72;
        content.add(modeButton); content.add(previewRotation);
        modeButton.setFocusable(true);
        modeButton.setTheme(UnsuspiciousUiTheme.DARK, com.meteorite.meteosuikit.api.style.UiControlRole.SECONDARY);
        scope.setEscape(this::onClose);
    }

    private void prepareUi() {
        modeButton.setBounds(leftPos + 8, topPos + 17, 14, 14);
        updateModeButton();
        if (menu.getPreviewResult().isEmpty()) scope.interaction().end(previewRotation, UiInteractionSession.EndReason.HIDDEN);
        scope.refreshFocus(); input.select(scope);
    }
    private UiInputContext pointer(double x, double y, int button) {
        prepareUi();
        return UiInputContext.of(scope, x, y, button, hasShiftDown(), hasControlDown(), hasAltDown());
    }

    private void updateModeButton() {
        PotteryWheelBlockEntity.ControlMode mode = menu.getControlMode();
        if (mode == displayedMode) return;
        displayedMode = mode;
        String key = switch (mode) {
            case ENABLED -> "screen.unsuspiciousblock.pottery_wheel.control.enabled";
            case DISABLED -> "screen.unsuspiciousblock.pottery_wheel.control.disabled";
            case REDSTONE_ENABLED -> "screen.unsuspiciousblock.pottery_wheel.control.redstone_enabled";
        };
        Component label = Component.translatable(key);
        UiIcon icon = mode == PotteryWheelBlockEntity.ControlMode.REDSTONE_ENABLED
                ? new UiIcon.Item(new ItemStack(Items.REDSTONE))
                : new UiIcon.Sprite(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/toolbar_icons.png"),
                        (mode == PotteryWheelBlockEntity.ControlMode.DISABLED ? 6 : 7) * 9, 9, 9, 9, 81, 27);
        modeButton.configure(font, Component.empty(), icon, List.of(label), () -> {
            menu.setClientControlMode(PotteryWheelBlockEntity.ControlMode.byId(mode.ordinal() + 1));
            if (Minecraft.getInstance().gameMode != null)
                Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, 0);
        });
        modeButton.setAccessibleName(label);
    }

    @Override protected void renderLabels(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        com.meteorite.meteosuikit.api.render.UiFlatText.drawWrapped(graphics, font, title,
                titleLabelX, titleLabelY, imageWidth - titleLabelX - 6, 0x404040);
        com.meteorite.meteosuikit.api.render.UiFlatText.drawWrapped(graphics, font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, imageWidth - inventoryLabelX - 6, 0x404040);
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        BACKGROUND.render(graphics, leftPos, topPos);
        if (!menu.getSlot(4).hasItem()) {
            renderGhostSlot(graphics, CLAY_SLOT, leftPos + 44, topPos + 36);
        }
        if (!menu.getSlot(5).hasItem()) {
            renderGhostSlot(graphics, WATER_BOTTLE_SLOT, leftPos + 89, topPos + 36);
        }
        int progressWidth = menu.getScaledProgress(PROGRESS_WIDTH);
        if (progressWidth > 0) {
            new UiIcon.Sprite(TEXTURE, PROGRESS_TEXTURE_X, PROGRESS_TEXTURE_Y, progressWidth, PROGRESS_HEIGHT, 256, 256)
                    .render(graphics, leftPos + PROGRESS_X, topPos + PROGRESS_Y);
        }
        // 预览必须在槽位和 tooltip 之前绘制，避免物品图标覆盖 tooltip。
        renderPreview(graphics);
    }

    // 使用统一的半透明纹理流程绘制输入槽位提示
    private void renderGhostSlot(GuiGraphics graphics, ResourceLocation texture, int x, int y) {
        graphics.setColor(1.0F, 1.0F, 1.0F, SLOT_GHOST_ALPHA);
        new UiIcon.Sprite(texture, 0, 0, 16, 16, 16, 16).render(graphics, x, y);
        graphics.flush();
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
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
        new UiIcon.Item(preview).renderScaled(graphics,
                new UiRect(leftPos + 137, topPos + 20, 32, 32), UnsuspiciousUiTheme.DARK);
    }

    private UiRect previewBounds() { return new UiRect(leftPos + PREVIEW_X, topPos + PREVIEW_Y, PREVIEW_SIZE, PREVIEW_SIZE); }
    private void renderDecoratedPot(GuiGraphics graphics, ItemStack output) {
        previewRenderer.render(graphics, output, previewBounds(), manualRotation);
    }

    /** 预览旋转只登记 kit 会话；槽位仍由原版父类处理。 */
    private final class PreviewRotation implements UiInteractionSession.Target {
        @Override public UiPointerHit hitTest(double x, double y) {
            return !menu.getPreviewResult().isEmpty() && previewBounds().contains(x, y) ? UiPointerHit.leaf(this, x, y).preservingFocus() : null;
        }
        @Override public boolean mousePressed(UiInputContext context) {
            if (context.button() != 0) return false;
            scope.interaction().begin(this, UiInteractionSession.Source.POINTER, 0); return true;
        }
        @Override public boolean mouseDragged(UiInputContext context, double dx, double dy) {
            manualRotation += (float) dx * 4; return true;
        }
        @Override public boolean mouseReleased(UiInputContext context) { return true; }
        @Override public void interactionEnded(UiInteractionSession.EndReason reason) { }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        return bridge.mousePressed(pointer(x, y, button)) || super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return bridge.mouseDragged(pointer(x, y, button), dx, dy) || super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        return bridge.mouseReleased(pointer(x, y, button)) || super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        return bridge.mouseScrolled(pointer(x, y, -1), sx, sy) || super.mouseScrolled(x, y, sx, sy);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        prepareUi();
        if (bridge.keyPressed(key, scan, modifiers)) return true;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) { onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        prepareUi(); return bridge.keyReleased(key, scan, modifiers) || super.keyReleased(key, scan, modifiers);
    }
    @Override public boolean charTyped(char value, int modifiers) {
        prepareUi(); return bridge.charTyped(value, modifiers) || super.charTyped(value, modifiers);
    }
    @Override public void removed() {
        bridge.close(); UiRenderLayers.releaseModels(); super.removed();
    }
    @Override public void render(@NotNull GuiGraphics graphics, int x, int y, float partialTick) {
        prepareUi();
        super.render(graphics, x, y, partialTick);
        UiPointer pointer = scope.pointer(x, y).resolve(content);
        modeButton.render(graphics, font, pointer);
        if (pointer.hit() == null) renderTooltip(graphics, x, y);
        else if (pointer.allowsTooltip()) pointer.hit().renderTooltip(graphics, font, UnsuspiciousUiTheme.DARK.surfaces(), x, y);
    }
}
