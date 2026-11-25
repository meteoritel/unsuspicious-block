package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalTooltipSpec;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionPresentation;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.FunctionFidelity;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootFunctionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ProbabilityFormat;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 考古笔记物品、子表与标签 Tooltip 构建器。
 *
 * <p>同时提供结构化视觉 {@link JournalTooltipSpec}（用于富图文混排渲染）与清理后的
 * 纯文本行列表回退，剥离通用条件容器冗余并消除概率提示与条件树之间的重复叙述。</p>
 */
public final class JournalTooltipBuilder {

    private static final int MAX_FUNCTION_RULE_DEPTH = 8;
    private static final String KEY_PREFIX = "screen.unsuspiciousblock.archaeology_journal.";
    private static final String KEY_PROBABILITY_CHIP = KEY_PREFIX + "tooltip.prob_chip";
    private static final String KEY_TRIGGER_CHIP = KEY_PREFIX + "tooltip.trigger_chip";
    private static final String KEY_ACQUIRED_CHIP = KEY_PREFIX + "tooltip.acquired_chip";

    private JournalTooltipBuilder() {
    }

    /**
     * 构建物品格富文本视觉 Tooltip。
     */
    public static JournalTooltipSpec buildRich(ItemGridPanel.TooltipData data) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        if (!data.discovered()) {
            spec.header(UiAtlas.UNKNOWN_ITEM,
                    Component.translatable(KEY_PREFIX + "undiscovered"),
                    TooltipBuilder.LABEL,
                    new JournalTooltipSpec.Pill(Component.translatable(KEY_PREFIX + "pending_analysis"), TooltipBuilder.HINT));
            spec.line(Component.translatable(KEY_PREFIX + "tooltip.undiscovered_hint").withStyle(TooltipBuilder.HINT));
            return spec.build();
        }

        Component title = data.displayName() != null ? data.displayName() : data.stack().getHoverName();
        JournalTooltipSpec.Pill statusBadge = itemStatusBadge(data);
        spec.itemHeader(data.stack(), title, TooltipBuilder.BODY, statusBadge);

        List<Component> details = new ArrayList<>();
        JournalItemDetailAppender.append(details, data.stack());
        for (Component detail : details) {
            spec.line(detail);
        }

        List<JournalTooltipSpec.MetricChip> chips = buildItemMetrics(data);
        spec.metrics(chips);

        data.stack().getTooltipImage().ifPresent(spec::nativeComponent);

        appendRichProbabilityNotes(spec, data);

        if (data.signature() == null && data.hint() != null) {
            spec.line(data.hint().copy().withStyle(TooltipBuilder.HINT, ChatFormatting.ITALIC));
        }

        appendRichAcquisitionPaths(spec, data.acquisitionPaths());
        return spec.build();
    }

    /**
     * 构建子表入口富文本视觉 Tooltip。
     */
    public static JournalTooltipSpec buildChildTableRich(Component displayName, ResourceLocation tableId,
                                                           Probability probability,
                                                           List<LootConditionInfo> conditions,
                                                           boolean luckAffected) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        JournalTooltipSpec.Pill probBadge = new JournalTooltipSpec.Pill(
                ProbabilityFormat.formatComponent(probability),
                probability.isDisplayable() ? TooltipBuilder.POSITIVE : TooltipBuilder.LABEL);
        spec.itemHeader(new ItemStack(Items.BOOK), displayName, TooltipBuilder.BODY, probBadge);

        if (JournalTooltipSpec.showAdvancedIds()) {
            spec.line(Component.literal(tableId.toString()).withStyle(TooltipBuilder.HINT));
        }
        if (luckAffected) {
            spec.metrics(List.of(new JournalTooltipSpec.MetricChip(
                    UiAtlas.LUCK_ICON,
                    Component.translatable(KEY_PREFIX + "tooltip.luck_affected"),
                    TooltipBuilder.ACCENT, true)));
        }
        if (!conditions.isEmpty()) {
            spec.divider();
            spec.line(Component.translatable(KEY_PREFIX + "parent_table_conditions_header")
                    .withStyle(TooltipBuilder.TITLE));
            appendRichConditionTree(spec, conditions, 1, List.of());
        }
        spec.footer(null, Component.translatable(KEY_PREFIX + "tooltip.click_action"),
                TooltipBuilder.ACCENT, Component.translatable(KEY_PREFIX + "child_table_open"));
        return spec.build();
    }

    /**
     * 构建物品标签合集入口的富文本视觉 Tooltip。
     */
    public static JournalTooltipSpec buildTagRich(ResourceLocation tagId, int discoveredCount, int totalCount) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        Component tagName = localizedTagName(tagId);
        JournalTooltipSpec.Pill progress = new JournalTooltipSpec.Pill(
                Component.literal(discoveredCount + "/" + totalCount),
                discoveredCount >= totalCount && totalCount > 0 ? TooltipBuilder.POSITIVE : TooltipBuilder.TITLE);
        spec.itemHeader(new ItemStack(Items.NAME_TAG), tagName, TooltipBuilder.BODY, progress);
        if (JournalTooltipSpec.showAdvancedIds()) {
            spec.line(Component.literal("#" + tagId).withStyle(TooltipBuilder.HINT));
        }
        spec.footer(null, Component.translatable(KEY_PREFIX + "tooltip.click_action"),
                TooltipBuilder.ACCENT, Component.translatable(KEY_PREFIX + "tag_group_open"));
        return spec.build();
    }

    /**
     * 构建物品标签页「返回」格子的富文本视觉 Tooltip。
     */
    public static JournalTooltipSpec buildTagBackRich(ResourceLocation tagId) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        spec.header(UiAtlas.icon(UiAtlas.Icon.BACK), localizedTagName(tagId), TooltipBuilder.BODY, null);
        if (JournalTooltipSpec.showAdvancedIds()) {
            spec.line(Component.literal("#" + tagId).withStyle(TooltipBuilder.HINT));
        }
        spec.footer(null, Component.translatable(KEY_PREFIX + "tooltip.click_action"),
                TooltipBuilder.ACCENT, Component.translatable(KEY_PREFIX + "tag_group_back_tooltip"));
        return spec.build();
    }

    /**
     * 构建网格右上角「推荐求解」按钮的富文本视觉 Tooltip。
     */
    public static JournalTooltipSpec buildRecommendRich(Component targetName) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        spec.header(UiAtlas.icon(UiAtlas.Icon.RECOMMEND), ScenarioSession.text("recommend.title"),
                TooltipBuilder.TITLE, null);
        spec.line(Component.translatable(KEY_PREFIX + "tooltip.recommend_desc", targetName)
                .withStyle(TooltipBuilder.LABEL));
        spec.footer(null, Component.translatable(KEY_PREFIX + "tooltip.click_action"),
                TooltipBuilder.ACCENT, ScenarioSession.text("recommend.branch_tooltip"));
        return spec.build();
    }

    /**
     * 构建物品纯文本 Tooltip 行列表（兼容文本导出与无障碍回退）。
     */
    public static List<Component> build(ItemGridPanel.TooltipData data) {
        return buildRich(data).plainLines();
    }

    /**
     * 构建子表入口纯文本 Tooltip 行列表。
     */
    public static List<Component> buildChildTable(Component displayName, ResourceLocation tableId,
                                                   Probability probability,
                                                   List<LootConditionInfo> conditions,
                                                   boolean luckAffected) {
        return buildChildTableRich(displayName, tableId, probability, conditions, luckAffected).plainLines();
    }

    /**
     * 将物品标签 ID 转换为本地化名称，缺省时退回 {@code #namespace:path}。
     */
    public static Component localizedTagName(ResourceLocation tagId) {
        return Component.translatableWithFallback(
                "tag.item." + tagId.getNamespace() + "." + tagId.getPath().replace('/', '.'),
                "#" + tagId);
    }

    /**
     * 为条件节点选择语义图标（群系、维度、时钟或未知警告）。
     */
    public static @Nullable UiIcon conditionIcon(ConditionPresentation info) {
        if (isFidelityUnreadable(info) || isFidelityIncomplete(info)) {
            return UiAtlas.icon(UiAtlas.Icon.HELP);
        }
        ResourceLocation type = info.conditionType();
        if (!"minecraft".equals(type.getNamespace())) {
            return null;
        }
        return switch (type.getPath()) {
            case "location_check" -> isDimensionCondition(info)
                    ? UiAtlas.icon(UiAtlas.Icon.DIMENSION) : UiAtlas.icon(UiAtlas.Icon.BIOME);
            case "random_chance", "random_chance_with_enchanted_bonus", "table_bonus", "time_check" ->
                    UiAtlas.icon(UiAtlas.Icon.CLOCK);
            case "weather_check" -> UiAtlas.icon(UiAtlas.Icon.BIOME);
            default -> null;
        };
    }

    private static boolean isDimensionCondition(ConditionPresentation info) {
        return info.description().getContents() instanceof TranslatableContents contents
                && (LootConditionHandlers.I18N_PREFIX + "location_check_dimension").equals(contents.getKey());
    }

    private static @Nullable JournalTooltipSpec.Pill itemStatusBadge(ItemGridPanel.TooltipData data) {
        Probability probability = data.probability();
        if (probability instanceof Probability.NeedsCondition needs) {
            boolean unresolved = ProbabilityFormat.hasUnresolvedConditions(needs.hints());
            return new JournalTooltipSpec.Pill(
                    Component.translatable(KEY_PREFIX
                            + (unresolved ? "probability_unresolved_condition" : "probability_needs_condition")),
                    unresolved ? TooltipBuilder.LABEL : TooltipBuilder.NEGATIVE);
        }
        if (probability instanceof Probability.Unreachable) {
            return new JournalTooltipSpec.Pill(Component.literal("0%"), TooltipBuilder.NEGATIVE);
        }
        if (probability != null && probability.isZeroHit()) {
            return new JournalTooltipSpec.Pill(
                    Component.translatable(KEY_PREFIX + "probability_miss"), TooltipBuilder.LABEL);
        }
        return null;
    }

    private static List<JournalTooltipSpec.MetricChip> buildItemMetrics(ItemGridPanel.TooltipData data) {
        List<JournalTooltipSpec.MetricChip> chips = new ArrayList<>();
        if (data.count() >= 0) {
            ChatFormatting countColor = data.count() > 0 ? TooltipBuilder.POSITIVE : TooltipBuilder.LABEL;
            chips.add(new JournalTooltipSpec.MetricChip(
                    null,
                    Component.translatable(KEY_ACQUIRED_CHIP, data.count()),
                    countColor, true));
        }
        if (!data.declaredChances().isEmpty()) {
            chips.add(new JournalTooltipSpec.MetricChip(
                    UiAtlas.icon(UiAtlas.Icon.CLOCK),
                    Component.translatable(KEY_TRIGGER_CHIP,
                            ProbabilityFormat.formatDeclaredChances(data.declaredChances())),
                    TooltipBuilder.CONDITION_PROBABILISTIC, true));
        }
        Probability probability = data.probability();
        if (probability instanceof Probability.Measured && !probability.isZeroHit()) {
            ChatFormatting probColor = numericProbabilityColor(data, probability);
            chips.add(new JournalTooltipSpec.MetricChip(
                    null,
                    Component.translatable(KEY_PROBABILITY_CHIP, ProbabilityFormat.formatComponent(probability)),
                    probColor, true));
        }
        if (data.acquisitionPaths().stream().anyMatch(LootAcquisitionPath::luckAffected)) {
            chips.add(new JournalTooltipSpec.MetricChip(
                    UiAtlas.LUCK_ICON,
                    Component.translatable(KEY_PREFIX + "tooltip.luck_affected"),
                    TooltipBuilder.ACCENT, true));
        }
        if (data.injected()) {
            chips.add(new JournalTooltipSpec.MetricChip(
                    null,
                    Component.translatable(KEY_PREFIX + "tooltip.injected_badge"),
                    TooltipBuilder.ACCENT, true));
        }
        return chips;
    }

    private static void appendRichProbabilityNotes(JournalTooltipSpec.Builder spec, ItemGridPanel.TooltipData data) {
        switch (data.probability()) {
            case null -> {
            }
            case Probability.NeedsCondition(List<PathHint> hints) -> {
                List<Component> conciseHints = ProbabilityFormat.describePathHints(hints);
                if (!conciseHints.isEmpty()) {
                    spec.divider();
                    Component prefix = Component.translatable(KEY_PREFIX
                            + (ProbabilityFormat.hasUnresolvedConditions(hints)
                            ? "probability_unresolved_condition_detail"
                            : "probability_needs_condition_detail")).withStyle(TooltipBuilder.NEGATIVE);
                    MutableComponent joined = prefix.copy();
                    for (int i = 0; i < conciseHints.size(); i++) {
                        if (i > 0) {
                            joined.append(Component.literal(" · ").withStyle(TooltipBuilder.HINT));
                        }
                        joined.append(conciseHints.get(i).copy().withStyle(TooltipBuilder.BODY));
                    }
                    spec.line(joined, UiAtlas.STATUS_BLOCKED, 0);
                }
            }
            case Probability.Unknown(UnknownReason reason) -> {
                spec.divider();
                spec.line(ProbabilityFormat.describeUnknown(reason).copy().withStyle(TooltipBuilder.LABEL),
                        UiAtlas.icon(UiAtlas.Icon.HELP), 0);
            }
            case Probability.Measured measured -> {
                if (measured.isZeroHit()) {
                    spec.divider();
                    spec.line(ProbabilityFormat.describeZeroHit(data.simulationCount())
                            .copy().withStyle(TooltipBuilder.LABEL), UiAtlas.icon(UiAtlas.Icon.HELP), 0);
                    spec.line(ProbabilityFormat.describeZeroHitHint().copy().withStyle(TooltipBuilder.HINT),
                            null, 14);
                }
            }
            case Probability.Unreachable ignored -> {
            }
        }
    }

    private static ChatFormatting numericProbabilityColor(ItemGridPanel.TooltipData data, Probability probability) {
        boolean probUncertain = probability.isUnknown();
        return switch (data.uncertaintyLevel()) {
            case PROBABILISTIC -> TooltipBuilder.CONDITION_PROBABILISTIC;
            case RUNTIME -> TooltipBuilder.CONDITION_RUNTIME;
            default -> probUncertain ? TooltipBuilder.LABEL : TooltipBuilder.POSITIVE;
        };
    }

    private static void appendRichAcquisitionPaths(JournalTooltipSpec.Builder spec, List<LootAcquisitionPath> paths) {
        if (paths.stream().noneMatch(path -> path.hasConditions()
                || path.sourceChildTable() != null
                || path.sourceItemTag() != null
                || !displayFunctions(path.functions(), 0).isEmpty())) {
            return;
        }
        spec.divider();
        if (paths.size() == 1) {
            appendRichSinglePath(spec, paths.getFirst());
            return;
        }
        spec.line(Component.translatable(KEY_PREFIX + "acquisition_paths_alternatives_header")
                .withStyle(TooltipBuilder.TITLE));
        for (int i = 0; i < paths.size(); i++) {
            boolean isLast = i == paths.size() - 1;
            spec.treeRow(1, isLast, List.of(), null,
                    Component.translatable(KEY_PREFIX + "acquisition_path", i + 1).withStyle(TooltipBuilder.ACCENT));
            appendRichPathDetails(spec, paths.get(i), List.of(!isLast));
        }
    }

    private static void appendRichSinglePath(JournalTooltipSpec.Builder spec, LootAcquisitionPath path) {
        List<Component> sources = collectSources(path);
        List<LootFunctionInfo> functions = displayFunctions(path.functions(), 0);
        if (path.hasConditions()) {
            spec.line(Component.translatable(KEY_PREFIX + "conditions_header").withStyle(TooltipBuilder.TITLE));
            if (!path.inheritedConditions().isEmpty()) {
                boolean inheritedLast = path.entryConditions().isEmpty();
                spec.treeRow(1, inheritedLast, List.of(), null,
                        Component.translatable(KEY_PREFIX + "inherited_conditions_header")
                                .withStyle(TooltipBuilder.ACCENT));
                appendRichConditionTree(spec, path.inheritedConditions(), 2, List.of(!inheritedLast));
            }
            appendRichConditionTree(spec, path.entryConditions(), 1, List.of());
        }
        for (Component source : sources) {
            spec.line(source, UiAtlas.icon(UiAtlas.Icon.CHILD_BADGE), 0);
        }
        if (!functions.isEmpty()) {
            spec.line(Component.translatable(KEY_PREFIX + "function.rules_header")
                    .withStyle(TooltipBuilder.TITLE));
            appendRichFunctionChain(spec, functions, 1, List.of(), 0);
        }
    }

    private static void appendRichPathDetails(JournalTooltipSpec.Builder spec, LootAcquisitionPath path,
                                              List<Boolean> ancestorContinues) {
        int depth = 2;
        List<Component> sources = collectSources(path);
        List<LootFunctionInfo> functions = displayFunctions(path.functions(), 0);
        boolean hasFunctions = !functions.isEmpty();
        boolean conditionsLast = sources.isEmpty() && !hasFunctions;
        if (path.hasConditions()) {
            spec.treeRow(depth, conditionsLast, ancestorContinues, null,
                    Component.translatable(KEY_PREFIX + "conditions_header").withStyle(TooltipBuilder.ACCENT));
            List<Boolean> condAncestors = appendAncestor(ancestorContinues, !conditionsLast);
            if (!path.inheritedConditions().isEmpty()) {
                boolean inheritedLast = path.entryConditions().isEmpty();
                spec.treeRow(depth + 1, inheritedLast, condAncestors, null,
                        Component.translatable(KEY_PREFIX + "inherited_conditions_header")
                                .withStyle(TooltipBuilder.ACCENT));
                appendRichConditionTree(spec, path.inheritedConditions(), depth + 2,
                        appendAncestor(condAncestors, !inheritedLast));
            }
            appendRichConditionTree(spec, path.entryConditions(), depth + 1, condAncestors);
        } else if (!hasFunctions && sources.isEmpty()) {
            spec.treeRow(depth, true, ancestorContinues, UiAtlas.icon(UiAtlas.Icon.ENABLED),
                    Component.translatable(KEY_PREFIX + "acquisition_path_conditions_unconditional")
                            .withStyle(TooltipBuilder.POSITIVE));
        }
        for (int index = 0; index < sources.size(); index++) {
            boolean isLast = index == sources.size() - 1 && !hasFunctions;
            spec.treeRow(depth, isLast, ancestorContinues, UiAtlas.icon(UiAtlas.Icon.CHILD_BADGE), sources.get(index));
        }
        if (hasFunctions) {
            spec.treeRow(depth, true, ancestorContinues, null,
                    Component.translatable(KEY_PREFIX + "function.rules_header").withStyle(TooltipBuilder.ACCENT));
            appendRichFunctionChain(spec, functions, depth + 1, appendAncestor(ancestorContinues, false), 0);
        }
    }

    private static List<Component> collectSources(LootAcquisitionPath path) {
        List<Component> sources = new ArrayList<>();
        if (path.sourceChildTable() != null
                && ArchaeologyJournalClientState.getCatalog().containsKey(path.sourceChildTable())) {
            Component childTableName = ClientLootTableLanguageStore.displayName(path.sourceChildTable());
            sources.add(Component.translatable(KEY_PREFIX + "from_child_table", childTableName)
                    .withStyle(TooltipBuilder.ACCENT, ChatFormatting.ITALIC));
        }
        if (path.sourceItemTag() != null) {
            sources.add(Component.translatable(KEY_PREFIX + "from_item_tag", localizedTagName(path.sourceItemTag()))
                    .withStyle(TooltipBuilder.ACCENT, ChatFormatting.ITALIC));
        }
        return sources;
    }

    private static void appendRichConditionTree(JournalTooltipSpec.Builder spec,
                                                List<? extends ConditionPresentation> rawConditions,
                                                int depth, List<Boolean> ancestorContinues) {
        List<ConditionPresentation> conditions = ProbabilityFormat.flattenGenericWrappers(rawConditions);
        for (int i = 0; i < conditions.size(); i++) {
            ConditionPresentation info = conditions.get(i);
            boolean isLast = i == conditions.size() - 1;
            MutableComponent text = info.description().copy().withStyle(getConditionColor(info));
            if (isFidelityIncomplete(info)) {
                text.withStyle(ChatFormatting.ITALIC);
            }
            spec.treeRow(depth, isLast, ancestorContinues, conditionIcon(info), text);
            if (!info.displayChildren().isEmpty()) {
                appendRichConditionTree(spec, info.displayChildren(), depth + 1,
                        appendAncestor(ancestorContinues, !isLast));
            }
        }
    }

    private static void appendRichFunctionChain(JournalTooltipSpec.Builder spec, List<LootFunctionInfo> chain,
                                                int depth, List<Boolean> ancestorContinues, int recursionDepth) {
        chain = displayFunctions(chain, recursionDepth);
        if (chain.isEmpty()) {
            return;
        }
        if (recursionDepth >= MAX_FUNCTION_RULE_DEPTH) {
            spec.treeRow(depth, true, ancestorContinues, UiAtlas.icon(UiAtlas.Icon.HELP),
                    Component.translatable(KEY_PREFIX + "function.rules_nested_truncated", MAX_FUNCTION_RULE_DEPTH)
                            .withStyle(TooltipBuilder.LABEL));
            return;
        }
        List<List<Component>> rendered = new ArrayList<>(chain.size());
        for (LootFunctionInfo info : chain) {
            rendered.add(renderFunctionPlainSignature(info, recursionDepth));
        }
        int index = 0;
        while (index < chain.size()) {
            List<Component> first = rendered.get(index);
            int run = 1;
            while (index + run < chain.size() && sameLines(first, rendered.get(index + run))) {
                run++;
            }
            boolean isLast = index + run == chain.size();
            LootFunctionInfo info = chain.get(index);
            MutableComponent head = info.description().copy().withStyle(TooltipBuilder.BODY);
            if (run > 1) {
                head.append(Component.literal(" ").append(Component.translatable(
                                KEY_PREFIX + "function.rules_repeat", run - 1))
                        .withStyle(TooltipBuilder.LABEL));
            }
            spec.treeRow(depth, isLast, ancestorContinues, UiAtlas.icon(UiAtlas.Icon.BRANCH_ARROW), head);

            boolean truncated = info.metadata().containsKey(LootFunctionInfo.METADATA_TRUNCATED);
            List<Boolean> childAncestors = appendAncestor(ancestorContinues, !isLast);
            if (info.hasConditions()) {
                boolean hasFollowing = !info.children().isEmpty() || truncated;
                spec.treeRow(depth + 1, !hasFollowing, childAncestors, null,
                        Component.translatable(KEY_PREFIX + "function.conditions_header")
                                .withStyle(TooltipBuilder.ACCENT));
                appendRichConditionTree(spec, info.conditions(), depth + 2,
                        appendAncestor(childAncestors, hasFollowing));
            }
            if (!info.children().isEmpty()) {
                appendRichFunctionChain(spec, info.children(), depth + 1, childAncestors, recursionDepth + 1);
            }
            if (truncated) {
                spec.treeRow(depth + 1, true, childAncestors, UiAtlas.icon(UiAtlas.Icon.HELP),
                        Component.translatable(KEY_PREFIX + "function.rules_truncated")
                                .withStyle(TooltipBuilder.LABEL));
            }
            index += run;
        }
    }

    private static List<Component> renderFunctionPlainSignature(LootFunctionInfo info, int depth) {
        List<Component> lines = new ArrayList<>();
        lines.add(info.description());
        boolean truncated = info.metadata().containsKey(LootFunctionInfo.METADATA_TRUNCATED);
        if (info.hasConditions()) {
            lines.add(Component.translatable(KEY_PREFIX + "function.conditions_header"));
            for (ConditionPresentation condition : ProbabilityFormat.flattenGenericWrappers(info.conditions())) {
                lines.add(condition.description());
            }
        }
        if (!info.children().isEmpty() && depth + 1 < MAX_FUNCTION_RULE_DEPTH) {
            for (LootFunctionInfo child : displayFunctions(info.children(), depth + 1)) {
                lines.addAll(renderFunctionPlainSignature(child, depth + 1));
            }
        }
        if (truncated) {
            lines.add(Component.translatable(KEY_PREFIX + "function.rules_truncated"));
        }
        return lines;
    }

    private static List<LootFunctionInfo> displayFunctions(List<LootFunctionInfo> functions, int depth) {
        List<LootFunctionInfo> result = new ArrayList<>();
        appendDisplayFunctions(functions, depth, result);
        return result;
    }

    private static void appendDisplayFunctions(List<LootFunctionInfo> functions, int depth,
                                              List<LootFunctionInfo> result) {
        for (LootFunctionInfo info : functions) {
            if (info.semantics().displayNoOp()) {
                continue;
            }
            boolean transparent = "minecraft".equals(info.functionType().getNamespace())
                    && ("sequence".equals(info.functionType().getPath())
                    || "reference".equals(info.functionType().getPath()))
                    && !info.hasConditions() && !info.children().isEmpty()
                    && info.fidelity() == FunctionFidelity.FULL
                    && !info.metadata().containsKey(LootFunctionInfo.METADATA_TRUNCATED);
            if (transparent && depth < MAX_FUNCTION_RULE_DEPTH) {
                appendDisplayFunctions(info.children(), depth + 1, result);
            } else {
                result.add(info);
            }
        }
    }

    private static boolean sameLines(List<Component> first, List<Component> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!first.get(index).getString().equals(second.get(index).getString())) {
                return false;
            }
        }
        return true;
    }

    private static List<Boolean> appendAncestor(List<Boolean> current, boolean continues) {
        List<Boolean> next = new ArrayList<>(current.size() + 1);
        next.addAll(current);
        next.add(continues);
        return List.copyOf(next);
    }

    private static boolean isFidelityIncomplete(ConditionPresentation info) {
        return info.metadata().containsKey(LootConditionHandlers.FIDELITY_METADATA_KEY);
    }

    private static ChatFormatting getConditionColor(ConditionPresentation info) {
        if (isFidelityUnreadable(info)) {
            return TooltipBuilder.CONDITION_UNREADABLE;
        }
        LootConditionHandler handler = LootConditionHandlers.get(info.conditionType());
        if (handler == null) {
            return TooltipBuilder.CONDITION_UNREADABLE;
        }
        return switch (handler.uncertaintyLevel()) {
            case NONE -> TooltipBuilder.CONDITION_STATIC;
            case PROBABILISTIC -> TooltipBuilder.CONDITION_PROBABILISTIC;
            case RUNTIME -> TooltipBuilder.CONDITION_RUNTIME;
        };
    }

    private static boolean isFidelityUnreadable(ConditionPresentation info) {
        return LootConditionHandlers.FIDELITY_UNREADABLE.equals(
                info.metadata().get(LootConditionHandlers.FIDELITY_METADATA_KEY));
    }
}
