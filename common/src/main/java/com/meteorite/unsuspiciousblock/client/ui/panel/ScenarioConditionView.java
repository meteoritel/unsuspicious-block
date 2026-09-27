package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.kit.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiDocument;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNineSlice;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNode;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiScrollView;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTransform;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** 页内条件树使用自然字号纵向阅读；复杂条件仍可从页面入口打开灯箱。 */
final class ScenarioConditionView {
    private static final UiNineSlice BORDER = new UiNineSlice(ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/scenario_frame.png"), 24, 8);
    private final UiScrollView scroll = new UiScrollView();
    private final UiDocument document;
    private final Font font;
    private UiRect bounds = new UiRect(0, 0, 1, 1);

    ScenarioConditionView(Font font) {
        this.font = font;
        document = new UiDocument(TextMeasurer.of(font), new UiTransform(), UiTextPalette.Parchment.LABEL, false);
        scroll.setStep(18);
    }

    void setBounds(int x, int y, int width, int height) {
        UiRect next = new UiRect(x, y, width, height);
        if (bounds.equals(next)) return;
        bounds = next;
        scroll.setViewport(x + 7, y + 7, Math.max(1, width - 14), Math.max(1, height - 14));
        layoutDocument();
    }

    void setContent(List<UiNode> content) {
        document.setContent(content);
        scroll.setOffset(0);
        layoutDocument();
    }

    int offset() { return scroll.offset(); }
    void setOffset(int offset) { scroll.setOffset(offset); }

    private void layoutDocument() {
        int width = Math.max(1, scroll.viewport().width() - UiScrollView.SCROLLBAR_WIDTH);
        document.setViewport(0, 0, width, Math.max(1, scroll.viewport().height()));
        int height = document.contentHeight();
        scroll.setContentHeight(height);
        scroll.setScrollbarVisible(scroll.maxOffset() > 0);
        document.setViewport(0, 0, width, Math.max(scroll.viewport().height(), height));
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        BORDER.render(graphics, bounds);
        boolean hovered = scroll.contains(mouseX, mouseY) && !scroll.hitScrollbar(mouseX, mouseY);
        int contentX = hovered ? (int) scroll.toContentX(mouseX) : -1000;
        int contentY = hovered ? (int) scroll.toContentY(mouseY) : -1000;
        scroll.push(graphics);
        try {
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
}
