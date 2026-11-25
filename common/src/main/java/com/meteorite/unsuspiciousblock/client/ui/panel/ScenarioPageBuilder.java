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
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioConditionEvaluator.BranchStatus;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;
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
import java.util.function.Consumer;

/**
 * 场景详情文档构建器：将原子环境条件勾选区与按可获得性分层的分支状态组装为紧凑排版节点。
 * 严格遵守未发现物品防剧透约定：未解锁目标只渲染占位图标且不暴露名称与跳转。
 */
final class ScenarioPageBuilder {
    private static final int INDENT_STEP = 6;
    private static final int ITEM_INDENT = 8;
    private static final int ICONS_PER_ROW = 6;
    private static final int SECTION_TITLE_COLOR = 0xFFFFF1D0;

    enum Heading {SECTION, ENTRY}

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
                                        Consumer<SimulationAssistTarget> recommend,
                                        BiConsumer<String, String> locate) {
        CatalogTableDto structure = snapshot.structure();
        List<String> enabledConditions = snapshot.choice().conditions();
        ScenarioParams params = snapshot.choice().params();
        List<Component> notes = snapshot.notes();
        Set<String> enabledSet = Set.copyOf(enabledConditions);
        List<UiNode> content = new ArrayList<>();

        if (!notes.isEmpty()) {
            content.add(new UiNode.Row(4, UiNode.NO_PARENT,
                    new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.CLOSE),
                            List.of(text("notes.dismiss").withStyle(TooltipBuilder.BODY)), null, dismissNotes::run),
                    text("notes.header").withStyle(ChatFormatting.BOLD),
                    UiTextScheme.PARCHMENT.accent(), List.of(),
                    List.of(text("notes.dismiss").withStyle(TooltipBuilder.BODY)), null, dismissNotes::run));
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
                    List.of(text("conditions.clear").withStyle(TooltipBuilder.BODY)), null, clearConditions::run));
            content.add(heading(toggleHeading, headerIcons,
                    List.of(text("conditions.toggles_hint").withStyle(TooltipBuilder.LABEL))));
            content.add(new UiNode.Gap(2));
            for (ConditionOption option : options.conditions()) {
                boolean checked = enabledSet.contains(option.id());
                UiIcon box = UiAtlas.icon(checked ? UiAtlas.Icon.CHECKBOX_CHECKED : UiAtlas.Icon.CHECKBOX_UNCHECKED);
                int labelColor = checked ? JournalTextColors.COMPLETED : UiTextScheme.PARCHMENT.body();
                Component label = ScenarioLabel.shortConditionLabel(option.condition()).copy()
                        .withStyle(style -> style.withColor(labelColor));
                List<Component> tooltip = ScenarioLabel.conditionTooltip(option.condition(), checked);
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
            content.add(heading(text("conditions.branches"), List.of(), List.of()));
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
                text("branches.available", available.size()),
                List.of(text("branches.available_hint").withStyle(TooltipBuilder.LABEL)),
                available, toggleCondition, locate, recommend);
        appendBucket(content, snapshot, enabledSet, params,
                text("branches.blocked", blocked.size()),
                List.of(text("branches.blocked_hint").withStyle(TooltipBuilder.LABEL)),
                blocked, toggleCondition, locate, recommend);
        appendBucket(content, snapshot, enabledSet, params,
                text("branches.uncertain", uncertain.size()),
                List.of(text("branches.uncertain_hint").withStyle(TooltipBuilder.LABEL)),
                uncertain, toggleCondition, locate, recommend);
        return List.copyOf(content);
    }

    private static void appendBucket(List<UiNode> content,
                                     ScenarioTableSnapshot snapshot,
                                     Set<String> enabledSet,
                                     ScenarioParams params,
                                     Component title,
                                     List<Component> tooltip,
                                     List<EvaluatedGroup> bucket,
                                     Consumer<String> toggleCondition,
                                     BiConsumer<String, String> locate,
                                     Consumer<SimulationAssistTarget> recommend) {
        if (bucket.isEmpty()) {
            return;
        }
        if (!content.isEmpty() && !(content.getLast() instanceof UiNode.Gap)) {
            content.add(new UiNode.Gap(6));
        }
        content.add(heading(title, List.of(), tooltip));
        for (EvaluatedGroup evaluated : bucket) {
            addBranch(content, snapshot, enabledSet, params, evaluated,
                    toggleCondition, locate, recommend);
        }
    }

    private static void addBranch(List<UiNode> nodes,
                                  ScenarioTableSnapshot snapshot,
                                  Set<String> enabledSet,
                                  ScenarioParams params,
                                  EvaluatedGroup evaluated,
                                  Consumer<String> toggleCondition,
                                  BiConsumer<String, String> locate,
                                  Consumer<SimulationAssistTarget> recommend) {
        CatalogTableDto table = snapshot.structure();
        Group group = evaluated.group();
        List<ScenarioBranch> branches = evaluated.branches();
        BranchStatus status = evaluated.status();
        BranchAppearance appearance = evaluated.appearance();
        nodes.add(new UiNode.Gap(4));
        int parent = nodes.size();

        Component badgeText = text(appearance.labelKey);
        MutableComponent title = summarizeGroup(group, evaluated.ordinal())
                .withStyle(ChatFormatting.BOLD)
                .append(Component.literal(" · ").withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.hint())))
                .append(badgeText.copy().withStyle(style -> style.withBold(false).withColor(appearance.color)));

        List<Component> branchTooltip = new ArrayList<>();
        branchTooltip.add(badgeText.copy().withStyle(appearance.tooltipStyle));
        if (!group.source().isEmpty() && !group.source().equals("observed")) {
            branchTooltip.add(text("injection.known", group.source()).withStyle(TooltipBuilder.BODY));
        }
        if (group.uncertain()) {
            branchTooltip.add(text(group.source().equals("observed")
                    ? "injection.observed_hint" : "conditions.hypothetical").withStyle(TooltipBuilder.LABEL));
        }

        UiIcon statusIcon = UiAtlas.icon(appearance.icon);
        nodes.add(new UiNode.Row(4, UiNode.NO_PARENT,
                new UiNode.InlineIcon(statusIcon, branchTooltip, status, null),
                title, UiTextScheme.PARCHMENT.title(), List.of(), branchTooltip,
                Heading.ENTRY, null));

        for (LootConditionInfo condition : group.conditions()) {
            addScenarioCondition(nodes, condition, enabledSet, params, parent, toggleCondition);
        }
        for (LootConditionInfo extra : group.extras()) {
            addExtraCondition(nodes, extra, params, parent);
        }
        addLuckRows(nodes, group.luck(), params, parent);

        Set<String> seen = new HashSet<>();
        List<UiNode.InlineIcon> icons = new ArrayList<>();
        ScenarioBranch recommendBranch = branches.stream()
                .filter(branch -> branch.recommendable() && snapshot.target(branch.kind(), branch.target()).visible())
                .findFirst().orElse(null);

        for (ScenarioBranch branch : branches) {
            if (!seen.add(branch.kind() + "#" + branch.target())) {
                continue;
            }
            var target = snapshot.target(branch.kind(), branch.target());
            UiIcon icon = UiAtlas.UNKNOWN_ITEM;
            List<Component> tooltip = List.of(text("conditions.undiscovered").withStyle(TooltipBuilder.LABEL));
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
                        text("conditions.target." + branch.kind(), name).withStyle(TooltipBuilder.BODY),
                        text("conditions.locate").withStyle(TooltipBuilder.POSITIVE));
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

        boolean needsSetup = !status.allDeterministicSatisfied() || status.hasUnresolvedCondition();
        if (recommendBranch != null && needsSetup) {
            Component targetName = snapshot.target(recommendBranch.kind(), recommendBranch.target()).name();
            Component label = text("recommend.branch_short", targetName);
            List<Component> recTooltip = List.of(
                    text("recommend.branch", targetName).withStyle(TooltipBuilder.TITLE),
                    text("recommend.branch_tooltip").withStyle(TooltipBuilder.LABEL));
            UiAction action = () -> recommend.accept(SimulationAssistTarget.branch(recommendBranch.reference()));
            nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                    new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.RECOMMEND), recTooltip, null, action),
                    label, UiTextScheme.PARCHMENT.accent(), List.of(), recTooltip, null, action));
        } else if (needsSetup && branches.stream().anyMatch(branch -> snapshot.target(branch.kind(), branch.target()).visible())) {
            nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                    text(group.source().equals("observed") ? "recommend.observed" : "recommend.tag"),
                    UiTextScheme.PARCHMENT.label(), List.of(), List.of(), null, null));
        }
    }

    private static void addLuckRows(List<UiNode> nodes, LuckGate luck, ScenarioParams params, int parent) {
        if (luck.isTrivial()) {
            return;
        }
        if (luck.minLuck().isPresent()) {
            double threshold = luck.minLuck().getAsDouble();
            boolean luckOk = params.luck() >= threshold;
            String formatted = formatLuck(threshold);
            Component shortText = text("conditions.luck_gate_short", formatted);
            List<Component> tooltip = List.of(
                    text("conditions.luck_gate", formatted).withStyle(TooltipBuilder.BODY),
                    text("params.luck_marker.player", formatLuck(params.luck()))
                            .withStyle(luckOk ? TooltipBuilder.POSITIVE : TooltipBuilder.NEGATIVE));
            nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                    new UiNode.InlineIcon(readOnlyTruthIcon(luckOk ? ConditionTree.Truth.TRUE : ConditionTree.Truth.FALSE),
                            tooltip, null, null),
                    shortText, luckOk ? JournalTextColors.COMPLETED : JournalTextColors.SEVERE,
                    List.of(), tooltip, null, null));
        }
        if (luck.rangeLimited()) {
            List<Component> tooltip = List.of(text("conditions.luck_range").withStyle(TooltipBuilder.BODY));
            nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                    new UiNode.InlineIcon(UiAtlas.icon(UiAtlas.Icon.HELP), tooltip, null, null),
                    text("conditions.luck_range_short"), UiTextScheme.PARCHMENT.label(),
                    List.of(), tooltip, null, null));
        }
        if (luck.bonusRollsGate().isPresent()) {
            double threshold = luck.bonusRollsGate().getAsDouble();
            boolean bonusOk = params.luck() >= threshold;
            String formatted = formatLuck(threshold);
            Component shortText = text("conditions.bonus_luck_short", formatted);
            List<Component> tooltip = List.of(
                    text("conditions.bonus_luck", formatted).withStyle(TooltipBuilder.BODY),
                    text("params.luck_marker.player", formatLuck(params.luck()))
                            .withStyle(bonusOk ? TooltipBuilder.POSITIVE : TooltipBuilder.NEGATIVE));
            nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                    new UiNode.InlineIcon(readOnlyTruthIcon(bonusOk ? ConditionTree.Truth.TRUE : ConditionTree.Truth.FALSE),
                            tooltip, null, null),
                    shortText, bonusOk ? JournalTextColors.COMPLETED : JournalTextColors.SEVERE,
                    List.of(), tooltip, null, null));
        }
    }

    private static MutableComponent summarizeGroup(Group group, int ordinal) {
        if (group.source().equals("observed")) {
            return text("injection.observed");
        }
        if (!group.source().isEmpty()) {
            return text("injection.known", group.source());
        }
        List<Component> parts = new ArrayList<>();
        for (LootConditionInfo condition : group.conditions()) {
            parts.add(ScenarioLabel.shortConditionLabel(condition));
        }
        for (LootConditionInfo extra : group.extras()) {
            if (!ScenarioConditionEvaluator.isRandomCondition(extra)) {
                parts.add(ScenarioLabel.shortConditionLabel(extra));
            }
        }
        LuckGate luck = group.luck();
        if (luck.minLuck().isPresent()) {
            parts.add(text("conditions.luck_gate_short", formatLuck(luck.minLuck().getAsDouble())));
        } else if (luck.bonusRollsGate().isPresent()) {
            parts.add(text("conditions.bonus_luck_short", formatLuck(luck.bonusRollsGate().getAsDouble())));
        } else if (luck.rangeLimited()) {
            parts.add(text("conditions.luck_range_short"));
        }
        if (parts.isEmpty()) {
            for (LootConditionInfo extra : group.extras()) {
                parts.add(ScenarioLabel.shortConditionLabel(extra));
            }
        }
        if (parts.isEmpty()) {
            return text("conditions.branch", ordinal);
        }
        MutableComponent summary = parts.getFirst().copy();
        if (parts.size() > 1) {
            summary.append(Component.literal(" +" + (parts.size() - 1))
                    .withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.label())));
        }
        return summary;
    }

    private static String formatLuck(double luck) {
        return String.format(Locale.ROOT, "%.2f", luck);
    }

    private static void addScenarioCondition(List<UiNode> nodes,
                                             LootConditionInfo condition,
                                             Set<String> enabledSet,
                                             ScenarioParams params,
                                             int parent,
                                             Consumer<String> toggleCondition) {
        // 仅可切换的环境开关使用复选框，与下方的只读条件和门槛明确区分
        if (ConditionTree.kind(condition) == ConditionTree.Kind.LEAF
                && SimulationScenarioPlanner.isScenarioControlled(condition.conditionType())
                && ConditionIdentity.isStable(condition)) {
            boolean met = enabledSet.contains(condition.identity().key());
            Component styled = ScenarioLabel.shortConditionLabel(condition);
            List<Component> tooltip = ScenarioLabel.conditionTooltip(condition, met);
            UiAction action = () -> toggleCondition.accept(condition.identity().key());
            nodes.add(new UiNode.Row(ITEM_INDENT, parent,
                    new UiNode.InlineIcon(UiAtlas.icon(met ? UiAtlas.Icon.CHECKBOX_CHECKED : UiAtlas.Icon.CHECKBOX_UNCHECKED),
                            tooltip, condition.identity().key(), action),
                    styled,
                    met ? JournalTextColors.COMPLETED : JournalTextColors.SEVERE,
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
        addGenericConditionNode(nodes, condition, truth, ITEM_INDENT, parent, true);
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
        if (condition instanceof LootConditionInfo info && ScenarioConditionEvaluator.isRandomCondition(info)) {
            styled.append(text("conditions.random_trigger").withStyle(style -> style.withColor(JournalTextColors.PROB_PROBABILISTIC)))
                    .append(" · ");
        }
        styled.append(inner);
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(condition.description().copy().withStyle(TooltipBuilder.BODY));
        tooltip.add(Component.literal(condition.conditionType().toString()).withStyle(TooltipBuilder.HINT));
        if (uncertain) {
            tooltip.add(text("conditions." + condition.metadata().get(LootConditionHandlers.FIDELITY_METADATA_KEY))
                    .withStyle(TooltipBuilder.NEGATIVE));
        }
        int color = truth == ConditionTree.Truth.FALSE
                ? JournalTextColors.SEVERE
                : truth == ConditionTree.Truth.TRUE && !secondary
                ? JournalTextColors.COMPLETED
                : secondary ? UiTextScheme.PARCHMENT.label() : UiTextScheme.PARCHMENT.body();
        nodes.add(new UiNode.Row(indent, parent,
                new UiNode.InlineIcon(readOnlyTruthIcon(uncertain ? ConditionTree.Truth.UNKNOWN : truth),
                        tooltip, null, null), styled, color, List.of(), tooltip, null, null));
        for (ConditionPresentation child : condition.displayChildren()) {
            addGenericConditionNode(nodes, child, ConditionTree.Truth.UNKNOWN, indent + INDENT_STEP, self, secondary);
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

    private static UiNode.Row heading(Component value, List<UiNode.InlineIcon> icons, List<Component> tooltip) {
        return new UiNode.Row(4, UiNode.NO_PARENT, value.copy().withStyle(ChatFormatting.BOLD),
                SECTION_TITLE_COLOR, icons, tooltip, Heading.SECTION, null);
    }

    private static MutableComponent text(String key, Object... args) {
        return ScenarioSession.text(key, args).copy();
    }
}
