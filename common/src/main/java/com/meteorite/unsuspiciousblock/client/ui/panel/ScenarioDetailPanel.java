package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiDocumentView;
import com.meteorite.meteosuikit.api.layout.UiDocumentTarget;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.meteosuikit.api.control.UiLightbox;
import com.meteorite.meteosuikit.api.input.UiAction;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputContainer;
import com.meteorite.meteosuikit.api.input.UiInputTarget;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.layout.UiNode;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.render.UiNineSlice;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.state.ScenarioTableSnapshot;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground.BookLayout;
import com.meteorite.unsuspiciousblock.client.ui.layout.LayoutAware;
import com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioParamsOverlay;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalTextColors;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.ScenarioAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalRichTooltip;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalTooltipSpec;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranch;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ProbabilityFormat;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 场景详情页：顶部总览与快捷控制栏 + 原子环境条件勾选与实时分支/物品状态清单。
 */
public final class ScenarioDetailPanel implements PagePanel, LayoutAware, UiInputContainer {
    private static final int MAX_SAVED_POSITIONS = 512;

    private final JournalOverlays overlays;
    private final UiChildren<UiInputTarget> children = new UiChildren<>();
    private final UiControl expandButton = new UiControl();
    private final UiControl summary = new UiControl();
    private final UiControl luck = new UiControl();
    private final UiControl enchantments = new UiControl();
    private final UiControl probeButton = new UiControl();
    private final UiControl paramsButton = new UiControl();
    private final UiControl calculateButton = new UiControl();
    private final List<UiControl> controls = List.of(
            expandButton, summary, luck, enchantments,
            probeButton, paramsButton, calculateButton);
    private final Map<String, Integer> conditionPositions = new LinkedHashMap<>();

    private BookLayout layout;
    @Nullable
    private ResourceLocation table;
    @Nullable
    private Font measuredFont;
    @Nullable
    private UiDocumentView conditions;
    @Nullable
    private String viewKey;
    private boolean documentDirty = true;
    private Language language;
    private List<UiNode> conditionTree = List.of();
    private Map<List<Component>, JournalTooltipSpec> documentRichTooltips = Map.of();
    private final Map<UiControl, JournalTooltipSpec> controlRichTooltips = new LinkedHashMap<>();
    private @Nullable ScenarioTableSnapshot renderedSnapshot;
    private BiConsumer<String, String> locateTarget = (kind, target) -> {
    };
    private Runnable viewDrops = () -> {
    };

    public ScenarioDetailPanel(BookLayout layout, JournalOverlays overlays) {
        this.layout = layout;
        this.overlays = overlays;
        children.addAll(controls);
        for (UiControl control : List.of(expandButton, probeButton, paramsButton, calculateButton)) {
            control.setFocusable(true);
        }
    }

    public void setTargetNavigation(BiConsumer<String, String> locate,
                                    Runnable viewDrops) {
        this.locateTarget = (kind, target) -> {
            if (overlays.isOpen()) {
                overlays.close();
            }
            locate.accept(kind, target);
        };
        this.viewDrops = viewDrops;
    }

    @Override
    public void applyLayout(BookLayout layout) {
        saveCurrentPosition();
        this.layout = layout;
        documentDirty = true;
    }

    public void setTable(@Nullable ResourceLocation value) {
        if (Objects.equals(table, value)) {
            return;
        }
        saveCurrentPosition();
        table = value;
        viewKey = null;
        documentDirty = true;
    }

    @Override
    public @Nullable UiPointerHit hitTest(double x, double y) {
        UiPointerHit hit = UiInputContainer.super.hitTest(x, y);
        if (hit == null) {
            return null;
        }
        for (var entry : controlRichTooltips.entrySet()) {
            if (hit.owns(entry.getKey())) {
                return JournalRichTooltip.attach(hit, entry.getValue());
            }
        }
        if (conditions != null && hit.owns(conditions) && hit.tooltip() != null) {
            JournalTooltipSpec spec = documentRichTooltips.get(hit.tooltip().tooltip());
            if (spec != null) {
                return JournalRichTooltip.attach(hit, spec);
            }
        }
        return hit;
    }

    @Nullable
    JournalTooltipSpec richTooltip(List<Component> plainLines) {
        return documentRichTooltips.get(plainLines);
    }

    @Override
    public UiChildren<UiInputTarget> inputChildren() {
        return children;
    }

    @Override
    public void prepareUi() {
        sync(Minecraft.getInstance().font);
    }

    @Override
    public void render(GuiGraphics graphics, Font font, UiPointer pointer) {
    }

    @Override
    public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
        if (summary.isVisible()) {
            UiRect sb = summary.bounds();
            JournalPalette.drawSlot(graphics, sb.x(), sb.y(), sb.width(), pointer.contains(sb));
        }
        if (luck.isVisible()) {
            UiRect lb = luck.bounds();
            JournalPalette.drawStatPill(graphics, lb.x(), lb.y(), lb.width(), lb.height(), pointer.contains(lb));
        }
        if (enchantments.isVisible()) {
            UiRect eb = enchantments.bounds();
            JournalPalette.drawStatPill(graphics, eb.x(), eb.y(), eb.width(), eb.height(), pointer.contains(eb));
        }
        for (UiControl control : controls) {
            control.render(graphics, font, pointer);
        }
        if (conditions != null) {
            UiRect inner = conditions.bounds();
            ScenarioAtlas.PANEL.render(graphics, new UiRect(inner.x() - 6, inner.y() - 6, inner.width() + 12, inner.height() + 12));
            conditions.render(graphics, font, pointer);
        }
    }

    @Override
    public boolean containsMouse(double x, double y) {
        return PagePanel.containsPageBounds(x, y,
                layout.rightPageX(), layout.rightPageY(),
                layout.rightPageRight(), layout.rightPageBottom());
    }

    @Override
    public int pageCount() {
        return 1;
    }

    @Override
    public int getPage() {
        return 0;
    }

    @Override
    public void setPage(int page) {
    }

    @Override
    public void changePage(int delta) {
    }

    private void sync(Font font) {
        ScenarioSession session = JournalClientConnection.scenario();
        if (table != null) {
            session.ensureCacheQuery(table);
        }
        UiDocumentView conditionView = ensureConditionView(font);
        ScenarioTableSnapshot snapshot = session.snapshot(table);
        Language nextLanguage = Language.getInstance();
        if (!documentDirty && renderedSnapshot == snapshot && language == nextLanguage) {
            return;
        }

        String nextViewKey = table == null ? null : table.toString();
        boolean sameTable = nextViewKey != null && nextViewKey.equals(viewKey);
        int previousOffset = sameTable ? conditionView.offset() : 0;
        if (!sameTable) {
            saveCurrentPosition();
        }
        if (language != nextLanguage) {
            conditionView.invalidateLayout();
        }
        language = nextLanguage;
        viewKey = nextViewKey;
        renderedSnapshot = snapshot;
        documentDirty = false;

        SimulationOptions options = snapshot == null ? null : snapshot.structure().options();
        if (snapshot == null || options == null) {
            layoutPanel(false, conditionView);
            configureUnavailable(font, conditionView);
        } else {
            boolean showEnchantments = !options.enchantments().isEmpty()
                    || !snapshot.choice().params().toolEnchantments().isEmpty();
            layoutPanel(showEnchantments, conditionView);
            configureAvailable(font, conditionView, snapshot, options, showEnchantments);
        }
        if (nextViewKey != null) {
            conditionView.setOffset(sameTable ? previousOffset : conditionPositions.getOrDefault(nextViewKey, 0));
        }
    }

    private UiDocumentView ensureConditionView(Font font) {
        if (conditions != null && measuredFont == font) {
            return conditions;
        }
        saveCurrentPosition();
        if (conditions != null) {
            children.remove(conditions);
        }
        UiDocumentView view = new UiDocumentView(TextMeasurer.of(font), () -> UnsuspiciousUiTheme.PARCHMENT,
                ScenarioSession.text("conditions.heading"));
        view.setBackground(ScenarioDetailPanel::renderConditionBackground);
        children.add(view);
        conditions = view;
        measuredFont = font;
        viewKey = null;
        documentDirty = true;
        return view;
    }

    private void layoutPanel(boolean showEnchantments, UiDocumentView conditionView) {
        int x = layout.rightPageX() + 4;
        int y = layout.rightPageY();
        int width = layout.rightPageWidth() - 8;
        int headerY = y + 2;
        int iconSize = 16;
        int iconGap = 2;
        int actionWidth = iconSize * 3 + iconGap * 2;
        int actionX = x + Math.max(0, width - actionWidth);
        summary.setBounds(x, headerY, 18, 18);
        int readoutAreaWidth = Math.max(0, actionX - x - 21 - 3);
        if (showEnchantments) {
            int pillGap = 3;
            int luckWidth = Math.max(0, (readoutAreaWidth - pillGap) / 2);
            int enchWidth = Math.max(0, readoutAreaWidth - pillGap - luckWidth);
            luck.setBounds(x + 21, headerY + 1, luckWidth, 16);
            enchantments.setBounds(x + 21 + luckWidth + pillGap, headerY + 1, enchWidth, 16);
        } else {
            luck.setBounds(x + 21, headerY + 1, readoutAreaWidth, 16);
            enchantments.setBounds(0, 0, 0, 0);
        }
        probeButton.setBounds(actionX, headerY + 1, iconSize, iconSize);
        paramsButton.setBounds(actionX + iconSize + iconGap, headerY + 1, iconSize, iconSize);
        expandButton.setBounds(actionX + (iconSize + iconGap) * 2, headerY + 1, iconSize, iconSize);
        calculateButton.setBounds(x, y + 23, width, 16);
        int panelTop = y + 43;
        int contentHeight = Math.max(1, layout.rightPageBottom() - 8 - panelTop);
        conditionView.setBounds(x + 6, panelTop + 6, Math.max(0, width - 12), Math.max(0, contentHeight - 12));
    }

    private void configureUnavailable(Font font, UiDocumentView conditionView) {
        conditionTree = List.of(new UiNode.Row(ScenarioSession.text("unavailable"),
                UiTextScheme.PARCHMENT.label()));
        documentRichTooltips = Map.of();
        controlRichTooltips.clear();
        conditionView.setContent(conditionTree);
        for (UiControl control : controls) {
            control.configure(font, Component.empty(), UiTextScheme.PARCHMENT.body(), null, List.of(), null);
            control.setVisible(false);
        }
    }

    private void configureAvailable(Font font, UiDocumentView conditionView, ScenarioTableSnapshot snapshot,
                                    SimulationOptions options, boolean showEnchantments) {
        CatalogTableDto structure = snapshot.structure();
        ResourceLocation currentTable = structure.id();
        var choice = snapshot.choice();
        var params = choice.params();
        controlRichTooltips.clear();
        for (UiControl control : controls) {
            control.setVisible(true);
            control.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.SECONDARY);
        }

        var built = ScenarioPageBuilder.buildConditionsWithTooltips(snapshot, options,
                () -> JournalClientConnection.scenario().clearNotes(),
                () -> JournalClientConnection.scenario().select(currentTable, List.of(), params),
                conditionId -> JournalClientConnection.scenario().toggleCondition(currentTable, conditionId),
                locateTarget);
        conditionTree = built.nodes();
        documentRichTooltips = built.richTooltips();
        conditionView.setContent(conditionTree);

        ResourceLocation toolId = params.toolId();
        Component toolName = Component.literal(toolId.toString());
        for (var tool : options.tools()) {
            if (tool.id().equals(toolId)) {
                toolName = tool.displayName();
                break;
            }
        }
        ItemStack toolStack = new ItemStack(BuiltInRegistries.ITEM.get(toolId));
        UiIcon toolIcon = new UiIcon.Item(toolStack);

        JournalTooltipSpec.Builder paramsSpec = JournalTooltipSpec.builder()
                .header(UiAtlas.icon(UiAtlas.Icon.PARAMS), ScenarioSession.text("params.title"),
                        TooltipBuilder.TITLE, null)
                .metrics(List.of(
                        new JournalTooltipSpec.MetricChip(toolIcon, toolName, TooltipBuilder.BODY, false),
                        new JournalTooltipSpec.MetricChip(UiAtlas.LUCK_ICON,
                                ScenarioSession.text("params.luck_short", ProbabilityFormat.formatLuck(params.luck())),
                                TooltipBuilder.ACCENT, true)));
        if (!params.toolEnchantments().isEmpty()) {
            List<JournalTooltipSpec.MetricChip> enchChips = new ArrayList<>();
            params.toolEnchantments().forEach((id, level) -> enchChips.add(new JournalTooltipSpec.MetricChip(
                    null,
                    ScenarioSession.text("params.enchant_level", ScenarioParamsOverlay.enchantmentName(id), level),
                    TooltipBuilder.ACCENT, true)));
            paramsSpec.metrics(enchChips);
        }
        paramsSpec.footer(null, clickAction(), TooltipBuilder.ACCENT, ScenarioSession.text("params.tooltip_open"));
        JournalTooltipSpec builtParamsSpec = paramsSpec.build();

        JournalTooltipSpec summarySpec = JournalTooltipSpec.builder()
                .header(toolIcon, toolName, TooltipBuilder.BODY, null)
                .line(ScenarioSession.text("params.readonly_hint").copy().withStyle(TooltipBuilder.LABEL))
                .build();
        summary.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.LABEL);
        summary.configure(font, Component.empty(), JournalTextColors.NAME,
                toolIcon, summarySpec.plainLines(), null);
        controlRichTooltips.put(summary, summarySpec);

        JournalTooltipSpec luckSpec = JournalTooltipSpec.builder()
                .header(UiAtlas.LUCK_ICON,
                        ScenarioSession.text("params.luck_short", ProbabilityFormat.formatLuck(params.luck())),
                        TooltipBuilder.ACCENT, null)
                .line(ScenarioSession.text("params.luck_description").copy().withStyle(TooltipBuilder.LABEL))
                .line(ScenarioSession.text("params.readonly_hint").copy().withStyle(TooltipBuilder.HINT))
                .build();
        configureValue(font, luck, UiAtlas.LUCK_ICON, "params.luck_short",
                ProbabilityFormat.formatLuck(params.luck()), luckSpec);

        enchantments.setVisible(showEnchantments);
        if (showEnchantments) {
            JournalTooltipSpec.Builder enchSpec = JournalTooltipSpec.builder()
                    .header(ScenarioSession.text("params.attribute.enchantments"), TooltipBuilder.TITLE,
                            new JournalTooltipSpec.Pill(
                                    ScenarioSession.text("params.attribute.enchantment_count",
                                            params.toolEnchantments().size()),
                                    params.toolEnchantments().isEmpty() ? TooltipBuilder.LABEL : TooltipBuilder.ACCENT));
            if (params.toolEnchantments().isEmpty()) {
                enchSpec.line(ScenarioSession.text("params.readonly_hint").copy().withStyle(TooltipBuilder.LABEL));
            } else {
                params.toolEnchantments().forEach((id, level) -> enchSpec.line(
                        ScenarioSession.text("params.enchant_level",
                                ScenarioParamsOverlay.enchantmentName(id), level).copy().withStyle(TooltipBuilder.ACCENT)));
            }
            configureValue(font, enchantments, null, "params.enchantments_short",
                    Integer.toString(params.toolEnchantments().size()), enchSpec.build());
        }

        JournalTooltipSpec probeSpec = JournalTooltipSpec.builder()
                .header(UiAtlas.SYNC_PLAYER, ScenarioSession.text("probe.button"), TooltipBuilder.TITLE, null)
                .line(ScenarioSession.text("probe.hint").copy().withStyle(TooltipBuilder.LABEL))
                .footer(null, clickAction(), TooltipBuilder.ACCENT, ScenarioSession.text("probe.short"))
                .build();
        probeButton.configure(font, Component.empty(), UiAtlas.SYNC_PLAYER,
                probeSpec.plainLines(),
                () -> JournalClientConnection.scenario().requestPlayerProbe(currentTable));
        probeButton.setAccessibleName(ScenarioSession.text("probe.button"));
        probeButton.setEnabled(!JournalClientConnection.scenario().assistPending());
        controlRichTooltips.put(probeButton, probeSpec);

        paramsButton.configure(font, Component.empty(), UiAtlas.icon(UiAtlas.Icon.PARAMS),
                builtParamsSpec.plainLines(),
                () -> overlays.open(new ScenarioParamsOverlay(overlays, currentTable, choice.conditions(),
                        options, params)));
        paramsButton.setAccessibleName(ScenarioSession.text("params.title"));
        controlRichTooltips.put(paramsButton, builtParamsSpec);

        configureCalculateButton(font, snapshot);

        JournalTooltipSpec expandSpec = JournalTooltipSpec.builder()
                .header(UiAtlas.icon(UiAtlas.Icon.EXPAND), ScenarioSession.text("frame.expand"),
                        TooltipBuilder.TITLE, null)
                .footer(null, clickAction(), TooltipBuilder.ACCENT, ScenarioSession.text("frame.expand_hint"))
                .build();
        expandButton.configure(font, Component.empty(), UiAtlas.icon(UiAtlas.Icon.EXPAND),
                expandSpec.plainLines(),
                () -> {
                    var content = ScenarioExpandedOverlay.create(this, font);
                    UiLightbox lightbox = new UiLightbox(ScenarioExpandedOverlay.LABELS, content, overlays::close);
                    ScenarioUi.styleLightbox(lightbox);
                    lightbox.setText(ScenarioSession.text("conditions.title", structure.displayName()), null);
                    lightbox.setMaskClickCloses(false);
                    overlays.open(lightbox);
                });
        expandButton.setAccessibleName(ScenarioSession.text("frame.expand"));
        controlRichTooltips.put(expandButton, expandSpec);
    }

    private void configureCalculateButton(Font font, ScenarioTableSnapshot snapshot) {
        String status = snapshot.status();
        boolean pending = switch (status) {
            case "pending", "querying", "retrieving" -> true;
            default -> false;
        };
        boolean canCalculate = switch (status) {
            case "uncomputed", "query_failed", "available" -> true;
            default -> false;
        };
        String label = switch (status) {
            case "cached" -> "view_drops_ready";
            case "uncomputed", "failed", "query_failed" -> "calculate";
            default -> "results.status." + status;
        };
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        switch (status) {
            case "failed" -> {
                spec.header(UiAtlas.statusBadge(status), ScenarioSession.text("calculate"), TooltipBuilder.NEGATIVE,
                        new JournalTooltipSpec.Pill(ScenarioSession.text("results.status.failed"), TooltipBuilder.NEGATIVE));
                spec.line(ScenarioSession.text("failure." + snapshot.failure()).copy().withStyle(TooltipBuilder.NEGATIVE));
                spec.footer(null, clickAction(), TooltipBuilder.ACCENT, ScenarioSession.text("calculate_action"));
            }
            case "cached" -> {
                spec.header(UiAtlas.statusBadge(status), ScenarioSession.text("view_drops"), TooltipBuilder.POSITIVE,
                        new JournalTooltipSpec.Pill(ScenarioSession.text("results.status.cached"), TooltipBuilder.POSITIVE));
                spec.line(ScenarioSession.text("cached_hint").copy().withStyle(TooltipBuilder.LABEL));
                spec.footer(null, clickAction(), TooltipBuilder.ACCENT, ScenarioSession.text("view_drops"));
            }
            default -> {
                ChatFormatting pillColor = pending ? TooltipBuilder.ACCENT : TooltipBuilder.LABEL;
                spec.header(UiAtlas.statusBadge(status), ScenarioSession.text("calculate"), TooltipBuilder.TITLE,
                        new JournalTooltipSpec.Pill(ScenarioSession.text("results.status." + status), pillColor));
                spec.line(ScenarioSession.text(status).copy().withStyle(TooltipBuilder.LABEL));
                if (canCalculate) {
                    spec.footer(null, clickAction(), TooltipBuilder.ACCENT, ScenarioSession.text("calculate_action"));
                }
            }
        }
        JournalTooltipSpec builtSpec = spec.build();
        UiAction action = "cached".equals(status) ? () -> viewDrops.run()
                : () -> JournalClientConnection.scenario().request(snapshot.structure().id(), true);
        calculateButton.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.PRIMARY);
        calculateButton.configure(font, ScenarioSession.text(label), UiAtlas.statusBadge(status),
                builtSpec.plainLines(), action);
        calculateButton.setEnabled(!pending);
        controlRichTooltips.put(calculateButton, builtSpec);
    }

    private void configureValue(Font font, UiControl control, @Nullable UiIcon icon, String key, String value,
                                JournalTooltipSpec spec) {
        Component full = ScenarioSession.text(key, value);
        int iconReserve = icon == null ? 0 : icon.width() + 4;
        Component label = font.width(full) <= control.bounds().width() - 4 - iconReserve
                ? full : Component.literal(value);
        control.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.LABEL);
        control.configure(font, label, icon, spec.plainLines(), null);
        controlRichTooltips.put(control, spec);
    }

    private static Component clickAction() {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.tooltip.click_action");
    }

    static void renderConditionBackground(GuiGraphics graphics, UiDocumentTarget target, boolean selected) {
        UiRect rect = target.contentBounds();
        Object payload = target.hit().payload();
        if (payload instanceof ScenarioPageBuilder.Heading heading) {
            UiNineSlice pill = switch (heading) {
                case SECTION_NEUTRAL -> UiAtlas.STAT_PILL;
                case SECTION_AVAILABLE -> UiAtlas.SECTION_PILL_AVAILABLE;
                case SECTION_BLOCKED -> UiAtlas.SECTION_PILL_BLOCKED;
                case SECTION_UNCERTAIN -> UiAtlas.STAT_PILL_SUBTLE;
                case ENTRY -> null;
            };
            if (pill != null) {
                int expandLeft = Math.min(2, rect.x());
                pill.render(graphics, new UiRect(rect.x() - expandLeft, rect.y(),
                        rect.width() + expandLeft + 2, rect.height()));
            }
        } else if (payload instanceof ScenarioBranch) {
            // 为条件树中的候选掉落物与子表图标绘制内凹物品插槽框
            JournalPalette.drawSlot(graphics, rect.x() - 1, rect.y() - 1, Math.max(rect.width(), rect.height()) + 2, selected);
        }
        if (selected && !(payload instanceof ScenarioBranch)) {
            ScenarioAtlas.SELECTED.render(graphics, rect);
        }
    }

    List<UiNode> tree() {
        return conditionTree;
    }

    private void saveCurrentPosition() {
        if (viewKey == null || conditions == null) {
            return;
        }
        remember(conditionPositions, viewKey, conditions.offset());
    }

    private static void remember(Map<String, Integer> positions, String key, int offset) {
        positions.put(key, offset);
        while (positions.size() > MAX_SAVED_POSITIONS) {
            positions.remove(positions.keySet().iterator().next());
        }
    }

    /**
     * 按表捕获条件正文位置，供宿主阅读快照统一持有。
     */
    public Map<String, Integer> captureConditionOffsets() {
        saveCurrentPosition();
        return new LinkedHashMap<>(conditionPositions);
    }

    /**
     * 恢复有界位置表，正文重建后由实际视口钳制偏移。
     */
    public void restoreConditionOffsets(Map<String, Integer> offsets) {
        if (conditionPositions.equals(offsets)) return;
        conditionPositions.clear();
        offsets.forEach((key, value) -> remember(conditionPositions, key, Math.max(0, value)));
        viewKey = null;
        documentDirty = true;
    }
}
