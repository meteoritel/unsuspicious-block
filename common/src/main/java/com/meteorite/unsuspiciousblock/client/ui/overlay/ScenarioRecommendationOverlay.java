package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiControlGroup;
import com.meteorite.meteosuikit.api.control.UiDocumentView;
import com.meteorite.meteosuikit.api.control.UiOverlay;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiAction;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputContainer;
import com.meteorite.meteosuikit.api.input.UiInputTarget;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.input.UiPointerHit;
import com.meteorite.meteosuikit.api.layout.UiNode;
import com.meteorite.meteosuikit.api.render.UiLayer;
import com.meteorite.meteosuikit.api.render.UiTooltipPresenter;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalRichTooltip;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ConditionOption;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.core.network.payload.ScenarioSelection;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static com.meteorite.unsuspiciousblock.client.state.ScenarioSession.text;

/**
 * 推荐输入的模态预览：请求和应用分离，关闭、过期及切表后不接收旧答复。
 */
public final class ScenarioRecommendationOverlay implements JournalOverlays.Surface, UiInputContainer {
    private final JournalOverlays owner;
    private final ResourceLocation table;
    private final SimulationAssistTarget target;
    private final ScenarioSelection original;
    private final ScenarioSession session;
    private final UiDocumentView document;
    private final UiControlGroup controls = new UiControlGroup();
    private final UiChildren<UiInputTarget> children = new UiChildren<>();
    private final UiLayer layer;
    private UiRect bounds = new UiRect(0, 0, 1, 1);
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

    private ScenarioRecommendationOverlay(JournalOverlays owner, ResourceLocation table,
                                          SimulationAssistTarget target, ScenarioSelection original) {
        this.owner = owner;
        this.table = table;
        this.target = target;
        this.original = original;
        session = JournalClientConnection.scenario();
        document = new UiDocumentView(TextMeasurer.of(Minecraft.getInstance().font),
                () -> UnsuspiciousUiTheme.PARCHMENT, text("recommend.title"));
        document.setScrollStep(24);
        children.add(document);
        children.add(controls);
        layer = new UiOverlay(this).layer(() -> new UiRect(0, 0, owner.width(), owner.height()), 0x88000000,
                (graphics, font, pointer, depth) -> render(graphics, font, pointer));
        controls.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT);
    }

    /** 以当前选择为推荐基准，仅在会话可以构造该表的选择时打开预览。 */
    public static void open(JournalOverlays owner, ResourceLocation table, SimulationAssistTarget target) {
        var original = JournalClientConnection.scenario().requestOf(table);
        if (original == null) {
            return;
        }
        owner.open(new ScenarioRecommendationOverlay(owner, table, target, original));
        JournalClientConnection.scenario().requestAssist(table, target);
    }

    private void sync(Font font) {
        int width = Math.clamp(owner.width() - 20, 1, 300);
        int height = Math.clamp(owner.height() - 20, 1, 230);
        UiRect nextBounds = new UiRect((owner.width() - width) / 2, (owner.height() - height) / 2, width, height);
        boolean pending = session.assistPending();
        boolean stale = !original.equals(session.requestOf(table));
        var recommendation = session.recommendation(table);
        List<Component> notes = session.notesFor(table);
        String nextLanguage = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!dirty && bounds.equals(nextBounds) && displayed == recommendation && displayedNotes.equals(notes)
                && wasPending == pending && wasStale == stale && language.equals(nextLanguage) && measuredFont == font) {
            return;
        }
        dirty = false;
        displayed = recommendation;
        displayedNotes = notes;
        wasPending = pending;
        wasStale = stale;
        language = nextLanguage;
        measuredFont = font;
        bounds = nextBounds;
        document.setBounds(bounds.x() + 8, bounds.y() + 26, Math.max(1, width - 16), Math.max(1, height - 55));
        updateDocument(recommendation, notes, pending, stale);
        updateControls(font, recommendation, !pending && !stale);
    }

    private void updateDocument(@Nullable ScenarioSelection recommendation, List<Component> notes,
                                boolean pending, boolean stale) {
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
        if (error != null) {
            content.add(error);
        }
        if (stale) {
            content.add(text("assist_stale"));
        } else if (pending) {
            content.add(text("assist_pending"));
        } else if (recommendation != null) {
            // 会话只为当前表与模拟选项有效的选择返回推荐。
            var structure = Objects.requireNonNull(snapshot).structure();
            List<ConditionOption> conditions = Objects.requireNonNull(structure.options()).conditions();
            appendComparison(content, conditions, recommendation);
        } else if (notes.isEmpty()) {
            content.add(text("assist_invalid"));
        }
        if (!pending && !stale) {
            content.addAll(notes);
        }
        document.setContent(content.stream()
                .<UiNode>map(line -> new UiNode.Row(line, UiTextScheme.PARCHMENT.body()))
                .toList());
    }

    private void updateControls(Font font, @Nullable ScenarioSelection recommendation, boolean canRequest) {
        controls.beginUpdate();
        UiControl close = controls.obtain("close");
        close.setBounds(bounds.right() - 22, bounds.y() + 4, 18, 16);
        Component closeLabel = text("params.cancel").copy().withStyle(TooltipBuilder.BODY);
        close.configure(font, Component.empty(), UiAtlas.icon(UiAtlas.Icon.CLOSE),
                List.of(closeLabel), owner::close);
        close.setAccessibleName(closeLabel);
        UiControl retry = button(font, "retry", text("recommend.retry"), bounds.x() + 8, bounds.bottom() - 22, 78,
                () -> {
                    error = null;
                    session.requestAssist(table, target);
                    dirty = true;
                });
        retry.setEnabled(canRequest);
        UiControl apply = button(font, "apply", text("recommend.apply"), bounds.right() - 112, bounds.bottom() - 22, 104, () -> {
            if (!original.equals(session.requestOf(table))) {
                error = text("assist_stale");
                dirty = true;
                return;
            }
            var result = session.applyRecommendation(table);
            if (result.applied()) {
                owner.close();
            } else {
                error = result.error();
                dirty = true;
            }
        });
        apply.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.PRIMARY);
        apply.setEnabled(canRequest && recommendation != null);
        controls.endUpdate();
    }

    private UiControl button(Font font, String key, Component label, int x, int y, int width, UiAction action) {
        UiControl control = controls.obtain(key);
        control.setBounds(x, y, width, 16);
        control.configure(font, label, null, List.of(), action);
        control.setAccessibleName(label);
        return control;
    }

    private void appendComparison(List<Component> content, List<ConditionOption> conditions, ScenarioSelection recommendation) {
        content.add(text("recommend.compare"));
        appendConditionDiff(content, conditions, original.enabledConditions(), recommendation.enabledConditions());
        content.add(text("recommend.change", text("params.tool"), toolName(original.toolId()), toolName(recommendation.toolId())));
        content.add(text("recommend.change", text("params.luck"), original.luck(), recommendation.luck()));
        content.add(text("recommend.change", text("params.samples"), original.sampleCount(), recommendation.sampleCount()));
        Set<ResourceLocation> enchantments = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        enchantments.addAll(original.toolEnchantments().keySet());
        enchantments.addAll(recommendation.toolEnchantments().keySet());
        for (var enchantment : enchantments) {
            content.add(text("recommend.change", ScenarioParamsOverlay.enchantmentName(enchantment),
                    original.toolEnchantments().getOrDefault(enchantment, 0),
                    recommendation.toolEnchantments().getOrDefault(enchantment, 0)));
        }
        content.add(text("recommend.help"));
    }

    private static void appendConditionDiff(List<Component> content, List<ConditionOption> conditions,
                                            List<String> current, List<String> recommended) {
        if (conditions.isEmpty()) {
            return;
        }
        Set<String> currentSet = new LinkedHashSet<>(current);
        Set<String> recommendedSet = new LinkedHashSet<>(recommended);
        boolean anyDiff = false;
        for (ConditionOption option : conditions) {
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
        document.render(graphics, font, pointer);
        controls.render(graphics, font, pointer);
        if (pointer.allowsTooltip() && pointer.hit() != null) UiTooltipPresenter.render(graphics, font, pointer.hit(),
                UnsuspiciousUiTheme.PARCHMENT.tooltipSkin(), (int) pointer.x(), (int) pointer.y());
    }

    @Override
    public @Nullable UiPointerHit hitTest(double x, double y) {
        return JournalRichTooltip.attachText(UiInputContainer.super.hitTest(x, y));
    }

    @Override
    public UiChildren<UiInputTarget> inputChildren() {
        return children;
    }

    @Override
    public UiLayer layer() {
        return layer;
    }

    @Override
    public void prepare(Font font) {
        sync(font);
    }

    @Override
    public void invalidateResources() {
        dirty = true;
        document.invalidateLayout();
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            int direction = key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1;
            document.setOffset(document.offset() + direction * document.bounds().height());
            return true;
        }
        return UiInputContainer.super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void closed() {
        session.cancelAssist();
    }
}
