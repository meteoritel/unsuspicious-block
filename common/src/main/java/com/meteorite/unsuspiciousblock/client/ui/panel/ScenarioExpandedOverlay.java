package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiDocumentLightboxContent;
import com.meteorite.meteosuikit.api.control.UiLightbox;
import com.meteorite.meteosuikit.api.control.UiStatefulTarget;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiFocusState;
import com.meteorite.meteosuikit.api.input.UiInputContext;
import com.meteorite.meteosuikit.api.input.UiInteractionSession;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.layout.UiLayoutNode;
import com.meteorite.meteosuikit.api.layout.UiLayoutSource;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalRichTooltip;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 场景灯箱的业务文案与内容装配，阅读交互由 kit 持有。
 */
final class ScenarioExpandedOverlay {
    /**
     * 灯箱通用文案：外壳的按钮与 tooltip 全部经由它取本地化文本；实例无状态，可复用。
     */
    static final UiLightbox.Labels LABELS = new UiLightbox.Labels() {
        @Override
        public Component close() {
            return text("close");
        }

        @Override
        public Component zoomIn() {
            return text("zoom_in");
        }

        @Override
        public Component zoomOut() {
            return text("zoom_out");
        }

        @Override
        public Component fit() {
            return ScenarioSession.text("frame.reset");
        }

        @Override
        public Component panLock() {
            return ScenarioSession.text("frame.pan_lock");
        }

        @Override
        public Component panUnlock() {
            return ScenarioSession.text("frame.pan_unlock");
        }

        @Override
        public Component previous() {
            return text("previous");
        }

        @Override
        public Component next() {
            return text("next");
        }

        @Override
        public Component position(int index, int total) {
            return text("position", index, total);
        }
    };

    private ScenarioExpandedOverlay() {
    }

    static UiLightbox.Content create(ScenarioDetailPanel owner, Font font) {
        var content = new UiDocumentLightboxContent(TextMeasurer.of(font), () -> UnsuspiciousUiTheme.PARCHMENT,
                ScenarioSession.text("conditions.heading"));
        content.setContentSource(() -> ScenarioPageBuilder.copyForLightbox(owner.tree()));
        content.setBackground(ScenarioDetailPanel::renderConditionBackground);
        return new RichLightboxContent(content);
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.unsuspiciousblock.lightbox." + key, args);
    }

    /**
     * 委托灯箱内容，在保留全部平移/缩放与焦点行为的前提下升级命中节点的 Tooltip 阴影渲染。
     */
    private record RichLightboxContent(UiDocumentLightboxContent delegate)
            implements UiLightbox.Content, UiStatefulTarget, UiLayoutSource {

        @Override
        public @Nullable UiPointerHit hitTest(double x, double y) {
            return JournalRichTooltip.attachText(delegate.hitTest(x, y));
        }

        @Override public int contentWidth() { return delegate.contentWidth(); }
        @Override public int contentHeight() { return delegate.contentHeight(); }
        @Override public void setViewport(UiRect viewport) { delegate.setViewport(viewport); }
        @Override public void render(GuiGraphics graphics, Font font, UiPointer pointer) { delegate.render(graphics, font, pointer); }
        @Override public boolean zoom(double amount, double anchorX, double anchorY) { return delegate.zoom(amount, anchorX, anchorY); }
        @Override public boolean zoomBy(int direction) { return delegate.zoomBy(direction); }
        @Override public void fit() { delegate.fit(); }
        @Override public void setPanLocked(boolean panLocked) { delegate.setPanLocked(panLocked); }
        @Override public boolean panBy(double screenDx, double screenDy) { return delegate.panBy(screenDx, screenDy); }
        @Override public boolean mousePressed(UiInputContext context) { return delegate.mousePressed(context); }
        @Override public boolean mouseReleased(UiInputContext context) { return delegate.mouseReleased(context); }
        @Override public boolean mouseDragged(UiInputContext context, double dx, double dy) { return delegate.mouseDragged(context, dx, dy); }
        @Override public boolean mouseScrolled(UiInputContext context, double x, double y) { return delegate.mouseScrolled(context, x, y); }
        @Override public boolean keyPressed(int key, int scan, int modifiers) { return delegate.keyPressed(key, scan, modifiers); }
        @Override public boolean keyReleased(int key, int scan, int modifiers) { return delegate.keyReleased(key, scan, modifiers); }
        @Override public boolean charTyped(char codePoint, int modifiers) { return delegate.charTyped(codePoint, modifiers); }
        @Override public void interactionEnded(UiInteractionSession.EndReason reason) { delegate.interactionEnded(reason); }
        @Override public void scopeExited(UiInteractionSession.EndReason reason) { delegate.scopeExited(reason); }
        @Override public int zoomPercent() { return delegate.zoomPercent(); }
        @Override public @Nullable Component placeholder() { return delegate.placeholder(); }
        @Override public void invalidateResources() { delegate.invalidateResources(); }
        @Override public UiFocusState focusState() { return delegate.focusState(); }
        @Override public Component accessibleName() { return delegate.accessibleName(); }
        @Override public boolean activate() { return delegate.activate(); }
        @Override public UiRect bounds() { return delegate.bounds(); }
        @Override public boolean isEnabled() { return delegate.isEnabled(); }
        @Override public boolean isVisible() { return delegate.isVisible(); }
        @Override public UiStatefulTarget setEnabled(boolean value) { delegate.setEnabled(value); return this; }
        @Override public UiStatefulTarget setVisible(boolean value) { delegate.setVisible(value); return this; }
        @Override public void setBounds(int x, int y, int width, int height) { delegate.setBounds(x, y, width, height); }
        @Override public void setLayoutKey(String key) { delegate.setLayoutKey(key); }
        @Override public List<UiLayoutNode> layoutNodes() { return delegate.layoutNodes(); }
    }
}
