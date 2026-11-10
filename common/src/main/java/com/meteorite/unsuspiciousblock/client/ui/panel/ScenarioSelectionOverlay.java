package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.render.UiLayer;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays;
import com.meteorite.unsuspiciousblock.client.ui.support.*;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.*;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.function.IntConsumer;

/** 服务端场景顺序的 kit 选择列表；浏览不提交，激活后选择并关闭。 */
final class ScenarioSelectionOverlay implements JournalOverlays.Surface, UiInputContainer {
    private final JournalOverlays owner;
    private final UiChildren<UiInputTarget> children = new UiChildren<>();
    private final UiOverlay overlay = new UiOverlay(this);
    private final UiListView<CatalogTableDto.ScenarioAssumptions> scenes;
    private final SimulationOptions options;
    private final UiLayer layer;
    private UiRect bounds = new UiRect(0, 0, 1, 1);

    ScenarioSelectionOverlay(JournalOverlays owner, ResourceLocation table, SimulationOptions options,
                             ScenarioParams params, int current, IntConsumer select) {
        this.owner = owner;
        this.options = options;
        scenes = new UiListView<>(UnsuspiciousUiTheme.PARCHMENT, (graphics, font, scene, index, row, selected, hovered, focused) -> {
            Component name = ScenarioLabel.shortLabel(scene.scenarioKey());
            ScenarioPresentation.resolve(table, scene.scenarioKey(), params).badge().render(graphics, row.right() - 12, row.y() + 3);
            graphics.drawString(font, name, row.x() + 3, row.y() + 3, UiTextPalette.Parchment.TITLE, false);
            var detail = font.split(ScenarioLabel.detailLabel(options, scene.scenarioKey()), Math.max(1, row.width() - 6));
            if (!detail.isEmpty()) graphics.drawString(font, detail.getFirst(), row.x() + 3, row.y() + 17, UiTextPalette.Parchment.BODY, false);
            Component status = ScenarioSimulationClientState.text(ScenarioPresentation.resolve(table, scene.scenarioKey(), params).status());
            graphics.drawString(font, status, row.x() + 3, row.y() + 29, UiTextPalette.Parchment.LABEL, false);
        });
        scenes.setItems(options.scenes()).setRowHeight(44).setSelectedIndex(current);
        scenes.setActivateOnSingleClick(true);
        scenes.setOnActivate(scene -> { select.accept(options.scenes().indexOf(scene)); owner.close(); });
        children.add(scenes);
        layer = new UiLayer(overlay.inputScope(), (graphics, font, pointer, depth) ->
            overlay.render(graphics, new UiRect(0, 0, owner.width(), owner.height()), depth, 0x88000000,
                    () -> {
                        ScenarioUi.PANEL.render(graphics, bounds);
                        graphics.drawString(font, ScenarioSimulationClientState.text("scene.toggle"), bounds.x() + 8,
                                bounds.y() + 8, UiTextPalette.Parchment.TITLE, false);
                        scenes.render(graphics, font, pointer);
                        if (pointer.allowsTooltip() && pointer.hit() != null)
                            pointer.hit().renderTooltip(graphics, font, UnsuspiciousUiTheme.PARCHMENT.surfaces(), (int) pointer.x(), (int) pointer.y());
                    }), null);

    }
    @Override public UiPointerHit hitTest(double x, double y) {
        UiPointerHit hit = children.hitTest(x, y);
        if (hit == null) return null;
        int index = hit.rowIndex(scenes);
        return (index < 0 ? hit : hit.withTextTooltip(ScenarioLabel.tooltip(options, scenes.items().get(index).scenarioKey()), scenes.bounds())).within(this);
    }
    @Override public UiChildren<UiInputTarget> inputChildren() { return children; }
    @Override public UiLayer layer() { return layer; }
    @Override public void prepare(Font font) {
        int width = Math.clamp(owner.width() - 20, 0, 300);
        int height = Math.clamp(owner.height() - 20, 0, 270);
        bounds = new UiRect((owner.width() - width) / 2, (owner.height() - height) / 2, width, height);
        scenes.setBounds(bounds.x() + 6, bounds.y() + 25, Math.max(0, width - 12), Math.max(0, height - 31));
    }
}
