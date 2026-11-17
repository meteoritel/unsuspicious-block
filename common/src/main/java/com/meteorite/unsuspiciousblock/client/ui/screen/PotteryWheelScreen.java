package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiIconLightboxContent;
import com.meteorite.meteosuikit.api.control.UiLightbox;
import com.meteorite.meteosuikit.api.control.UiScreenBridge;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.geometry.UiViewport;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.render.UiFlatText;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.render.UiRenderLayers;
import com.meteorite.meteosuikit.api.render.UiTooltipPresenter;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.PotteryPreviewRenderer;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.blockentity.PotteryWheelBlockEntity;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import com.meteorite.unsuspiciousblock.core.pottery.PotteryWheelMenu;
import net.minecraft.Util;
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
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;

/**
 * 纹饰陶轮台界面，右侧放大显示陶罐或复制陶片的结果预览。
 */
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
    private static final double PREVIEW_CLICK_DRAG_THRESHOLD = 2.0D;
    private static final float PREVIEW_ROTATION_PER_PIXEL = 4;

    private final PotteryPreviewRenderer previewRenderer = new PotteryPreviewRenderer();
    private float manualRotation;
    private PotteryWheelBlockEntity.ControlMode displayedMode;
    private final UiControl modeButton = new UiControl();
    private final PreviewRotation previewRotation = new PreviewRotation();
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private final UiInputRouter input = new UiInputRouter();
    private final UiScreenBridge bridge = new UiScreenBridge(input, new UiScreenBridge.Host() {
        @Override
        public UiInputScope inputScope() {
            return activeScope();
        }

        @Override
        public UiViewport viewport() {
            return new UiViewport(1, width, height, new UiRect(0, 0, width, height));
        }

        @Override
        public void prepareUi() {
            PotteryWheelScreen.this.prepareUi();
        }

        @Override
        public void layoutUi() {
            PotteryWheelScreen.this.layoutUi();
        }

        @Override
        public void invalidateUiResources() {
            PotteryWheelScreen.this.invalidateUiResources();
        }
    });
    private static final UiIcon BACKGROUND = new UiIcon.Sprite(TEXTURE, 0, 0, 176, 166, 256, 256);
    private static final UiLightbox.Labels LIGHTBOX_LABELS = new UiLightbox.Labels() {
        @Override
        public Component close() {
            return lightboxText("close");
        }

        @Override
        public Component zoomIn() {
            return lightboxText("zoom_in");
        }

        @Override
        public Component zoomOut() {
            return lightboxText("zoom_out");
        }

        @Override
        public Component fit() {
            return lightboxText("fit");
        }

        @Override
        public Component panLock() {
            return lightboxText("pan_lock");
        }

        @Override
        public Component panUnlock() {
            return lightboxText("pan_unlock");
        }

        @Override
        public Component previous() {
            return lightboxText("previous");
        }

        @Override
        public Component next() {
            return lightboxText("next");
        }

        @Override
        public Component position(int index, int total) {
            return lightboxText("position", index, total);
        }
    };
    private static final Map<String, UiIcon> LIGHTBOX_ICONS = Map.of(
            "close", UiAtlas.icon(UiAtlas.Icon.CLOSE),
            "fit", UiAtlas.icon(UiAtlas.Icon.RESET),
            "zoom_in", UiAtlas.icon(UiAtlas.Icon.PLUS),
            "zoom_out", UiAtlas.icon(UiAtlas.Icon.MINUS));

    private UiLightbox lightbox;
    private ItemStack lightboxSource = ItemStack.EMPTY;

    public PotteryWheelScreen(PotteryWheelMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.titleLabelX = 19;
        this.inventoryLabelY = 72;
        content.add(modeButton);
        content.add(previewRotation);
        modeButton.setFocusable(true);
        modeButton.setTheme(() -> UnsuspiciousUiTheme.DARK, UiControlRole.SECONDARY);
        scope.setEscape(this::onClose);
        scope.setNavigationBack(this::onClose);
    }

    @Override
    protected void init() {
        super.init();
        scope.interaction().end(previewRotation, UiInteractionSession.EndReason.CANCEL);
        bridge.refresh();
    }

    private void prepareUi() {
        updateModeButton();
        ItemStack result = menu.getPreviewResult();
        if (lightbox != null && !ItemStack.isSameItemSameComponents(result, lightboxSource)) closePreviewLightbox();
        if (scope.interaction().owns(previewRotation)
                && !ItemStack.isSameItemSameComponents(result, previewRotation.source)) {
            scope.interaction().end(previewRotation, UiInteractionSession.EndReason.UNMOUNTED);
        }
    }

    private void layoutUi() {
        modeButton.setBounds(leftPos + 8, topPos + 17, 14, 14);
        if (lightbox != null) lightbox.prepare(font, width, height);
    }

    private void invalidateUiResources() {
        displayedMode = null;
        if (lightbox != null) {
            lightbox.setText(previewStack(lightboxSource).getHoverName(), null);
            lightbox.invalidateResources();
        }
    }

    private UiInputScope activeScope() {
        return lightbox == null ? scope : lightbox.inputScope();
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
        UiIcon icon = switch (mode) {
            case ENABLED -> UiAtlas.icon(UiAtlas.Icon.POTTERY_ENABLED);
            case DISABLED -> UiAtlas.icon(UiAtlas.Icon.POTTERY_DISABLED);
            case REDSTONE_ENABLED -> UiAtlas.icon(UiAtlas.Icon.POTTERY_REDSTONE);
        };
        modeButton.configure(font, Component.empty(), icon, List.of(label), () -> {
            menu.setClientControlMode(PotteryWheelBlockEntity.ControlMode.byId(mode.ordinal() + 1));
            if (Minecraft.getInstance().gameMode != null)
                Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, 0);
        });
        modeButton.setAccessibleName(label);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        UiFlatText.drawWrapped(graphics, font, title,
                titleLabelX, titleLabelY, imageWidth - titleLabelX - 6, 0x404040);
        UiFlatText.drawWrapped(graphics, font, playerInventoryTitle,
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
        UiIcon preview = createPreviewIcon(previewResult, manualRotation);
        if (isDecoratedPotPreview(previewResult)) {
            preview.render(graphics, previewBounds().x(), previewBounds().y());
            return;
        }
        preview.renderScaled(graphics,
                new UiRect(leftPos + 137, topPos + 20, 32, 32), () -> UnsuspiciousUiTheme.DARK);
    }

    private UiRect previewBounds() {
        return new UiRect(leftPos + PREVIEW_X, topPos + PREVIEW_Y, PREVIEW_SIZE, PREVIEW_SIZE);
    }

    private UiIcon createPreviewIcon(ItemStack previewResult, float rotation) {
        ItemStack preview = previewStack(previewResult);
        if (preview.is(Items.DECORATED_POT)) {
            ItemStack renderedPreview = preview.copy();
            return new UiIcon.Rendered(PREVIEW_SIZE, PREVIEW_SIZE,
                    (graphics, bounds) -> previewRenderer.render(graphics, renderedPreview, bounds, rotation));
        }
        return new UiIcon.Item(preview);
    }

    private ItemStack previewStack(ItemStack previewResult) {
        if (previewResult.is(ModItems.UNFIRED_DECORATED_POT)) {
            ItemStack decoratedPotPreview = Items.DECORATED_POT.getDefaultInstance();
            decoratedPotPreview.set(DataComponents.POT_DECORATIONS,
                    previewResult.getOrDefault(DataComponents.POT_DECORATIONS, PotDecorations.EMPTY));
            return decoratedPotPreview;
        }
        if (previewResult.is(Items.DECORATED_POT)) return previewResult;
        if (previewResult.is(ModItems.UNFIRED_DECORATED_SHERD)) {
            PotDecorations decorations = previewResult.get(DataComponents.POT_DECORATIONS);
            if (decorations != null && !decorations.ordered().isEmpty()) {
                return new ItemStack(decorations.ordered().getFirst());
            }
        }
        return previewResult;
    }

    private boolean isDecoratedPotPreview(ItemStack previewResult) {
        return previewResult.is(ModItems.UNFIRED_DECORATED_POT) || previewResult.is(Items.DECORATED_POT);
    }

    private void openPreviewLightbox() {
        ItemStack previewResult = menu.getPreviewResult();
        if (previewResult.isEmpty()) return;
        ItemStack preview = previewStack(previewResult).copy();
        UiIconLightboxContent content = new UiIconLightboxContent(
                createPreviewIcon(previewResult, manualRotation), () -> UnsuspiciousUiTheme.DARK);
        UiLightbox next = new UiLightbox(LIGHTBOX_LABELS, content, this::closePreviewLightbox);
        next.setTheme(() -> UnsuspiciousUiTheme.DARK);
        next.setIcons(LIGHTBOX_ICONS);
        next.setText(preview.getHoverName(), null);
        next.setMaskClickCloses(false);
        next.inputScope().setEscape(this::closePreviewLightbox);
        next.inputScope().setNavigationBack(this::closePreviewLightbox);
        lightbox = next;
        lightboxSource = previewResult.copy();
    }

    private void closePreviewLightbox() {
        UiLightbox current = lightbox;
        if (current == null) return;
        lightbox = null;
        lightboxSource = ItemStack.EMPTY;
        current.onClosed();
    }

    private static Component lightboxText(String key, Object... args) {
        return Component.translatable("screen.unsuspiciousblock.lightbox." + key, args);
    }

    /**
     * 预览拖动与点击通过 kit 会话路由；槽位仍由原版父类处理。
     */
    private final class PreviewRotation implements UiInteractionSession.Target {
        private double pressX;
        private double pressY;
        private boolean dragged;
        private ItemStack source = ItemStack.EMPTY;

        @Override
        public UiPointerHit hitTest(double x, double y) {
            return !menu.getPreviewResult().isEmpty() && previewBounds().contains(x, y) ? UiPointerHit.leaf(this, x, y).preservingFocus() : null;
        }

        @Override
        public boolean mousePressed(UiInputContext context) {
            if (context.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
            pressX = context.pointerX();
            pressY = context.pointerY();
            dragged = false;
            source = menu.getPreviewResult().copy();
            scope.interaction().begin(this, UiInteractionSession.Source.POINTER, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return true;
        }

        @Override
        public boolean mouseDragged(UiInputContext context, double dx, double dy) {
            updateDragDistance(context);
            if (dragged) manualRotation += (float) dx * PREVIEW_ROTATION_PER_PIXEL;
            return true;
        }

        @Override
        public boolean mouseReleased(UiInputContext context) {
            updateDragDistance(context);
            if (!dragged && previewBounds().contains(context.pointerX(), context.pointerY())
                    && ItemStack.isSameItemSameComponents(source, menu.getPreviewResult())) openPreviewLightbox();
            return true;
        }

        private void updateDragDistance(UiInputContext context) {
            double movedX = context.pointerX() - pressX;
            double movedY = context.pointerY() - pressY;
            dragged |= movedX * movedX + movedY * movedY >= PREVIEW_CLICK_DRAG_THRESHOLD * PREVIEW_CLICK_DRAG_THRESHOLD;
        }

        @Override
        public void interactionEnded(UiInteractionSession.EndReason reason) {
            dragged = false;
            source = ItemStack.EMPTY;
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return bridge.mousePressed(x, y, button) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return bridge.mouseDragged(x, y, button, dx, dy) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return bridge.mouseReleased(x, y, button) || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        return bridge.mouseScrolled(x, y, sx, sy) || super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        return bridge.keyPressed(key, scan, modifiers) || super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        return bridge.keyReleased(key, scan, modifiers) || super.keyReleased(key, scan, modifiers);
    }

    @Override
    public boolean charTyped(char value, int modifiers) {
        return bridge.charTyped(value, modifiers) || super.charTyped(value, modifiers);
    }

    @Override
    public void removed() {
        bridge.close();
        closePreviewLightbox();
        super.removed();
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int x, int y, float partialTick) {
        bridge.beginFrame(Util.getMillis());
        super.render(graphics, x, y, partialTick);
        UiPointer pointer = scope.pointer(x, y).resolve(content);
        UiRenderLayers.draw(graphics, UiRenderLayers.FOREGROUND,
                () -> modeButton.render(graphics, font, pointer));
        if (lightbox == null) {
            if (pointer.allowsTooltip() && (pointer.hit() == null || pointer.hit().owners().isEmpty())) {
                renderTooltip(graphics, x, y);
            } else if (pointer.allowsTooltip()) {
                UiTooltipPresenter.render(graphics, font, pointer.hit(), UnsuspiciousUiTheme.DARK.surfaces(), x, y);
            }
        } else {
            lightbox.layer().render(graphics, font, x, y, 0);
        }
    }
}
