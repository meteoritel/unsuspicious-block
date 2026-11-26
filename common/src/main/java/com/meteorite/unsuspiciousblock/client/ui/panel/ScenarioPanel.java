package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiControlGroup;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputContainer;
import com.meteorite.meteosuikit.api.input.UiInputTarget;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.state.ScenarioTableSnapshot;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.meteorite.unsuspiciousblock.client.state.ScenarioSession.text;

/**
 * 网格页的紧凑条件栏：展示当前条件勾选摘要、打开条件勾选浮层与触发计算。
 */
public final class ScenarioPanel implements UiInputContainer {
    private final UiControlGroup controls = new UiControlGroup();
    private final UiChildren<UiInputTarget> children = new UiChildren<>();
    private JournalBookBackground.BookLayout layout;
    @Nullable
    private ResourceLocation table;
    @Nullable
    private Runnable openConditions;
    @Nullable
    private Font measuredFont;
    private String headerKey = "";

    public ScenarioPanel(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        controls.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT);
        children.add(controls);
    }

    public void applyLayout(JournalBookBackground.BookLayout layout) {
        this.layout = layout;
        headerKey = "";
    }

    public void setTable(@Nullable ResourceLocation value) {
        if (Objects.equals(table, value)) {
            return;
        }
        table = value;
        headerKey = "";
        if (value != null) {
            JournalClientConnection.scenario().queryCache(value, false);
        }
    }

    public void setOpenScenes(@Nullable Runnable value) {
        openConditions = value;
    }

    private void sync(Font font) {
        if (table != null) {
            JournalClientConnection.scenario().ensureCacheQuery(table);
        }
        ScenarioTableSnapshot presentation = JournalClientConnection.scenario().snapshot(table);
        if (presentation == null || presentation.structure().options() == null) {
            controls.beginUpdate();
            controls.endUpdate();
            headerKey = "";
            return;
        }
        var choice = presentation.choice();
        var structure = presentation.structure();
        String input = presentation.inputKey();
        String key = table + "#" + structure.hash() + "#" + input + "#" + presentation.status() + "#"
                + presentation.revision() + "#"
                + Minecraft.getInstance().getLanguageManager().getSelected();
        if (headerKey.equals(key) && measuredFont == font) {
            return;
        }
        headerKey = key;
        measuredFont = font;
        controls.beginUpdate();

        int x = layout.rightPageX() + 4;
        int y = layout.rightPageY() + 6;
        int width = layout.rightPageWidth() - 8;
        UiControl scene = place("scene", x, y + 6, width - 60);
        scene.setSkin(UiAtlas.QUIET_BUTTON_SKIN);
        Component summary = ScenarioLabel.selectionSummary(structure.options(), choice.conditions());
        scene.configure(font, summary, UiTextScheme.PARCHMENT.title(),
                UiAtlas.icon(UiAtlas.Icon.CHEVRON_DOWN),
                ScenarioLabel.selectionTooltipLines(structure.options(), choice.conditions(), choice.params()),
                () -> {
                    if (openConditions != null) {
                        openConditions.run();
                    }
                });

        String status = presentation.status();
        boolean canCalculate = switch (status) {
            case "uncomputed", "failed", "query_failed", "available" -> true;
            default -> false;
        };
        UiControl calculate = place("calculate", x + width - 58, y + 6, 58);
        calculate.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.PRIMARY);
        String labelKey = switch (status) {
            case "uncomputed", "failed", "query_failed" -> "calculate";
            default -> "results.status." + status;
        };
        calculate.configure(font, text(labelKey),
                null, buildCalculateTooltipLines(status, presentation.failure(), canCalculate),
                () -> JournalClientConnection.scenario().request(table, true));
        calculate.setEnabled(canCalculate);

        controls.endUpdate();
    }

    private static List<Component> buildCalculateTooltipLines(String status, @Nullable String failure,
                                                            boolean canCalculate) {
        List<Component> lines = new ArrayList<>();
        if ("failed".equals(status) && failure != null) {
            lines.add(text("failure." + failure).copy().withStyle(TooltipBuilder.NEGATIVE));
        } else {
            lines.add(text(status).copy().withStyle(TooltipBuilder.LABEL));
        }
        if (canCalculate) {
            lines.add(text("calculate_action").copy().withStyle(TooltipBuilder.HINT));
        }
        return List.copyOf(lines);
    }

    private UiControl place(String key, int left, int top, int controlWidth) {
        UiControl control = controls.obtain(key);
        control.setBounds(left, top, controlWidth, 16);
        return control;
    }

    @Override
    public UiChildren<UiInputTarget> inputChildren() {
        return children;
    }

    public void prepareUi() {
        sync(Minecraft.getInstance().font);
    }

    public void renderControls(GuiGraphics graphics, Font font, UiPointer pointer) {
        controls.render(graphics, font, pointer);
    }
}
