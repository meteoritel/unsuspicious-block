package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.text.TooltipBuilder;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.loottable.simulation.FunctionObservationSummary;
import com.meteorite.unsuspiciousblock.loottable.simulation.ObservedFunctionChain;
import com.meteorite.unsuspiciousblock.loottable.simulation.ProbabilityFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 考古笔记物品 tooltip 构建器，负责将 {@link ItemGridPanel.TooltipData} 转换为 tooltip 行列表。
 * <p>
 * 从 {@link com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen} 的 render 方法中提取，
 * 职责包括：
 * <ul>
 *   <li>物品名、获取数量、概率信息</li>
 *   <li>概率不确定性提示</li>
 *   <li>外部注入标记</li>
 *   <li>获取路径条件树渲染</li>
 *   <li>父表条件与子表来源标注</li>
 * </ul>
 */
public final class JournalTooltipBuilder {

    // 生成规则里包装函数（sequence / filtered / reference）的递归展开上限，
    // 与原版描述侧的 MAX_NESTED_DEPTH 取同一口径：递归必须有界，防止异常数据造成无限递归或超长 tooltip。
    private static final int MAX_FUNCTION_RULE_DEPTH = 8;

    private JournalTooltipBuilder() {
    }

    /**
     * 从 TooltipData 构建 tooltip 行列表。
     *
     * @param data tooltip 数据
     * @return tooltip 行列表，用于 {@code GuiGraphics.renderTooltip}
     */
    public static List<Component> build(ItemGridPanel.TooltipData data) {
        List<Component> lines = new ArrayList<>();

        // 发现之前只显示占位状态，随机条件与路径详情也可能泄露概率。
        if (!data.discovered()) {
            return List.of(Component.translatable("screen.unsuspiciousblock.archaeology_journal.undiscovered")
                    .withStyle(TooltipBuilder.LABEL));
        }
        lines.add(data.stack().getHoverName().copy().withStyle(TooltipBuilder.BODY));
        JournalItemDetailAppender.append(lines, data.stack());

        // 获取数量
        if (data.count() >= 0) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.acquired", data.count())
                    .copy().withStyle(TooltipBuilder.POSITIVE));
        }

        // 声明触发率与整表模拟掉落率分开展示，不把随机条件值冒充最终产出概率。
        // 网格上两者二选一（决策 44 的优先级链），tooltip 里始终并列并注明各自口径。
        if (!data.declaredChances().isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_trigger",
                    ProbabilityFormat.formatDeclaredChances(data.declaredChances()))
                    .withStyle(TooltipBuilder.CONDITION_PROBABILISTIC));
        }
        appendProbabilityLines(lines, data);

        if (data.acquisitionPaths().stream().anyMatch(LootAcquisitionPath::luckAffected)) {
            appendLuckNote(lines);
        }

        // 提示文本（近似概率等）
        if (data.hint() != null) {
            boolean probUncertain = data.probability() != null && data.probability().isUnknown();
            boolean hintIsApprox = data.hint().getString().equals(
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.item_hint.approximate").getString());
            if (!(probUncertain && hintIsApprox)) {
                // 提示文本：色表归并规则下 DARK_GREEN 归 HINT（文案规范 8.3）
                lines.add(data.hint().copy().withStyle(TooltipBuilder.HINT, ChatFormatting.ITALIC));
            }
        }

        // 外部注入标记
        if (data.injected()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.injected_loot")
                    .copy().withStyle(TooltipBuilder.ACCENT, ChatFormatting.ITALIC));
        }

        appendAcquisitionPaths(lines, data.acquisitionPaths());
        // 规则与观测分区（规划 §4.8）：静态规则回答"声明了什么"，观测回答"本输入的模拟里进过哪些执行体"。
        // 两者分开渲染，观测绝不写成"必然生效"，也不与规则拼成同一条因果链。
        appendObservedFunctions(lines, data.observedFunctions());
        appendFunctionRules(lines, data.acquisitionPaths());

        return lines;
    }

    // ==================== 函数规则与运行时观测 ====================

    // 静态"生成规则"：逐条路径列出其声明的有序函数树；每个函数单独一行，顺序即执行顺序。
    // 函数自身的条件缩进列在该函数下面——它**不是**物品的掉落条件（规划 F04）。
    // 包装函数的内层规则必须一起递归展开：只渲染顶层的话，sequence 只剩"依次执行 N 个函数"，
    // 内层规则与内层条件完全不可见（规划 §4.5.3 的有序函数树）。
    private static void appendFunctionRules(List<Component> lines, List<LootAcquisitionPath> paths) {
        List<List<LootFunctionInfo>> rulePaths = new ArrayList<>();
        for (LootAcquisitionPath path : paths) {
            if (!path.functions().isEmpty()) {
                rulePaths.add(path.functions());
            }
        }
        if (rulePaths.isEmpty()) {
            return;
        }
        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.function.rules_header")
                .copy().withStyle(TooltipBuilder.ACCENT, ChatFormatting.UNDERLINE));
        for (int index = 0; index < rulePaths.size(); index++) {
            List<LootFunctionInfo> chain = rulePaths.get(index);
            String prefix = rulePaths.size() > 1 ? "  " : "";
            if (rulePaths.size() > 1) {
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.acquisition_path", index + 1)
                        .copy().withStyle(TooltipBuilder.ACCENT));
            }
            appendFunctionChain(lines, chain, prefix, 0);
        }
    }

    // 递归渲染一层函数链：每个节点先给自己的描述（单独一行，顺序即执行顺序），
    // 再是它自身的条件树（缩进随层级递增），最后递归展开它的内层包装函数。
    private static void appendFunctionChain(List<Component> lines, List<LootFunctionInfo> chain,
                                            String prefix, int depth) {
        if (chain.isEmpty()) {
            return;
        }
        // 递归必须有界：到达深度上限只报一行省略提示，既不继续深入也不会无限递归
        if (depth >= MAX_FUNCTION_RULE_DEPTH) {
            lines.add(Component.literal(prefix).append(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.function.rules_nested_truncated",
                    MAX_FUNCTION_RULE_DEPTH)).withStyle(TooltipBuilder.LABEL));
            return;
        }
        for (LootFunctionInfo info : chain) {
            lines.add(Component.literal(prefix).append(info.description())
                    .withStyle(TooltipBuilder.HINT));
            if (info.hasConditions()) {
                appendConditionTree(lines, info.conditions(), prefix + "  ");
            }
            appendFunctionChain(lines, info.children(), prefix + "  ", depth + 1);
            if (info.metadata().containsKey(LootFunctionInfo.METADATA_TRUNCATED)) {
                lines.add(Component.literal(prefix + "  ").append(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.function.rules_truncated"))
                        .withStyle(TooltipBuilder.LABEL));
            }
        }
    }

    /**
     * 本次模拟观测到的函数链。
     * <p>
     * 多条链是**备选**（不同轮次/不同生成方式各走一条），因此逐条分行，绝不用分隔符拼成一条因果链。
     * 空链列表只说明"没观测到"，不是"没有函数"；未完整与截断各有独立提示（规划 D12）。
     */
    private static void appendObservedFunctions(List<Component> lines,
                                                @Nullable FunctionObservationSummary summary) {
        if (summary == null) {
            return;
        }
        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.function.observed_header")
                .copy().withStyle(TooltipBuilder.ACCENT, ChatFormatting.UNDERLINE));
        if (summary.unavailable()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.function.capture_unavailable")
                    .withStyle(TooltipBuilder.LABEL));
            return;
        }
        if (summary.chains().isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.function.observed_none")
                    .withStyle(TooltipBuilder.LABEL));
        } else {
            for (ObservedFunctionChain chain : summary.chains()) {
                lines.add(describeObservedChain(chain).copy().withStyle(TooltipBuilder.HINT));
            }
        }
        if (summary.incomplete()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.function.observed_incomplete")
                    .withStyle(TooltipBuilder.LABEL));
        }
        if (summary.truncated()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.function.observed_truncated")
                    .withStyle(TooltipBuilder.LABEL));
        }
        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.function.observed_note")
                .withStyle(TooltipBuilder.LABEL));
    }

    // 一条观测链按执行顺序渲染；链上的函数只有注册名可用，取 path 段即可读
    private static Component describeObservedChain(ObservedFunctionChain chain) {
        Component result = Component.empty();
        for (int index = 0; index < chain.functionTypes().size(); index++) {
            if (index > 0) {
                result = result.copy().append(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.function.chain_separator"));
            }
            result = result.copy().append(Component.literal(chain.functionTypes().get(index).getPath()));
        }
        if (!chain.contentExpanded()) {
            result = result.copy().append(Component.literal(" "))
                    .append(Component.translatable(
                            "screen.unsuspiciousblock.archaeology_journal.function.container_not_expanded"));
        }
        return result;
    }

    // 构建子表入口 tooltip；概率与物品使用相同摘要格式，并展示父表中的公共触发条件。
    public static List<Component> buildChildTable(Component displayName, ResourceLocation tableId,
                                                   Probability probability,
                                                   List<ScenarioProbability> scenarioProbabilities,
                                                   List<LootConditionInfo> conditions,
                                                   boolean luckAffected) {
        List<Component> lines = new ArrayList<>();
        lines.add(displayName.copy().withStyle(TooltipBuilder.BODY));
        lines.add(Component.literal(tableId.toString()).withStyle(TooltipBuilder.HINT));
        // 显示优先级与物品同一条链：**状态词优先于数值**。子表区间是"其它代表场景的范围"，
        // 用它顶掉「需要条件」就等于"为什么看不到数字"永远看不到；而且区间里的 0% 会与
        // 「需要条件」互相打脸（0% 读起来像"不可能"，而它只是"当前输入下进不去"）。
        if (!probability.isDisplayable()) {
            lines.add(ProbabilityFormat.formatComponent(probability).copy().withStyle(TooltipBuilder.LABEL));
        } else {
            ProbabilityBounds bounds = scenarioProbabilityBounds(scenarioProbabilities);
            if (bounds != null && !bounds.minimum().equals(bounds.maximum())) {
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.probability_minimum", bounds.minimum())
                        .withStyle(TooltipBuilder.POSITIVE));
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.probability_maximum", bounds.maximum())
                        .withStyle(TooltipBuilder.POSITIVE));
            } else {
                lines.add(ProbabilityFormat.formatComponent(probability).copy().withStyle(
                        probability.isDisplayable() ? TooltipBuilder.POSITIVE : TooltipBuilder.LABEL));
            }
        }
        if (luckAffected) {
            appendLuckNote(lines);
        }
        // 入口条件（路径共同条件 + 注入边门槛）由服务端下发；不含它时"进不去"就只剩一个状态词
        if (!conditions.isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.parent_table_conditions_header")
                    .withStyle(TooltipBuilder.ACCENT, ChatFormatting.UNDERLINE));
            appendConditionTree(lines, conditions, "");
        }
        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.child_table_open")
                .withStyle(TooltipBuilder.LABEL));
        return lines;
    }

    /**
     * 概率段落：按**展示状态的类型**分派，四种状态各有独立文案（决策 36/40/44）。
     * <ul>
     *   <li>「需要条件」：说明引用了哪些可调整的旋钮与条件——**只是陈述**，不承诺调完就能拿到；</li>
     *   <li>未知：按 {@code UnknownReason} 分述"为什么是问号"；</li>
     *   <li>零命中：报本次抽样次数并提示可提高次数，不写概率上界；</li>
     *   <li>已测量 / 静态不可达：给数值，并附其它代表场景的区间。</li>
     * </ul>
     */
    private static void appendProbabilityLines(List<Component> lines, ItemGridPanel.TooltipData data) {
        switch (data.probability()) {
            case null -> {
            }
            // 需要条件：静态信息性提示逐条列出。P0 没有可点击的「填入推荐值」——
            // 按决策 34，那必须携带整条路径联合验证过的输入，由 P2 的联合见证搜索产出。
            case Probability.NeedsCondition(List<PathHint> hints) -> {
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal."
                                + (ProbabilityFormat.hasUnresolvedConditions(hints)
                                ? "probability_unresolved_condition_detail"
                                : "probability_needs_condition_detail"))
                        .withStyle(TooltipBuilder.HINT));
                for (Component hint : ProbabilityFormat.describePathHints(hints)) {
                    lines.add(hint.copy().withStyle(TooltipBuilder.LABEL));
                }
            }
            case Probability.Unknown(UnknownReason reason) -> lines.add(
                    ProbabilityFormat.describeUnknown(reason).copy().withStyle(TooltipBuilder.LABEL));
            case Probability.Measured measured -> appendNumericProbabilityLines(lines, data, measured);
            case Probability.Unreachable unreachable -> appendNumericProbabilityLines(lines, data, unreachable);
        }
    }

    // 已测量 / 静态不可达：先给出数值，再附其它代表场景的区间
    private static void appendNumericProbabilityLines(List<Component> lines, ItemGridPanel.TooltipData data,
                                                      Probability probability) {
        if (probability.isZeroHit()) {
            lines.add(ProbabilityFormat.describeZeroHit(data.simulationCount())
                    .copy().withStyle(TooltipBuilder.LABEL));
            lines.add(ProbabilityFormat.describeZeroHitHint()
                    .copy().withStyle(TooltipBuilder.HINT));
        }
        boolean probUncertain = probability.isUnknown();
        ChatFormatting probColor = switch (data.uncertaintyLevel()) {
            // 不确定度等级复用条件树的语义别名：概率型=金、运行时=黄、其余按是否未知取灰/绿
            case PROBABILISTIC -> TooltipBuilder.CONDITION_PROBABILISTIC;
            case RUNTIME -> TooltipBuilder.CONDITION_RUNTIME;
            default -> probUncertain ? TooltipBuilder.LABEL : TooltipBuilder.POSITIVE;
        };
        ProbabilityBounds bounds = scenarioProbabilityBounds(data.scenarioProbabilities());
        if (bounds != null && !bounds.minimum().equals(bounds.maximum())) {
            // 网格给的是当前输入下的值，这里的区间是**其它代表场景**的范围，两者并列不互相冒充
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_simulated",
                    ProbabilityFormat.formatComponent(probability))
                    .copy().withStyle(probColor));
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_minimum", bounds.minimum())
                    .copy().withStyle(probColor));
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_maximum", bounds.maximum())
                    .copy().withStyle(probColor));
        } else if (data.uncertaintyLevel() == LootConditionHandler.UncertaintyLevel.PROBABILISTIC) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_estimated",
                    ProbabilityFormat.formatNumeric(probability))
                    .copy().withStyle(probColor));
        } else if (data.uncertaintyLevel() == LootConditionHandler.UncertaintyLevel.RUNTIME) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability_conditional_value",
                    ProbabilityFormat.formatNumeric(probability))
                    .copy().withStyle(probColor));
        } else {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.probability",
                    ProbabilityFormat.formatComponent(probability))
                    .copy().withStyle(probColor));
        }
    }

    // 说明该路径受幸运影响；**不再**写死一个幸运数值——幸运自 P1 起是输入的一维（基准值为 0），
    // 在 tooltip 里印一个固定数字会与玩家实际看到的那份输入不符。
    private static void appendLuckNote(List<Component> lines) {
        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.probability_luck_dependent")
                .withStyle(TooltipBuilder.HINT));
    }

    // 只聚合已测量/静态不可达的代表场景；未知、需要条件与零命中不参与区间——它们不是数值。
    private static ProbabilityBounds scenarioProbabilityBounds(List<ScenarioProbability> probabilities) {
        Probability minimum = null;
        Probability maximum = null;
        for (ScenarioProbability scenario : probabilities) {
            Probability value = scenario.probability();
            if (!value.isDisplayable()) continue;
            if (minimum == null || value.lowerBound() < minimum.lowerBound()) {
                minimum = value;
            }
            if (maximum == null || value.upperBound() > maximum.upperBound()) {
                maximum = value;
            }
        }
        return minimum == null
                ? null
                : new ProbabilityBounds(
                ProbabilityFormat.formatNumeric(minimum), ProbabilityFormat.formatNumeric(maximum));
    }

    private record ProbabilityBounds(String minimum, String maximum) {
    }

    private static void appendAcquisitionPaths(List<Component> lines, List<LootAcquisitionPath> paths) {
        if (paths.isEmpty()) {
            return;
        }
        if (paths.size() == 1) {
            appendSinglePath(lines, paths.getFirst());
            return;
        }

        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.acquisition_paths_header")
                .copy().withStyle(TooltipBuilder.ACCENT, ChatFormatting.UNDERLINE));
        for (int i = 0; i < paths.size(); i++) {
            LootAcquisitionPath path = paths.get(i);
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.acquisition_path", i + 1)
                    .copy().withStyle(TooltipBuilder.ACCENT));
            if (path.hasConditions()) {
                appendConditionTree(lines, path.allConditions(), "  ");
            } else if (path.sourceChildTable() == null) {
                lines.add(Component.literal("  ").append(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.acquisition_path_unconditional"))
                        .withStyle(TooltipBuilder.POSITIVE));
            }
            appendSourceTable(lines, path.sourceChildTable(), "  ");
            appendSourceItemTag(lines, path.sourceItemTag(), "  ");
        }
    }

    private static void appendSinglePath(List<Component> lines, LootAcquisitionPath path) {
        if (!path.entryConditions().isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.conditions_header")
                    .copy().withStyle(TooltipBuilder.ACCENT, ChatFormatting.UNDERLINE));
            appendConditionTree(lines, path.entryConditions(), "");
        }
        if (!path.inheritedConditions().isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.inherited_conditions_header")
                    .copy().withStyle(TooltipBuilder.ACCENT, ChatFormatting.UNDERLINE));
            appendConditionTree(lines, path.inheritedConditions(), "");
        }
        appendSourceTable(lines, path.sourceChildTable(), "");
        appendSourceItemTag(lines, path.sourceItemTag(), "");
    }

    private static void appendSourceTable(List<Component> lines, ResourceLocation sourceChildTable, String prefix) {
        if (sourceChildTable == null
                || !ArchaeologyJournalClientState.getCatalog().containsKey(sourceChildTable)) {
            return;
        }
        Component childTableName = LootTableNames.resolveDisplayName(sourceChildTable);
        lines.add(Component.literal(prefix).append(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.from_child_table", childTableName))
                .withStyle(TooltipBuilder.ACCENT, ChatFormatting.ITALIC));
    }

    private static void appendSourceItemTag(List<Component> lines,
                                            @org.jetbrains.annotations.Nullable ResourceLocation sourceItemTag,
                                            String prefix) {
        if (sourceItemTag == null) {
            return;
        }
        lines.add(Component.literal(prefix).append(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.from_item_tag",
                Component.literal(sourceItemTag.toString())))
                .withStyle(TooltipBuilder.ACCENT, ChatFormatting.ITALIC));
    }

    // 递归渲染条件树到 tooltip 行列表；颜色表示不确定性等级，斜体表示描述保真度
    private static void appendConditionTree(List<Component> lines, List<LootConditionInfo> conditions, String prefix) {
        for (int i = 0; i < conditions.size(); i++) {
            LootConditionInfo info = conditions.get(i);
            boolean isLast = i == conditions.size() - 1;
            String branch = isLast ? "└─ " : "├─ ";
            String childPrefix = isLast ? "   " : "│  ";

            MutableComponent text = info.description().copy().withStyle(getConditionColor(info));
            if (isFidelityIncomplete(info)) {
                text.withStyle(ChatFormatting.ITALIC);
            }
            // 树枝前缀不再是 DARK_GRAY：它在深色 tooltip 背景上几乎不可见，而条件树正是"为什么没数字"的依据
            lines.add(Component.literal(prefix + branch).withStyle(TooltipBuilder.HINT).append(text));

            if (!info.children().isEmpty()) {
                appendConditionTree(lines, info.children(), prefix + childPrefix);
            }
        }
    }

    // 保真度未知（"有保留"/"未读到"）时用斜体；缺失该标记即代表描述完整
    private static boolean isFidelityIncomplete(LootConditionInfo info) {
        return info.metadata().containsKey(LootConditionHandlers.FIDELITY_METADATA_KEY);
    }

    // 根据条件是否被识别、以及其不确定性等级返回对应颜色
    private static ChatFormatting getConditionColor(LootConditionInfo info) {
        if (isFidelityUnreadable(info)) {
            return TooltipBuilder.CONDITION_UNREADABLE;
        }
        var handler = LootConditionHandlers.get(info.conditionType());
        if (handler == null) return TooltipBuilder.CONDITION_UNREADABLE;
        return switch (handler.uncertaintyLevel()) {
            case NONE -> TooltipBuilder.CONDITION_STATIC;
            case PROBABILISTIC -> TooltipBuilder.CONDITION_PROBABILISTIC;
            case RUNTIME -> TooltipBuilder.CONDITION_RUNTIME;
        };
    }

    private static boolean isFidelityUnreadable(LootConditionInfo info) {
        return LootConditionHandlers.FIDELITY_UNREADABLE.equals(
                info.metadata().get(LootConditionHandlers.FIDELITY_METADATA_KEY));
    }
}
