package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import com.meteorite.unsuspiciousblock.client.ui.overlay.OverlayLayer;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** 居中放大框；复制打开时的页内视图，使用同一框实现，关闭时丢弃窗口内的临时平移与缩放。 */
final class ScenarioExpandedOverlay implements OverlayLayer.Overlay {
    private final OverlayLayer layer;
    private final ScenarioDetailPanel owner;
    private final ScenarioFrameView frame;
    private final UiControl close = new UiControl();
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private List<UiNode> tree = List.of();

    ScenarioExpandedOverlay(OverlayLayer layer, ScenarioDetailPanel owner, Font font, FrameState state) {
        this.layer = layer;
        this.owner = owner;
        frame = new ScenarioFrameView(font);
        frame.restore(state);
        close.configure(font, Component.literal("×"), UiTextPalette.Parchment.TITLE, null,
                List.of(ScenarioSimulationClientState.text("overlay.close")), layer::close);
        layout();
    }

    private void layout() {
        int width = Math.max(152, layer.width() * 3 / 4);
        int height = Math.max(166, layer.height() - 32);
        int x = (layer.width() - width) / 2;
        int y = (layer.height() - height) / 2;
        if (bounds.x() != x || bounds.y() != y || bounds.width() != width || bounds.height() != height) {
            bounds = new UiRect(x, y, width, height);
            frame.setBounds(x, y, width, height);
            close.setBounds(bounds.right() - 24, bounds.y() + 8, 16, 14);
        }
        if (tree != owner.tree()) { tree = owner.tree(); frame.setContent(tree); }
    }

    @Override public void render(GuiGraphics graphics, Font font, int x, int y, float partialTick) {
        layout();
        graphics.fill(0, 0, layer.width(), layer.height(), 0xC0101010);
        graphics.flush();
        frame.render(graphics, x, y);
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        try {
            close.render(graphics, font, x, y);
        } finally {
            graphics.flush();
            graphics.pose().popPose();
        }
        UiTarget target = close.hit(x, y);
        if (target != null) graphics.renderComponentTooltip(font, target.tooltip(), x, y);
        else frame.renderTooltip(graphics, x, y);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (!bounds.contains(x, y)) { layer.close(); return true; }
        UiTarget target = close.hit(x, y);
        if (button == 0 && target != null && target.action() != null) { target.action().run(); return true; }
        return frame.click(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return frame.drag(x, y, button); }
    @Override public boolean mouseReleased(double x, double y, int button) { return frame.release(button); }
    @Override public boolean mouseScrolled(double x, double y, double amount) { return frame.scroll(x, y, amount); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) layer.close();
        return true;
    }
}
