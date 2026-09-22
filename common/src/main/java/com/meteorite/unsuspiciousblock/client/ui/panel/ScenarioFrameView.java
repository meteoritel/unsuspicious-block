package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 页内框与放大窗口共用的呈现和交互；每个实例拥有独立视图，关闭放大窗口不改变页内视图。 */
final class ScenarioFrameView {
    private static final UiNineSlice BORDER = new UiNineSlice(ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/scenario_frame.png"), 24, 8);
    private final UiTransform transform = new UiTransform();
    private final UiControl zoom = new UiControl();
    private final UiControl reset = new UiControl();
    private UiRect bounds = new UiRect(0, 0, 32, 32);
    private final UiDocument document;
    private final Font font;
    private int displayedZoom = -1;
    private boolean dragging;
    private double lastX, lastY;

    ScenarioFrameView(Font font) {
        this.font = font;
        this.document = new UiDocument(TextMeasurer.of(font), transform, UiTextPalette.Parchment.LABEL,
                Services.PLATFORM.isDevelopmentEnvironment() && Boolean.getBoolean("unsuspiciousblock.uiKitDebug"));
        reset.configure(font, Component.literal("↺"), UiTextPalette.Parchment.TITLE, null,
                List.of(ScenarioSimulationClientState.text("frame.reset")), transform::reset);
    }

    void setBounds(int x, int y, int width, int height) {
        if (bounds.x() != x || bounds.y() != y || bounds.width() != width || bounds.height() != height) {
            bounds = new UiRect(x, y, width, height);
            document.setViewport(x + 8, y + 8, Math.max(1, width - 16), Math.max(1, height - 16));
            zoom.setBounds(bounds.right() - 70, bounds.bottom() - 21, 44, 13);
            reset.setBounds(bounds.right() - 25, bounds.bottom() - 21, 17, 13);
        }
    }

    void setContent(List<UiNode> content) { document.setContent(content); }
    FrameState state() { return FrameState.capture(transform); }
    void restore(FrameState state) { state.apply(transform); dragging = false; }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        BORDER.render(graphics, bounds);
        document.render(graphics, font, mouseX, mouseY);
        if (displayedZoom != transform.zoomIndex()) {
            displayedZoom = transform.zoomIndex();
            Component label = ScenarioSimulationClientState.text("frame.zoom", (int) (transform.scale() * 100));
            zoom.configure(font, label, UiTextPalette.Parchment.TITLE, null,
                    transform.zoomIndex() == 0
                            ? List.of(label, ScenarioSimulationClientState.text("frame.overview_hint"), ScenarioSimulationClientState.text("frame.zoom_hint"))
                            : List.of(label, ScenarioSimulationClientState.text("frame.zoom_hint")), () -> {
                        if (transform.zoomIndex() == 3) {
                            for (int i = 0; i < 3; i++) transform.zoomBy(-1, bounds.x() + 8, bounds.y() + 8);
                        } else transform.zoomBy(1, bounds.x() + 8, bounds.y() + 8);
                    });
        }
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        try {
            zoom.render(graphics, font, mouseX, mouseY);
            reset.render(graphics, font, mouseX, mouseY);
        } finally {
            graphics.flush();
            graphics.pose().popPose();
        }
    }

    @Nullable UiTarget hit(double x, double y) {
        if (!bounds.contains(x, y)) return null;
        UiTarget target = zoom.hit(x, y);
        if (target == null) target = reset.hit(x, y);
        return target != null ? target : document.hit(x, y);
    }

    void renderTooltip(GuiGraphics graphics, int x, int y) {
        UiTarget target = hit(x, y);
        if (target != null && !target.tooltip().isEmpty()) graphics.renderComponentTooltip(font, target.tooltip(), x, y);
    }

    boolean contains(double x, double y) { return bounds.contains(x, y); }

    boolean click(double x, double y, int button) {
        if (button != 0 || !contains(x, y)) return false;
        UiTarget target = hit(x, y);
        if (target != null && target.action() != null) target.action().run();
        else { dragging = true; lastX = x; lastY = y; }
        return true;
    }

    boolean drag(double x, double y, int button) {
        if (!dragging || button != 0) return false;
        transform.panBy(x - lastX, y - lastY);
        lastX = x; lastY = y;
        return true;
    }

    boolean release(int button) {
        if (button != 0 || !dragging) return false;
        dragging = false;
        return true;
    }

    boolean scroll(double x, double y, double amount) {
        if (!contains(x, y)) return false;
        transform.zoomBy((int) Math.signum(amount), x, y);
        return true;
    }
}
