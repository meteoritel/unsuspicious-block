package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.style.UiTextScheme;

import com.meteorite.meteosuikit.api.input.UiAction;

import net.minecraft.network.chat.MutableComponent;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.meteosuikit.api.layout.*;
import com.meteorite.meteosuikit.api.render.*;
import com.meteorite.meteosuikit.api.style.*;
import com.meteorite.unsuspiciousblock.client.ui.support.*;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionPresentation;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranch;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** 场景读模型：假设、完整获取分支及独立联动说明；不在客户端推断路径可达性。 */
final class ScenarioPageBuilder {
    private static final int INDENT_STEP = 10;
    private static final int ITEM_INDENT = 14;
    enum Heading { SECTION, ENTRY }
    /** 相同完整路径语义共享一组候选，替代获取路径保持独立。 */
    private record Group(List<LootConditionInfo> conditions, List<LootConditionInfo> extras, LuckGate luck,
                         List<String> scenes, boolean uncertain, String source, String mode) {}
    private ScenarioPageBuilder() {}

    static List<UiNode> buildConditions(CatalogTableDto structure, String sceneKey, boolean showOther,
                                       UiAction toggleOther, Consumer<ResourceLocation> recommend,
                                       BiPredicate<String, String> visible, BiConsumer<String, String> locate) {
        var options = structure.options();
        if (options == null) return List.of();
        List<UiNode> content = new ArrayList<>();
        content.add(heading(text("conditions.assumptions"), Heading.SECTION, ScenarioLabel.definition(options, sceneKey)));
        content.add(new UiNode.Gap(4));
        for (var condition : ScenarioLabel.positiveAssumptions(options, sceneKey))
            addCondition(content, condition, ITEM_INDENT, 0, false);
        List<LootConditionInfo> negatives = ScenarioLabel.negativeConditions(options, sceneKey);
        if (!negatives.isEmpty()) {
            int parent = content.size();
            content.add(action(showOther ? "conditions.hide_other" : "conditions.show_other",
                    showOther ? UiAtlas.Icon.CHEVRON_UP : UiAtlas.Icon.CHEVRON_DOWN, toggleOther));
            if (showOther || ScenarioLabel.isBaseline(sceneKey))
                for (var condition : negatives) addCondition(content, condition, ITEM_INDENT, parent, false);
        }
        if (ScenarioLabel.positiveAssumptions(options, sceneKey).isEmpty() && negatives.isEmpty())
            content.add(new UiNode.Row(text("no_assumptions"), UiTextScheme.PARCHMENT.label()));
        content.add(new UiNode.Gap(9));
        content.add(heading(text("conditions.branches"), Heading.SECTION, List.of(text("conditions.candidates_hint"))));
        content.add(new UiNode.Row(4, UiNode.NO_PARENT, text("conditions.candidates_hint"), UiTextScheme.PARCHMENT.hint(),
                List.of(), List.of(), null, null));
        Map<Group, List<ScenarioBranch>> groups = new LinkedHashMap<>();
        for (var branch : structure.branches()) {
            if (branch.injectionSource().isEmpty() && branch.conditions().isEmpty()
                    && branch.requirements().isEmpty() && branch.luck().isTrivial()) continue;
            var group = new Group(branch.conditions(), branch.requirements(), branch.luck(), branch.activeScenes(),
                    branch.uncertain(), branch.injectionSource(), branch.injectionMode());
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(branch);
        }
        int ordinal = 0;
        for (var entry : groups.entrySet()) {
            if (!entry.getKey().source().isEmpty()) continue;
            addBranch(content, structure, sceneKey, ++ordinal, entry.getKey(), entry.getValue(), visible, locate, recommend);
        }
        if (ordinal == 0) content.add(new UiNode.Row(text("conditions.no_branches"), UiTextScheme.PARCHMENT.label()));
        boolean hasInjection = groups.keySet().stream().anyMatch(group -> !group.source().isEmpty());
        if (hasInjection) {
            content.add(new UiNode.Gap(12));
            content.add(heading(text("injection.title"), Heading.SECTION, List.of(text("injection.limit"))));
            content.add(new UiNode.Row(text("injection.limit"), UiTextScheme.PARCHMENT.hint()));
            for (var entry : groups.entrySet()) {
                if (entry.getKey().source().isEmpty()) continue;
                addBranch(content, structure, sceneKey, ++ordinal, entry.getKey(), entry.getValue(), visible, locate, recommend);
            }
        }
        return List.copyOf(content);
    }

    private static void addBranch(List<UiNode> nodes, CatalogTableDto table, String scene, int ordinal, Group group,
                                  List<ScenarioBranch> branches, BiPredicate<String, String> visible,
                                  BiConsumer<String, String> locate, Consumer<ResourceLocation> recommend) {
        nodes.add(new UiNode.Gap(8));
        int parent = nodes.size();
        boolean active = group.scenes().contains(scene);
        Component title = group.source().equals("observed") ? text("injection.observed")
                : !group.source().isEmpty() ? text("injection.known", group.source()) : text("conditions.branch", ordinal);
        nodes.add(heading(title, Heading.ENTRY, List.of(title)));
        for (var condition : group.conditions()) addCondition(nodes, condition, ITEM_INDENT, parent, false);
        if (group.conditions().isEmpty()) nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                text("conditions.unconditional"), UiTextScheme.PARCHMENT.label(), List.of(), List.of(), null, null));
        if (group.uncertain()) nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                text(group.source().equals("observed") ? "injection.observed_hint" : "conditions.hypothetical"),
                UiTextScheme.PARCHMENT.negative(), List.of(), List.of(), null, null));
        if (!group.extras().isEmpty() || !group.luck().isTrivial()) {
            int extraParent = nodes.size();
            nodes.add(new UiNode.Row(ITEM_INDENT, parent, text("conditions.additional"), UiTextScheme.PARCHMENT.label(),
                    List.of(), List.of(), null, null));
            for (var condition : group.extras()) addCondition(nodes, condition, ITEM_INDENT + INDENT_STEP, extraParent, true);
            LuckGate luck = group.luck();
            if (luck.minLuck().isPresent()) nodes.add(new UiNode.Row(ITEM_INDENT + INDENT_STEP, extraParent,
                    text("conditions.luck_gate", String.format(Locale.ROOT, "%.2f", luck.minLuck().getAsDouble()))
                            .withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.accent())),
                    UiTextScheme.PARCHMENT.label(), List.of(), List.of(), null, null));
            if (luck.rangeLimited()) nodes.add(new UiNode.Row(ITEM_INDENT + INDENT_STEP, extraParent,
                    text("conditions.luck_range"), UiTextScheme.PARCHMENT.label(), List.of(), List.of(), null, null));
            if (luck.bonusRollsGate().isPresent()) nodes.add(new UiNode.Row(ITEM_INDENT + INDENT_STEP, extraParent,
                    text("conditions.bonus_luck", String.format(Locale.ROOT, "%.2f", luck.bonusRollsGate().getAsDouble())),
                    UiTextScheme.PARCHMENT.label(), List.of(), List.of(), null, null));
        }
        if (!group.mode().isEmpty()) nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                text("injection." + group.mode()), UiTextScheme.PARCHMENT.label(), List.of(), List.of(), null, null));
        if (!active) {
            nodes.add(new UiNode.Row(ITEM_INDENT, parent, text("conditions.inactive"), UiTextScheme.PARCHMENT.negative(),
                    List.of(), List.of(), null, null));
            return;
        }
        Set<String> seen = new HashSet<>();
        List<UiNode.InlineIcon> icons = new ArrayList<>();
        for (var branch : branches) {
            if (!seen.add(branch.kind() + "#" + branch.target())) continue;
            boolean revealed = visible.test(branch.kind(), branch.target());
            UiIcon icon = UiAtlas.UNKNOWN_ITEM;
            List<Component> tooltip = List.of(text("conditions.undiscovered"));
            UiAction action = null;
            if (revealed) {
                Component name;
                if (branch.kind().equals("item")) {
                    var signature = LootResultSignature.fromStoredKey(branch.target());
                    if (signature == null) continue;
                    icon = new UiIcon.Item(signature.createPreviewStack());
                    name = table.items().stream().filter(item -> item.signature().equals(signature))
                            .map(CatalogTableDto.ItemEntry::displayName).findFirst().orElse(Component.literal(signature.itemId().toString()));
                } else {
                    icon = new UiIcon.Item(new ItemStack(branch.kind().equals("table") ? Items.BOOK : Items.NAME_TAG));
                    var child = ScenarioSimulationClientState.table(ResourceLocation.tryParse(branch.target()));
                    name = branch.kind().equals("table") && child != null ? child.displayName() : Component.literal(branch.target());
                }
                tooltip = List.of(text("conditions.target." + branch.kind(), name), text("conditions.candidates_hint"), text("conditions.locate"));
                action = () -> locate.accept(branch.kind(), branch.target());
            }
            icons.add(new UiNode.InlineIcon(icon, tooltip, branch, action));
            if (icons.size() == 4) {
                nodes.add(candidateRow(parent, List.copyOf(icons)));
                icons.clear();
            }
        }
        if (!icons.isEmpty()) nodes.add(candidateRow(parent, List.copyOf(icons)));
        for (var branch : branches) if (branch.kind().equals("table") && visible.test(branch.kind(), branch.target())) {
            nodes.add(action("recommend.title", UiAtlas.Icon.RECOMMEND, () -> recommend.accept(ResourceLocation.parse(branch.target()))));
            break;
        }
    }

    private static UiNode.Row candidateRow(int parent, List<UiNode.InlineIcon> icons) {
        return new UiNode.Row(ITEM_INDENT, parent,
                new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.BRANCH_ARROW), List.of(), null, null),
                Component.empty(), UiTextScheme.PARCHMENT.accent(), icons, List.of(text("conditions.candidates_hint")), null, null);
    }

    private static UiNode.Row heading(Component value, Heading kind, List<Component> tooltip) {
        return new UiNode.Row(4, UiNode.NO_PARENT, value.copy().withStyle(ChatFormatting.BOLD),
                UiTextScheme.PARCHMENT.title(), List.of(), tooltip, kind, null);
    }
    private static UiNode.Row action(String key, UiAtlas.Icon icon, UiAction action) {
        Component label = text(key);
        return new UiNode.Row(4, UiNode.NO_PARENT, new UiNode.InlineIcon(UiAtlas.icon(icon), List.of(label), null, action),
                label.copy().withStyle(ChatFormatting.UNDERLINE), UiTextScheme.PARCHMENT.body(), List.of(), List.of(label), null, action);
    }
    private static void addCondition(List<UiNode> nodes, ConditionPresentation condition, int indent, int parent, boolean secondary) {
        int self = nodes.size();
        boolean uncertain = condition.metadata().containsKey(LootConditionHandlers.FIDELITY_METADATA_KEY);
        Component styled = ScenarioLabel.conditionText(condition).copy();
        if (secondary) styled = styled.copy().withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.label()));
        ConditionTree.Kind kind = condition instanceof LootConditionInfo node ? node.kind() : ConditionTree.Kind.LEAF;
        if (kind == ConditionTree.Kind.ALL) styled = ScenarioLabel.symbol("∧ ").copy().append(styled);
        if (kind == ConditionTree.Kind.ANY) styled = ScenarioLabel.symbol("∨ ").copy().append(styled);
        if (kind == ConditionTree.Kind.NOT) styled = ScenarioLabel.symbol("¬ ").copy().append(text("conditions.negation"));
        if (condition.conditionType().getPath().contains("random_chance") || condition.conditionType().getPath().equals("table_bonus"))
            styled = Component.empty().append(text("conditions.random_trigger").withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.accent())))
                    .append(" · ").append(styled);
        if (uncertain) styled = styled.copy().append(" · ").append(text("conditions." +
                condition.metadata().get(LootConditionHandlers.FIDELITY_METADATA_KEY)).withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.negative())));
        nodes.add(new UiNode.Row(indent, parent, styled, UiTextScheme.PARCHMENT.body(), List.of(),
                List.of(condition.description(), Component.literal(condition.conditionType().toString())), null, null));
        for (var child : condition.displayChildren()) addCondition(nodes, child, indent + INDENT_STEP, self, secondary);
    }
    private static MutableComponent text(String key, Object... args) {
        return ScenarioSimulationClientState.text(key, args).copy();
    }
}
