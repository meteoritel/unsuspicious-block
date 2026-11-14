package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.input.UiAction;
import com.meteorite.meteosuikit.api.layout.UiNode;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.state.ScenarioTableSnapshot;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionIdentity;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionPresentation;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranch;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ConditionOption;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioConditionEvaluator;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioConditionEvaluator.BranchStatus;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationScenarioPlanner;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * 场景详情文档构建器：将原子环境条件勾选区与实时分支/物品满足状态组装为统一排版节点。
 * 严格遵守未发现物品防剧透约定：未解锁目标只渲染占位图标且不暴露名称与跳转。
 */
final class ScenarioPageBuilder {
    private static final int INDENT_STEP = 10;
    private static final int ITEM_INDENT = 14;

    enum Heading {SECTION, ENTRY}

    private record Group(List<LootConditionInfo> conditions, List<LootConditionInfo> extras, LuckGate luck,
                         boolean uncertain, String source) {
    }

    private ScenarioPageBuilder() {
    }

    static List<UiNode> buildConditions(ScenarioTableSnapshot snapshot,
                                        Runnable dismissNotes,
                                        Consumer<String> toggleCondition,
                                        Consumer<SimulationAssistTarget> recommend,
                                        BiConsumer<String, String> locate) {
        CatalogTableDto structure = snapshot.structure();
        List<String> enabledConditions = snapshot.choice().conditions();
        ScenarioParams params = snapshot.choice().params();
        List<Component> notes = snapshot.notes();
        BiPredicate<String, String> visible = (kind, target) -> snapshot.target(kind, target).visible();
        SimulationOptions options = structure.options();
        if (options == null) {
            return List.of();
        }
        Set<String> enabledSet = Set.copyOf(enabledConditions);
        List<UiNode> content = new ArrayList<>();

        if (!notes.isEmpty()) {
            content.add(new UiNode.Row(4, UiNode.NO_PARENT,
                    new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.CLOSE),
                            List.of(text("notes.dismiss")), null, dismissNotes::run),
                    text("notes.header").withStyle(ChatFormatting.BOLD),
                    UiTextScheme.PARCHMENT.accent(), List.of(),
                    List.of(text("notes.dismiss")), null, dismissNotes::run));
            for (Component note : notes) {
                content.add(new UiNode.Row(10, UiNode.NO_PARENT, note, UiTextScheme.PARCHMENT.body(),
                        List.of(), List.of(), null, null));
            }
            content.add(new UiNode.Gap(6));
        }

        if (!options.conditions().isEmpty()) {
            content.add(heading(text("conditions.toggles"),
                    List.of(text("conditions.toggles_hint"))));
            content.add(new UiNode.Gap(3));
            for (ConditionOption option : options.conditions()) {
                boolean checked = enabledSet.contains(option.id());
                UiIcon box = UiAtlas.icon(checked ? UiAtlas.Icon.CHECKBOX_CHECKED : UiAtlas.Icon.CHECKBOX_UNCHECKED);
                Component label = ScenarioLabel.shortConditionLabel(option.condition()).copy()
                        .withStyle(style -> style.withColor(
                                checked ? UiTextScheme.PARCHMENT.positive() : UiTextScheme.PARCHMENT.body()));
                List<Component> tooltip = ScenarioLabel.conditionTooltip(option.condition(), checked);
                UiAction action = () -> toggleCondition.accept(option.id());
                content.add(new UiNode.Row(6, UiNode.NO_PARENT,
                        new UiNode.InlineIcon(box, tooltip, option.id(), action),
                        label,
                        checked ? UiTextScheme.PARCHMENT.positive() : UiTextScheme.PARCHMENT.body(),
                        List.of(), tooltip, option.id(), action));
            }
            content.add(new UiNode.Gap(8));
        }

        Map<Group, List<ScenarioBranch>> groups = new LinkedHashMap<>();
        for (ScenarioBranch branch : structure.branches()) {
            if (branch.injectionSource().isEmpty() && branch.conditions().isEmpty()
                    && branch.requirements().isEmpty() && branch.luck().isTrivial()) {
                continue;
            }
            Group group = new Group(branch.conditions(), branch.requirements(), branch.luck(),
                    branch.uncertain(), branch.injectionSource());
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(branch);
        }

        content.add(heading(text("conditions.branches"), List.of(text("conditions.candidates_hint"))));
        content.add(new UiNode.Row(6, UiNode.NO_PARENT,
                text("conditions.candidates_intro"), UiTextScheme.PARCHMENT.hint(),
                List.of(), List.of(), null, null));
        content.add(new UiNode.Gap(2));
        int ordinal = 0;
        for (var entry : groups.entrySet()) {
            if (!entry.getKey().source().isEmpty()) {
                continue;
            }
            addBranch(content, snapshot, enabledSet, params,
                    ++ordinal, entry.getKey(), entry.getValue(), toggleCondition, visible, locate, recommend);
        }
        boolean hasInjection = groups.keySet().stream().anyMatch(group -> !group.source().isEmpty());
        if (ordinal == 0 && !hasInjection) {
            content.add(new UiNode.Gap(4));
            content.add(new UiNode.Row(6, UiNode.NO_PARENT,
                    text("conditions.no_branches"), UiTextScheme.PARCHMENT.label(),
                    List.of(), List.of(), null, null));
        }
        if (hasInjection) {
            content.add(new UiNode.Gap(10));
            content.add(heading(text("injection.title"), List.of(text("injection.limit"))));
            for (var entry : groups.entrySet()) {
                if (entry.getKey().source().isEmpty()) {
                    continue;
                }
                addBranch(content, snapshot, enabledSet, params,
                        ++ordinal, entry.getKey(), entry.getValue(), toggleCondition, visible, locate, recommend);
            }
        }
        return List.copyOf(content);
    }

    private static void addBranch(List<UiNode> nodes,
                                  ScenarioTableSnapshot snapshot,
                                  Set<String> enabledSet,
                                  ScenarioParams params,
                                  int ordinal,
                                  Group group,
                                  List<ScenarioBranch> branches,
                                  Consumer<String> toggleCondition,
                                  BiPredicate<String, String> visible,
                                  BiConsumer<String, String> locate,
                                  Consumer<SimulationAssistTarget> recommend) {
        CatalogTableDto table = snapshot.structure();
        nodes.add(new UiNode.Gap(6));
        int parent = nodes.size();
        ScenarioBranch representative = branches.getFirst();
        BranchStatus status = ScenarioConditionEvaluator.evaluateBranch(
                representative, enabledSet, params);

        MutableComponent title = (group.source().equals("observed") ? text("injection.observed")
                : !group.source().isEmpty() ? text("injection.known", group.source())
                : text("conditions.branch", ordinal)).withStyle(ChatFormatting.BOLD);
        Component badgeText;
        int badgeColor;
        if (!status.allDeterministicSatisfied()) {
            badgeText = text("branch.unmet");
            badgeColor = UiTextScheme.PARCHMENT.negative();
        } else if (status.hasUnresolvedCondition()) {
            badgeText = text("branch.uncertain");
            badgeColor = UiTextScheme.PARCHMENT.accent();
        } else {
            badgeText = text(status.hasRandomCondition() ? "branch.triggerable" : "branch.met");
            badgeColor = UiTextScheme.PARCHMENT.positive();
        }
        List<Component> branchTooltip = new ArrayList<>();
        branchTooltip.add(badgeText.copy().withStyle(style -> style.withColor(badgeColor)));
        if (group.uncertain()) {
            branchTooltip.add(text(group.source().equals("observed")
                    ? "injection.observed_hint" : "conditions.hypothetical"));
        }
        UiIcon statusIcon = branchStatusIcon(status);
        nodes.add(new UiNode.Row(4, UiNode.NO_PARENT,
                new UiNode.InlineIcon(statusIcon,
                        branchTooltip,
                        status, null),
                title, UiTextScheme.PARCHMENT.title(), List.of(), branchTooltip,
                Heading.ENTRY, null));

        for (LootConditionInfo condition : group.conditions()) {
            addScenarioCondition(nodes, condition, enabledSet, params, parent, toggleCondition);
        }
        if (!group.extras().isEmpty() || !group.luck().isTrivial()) {
            int extraParent = nodes.size();
            nodes.add(new UiNode.Row(ITEM_INDENT, parent, text("conditions.additional"),
                    UiTextScheme.PARCHMENT.label(), List.of(), List.of(), null, null));
            for (LootConditionInfo extra : group.extras()) {
                addExtraCondition(nodes, extra, params, extraParent);
            }
            LuckGate luck = group.luck();
            if (luck.minLuck().isPresent()) {
                boolean luckOk = params.luck() >= luck.minLuck().getAsDouble();
                Component luckText = text("conditions.luck_gate", String.format(Locale.ROOT, "%.2f", luck.minLuck().getAsDouble()));
                nodes.add(new UiNode.Row(ITEM_INDENT + INDENT_STEP, extraParent,
                        new UiNode.InlineIcon(conditionIcon(luckOk ? ConditionTree.Truth.TRUE : ConditionTree.Truth.FALSE),
                                List.of(luckText), null, null), luckText,
                        luckOk ? UiTextScheme.PARCHMENT.positive() : UiTextScheme.PARCHMENT.negative(),
                        List.of(), List.of(), null, null));
            }
            if (luck.rangeLimited()) {
                nodes.add(new UiNode.Row(ITEM_INDENT + INDENT_STEP, extraParent,
                        text("conditions.luck_range"), UiTextScheme.PARCHMENT.label(),
                        List.of(), List.of(), null, null));
            }
            if (luck.bonusRollsGate().isPresent()) {
                boolean bonusOk = params.luck() >= luck.bonusRollsGate().getAsDouble();
                Component bonusText = text("conditions.bonus_luck", String.format(Locale.ROOT, "%.2f", luck.bonusRollsGate().getAsDouble()));
                nodes.add(new UiNode.Row(ITEM_INDENT + INDENT_STEP, extraParent,
                        new UiNode.InlineIcon(conditionIcon(bonusOk ? ConditionTree.Truth.TRUE : ConditionTree.Truth.FALSE),
                                List.of(bonusText), null, null), bonusText,
                        bonusOk ? UiTextScheme.PARCHMENT.positive() : UiTextScheme.PARCHMENT.negative(),
                        List.of(), List.of(), null, null));
            }
        }
        Set<String> seen = new HashSet<>();
        List<UiNode.InlineIcon> icons = new ArrayList<>();
        ScenarioBranch recommendBranch = branches.stream().filter(branch -> branch.recommendable()
                && visible.test(branch.kind(), branch.target())).findFirst().orElse(null);
        for (ScenarioBranch branch : branches) {
            if (!seen.add(branch.kind() + "#" + branch.target())) {
                continue;
            }
            boolean revealed = visible.test(branch.kind(), branch.target());
            UiIcon icon = UiAtlas.UNKNOWN_ITEM;
            List<Component> tooltip = List.of(text("conditions.undiscovered").withStyle(TooltipBuilder.LABEL));
            UiAction action = null;
            if (revealed) {
                Component name;
                if (branch.kind().equals("item")) {
                    var signature = LootResultSignature.fromStoredKey(branch.target());
                    if (signature == null) {
                        continue;
                    }
                    icon = new UiIcon.Item(signature.createPreviewStack());
                    name = table.items().stream()
                            .filter(item -> item.signature().equals(signature))
                            .map(CatalogTableDto.ItemEntry::displayName)
                            .findFirst()
                            .orElse(Component.literal(signature.itemId().toString()));
                } else {
                    icon = new UiIcon.Item(new ItemStack(branch.kind().equals("table") ? Items.BOOK : Items.NAME_TAG));
                    name = snapshot.target(branch.kind(), branch.target()).name();
                }
                tooltip = List.of(
                        text("conditions.target." + branch.kind(), name).withStyle(TooltipBuilder.BODY),
                        text("conditions.candidates_hint").withStyle(TooltipBuilder.LABEL),
                        text("conditions.locate").withStyle(TooltipBuilder.POSITIVE));
                action = () -> locate.accept(branch.kind(), branch.target());
            }
            icons.add(new UiNode.InlineIcon(icon, tooltip, branch, action));
            if (icons.size() == 4) {
                nodes.add(candidateRow(parent, List.copyOf(icons)));
                icons.clear();
            }
        }
        if (!icons.isEmpty()) {
            nodes.add(candidateRow(parent, List.copyOf(icons)));
        }
        if (recommendBranch != null) {
            Component label = text("recommend.branch", snapshot.target(recommendBranch.kind(), recommendBranch.target()).name());
            UiAction action = () -> recommend.accept(SimulationAssistTarget.branch(recommendBranch.reference()));
            nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                    new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.RECOMMEND), List.of(label), null, action),
                    label, UiTextScheme.PARCHMENT.accent(), List.of(), List.of(label), null, action));
        } else if (branches.stream().anyMatch(branch -> visible.test(branch.kind(), branch.target()))) {
            nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                    text(group.source().equals("observed") ? "recommend.observed" : "recommend.tag"),
                    UiTextScheme.PARCHMENT.label(), List.of(), List.of(), null, null));
        }
    }

    private static void addScenarioCondition(List<UiNode> nodes,
                                             LootConditionInfo condition,
                                             Set<String> enabledSet,
                                             ScenarioParams params,
                                             int parent,
                                             Consumer<String> toggleCondition) {
        if (ConditionTree.kind(condition) == ConditionTree.Kind.LEAF
                && SimulationScenarioPlanner.isScenarioControlled(condition.conditionType())
                && ConditionIdentity.isStable(condition)) {
            boolean met = enabledSet.contains(condition.identity().key());
            Component styled = ScenarioLabel.shortConditionLabel(condition);
            List<Component> tooltip = ScenarioLabel.conditionTooltip(condition, met);
            UiAction action = () -> toggleCondition.accept(condition.identity().key());
            nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                    new UiNode.InlineIcon(conditionIcon(met ? ConditionTree.Truth.TRUE : ConditionTree.Truth.FALSE),
                            tooltip, condition.identity().key(), action), styled,
                    met ? UiTextScheme.PARCHMENT.positive() : UiTextScheme.PARCHMENT.negative(),
                    List.of(), tooltip, condition.identity().key(), action));
            return;
        }
        ConditionTree.Truth truth = ScenarioConditionEvaluator.evaluate(condition, new SimulationInput(List.copyOf(enabledSet), params));
        addGenericConditionNode(nodes, condition, truth, ITEM_INDENT, parent, false);
    }

    private static void addExtraCondition(List<UiNode> nodes,
                                          LootConditionInfo condition,
                                          ScenarioParams params,
                                          int parent) {
        ConditionTree.Truth truth = ScenarioConditionEvaluator.evaluate(condition,
                new SimulationInput(List.of(), params));
        addGenericConditionNode(nodes, condition, truth, ITEM_INDENT + INDENT_STEP, parent, true);
    }

    private static void addGenericConditionNode(List<UiNode> nodes,
                                                ConditionPresentation condition,
                                                ConditionTree.Truth truth,
                                                int indent,
                                                int parent,
                                                boolean secondary) {
        int self = nodes.size();
        boolean uncertain = condition.metadata().containsKey(LootConditionHandlers.FIDELITY_METADATA_KEY);
        MutableComponent styled = Component.empty();
        Component inner = ScenarioLabel.conditionText(condition).copy();
        if (condition.conditionType().getPath().contains("random_chance")
                || condition.conditionType().getPath().equals("table_bonus")) {
            styled.append(text("conditions.random_trigger").withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.accent())))
                    .append(" · ");
        }
        styled.append(inner);
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(condition.description().copy().withStyle(TooltipBuilder.BODY));
        tooltip.add(Component.literal(condition.conditionType().toString()).withStyle(TooltipBuilder.HINT));
        if (uncertain) {
            tooltip.add(text("conditions." + condition.metadata().get(LootConditionHandlers.FIDELITY_METADATA_KEY))
                    .withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.negative())));
        }
        int color = truth == ConditionTree.Truth.FALSE
                ? UiTextScheme.PARCHMENT.negative()
                : truth == ConditionTree.Truth.TRUE && !secondary
                ? UiTextScheme.PARCHMENT.positive()
                : secondary ? UiTextScheme.PARCHMENT.label() : UiTextScheme.PARCHMENT.body();
        nodes.add(new UiNode.Row(indent, parent,
                new UiNode.InlineIcon(conditionIcon(uncertain ? ConditionTree.Truth.UNKNOWN : truth),
                        tooltip, null, null), styled, color, List.of(), tooltip, null, null));
        for (ConditionPresentation child : condition.displayChildren()) {
            addGenericConditionNode(nodes, child, ConditionTree.Truth.UNKNOWN, indent + INDENT_STEP, self, secondary);
        }
    }

    private static UiIcon branchStatusIcon(BranchStatus status) {
        if (!status.allDeterministicSatisfied()) {
            return UiAtlas.icon(UiAtlas.Icon.CHECKBOX_UNCHECKED);
        }
        if (status.hasUnresolvedCondition()) {
            return UiAtlas.icon(UiAtlas.Icon.HELP_CREAM);
        }
        return UiAtlas.icon(status.hasRandomCondition()
                ? UiAtlas.Icon.CLOCK : UiAtlas.Icon.CHECKBOX_CHECKED);
    }

    private static UiIcon conditionIcon(ConditionTree.Truth truth) {
        return switch (truth) {
            case TRUE -> UiAtlas.icon(UiAtlas.Icon.CHECKBOX_CHECKED);
            case FALSE -> UiAtlas.icon(UiAtlas.Icon.CHECKBOX_UNCHECKED);
            case UNKNOWN -> UiAtlas.icon(UiAtlas.Icon.HELP_CREAM);
        };
    }

    private static UiNode.Row candidateRow(int parent, List<UiNode.InlineIcon> icons) {
        return new UiNode.Row(ITEM_INDENT, parent,
                new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.BRANCH_ARROW), List.of(), null, null),
                Component.empty(), UiTextScheme.PARCHMENT.accent(), icons,
                List.of(text("conditions.candidates_hint").withStyle(TooltipBuilder.LABEL)), null, null);
    }

    /**
     * 灯箱只负责阅读与拖动，移除节点动作以免按住可点击图标时阻断平移。
     */
    static List<UiNode> copyForLightbox(List<UiNode> nodes) {
        return nodes.stream().map(ScenarioPageBuilder::copyWithoutActions).toList();
    }

    private static UiNode copyWithoutActions(UiNode node) {
        if (node instanceof UiNode.Row row) {
            UiNode.InlineIcon leading = row.leading() == null ? null : copyWithoutAction(row.leading());
            List<UiNode.InlineIcon> icons = row.icons().stream()
                    .map(ScenarioPageBuilder::copyWithoutAction).toList();
            return new UiNode.Row(row.indent(), row.parentRow(), leading, row.text(), row.color(), icons,
                    row.tooltip(), row.payload(), null);
        }
        if (node instanceof UiNode.Frame(var spec, var content)) {
            return new UiNode.Frame(spec, content.stream()
                    .map(ScenarioPageBuilder::copyWithoutActions).toList());
        }
        return node;
    }

    private static UiNode.InlineIcon copyWithoutAction(UiNode.InlineIcon icon) {
        return new UiNode.InlineIcon(icon.icon(), icon.tooltip(), icon.payload(), null);
    }

    private static UiNode.Row heading(Component value, List<Component> tooltip) {
        return new UiNode.Row(4, UiNode.NO_PARENT, value.copy().withStyle(ChatFormatting.BOLD),
                UiTextScheme.PARCHMENT.title(), List.of(), tooltip, Heading.SECTION, null);
    }

    private static MutableComponent text(String key, Object... args) {
        return ScenarioSession.text(key, args).copy();
    }
}
