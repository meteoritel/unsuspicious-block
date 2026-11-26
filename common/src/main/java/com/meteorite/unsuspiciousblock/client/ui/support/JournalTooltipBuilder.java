package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalTooltipSpec;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionPresentation;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.FunctionFidelity;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootFunctionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ParameterKind;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ProbabilityFormat;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 考古笔记物品、子表与标签 Tooltip 构建器。
 *
 * <p>遵循 {@link TooltipBuilder} 语义色表与 Minecraft 原生分段排版：
 * 去除卡片/按钮上已可见的图标与状态标签复现，剥离 {@link PathHint} 与条件树之间的重复叙述，
 * 同时保留数量、触发率、概率、幸运影响、条件树与函数效果等关键信息。</p>
 */
public final class JournalTooltipBuilder {

    private static final int MAX_FUNCTION_RULE_DEPTH = 8;
    private static final String KEY_PREFIX = "screen.unsuspiciousblock.archaeology_journal.";

    private JournalTooltipBuilder() {
    }

    /**
     * 构建物品格 Tooltip 规格（含可选标本箱预览图）。
     */
    public static JournalTooltipSpec buildRich(ItemGridPanel.TooltipData data) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        if (!data.discovered()) {
            spec.line(Component.translatable(KEY_PREFIX + "undiscovered"), TooltipBuilder.LABEL);
            spec.line(Component.translatable(KEY_PREFIX + "tooltip.undiscovered_hint"), TooltipBuilder.HINT);
            return spec.build();
        }

        Component title = data.displayName() != null ? data.displayName() : data.stack().getHoverName();
        spec.line(title, TooltipBuilder.BODY);

        List<Component> details = new ArrayList<>();
        JournalItemDetailAppender.append(details, data.stack());
        for (Component detail : details) {
            spec.line(detail);
        }
        if (!details.isEmpty()) {
            spec.blank();
        }

        if (data.count() >= 0) {
            ChatFormatting countColor = data.count() > 0 ? TooltipBuilder.POSITIVE : TooltipBuilder.LABEL;
            spec.labelValue(KEY_PREFIX + "acquired_value",
                    Component.literal(Integer.toString(data.count())).withStyle(countColor));
        }

        if (!data.declaredChances().isEmpty()) {
            spec.labelValue(KEY_PREFIX + "probability_trigger",
                    ProbabilityFormat.formatDeclaredChances(data.declaredChances())
                            .copy().withStyle(TooltipBuilder.CONDITION_PROBABILISTIC));
        }

        appendProbabilitySummary(spec, data);

        if (data.acquisitionPaths().stream().anyMatch(LootAcquisitionPath::luckAffected)) {
            spec.line(Component.translatable(KEY_PREFIX + "probability_luck_dependent"), TooltipBuilder.ACCENT);
        }
        if (data.injected()) {
            spec.line(Component.translatable(KEY_PREFIX + "injected_loot"), TooltipBuilder.ACCENT);
        }
        if (data.signature() == null && data.hint() != null) {
            spec.line(data.hint().copy().withStyle(TooltipBuilder.HINT, ChatFormatting.ITALIC));
        }

        appendAcquisitionPaths(spec, data.acquisitionPaths());
        data.stack().getTooltipImage().ifPresent(spec::image);
        return spec.build();
    }

    /**
     * 构建子表入口 Tooltip 规格（卡片已显示名称、概率与四叶草角标，仅补充 ID、幸运文本、入口条件与操作提示）。
     */
    public static JournalTooltipSpec buildChildTableRich(Component displayName, ResourceLocation tableId,
                                                           Probability probability,
                                                           List<LootConditionInfo> conditions,
                                                           boolean luckAffected) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        spec.line(displayName, TooltipBuilder.BODY);
        if (JournalTooltipSpec.showAdvancedIds()) {
            spec.line(Component.literal(tableId.toString()), TooltipBuilder.HINT);
        }
        if (luckAffected) {
            spec.line(Component.translatable(KEY_PREFIX + "probability_luck_dependent"), TooltipBuilder.ACCENT);
        }
        if (!conditions.isEmpty()) {
            spec.blank();
            spec.section(Component.translatable(KEY_PREFIX + "parent_table_conditions_header"));
            appendConditionTree(spec, conditions, "");
        }
        spec.blank();
        spec.line(Component.translatable(KEY_PREFIX + "child_table_open"), TooltipBuilder.HINT);
        return spec.build();
    }

    /**
     * 构建物品标签合集入口 Tooltip 规格（卡片已显示标签 ID 与 X/Y 进度，仅补充本地化名称与操作提示）。
     */
    public static JournalTooltipSpec buildTagRich(ResourceLocation tagId) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        spec.line(localizedTagName(tagId), TooltipBuilder.BODY);
        if (JournalTooltipSpec.showAdvancedIds()) {
            spec.line(Component.literal("#" + tagId), TooltipBuilder.HINT);
        }
        spec.line(Component.translatable(KEY_PREFIX + "tag_group_open"), TooltipBuilder.HINT);
        return spec.build();
    }

    /**
     * 构建物品标签页「返回」格子的 Tooltip 规格。
     */
    public static JournalTooltipSpec buildTagBackRich(ResourceLocation tagId) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        spec.line(localizedTagName(tagId), TooltipBuilder.BODY);
        if (JournalTooltipSpec.showAdvancedIds()) {
            spec.line(Component.literal("#" + tagId), TooltipBuilder.HINT);
        }
        spec.line(Component.translatable(KEY_PREFIX + "tag_group_back_tooltip"), TooltipBuilder.HINT);
        return spec.build();
    }

    /**
     * 构建网格右上角「推荐求解」星标的 Tooltip 规格（去重为两行清晰说明）。
     */
    public static JournalTooltipSpec buildRecommendRich(Component targetName) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        spec.line(Component.translatable(KEY_PREFIX + "tooltip.recommend_desc", targetName), TooltipBuilder.TITLE);
        spec.line(ScenarioSession.text("recommend.branch_tooltip"), TooltipBuilder.HINT);
        return spec.build();
    }

    /**
     * 构建物品纯文本 Tooltip 行列表。
     */
    public static List<Component> build(ItemGridPanel.TooltipData data) {
        return buildRich(data).lines();
    }

    /**
     * 构建子表入口纯文本 Tooltip 行列表。
     */
    public static List<Component> buildChildTable(Component displayName, ResourceLocation tableId,
                                                   Probability probability,
                                                   List<LootConditionInfo> conditions,
                                                   boolean luckAffected) {
        return buildChildTableRich(displayName, tableId, probability, conditions, luckAffected).lines();
    }

    /**
     * 将物品标签 ID 转换为本地化名称，缺省时退回 {@code #namespace:path}。
     */
    public static Component localizedTagName(ResourceLocation tagId) {
        return Component.translatableWithFallback(
                "tag.item." + tagId.getNamespace() + "." + tagId.getPath().replace('/', '.'),
                "#" + tagId);
    }

    private static void appendProbabilitySummary(JournalTooltipSpec.Builder spec, ItemGridPanel.TooltipData data) {
        switch (data.probability()) {
            case null -> {
            }
            case Probability.Measured measured -> {
                if (measured.isZeroHit()) {
                    spec.line(ProbabilityFormat.describeZeroHit(data.simulationCount()), TooltipBuilder.LABEL);
                    spec.line(ProbabilityFormat.describeZeroHitHint(), TooltipBuilder.HINT);
                } else {
                    spec.labelValue(KEY_PREFIX + "probability",
                            ProbabilityFormat.formatComponent(measured).copy()
                                    .withStyle(numericProbabilityColor(data, measured)));
                }
            }
            case Probability.Unreachable ignored ->
                    spec.labelValue(KEY_PREFIX + "probability", Component.literal("0%").withStyle(TooltipBuilder.NEGATIVE));
            case Probability.Unknown(UnknownReason reason) ->
                    spec.line(ProbabilityFormat.describeUnknown(reason), TooltipBuilder.LABEL);
            case Probability.NeedsCondition(List<PathHint> hints) -> {
                // 下方已有获取路径条件树时，只补充条件树未表达的旋钮要求（如具体的 LuckGate 数值门槛或抽样精度），
                // 避免把 path.allConditions() 以“未满足：...”形式在顶部再复述一遍，也避免把无门槛的“幸运加成”标成未满足。
                boolean hasTree = hasAcquisitionDetails(data.acquisitionPaths());
                List<Component> extraHints = collectNeedsConditionHints(hints, hasTree);
                if (!extraHints.isEmpty()) {
                    spec.blank();
                    spec.line(Component.translatable(KEY_PREFIX
                                    + (ProbabilityFormat.hasUnresolvedConditions(hints)
                                    ? "probability_unresolved_condition_detail"
                                    : "probability_needs_condition_detail")),
                            TooltipBuilder.NEGATIVE);
                    for (Component hint : extraHints) {
                        spec.line(Component.literal("• ").withStyle(TooltipBuilder.HINT)
                                .append(hint.copy().withStyle(TooltipBuilder.BODY)));
                    }
                }
            }
        }
    }

    private static List<Component> collectNeedsConditionHints(List<PathHint> hints, boolean hasAcquisitionTree) {
        if (!hasAcquisitionTree) {
            return ProbabilityFormat.describePathHints(hints);
        }
        List<PathHint> nonConditionHints = new ArrayList<>();
        for (PathHint hint : hints) {
            if (hint instanceof PathHint.ReferencesParameter parameter
                    && ((parameter.kind() == ParameterKind.LUCK
                    && parameter.detail() != null && !parameter.detail().getString().isBlank())
                    || parameter.kind() == ParameterKind.SAMPLE_COUNT)) {
                nonConditionHints.add(hint);
            }
        }
        return ProbabilityFormat.describePathHints(nonConditionHints);
    }

    private static ChatFormatting numericProbabilityColor(ItemGridPanel.TooltipData data, Probability probability) {
        boolean probUncertain = probability.isUnknown();
        return switch (data.uncertaintyLevel()) {
            case PROBABILISTIC -> TooltipBuilder.CONDITION_PROBABILISTIC;
            case RUNTIME -> TooltipBuilder.CONDITION_RUNTIME;
            default -> probUncertain ? TooltipBuilder.LABEL : TooltipBuilder.POSITIVE;
        };
    }

    private static boolean hasAcquisitionDetails(List<LootAcquisitionPath> paths) {
        return paths.stream().anyMatch(path -> path.hasConditions()
                || path.sourceChildTable() != null
                || path.sourceItemTag() != null
                || !displayFunctions(path.functions(), 0).isEmpty());
    }

    private static void appendAcquisitionPaths(JournalTooltipSpec.Builder spec, List<LootAcquisitionPath> paths) {
        if (!hasAcquisitionDetails(paths)) {
            return;
        }
        spec.blank();
        if (paths.size() == 1) {
            appendSinglePath(spec, paths.getFirst());
            return;
        }
        spec.section(Component.translatable(KEY_PREFIX + "acquisition_paths_alternatives_header"));
        for (int i = 0; i < paths.size(); i++) {
            boolean isLast = i == paths.size() - 1;
            String branch = isLast ? "└─ " : "├─ ";
            String childPrefix = isLast ? "   " : "│  ";
            spec.line(Component.literal(branch).withStyle(TooltipBuilder.HINT)
                    .append(Component.translatable(KEY_PREFIX + "acquisition_path", i + 1)
                            .withStyle(TooltipBuilder.ACCENT)));
            appendPathDetails(spec, paths.get(i), childPrefix);
        }
    }

    private static void appendSinglePath(JournalTooltipSpec.Builder spec, LootAcquisitionPath path) {
        List<Component> sources = collectSources(path);
        List<LootFunctionInfo> functions = displayFunctions(path.functions(), 0);
        if (path.hasConditions()) {
            spec.section(Component.translatable(KEY_PREFIX + "conditions_header"));
            if (!path.inheritedConditions().isEmpty()) {
                boolean inheritedLast = path.entryConditions().isEmpty();
                spec.line(Component.literal(inheritedLast ? "└─ " : "├─ ").withStyle(TooltipBuilder.HINT)
                        .append(Component.translatable(KEY_PREFIX + "inherited_conditions_header")
                                .withStyle(TooltipBuilder.ACCENT)));
                appendConditionTree(spec, path.inheritedConditions(), inheritedLast ? "   " : "│  ");
            }
            appendConditionTree(spec, path.entryConditions(), "");
        }
        if (!sources.isEmpty()) {
            if (path.hasConditions()) {
                spec.blank();
            }
            for (Component source : sources) {
                spec.line(source);
            }
        }
        if (!functions.isEmpty()) {
            if (path.hasConditions() || !sources.isEmpty()) {
                spec.blank();
            }
            spec.section(Component.translatable(KEY_PREFIX + "function.rules_header"));
            appendFunctionChain(spec, functions, "", 0);
        }
    }

    private static void appendPathDetails(JournalTooltipSpec.Builder spec, LootAcquisitionPath path, String prefix) {
        List<Component> sources = collectSources(path);
        List<LootFunctionInfo> functions = displayFunctions(path.functions(), 0);
        boolean hasFunctions = !functions.isEmpty();
        boolean conditionsLast = sources.isEmpty() && !hasFunctions;
        if (path.hasConditions()) {
            appendTreeLabel(spec, prefix, conditionsLast, KEY_PREFIX + "conditions_header");
            String condPrefix = prefix + (conditionsLast ? "   " : "│  ");
            if (!path.inheritedConditions().isEmpty()) {
                boolean inheritedLast = path.entryConditions().isEmpty();
                appendTreeLabel(spec, condPrefix, inheritedLast, KEY_PREFIX + "inherited_conditions_header");
                appendConditionTree(spec, path.inheritedConditions(), condPrefix + (inheritedLast ? "   " : "│  "));
            }
            appendConditionTree(spec, path.entryConditions(), condPrefix);
        } else if (!hasFunctions && sources.isEmpty()) {
            spec.line(Component.literal(prefix + "└─ ").withStyle(TooltipBuilder.HINT)
                    .append(Component.translatable(KEY_PREFIX + "acquisition_path_conditions_unconditional")
                            .withStyle(TooltipBuilder.POSITIVE)));
        }
        for (int index = 0; index < sources.size(); index++) {
            boolean isLast = index == sources.size() - 1 && !hasFunctions;
            spec.line(Component.literal(prefix + (isLast ? "└─ " : "├─ ")).withStyle(TooltipBuilder.HINT)
                    .append(sources.get(index)));
        }
        if (hasFunctions) {
            appendTreeLabel(spec, prefix, true, KEY_PREFIX + "function.rules_header");
            appendFunctionChain(spec, functions, prefix + "   ", 0);
        }
    }

    private static void appendTreeLabel(JournalTooltipSpec.Builder spec, String prefix, boolean isLast,
                                        String key, Object... args) {
        spec.line(Component.literal(prefix + (isLast ? "└─ " : "├─ ")).withStyle(TooltipBuilder.HINT)
                .append(Component.translatable(key, args).withStyle(TooltipBuilder.ACCENT)));
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

    private static void appendConditionTree(JournalTooltipSpec.Builder spec,
                                            List<? extends ConditionPresentation> rawConditions,
                                            String prefix) {
        List<ConditionPresentation> conditions = ProbabilityFormat.flattenGenericWrappers(rawConditions);
        for (int i = 0; i < conditions.size(); i++) {
            ConditionPresentation info = conditions.get(i);
            boolean isLast = i == conditions.size() - 1;
            String branch = isLast ? "└─ " : "├─ ";
            String childPrefix = isLast ? "   " : "│  ";

            MutableComponent text = info.description().copy().withStyle(getConditionColor(info));
            if (isFidelityIncomplete(info)) {
                text.withStyle(ChatFormatting.ITALIC);
            }
            spec.line(Component.literal(prefix + branch).withStyle(TooltipBuilder.HINT).append(text));
            if (!info.displayChildren().isEmpty()) {
                appendConditionTree(spec, info.displayChildren(), prefix + childPrefix);
            }
        }
    }

    private static void appendFunctionChain(JournalTooltipSpec.Builder spec, List<LootFunctionInfo> chain,
                                            String prefix, int recursionDepth) {
        chain = displayFunctions(chain, recursionDepth);
        if (chain.isEmpty()) {
            return;
        }
        if (recursionDepth >= MAX_FUNCTION_RULE_DEPTH) {
            spec.line(Component.literal(prefix + "└─ ").withStyle(TooltipBuilder.HINT)
                    .append(Component.translatable(KEY_PREFIX + "function.rules_nested_truncated",
                            MAX_FUNCTION_RULE_DEPTH).withStyle(TooltipBuilder.LABEL)));
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
            String branch = isLast ? "└─ " : "├─ ";
            String childPrefix = prefix + (isLast ? "   " : "│  ");

            LootFunctionInfo info = chain.get(index);
            MutableComponent head = info.description().copy().withStyle(TooltipBuilder.BODY);
            if (run > 1) {
                head.append(Component.literal(" ").append(Component.translatable(
                                KEY_PREFIX + "function.rules_repeat", run - 1))
                        .withStyle(TooltipBuilder.LABEL));
            }
            spec.line(Component.literal(prefix + branch).withStyle(TooltipBuilder.HINT).append(head));

            boolean truncated = info.metadata().containsKey(LootFunctionInfo.METADATA_TRUNCATED);
            if (info.hasConditions()) {
                boolean hasFollowing = !info.children().isEmpty() || truncated;
                appendTreeLabel(spec, childPrefix, !hasFollowing, KEY_PREFIX + "function.conditions_header");
                appendConditionTree(spec, info.conditions(), childPrefix + (hasFollowing ? "│  " : "   "));
            }
            if (!info.children().isEmpty()) {
                appendFunctionChain(spec, info.children(), childPrefix, recursionDepth + 1);
            }
            if (truncated) {
                spec.line(Component.literal(childPrefix + "└─ ").withStyle(TooltipBuilder.HINT)
                        .append(Component.translatable(KEY_PREFIX + "function.rules_truncated")
                                .withStyle(TooltipBuilder.LABEL)));
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
