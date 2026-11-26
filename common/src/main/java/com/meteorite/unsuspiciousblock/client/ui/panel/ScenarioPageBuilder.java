package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.meteosuikit.api.input.UiAction;
import com.meteorite.meteosuikit.api.layout.UiNode;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.state.ScenarioTableSnapshot;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalTextColors;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalTooltipSpec;
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
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ProbabilityFormat;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioConditionEvaluator;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioConditionEvaluator.BranchStatus;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationScenarioPlanner;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 场景详情文档构建器：将原子环境条件勾选区与按可获得性分层的分支状态组装为紧凑排版节点。
 * 严格遵守未发现物品防剧透约定：未解锁目标只渲染占位图标且不暴露名称与跳转。
 */
final class ScenarioPageBuilder {
    private static final int INDENT_STEP = 6;
    private static final int ITEM_INDENT = 8;
    private static final int ICONS_PER_ROW = 6;
    private static final String KEY_PREFIX = "screen.unsuspiciousblock.archaeology_journal.";

    enum Heading {
        SECTION_NEUTRAL,
        SECTION_AVAILABLE,
        SECTION_BLOCKED,
        SECTION_UNCERTAIN,
        ENTRY
    }

    private record Group(List<LootConditionInfo> conditions, List<LootConditionInfo> extras, LuckGate luck,
                         boolean uncertain, String source) {
    }

    private record EvaluatedGroup(int ordinal, Group group, List<ScenarioBranch> branches, BranchStatus status,
                                  BranchAppearance appearance) {
    }

    private enum BranchAppearance {
        BLOCKED("branch.unmet", JournalTextColors.SEVERE, UiAtlas.Icon.DISABLED, TooltipBuilder.NEGATIVE),
        UNCERTAIN("branch.uncertain", UiTextScheme.PARCHMENT.label(), UiAtlas.Icon.HELP, TooltipBuilder.LABEL),
        TRIGGERABLE("branch.triggerable", JournalTextColors.PROB_PROBABILISTIC, UiAtlas.Icon.CLOCK, TooltipBuilder.TITLE),
        AVAILABLE("branch.met", JournalTextColors.COMPLETED, UiAtlas.Icon.ENABLED, TooltipBuilder.POSITIVE);

        private final String labelKey;
        private final int color;
        private final UiAtlas.Icon icon;
        private final ChatFormatting tooltipStyle;

        BranchAppearance(String labelKey, int color, UiAtlas.Icon icon, ChatFormatting tooltipStyle) {
            this.labelKey = labelKey;
            this.color = color;
            this.icon = icon;
            this.tooltipStyle = tooltipStyle;
        }

        private static BranchAppearance from(BranchStatus status, boolean uncertainSource) {
            if (!status.allDeterministicSatisfied()) {
                return BLOCKED;
            }
            if (status.hasUnresolvedCondition() || uncertainSource) {
                return UNCERTAIN;
            }
            return status.hasRandomCondition() ? TRIGGERABLE : AVAILABLE;
        }
    }

    private ScenarioPageBuilder() {
    }

    static List<UiNode> buildConditions(ScenarioTableSnapshot snapshot,
                                        SimulationOptions options,
                                        Runnable dismissNotes,
                                        Runnable clearConditions,
                                        Consumer<String> toggleCondition,
                                        BiConsumer<String, String> locate) {
        CatalogTableDto structure = snapshot.structure();
        List<String> enabledConditions = snapshot.choice().conditions();
        ScenarioParams params = snapshot.choice().params();
        List<Component> notes = snapshot.notes();
        Set<String> enabledSet = Set.copyOf(enabledConditions);
        List<UiNode> content = new ArrayList<>();

        if (!notes.isEmpty()) {
            List<Component> dismissTip = List.of(text("notes.dismiss").withStyle(TooltipBuilder.BODY));
            content.add(new UiNode.Row(4, UiNode.NO_PARENT,
                    new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.CLOSE), dismissTip, null, dismissNotes::run),
                    text("notes.header").withStyle(ChatFormatting.BOLD),
                    UiTextScheme.PARCHMENT.accent(), List.of(),
                    dismissTip, null, dismissNotes::run));
            for (Component note : notes) {
                content.add(new UiNode.Row(10, UiNode.NO_PARENT, note, UiTextScheme.PARCHMENT.body(),
                        List.of(), List.of(), null, null));
            }
            content.add(new UiNode.Gap(6));
        }

        if (!options.conditions().isEmpty()) {
            Component toggleHeading = enabledSet.isEmpty()
                    ? text("conditions.toggles")
                    : text("conditions.toggles_count", enabledSet.size(), options.conditions().size());
            List<UiNode.InlineIcon> headerIcons = enabledSet.isEmpty()
                    ? List.of()
                    : List.of(new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.CLOSE),
                    List.of(text("conditions.clear").withStyle(TooltipBuilder.BODY)),
                    null, clearConditions::run));
            List<Component> togglesTip = List.of(text("conditions.toggles_hint").withStyle(TooltipBuilder.LABEL));
            content.add(sectionHeading(Heading.SECTION_NEUTRAL, toggleHeading, headerIcons, togglesTip));
            content.add(new UiNode.Gap(2));
            for (ConditionOption option : options.conditions()) {
                boolean checked = enabledSet.contains(option.id());
                UiIcon box = UiAtlas.icon(checked ? UiAtlas.Icon.CHECKBOX_CHECKED : UiAtlas.Icon.CHECKBOX_UNCHECKED);
                int labelColor = checked ? JournalTextColors.COMPLETED : UiTextScheme.PARCHMENT.body();
                Component label = ScenarioLabel.shortConditionLabel(option.condition()).copy()
                        .withStyle(style -> style.withColor(labelColor));
                List<Component> tooltip = ScenarioLabel.conditionTooltipLines(option.condition(), checked);
                UiAction action = () -> toggleCondition.accept(option.id());
                content.add(new UiNode.Row(6, UiNode.NO_PARENT,
                        new UiNode.InlineIcon(box, tooltip, option.id(), action),
                        label, labelColor, List.of(), tooltip, option.id(), action));
            }
            content.add(new UiNode.Gap(6));
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

        if (groups.isEmpty()) {
            content.add(sectionHeading(Heading.SECTION_NEUTRAL, text("conditions.branches"), List.of(), List.of()));
            content.add(new UiNode.Gap(4));
            content.add(new UiNode.Row(6, UiNode.NO_PARENT,
                    new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.ENABLED), List.of(), null, null),
                    text("conditions.all_unconditional"), JournalTextColors.COMPLETED,
                    List.of(), List.of(), null, null));
            return List.copyOf(content);
        }

        List<EvaluatedGroup> available = new ArrayList<>();
        List<EvaluatedGroup> blocked = new ArrayList<>();
        List<EvaluatedGroup> uncertain = new ArrayList<>();
        int ordinal = 0;
        for (var entry : groups.entrySet()) {
            Group group = entry.getKey();
            List<ScenarioBranch> branches = entry.getValue();
            BranchStatus status = ScenarioConditionEvaluator.evaluateBranch(
                    branches.getFirst(), enabledSet, params);
            BranchAppearance appearance = BranchAppearance.from(status, group.uncertain());
            EvaluatedGroup evaluated = new EvaluatedGroup(++ordinal, group, branches, status, appearance);
            switch (appearance) {
                case BLOCKED -> blocked.add(evaluated);
                case UNCERTAIN -> uncertain.add(evaluated);
                case TRIGGERABLE, AVAILABLE -> available.add(evaluated);
            }
        }

        appendBucket(content, snapshot, enabledSet, params,
                Heading.SECTION_AVAILABLE,
                text("branches.available", available.size()),
                List.of(text("branches.available_hint").withStyle(TooltipBuilder.LABEL)),
                available, toggleCondition, locate);
        appendBucket(content, snapshot, enabledSet, params,
                Heading.SECTION_BLOCKED,
                text("branches.blocked", blocked.size()),
                List.of(text("branches.blocked_hint").withStyle(TooltipBuilder.LABEL)),
                blocked, toggleCondition, locate);
        appendBucket(content, snapshot, enabledSet, params,
                Heading.SECTION_UNCERTAIN,
                text("branches.uncertain", uncertain.size()),
                List.of(text("branches.uncertain_hint").withStyle(TooltipBuilder.LABEL)),
                uncertain, toggleCondition, locate);
        return List.copyOf(content);
    }

    private static void appendBucket(List<UiNode> content,
                                     ScenarioTableSnapshot snapshot,
                                     Set<String> enabledSet,
                                     ScenarioParams params,
                                     Heading headingKind,
                                     Component title,
                                     List<Component> tooltip,
                                     List<EvaluatedGroup> bucket,
                                     Consumer<String> toggleCondition,
                                     BiConsumer<String, String> locate) {
        if (bucket.isEmpty()) {
            return;
        }
        if (!content.isEmpty() && !(content.getLast() instanceof UiNode.Gap)) {
            content.add(new UiNode.Gap(6));
        }
        content.add(sectionHeading(headingKind, title, List.of(), tooltip));
        for (EvaluatedGroup evaluated : bucket) {
            addBranch(content, snapshot, enabledSet, params, evaluated, toggleCondition, locate);
        }
    }

    private static void addBranch(List<UiNode> nodes,
                                  ScenarioTableSnapshot snapshot,
                                  Set<String> enabledSet,
                                  ScenarioParams params,
                                  EvaluatedGroup evaluated,
                                  Consumer<String> toggleCondition,
                                  BiConsumer<String, String> locate) {
        CatalogTableDto table = snapshot.structure();
        Group group = evaluated.group();
        List<ScenarioBranch> branches = evaluated.branches();
        BranchStatus status = evaluated.status();
        BranchAppearance appearance = evaluated.appearance();
        nodes.add(new UiNode.Gap(4));
        int parent = nodes.size();

        int constraints = constraintCount(group);
        if (isSingleCollapsibleBranch(group, constraints)) {
            addCollapsedBranchHeader(nodes, group, enabledSet, params, toggleCondition);
        } else {
            Component badgeText = text(appearance.labelKey);
            MutableComponent title = summarizeGroup(group, evaluated.ordinal(), constraints)
                    .withStyle(ChatFormatting.BOLD);
            if (appearance == BranchAppearance.TRIGGERABLE) {
                title.append(Component.literal(" · ").withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.hint())))
                        .append(badgeText.copy().withStyle(style -> style.withBold(false).withColor(appearance.color)));
            }

            List<Component> branchTooltip = new ArrayList<>();
            if (!group.source().isEmpty() && !group.source().equals("observed")) {
                branchTooltip.add(text("injection.known", group.source()).withStyle(TooltipBuilder.ACCENT));
            }
            if (group.uncertain()) {
                branchTooltip.add(text(group.source().equals("observed")
                        ? "injection.observed_hint" : "conditions.hypothetical").withStyle(TooltipBuilder.LABEL));
            }
            List<Component> immutableBranchTooltip = List.copyOf(branchTooltip);

            nodes.add(new UiNode.Row(4, UiNode.NO_PARENT,
                    new UiNode.InlineIcon(UiAtlas.icon(appearance.icon), immutableBranchTooltip, status, null),
                    title, UiTextScheme.PARCHMENT.title(), List.of(), immutableBranchTooltip,
                    Heading.ENTRY, null));

            for (LootConditionInfo condition : group.conditions()) {
                addScenarioCondition(nodes, condition, enabledSet, params, ITEM_INDENT, parent, null, toggleCondition);
            }
            for (LootConditionInfo extra : group.extras()) {
                addExtraCondition(nodes, extra, params, parent);
            }
            addLuckRows(nodes, group.luck(), params, ITEM_INDENT, parent, null);
        }

        Set<String> seen = new HashSet<>();
        List<UiNode.InlineIcon> icons = new ArrayList<>();
        for (ScenarioBranch branch : branches) {
            if (!seen.add(branch.kind() + "#" + branch.target())) {
                continue;
            }
            var target = snapshot.target(branch.kind(), branch.target());
            UiIcon icon = UiAtlas.UNKNOWN_ITEM;
            List<Component> tooltip = List.of(
                    text("conditions.undiscovered").withStyle(TooltipBuilder.LABEL),
                    Component.translatable(KEY_PREFIX + "tooltip.undiscovered_hint").withStyle(TooltipBuilder.HINT)
            );
            UiAction action = null;
            if (target.visible()) {
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
                    name = target.name();
                }
                tooltip = List.of(
                        name.copy().withStyle(TooltipBuilder.BODY),
                        text("conditions.target_kind." + branch.kind()).withStyle(TooltipBuilder.LABEL),
                        text(appearance.labelKey).withStyle(appearance.tooltipStyle),
                        text("conditions.locate").withStyle(TooltipBuilder.HINT)
                );
                action = () -> locate.accept(branch.kind(), branch.target());
            }
            icons.add(new UiNode.InlineIcon(icon, tooltip, branch, action));
            if (icons.size() == ICONS_PER_ROW) {
                nodes.add(candidateRow(parent, List.copyOf(icons)));
                icons.clear();
            }
        }
        if (!icons.isEmpty()) {
            nodes.add(candidateRow(parent, List.copyOf(icons)));
        }
    }

    private static boolean isSingleCollapsibleBranch(Group group, int constraints) {
        if (!group.source().isEmpty() || constraints != 1) {
            return false;
        }
        return group.conditions().stream().allMatch(c -> c.displayChildren().isEmpty())
                && group.extras().stream().allMatch(c -> c.displayChildren().isEmpty());
    }

    private static int luckConstraintCount(LuckGate luck) {
        int count = 0;
        if (luck.minLuck().isPresent()) {
            count++;
        }
        if (luck.rangeLimited()) {
            count++;
        }
        if (luck.bonusRollsGate().isPresent()) {
            count++;
        }
        return count;
    }

    private static int constraintCount(Group group) {
        return group.conditions().size() + group.extras().size() + luckConstraintCount(group.luck());
    }

    private static void addCollapsedBranchHeader(List<UiNode> nodes,
                                                 Group group,
                                                 Set<String> enabledSet,
                                                 ScenarioParams params,
                                                 Consumer<String> toggleCondition) {
        if (!group.conditions().isEmpty()) {
            addScenarioCondition(nodes, group.conditions().getFirst(), enabledSet, params,
                    4, UiNode.NO_PARENT, Heading.ENTRY, toggleCondition);
            return;
        }
        if (!group.extras().isEmpty()) {
            ConditionTree.Truth truth = ScenarioConditionEvaluator.evaluate(group.extras().getFirst(),
                    new SimulationInput(List.of(), params));
            addGenericConditionNode(nodes, group.extras().getFirst(), truth,
                    4, UiNode.NO_PARENT, false, Heading.ENTRY);
            return;
        }
        addLuckRows(nodes, group.luck(), params, 4, UiNode.NO_PARENT, Heading.ENTRY);
    }

    private static void addLuckRows(List<UiNode> nodes,
                                    LuckGate luck, ScenarioParams params,
                                    int indent, int parent, @Nullable Object payload) {
        if (luck.isTrivial()) {
            return;
        }
        if (luck.minLuck().isPresent()) {
            double threshold = luck.minLuck().getAsDouble();
            boolean luckOk = params.luck() >= threshold;
            String formatted = ProbabilityFormat.formatLuck(threshold);
            Component shortText = text("conditions.luck_gate_short", formatted);
            List<Component> tooltip = luckThresholdTooltip(
                    text("conditions.luck_gate", formatted), params.luck(), luckOk);
            nodes.add(new UiNode.Row(indent, parent,
                    new UiNode.InlineIcon(readOnlyTruthIcon(luckOk ? ConditionTree.Truth.TRUE : ConditionTree.Truth.FALSE),
                            tooltip, null, null),
                    shortText, luckOk ? JournalTextColors.COMPLETED : JournalTextColors.SEVERE,
                    List.of(), tooltip, payload, null));
        }
        if (luck.rangeLimited()) {
            List<Component> tooltip = List.of(text("conditions.luck_range").withStyle(TooltipBuilder.LABEL));
            nodes.add(new UiNode.Row(indent, parent,
                    new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.HELP), tooltip, null, null),
                    text("conditions.luck_range_short"), UiTextScheme.PARCHMENT.label(),
                    List.of(), tooltip, payload, null));
        }
        if (luck.bonusRollsGate().isPresent()) {
            double threshold = luck.bonusRollsGate().getAsDouble();
            boolean bonusOk = params.luck() >= threshold;
            String formatted = ProbabilityFormat.formatLuck(threshold);
            Component shortText = text("conditions.bonus_luck_short", formatted);
            List<Component> tooltip = luckThresholdTooltip(
                    text("conditions.bonus_luck", formatted), params.luck(), bonusOk);
            nodes.add(new UiNode.Row(indent, parent,
                    new UiNode.InlineIcon(readOnlyTruthIcon(bonusOk ? ConditionTree.Truth.TRUE : ConditionTree.Truth.FALSE),
                            tooltip, null, null),
                    shortText, bonusOk ? JournalTextColors.COMPLETED : JournalTextColors.SEVERE,
                    List.of(), tooltip, payload, null));
        }
    }

    private static List<Component> luckThresholdTooltip(Component requirement, float currentLuck, boolean met) {
        return List.of(
                requirement.copy().withStyle(TooltipBuilder.BODY),
                text("conditions.current_luck", ProbabilityFormat.formatLuck(currentLuck))
                        .withStyle(met ? TooltipBuilder.POSITIVE : TooltipBuilder.NEGATIVE)
        );
    }

    private static MutableComponent summarizeGroup(Group group, int ordinal, int constraintCount) {
        if (group.source().equals("observed")) {
            return text("injection.observed");
        }
        if (!group.source().isEmpty()) {
            return text("injection.known", group.source());
        }
        if (constraintCount > 1) {
            return text("conditions.combo_summary", constraintCount);
        }
        if (!group.conditions().isEmpty()) {
            return ScenarioLabel.shortConditionLabel(group.conditions().getFirst()).copy();
        }
        if (!group.extras().isEmpty()) {
            return ScenarioLabel.shortConditionLabel(group.extras().getFirst()).copy();
        }
        return text("conditions.branch", ordinal);
    }

    private static void addScenarioCondition(List<UiNode> nodes,
                                             LootConditionInfo condition,
                                             Set<String> enabledSet,
                                             ScenarioParams params,
                                             int indent,
                                             int parent,
                                             @Nullable Object payload,
                                             Consumer<String> toggleCondition) {
        if (ConditionTree.kind(condition) == ConditionTree.Kind.LEAF
                && SimulationScenarioPlanner.isScenarioControlled(condition.conditionType())
                && ConditionIdentity.isStable(condition)) {
            String key = condition.identity().key();
            boolean met = enabledSet.contains(key);
            Component styled = ScenarioLabel.shortConditionLabel(condition);
            List<Component> tooltip = ScenarioLabel.conditionTooltipLines(condition, met);
            UiAction action = () -> toggleCondition.accept(key);
            nodes.add(new UiNode.Row(indent, parent,
                    new UiNode.InlineIcon(UiAtlas.icon(met ? UiAtlas.Icon.CHECKBOX_CHECKED : UiAtlas.Icon.CHECKBOX_UNCHECKED),
                            tooltip, key, action),
                    styled,
                    met ? JournalTextColors.COMPLETED : JournalTextColors.SEVERE,
                    List.of(), tooltip, payload != null ? payload : key, action));
            return;
        }
        ConditionTree.Truth truth = ScenarioConditionEvaluator.evaluate(condition,
                new SimulationInput(List.copyOf(enabledSet), params));
        addGenericConditionNode(nodes, condition, truth, indent, parent, false, payload);
    }

    private static void addExtraCondition(List<UiNode> nodes,
                                          LootConditionInfo condition,
                                          ScenarioParams params,
                                          int parent) {
        ConditionTree.Truth truth = ScenarioConditionEvaluator.evaluate(condition,
                new SimulationInput(List.of(), params));
        addGenericConditionNode(nodes, condition, truth, ITEM_INDENT, parent, true, null);
    }

    private static void addGenericConditionNode(List<UiNode> nodes,
                                                ConditionPresentation condition,
                                                ConditionTree.Truth truth,
                                                int indent,
                                                int parent,
                                                boolean secondary,
                                                @Nullable Object payload) {
        int self = nodes.size();
        boolean uncertain = condition.metadata().containsKey(LootConditionHandlers.FIDELITY_METADATA_KEY);
        MutableComponent styled = Component.empty();
        Component inner = ScenarioLabel.conditionText(condition).copy();
        if (condition instanceof LootConditionInfo info && ScenarioConditionEvaluator.isRandomCondition(info)) {
            styled.append(text("conditions.random_trigger").withStyle(style -> style.withColor(JournalTextColors.PROB_PROBABILISTIC)))
                    .append(" · ");
        }
        styled.append(inner);

        ConditionTree.Truth effectiveTruth = uncertain ? ConditionTree.Truth.UNKNOWN : truth;
        UiIcon statusIcon = readOnlyTruthIcon(effectiveTruth);
        ChatFormatting statusStyle = switch (effectiveTruth) {
            case TRUE -> TooltipBuilder.POSITIVE;
            case FALSE -> TooltipBuilder.NEGATIVE;
            case UNKNOWN -> uncertain ? TooltipBuilder.NEGATIVE : TooltipBuilder.LABEL;
        };
        Component statusText = switch (effectiveTruth) {
            case TRUE -> text("branch.met");
            case FALSE -> text("branch.unmet");
            case UNKNOWN -> uncertain
                    ? text("conditions." + condition.metadata().get(LootConditionHandlers.FIDELITY_METADATA_KEY))
                    : text("branch.uncertain");
        };
        List<Component> tooltipLines = new ArrayList<>();
        tooltipLines.add(statusText.copy().withStyle(statusStyle));
        Component titleText = ScenarioLabel.plainShortLabel(condition);
        Component fullDesc = ScenarioLabel.displayDescription(condition);
        if (!fullDesc.getString().equals(titleText.getString()) && !ProbabilityFormat.isGenericWrapper(condition)) {
            tooltipLines.add(fullDesc.copy().withStyle(TooltipBuilder.BODY));
        }
        if (JournalTooltipSpec.showAdvancedIds()) {
            tooltipLines.add(Component.literal(condition.conditionType().toString()).withStyle(TooltipBuilder.HINT));
        }
        List<Component> tooltip = List.copyOf(tooltipLines);

        int color = truth == ConditionTree.Truth.FALSE
                ? JournalTextColors.SEVERE
                : truth == ConditionTree.Truth.TRUE && !secondary
                ? JournalTextColors.COMPLETED
                : secondary ? UiTextScheme.PARCHMENT.label() : UiTextScheme.PARCHMENT.body();
        nodes.add(new UiNode.Row(indent, parent,
                new UiNode.InlineIcon(statusIcon, tooltip, null, null), styled, color,
                List.of(), tooltip, payload, null));
        for (ConditionPresentation child : condition.displayChildren()) {
            addGenericConditionNode(nodes, child, ConditionTree.Truth.UNKNOWN,
                    indent + INDENT_STEP, self, secondary, null);
        }
    }

    private static UiIcon readOnlyTruthIcon(ConditionTree.Truth truth) {
        return switch (truth) {
            case TRUE -> UiAtlas.icon(UiAtlas.Icon.ENABLED);
            case FALSE -> UiAtlas.STATUS_BLOCKED;
            case UNKNOWN -> UiAtlas.icon(UiAtlas.Icon.HELP);
        };
    }

    private static UiNode.Row candidateRow(int parent, List<UiNode.InlineIcon> icons) {
        return new UiNode.Row(ITEM_INDENT, parent,
                null,
                Component.empty(), UiTextScheme.PARCHMENT.accent(), icons,
                List.of(), null, null);
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

    private static UiNode.Row sectionHeading(Heading kind,
                                             Component value,
                                             List<UiNode.InlineIcon> icons,
                                             List<Component> tooltip) {
        UiNode.InlineIcon leading = switch (kind) {
            case SECTION_AVAILABLE -> new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.ENABLED), tooltip, null, null);
            case SECTION_BLOCKED -> new UiNode.InlineIcon(UiAtlas.STATUS_BLOCKED, tooltip, null, null);
            case SECTION_UNCERTAIN -> new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.HELP), tooltip, null, null);
            case SECTION_NEUTRAL, ENTRY -> null;
        };
        int color = switch (kind) {
            case SECTION_AVAILABLE -> JournalTextColors.COMPLETED;
            case SECTION_BLOCKED -> JournalTextColors.SEVERE;
            case SECTION_UNCERTAIN -> JournalTextColors.PROB_UNKNOWN;
            case SECTION_NEUTRAL, ENTRY -> UiTextScheme.PARCHMENT.title();
        };
        return new UiNode.Row(4, UiNode.NO_PARENT, leading, value.copy().withStyle(ChatFormatting.BOLD),
                color, icons, tooltip, kind, null);
    }

    private static MutableComponent text(String key, Object... args) {
        return ScenarioSession.text(key, args).copy();
    }
}
