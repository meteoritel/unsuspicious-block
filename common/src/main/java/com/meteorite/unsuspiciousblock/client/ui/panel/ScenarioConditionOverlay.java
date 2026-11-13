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
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioParamsOverlay;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.state.ScenarioTableSnapshot;
import com.meteorite.unsuspiciousblock.client.state.SimulationPreferenceStore;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalTextColors;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    private final ScenarioSession session;
    private final ScenarioSession.Draft baseDraft;
    private @Nullable Component error;
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
        session = JournalClientConnection.scenario();
        baseDraft = session.draft(table, currentConditions, params);
        this.draftConditions.addAll(currentConditions);

        Font font = Minecraft.getInstance().font;
        list = new UiListView<>(() -> UnsuspiciousUiTheme.PARCHMENT,
                (graphics, rowFont, option, index, row, selected, hovered, focused) -> {
                    boolean checked = draftConditions.contains(option.id());
                    UiAtlas.icon(checked ? UiAtlas.Icon.CHECKBOX_CHECKED : UiAtlas.Icon.CHECKBOX_UNCHECKED)
                            .render(graphics, row.x() + 4, row.y() + (row.height() - UiAtlas.ICON_SIZE) / 2);
                    Component label = ScenarioLabel.shortConditionLabel(option.condition());
                    String clipped = rowFont.plainSubstrByWidth(label.getString(), Math.max(0, row.width() - 22));
                    int textColor = checked ? JournalTextColors.COMPLETED : UiTextScheme.PARCHMENT.body();
                    graphics.drawString(rowFont, clipped, row.x() + 18,
                            row.y() + (row.height() - rowFont.lineHeight) / 2, textColor, false);
                });
        list.setItems(options.conditions()).setRowHeight(22);
        list.setEmptyMessage(Component.empty());
        list.setActivateOnSingleClick(true);
        list.setOnActivate(this::toggle);

        actions.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT);
        actions.beginUpdate();
        button(font, "probe", ScenarioSession.text("probe.short"), UiAtlas.Icon.BIOME, () -> {
            session.requestPlayerProbe(table);
            owner.close();
        });
        button(font, "params", ScenarioSession.text("params.short"), UiAtlas.Icon.PARAMS, () ->
                owner.open(new ScenarioParamsOverlay(owner, options, baseDraft.withInput(new SimulationInput(List.copyOf(draftConditions), params)))));
        button(font, "reset", ScenarioSession.text("reset.button"), UiAtlas.Icon.BACK, draftConditions::clear);
        button(font, "calculate", ScenarioSession.text("params.apply_calculate"), null, () -> confirm(true))
                .setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.PRIMARY);
        button(font, "apply", ScenarioSession.text("params.apply_only"), null, () -> confirm(false));
        button(font, "cancel", Component.translatable("gui.cancel"), null, owner::close);
        actions.endUpdate();

        children.add(list);
        children.add(actions);
        layer = new UiLayer(overlay.inputScope(), (graphics, rowFont, pointer, depth) ->
                overlay.render(graphics, new UiRect(0, 0, owner.width(), owner.height()), depth, 0x88000000,
                        () -> render(graphics, rowFont, pointer)), null);
    }

    private UiControl button(Font font, String key, Component label, @Nullable UiAtlas.Icon icon, UiAction action) {
        UiControl button = actions.obtain(key);
        button.configure(font, label, icon == null ? null : UiAtlas.icon(icon),
                List.of(label.copy().withStyle(TooltipBuilder.BODY)), action);
        return button;
    }

    private void toggle(ConditionOption option) {
        if (!draftConditions.add(option.id())) {
            draftConditions.remove(option.id());
        }
    }

    private void confirm(boolean calculate) {
        List<String> next = SimulationInput.canonicalize(List.copyOf(draftConditions));
        var draft = baseDraft.withInput(new SimulationInput(next, params));
        var result = calculate ? session.applyDraftAndSimulate(draft) : session.applyDraft(draft);
        if (!result.applied()) {
            error = result.error();
            return;
        }
        owner.close();
    }

    private void render(GuiGraphics graphics, Font font, UiPointer pointer) {
        ScenarioUi.renderPage(graphics, bounds);
        graphics.drawString(font, ScenarioSession.text("conditions.overlay_title"),
                bounds.x() + 8, bounds.y() + 9, UiTextScheme.PARCHMENT.title(), false);
        List<String> currentDraft = SimulationInput.canonicalize(List.copyOf(draftConditions));
        ScenarioTableSnapshot presentation = session.snapshot(table, new SimulationPreferenceStore.Selection(currentDraft, params));
        Component countLabel = ScenarioSession.text("conditions.summary_count",
                currentDraft.size(), options.conditions().size());
        int countWidth = font.width(countLabel);
        int pillWidth = countWidth + 22;
        JournalPalette.drawStatPill(graphics, bounds.right() - 8 - pillWidth, bounds.y() + 6, pillWidth, 14, false);
        UiAtlas.statusBadge(presentation == null ? "uncomputed" : presentation.status()).render(graphics, bounds.right() - 22, bounds.y() + 7);
        graphics.drawString(font, countLabel, bounds.right() - 26 - countWidth,
                bounds.y() + 9, UiTextScheme.PARCHMENT.label(), false);
        graphics.fill(bounds.x() + 8, bounds.y() + 23, bounds.right() - 8, bounds.y() + 24, JournalPalette.SEPARATOR);
        list.render(graphics, font, pointer);
        if (options.conditions().isEmpty()) {
            JournalPalette.drawEmptyState(graphics, font, list.bounds(), new ItemStack(Items.COMPASS),
                    ScenarioSession.text("conditions.overlay_empty"));
        }
        graphics.fill(bounds.x() + 8, bounds.bottom() - 35, bounds.right() - 8, bounds.bottom() - 34, JournalPalette.SEPARATOR);
        if (error != null) graphics.drawString(font, font.plainSubstrByWidth(error.getString(),
                Math.max(0, bounds.width() - 16)), bounds.x() + 8, bounds.bottom() - 40, UiTextScheme.PARCHMENT.negative(), false);
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
        boolean empty = options.conditions().isEmpty();
        int width = Math.clamp(owner.width() - 20, 0, 316);
        int desiredHeight = empty
                ? 156
                : Math.clamp(96 + options.conditions().size() * 22L + 12, 180, 270);
        int height = Math.clamp(owner.height() - 20, 0, desiredHeight);
        bounds = new UiRect((owner.width() - width) / 2, (owner.height() - height) / 2, width, height);
        int innerWidth = Math.max(0, width - 16);
        int left = bounds.x() + 8;

        List<UiControl> buttons = actions.controls();
        // 顶部工具栏：同步自身、参数配置，以及仅在有条件时展示的轻量重置按钮
        UiControl probe = buttons.get(0);
        UiControl paramsBtn = buttons.get(1);
        UiControl reset = buttons.get(2);
        reset.setVisible(!empty);
        reset.setEnabled(!empty && !draftConditions.isEmpty());

        int utilY = bounds.y() + 27;
        if (empty) {
            int twoWidth = Math.max(0, (innerWidth - 4) / 2);
            probe.setBounds(left, utilY, twoWidth, 18);
            paramsBtn.setBounds(left + twoWidth + 4, utilY, innerWidth - twoWidth - 4, 18);
        } else {
            int utilWidth = Math.max(0, (innerWidth - 8) / 3);
            probe.setBounds(left, utilY, utilWidth, 18);
            paramsBtn.setBounds(left + utilWidth + 4, utilY, utilWidth, 18);
            reset.setBounds(left + (utilWidth + 4) * 2, utilY, innerWidth - (utilWidth + 4) * 2, 18);
        }

        int listTop = bounds.y() + 49;
        list.setBounds(left, listTop, innerWidth, Math.max(0, bounds.bottom() - 39 - listTop));

        // 底部主操作区：仅保留核心确认/取消三联，层次分明
        int mainWidth = Math.max(0, (innerWidth - 8) / 3);
        for (int i = 3; i < buttons.size(); i++) {
            buttons.get(i).setBounds(left + (i - 3) * (mainWidth + 4), bounds.bottom() - 28, mainWidth, 20);
        }
    }
}
