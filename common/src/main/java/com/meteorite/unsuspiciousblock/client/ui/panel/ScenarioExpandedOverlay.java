package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.*;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.layout.*;
import com.meteorite.meteosuikit.api.render.*;
import com.meteorite.meteosuikit.api.style.*;
import com.meteorite.meteosuikit.api.text.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import org.lwjgl.glfw.GLFW;

/** 条件文档灯箱内容：独立缩放、平移和动作导航，外壳直接使用 kit 层。 */
final class ScenarioExpandedOverlay implements UiLightbox.Content, UiFocusTarget {
    /** 灯箱通用文案：外壳的按钮与 tooltip 全部经由它取本地化文本；实例无状态，可复用。 */
    static final UiLightbox.Labels LABELS = new UiLightbox.Labels() {
        @Override public Component close() { return text("close"); }
        @Override public Component zoomIn() { return text("zoom_in"); }
        @Override public Component zoomOut() { return text("zoom_out"); }
        @Override public Component fit() { return text("fit"); }
        @Override public Component previous() { return text("previous"); }
        @Override public Component next() { return text("next"); }
        @Override public Component position(int index, int total) { return text("position", index, total); }
    };

    private final ScenarioDetailPanel owner;
    private final ScenarioFrameView frame;
    private List<UiNode> tree;
    private UiRect viewport = new UiRect(0, 0, 1, 1);
    /** 内容区拖动状态：只有按下后拖动中才让 panBy 生效，避免点击与拖动互相误触。 */
    private boolean dragging;
    private final UiFocusState focus = new UiFocusState();
    private UiDocumentHit pressed;
    private int actionIndex;

    ScenarioExpandedOverlay(ScenarioDetailPanel owner, Font font) {
        this.owner = owner;
        this.frame = new ScenarioFrameView(font);
        this.tree = owner.tree();
        this.frame.setContent(tree);
    }

    // 文案 key 前缀由灯箱外壳约定，kit 自身不持有任何文案。
    private static Component text(String key, Object... args) {
        return Component.translatable("screen.unsuspiciousblock.lightbox." + key, args);
    }

    // ---------- 内容映射 ----------

    @Override public int contentWidth() { return frame.contentWidth(); }

    @Override public int contentHeight() { return frame.contentHeight(); }

    @Override public void setViewport(UiRect viewport) {
        this.viewport = viewport;
        frame.setBounds(viewport.x(), viewport.y(), viewport.width(), viewport.height());
        // 视口变化只重新钳制、不 refit：外壳在首次布局与换内容后才调一次 fit()，resize 要保留用户缩放。
        clampPan();
    }

    @Override public void render(GuiGraphics graphics, Font font, UiPointer pointer) {
        List<UiNode> next = owner.tree();
        if (next != tree) {
            tree = next;
            frame.setContent(tree);
            // 内容换了尺寸也可能换：重新钳制平移，但不改用户选定的缩放档位。
            clampPan();
        }
        // 不画 tooltip：唯一 tooltip 的来源是灯箱外壳（控件优先，其次这里的 hit）。
        frame.render(graphics, pointer);
    }

    @Override public boolean zoom(double amount, double anchorX, double anchorY) {
        if (amount == 0) return false;
        int previous = frame.transform().zoomIndex();
        frame.transform().zoomBy((int) Math.signum(amount), anchorX, anchorY);
        clampPan();
        return previous != frame.transform().zoomIndex();
    }

    // 无锚点的缩放（灯箱按钮）以视口中心为锚点：等价于围绕可见内容中心放大/缩小。
    @Override public boolean zoomBy(int direction) {
        if (direction == 0) return false;
        double centerX = viewport.x() + viewport.width() / 2.0;
        double centerY = viewport.y() + viewport.height() / 2.0;
        int previous = frame.transform().zoomIndex();
        frame.transform().zoomBy(direction, centerX, centerY);
        clampPan();
        return previous != frame.transform().zoomIndex();
    }

    @Override public void fit() {
        frame.transform().fit(0, 0, contentWidth(), contentHeight(), 0);
        if (frame.transform().scale() > 1) frame.transform().reset();
        frame.transform().restore(frame.transform().zoomIndex(), 0, 0);
        clampPan();
    }

    @Override public boolean panBy(double screenDx, double screenDy) {
        if (!dragging) return false;
        frame.transform().panBy(screenDx, screenDy);
        clampPan();
        return true;
    }

    @Override public boolean mousePressed(UiInputContext context) {
        if (context.button() != 0 || !visibleViewport().contains(context.pointerX(), context.pointerY())) return false;
        pressed = frame.hit(context.pointerX(), context.pointerY());
        dragging = pressed == null || pressed.action() == null;
        context.scope().interaction().begin(this, UiInteractionSession.Source.POINTER, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        return true;
    }
    @Override public boolean mouseDragged(UiInputContext context, double dx, double dy) { return panBy(dx, dy); }
    @Override public boolean mouseReleased(UiInputContext context) {
        UiDocumentHit hit = frame.hit(context.pointerX(), context.pointerY());
        UiDocumentHit action = pressed;
        pressed = null; dragging = false;
        if (action != null && action == hit && action.action() != null) action.action().run();
        return true;
    }
    @Override public void interactionEnded(UiInteractionSession.EndReason reason) { dragging = false; pressed = null; }
    @Override public int zoomPercent() { return frame.transform().scalePercent(); }
    @Override public @Nullable UiPointerHit hitTest(double x, double y) {
        if (!visibleViewport().contains(x, y)) return null;
        UiDocumentHit hit = frame.hit(x, y);
        UiPointerHit result = UiPointerHit.leaf(this, x, y);
        return hit == null ? result : result.withTextTooltip(hit.tooltip(), viewport);
    }
    @Override public UiFocusState focusState() { return focus; }
    @Override public UiRect bounds() { return viewport; }
    @Override public Component accessibleName() {
        var actions = frame.actions();
        return actions.isEmpty() ? com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState.text("conditions.heading")
                : actions.get(Math.min(actionIndex, actions.size() - 1)).label();
    }
    @Override public boolean activate() {
        var actions = frame.actions();
        if (actions.isEmpty()) return false;
        java.util.Objects.requireNonNull(actions.get(Math.min(actionIndex, actions.size() - 1)).hit().action()).run();
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (!isFocused()) return false;
        var actions = frame.actions();
        if (key != org.lwjgl.glfw.GLFW.GLFW_KEY_UP && key != org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) return false;
        if (actions.isEmpty()) return true;
        actionIndex = Math.clamp(actionIndex + (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, actions.size() - 1);
        UiRect selected = actions.get(actionIndex).contentBounds();
        frame.transform().restore(frame.transform().zoomIndex(), frame.transform().panX(), -selected.y());
        clampPan();
        return true;
    }

    // 条件树不依赖任何纹理资源：不存在「缺图」占位。
    @Override public @Nullable Component placeholder() { return null; }

    // 资源重载不需要丢弃派生缓存：树的内容与尺寸由文档自身在内容版本变化时重排，这里沿用默认 no-op。

    // ---------- 平移钳制 ----------

    // 真正可见的内容区是框内 8 像素边的内侧（document.viewport）；未布局时退回外壳给的视口。
    private UiRect visibleViewport() {
        UiRect inner = frame.contentViewport();
        return inner.width() > 0 && inner.height() > 0 ? inner : viewport;
    }

    // 灯箱模式下的平移钳制：小内容锁定左上角，大内容限制在边缘内。
    private void clampPan() {
        UiTransform transform = frame.transform();
        UiRect visible = visibleViewport();
        double vw = Math.max(1, visible.width());
        double vh = Math.max(1, visible.height());
        double scale = transform.scale();
        double panX = clampAxis(transform.panX(), frame.contentWidth(), vw, scale);
        double panY = clampAxis(transform.panY(), frame.contentHeight(), vh, scale);
        transform.restore(transform.zoomIndex(), panX, panY);
    }

    // 单轴：可见内容范围 X = [-pan, vw/s - pan]，内容占 [0, cw]；故 cw*s > vw 时 pan ∈ [vw/s - cw, 0]。
    // 该轴放不下时不居中，保留 pan=0（左上角），只保证拖动过程不露出内容边界外的空白。
    private static double clampAxis(double pan, double content, double viewportSize, double scale) {
        double visible = viewportSize / scale;
        if (content * scale <= viewportSize) return 0;
        return Math.clamp(pan, visible - content, 0.0);
    }
}
