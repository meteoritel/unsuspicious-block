package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiScrollView;
import com.meteorite.meteosuikit.api.geometry.*;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.layout.*;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/** 条件文档的单个键盘停靠点；动作与标题几何直接取自 kit 的排版结果。 */
final class ScenarioConditionView implements UiFocusTarget, UiInteractionSession.Target {
    private final UiFocusState focus = new UiFocusState();
    private final UiScrollView scroll = new UiScrollView();
    private final UiDocument document;
    private final Font font;
    private List<UiNode> content = List.of();
    private List<UiDocumentTarget> targets = List.of();
    private List<UiDocumentTarget> actions = List.of();
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private @Nullable UiDocumentHit pressed;
    private int actionIndex;

    ScenarioConditionView(Font font) {
        this.font = font;
        document = new UiDocument(TextMeasurer.of(font), new UiTransform(), ScenarioUi.BRANCH, false);
        scroll.setStep(18);
    }
    void setBounds(int x, int y, int width, int height) {
        UiRect next = new UiRect(x, y, width, height);
        if (bounds.equals(next)) return;
        bounds = next;
        scroll.setViewport(x + 6, y + 6, Math.max(0, width - 12), Math.max(0, height - 12));
        layoutDocument();
    }
    void setContent(List<UiNode> value) { content = List.copyOf(value); scroll.setOffset(0); layoutDocument(); }
    int offset() { return scroll.offset(); }
    void setOffset(int value) { scroll.setOffset(value); }
    private void layoutDocument() {
        int width = Math.max(1, scroll.viewport().width() - UiScrollView.SCROLLBAR_WIDTH);
        document.setViewport(0, 0, width, Math.max(1, scroll.viewport().height()));
        document.setContent(ScenarioConditionLayout.wrap(content, font, width));
        scroll.setContentHeight(document.contentHeight());
        scroll.setScrollbarVisible(scroll.maxOffset() > 0);
        document.setViewport(0, 0, width, Math.max(1, document.contentHeight()));
        targets = document.targets();
        actions = targets.stream().filter(target -> target.hit().action() != null).toList();
        actionIndex = Math.clamp(actionIndex, 0, Math.max(0, actions.size() - 1));
    }
    void render(GuiGraphics graphics, UiPointer incoming) {
        ScenarioUi.PANEL.render(graphics, bounds);
        UiPointer pointer = incoming.forTarget(this).restrict(scroll.contains(incoming.x(), incoming.y())
                && !scroll.hitScrollbar(incoming.x(), incoming.y()));
        UiPointer local = pointer.withPosition(scroll.toContentX(pointer.x()), scroll.toContentY(pointer.y()));
        scroll.push(graphics);
        try {
            for (UiDocumentTarget target : targets) {
                if (target.hit().payload() instanceof ScenarioPageBuilder.Heading heading) {
                    UiRect rect = target.contentBounds();
                    graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(),
                            heading == ScenarioPageBuilder.Heading.SECTION ? 0x40B99A60 : 0x18B99A60);
                }
            }
            if (isFocusVisible() && !actions.isEmpty()) {
                UiRect selected = actions.get(actionIndex).contentBounds();
                graphics.fill(selected.x(), selected.y(), selected.right(), selected.bottom(), ScenarioUi.QUIET.selectedBackground());
            }
            document.render(graphics, font, local);
        } finally { scroll.pop(graphics); }
        scroll.renderScrollbar(graphics, () -> UnsuspiciousUiTheme.PARCHMENT);
    }
    private @Nullable UiDocumentHit documentHit(double x, double y) {
        return scroll.contains(x, y) && !scroll.hitScrollbar(x, y)
                ? document.hit(scroll.toContentX(x), scroll.toContentY(y)) : null;
    }
    @Override public @Nullable UiPointerHit hitTest(double x, double y) {
        if (!bounds.contains(x, y)) return null;
        UiPointerHit bar = scroll.hitTest(x, y);
        if (bar != null) return bar.within(this);
        UiDocumentHit hit = documentHit(x, y);
        UiPointerHit result = UiPointerHit.leaf(this, x, y);
        return hit == null ? result : result.withTextTooltip(hit.tooltip(), bounds);
    }
    @Override public boolean mousePressed(UiInputContext context) {
        if (scroll.mousePressed(context)) return true;
        if (context.button() != 0) return false;
        pressed = documentHit(context.pointerX(), context.pointerY());
        if (pressed != null && pressed.action() != null)
            context.scope().interaction().begin(this, UiInteractionSession.Source.POINTER, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        return true;
    }
    @Override public boolean mouseReleased(UiInputContext context) {
        UiDocumentHit hit = documentHit(context.pointerX(), context.pointerY());
        UiDocumentHit activated = pressed;
        pressed = null;
        if (activated != null && hit == activated && activated.action() != null) activated.action().run();
        return true;
    }
    @Override public void interactionEnded(UiInteractionSession.EndReason reason) { pressed = null; }
    @Override public boolean mouseScrolled(UiInputContext context, double x, double y) {
        if (!bounds.contains(context.pointerX(), context.pointerY())) return false;
        scroll.scrollBy(y); return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (!isFocused()) return false;
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            actionIndex = Math.clamp(actionIndex + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, Math.max(0, actions.size() - 1));
            revealAction(); return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            scroll.setOffset(scroll.offset() + (key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * scroll.viewport().height());
            return true;
        }
        return false;
    }
    private void revealAction() { if (!actions.isEmpty()) scroll.ensureVisible(actions.get(actionIndex).contentBounds()); }
    @Override public UiFocusState focusState() { return focus; }
    @Override public void focusChanged(boolean value) { if (value) revealAction(); }
    @Override public boolean canFocus() { return !content.isEmpty(); }
    @Override public boolean activate() {
        if (actions.isEmpty()) return false;
        java.util.Objects.requireNonNull(actions.get(actionIndex).hit().action()).run(); return true;
    }
    @Override public UiRect bounds() { return bounds; }
    @Override public Component accessibleName() {
        return actions.isEmpty() ? ScenarioSimulationClientState.text("conditions.heading") : actions.get(actionIndex).label();
    }
}
