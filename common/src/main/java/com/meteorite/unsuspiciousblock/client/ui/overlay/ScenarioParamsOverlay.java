package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.meteosuikit.api.style.UiTextScheme;

import com.meteorite.meteosuikit.api.style.UiSliderRole;

import com.meteorite.meteosuikit.api.input.UiAction;

import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.number.*;
import com.meteorite.meteosuikit.api.render.*;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGateAnalysis;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import java.util.*;

/** 场景参数草稿；kit 负责数值编辑和列表导航，显式应用后才修改模拟选择。 */
public final class ScenarioParamsOverlay implements JournalOverlays.Surface, UiInputContainer {
    private final JournalOverlays owner;
    private final ResourceLocation table;
    private final String scene;
    private final String tableHash;
    private final SimulationOptions options;
    private final Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
    private final UiChildren<UiInputTarget> children = new UiChildren<>();
    private final UiOverlay overlay = new UiOverlay(this);
    private final UiLayer layer;
    private final UiControlGroup actions = new UiControlGroup();
    private final UiTextInput luckText;
    private final UiNumberField luck;
    private final UiTextInput search;
    private final UiListView<ToolOption> tools;
    private final UiListView<ResourceLocation> enchantments;
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private @Nullable Component error;
    private String query = "";

    public ScenarioParamsOverlay(JournalOverlays owner, ResourceLocation table, String scene,
                                  SimulationOptions options, ScenarioParams current) {
        this.owner = owner;
        this.table = table;
        this.scene = scene;
        this.options = options;
        var structure = ScenarioSimulationClientState.table(table);
        tableHash = structure == null ? "" : structure.hash();
        levels.putAll(current.toolEnchantments());
        Font font = Minecraft.getInstance().font;
        luckText = new UiTextInput(() -> UnsuspiciousUiTheme.PARCHMENT, font, text("params.luck"));
        UiScalarSlider slider = new UiScalarSlider(UiNumberPolicy.of(LuckGateAnalysis.MIN_LUCK, LuckGateAnalysis.MAX_LUCK, 1, 0.01));
        slider.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiSliderRole.COMPACT);
        slider.setLabel(text("params.luck"));
        slider.setValue(current.luck());
        luck = new UiNumberField(slider, luckText, ScenarioParamsOverlay::parseLuck,
                value -> String.format(Locale.ROOT, "%.2f", value), () -> error = null, () -> { }, null, null,
                new UiNumberField.Metrics(6, 50));
        luck.setInputWidth(66);
        luck.setText(Float.toString(current.luck()));
        luck.acceptText();
        tools = new UiListView<>(() -> UnsuspiciousUiTheme.PARCHMENT, (graphics, rowFont, tool, index, row, selected, hovered, focused) -> {
            new UiIcon.Item(new ItemStack(BuiltInRegistries.ITEM.get(tool.id()))).render(graphics, row.x() + 2, row.y() + 2);
            graphics.drawString(rowFont, rowFont.plainSubstrByWidth(tool.displayName().getString(), Math.max(0, row.width() - 24)),
                    row.x() + 22, row.y() + 6, UiTextScheme.PARCHMENT.body(), false);
        });
        tools.setItems(options.tools()).setRowHeight(22);
        for (int i = 0; i < options.tools().size(); i++) if (options.tools().get(i).id().equals(current.toolId())) tools.setSelectedIndex(i);
        tools.shell().setEnabled(options.toolSelectionAllowed());
        enchantments = new UiListView<>(() -> UnsuspiciousUiTheme.PARCHMENT, (graphics, rowFont, id, index, row, selected, hovered, focused) -> {
            Component name = text("params.enchant_level", enchantmentName(id), levels.getOrDefault(id, 0));
            graphics.drawString(rowFont, rowFont.plainSubstrByWidth(name.getString(), Math.max(0, row.width() - 6)),
                    row.x() + 3, row.y() + 6, UiTextScheme.PARCHMENT.body(), false);
        });
        enchantments.setRowHeight(22);
        enchantments.setEmptyMessage(text("params.no_enchantments"));
        search = new UiTextInput(() -> UnsuspiciousUiTheme.PARCHMENT, font, text("params.search"));
        search.setOnValueChanged(() -> { query = search.value(); filterEnchantments(); });
        filterEnchantments();
        actions.setStyle(ScenarioUi.QUIET);
        actions.beginUpdate();
        button(font, "minus", text("params.decrease"), () -> changeLevel(-1));
        button(font, "plus", text("params.increase"), () -> changeLevel(1));
        button(font, "calculate", text("params.apply_calculate"), () -> confirm(true)).setStyle(ScenarioUi.ACTION);
        button(font, "apply", text("params.apply_only"), () -> confirm(false));
        button(font, "cancel", Component.translatable("gui.cancel"), owner::close);
        actions.endUpdate();
        children.add(luck); children.add(tools); children.add(search); children.add(enchantments); children.add(actions);
        layer = new UiLayer(overlay.inputScope(), (graphics, rowFont, pointer, depth) -> overlay.render(graphics,
                new UiRect(0, 0, owner.width(), owner.height()), depth, 0x88000000,
                () -> render(graphics, rowFont, pointer)), null);
    }
    private UiControl button(Font font, String key, Component label, UiAction action) {
        UiControl button = actions.obtain(key);
        button.configure(font, label, UiTextScheme.PARCHMENT.body(), null, List.of(label), action);
        return button;
    }
    private void filterEnchantments() {
        ResourceLocation selected = enchantments.selectedItem();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<ResourceLocation> filtered = options.enchantments().keySet().stream()
                .filter(id -> needle.isEmpty() || id.toString().toLowerCase(Locale.ROOT).contains(needle)
                        || enchantmentName(id).toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(ScenarioParamsOverlay::enchantmentName)).toList();
        enchantments.setItems(filtered).setSelectedIndex(selected == null ? (filtered.isEmpty() ? -1 : 0) : filtered.indexOf(selected));
    }
    private void changeLevel(int delta) {
        ResourceLocation selected = enchantments.selectedItem();
        if (selected == null) return;
        levels.put(selected, Math.clamp(levels.getOrDefault(selected, 0) + delta, 0, options.enchantments().get(selected)));
        error = null;
    }
    private static @Nullable Double parseLuck(String text) {
        try {
            double value = Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(value) && value >= LuckGateAnalysis.MIN_LUCK && value <= LuckGateAnalysis.MAX_LUCK ? value : null;
        } catch (NumberFormatException invalid) { return null; }
    }
    private void confirm(boolean calculate) {
        Double value = parseLuck(luckText.value());
        ToolOption tool = tools.selectedItem();
        if (value == null || tool == null) { error = text("luck_invalid"); return; }
        ScenarioParams params = new ScenarioParams(value.floatValue(), tool.id(), levels, ScenarioParams.DEFAULT_SAMPLE_COUNT);
        var latest = ScenarioSimulationClientState.table(table);
        if (latest == null || latest.options() == null || !latest.hash().equals(tableHash)
                || latest.options().generation() != options.generation() || latest.options().rejects(scene, params)) {
            error = text("failure.rejected_input"); return;
        }
        ScenarioSimulationClientState.select(table, scene, params);
        if (calculate) ScenarioSimulationClientState.request(table, true);
        owner.close();
    }
    @Override public UiChildren<UiInputTarget> inputChildren() { return children; }
    @Override public UiLayer layer() { return layer; }
    @Override public void prepare(Font font) {
        int width = Math.clamp(owner.width() - 20, 0, 370);
        int height = Math.clamp(owner.height() - 20, 0, 310);
        bounds = new UiRect((owner.width() - width) / 2, (owner.height() - height) / 2, width, height);
        int innerWidth = Math.max(0, width - 16);
        int column = Math.max(0, (innerWidth - 8) / 2);
        int left = bounds.x() + 8, right = left + column + 8;
        luck.setBounds(left, bounds.y() + 26, innerWidth, 22);
        search.setBounds(right, bounds.y() + 66, column, 20);
        int bodyY = bounds.y() + 90;
        int bodyHeight = Math.max(0, height - 162);
        tools.setBounds(left, bodyY, column, bodyHeight + 24);
        enchantments.setBounds(right, bodyY, column, bodyHeight);
        List<UiControl> buttons = actions.controls();
        buttons.get(0).setBounds(right, bounds.bottom() - 70, column / 2 - 2, 20);
        buttons.get(1).setBounds(right + column / 2 + 2, bounds.bottom() - 70, column / 2 - 2, 20);
        int buttonWidth = Math.max(0, (innerWidth - 8) / 3);
        for (int i = 2; i < buttons.size(); i++) buttons.get(i).setBounds(left + (i - 2) * (buttonWidth + 4), bounds.bottom() - 28, buttonWidth, 20);
        ResourceLocation selected = enchantments.selectedItem();
        int level = selected == null ? 0 : levels.getOrDefault(selected, 0);
        buttons.get(0).setEnabled(selected != null && level > 0);
        buttons.get(1).setEnabled(selected != null && level < options.enchantments().get(selected));
    }
    private void render(GuiGraphics graphics, Font font, UiPointer pointer) {
        ScenarioUi.renderPage(graphics, bounds);
        graphics.drawString(font, text("params.title"), bounds.x() + 8, bounds.y() + 8, UiTextScheme.PARCHMENT.title(), false);
        Component samples = text("params.fixed_samples");
        graphics.drawString(font, samples, bounds.right() - 8 - font.width(samples), bounds.y() + 8,
                UiTextScheme.PARCHMENT.label(), false);
        graphics.drawString(font, text("params.tool"), bounds.x() + 8, bounds.y() + 70, UiTextScheme.PARCHMENT.label(), false);
        luck.render(graphics, font, pointer); tools.render(graphics, font, pointer);
        search.render(graphics, font, pointer); enchantments.render(graphics, font, pointer); actions.render(graphics, font, pointer);
        if (error != null) graphics.drawString(font, error, bounds.x() + 8, bounds.bottom() - 43, UiTextScheme.PARCHMENT.negative(), false);
        if (pointer.allowsTooltip() && pointer.hit() != null)
            pointer.hit().renderTooltip(graphics, font, UnsuspiciousUiTheme.PARCHMENT.surfaces(), (int) pointer.x(), (int) pointer.y());
    }
    @Override public @Nullable UiPointerHit hitTest(double x, double y) {
        UiPointerHit hit = children.hitTest(x, y);
        if (hit == null) return null;
        int toolIndex = hit.rowIndex(tools);
        if (toolIndex >= 0) {
            ToolOption tool = tools.items().get(toolIndex);
            hit = hit.withTextTooltip(tool.predicateText() == null ? List.of(tool.displayName()) : List.of(tool.displayName(), tool.predicateText()), tools.bounds());
        }
        int enchantmentIndex = hit.rowIndex(enchantments);
        if (enchantmentIndex >= 0) hit = hit.withTextTooltip(List.of(Component.literal(enchantmentName(enchantments.items().get(enchantmentIndex)))), enchantments.bounds());
        if (hit.owns(luck)) hit = hit.withTextTooltip(List.of(text("params.luck_description"),
                text("params.luck_slider")), luck.bounds());
        return hit.within(this);
    }
    public static String enchantmentName(ResourceLocation id) {
        var level = Minecraft.getInstance().level;
        if (level == null) return id.toString();
        var enchantment = level.registryAccess().registry(Registries.ENCHANTMENT).map(registry -> registry.get(id)).orElse(null);
        return enchantment == null ? id.toString() : enchantment.description().getString();
    }
    private static Component text(String key, Object... args) { return ScenarioSimulationClientState.text(key, args); }
}
