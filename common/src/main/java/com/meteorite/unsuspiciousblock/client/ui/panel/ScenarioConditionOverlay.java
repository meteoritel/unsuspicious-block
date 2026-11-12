package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiControlGroup;
import com.meteorite.meteosuikit.api.control.UiListView;
import com.meteorite.meteosuikit.api.control.UiOverlay;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiAction;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputContainer;
import com.meteorite.meteosuikit.api.input.UiInputTarget;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.render.UiLayer;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioParamsOverlay;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ConditionOption;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 网格页顶栏打开的紧凑条件勾选浮层：允许玩家直接勾选想要组合测试的环境条件，
 * 并可一键同步自身状态、跳转参数配置或立即发起模拟计算。
 */
final class ScenarioConditionOverlay implements JournalOverlays.Surface, UiInputContainer {
    private final JournalOverlays owner;
    private final ResourceLocation table;
    private final SimulationOptions options;
    private final ScenarioParams params;
    private final Set<String> draftConditions = new LinkedHashSet<>();
    private final UiChildren<UiInputTarget> children = new UiChildren<>();
    private final UiOverlay overlay = new UiOverlay(this);
    private final UiListView<ConditionOption> list;
    private final UiControlGroup actions = new UiControlGroup();
    private final UiLayer layer;
    private UiRect bounds = new UiRect(0, 0, 1, 1);

    ScenarioConditionOverlay(JournalOverlays owner, ResourceLocation table, SimulationOptions options,
                             List<String> currentConditions, ScenarioParams params) {
        this.owner = owner;
        this.table = table;
        this.options = options;
        this.params = params;
        this.draftConditions.addAll(currentConditions);

        Font font = Minecraft.getInstance().font;
        list = new UiListView<>(() -> UnsuspiciousUiTheme.PARCHMENT,
                (graphics, rowFont, option, index, row, selected, hovered, focused) -> {
                    boolean checked = draftConditions.contains(option.id());
                    UiAtlas.icon(checked ? UiAtlas.Icon.CHECKBOX_CHECKED : UiAtlas.Icon.CHECKBOX_UNCHECKED)
                            .render(graphics, row.x() + 4, row.y() + (row.height() - UiAtlas.ICON_SIZE) / 2);
                    Component label = ScenarioLabel.shortConditionLabel(option.condition());
                    String clipped = rowFont.plainSubstrByWidth(label.getString(), Math.max(0, row.width() - 22));
                    int textColor = checked ? UiTextScheme.PARCHMENT.positive() : UiTextScheme.PARCHMENT.body();
                    graphics.drawString(rowFont, clipped, row.x() + 18,
                            row.y() + (row.height() - rowFont.lineHeight) / 2, textColor, false);
                });
        list.setItems(options.conditions()).setRowHeight(22);
        list.setEmptyMessage(ScenarioSimulationClientState.text("conditions.overlay_empty"));
        list.setActivateOnSingleClick(true);
        list.setOnActivate(this::toggle);

        actions.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT);
        actions.beginUpdate();
        button(font, "reset", ScenarioSimulationClientState.text("reset.button"), () -> draftConditions.clear());
        button(font, "probe", ScenarioSimulationClientState.text("probe.short"), () -> {
            ScenarioSimulationClientState.requestPlayerProbe(table);
            owner.close();
        });
        button(font, "params", ScenarioSimulationClientState.text("params.short"), () ->
                owner.open(new ScenarioParamsOverlay(owner, table, List.copyOf(draftConditions), options, params)));
        button(font, "calculate", ScenarioSimulationClientState.text("params.apply_calculate"), () -> confirm(true))
                .setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.PRIMARY);
        button(font, "apply", ScenarioSimulationClientState.text("params.apply_only"), () -> confirm(false));
        button(font, "cancel", Component.translatable("gui.cancel"), owner::close);
        actions.endUpdate();

        children.add(list);
        children.add(actions);
        layer = new UiLayer(overlay.inputScope(), (graphics, rowFont, pointer, depth) ->
                overlay.render(graphics, new UiRect(0, 0, owner.width(), owner.height()), depth, 0x88000000,
                        () -> render(graphics, rowFont, pointer)), null);
    }

    private UiControl button(Font font, String key, Component label, UiAction action) {
        UiControl button = actions.obtain(key);
        button.configure(font, label, null, List.of(label.copy().withStyle(TooltipBuilder.BODY)), action);
        return button;
    }

    private void toggle(ConditionOption option) {
        if (!draftConditions.add(option.id())) {
            draftConditions.remove(option.id());
        }
    }

    private void confirm(boolean calculate) {
        List<String> next = SimulationInput.canonicalize(List.copyOf(draftConditions));
        ScenarioSimulationClientState.select(table, next, params);
        if (calculate) {
            ScenarioSimulationClientState.request(table, true);
        }
        owner.close();
    }

    private void render(GuiGraphics graphics, Font font, UiPointer pointer) {
        ScenarioUi.renderPage(graphics, bounds);
        graphics.drawString(font, ScenarioSimulationClientState.text("conditions.overlay_title"),
                bounds.x() + 8, bounds.y() + 8, UiTextScheme.PARCHMENT.title(), false);
        List<String> currentDraft = SimulationInput.canonicalize(List.copyOf(draftConditions));
        ScenarioPresentation presentation = ScenarioPresentation.resolve(table, currentDraft, params);
        presentation.badge().render(graphics, bounds.right() - 20, bounds.y() + 6);
        Component countLabel = ScenarioSimulationClientState.text("conditions.summary_count",
                currentDraft.size(), options.conditions().size());
        graphics.drawString(font, countLabel, bounds.right() - 24 - font.width(countLabel),
                bounds.y() + 8, UiTextScheme.PARCHMENT.label(), false);
        list.render(graphics, font, pointer);
        actions.render(graphics, font, pointer);
        if (pointer.allowsTooltip() && pointer.hit() != null) {
            pointer.hit().renderTooltip(graphics, font, UnsuspiciousUiTheme.PARCHMENT.surfaces(),
                    (int) pointer.x(), (int) pointer.y());
        }
    }

    @Override
    public @Nullable UiPointerHit hitTest(double x, double y) {
        UiPointerHit hit = children.hitTest(x, y);
        if (hit == null) {
            return null;
        }
        int index = hit.rowIndex(list);
        if (index >= 0 && index < options.conditions().size()) {
            ConditionOption option = options.conditions().get(index);
            hit = hit.withTextTooltip(
                    ScenarioLabel.conditionTooltip(option.condition(), draftConditions.contains(option.id())),
                    list.bounds());
        }
        return hit.within(this);
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
        int width = Math.clamp(owner.width() - 20, 0, 310);
        int height = Math.clamp(owner.height() - 20, 0, 270);
        bounds = new UiRect((owner.width() - width) / 2, (owner.height() - height) / 2, width, height);
        int innerWidth = Math.max(0, width - 16);
        int left = bounds.x() + 8;
        list.setBounds(left, bounds.y() + 24, innerWidth, Math.max(0, height - 78));

        List<UiControl> buttons = actions.controls();
        int utilWidth = Math.max(0, (innerWidth - 8) / 3);
        for (int i = 0; i < 3; i++) {
            buttons.get(i).setBounds(left + i * (utilWidth + 4), bounds.bottom() - 48, utilWidth, 18);
        }
        buttons.get(0).setEnabled(!draftConditions.isEmpty());
        int mainWidth = Math.max(0, (innerWidth - 8) / 3);
        for (int i = 3; i < buttons.size(); i++) {
            buttons.get(i).setBounds(left + (i - 3) * (mainWidth + 4), bounds.bottom() - 26, mainWidth, 18);
        }
    }
}
