package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 放大页的灯箱内容适配器：把条件树的独立视图（{@link ScenarioFrameView}）按 {@link UiLightbox.Content}
 * 契约暴露给灯箱外壳，从而复用遮罩、控制栏、适应窗口、焦点与关闭语义。
 *
 * <p>本类不再是浮层：模态接管、ESC/× 与键盘焦点由 {@code UiLightbox} + {@code LightboxOverlay} 负责，
 * 这里只做「视口 → 尺寸 / 绘制 / 缩放 / 平移 / 命中」的映射。视图是独立实例，页内框的平移与缩放不受影响；
 * 工具提示也不再由内容自己绘制——外壳按「控件优先，其次内容命中」统一出唯一 tooltip。</p>
 */
final class ScenarioExpandedOverlay implements UiLightbox.Content {
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

    ScenarioExpandedOverlay(ScenarioDetailPanel owner, Font font) {
        this.owner = owner;
        this.frame = new ScenarioFrameView(font);
        this.tree = owner.tree();
        this.frame.setContent(tree);
        // 灯箱自己提供缩放与适应窗口：关掉框内自带的档位/复位角控件，避免两套控件与两处命中。
        this.frame.hideCornerControls();
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

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        List<UiNode> next = owner.tree();
        if (next != tree) {
            tree = next;
            frame.setContent(tree);
            // 内容换了尺寸也可能换：重新钳制平移，但不改用户选定的缩放档位。
            clampPan();
        }
        // 不画 tooltip：唯一 tooltip 的来源是灯箱外壳（控件优先，其次这里的 hit）。
        frame.render(graphics, mouseX, mouseY);
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

    // 自动适应不超过自然字号；所有树从左上角阅读，手动缩放仍允许更大档位。
    // 档位总数由 restore 的钳制行为反推（kit 未暴露档位表），这样不复制内部常量。
    @Override public void fit() {
        UiTransform transform = frame.transform();
        UiRect visible = visibleViewport();
        int maxIndex = 0;
        while (true) {
            transform.restore(maxIndex + 1, 0, 0);
            if (transform.zoomIndex() != maxIndex + 1) break;
            maxIndex++;
        }
        int chosen = 0;
        for (int index = maxIndex; index >= 0; index--) {
            transform.restore(index, 0, 0);
            if (transform.scale() <= 1.0 && frame.contentWidth() * transform.scale() <= visible.width()
                    && frame.contentHeight() * transform.scale() <= visible.height()) {
                chosen = index;
                break;
            }
        }
        transform.restore(chosen, 0, 0);
        clampPan();
    }

    @Override public boolean panBy(double screenDx, double screenDy) {
        if (!dragging) return false;
        frame.transform().panBy(screenDx, screenDy);
        clampPan();
        return true;
    }

    @Override public boolean mousePressed(double x, double y, int button) {
        if (button != 0 || !visibleViewport().contains(x, y)) return false;
        UiTarget target = frame.hit(x, y);
        // 命中带动作的树节点时照常执行它（与页内一致）；空白处按下则开始拖动平移。
        if (target != null && target.action() != null) {
            target.action().run();
            return true;
        }
        dragging = true;
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button != 0 || !dragging) return false;
        dragging = false;
        return true;
    }

    @Override public int zoomPercent() { return (int) Math.round(frame.transform().scale() * 100); }

    @Override public @Nullable UiTarget hit(double x, double y) { return frame.hit(x, y); }

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
