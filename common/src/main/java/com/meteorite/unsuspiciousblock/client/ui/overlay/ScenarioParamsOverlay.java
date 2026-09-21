package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControl;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiTarget;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.loottable.simulation.ToolOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 参数草稿模态：只有确认才提交；除幸运值原生输入框外，按钮、标签和提示均走 kit。 */
public final class ScenarioParamsOverlay implements OverlayLayer.Overlay {
    private static final int PANEL_WIDTH = 276;
    private final OverlayLayer layer;
    private final ResourceLocation table;
    private final String scene;
    private final SimulationOptions options;
    private final List<ToolOption> tools;
    private final List<Integer> samples;
    private final List<ResourceLocation> enchantments;
    private final Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
    private final List<UiControl> controls = new ArrayList<>();
    private String draftLuck;
    private int toolIndex, sampleIndex, firstRow;
    private int panelX, panelY, panelHeight;
    private int lastWidth, lastHeight;
    private boolean dirty = true;
    private boolean draggingLuck;
    private int dragAnchor;
    @Nullable private Font measuredFont;
    @Nullable private EditBox luckBox;
    @Nullable private Component error;

    public ScenarioParamsOverlay(OverlayLayer layer, ResourceLocation table, String scene,
                                 SimulationOptions options, ScenarioParams current) {
        this.layer = layer;
        this.table = table;
        this.scene = scene;
        this.options = options;
        tools = options.tools();
        samples = options.samples();
        List<ResourceLocation> ids = new ArrayList<>(options.enchantments().keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        enchantments = List.copyOf(ids);
        for (int i = 0; i < tools.size(); i++) if (tools.get(i).id().equals(current.toolId())) toolIndex = i;
        sampleIndex = Math.max(0, samples.indexOf(current.sampleCount()));
        for (ResourceLocation id : enchantments) levels.put(id, Math.clamp(current.toolEnchantments().getOrDefault(id, 0),
                0, options.enchantments().get(id)));
        draftLuck = String.format(Locale.ROOT, "%.2f", current.luck());
    }

    private void layout(Font font) {
        if (!dirty && measuredFont == font && lastWidth == layer.width() && lastHeight == layer.height()) return;
        if (luckBox == null || measuredFont != font) {
            luckBox = new EditBox(font, 0, 0, 76, 16, text("params.luck"));
            luckBox.setMaxLength(16);
            luckBox.setValue(draftLuck);
            luckBox.setResponder(value -> draftLuck = value);
        }
        measuredFont = font;
        lastWidth = layer.width(); lastHeight = layer.height(); dirty = false;
        int totalRows = 3 + enchantments.size();
        int visibleRows = Math.clamp((layer.height() - 94) / 20, 1, totalRows);
        firstRow = Math.clamp(firstRow, 0, Math.max(0, totalRows - visibleRows));
        panelHeight = 64 + visibleRows * 20;
        panelX = (layer.width() - PANEL_WIDTH) / 2;
        panelY = (layer.height() - panelHeight) / 2;
        controls.clear();
        control(font, text("params.title"), panelX + 8, panelY + 6, PANEL_WIDTH - 16, 16, null);
        luckBox.visible = false;
        int labelX = panelX + 8;
        int controlX = panelX + 106;
        int controlWidth = PANEL_WIDTH - 114;
        for (int row = firstRow; row < firstRow + visibleRows; row++) {
            int y = panelY + 26 + (row - firstRow) * 20;
            if (row == 0) {
                control(font, text("params.tool"), labelX, y, 96, 16, null);
                control(font, Component.literal("◀"), controlX, y, 16, 16,
                        () -> { toolIndex = cycle(toolIndex, -1, tools.size()); dirty = true; });
                Component name = tools.isEmpty() ? text("unavailable") : tools.get(toolIndex).displayName();
                control(font, name, controlX + 17, y, controlWidth - 34, 16, null);
                control(font, Component.literal("▶"), controlX + controlWidth - 16, y, 16, 16,
                        () -> { toolIndex = cycle(toolIndex, 1, tools.size()); dirty = true; });
            } else if (row == 1) {
                control(font, text("params.luck"), labelX, y, 96, 16, null);
                luckBox.visible = true;
                luckBox.setX(controlX); luckBox.setY(y);
            } else if (row == 2) {
                control(font, text("params.samples"), labelX, y, 96, 16, null);
                int cellWidth = controlWidth / Math.max(1, samples.size());
                for (int i = 0; i < samples.size(); i++) {
                    int index = i;
                    Component label = Component.literal(Integer.toString(samples.get(i)));
                    if (i == sampleIndex) label = label.copy().withStyle(net.minecraft.ChatFormatting.BOLD);
                    control(font, label, controlX + i * cellWidth, y, cellWidth - 2, 16,
                            () -> { sampleIndex = index; dirty = true; });
                }
            } else {
                ResourceLocation id = enchantments.get(row - 3);
                control(font, Component.literal(enchantmentName(id)), labelX, y, 150, 16, null);
                control(font, Component.literal("−"), panelX + 170, y, 20, 16, () -> changeLevel(id, -1));
                control(font, Component.literal(Integer.toString(levels.get(id))), panelX + 192, y, 44, 16, null);
                control(font, Component.literal("+"), panelX + 238, y, 24, 16, () -> changeLevel(id, 1));
            }
        }
        if (!luckBox.visible) { luckBox.setFocused(false); draggingLuck = false; }
        Component hint = error != null ? error : (visibleRows < totalRows ? text("params.scroll_hint") : Component.empty());
        control(font, hint, panelX + 8, panelY + panelHeight - 36, PANEL_WIDTH - 16, 14, null);
        control(font, text("params.cancel"), panelX + 66, panelY + panelHeight - 20, 68, 16, layer::close);
        control(font, text("params.confirm"), panelX + 142, panelY + panelHeight - 20, 68, 16, this::confirm);
    }

    private void control(Font font, Component label, int x, int y, int width, int height,
                         @Nullable com.meteorite.unsuspiciousblock.client.ui.kit.UiAction action) {
        UiControl control = new UiControl();
        control.setBounds(x, y, width, height);
        control.configure(font, label, error != null && label == error ? UiTextPalette.Parchment.NEGATIVE : UiTextPalette.Parchment.BODY,
                null, List.of(label), action);
        controls.add(control);
    }

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        layout(font);
        graphics.fill(0, 0, layer.width(), layer.height(), 0xC0101010);
        graphics.fill(panelX - 1, panelY - 1, panelX + PANEL_WIDTH + 1, panelY + panelHeight + 1, 0xFF896C48);
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xFFF2E5C6);
        UiTarget hovered = null;
        for (UiControl control : controls) {
            control.render(graphics, font, mouseX, mouseY);
            UiTarget target = control.hit(mouseX, mouseY);
            if (target != null) hovered = target;
        }
        if (luckBox != null && luckBox.visible) luckBox.render(graphics, mouseX, mouseY, partialTick);
        if (hovered != null && !hovered.tooltip().isEmpty()) graphics.renderComponentTooltip(font, hovered.tooltip(), mouseX, mouseY);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        layout(Minecraft.getInstance().font);
        draggingLuck = false;
        if (button != 0) return true;
        if (luckBox != null) {
            luckBox.setFocused(luckBox.visible && luckBox.isMouseOver(x, y));
            if (luckBox.isFocused()) {
                draggingLuck = luckBox.mouseClicked(x, y, button);
                dragAnchor = luckBox.getCursorPosition();
                return true;
            }
        }
        for (UiControl control : controls) {
            UiTarget target = control.hit(x, y);
            if (target != null && target.action() != null) { target.action().run(); return true; }
        }
        return true;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (draggingLuck && button == 0 && luckBox != null) {
            luckBox.onClick(x, y);
            luckBox.setHighlightPos(dragAnchor);
        }
        return true;
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) draggingLuck = false;
        return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        firstRow -= (int) Math.signum(amount);
        dirty = true;
        return true;
    }

    private static int cycle(int index, int delta, int size) { return size == 0 ? 0 : Math.floorMod(index + delta, size); }
    private void changeLevel(ResourceLocation id, int delta) {
        levels.put(id, Math.clamp(levels.get(id) + delta, 0, options.enchantments().get(id)));
        dirty = true;
    }

    private void confirm() {
        try {
            if (tools.isEmpty() || samples.isEmpty()) throw new IllegalArgumentException("No options");
            float luck = Float.parseFloat(draftLuck.trim().replace(',', '.'));
            Map<ResourceLocation, Integer> selected = new LinkedHashMap<>();
            levels.forEach((id, level) -> { if (level > 0) selected.put(id, level); });
            ScenarioParams params = new ScenarioParams(luck, tools.get(toolIndex).id(), selected, samples.get(sampleIndex));
            var current = ScenarioSimulationClientState.table(table);
            if (current == null || current.options() == null || current.options().rejects(scene, params)) {
                error = text("failure.rejected_input"); dirty = true; return;
            }
            ScenarioSimulationClientState.select(table, scene, params);
            layer.close();
        } catch (IllegalArgumentException ignored) {
            error = text("luck_invalid");
            dirty = true;
        }
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        layout(Minecraft.getInstance().font);
        if (key == GLFW.GLFW_KEY_ESCAPE) layer.close();
        else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) confirm();
        else if (luckBox != null && luckBox.visible) return luckBox.keyPressed(key, scan, modifiers);
        return true;
    }
    @Override public boolean charTyped(char value, int modifiers) {
        return luckBox != null && luckBox.visible && luckBox.charTyped(value, modifiers);
    }

    // 数据驱动附魔名称由当前客户端注册表提供。
    public static String enchantmentName(ResourceLocation id) {
        var level = Minecraft.getInstance().level;
        if (level == null) return id.toString();
        var enchantment = level.registryAccess().registry(Registries.ENCHANTMENT).map(registry -> registry.get(id)).orElse(null);
        return enchantment == null ? id.toString() : enchantment.description().getString();
    }
    private static Component text(String key) { return ScenarioSimulationClientState.text(key); }
}
