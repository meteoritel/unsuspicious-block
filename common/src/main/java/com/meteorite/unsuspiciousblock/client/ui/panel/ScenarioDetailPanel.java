package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.state.SimulationPreferenceStore;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground.BookLayout;
import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import com.meteorite.unsuspiciousblock.client.ui.layout.LayoutAware;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 场景详情页：统一场景选择、显式计算、模态入口以及按表和场景保存的框内视图。 */
public final class ScenarioDetailPanel implements PagePanel, LayoutAware, UiStateful {
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID,
            "textures/gui/toolbar_icons.png");
    private final OverlayLayer overlays;
    private final UiControl title = new UiControl();
    private final UiControl status = new UiControl();
    private final UiControl scenesButton = new UiControl();
    private final UiControl expandButton = new UiControl();
    private final UiControl paramsButton = new UiControl();
    private final UiControl calculateButton = new UiControl();
    private final List<UiControl> controls = List.of(title, status, scenesButton, expandButton, paramsButton, calculateButton);
    private final Map<String, FrameState> sceneViews = new LinkedHashMap<>();
    private BookLayout layout;
    @Nullable private ResourceLocation table;
    @Nullable private Font measuredFont;
    @Nullable private ScenarioFrameView frame;
    @Nullable private String viewKey;
    private long revision = Long.MIN_VALUE;
    private String input = "";
    private String requestStatus = "";
    private List<UiNode> tree = List.of();

    public ScenarioDetailPanel(BookLayout layout, OverlayLayer overlays) {
        this.layout = layout;
        this.overlays = overlays;
    }

    @Override public void applyLayout(BookLayout layout) {
        this.layout = layout;
        revision = Long.MIN_VALUE;
        if (frame != null) frame.release(0);
    }

    public void setTable(@Nullable ResourceLocation value) {
        if (Objects.equals(table, value)) return;
        saveCurrentView();
        table = value;
        viewKey = null;
        revision = Long.MIN_VALUE;
    }

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        sync(font);
        for (UiControl control : controls) control.render(graphics, font, mouseX, mouseY);
        if (frame != null) frame.render(graphics, mouseX, mouseY);
    }

    // 命中只查询一次：固定控件优先，其次框角控件，最后树内容。
    @Nullable private UiTarget hit(double x, double y) {
        for (UiControl control : controls) {
            UiTarget target = control.hit(x, y);
            if (target != null) return target;
        }
        return frame == null ? null : frame.hit(x, y);
    }

    public void renderTooltip(GuiGraphics graphics, Font font, int x, int y) {
        UiTarget target = hit(x, y);
        if (target != null && !target.tooltip().isEmpty()) graphics.renderComponentTooltip(font, target.tooltip(), x, y);
    }

    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        sync(Minecraft.getInstance().font);
        for (UiControl control : controls) {
            UiTarget target = control.hit(x, y);
            if (target == null) continue;
            if (target.action() != null) target.action().run();
            return true;
        }
        return frame != null && frame.click(x, y, button);
    }

    public boolean mouseDragged(double x, double y, int button) { return frame != null && frame.drag(x, y, button); }
    public boolean mouseReleased(int button) { return frame != null && frame.release(button); }
    public boolean mouseScrolled(double x, double y, double amount) {
        sync(Minecraft.getInstance().font);
        return frame != null && frame.scroll(x, y, amount);
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
        saveCurrentView();
        if (frame != null) frame.release(0);
        ScenarioSimulationClientState.select(table, scenes.get(Math.clamp(page, 0, scenes.size() - 1)).scenarioKey(), selected.params());
    }
    @Override public void changePage(int delta) { setPage(getPage() + delta); }

    private void sync(Font font) {
        if (frame == null || measuredFont != font) {
            saveCurrentView();
            measuredFont = font;
            frame = new ScenarioFrameView(font);
            viewKey = null;
            revision = Long.MIN_VALUE;
        }
        int x = layout.rightPageX() + 4;
        int y = layout.rightPageY();
        int width = layout.rightPageWidth() - 8;
        title.setBounds(x, y + 6, width - 16, 12);
        status.setBounds(x + width - 16, y + 6, 16, 12);
        scenesButton.setBounds(x, y + 18, 30, 14);
        expandButton.setBounds(x + 32, y + 18, 24, 14);
        paramsButton.setBounds(x + 58, y + 18, 24, 14);
        calculateButton.setBounds(x + 84, y + 18, width - 84, 14);
        frame.setBounds(x, y + JournalLayout.SCENARIO_FRAME_TOP, width, JournalLayout.SCENARIO_FRAME_HEIGHT);
        var selection = selection();
        CatalogTableDto structure = structure();
        long currentRevision = ArchaeologyJournalClientState.getCatalogRevision();
        String nextStatus = table == null || input.isEmpty() ? "uncomputed" : ScenarioSimulationClientState.status(table, input);
        if (revision == currentRevision && requestStatus.equals(nextStatus)) return;
        revision = currentRevision;
        input = selection == null ? "" : ScenarioSimulationClientState.inputKey(selection);
        requestStatus = table == null || input.isEmpty() ? "uncomputed" : ScenarioSimulationClientState.status(table, input);
        String key = table == null || selection == null ? "" : table + "#" + selection.scene();
        if (!Objects.equals(viewKey, key)) {
            saveCurrentView();
            viewKey = key;
            frame.restore(sceneViews.getOrDefault(key, FrameState.DEFAULT));
        }
        if (structure == null || structure.options() == null || selection == null) {
            tree = List.of(new UiNode.Row(ScenarioSimulationClientState.text("unavailable"), UiTextPalette.Parchment.LABEL));
            for (UiControl control : controls) control.configure(font, Component.empty(), UiTextPalette.Parchment.BODY,
                    null, List.of(), null);
            title.configure(font, ScenarioSimulationClientState.text("unavailable"), UiTextPalette.Parchment.LABEL, null, List.of(), null);
        } else {
            ScenarioPresentation presentation = ScenarioPresentation.resolve(table, selection.scene(), selection.params());
            tree = ScenarioPageBuilder.buildContent(structure, selection.scene(), presentation);
            List<Component> titleTooltip = new ArrayList<>(ScenarioLabel.definition(structure.options(), selection.scene()));
            titleTooltip.add(ScenarioSimulationClientState.text(presentation.status()));
            title.configure(font, ScenarioLabel.label(structure.options(), selection.scene()), UiTextPalette.Parchment.TITLE,
                    null, titleTooltip, null);
            List<Component> statusTooltip = new ArrayList<>();
            statusTooltip.add(ScenarioSimulationClientState.text(presentation.status()));
            if (presentation.status().equals("failed")) statusTooltip.add(ScenarioSimulationClientState.text(
                    "failure." + ScenarioSimulationClientState.failure(table, input)));
            status.configure(font, Component.empty(), UiTextPalette.Parchment.TITLE, presentation.badge(), statusTooltip, null);
            ResourceLocation currentTable = table;
            scenesButton.configure(font, Component.empty(), UiTextPalette.Parchment.TITLE, icon(1, 0),
                    List.of(ScenarioSimulationClientState.text("scene.toggle")),
                    () -> overlays.open(new ScenarioSelectionOverlay(overlays, dropdownX(), dropdownY(), currentTable,
                            structure.options(), selection.params(), getPage(), this::setPage)));
            expandButton.configure(font, Component.empty(), UiTextPalette.Parchment.TITLE, icon(1, 2),
                    List.of(ScenarioSimulationClientState.text("frame.expand")), () -> {
                        if (frame != null) overlays.open(new ScenarioExpandedOverlay(overlays, this, font, frame.state()));
                    });
            List<Component> paramsTooltip = new ArrayList<>();
            Component toolName = Component.literal(selection.params().toolId().toString());
            for (var tool : structure.options().tools()) if (tool.id().equals(selection.params().toolId())) toolName = tool.displayName();
            paramsTooltip.add(ScenarioSimulationClientState.text("params.current", toolName, selection.params().luck(), selection.params().sampleCount()));
            selection.params().toolEnchantments().forEach((id, level) -> paramsTooltip.add(ScenarioSimulationClientState.text(
                    "params.enchant_level", ScenarioParamsOverlay.enchantmentName(id), level)));
            paramsButton.configure(font, Component.empty(), UiTextPalette.Parchment.TITLE, icon(0, 2), paramsTooltip,
                    () -> overlays.open(new ScenarioParamsOverlay(overlays, table, selection.scene(), structure.options(), selection.params())));
            calculateButton.configure(font, ScenarioSimulationClientState.text("calculate"), UiTextPalette.Parchment.TITLE, null,
                    List.of(ScenarioSimulationClientState.text("calculate")), () -> ScenarioSimulationClientState.request(table, true));
        }
        frame.setContent(tree);
    }

    private static UiIcon icon(int col, int row) { return new UiIcon.Sprite(ATLAS, col * 9, row * 9, 9, 9, 81, 27); }
    List<UiNode> tree() { return tree; }
    int dropdownX() { return layout.rightPageX() + 4; }
    int dropdownY() { return layout.rightPageY() + 32; }

    private void saveCurrentView() {
        if (frame == null || viewKey == null || viewKey.isEmpty()) return;
        sceneViews.put(viewKey, frame.state());
        while (sceneViews.size() > 512) sceneViews.remove(sceneViews.keySet().iterator().next());
    }

    @Override public void saveUiState(CompoundTag tag) {
        saveCurrentView();
        sceneViews.forEach((key, state) -> tag.put(key, state.save()));
    }

    @Override public void loadUiState(CompoundTag tag) {
        sceneViews.clear();
        for (String key : tag.getAllKeys()) {
            if (sceneViews.size() >= 512) break;
            sceneViews.put(key, FrameState.load(tag.getCompound(key)));
        }
        viewKey = null;
        revision = Long.MIN_VALUE;
    }
}
