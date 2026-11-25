package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiListView;
import com.meteorite.meteosuikit.api.control.UiOverlay;
import com.meteorite.meteosuikit.api.control.UiWidgetRow;
import com.meteorite.meteosuikit.api.layout.UiLinearLayout;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.render.UiLayer;
import com.meteorite.meteosuikit.api.render.UiTooltipPresenter;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.state.ScenarioTableSnapshot;
import com.meteorite.unsuspiciousblock.client.state.SimulationPreferenceStore;
import com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioParamsOverlay;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalTextColors;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalRichTooltip;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalTooltipSpec;
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
import java.util.function.Function;

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
    private final UiChildren<UiControl> actions = new UiChildren<>();
    private final UiControl probe;
    private final UiControl paramsButton;
    private final UiControl clear;
    private final UiControl calculateButton;
    private final UiControl applyOnlyButton;
    private final UiControl cancelButton;
    private final UiWidgetRow confirmationRow = new UiWidgetRow(4, UiWidgetRow.Alignment.START);
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

        probe = button(font, ScenarioSession.text("probe.short"), UiAtlas.Icon.BIOME, () -> {
            session.requestPlayerProbe(table);
            owner.close();
        });
        paramsButton = button(font, ScenarioSession.text("params.short"), UiAtlas.Icon.PARAMS, () ->
                owner.open(new ScenarioParamsOverlay(owner, options, baseDraft.withInput(new SimulationInput(List.copyOf(draftConditions), params)))));
        clear = button(font, ScenarioSession.text("conditions.clear"), null, draftConditions::clear);
        calculateButton = button(font, ScenarioSession.text("params.apply_calculate"), null,
                () -> submitDraft(session::applyDraftAndSimulate));
        calculateButton.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.PRIMARY);
        applyOnlyButton = button(font, ScenarioSession.text("params.apply_only"), null,
                () -> submitDraft(session::applyDraft));
        cancelButton = button(font, Component.translatable("gui.cancel"), null, owner::close);
        List<UiControl> confirmationActions = List.of(calculateButton, applyOnlyButton, cancelButton);
        for (UiControl action : confirmationActions) {
            actions.remove(action);
            confirmationRow.add(action, UiLinearLayout.Child.remain());
        }

        children.add(list);
        children.add(actions);
        children.add(confirmationRow);
        layer = overlay.layer(() -> new UiRect(0, 0, owner.width(), owner.height()), 0x88000000,
                (graphics, rowFont, pointer, depth) -> render(graphics, rowFont, pointer));
    }

    private UiControl button(Font font, Component label, @Nullable UiAtlas.Icon icon, UiAction action) {
        UiControl button = new UiControl();
        button.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.SECONDARY);
        button.setFocusable(true);
        button.configure(font, label, icon == null ? null : UiAtlas.icon(icon),
                List.of(label.copy().withStyle(TooltipBuilder.BODY)), action);
        actions.add(button);
        return button;
    }

    private void toggle(ConditionOption option) {
        if (!draftConditions.add(option.id())) {
            draftConditions.remove(option.id());
        }
    }

    private void submitDraft(Function<ScenarioSession.Draft, ScenarioSession.CommitResult> submit) {
        var draft = baseDraft.withInput(new SimulationInput(List.copyOf(draftConditions), params));
        var result = submit.apply(draft);
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
        var selection = new SimulationPreferenceStore.Selection(List.copyOf(draftConditions), params);
        ScenarioTableSnapshot presentation = session.snapshot(table, selection);
        Component countLabel = ScenarioSession.text("conditions.summary_count",
                selection.conditions().size(), options.conditions().size());
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
        for (UiControl action : actions) action.render(graphics, font, pointer);
        confirmationRow.render(graphics, font, pointer);
        if (pointer.allowsTooltip() && pointer.hit() != null) {
            UiTooltipPresenter.render(graphics, font, pointer.hit(), UnsuspiciousUiTheme.PARCHMENT.surfaces(),
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
            JournalTooltipSpec spec = ScenarioLabel.conditionTooltipRich(
                    option.condition(), draftConditions.contains(option.id()));
            hit = JournalRichTooltip.attach(hit.withTextTooltip(spec.plainLines(), list.bounds()), spec);
        } else if (hit.owns(probe)) {
            hit = JournalRichTooltip.attach(hit, JournalTooltipSpec.builder()
                    .header(UiAtlas.SYNC_PLAYER, ScenarioSession.text("probe.button"), TooltipBuilder.TITLE, null)
                    .line(ScenarioSession.text("probe.hint").copy().withStyle(TooltipBuilder.LABEL))
                    .build());
        } else if (hit.owns(paramsButton)) {
            hit = JournalRichTooltip.attach(hit, JournalTooltipSpec.builder()
                    .header(UiAtlas.icon(UiAtlas.Icon.PARAMS), ScenarioSession.text("params.title"),
                            TooltipBuilder.TITLE, null)
                    .line(ScenarioSession.text("params.tooltip_open").copy().withStyle(TooltipBuilder.LABEL))
                    .build());
        } else if (hit.owns(clear)) {
            hit = JournalRichTooltip.attach(hit, JournalTooltipSpec.builder()
                    .header(UiAtlas.icon(UiAtlas.Icon.CLOSE), ScenarioSession.text("conditions.clear"),
                            TooltipBuilder.BODY, null)
                    .line(ScenarioSession.text("conditions.clear_hint").copy().withStyle(TooltipBuilder.LABEL))
                    .build());
        } else if (hit.owns(calculateButton)) {
            hit = JournalRichTooltip.attach(hit, JournalTooltipSpec.builder()
                    .header(UiAtlas.icon(UiAtlas.Icon.CALCULATE), ScenarioSession.text("params.apply_calculate"),
                            TooltipBuilder.TITLE, null)
                    .line(ScenarioSession.text("params.apply_calculate_hint").copy().withStyle(TooltipBuilder.LABEL))
                    .build());
        } else if (hit.owns(applyOnlyButton)) {
            hit = JournalRichTooltip.attach(hit, JournalTooltipSpec.builder()
                    .header(ScenarioSession.text("params.apply_only"), TooltipBuilder.BODY)
                    .line(ScenarioSession.text("params.confirm_help").copy().withStyle(TooltipBuilder.LABEL))
                    .build());
        } else if (hit.owns(cancelButton)) {
            hit = JournalRichTooltip.attach(hit, JournalTooltipSpec.builder()
                    .header(Component.translatable("gui.cancel"), TooltipBuilder.LABEL)
                    .build());
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

        clear.setVisible(!empty);
        clear.setEnabled(!empty && !draftConditions.isEmpty());

        int utilY = bounds.y() + 27;
        if (empty) {
            int twoWidth = Math.max(0, (innerWidth - 4) / 2);
            probe.setBounds(left, utilY, twoWidth, 18);
            paramsButton.setBounds(left + twoWidth + 4, utilY, innerWidth - twoWidth - 4, 18);
        } else {
            int utilWidth = Math.max(0, (innerWidth - 8) / 3);
            probe.setBounds(left, utilY, utilWidth, 18);
            paramsButton.setBounds(left + utilWidth + 4, utilY, utilWidth, 18);
            clear.setBounds(left + (utilWidth + 4) * 2, utilY, innerWidth - (utilWidth + 4) * 2, 18);
        }

        int listTop = bounds.y() + 49;
        list.setBounds(left, listTop, innerWidth, Math.max(0, bounds.bottom() - 39 - listTop));

        confirmationRow.setBounds(left, bounds.bottom() - 28, innerWidth, 20);
    }
}
