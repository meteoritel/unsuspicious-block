package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.unsuspiciousblock.client.ui.kit.UiLightbox;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Objects;

/**
 * 灯箱的模态适配器：把不依赖浮层实现的 {@link UiLightbox} 接进 {@link OverlayLayer}，保持
 * 「宿主层 → kit」的单向依赖。宿主打开它即获得遮罩、独占输入与关闭语义。
 */
public final class LightboxOverlay implements OverlayLayer.Overlay {
    private final OverlayLayer layer;
    private final UiLightbox lightbox;

    public LightboxOverlay(OverlayLayer layer, UiLightbox lightbox) {
        this.layer = Objects.requireNonNull(layer);
        this.lightbox = Objects.requireNonNull(lightbox);
    }

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        lightbox.setBounds(layer.width(), layer.height());
        lightbox.render(graphics, font, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        return lightbox.mouseClicked(x, y, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        return lightbox.mouseDragged(x, y, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        return lightbox.mouseReleased(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        return lightbox.mouseScrolled(x, y, amount);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return lightbox.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean charTyped(char codePoint, int modifiers) {
        return lightbox.charTyped(codePoint, modifiers);
    }

    @Override public void closed() {
        lightbox.onClosed();
    }
}
