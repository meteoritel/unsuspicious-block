package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiDocumentView;
import com.meteorite.meteosuikit.api.layout.UiDocumentTarget;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.meteosuikit.api.control.UiLightbox;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputContainer;
import com.meteorite.meteosuikit.api.input.UiInputTarget;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.layout.UiNode;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.state.ScenarioTableSnapshot;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.state.SimulationPreferenceStore;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground.BookLayout;
import com.meteorite.unsuspiciousblock.client.ui.layout.LayoutAware;
import com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioParamsOverlay;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioRecommendationOverlay;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalTextColors;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.ScenarioAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranch;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
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
import java.util.Locale;
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
    @Nullable
    private Component overviewLabel;
    private @Nullable ScenarioTableSnapshot snapshot;
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
    public UiPointerHit hitTest(double x, double y) {
        return UiInputContainer.super.hitTest(x, y);
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
        if (table != null) {
            JournalClientConnection.scenario().ensureCacheQuery(table);
        }
        if (conditions == null || measuredFont != font) {
            saveCurrentPosition();
            measuredFont = font;
            if (conditions != null) {
                children.remove(conditions);
            }
            conditions = new UiDocumentView(TextMeasurer.of(font), () -> UnsuspiciousUiTheme.PARCHMENT, ScenarioSession.text("conditions.heading"));
            conditions.setBackground(ScenarioDetailPanel::renderConditionBackground);
            children.add(conditions);
            viewKey = null;
            documentDirty = true;
        }
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
        ScenarioTableSnapshot nextSnapshot = JournalClientConnection.scenario().snapshot(table);
        var choice = nextSnapshot == null ? null : nextSnapshot.choice();
        CatalogTableDto structure = nextSnapshot == null ? null : nextSnapshot.structure();
        boolean showEnchantments = structure != null && structure.options() != null && choice != null
                && (!structure.options().enchantments().isEmpty()
                || !choice.params().toolEnchantments().isEmpty());
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
        String nextStatus = nextSnapshot == null ? "uncomputed" : nextSnapshot.status();
        Language nextLanguage = Language.getInstance();
        if (!documentDirty && snapshot == nextSnapshot && language == nextLanguage) return;
        snapshot = nextSnapshot;

        boolean sameViewKey = table != null && Objects.equals(viewKey, table.toString());
        int preservedOffset = sameViewKey ? conditions.offset() : -1;
        if (!sameViewKey) {
            saveCurrentPosition();
        }
        documentDirty = false;
        if (language != nextLanguage) conditions.invalidateLayout();
        language = nextLanguage;
        viewKey = table == null ? null : table.toString();

        if (structure == null || structure.options() == null || choice == null) {
            configureUnavailable(font);
        } else {
            configureAvailable(font, structure, choice, nextStatus);
        }
        if (viewKey != null) {
            conditions.setOffset(preservedOffset >= 0
                    ? preservedOffset : conditionPositions.getOrDefault(viewKey, 0));
        }
    }

    private void configureUnavailable(Font font) {
        UiDocumentView conditionView = Objects.requireNonNull(conditions);
        conditionTree = List.of(new UiNode.Row(ScenarioSession.text("unavailable"),
                UiTextScheme.PARCHMENT.label()));
        conditionView.setContent(conditionTree);
        for (UiControl control : controls) {
            control.configure(font, Component.empty(), UiTextScheme.PARCHMENT.body(), null, List.of(), null);
            control.setVisible(false);
        }
        overviewLabel = null;
    }

    private void configureAvailable(Font font, CatalogTableDto structure,
                                    SimulationPreferenceStore.Selection choice, String rawStatus) {
        UiDocumentView conditionView = Objects.requireNonNull(conditions);
        SimulationOptions options = Objects.requireNonNull(structure.options());
        for (UiControl control : controls) {
            control.setVisible(true);
            control.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.SECONDARY);
        }
        ResourceLocation currentTable = Objects.requireNonNull(table);

        conditionTree = ScenarioPageBuilder.buildConditions(Objects.requireNonNull(snapshot),
                () -> JournalClientConnection.scenario().clearNotes(),
                () -> JournalClientConnection.scenario().select(currentTable, List.of(), choice.params()),
                conditionId -> JournalClientConnection.scenario().toggleCondition(currentTable, conditionId),
                target -> ScenarioRecommendationOverlay.open(overlays, currentTable, target), locateTarget);
        conditionView.setContent(conditionTree);

        overviewLabel = structure.displayName();

        Component toolName = Component.literal(choice.params().toolId().toString());
        for (var tool : options.tools()) {
            if (tool.id().equals(choice.params().toolId())) {
                toolName = tool.displayName();
                break;
            }
        }
        List<Component> paramsTooltip = new ArrayList<>();
        paramsTooltip.add(ScenarioSession.text("params.current", toolName,
                choice.params().luck(), choice.params().sampleCount()).copy().withStyle(TooltipBuilder.BODY));
        choice.params().toolEnchantments().forEach((id, level) -> paramsTooltip.add(
                ScenarioSession.text("params.enchant_level",
                        ScenarioParamsOverlay.enchantmentName(id), level).copy().withStyle(TooltipBuilder.ACCENT)));

        summary.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.LABEL);
        summary.configure(font, Component.empty(), JournalTextColors.NAME,
                new UiIcon.Item(new ItemStack(BuiltInRegistries.ITEM.get(choice.params().toolId()))),
                List.of(toolName.copy().withStyle(TooltipBuilder.BODY)), null);
        configureValue(font, luck, UiAtlas.LUCK_ICON, "params.luck_short",
                String.format(Locale.ROOT, "%s", choice.params().luck()));
        boolean showEnchantments = !options.enchantments().isEmpty() || !choice.params().toolEnchantments().isEmpty();
        enchantments.setVisible(showEnchantments);
        if (showEnchantments) {
            configureValue(font, enchantments, null, "params.enchantments_short",
                    String.valueOf(choice.params().toolEnchantments().size()));
        }

        probeButton.configure(font, Component.empty(), UiAtlas.SYNC_PLAYER,
                List.of(ScenarioSession.text("probe.button").copy().withStyle(TooltipBuilder.TITLE),
                        ScenarioSession.text("probe.hint").copy().withStyle(TooltipBuilder.LABEL)),
                () -> JournalClientConnection.scenario().requestPlayerProbe(currentTable));
        probeButton.setAccessibleName(ScenarioSession.text("probe.button"));
        probeButton.setEnabled(!JournalClientConnection.scenario().assistPending());

        paramsButton.configure(font, Component.empty(), UiAtlas.icon(UiAtlas.Icon.PARAMS), paramsTooltip,
                () -> overlays.open(new ScenarioParamsOverlay(overlays, currentTable, choice.conditions(),
                        options, choice.params())));
        paramsButton.setAccessibleName(ScenarioSession.text("params.title"));

        Component failure = "failed".equals(rawStatus)
                ? ScenarioSession.text("failure." + Objects.requireNonNull(snapshot).failure())
                : null;
        boolean isCached = "cached".equals(rawStatus);
        List<Component> calcTooltip = failure != null
                ? List.of(failure.copy().withStyle(TooltipBuilder.NEGATIVE))
                : isCached
                ? List.of(ScenarioSession.text("view_drops").copy().withStyle(TooltipBuilder.TITLE),
                ScenarioSession.text("cached_hint").copy().withStyle(TooltipBuilder.LABEL))
                : List.of(ScenarioSession.text("calculate").copy().withStyle(TooltipBuilder.TITLE),
                ScenarioSession.text(rawStatus).copy().withStyle(TooltipBuilder.LABEL));
        calculateButton.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.PRIMARY);
        calculateButton.configure(font,
                ScenarioSession.text(isCached
                        ? "view_drops_ready"
                        : "uncomputed".equals(rawStatus) || "failed".equals(rawStatus)
                        || "query_failed".equals(rawStatus) ? "calculate" : "results.status." + rawStatus),
                UiAtlas.statusBadge(rawStatus),
                calcTooltip,
                () -> {
                    if (isCached) {
                        viewDrops.run();
                    } else {
                        JournalClientConnection.scenario().request(currentTable, true);
                    }
                });
        calculateButton.setEnabled(!List.of("pending", "querying", "retrieving").contains(rawStatus));

        expandButton.configure(font, Component.empty(), UiAtlas.icon(UiAtlas.Icon.EXPAND),
                List.of(ScenarioSession.text("frame.expand").copy().withStyle(TooltipBuilder.BODY)),
                () -> {
                    var content = ScenarioExpandedOverlay.create(this, font);
                    UiLightbox lightbox = new UiLightbox(ScenarioExpandedOverlay.LABELS, content, overlays::close);
                    ScenarioUi.styleLightbox(lightbox);
                    lightbox.setText(ScenarioSession.text("conditions.title",
                            overviewLabel == null ? structure.displayName() : overviewLabel), null);
                    lightbox.setMaskClickCloses(false);
                    overlays.open(lightbox);
                });
        expandButton.setAccessibleName(ScenarioSession.text("frame.expand"));
    }

    private static void configureValue(Font font, UiControl control, @Nullable UiIcon icon, String key, String value) {
        Component full = ScenarioSession.text(key, value);
        int iconReserve = icon == null ? 0 : icon.width() + 4;
        Component label = font.width(full) <= control.bounds().width() - 4 - iconReserve
                ? full : Component.literal(value);
        control.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.LABEL);
        control.configure(font, label, icon, List.of(full.copy().withStyle(TooltipBuilder.BODY)), null);
    }

    static void renderConditionBackground(GuiGraphics graphics, UiDocumentTarget target, boolean selected) {
        UiRect rect = target.contentBounds();
        Object payload = target.hit().payload();
        if (payload instanceof ScenarioPageBuilder.Heading heading) {
            boolean isSection = heading == ScenarioPageBuilder.Heading.SECTION;
            if (isSection) {
                ScenarioAtlas.SECTION.render(graphics, rect);
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
