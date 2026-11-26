package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiListView;
import com.meteorite.meteosuikit.api.control.UiOverlay;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.meteosuikit.api.control.UiWidgetRow;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiAction;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputContainer;
import com.meteorite.meteosuikit.api.input.UiInputTarget;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.layout.UiLinearLayout;
import com.meteorite.meteosuikit.api.number.UiNumberField;
import com.meteorite.meteosuikit.api.number.UiNumberPolicy;
import com.meteorite.meteosuikit.api.number.UiScalarSlider;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.render.UiLayer;
import com.meteorite.meteosuikit.api.render.UiTooltipPresenter;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiSliderRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalTextColors;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalRichTooltip;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalTooltipSpec;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGateAnalysis;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ToolOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 场景参数草稿；kit 负责数值编辑和列表导航，显式应用后才修改模拟选择。
 */
public final class ScenarioParamsOverlay implements JournalOverlays.Surface, UiInputContainer {
    private static final int ENCHANTMENT_LEVEL_STEP = 1;
    private final JournalOverlays owner;
    private final List<String> conditions;
    private final ScenarioSession session;
    private final ScenarioSession.Draft baseDraft;
    private final SimulationOptions options;
    private final Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
    private final UiChildren<UiInputTarget> children = new UiChildren<>();
    private final UiLayer layer;
    private final UiChildren<UiControl> levelActions = new UiChildren<>();
    private final UiControl decrease;
    private final UiControl increase;
    private final UiWidgetRow confirmationRow = new UiWidgetRow(4, UiWidgetRow.Alignment.START);
    private final UiTextInput luckText;
    private final UiScalarSlider luckSlider;
    private final UiNumberField luck;
    private final UiTextInput search;
    private final UiListView<ToolOption> tools;
    private final UiListView<ResourceLocation> enchantments;
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private @Nullable Component error;

    public ScenarioParamsOverlay(JournalOverlays owner, ResourceLocation table, List<String> conditions,
                                 SimulationOptions options, ScenarioParams current) {
        this(owner, options, JournalClientConnection.scenario().draft(table, conditions, current));
    }

    public ScenarioParamsOverlay(JournalOverlays owner, SimulationOptions options, ScenarioSession.Draft draft) {
        List<String> conditions = draft.input().enabledConditions();
        ScenarioParams current = draft.input().params();
        this.owner = owner;
        this.conditions = List.copyOf(conditions);
        this.options = options;
        session = JournalClientConnection.scenario();
        baseDraft = draft;
        levels.putAll(current.toolEnchantments());
        Font font = Minecraft.getInstance().font;
        luckText = new UiTextInput(() -> UnsuspiciousUiTheme.PARCHMENT, font, text("params.luck"));
        luckSlider = new UiScalarSlider(UiNumberPolicy.of(LuckGateAnalysis.MIN_LUCK, LuckGateAnalysis.MAX_LUCK, 1, 0.01));
        luckSlider.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiSliderRole.COMPACT);
        luckSlider.setLabel(text("params.luck"));
        luckSlider.setFormatter(ignored -> Component.empty());
        luckSlider.setValue(current.luck());
        luck = new UiNumberField(luckSlider, luckText, ScenarioParamsOverlay::parseLuck,
                value -> String.format(Locale.ROOT, "%.2f", value), () -> error = null, () -> {
        }, null, null,
                new UiNumberField.Metrics(6, 50));
        luck.setInputWidth(66);
        luck.setText(Float.toString(current.luck()));
        luck.acceptText();
        tools = new UiListView<>(() -> UnsuspiciousUiTheme.PARCHMENT, (graphics, rowFont, tool, index, row, selected, hovered, focused) -> {
            JournalPalette.drawSlot(graphics, row.x() + 2, row.y() + 2, 18, hovered || selected);
            new UiIcon.Item(new ItemStack(BuiltInRegistries.ITEM.get(tool.id()))).render(graphics, row.x() + 3, row.y() + 3);
            int textColor = selected ? JournalTextColors.SELECTED : UiTextScheme.PARCHMENT.body();
            graphics.drawString(rowFont, rowFont.plainSubstrByWidth(tool.displayName().getString(), Math.max(0, row.width() - 26)),
                    row.x() + 24, row.y() + 7, textColor, false);
        });
        tools.setItems(options.tools()).setRowHeight(22);
        for (int i = 0; i < options.tools().size(); i++)
            if (options.tools().get(i).id().equals(current.toolId())) tools.setSelectedIndex(i);
        tools.setEnabled(options.toolSelectionAllowed());
        enchantments = new UiListView<>(() -> UnsuspiciousUiTheme.PARCHMENT, (graphics, rowFont, id, index, row, selected, hovered, focused) -> {
            Component name = text("params.enchant_level", enchantmentName(id), levels.getOrDefault(id, 0));
            int textColor = selected ? JournalTextColors.SELECTED : UiTextScheme.PARCHMENT.body();
            graphics.drawString(rowFont, rowFont.plainSubstrByWidth(name.getString(), Math.max(0, row.width() - 8)),
                    row.x() + 4, row.y() + 6, textColor, false);
        });
        enchantments.setRowHeight(22);
        enchantments.setEmptyMessage(Component.empty());
        search = new UiTextInput(() -> UnsuspiciousUiTheme.PARCHMENT, font, text("params.search"));
        search.setEnabled(!options.enchantments().isEmpty());
        search.setOnValueChanged(this::filterEnchantments);
        filterEnchantments();
        decrease = button(font, text("params.decrease"), () -> changeLevel(-ENCHANTMENT_LEVEL_STEP));
        increase = button(font, text("params.increase"), () -> changeLevel(ENCHANTMENT_LEVEL_STEP));
        levelActions.add(decrease);
        levelActions.add(increase);
        UiControl calculate = button(font, text("params.apply_calculate"), () -> submitDraft(session::applyDraftAndSimulate));
        calculate.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.PRIMARY);
        confirmationRow.add(calculate, UiLinearLayout.Child.remain());
        confirmationRow.add(button(font, text("params.apply_only"), () -> submitDraft(session::applyDraft)),
                UiLinearLayout.Child.remain());
        confirmationRow.add(button(font, Component.translatable("gui.cancel"), owner::close),
                UiLinearLayout.Child.remain());
        children.add(luck);
        children.add(tools);
        children.add(search);
        children.add(enchantments);
        children.add(levelActions);
        children.add(confirmationRow);
        layer = new UiOverlay(this).layer(() -> new UiRect(0, 0, owner.width(), owner.height()), 0x88000000,
                (graphics, rowFont, pointer, depth) -> render(graphics, rowFont, pointer));
    }

    private static UiControl button(Font font, Component label, UiAction action) {
        UiControl button = new UiControl();
        button.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.SECONDARY);
        button.setFocusable(true);
        button.configure(font, label, null, List.of(), action);
        button.setAccessibleName(label);
        return button;
    }

    private void filterEnchantments() {
        ResourceLocation selected = enchantments.selectedItem();
        String needle = search.value().trim().toLowerCase(Locale.ROOT);
        List<ResourceLocation> filtered = options.enchantments().keySet().stream()
                .filter(id -> needle.isEmpty() || id.toString().toLowerCase(Locale.ROOT).contains(needle)
                        || enchantmentName(id).toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(ScenarioParamsOverlay::enchantmentName)).toList();
        int selectedIndex = selected == null ? 0 : filtered.indexOf(selected);
        enchantments.setItems(filtered).setSelectedIndex(selectedIndex);
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
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private void submitDraft(Function<ScenarioSession.Draft, ScenarioSession.CommitResult> submit) {
        Double value = parseLuck(luckText.value());
        ToolOption tool = tools.selectedItem();
        if (value == null || tool == null) {
            error = text("luck_invalid");
            return;
        }
        ScenarioParams params = new ScenarioParams(value.floatValue(), tool.id(), levels, ScenarioParams.DEFAULT_SAMPLE_COUNT);
        var draft = baseDraft.withInput(new SimulationInput(conditions, params));
        var result = submit.apply(draft);
        if (!result.applied()) {
            error = result.error();
            return;
        }
        owner.close();
    }

    @Override
    public UiChildren<UiInputTarget> inputChildren() {
        return children;
    }

    @Override
    public UiLayer layer() {
        return layer;
    }

    @Override
    public void prepare(Font font) {
        int width = Math.clamp(owner.width() - 20, 0, 370);
        int height = Math.clamp(owner.height() - 20, 0, 310);
        bounds = new UiRect((owner.width() - width) / 2, (owner.height() - height) / 2, width, height);
        int innerWidth = Math.max(0, width - 16);
        int column = Math.max(0, (innerWidth - 8) / 2);
        int left = bounds.x() + 8, right = left + column + 8;
        luck.setBounds(left, bounds.y() + 27, innerWidth, 24);
        int toolHeaderY = toolSectionDividerY(font) + 7;
        search.setBounds(right, toolHeaderY, column, 19);
        int toolsTop = toolHeaderY + 12;
        int enchantTop = search.bounds().bottom() + 4;
        int toolsHeight = Math.max(0, bounds.bottom() - 42 - toolsTop);
        int enchantHeight = Math.max(0, bounds.bottom() - 68 - enchantTop);
        tools.setBounds(left, toolsTop, column, toolsHeight);
        enchantments.setBounds(right, enchantTop, column, enchantHeight);
        decrease.setBounds(right, bounds.bottom() - 62, column / 2 - 2, 18);
        increase.setBounds(right + column / 2 + 2, bounds.bottom() - 62, column / 2 - 2, 18);
        confirmationRow.setBounds(left, bounds.bottom() - 28, innerWidth, 20);
        ResourceLocation selected = enchantments.selectedItem();
        int level = selected == null ? 0 : levels.getOrDefault(selected, 0);
        decrease.setEnabled(selected != null && level > 0);
        increase.setEnabled(selected != null && level < options.enchantments().get(selected));
    }

    private int toolSectionDividerY(Font font) {
        return luck.bounds().bottom() + font.lineHeight + 4;
    }

    private void renderZeroLuckMark(GuiGraphics graphics, Font font) {
        double fraction = luckSlider.window().fraction(ScenarioParams.DEFAULT_LUCK);
        int centerX = luckSlider.trackLeft() + (int) Math.round(fraction * luckSlider.trackWidth());
        int markY = luckSlider.bounds().bottom() + 1;
        int color = UiTextScheme.PARCHMENT.label();
        String label = "0";
        graphics.fill(centerX, markY, centerX + 1, markY + 2, color);
        graphics.drawString(font, label, centerX - font.width(label) / 2, markY + 2, color, false);
    }

    private void render(GuiGraphics graphics, Font font, UiPointer pointer) {
        ScenarioUi.renderPage(graphics, bounds);
        graphics.drawString(font, text("params.title"), bounds.x() + 8, bounds.y() + 9, UiTextScheme.PARCHMENT.title(), false);
        Component samples = text("params.fixed_samples");
        int samplesW = font.width(samples);
        JournalPalette.drawStatPill(graphics, bounds.right() - 12 - samplesW, bounds.y() + 6, samplesW + 8, 14, false);
        graphics.drawString(font, samples, bounds.right() - 8 - samplesW, bounds.y() + 9,
                UiTextScheme.PARCHMENT.label(), false);
        graphics.fill(bounds.x() + 8, bounds.y() + 23, bounds.right() - 8, bounds.y() + 24, JournalPalette.SEPARATOR);
        int toolDividerY = toolSectionDividerY(font);
        graphics.fill(bounds.x() + 8, toolDividerY, bounds.right() - 8, toolDividerY + 1, JournalPalette.SEPARATOR);
        graphics.drawString(font, text("params.tool"), bounds.x() + 8, toolDividerY + 7, UiTextScheme.PARCHMENT.label(), false);
        luck.render(graphics, font, pointer);
        renderZeroLuckMark(graphics, font);
        tools.render(graphics, font, pointer);
        search.render(graphics, font, pointer);
        enchantments.render(graphics, font, pointer);
        if (enchantments.items().isEmpty()) {
            ItemStack emptyIcon = new ItemStack(options.enchantments().isEmpty() ? Items.ENCHANTED_BOOK : Items.SPYGLASS);
            JournalPalette.drawEmptyState(graphics, font, enchantments.bounds(), emptyIcon, text("params.no_enchantments"));
        }
        graphics.fill(bounds.x() + 8, bounds.bottom() - 35, bounds.right() - 8, bounds.bottom() - 34, JournalPalette.SEPARATOR);
        for (UiControl action : levelActions) {
            action.render(graphics, font, pointer);
        }
        confirmationRow.render(graphics, font, pointer);
        if (error != null)
            graphics.drawString(font, error, bounds.x() + 8, bounds.bottom() - 45, UiTextScheme.PARCHMENT.negative(), false);
        if (pointer.allowsTooltip() && pointer.hit() != null)
            UiTooltipPresenter.render(graphics, font, pointer.hit(), UnsuspiciousUiTheme.PARCHMENT.tooltipSkin(), (int) pointer.x(), (int) pointer.y());
    }

    @Override
    public @Nullable UiPointerHit hitTest(double x, double y) {
        UiPointerHit hit = children.hitTest(x, y);
        if (hit == null) return null;
        int toolIndex = hit.rowIndex(tools);
        if (toolIndex >= 0) {
            ToolOption tool = tools.items().get(toolIndex);
            List<Component> lines = new ArrayList<>();
            if (tool.predicateText() != null) {
                lines.add(tool.predicateText().copy().withStyle(TooltipBuilder.LABEL));
            }
            if (JournalTooltipSpec.showAdvancedIds()) {
                lines.add(Component.literal(tool.id().toString()).withStyle(TooltipBuilder.HINT));
            }
            if (!lines.isEmpty()) {
                hit = hit.withTextTooltip(List.copyOf(lines), tools.bounds());
            }
            return JournalRichTooltip.attachText(hit).within(this);
        }
        int enchantmentIndex = hit.rowIndex(enchantments);
        if (enchantmentIndex >= 0) {
            ResourceLocation enchId = enchantments.items().get(enchantmentIndex);
            int level = levels.getOrDefault(enchId, 0);
            int maxLevel = options.enchantments().getOrDefault(enchId, 0);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(enchantmentName(enchId) + " " + level + "/" + maxLevel)
                    .withStyle(level > 0 ? TooltipBuilder.ACCENT : TooltipBuilder.BODY));
            if (JournalTooltipSpec.showAdvancedIds()) {
                lines.add(Component.literal(enchId.toString()).withStyle(TooltipBuilder.HINT));
            }
            return JournalRichTooltip.attachText(hit.withTextTooltip(List.copyOf(lines), enchantments.bounds())).within(this);
        }
        if (hit.owns(luck)) {
            List<Component> lines = List.of(
                    text("params.luck_description").copy().withStyle(TooltipBuilder.LABEL),
                    text("params.luck_slider").copy().withStyle(TooltipBuilder.HINT)
            );
            return JournalRichTooltip.attachText(hit.withTextTooltip(lines, luck.bounds())).within(this);
        }
        return JournalRichTooltip.attachText(hit).within(this);
    }

    public static String enchantmentName(ResourceLocation id) {
        var level = Minecraft.getInstance().level;
        if (level == null) return id.toString();
        var enchantment = level.registryAccess().registry(Registries.ENCHANTMENT).map(registry -> registry.get(id)).orElse(null);
        return enchantment == null ? id.toString() : enchantment.description().getString();
    }

    private static Component text(String key, Object... args) {
        return ScenarioSession.text(key, args);
    }
}
