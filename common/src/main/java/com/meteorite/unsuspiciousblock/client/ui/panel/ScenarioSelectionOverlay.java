package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.overlay.OverlayLayer;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** 场景跳转浮层：按服务端签发顺序列出场景，与页码共用同一选择。 */
final class ScenarioSelectionOverlay implements OverlayLayer.Overlay {
    private static final int ROWS = 7;
    private final OverlayLayer layer;
    private final ScenarioDetailPanel owner;
    private final List<UiControl> controls = new ArrayList<>();
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private int first;
    private long revision = Long.MIN_VALUE;
    private long second = -1;
    private boolean dirty = true;

    ScenarioSelectionOverlay(OverlayLayer layer, ScenarioDetailPanel owner) {
        this.layer = layer;
        this.owner = owner;
        first = Math.max(0, owner.getPage() - ROWS / 2);
    }

    private void sync(Font font) {
        long nextRevision = ArchaeologyJournalClientState.getCatalogRevision();
        long nextSecond = System.currentTimeMillis() / 1000;
        if (!dirty && revision == nextRevision && second == nextSecond) return;
        dirty = false; revision = nextRevision; second = nextSecond;
        var structure = owner.structure();
        var selection = owner.selection();
        controls.clear();
        if (structure == null || structure.options() == null || selection == null || owner.table() == null) {
            layer.close();
            return;
        }
        var scenes = owner.scenes();
        int count = Math.min(ROWS, scenes.size());
        first = Math.clamp(first, 0, Math.max(0, scenes.size() - count));
        int width = 152;
        int height = count * 20 + 20;
        bounds = new UiRect(Math.clamp(owner.dropdownX(), 2, Math.max(2, layer.width() - width - 2)),
                Math.clamp(owner.dropdownY(), 2, Math.max(2, layer.height() - height - 2)), width, height);
        for (int i = 0; i < count; i++) {
            int index = first + i;
            var scene = scenes.get(index);
            var presentation = ScenarioPresentation.resolve(owner.table(), scene.scenarioKey(), selection.params());
            Component label = ScenarioPageBuilder.title(structure.options(), scene.scenarioKey());
            if (scene.scenarioKey().equals(selection.scene())) label = label.copy().withStyle(ChatFormatting.BOLD);
            List<Component> tooltip = new ArrayList<>(ScenarioPageBuilder.titleTooltip(structure.options(), scene.scenarioKey()));
            tooltip.add(ScenarioSimulationClientState.text(presentation.status()));
            if (presentation.status().equals("failed")) {
                String input = new com.meteorite.unsuspiciousblock.loottable.simulation.SimulationInput(
                        scene.scenarioKey(), java.util.Map.of(), selection.params()).key();
                tooltip.add(ScenarioSimulationClientState.text("failure." + ScenarioSimulationClientState.failure(owner.table(), input)));
            }
            UiControl row = new UiControl();
            row.setBounds(bounds.x() + 2, bounds.y() + 2 + i * 20, width - 4, 18);
            row.configure(font, label, UiTextPalette.Parchment.BODY, presentation.badge(), tooltip,
                    () -> { owner.setPage(index); layer.close(); });
            controls.add(row);
        }
        addPageControl(font, "◀", bounds.x() + 2, -ROWS);
        addPageControl(font, "▶", bounds.right() - 24, ROWS);
    }

    private void addPageControl(Font font, String symbol, int x, int delta) {
        UiControl control = new UiControl();
        control.setBounds(x, bounds.bottom() - 17, 22, 15);
        control.configure(font, Component.literal(symbol), UiTextPalette.Parchment.TITLE, null,
                List.of(ScenarioSimulationClientState.text(delta < 0 ? "scene.previous" : "more")),
                () -> { first += delta; dirty = true; });
        controls.add(control);
    }

    @Override public void render(GuiGraphics graphics, Font font, int x, int y, float partialTick) {
        sync(font);
        graphics.fill(bounds.x() - 1, bounds.y() - 1, bounds.right() + 1, bounds.bottom() + 1, 0xFF896C48);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), 0xFFF2E5C6);
        UiTarget hovered = null;
        for (UiControl control : controls) {
            control.render(graphics, font, x, y);
            UiTarget hit = control.hit(x, y);
            if (hit != null) hovered = hit;
        }
        if (hovered != null && !hovered.tooltip().isEmpty()) graphics.renderComponentTooltip(font, hovered.tooltip(), x, y);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        sync(Minecraft.getInstance().font);
        if (!bounds.contains(x, y)) { layer.close(); return true; }
        if (button != 0) return true;
        for (UiControl control : controls) {
            UiTarget target = control.hit(x, y);
            if (target != null && target.action() != null) { target.action().run(); return true; }
        }
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        first -= (int) Math.signum(amount);
        dirty = true;
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) layer.close();
        else if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            owner.changePage(key == GLFW.GLFW_KEY_DOWN ? 1 : -1);
            first = Math.max(0, owner.getPage() - ROWS / 2);
            dirty = true;
        }
        return true;
    }
}
