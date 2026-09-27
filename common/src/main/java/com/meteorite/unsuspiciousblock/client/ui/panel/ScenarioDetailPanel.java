package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.state.SimulationPreferenceStore;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground.BookLayout;
import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import com.meteorite.unsuspiciousblock.client.ui.layout.LayoutAware;
import com.meteorite.unsuspiciousblock.client.ui.overlay.LightboxOverlay;
import com.meteorite.unsuspiciousblock.client.ui.overlay.OverlayLayer;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioParamsOverlay;
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

/** 场景详情页：结果优先，条件独立阅读；同一输入驱动标题、状态、结果与提示。 */
public final class ScenarioDetailPanel implements PagePanel, LayoutAware, UiStateful {
    private enum Section { RESULTS, CONDITIONS }

    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID,
            "textures/gui/toolbar_icons.png");
    private static final int MAX_SAVED_POSITIONS = 512;
    private final OverlayLayer overlays;
    private final UiFocusManager focus = new UiFocusManager();
    private final UiControl title = new UiControl();
    private final UiControl status = new UiControl();
    private final UiControl summary = new UiControl();
    private final UiControl scenesButton = new UiControl();
    private final UiControl paramsButton = new UiControl();
    private final UiControl calculateButton = new UiControl();
    private final UiControl resultsTab = new UiControl();
    private final UiControl conditionsTab = new UiControl();
    private final UiControl expandButton = new UiControl();
    private final List<UiControl> controls = List.of(title, status, summary, scenesButton, paramsButton,
            calculateButton, resultsTab, conditionsTab, expandButton);
    private final Map<String, Integer> resultPositions = new LinkedHashMap<>();
    private final Map<String, Integer> conditionPositions = new LinkedHashMap<>();
    private BookLayout layout;
    @Nullable private ResourceLocation table;
    @Nullable private Font measuredFont;
    @Nullable private ScenarioResultView results;
    @Nullable private ScenarioConditionView conditions;
    @Nullable private String viewKey;
    private Section section = Section.RESULTS;
    private long revision = Long.MIN_VALUE;
    private String input = "";
    private String requestStatus = "";
    private List<UiNode> conditionTree = List.of();
    @Nullable private Component fullSceneLabel;

    public ScenarioDetailPanel(BookLayout layout, OverlayLayer overlays) {
        this.layout = layout;
        this.overlays = overlays;
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
        section = Section.RESULTS;
        revision = Long.MIN_VALUE;
        focus.clearFocus();
    }

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        sync(font);
        for (UiControl control : controls) control.render(graphics, font, mouseX, mouseY);
        if (section == Section.RESULTS && results != null) results.render(graphics, mouseX, mouseY);
        if (section == Section.CONDITIONS && conditions != null) conditions.render(graphics, mouseX, mouseY);
    }

    @Nullable private UiTarget hit(double x, double y) {
        for (UiControl control : controls) {
            UiTarget target = control.hit(x, y);
            if (target != null) return target;
        }
        return null;
    }

    public void renderTooltip(GuiGraphics graphics, Font font, int x, int y) {
        UiTarget target = hit(x, y);
        if (target != null) {
            if (!target.tooltip().isEmpty()) graphics.renderComponentTooltip(font, target.tooltip(), x, y);
            return;
        }
        if (section == Section.RESULTS && results != null) results.renderTooltip(graphics, x, y);
        if (section == Section.CONDITIONS && conditions != null) conditions.renderTooltip(graphics, x, y);
    }

    public ItemStack hoveredItem(double x, double y) {
        if (section != Section.RESULTS || results == null) return ItemStack.EMPTY;
        sync(Minecraft.getInstance().font);
        return results.hoveredItem(x, y);
    }

    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        sync(Minecraft.getInstance().font);
        UiTarget target = hit(x, y);
        if (target != null) {
            focus.clearFocus();
            if (target.action() != null) target.action().run();
            return true;
        }
        if (section == Section.RESULTS && results != null) return results.mouseClicked(x, y, button);
        return section == Section.CONDITIONS && conditions != null && conditions.mouseClicked(x, y, button);
    }

    public boolean mouseDragged(double y, int button) {
        if (button != 0) return false;
        if (section == Section.RESULTS && results != null) return results.mouseDragged(y);
        return section == Section.CONDITIONS && conditions != null && conditions.mouseDragged(y);
    }

    public boolean mouseReleased(int button) {
        if (section == Section.RESULTS && results != null) return results.mouseReleased(button);
        return section == Section.CONDITIONS && conditions != null && conditions.mouseReleased();
    }

    public boolean mouseScrolled(double x, double y, double amount) {
        if (section == Section.RESULTS && results != null) return results.mouseScrolled(x, y, amount);
        return section == Section.CONDITIONS && conditions != null && conditions.mouseScrolled(x, y, amount);
    }

    public boolean keyPressed(int key, int scan, int modifiers) {
        sync(Minecraft.getInstance().font);
        return focus.keyPressed(key, scan, modifiers);
    }

    @Override public boolean containsMouse(double x, double y) {
        return x >= layout.rightPageX() && x < layout.rightPageRight()
                && y >= layout.rightPageY() && y < layout.rightPageBottom();
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
        if (results == null || conditions == null || measuredFont != font) {
            saveCurrentPosition();
            measuredFont = font;
            results = new ScenarioResultView(font);
            conditions = new ScenarioConditionView(font);
            viewKey = null;
            revision = Long.MIN_VALUE;
        }
        int x = layout.rightPageX() + 4;
        int y = layout.rightPageY();
        int width = layout.rightPageWidth() - 8;
        title.setBounds(x, y + 5, width - 66, 13);
        status.setBounds(x + width - 64, y + 5, 64, 13);
        summary.setBounds(x, y + 19, width, 11);
        scenesButton.setBounds(x, y + 32, 54, 14);
        paramsButton.setBounds(x + 56, y + 32, 44, 14);
        calculateButton.setBounds(x + 102, y + 32, width - 102, 14);
        resultsTab.setBounds(x, y + 48, 62, 14);
        conditionsTab.setBounds(x + 64, y + 48, 62, 14);
        expandButton.setBounds(x + 128, y + 48, width - 128, 14);
        results.setBounds(x, y + 64, width, 136);
        conditions.setBounds(x, y + 64, width, 136);

        var choice = selection();
        CatalogTableDto structure = structure();
        String nextInput = choice == null ? "" : ScenarioSimulationClientState.inputKey(choice);
        String nextStatus = table == null || nextInput.isEmpty()
                ? "uncomputed" : ScenarioSimulationClientState.status(table, nextInput);
        long nextRevision = ArchaeologyJournalClientState.getCatalogRevision();
        if (revision == nextRevision && input.equals(nextInput) && requestStatus.equals(nextStatus)) return;

        saveCurrentPosition();
        revision = nextRevision;
        input = nextInput;
        requestStatus = nextStatus;
        viewKey = table == null || nextInput.isEmpty() ? null : table + "#" + nextInput;
        if (structure == null || structure.options() == null || choice == null) {
            configureUnavailable(font);
        } else {
            configureAvailable(font, structure, choice, nextStatus);
        }
        if (viewKey != null) {
            results.setOffset(resultPositions.getOrDefault(viewKey, 0));
            conditions.setOffset(conditionPositions.getOrDefault(viewKey, 0));
        }
        rebuildFocus();
    }

    private void configureUnavailable(Font font) {
        ScenarioConditionView conditionView = Objects.requireNonNull(conditions);
        ScenarioResultView resultView = Objects.requireNonNull(results);
        conditionTree = List.of(new UiNode.Row(ScenarioSimulationClientState.text("unavailable"),
                UiTextPalette.Parchment.LABEL));
        conditionView.setContent(conditionTree);
        resultView.setContent(List.of(), "uncomputed", null);
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
        ScenarioResultView resultView = Objects.requireNonNull(results);
        var options = Objects.requireNonNull(structure.options());
        for (UiControl control : controls) control.setVisible(true);
        ResourceLocation currentTable = Objects.requireNonNull(table);
        ScenarioPresentation presentation = ScenarioPresentation.resolve(currentTable, choice.scene(), choice.params());
        conditionTree = ScenarioPageBuilder.buildConditions(structure, choice.scene());
        conditionView.setContent(conditionTree);
        Component failure = "failed".equals(presentation.status())
                ? ScenarioSimulationClientState.text("failure." + ScenarioSimulationClientState.failure(currentTable, input))
                : null;
        resultView.setContent(ScenarioPageBuilder.outcomes(choice.scene(), presentation), presentation.status(), failure);

        Component sceneLabel = ScenarioLabel.label(options, choice.scene());
        fullSceneLabel = sceneLabel;
        List<Component> titleTooltip = new ArrayList<>();
        titleTooltip.add(sceneLabel);
        titleTooltip.addAll(ScenarioLabel.definition(options, choice.scene()));
        title.configure(font, ScenarioLabel.shortLabel(choice.scene()), UiTextPalette.Parchment.TITLE,
                null, titleTooltip, null);
        List<Component> statusTooltip = new ArrayList<>();
        statusTooltip.add(ScenarioSimulationClientState.text(presentation.status()));
        if (failure != null) statusTooltip.add(failure);
        status.configure(font, ScenarioSimulationClientState.text("results.status." + presentation.status()),
                UiTextPalette.Parchment.BODY, presentation.badge(), statusTooltip, null);

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
        summary.configure(font, ScenarioSimulationClientState.text("results.input_summary", toolName,
                        String.format(Locale.ROOT, "%.2f", choice.params().luck()), choice.params().sampleCount()),
                UiTextPalette.Parchment.LABEL, null, paramsTooltip, null);

        scenesButton.configure(font, ScenarioSimulationClientState.text("scene.toggle"),
                UiTextPalette.Parchment.TITLE, null, List.of(sceneLabel), () ->
                        overlays.open(new ScenarioSelectionOverlay(overlays, currentTable,
                                options, choice.params(), getPage(), this::setPage), scenesButton));
        paramsButton.configure(font, ScenarioSimulationClientState.text("params.title"),
                UiTextPalette.Parchment.TITLE, null, paramsTooltip, () ->
                        overlays.open(new ScenarioParamsOverlay(overlays, currentTable, choice.scene(),
                                options, choice.params()), paramsButton));
        calculateButton.configure(font, ScenarioSimulationClientState.text("calculate"),
                UiTextPalette.Parchment.TITLE, null, List.of(ScenarioSimulationClientState.text("calculate")),
                () -> ScenarioSimulationClientState.request(currentTable, true));
        calculateButton.setEnabled(!"pending".equals(rawStatus) && !"cached".equals(rawStatus));

        resultsTab.configure(font, ScenarioSimulationClientState.text("results.tab"),
                UiTextPalette.Parchment.TITLE, null, List.of(), () -> switchSection(Section.RESULTS));
        conditionsTab.configure(font, ScenarioSimulationClientState.text("conditions.tab"),
                UiTextPalette.Parchment.TITLE, null, List.of(), () -> switchSection(Section.CONDITIONS));
        expandButton.configure(font, Component.empty(), UiTextPalette.Parchment.TITLE, expandIcon(),
                List.of(ScenarioSimulationClientState.text("frame.expand")), () -> {
                    ScenarioExpandedOverlay content = new ScenarioExpandedOverlay(this, font);
                    UiLightbox lightbox = new UiLightbox(ScenarioExpandedOverlay.LABELS, content, overlays::close);
                    lightbox.setText(fullSceneLabel, null);
                    lightbox.setMaskClickCloses(false);
                    overlays.open(new LightboxOverlay(overlays, lightbox), expandButton);
                });
        updateSectionControls();
    }

    private void switchSection(Section next) {
        if (section == next) return;
        saveCurrentPosition();
        section = next;
        updateSectionControls();
        rebuildFocus();
    }

    private void updateSectionControls() {
        resultsTab.setSelected(section == Section.RESULTS);
        conditionsTab.setSelected(section == Section.CONDITIONS);
        expandButton.setVisible(section == Section.CONDITIONS);
    }

    private void rebuildFocus() {
        focus.beginUpdate();
        focus.add(scenesButton);
        focus.add(paramsButton);
        focus.add(calculateButton);
        focus.add(resultsTab);
        focus.add(conditionsTab);
        if (expandButton.isVisible()) focus.add(expandButton);
        focus.endUpdate();
    }

    private static UiIcon expandIcon() {
        return new UiIcon.Sprite(ATLAS, 9, 18, 9, 9, 81, 27);
    }

    List<UiNode> tree() { return conditionTree; }
    private void saveCurrentPosition() {
        if (viewKey == null || results == null || conditions == null) return;
        remember(resultPositions, viewKey, results.offset());
        remember(conditionPositions, viewKey, conditions.offset());
    }

    private static void remember(Map<String, Integer> positions, String key, int offset) {
        positions.put(key, offset);
        while (positions.size() > MAX_SAVED_POSITIONS) positions.remove(positions.keySet().iterator().next());
    }

    @Override public void saveUiState(CompoundTag tag) {
        saveCurrentPosition();
        tag.putString("section", section.name());
        tag.put("resultOffsets", savePositions(resultPositions));
        tag.put("conditionOffsets", savePositions(conditionPositions));
    }

    @Override public void loadUiState(CompoundTag tag) {
        resultPositions.clear();
        conditionPositions.clear();
        loadPositions(tag.getCompound("resultOffsets"), resultPositions);
        loadPositions(tag.getCompound("conditionOffsets"), conditionPositions);
        try {
            section = Section.valueOf(tag.getString("section"));
        } catch (IllegalArgumentException exception) {
            section = Section.RESULTS;
        }
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
