package com.meteorite.unsuspiciousblock.client.ui.panel;

import net.minecraft.locale.Language;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.state.SimulationPreferenceStore;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground.BookLayout;
import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.*;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.layout.*;
import com.meteorite.meteosuikit.api.render.*;
import com.meteorite.meteosuikit.api.style.*;
import com.meteorite.meteosuikit.api.text.*;
import com.meteorite.unsuspiciousblock.client.ui.layout.LayoutAware;
import com.meteorite.unsuspiciousblock.client.ui.overlay.JournalOverlays;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioParamsOverlay;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioRecommendationOverlay;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.client.ui.support.*;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
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

/** 场景详情页：紧凑参数栏与条件树；物品及其概率统一在物品网格阅读。 */
public final class ScenarioDetailPanel implements PagePanel, LayoutAware, UiStateful, UiInputContainer {
    private static final int MAX_SAVED_POSITIONS = 512;
    private final JournalOverlays overlays;
    private final UiChildren<UiInputTarget> children = new UiChildren<>();
    private final UiControl title = new UiControl();
    private final UiControl summary = new UiControl();
    private final UiControl luck = new UiControl();
    private final UiControl samples = new UiControl();
    private final UiControl paramsButton = new UiControl();
    private final UiControl calculateButton = new UiControl();
    private final UiControl conditionsHeading = new UiControl();
    private final UiControl expandButton = new UiControl();
    private final List<UiControl> controls = List.of(title, summary, luck, samples, paramsButton,
            calculateButton, conditionsHeading, expandButton);
    private final Map<String, Integer> conditionPositions = new LinkedHashMap<>();
    private BookLayout layout;
    @Nullable private ResourceLocation table;
    @Nullable private Font measuredFont;
    @Nullable private ScenarioConditionView conditions;
    @Nullable private String viewKey;
    private long revision = Long.MIN_VALUE;
    private String input = "";
    private String requestStatus = "";
    private Language language;
    private boolean showOther;
    private List<UiNode> conditionTree = List.of();
    @Nullable private Component fullSceneLabel;
    private java.util.function.BiPredicate<String, String> targetVisible = (kind, target) -> false;
    private java.util.function.BiConsumer<String, String> locateTarget = (kind, target) -> {};
    private Runnable viewDrops = () -> {};

    public void setTargetNavigation(java.util.function.BiPredicate<String, String> visible,
                                    java.util.function.BiConsumer<String, String> locate, Runnable viewDrops) {
        this.targetVisible = visible;
        this.locateTarget = (kind, target) -> { if (overlays.isOpen()) overlays.close(); locate.accept(kind, target); };
        this.viewDrops = viewDrops;
    }

    public ScenarioDetailPanel(BookLayout layout, JournalOverlays overlays) {
        this.layout = layout;
        this.overlays = overlays;
        children.addAll(controls);
        for (UiControl control : List.of(title, paramsButton, calculateButton, expandButton)) control.setFocusable(true);
    }

    @Override public void applyLayout(BookLayout layout) {
        saveCurrentPosition();
        this.layout = layout;
        revision = Long.MIN_VALUE;
    }

    public void setTable(@Nullable ResourceLocation value) {
        if (Objects.equals(table, value)) return;
        saveCurrentPosition();
        table = value;
        viewKey = null;
        revision = Long.MIN_VALUE;
        showOther = false;
    }

    @Override public UiPointerHit hitTest(double x, double y) { return UiInputContainer.super.hitTest(x, y); }
    @Override public UiChildren<UiInputTarget> inputChildren() { return children; }
    @Override public void prepareUi() { sync(Minecraft.getInstance().font); }
    @Override public void render(GuiGraphics graphics, Font font, int x, int y) { }
    public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
        for (UiControl control : controls) control.render(graphics, font, pointer);
        if (conditions != null) conditions.render(graphics, pointer);
    }

    @Override public boolean containsMouse(double x, double y) {
        return PagePanel.containsPageBounds(x, y,
                layout.rightPageX(), layout.rightPageY(),
                layout.rightPageRight(), layout.rightPageBottom());
    }

    List<CatalogTableDto.ScenarioAssumptions> scenes() {
        CatalogTableDto dto = structure();
        return dto == null || dto.options() == null ? List.of() : dto.options().scenes();
    }

    @Nullable CatalogTableDto structure() { return table == null ? null : ScenarioSimulationClientState.table(table); }
    @Nullable SimulationPreferenceStore.Selection selection() {
        return table == null ? null : ScenarioSimulationClientState.selection(table);
    }

    @Override public int pageCount() { return Math.max(1, scenes().size()); }
    @Override public int getPage() {
        var selected = selection();
        List<CatalogTableDto.ScenarioAssumptions> scenes = scenes();
        if (selected != null) for (int i = 0; i < scenes.size(); i++) {
            if (scenes.get(i).scenarioKey().equals(selected.scene())) return i;
        }
        return 0;
    }

    @Override public void setPage(int page) {
        var selected = selection();
        var scenes = scenes();
        if (table == null || selected == null || scenes.isEmpty()) return;
        saveCurrentPosition();
        ScenarioSimulationClientState.select(table, scenes.get(Math.clamp(page, 0, scenes.size() - 1)).scenarioKey(),
                selected.params());
    }
    @Override public void changePage(int delta) { setPage(getPage() + delta); }

    private void sync(Font font) {
        if (table != null) ScenarioSimulationClientState.ensureCacheQuery(table);
        if (conditions == null || measuredFont != font) {
            saveCurrentPosition();
            measuredFont = font;
            if (conditions != null) children.remove(conditions);
            conditions = new ScenarioConditionView(font);
            children.add(conditions);
            viewKey = null;
            revision = Long.MIN_VALUE;
        }
        int x = layout.rightPageX() + 4;
        int y = layout.rightPageY();
        int width = layout.rightPageWidth() - 8;
        title.setBounds(x, y + 3, width, 14);
        summary.setBounds(x, y + 19, 20, 16);
        paramsButton.setBounds(x + width - 16, y + 19, 16, 16);
        int readoutWidth = Math.max(0, width - 42);
        int luckWidth = readoutWidth * 2 / 5;
        luck.setBounds(x + 22, y + 19, luckWidth, 16);
        samples.setBounds(x + 22 + luckWidth, y + 19, readoutWidth - luckWidth, 16);
        calculateButton.setBounds(x, y + 37, width, 14);
        conditionsHeading.setBounds(x, y + 53, width - 20, 12);
        expandButton.setBounds(x + width - 18, y + 52, 18, 14);
        int contentHeight = Math.max(1, layout.rightPageBottom() - 24 - (y + 67));
        conditions.setBounds(x, y + 67, width, contentHeight);

        var choice = selection();
        CatalogTableDto structure = structure();
        String nextInput = choice == null ? "" : ScenarioSimulationClientState.inputKey(choice);
        String nextStatus = table == null || nextInput.isEmpty()
                ? "uncomputed" : ScenarioSimulationClientState.status(table, nextInput);
        long nextRevision = ArchaeologyJournalClientState.getCatalogRevision();
        Language nextLanguage = Language.getInstance();
        if (revision == nextRevision && input.equals(nextInput) && requestStatus.equals(nextStatus)
                && language == nextLanguage) return;

        saveCurrentPosition();
        revision = nextRevision;
        language = nextLanguage;
        input = nextInput;
        requestStatus = nextStatus;
        viewKey = table == null || nextInput.isEmpty() ? null : table + "#" + nextInput;
        if (structure == null || structure.options() == null || choice == null) {
            configureUnavailable(font);
        } else {
            configureAvailable(font, structure, choice, nextStatus);
        }
        if (viewKey != null) {
            conditions.setOffset(conditionPositions.getOrDefault(viewKey, 0));
        }
    }

    private void configureUnavailable(Font font) {
        ScenarioConditionView conditionView = Objects.requireNonNull(conditions);
        conditionTree = List.of(new UiNode.Row(ScenarioSimulationClientState.text("unavailable"),
                UiTextPalette.Parchment.LABEL));
        conditionView.setContent(conditionTree);
        for (UiControl control : controls) {
            control.configure(font, Component.empty(), UiTextPalette.Parchment.BODY, null, List.of(), null);
            control.setVisible(false);
        }
        title.setVisible(true);
        title.configure(font, ScenarioSimulationClientState.text("unavailable"),
                UiTextPalette.Parchment.LABEL, null, List.of(), null);
        fullSceneLabel = null;
    }

    private void configureAvailable(Font font, CatalogTableDto structure,
                                    SimulationPreferenceStore.Selection choice, String rawStatus) {
        ScenarioConditionView conditionView = Objects.requireNonNull(conditions);
        var options = Objects.requireNonNull(structure.options());
        for (UiControl control : controls) {
            control.setVisible(true);
            control.setStyle(ScenarioUi.QUIET);
        }
        ResourceLocation currentTable = Objects.requireNonNull(table);
        UiAction openParams = () -> overlays.open(new ScenarioParamsOverlay(overlays, currentTable, choice.scene(),
                options, choice.params()));
        conditionTree = ScenarioPageBuilder.buildConditions(structure, choice.scene(), showOther,
                () -> { showOther = !showOther; revision = Long.MIN_VALUE; },
                child -> ScenarioRecommendationOverlay.open(overlays, currentTable,
                        SimulationAssistTarget.childTable(child)), targetVisible, locateTarget);
        conditionView.setContent(conditionTree);
        Component failure = "failed".equals(rawStatus)
                ? ScenarioSimulationClientState.text("failure." + ScenarioSimulationClientState.failure(currentTable, input))
                : null;

        fullSceneLabel = ScenarioLabel.shortLabel(choice.scene());
        List<Component> titleTooltip = new ArrayList<>(ScenarioLabel.tooltip(options, choice.scene()));
        title.configure(font, ScenarioLabel.shortLabel(choice.scene()), UiTextPalette.Parchment.TITLE,
                ScenarioUi.icon(ScenarioUi.Icon.DOWN), titleTooltip, () ->
                        overlays.open(new ScenarioSelectionOverlay(overlays, currentTable,
                                options, choice.params(), getPage(), this::setPage)));

        Component toolName = Component.literal(choice.params().toolId().toString());
        for (var tool : options.tools()) {
            if (tool.id().equals(choice.params().toolId())) {
                toolName = tool.displayName();
                break;
            }
        }
        List<Component> paramsTooltip = new ArrayList<>();
        paramsTooltip.add(ScenarioSimulationClientState.text("params.current", toolName,
                choice.params().luck(), choice.params().sampleCount()));
        choice.params().toolEnchantments().forEach((id, level) -> paramsTooltip.add(
                ScenarioSimulationClientState.text("params.enchant_level",
                        ScenarioParamsOverlay.enchantmentName(id), level)));
        summary.setStyle(ScenarioUi.READOUT);
        summary.configure(font, Component.empty(), UiTextPalette.Parchment.NAME,
                new UiIcon.Item(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(choice.params().toolId()))),
                List.of(toolName), null);
        configureValue(font, luck, "params.luck_short", String.format(Locale.ROOT, "%s", choice.params().luck()));
        configureValue(font, samples, "params.enchantments_short", String.valueOf(choice.params().toolEnchantments().size()));
        paramsButton.configure(font, Component.empty(),
                UiTextPalette.Parchment.TITLE, ScenarioUi.icon(ScenarioUi.Icon.PARAMS), paramsTooltip, openParams);
        paramsButton.setAccessibleName(ScenarioSimulationClientState.text("params.title"));
        calculateButton.setStyle(ScenarioUi.ACTION);
        calculateButton.configure(font, ScenarioSimulationClientState.text("cached".equals(rawStatus)
                        ? "view_drops" : "uncomputed".equals(rawStatus) || "failed".equals(rawStatus)
                        || "query_failed".equals(rawStatus) ? "calculate" : "results.status." + rawStatus),
                UiTextPalette.Parchment.TITLE, ScenarioUi.icon(ScenarioUi.Icon.CALCULATE),
                failure == null ? List.of(ScenarioSimulationClientState.text("calculate"), ScenarioSimulationClientState.text(rawStatus)) : List.of(failure),
                () -> { if ("cached".equals(rawStatus)) viewDrops.run(); else ScenarioSimulationClientState.request(currentTable, true); });
        calculateButton.setEnabled(!List.of("pending", "querying", "retrieving").contains(rawStatus));

        conditionsHeading.setStyle(ScenarioUi.READOUT);
        conditionsHeading.configure(font, ScenarioSimulationClientState.text("conditions.heading"),
                UiTextPalette.Parchment.TITLE, null, List.of(), null);
        expandButton.configure(font, Component.empty(), UiTextPalette.Parchment.TITLE, ScenarioUi.icon(ScenarioUi.Icon.EXPAND),
                List.of(ScenarioSimulationClientState.text("frame.expand")), () -> {
                    ScenarioExpandedOverlay content = new ScenarioExpandedOverlay(this, font);
                    UiLightbox lightbox = new UiLightbox(ScenarioExpandedOverlay.LABELS, content, overlays::close);
                    ScenarioUi.styleLightbox(lightbox);
                    lightbox.setText(ScenarioSimulationClientState.text("conditions.title", fullSceneLabel), null);
                    lightbox.setMaskClickCloses(false);
                    overlays.open(lightbox);
                });
        expandButton.setAccessibleName(ScenarioSimulationClientState.text("frame.expand"));
    }

    private static void configureValue(Font font, UiControl control, String key, String value) {
        Component full = ScenarioSimulationClientState.text(key, value);
        Component label = font.width(full) <= control.bounds().width() - 6 ? full : Component.literal(value);
        control.setStyle(ScenarioUi.READOUT);
        control.configure(font, label, UiTextPalette.Parchment.BODY, null, List.of(full), null);
    }

    List<UiNode> tree() { return conditionTree; }
    private void saveCurrentPosition() {
        if (viewKey == null || conditions == null) return;
        remember(conditionPositions, viewKey, conditions.offset());
    }

    private static void remember(Map<String, Integer> positions, String key, int offset) {
        positions.put(key, offset);
        while (positions.size() > MAX_SAVED_POSITIONS) positions.remove(positions.keySet().iterator().next());
    }

    @Override public void saveUiState(CompoundTag tag) {
        saveCurrentPosition();
        tag.put("conditionOffsets", savePositions(conditionPositions));
    }

    @Override public void loadUiState(CompoundTag tag) {
        conditionPositions.clear();
        loadPositions(tag.getCompound("conditionOffsets"), conditionPositions);
        viewKey = null;
        revision = Long.MIN_VALUE;
    }

    private static CompoundTag savePositions(Map<String, Integer> positions) {
        CompoundTag tag = new CompoundTag();
        positions.forEach(tag::putInt);
        return tag;
    }

    private static void loadPositions(CompoundTag tag, Map<String, Integer> positions) {
        for (String key : tag.getAllKeys()) {
            if (positions.size() >= MAX_SAVED_POSITIONS) break;
            positions.put(key, Math.max(0, tag.getInt(key)));
        }
    }
}
