package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.style.UiTextScheme;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.*;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.layout.*;
import com.meteorite.meteosuikit.api.render.*;
import com.meteorite.meteosuikit.api.style.*;
import com.meteorite.meteosuikit.api.text.*;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState.text;

/** 网格页的紧凑场景栏：场景选择与计算按钮，参数调整由场景 Tab 承担。 */
public final class ScenarioPanel implements UiInputContainer {
    private final UiControlGroup controls = new UiControlGroup();
    private final int x, y, width;
    @Nullable private ResourceLocation table;
    @Nullable private Runnable openScenes;
    @Nullable private Font measuredFont;
    private String headerKey = "";

    public ScenarioPanel(JournalBookBackground.BookLayout layout) {
        x = layout.rightPageX() + 4;
        y = layout.rightPageY() + 6;
        width = layout.rightPageWidth() - 8;
        controls.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT);
        children.add(controls);
    }

    public void setTable(@Nullable ResourceLocation value) {
        if (Objects.equals(table, value)) return;
        table = value;
        headerKey = "";

        if (value != null) ScenarioSimulationClientState.queryCache(value, false);
    }
    public void setOpenScenes(@Nullable Runnable value) { openScenes = value; }

    private void sync(Font font) {
        if (table != null) ScenarioSimulationClientState.ensureCacheQuery(table);
        var choice = ScenarioSimulationClientState.selection(table);
        var structure = ScenarioSimulationClientState.table(table);
        if (choice == null || structure == null || structure.options() == null) {
            controls.beginUpdate(); controls.endUpdate();
             headerKey = "";
            return;
        }
        String input = ScenarioSimulationClientState.inputKey(choice);
        ScenarioPresentation presentation = ScenarioPresentation.resolve(table, choice.scene(), choice.params());
        String key = table + "#" + input + "#" + presentation.status() + "#"
                + ArchaeologyJournalClientState.getCatalogRevision() + "#"
                + Minecraft.getInstance().getLanguageManager().getSelected();
        if (headerKey.equals(key) && measuredFont == font) return;
        headerKey = key; measuredFont = font;
        controls.beginUpdate();
        UiControl scene = place("scene", x, y + 6, width - 60);
        scene.setSkin(UiAtlas.QUIET_BUTTON_SKIN);
        scene.configure(font, ScenarioLabel.shortLabel(choice.scene()), UiTextScheme.PARCHMENT.title(),
                UiAtlas.icon(UiAtlas.Icon.CHEVRON_DOWN), ScenarioLabel.tooltip(structure.options(), choice.scene()),
                () -> { if (openScenes != null) openScenes.run(); });
        List<Component> calculationNotes = new ArrayList<>();
        calculationNotes.add(text("calculate"));
        calculationNotes.add(text(presentation.status()));
        if ("failed".equals(presentation.status())) calculationNotes.add(text("failure." + ScenarioSimulationClientState.failure(table, input)));
        UiControl calculate = place("calculate", x + width - 58, y + 6, 58);
        calculate.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.PRIMARY);
        String status = presentation.status();
        calculate.configure(font, text(List.of("uncomputed", "failed", "query_failed").contains(status)
                        ? "calculate" : "results.status." + status),
                null, calculationNotes,
                () -> ScenarioSimulationClientState.request(table, true));
        calculate.setEnabled(List.of("uncomputed", "failed", "query_failed", "available").contains(status));

        controls.endUpdate();
    }

    private UiControl place(String key, int left, int top, int controlWidth) {
        UiControl control = controls.obtain(key);
        control.setBounds(left, top, controlWidth, 16);
        return control;
    }

    private final UiChildren<UiInputTarget> children = new UiChildren<>();
    @Override public UiChildren<UiInputTarget> inputChildren() { return children; }
    public void prepareUi() { sync(Minecraft.getInstance().font); }
    public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
        controls.render(graphics, font, pointer);
    }
}
