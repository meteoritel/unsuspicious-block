package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.meteosuikit.api.input.UiAction;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.*;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.render.*;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ConditionOption;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.core.network.payload.ScenarioSelection;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.meteorite.unsuspiciousblock.client.state.ScenarioSession.text;

/**
 * 推荐输入的模态预览：请求和应用分离，关闭、过期及切表后不接收旧答复。
 */
public final class ScenarioRecommendationOverlay implements JournalOverlays.Surface, UiInputContainer {
    private final JournalOverlays layer;
    private final ResourceLocation table;
    private final SimulationAssistTarget target;
    private final ScenarioSelection original;
    private final ScenarioSession session;
    private final UiScrollView scroll = new UiScrollView();
    private final UiControlGroup controls = new UiControlGroup();
    private final UiChildren<UiInputTarget> children = new UiChildren<>();
    private final UiOverlay overlay = new UiOverlay(this);
    private final UiLayer presentation;
    private UiRect bounds = new UiRect(0, 0, 1, 1);
    private List<FormattedCharSequence> lines = List.of();
    @Nullable
    private ScenarioSelection displayed;
    private List<Component> displayedNotes = List.of();
    @Nullable
    private Component error;
    private String language = "";
    @Nullable
    private Font measuredFont;
    private boolean wasPending;
    private boolean wasStale;
    private boolean dirty = true;

    private ScenarioRecommendationOverlay(JournalOverlays layer, ResourceLocation table,
                                          SimulationAssistTarget target, ScenarioSelection original) {
        this.layer = layer;
        this.table = table;
        this.target = target;
        this.original = original;
        session = JournalClientConnection.scenario();
        children.add(scroll);
        children.add(controls);
        presentation = new UiLayer(overlay.inputScope(), (graphics, font, pointer, depth) -> overlay.render(graphics,
                new UiRect(0, 0, layer.width(), layer.height()), depth, 0x88000000,
                () -> render(graphics, font, pointer)), null);
        controls.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT);
        scroll.setStep(24);
    }

    public static void open(JournalOverlays layer, ResourceLocation table, SimulationAssistTarget target) {
        var original = JournalClientConnection.scenario().requestOf(table);
        if (original == null) return;
        layer.open(new ScenarioRecommendationOverlay(layer, table, target, original));
        JournalClientConnection.scenario().requestAssist(table, target);
    }

    private void sync(Font font) {
        int width = Math.clamp(layer.width() - 20, 1, 300);
        int height = Math.clamp(layer.height() - 20, 1, 230);
        UiRect nextBounds = new UiRect((layer.width() - width) / 2, (layer.height() - height) / 2, width, height);
        boolean pending = session.assistPending();
        boolean stale = !original.equals(session.requestOf(table));
        var recommendation = session.recommendation(table);
        List<Component> notes = session.notesFor(table);
        String nextLanguage = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!dirty && bounds.equals(nextBounds) && displayed == recommendation && displayedNotes.equals(notes)
                && wasPending == pending && wasStale == stale && language.equals(nextLanguage) && measuredFont == font)
            return;
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
        var snapshot = session.snapshot(table);
        String kind = target.kind() == SimulationAssistTarget.Kind.CHILD_TABLE ? "table" : "item";
        String value = target.value();
        if (target.kind() == SimulationAssistTarget.Kind.BRANCH && snapshot != null) {
            var branch = snapshot.structure().branches().stream().filter(b -> b.reference().equals(target.value())).findFirst().orElse(null);
            if (branch != null) {
                kind = branch.kind();
                value = branch.target();
            }
            content.add(text("recommend.branch_scope", target.value()));
        }
        if (snapshot != null) {
            var display = snapshot.target(kind, value);
            content.add(text("recommend.target", display.visible() ? display.name() : text("conditions.undiscovered")));
        }
        var witness = session.recommendationWitness(table);
        if (witness != null) {
            content.add(text("recommend.path", witness.branchReference(), witness.pathId()));
            if (!witness.itemSignature().isEmpty() && snapshot != null) {
                var display = snapshot.target("item", witness.itemSignature());
                content.add(text("recommend.actual_item", display.visible() ? display.name() : text("conditions.undiscovered")));
            }
        }
        if (error != null) content.add(error);
        if (stale) content.add(text("assist_stale"));
        else if (pending) content.add(text("assist_pending"));
        else if (recommendation != null) {
            content.add(text("recommend.compare"));
            var structure = session.table(table);
            SimulationOptions options = structure == null ? null : structure.options();
            appendConditionDiff(content, options, original.enabledConditions(), recommendation.enabledConditions());
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
            content.add(text("recommend.help"));
        } else if (notes.isEmpty()) content.add(text("assist_invalid"));
        if (!pending && !stale) content.addAll(notes);
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : content) wrapped.addAll(font.split(line, Math.max(1, scroll.viewport().width() - 6)));
        lines = List.copyOf(wrapped);
        scroll.setContentHeight(lines.size() * (font.lineHeight + 4));
        scroll.setScrollbarVisible(scroll.maxOffset() > 0);
        controls.beginUpdate();
        UiControl close = button(font, "close", text("params.cancel"), bounds.right() - 22, bounds.y() + 4, 18, layer::close);
        close.configure(font, Component.empty(), UiAtlas.icon(UiAtlas.Icon.CLOSE),
                List.of(text("params.cancel")), layer::close);
        close.setAccessibleName(text("params.cancel"));
        UiControl retry = button(font, "retry", text("recommend.retry"), bounds.x() + 8, bounds.bottom() - 22, 78,
                () -> {
                    error = null;
                    session.requestAssist(table, target);
                    dirty = true;
                });
        retry.setEnabled(!pending && !stale);
        UiControl apply = button(font, "apply", text("recommend.apply"), bounds.right() - 112, bounds.bottom() - 22, 104, () -> {
            if (!original.equals(session.requestOf(table))) {
                error = text("assist_stale");
                dirty = true;
                return;
            }
            var result = session.applyRecommendation(table);
            if (result.applied()) layer.close();
            else {
                error = result.error();
                dirty = true;
            }
        });
        apply.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.PRIMARY);
        apply.setEnabled(recommendation != null && !pending && !stale);
        controls.endUpdate();
    }

    private UiControl button(Font font, String key, Component label, int x, int y, int width, UiAction action) {
        UiControl control = controls.obtain(key);
        control.setBounds(x, y, width, 16);
        control.configure(font, label, null, List.of(label), action);
        return control;
    }

    private static void appendConditionDiff(List<Component> content, @Nullable SimulationOptions options,
                                            List<String> current, List<String> recommended) {
        if (options == null || options.conditions().isEmpty()) {
            return;
        }
        Set<String> currentSet = new LinkedHashSet<>(current);
        Set<String> recommendedSet = new LinkedHashSet<>(recommended);
        boolean anyDiff = false;
        for (ConditionOption option : options.conditions()) {
            boolean was = currentSet.contains(option.id());
            boolean will = recommendedSet.contains(option.id());
            if (was == will) {
                continue;
            }
            anyDiff = true;
            content.add(text(will ? "recommend.check_condition" : "recommend.uncheck_condition",
                    ScenarioLabel.shortConditionLabel(option.condition())));
        }
        if (!anyDiff) {
            content.add(text("recommend.conditions_unchanged"));
        }
    }

    private static Component toolName(ResourceLocation id) {
        return new ItemStack(BuiltInRegistries.ITEM.get(id)).getHoverName();
    }

    private void render(GuiGraphics graphics, Font font, UiPointer pointer) {
        ScenarioUi.renderPage(graphics, bounds);
        graphics.drawString(font, text("recommend.title"), bounds.x() + 8, bounds.y() + 8, UiTextScheme.PARCHMENT.title(), false);
        scroll.push(graphics);
        try {
            int lineHeight = font.lineHeight + 4;
            int first = scroll.offset() / lineHeight;
            int end = Math.min(lines.size(), (scroll.offset() + scroll.viewport().height() + lineHeight - 1) / lineHeight);
            for (int index = first; index < end; index++) {
                graphics.drawString(font, lines.get(index), 0, index * lineHeight, UiTextScheme.PARCHMENT.body(), false);
            }
        } finally {
            scroll.pop(graphics);
        }
        scroll.renderScrollbar(graphics, () -> UnsuspiciousUiTheme.PARCHMENT);
        controls.render(graphics, font, pointer);
        if (pointer.allowsTooltip() && pointer.hit() != null) pointer.hit().renderTooltip(graphics, font,
                UnsuspiciousUiTheme.PARCHMENT.surfaces(), (int) pointer.x(), (int) pointer.y());
    }

    @Override
    public UiChildren<UiInputTarget> inputChildren() {
        return children;
    }

    @Override
    public UiLayer layer() {
        return presentation;
    }

    @Override
    public void prepare(Font font) {
        sync(font);
    }

    @Override
    public UiPointerHit hitTest(double x, double y) {
        UiPointerHit hit = children.hitTest(x, y);
        return hit != null ? hit.within(this) : scroll.contains(x, y) ? UiPointerHit.leaf(this, x, y) : null;
    }

    @Override
    public boolean mouseScrolled(UiInputContext context, double x, double y) {
        if (!scroll.contains(context.pointerX(), context.pointerY())) return false;
        scroll.scrollBy(y);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            scroll.setOffset(scroll.offset() + (key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * scroll.viewport().height());
            return true;
        }
        return UiInputContainer.super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void closed() {
        session.cancelAssist();
    }
}
