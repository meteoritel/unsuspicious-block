package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.kit.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiDocument;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNode;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiScrollView;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTransform;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiFocusTarget;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** 页内条件树使用自然字号纵向阅读；复杂条件仍可从页面入口打开灯箱。 */
final class ScenarioConditionView implements UiFocusTarget {
    private List<UiNode> content = List.of();
    private final UiScrollView scroll = new UiScrollView();
    private final UiDocument document;
    private final Font font;
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private final List<ActionRow> actions = new ArrayList<>();
    private boolean focused;
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
        scroll.setViewport(x + 4, y + 4, Math.max(1, width - 8), Math.max(1, height - 8));
        layoutDocument();
    }

    void setContent(List<UiNode> content) {
        this.content = List.copyOf(content);
        scroll.setOffset(0);
        layoutDocument();
    }

    int offset() { return scroll.offset(); }
    void setOffset(int offset) { scroll.setOffset(offset); }

    private void layoutDocument() {
        int width = Math.max(1, scroll.viewport().width() - UiScrollView.SCROLLBAR_WIDTH);
        List<UiNode> wrapped = ScenarioConditionLayout.wrap(content, font, width);
        document.setContent(wrapped);
        actions.clear();
        int top = 0;
        for (UiNode node : wrapped) {
            if (node instanceof UiNode.Row row) {
                int rowHeight = font.lineHeight;
                if (row.leading() != null) rowHeight = Math.max(rowHeight, row.leading().icon().height());
                for (var icon : row.icons()) rowHeight = Math.max(rowHeight, icon.icon().height());
                rowHeight += 4;
                if (row.action() != null) actions.add(new ActionRow(row, new UiRect(0, top, width, rowHeight)));
                top += rowHeight;
            } else if (node instanceof UiNode.Gap(var height)) top += height;
            else if (node instanceof UiNode.Divider) top++;
        }
        actionIndex = Math.clamp(actionIndex, 0, Math.max(0, actions.size() - 1));
        document.setViewport(0, 0, width, Math.max(1, scroll.viewport().height()));
        int height = document.contentHeight();
        scroll.setContentHeight(height);
        scroll.setScrollbarVisible(scroll.maxOffset() > 0);
        document.setViewport(0, 0, width, Math.max(scroll.viewport().height(), height));
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        ScenarioUi.PANEL.render(graphics, bounds);
        boolean hovered = scroll.contains(mouseX, mouseY) && !scroll.hitScrollbar(mouseX, mouseY);
        int contentX = hovered ? (int) scroll.toContentX(mouseX) : -1000;
        int contentY = hovered ? (int) scroll.toContentY(mouseY) : -1000;
        scroll.push(graphics);
        try {
            if (focused && !actions.isEmpty()) {
                UiRect selected = actions.get(actionIndex).rect();
                graphics.fill(selected.x(), selected.y(), selected.right(), selected.bottom(), ScenarioUi.QUIET.selectedBackground());
            }
            document.render(graphics, font, contentX, contentY);
        } finally {
            scroll.pop(graphics);
        }
        scroll.renderScrollbar(graphics, com.meteorite.unsuspiciousblock.client.ui.kit.UiControlStyle.PARCHMENT);
    }

    void renderTooltip(GuiGraphics graphics, int x, int y) {
        if (!scroll.contains(x, y) || scroll.hitScrollbar(x, y)) return;
        UiTarget target = document.hit(scroll.toContentX(x), scroll.toContentY(y));
        if (target != null && !target.tooltip().isEmpty()) {
            graphics.renderComponentTooltip(font, target.tooltip(), x, y);
        }
    }

    boolean mouseClicked(double x, double y, int button) {
        if (!scroll.contains(x, y)) return false;
        focused = false;
        if (scroll.mousePressed(x, y, button)) return true;
        UiTarget target = document.hit(scroll.toContentX(x), scroll.toContentY(y));
        if (target != null && target.action() != null) target.action().run();
        return true;
    }

    boolean mouseDragged(double y) {
        if (!scroll.isDragging()) return false;
        scroll.mouseDragged(y);
        return true;
    }

    boolean mouseReleased() {
        boolean handled = scroll.isDragging();
        scroll.mouseReleased();
        return handled;
    }

    boolean mouseScrolled(double x, double y, double amount) {
        if (!scroll.contains(x, y)) return false;
        scroll.scrollBy(amount);
        return true;
    }

    boolean keyPressed(int key) {
        if (!focused) return false;
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            actionIndex = Math.clamp(actionIndex + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, Math.max(0, actions.size() - 1));
            revealAction();
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            scroll.setOffset(scroll.offset() + (key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * scroll.viewport().height());
            return true;
        }
        return false;
    }

    private void revealAction() {
        if (!actions.isEmpty()) scroll.ensureVisible(actions.get(actionIndex).rect());
    }

    @Override public boolean canFocus() { return !content.isEmpty(); }
    @Override public void setFocused(boolean value) { focused = value; if (value) revealAction(); }
    @Override public boolean isFocused() { return focused; }
    @Override public boolean activate() {
        if (actions.isEmpty()) return false;
        var action = actions.get(actionIndex).row().action();
        if (action == null) return false;
        action.run();
        return true;
    }
    @Override public UiRect bounds() { return bounds; }
    @Override public Component accessibleName() {
        return actions.isEmpty() ? Component.empty() : actions.get(actionIndex).row().text();
    }

    /** 可执行节点在折行后内容坐标内的位置。 */
    private record ActionRow(UiNode.Row row, UiRect rect) {}
}
