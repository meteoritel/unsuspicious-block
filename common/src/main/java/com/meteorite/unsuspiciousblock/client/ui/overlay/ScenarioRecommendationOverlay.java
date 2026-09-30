package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.*;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestScenarioSimulationPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import static com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState.text;

/** 推荐输入的模态预览：请求和应用分离，关闭、过期及切表后不接收旧答复。 */
public final class ScenarioRecommendationOverlay implements OverlayLayer.Overlay {
    private final OverlayLayer layer;
    private final ResourceLocation table;
    private final SimulationAssistTarget target;
    private final RequestScenarioSimulationPayload original;
    private final UiScrollView scroll = new UiScrollView();
    private final UiControlGroup controls = new UiControlGroup();
    private final UiFocusManager focus = new UiFocusManager();
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private List<FormattedCharSequence> lines = List.of();
    @Nullable private RequestScenarioSimulationPayload displayed;
    private List<Component> displayedNotes = List.of();
    private String language = "";
    @Nullable private Font measuredFont;
    private boolean wasPending;
    private boolean wasStale;
    private boolean dirty = true;

    private ScenarioRecommendationOverlay(OverlayLayer layer, ResourceLocation table,
            SimulationAssistTarget target, RequestScenarioSimulationPayload original) {
        this.layer = layer;
        this.table = table;
        this.target = target;
        this.original = original;
        controls.setStyle(ScenarioUi.QUIET);
        scroll.setStep(24);
    }

    public static void open(OverlayLayer layer, ResourceLocation table, SimulationAssistTarget target) {
        var original = ScenarioSimulationClientState.requestOf(table);
        if (original == null) return;
        layer.open(new ScenarioRecommendationOverlay(layer, table, target, original));
        ScenarioSimulationClientState.requestAssist(table, target);
    }

    private void sync(Font font) {
        int width = Math.clamp(layer.width() - 20, 1, 300);
        int height = Math.clamp(layer.height() - 20, 1, 230);
        UiRect nextBounds = new UiRect((layer.width() - width) / 2, (layer.height() - height) / 2, width, height);
        boolean pending = ScenarioSimulationClientState.assistPending();
        boolean stale = !original.equals(ScenarioSimulationClientState.requestOf(table));
        var recommendation = ScenarioSimulationClientState.recommendation(table);
        List<Component> notes = ScenarioSimulationClientState.notes();
        String nextLanguage = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!dirty && bounds.equals(nextBounds) && displayed == recommendation && displayedNotes.equals(notes)
                && wasPending == pending && wasStale == stale && language.equals(nextLanguage) && measuredFont == font) return;
        dirty = false;
        displayed = recommendation;
        displayedNotes = notes;
        wasPending = pending;
        wasStale = stale;
        language = nextLanguage;
        measuredFont = font;
        bounds = nextBounds;
        scroll.setViewport(bounds.x() + 8, bounds.y() + 26, Math.max(1, width - 16), Math.max(1, height - 55));
        List<Component> content = new ArrayList<>();
        if (target.kind() == SimulationAssistTarget.Kind.CHILD_TABLE) {
            var child = ScenarioSimulationClientState.table(ResourceLocation.parse(target.value()));
            content.add(text("recommend.target", child == null ? Component.literal(target.value()) : child.displayName()));
        } else content.add(text("recommend.item"));
        if (stale) content.add(text("assist_stale"));
        else if (pending) content.add(text("assist_pending"));
        else if (recommendation != null) {
            content.add(text("recommend.compare"));
            content.add(text("recommend.change", text("tab"), ScenarioLabel.shortLabel(original.scenarioKey()),
                    ScenarioLabel.shortLabel(recommendation.scenarioKey())));
            content.add(text("recommend.change", text("params.tool"), toolName(original.toolId()), toolName(recommendation.toolId())));
            content.add(text("recommend.change", text("params.luck"), original.luck(), recommendation.luck()));
            content.add(text("recommend.change", text("params.samples"), original.sampleCount(), recommendation.sampleCount()));
            TreeSet<ResourceLocation> enchantments = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
            enchantments.addAll(original.toolEnchantments().keySet());
            enchantments.addAll(recommendation.toolEnchantments().keySet());
            for (var enchantment : enchantments) {
                content.add(text("recommend.change", ScenarioParamsOverlay.enchantmentName(enchantment),
                        original.toolEnchantments().getOrDefault(enchantment, 0),
                        recommendation.toolEnchantments().getOrDefault(enchantment, 0)));
            }
            var structure = ScenarioSimulationClientState.table(table);
            if (structure != null && structure.options() != null) {
                content.addAll(ScenarioLabel.definition(structure.options(), recommendation.scenarioKey()));
            }
            content.add(text("recommend.help"));
        } else if (notes.isEmpty()) content.add(text("assist_invalid"));
        if (!pending && !stale) content.addAll(notes);
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : content) wrapped.addAll(font.split(line, Math.max(1, scroll.viewport().width() - 6)));
        lines = List.copyOf(wrapped);
        scroll.setContentHeight(lines.size() * (font.lineHeight + 4));
        scroll.setScrollbarVisible(scroll.maxOffset() > 0);
        controls.beginUpdate();
        focus.beginUpdate();
        UiControl close = button(font, "close", text("params.cancel"), bounds.right() - 22, bounds.y() + 4, 18, layer::close);
        close.configure(font, Component.empty(), UiTextPalette.Parchment.BODY, ScenarioUi.icon(ScenarioUi.Icon.CLOSE),
                List.of(text("params.cancel")), layer::close);
        close.setAccessibleName(text("params.cancel"));
        UiControl retry = button(font, "retry", text("recommend.retry"), bounds.x() + 8, bounds.bottom() - 22, 78,
                () -> { ScenarioSimulationClientState.requestAssist(table, target); dirty = true; });
        retry.setEnabled(!pending && !stale);
        UiControl apply = button(font, "apply", text("recommend.apply"), bounds.right() - 112, bounds.bottom() - 22, 104, () -> {
            if (original.equals(ScenarioSimulationClientState.requestOf(table))) ScenarioSimulationClientState.applyRecommendation(table);
            layer.close();
        });
        apply.setStyle(ScenarioUi.ACTION);
        apply.setEnabled(recommendation != null && !pending && !stale);
        controls.endUpdate();
        focus.endUpdate();
    }

    private UiControl button(Font font, String key, Component label, int x, int y, int width, UiAction action) {
        UiControl control = controls.obtain(key);
        control.setBounds(x, y, width, 16);
        control.configure(font, label, UiTextPalette.Parchment.BODY, null, List.of(label), action);
        focus.add(control);
        return control;
    }

    private static Component toolName(ResourceLocation id) {
        return new ItemStack(BuiltInRegistries.ITEM.get(id)).getHoverName();
    }

    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        sync(font);
        graphics.fill(0, 0, layer.width(), layer.height(), 0x88000000);
        ScenarioUi.PANEL.render(graphics, bounds);
        graphics.drawString(font, text("recommend.title"), bounds.x() + 8, bounds.y() + 8, UiTextPalette.Parchment.TITLE, false);
        scroll.push(graphics);
        try {
            int lineHeight = font.lineHeight + 4;
            int first = scroll.offset() / lineHeight;
            int end = Math.min(lines.size(), (scroll.offset() + scroll.viewport().height() + lineHeight - 1) / lineHeight);
            for (int index = first; index < end; index++) {
                graphics.drawString(font, lines.get(index), 0, index * lineHeight, UiTextPalette.Parchment.BODY, false);
            }
        } finally { scroll.pop(graphics); }
        scroll.renderScrollbar(graphics, ScenarioUi.QUIET);
        controls.render(graphics, font, mouseX, mouseY);
        controls.renderTooltip(graphics, font, mouseX, mouseY);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        sync(Minecraft.getInstance().font);
        focus.clearFocus();
        if (button == 0 && !scroll.mousePressed(x, y, button)) controls.mousePressed(x, y, button);
        return true;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) { return scroll.mouseDragged(y); }
    @Override public boolean mouseReleased(double x, double y, int button) {
        scroll.mouseReleased(); controls.mouseReleased(button); return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (scroll.contains(x, y)) scroll.scrollBy(amount);
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        sync(Minecraft.getInstance().font);
        if (key == GLFW.GLFW_KEY_ESCAPE) { layer.close(); return true; }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            scroll.scrollBy(key == GLFW.GLFW_KEY_UP ? 1 : -1); return true;
        }
        return focus.keyPressed(key, scan, modifiers);
    }
    @Override public void closed() {
        ScenarioSimulationClientState.cancelAssist();
        controls.mouseReleased(0); scroll.mouseReleased(); focus.clearFocus();
    }
}
