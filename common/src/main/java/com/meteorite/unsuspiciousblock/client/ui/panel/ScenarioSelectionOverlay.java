package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiRect;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.overlay.OverlayLayer;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationInput;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * 场景跳转浮层：按服务端签发顺序列出场景，与页码共用同一选择。
 *
 * <p>只吃数据与一个回调，因此场景页的「场景」按钮与网格页头部的「切换场景」按钮共用同一实现——
 * 前者把选择映射到页码（会保存框内视图），后者只切换选择（网格数字随之更新）。</p>
 */
final class ScenarioSelectionOverlay implements OverlayLayer.Overlay {
    private static final int ROWS = 7;
    private static final int ROW_HEIGHT = 20;
    private final OverlayLayer layer;
    private final int anchorX;
    private final int anchorY;
    private final ResourceLocation table;
    private final SimulationOptions options;
    private final List<CatalogTableDto.ScenarioAssumptions> scenes;
    private final ScenarioParams params;
    private final IntConsumer onSelect;
    private final List<UiControl> controls = new ArrayList<>();
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private int first;
    private int current;
    private long revision = Long.MIN_VALUE;
    private long second = -1;
    private boolean dirty = true;

    ScenarioSelectionOverlay(OverlayLayer layer, int anchorX, int anchorY, ResourceLocation table,
                             SimulationOptions options, ScenarioParams params, int currentIndex,
                             IntConsumer onSelect) {
        this.layer = layer;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.table = table;
        this.options = options;
        this.scenes = options.scenes();
        this.params = params;
        this.current = currentIndex;
        this.onSelect = onSelect;
        this.first = Math.max(0, currentIndex - ROWS / 2);
    }

    private void sync(Font font) {
        long nextRevision = ArchaeologyJournalClientState.getCatalogRevision();
        long nextSecond = System.currentTimeMillis() / 1000;
        if (!dirty && revision == nextRevision && second == nextSecond) return;
        dirty = false; revision = nextRevision; second = nextSecond;
        controls.clear();
        if (scenes.isEmpty()) {
            layer.close();
            return;
        }
        int count = Math.min(ROWS, scenes.size());
        first = Math.clamp(first, 0, Math.max(0, scenes.size() - count));
        int width = 152;
        int height = count * ROW_HEIGHT + ROW_HEIGHT;
        bounds = new UiRect(Math.clamp(anchorX, 2, Math.max(2, layer.width() - width - 2)),
                Math.clamp(anchorY, 2, Math.max(2, layer.height() - height - 2)), width, height);
        for (int i = 0; i < count; i++) {
            int index = first + i;
            String sceneKey = scenes.get(index).scenarioKey();
            ScenarioPresentation presentation = ScenarioPresentation.resolve(table, sceneKey, params);
            Component label = ScenarioLabel.label(options, sceneKey);
            if (index == current) label = label.copy().withStyle(ChatFormatting.BOLD);
            List<Component> tooltip = new ArrayList<>(ScenarioLabel.definition(options, sceneKey));
            tooltip.add(ScenarioSimulationClientState.text(presentation.status()));
            if (presentation.status().equals("failed")) {
                String input = new SimulationInput(sceneKey, Map.of(), params).key();
                tooltip.add(ScenarioSimulationClientState.text(
                        "failure." + ScenarioSimulationClientState.failure(table, input)));
            }
            UiControl row = new UiControl();
            row.setBounds(bounds.x() + 2, bounds.y() + 2 + i * ROW_HEIGHT, width - 4, ROW_HEIGHT - 2);
            row.configure(font, label, UiTextPalette.Parchment.BODY, presentation.badge(), tooltip,
                    () -> select(index));
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

    private void select(int index) {
        if (index < 0 || index >= scenes.size()) return;
        current = index;
        onSelect.accept(index);
        layer.close();
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
            int index = Math.clamp(current + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, scenes.size() - 1);
            current = index;
            onSelect.accept(index);
            first = Math.max(0, index - ROWS / 2);
            dirty = true;
        }
        return true;
    }
}
