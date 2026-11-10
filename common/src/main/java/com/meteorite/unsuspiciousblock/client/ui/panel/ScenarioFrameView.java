package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.geometry.*;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.layout.*;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import java.util.List;

/** 灯箱条件文档的独立排版与变换，页内阅读位置不受影响。 */
final class ScenarioFrameView {
    private final UiTransform transform = new UiTransform();
    private final UiDocument document;
    private final Font font;
    private List<UiNode> content = List.of();
    private UiRect bounds = new UiRect(0, 0, 1, 1);

    ScenarioFrameView(Font font) {
        this.font = font;
        document = new UiDocument(TextMeasurer.of(font), transform, ScenarioUi.BRANCH, false);
    }
    void setBounds(int x, int y, int width, int height) {
        UiRect next = new UiRect(x, y, width, height);
        if (bounds.equals(next)) return;
        bounds = next;
        document.setViewport(x + 4, y + 4, Math.max(0, width - 8), Math.max(0, height - 8));
        document.setContent(ScenarioConditionLayout.wrap(content, font, Math.max(1, width - 8)));
    }
    void setContent(List<UiNode> value) {
        content = List.copyOf(value);
        document.setContent(ScenarioConditionLayout.wrap(content, font, Math.max(1, bounds.width() - 8)));
    }
    UiTransform transform() { return transform; }
    UiRect contentViewport() { return document.viewport(); }
    int contentWidth() { return document.contentWidth(); }
    int contentHeight() { return document.contentHeight(); }
    List<UiDocumentTarget> actions() { return document.targets().stream().filter(target -> target.hit().action() != null).toList(); }
    void render(GuiGraphics graphics, UiPointer pointer) {
        ScenarioUi.PANEL.render(graphics, bounds);
        document.render(graphics, font, pointer);
    }
    UiDocumentHit hit(double x, double y) { return document.hit(x, y); }
}
