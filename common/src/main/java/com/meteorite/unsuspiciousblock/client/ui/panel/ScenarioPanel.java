package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioParamsOverlay;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState.text;

/** 网格页的两行紧凑场景栏：短名与状态、参数入口与计算，不挤占物品网格。 */
public final class ScenarioPanel {
    private final UiControlGroup controls = new UiControlGroup();
    private final UiFocusManager focus = new UiFocusManager();
    private final int x, y, width;
    @Nullable private ResourceLocation table;
    @Nullable private Runnable openScenes;
    @Nullable private Runnable openParams;
    @Nullable private Font measuredFont;
    private String headerKey = "";
    private List<Component> tooltip = List.of();

    public ScenarioPanel(JournalBookBackground.BookLayout layout) {
        x = layout.rightPageX() + 4;
        y = layout.rightPageY() + 6;
        width = layout.rightPageWidth() - 8;
        controls.setStyle(ScenarioUi.QUIET);
    }

    public void setTable(@Nullable ResourceLocation value) {
        if (Objects.equals(table, value)) return;
        table = value;
        headerKey = "";
        focus.clearFocus();
    }
    public void setOpenScenes(@Nullable Runnable value) { openScenes = value; }
    public void setOpenParams(@Nullable Runnable value) { openParams = value; }

    private void sync(Font font) {
        var choice = ScenarioSimulationClientState.selection(table);
        var structure = ScenarioSimulationClientState.table(table);
        if (choice == null || structure == null || structure.options() == null) {
            controls.beginUpdate(); controls.endUpdate();
            focus.clearFocus(); headerKey = "";
            return;
        }
        String input = ScenarioSimulationClientState.inputKey(choice);
        ScenarioPresentation presentation = ScenarioPresentation.resolve(table, choice.scene(), choice.params());
        String key = table + "#" + input + "#" + presentation.status() + "#"
                + ArchaeologyJournalClientState.getCatalogRevision() + "#"
                + Minecraft.getInstance().getLanguageManager().getSelected();
        if (headerKey.equals(key) && measuredFont == font) return;
        headerKey = key; measuredFont = font;
        controls.beginUpdate(); focus.beginUpdate();
        UiControl scene = place("scene", x, y, width - 66, 12);
        scene.configure(font, ScenarioLabel.shortLabel(choice.scene()), UiTextPalette.Parchment.TITLE,
                ScenarioUi.icon(ScenarioUi.Icon.DOWN), ScenarioLabel.definition(structure.options(), choice.scene()),
                () -> { if (openScenes != null) openScenes.run(); });
        UiControl status = place("status", x + width - 64, y, 64, 12);
        List<Component> statusNotes = new ArrayList<>();
        statusNotes.add(text(presentation.status()));
        if ("failed".equals(presentation.status())) statusNotes.add(text("failure." + ScenarioSimulationClientState.failure(table, input)));
        status.configure(font, text("results.status." + presentation.status()), UiTextPalette.Parchment.LABEL,
                presentation.badge(), statusNotes, null);
        ItemStack tool = new ItemStack(BuiltInRegistries.ITEM.get(choice.params().toolId()));
        List<Component> params = new ArrayList<>();
        params.add(text("params.current", tool.getHoverName(), choice.params().luck(), choice.params().sampleCount()));
        choice.params().toolEnchantments().forEach((id, level) -> params.add(
                text("params.enchant_level", ScenarioParamsOverlay.enchantmentName(id), level)));
        UiControl settings = place("params", x, y + 12, width - 60, 16);
        settings.configure(font, tool.getHoverName(), UiTextPalette.Parchment.NAME, ScenarioUi.icon(ScenarioUi.Icon.PARAMS), params,
                () -> { if (openParams != null) openParams.run(); });
        UiControl calculate = place("calculate", x + width - 58, y + 12, 58, 16);
        calculate.setStyle(ScenarioUi.ACTION);
        calculate.configure(font, text("cached".equals(presentation.status()) ? "results.status.cached" : "calculate"),
                UiTextPalette.Parchment.TITLE, null, List.of(text("calculate")),
                () -> ScenarioSimulationClientState.request(table, true));
        calculate.setEnabled(!"cached".equals(presentation.status()) && !"pending".equals(presentation.status()));
        focus.add(scene); focus.add(settings); focus.add(calculate);
        controls.endUpdate(); focus.endUpdate();
    }

    private UiControl place(String key, int left, int top, int controlWidth, int height) {
        UiControl control = controls.obtain(key);
        control.setBounds(left, top, controlWidth, height);
        return control;
    }

    public void renderHeader(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        sync(font);
        controls.render(graphics, font, mouseX, mouseY);
        UiTarget target = controls.targetAt(mouseX, mouseY);
        tooltip = target == null ? List.of() : target.tooltip();
    }
    public boolean click(double mouseX, double mouseY, int button) {
        sync(Minecraft.getInstance().font);
        focus.clearFocus();
        boolean handled = controls.mousePressed(mouseX, mouseY, button);
        controls.mouseReleased(button);
        return handled;
    }
    public boolean keyPressed(int key, int scan, int modifiers) {
        sync(Minecraft.getInstance().font);
        return focus.keyPressed(key, scan, modifiers);
    }
    public List<Component> tooltip() { return tooltip; }
}
