package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.text.TooltipBuilder;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.UnknownReason;
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

    // 数值格式化集中在 ProbabilityFormat，tooltip 仅负责标签和顺序。
    private static final String KEY_PROBABILITY = "screen.unsuspiciousblock.archaeology_journal.probability";
    private static final String KEY_PROBABILITY_MIN =
            "screen.unsuspiciousblock.archaeology_journal.probability_minimum";
    private static final String KEY_PROBABILITY_MAX =
            "screen.unsuspiciousblock.archaeology_journal.probability_maximum";

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
        // 标题取目录下发的展示名：签名预览栈会丢掉函数产生的自定义名称（例如熔炼后再改名），
        // 直接用它当标题会出现"格子上叫 A、tooltip 里叫 B"的错位；只有缺展示名时才退回预览栈名。
        Component title = data.displayName() != null ? data.displayName() : data.stack().getHoverName();
        lines.add(title.copy().withStyle(TooltipBuilder.BODY));
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

        // 具体效果已在路径里说明，不再追加附魔、近似等内部签名标记；无签名调用方保留自定义提示。
        if (data.signature() == null && data.hint() != null) {
            lines.add(data.hint().copy().withStyle(TooltipBuilder.HINT, ChatFormatting.ITALIC));
        }

        // 外部注入标记
        if (data.injected()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.injected_loot")
                    .copy().withStyle(TooltipBuilder.ACCENT, ChatFormatting.ITALIC));
        }

        appendAcquisitionPaths(lines, data.acquisitionPaths());
        // 执行捕获继续用于诊断数据，普通 tooltip 只呈现获取物品所需的规则。

        return lines;
    }

    // 逐节点保留条件与执行顺序，连续相同的展示内容折叠，避免撑满屏幕。
    private static void appendFunctionChain(List<Component> lines, List<LootFunctionInfo> chain,
                                            String prefix, int depth) {
        chain = displayFunctions(chain, depth);
        if (chain.isEmpty()) {
            return;
        }
        // 递归必须有界：到达深度上限只报一行省略提示，既不继续深入也不会无限递归
        if (depth >= MAX_FUNCTION_RULE_DEPTH) {
            lines.add(Component.literal(prefix + "└─ ").append(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.function.rules_nested_truncated",
                    MAX_FUNCTION_RULE_DEPTH)).withStyle(TooltipBuilder.LABEL));
            return;
        }
        List<List<Component>> rendered = new ArrayList<>(chain.size());
        for (LootFunctionInfo info : chain) {
            // 比较不带当前层树枝的内容，否则末尾节点会因树枝不同而无法折叠。
            rendered.add(renderFunctionNode(info, depth));
        }
        int index = 0;
        while (index < chain.size()) {
            List<Component> first = rendered.get(index);
            int run = 1;
            while (index + run < chain.size() && sameLines(first, rendered.get(index + run))) {
                run++;
            }
            boolean isLast = index + run == chain.size();
            for (int lineIndex = 0; lineIndex < first.size(); lineIndex++) {
                String branch = lineIndex == 0
                        ? (isLast ? "└─ " : "├─ ") : (isLast ? "   " : "│  ");
                lines.add(Component.literal(prefix + branch).withStyle(TooltipBuilder.HINT)
                        .append(first.get(lineIndex)));
            }
            if (run > 1) {
                lines.add(Component.literal(prefix + (isLast ? "   " : "│  ")).append(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.function.rules_repeat", run - 1))
                        .withStyle(TooltipBuilder.LABEL));
            }
            index += run;
        }
    }

    // 单个函数节点的渲染结果（描述 + 自身条件树 + 内层函数 + 截断提示），供折叠比较与最终输出共用
    private static List<Component> renderFunctionNode(LootFunctionInfo info, int depth) {
        List<Component> nodeLines = new ArrayList<>();
        nodeLines.add(info.description().copy().withStyle(TooltipBuilder.HINT));
        boolean truncated = info.metadata().containsKey(LootFunctionInfo.METADATA_TRUNCATED);
        if (info.hasConditions()) {
            boolean hasFollowingNodes = !info.children().isEmpty() || truncated;
            appendTreeLabel(nodeLines, "", !hasFollowingNodes,
                    "screen.unsuspiciousblock.archaeology_journal.function.conditions_header");
            appendConditionTree(nodeLines, info.conditions(), hasFollowingNodes ? "│  " : "   ");
        }
        if (!info.children().isEmpty()) {
            appendFunctionChain(nodeLines, info.children(), "", depth + 1);
        }
        if (truncated) {
            nodeLines.add(Component.literal("└─ ").append(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.function.rules_truncated"))
                    .withStyle(TooltipBuilder.LABEL));
        }
        return nodeLines;
    }

    // 纯顺序包装与已展开引用不增加阅读层级；生效条件、截断和未知效果均原位保留。
    private static List<LootFunctionInfo> displayFunctions(List<LootFunctionInfo> functions, int depth) {
        List<LootFunctionInfo> result = new ArrayList<>();
        for (LootFunctionInfo info : functions) {
            if ("true".equals(info.metadata().get(LootFunctionInfo.METADATA_DISPLAY_NO_OP))) continue;
            boolean transparent = "minecraft".equals(info.functionType().getNamespace())
                    && ("sequence".equals(info.functionType().getPath())
                    || "reference".equals(info.functionType().getPath()))
                    && !info.hasConditions() && !info.children().isEmpty()
                    && info.fidelity() == com.meteorite.unsuspiciousblock.loottable.analysis.FunctionFidelity.FULL
                    && !info.metadata().containsKey(LootFunctionInfo.METADATA_TRUNCATED);
            if (transparent && depth < MAX_FUNCTION_RULE_DEPTH) {
                result.addAll(displayFunctions(info.children(), depth + 1));
            } else {
                result.add(info);
            }
        }
        return result;
    }

    // 逐行比较渲染结果：样式不影响折叠判定，比的是玩家真正读到的文本与行序
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

    // 子表入口沿用物品的概率摘要，并列出进入该子表的公共条件。
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
        // 数值口径统一成一个标签：数字都是**当前输入下的模拟结果**，玩家不需要分辨
        // "概率 / 估算概率 / 条件概率"三种叫法；不确定性由颜色（概率型=金、运行时=黄、其余=绿）
        // 以及下方的条件区、生成规则区表达。旧文案里"条件概率"会被误读成统计意义上的条件概率。
        lines.add(Component.translatable(KEY_PROBABILITY, ProbabilityFormat.formatComponent(probability))
                .copy().withStyle(probColor));
        ProbabilityBounds bounds = scenarioProbabilityBounds(data.scenarioProbabilities());
        if (bounds != null && !bounds.minimum().equals(bounds.maximum())) {
            // 网格给的是当前输入下的值，这里的区间是**其它代表场景**的范围，两者并列但不互相冒充
            lines.add(Component.translatable(KEY_PROBABILITY_MIN, bounds.minimum())
                    .copy().withStyle(probColor));
            lines.add(Component.translatable(KEY_PROBABILITY_MAX, bounds.maximum())
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
        // 没有掉落条件或生成函数时，路径区没有需要玩家阅读的规则。
        // 混合路径保留无条件分支及原始编号，否则会隐藏一种确实可用的获取方式。
        if (paths.stream().noneMatch(path -> path.hasConditions() || !displayFunctions(path.functions(), 0).isEmpty())) {
            return;
        }
        lines.add(Component.translatable(
                paths.size() > 1
                        ? "screen.unsuspiciousblock.archaeology_journal.acquisition_paths_alternatives_header"
                        : "screen.unsuspiciousblock.archaeology_journal.acquisition_paths_header")
                .copy().withStyle(TooltipBuilder.ACCENT, ChatFormatting.UNDERLINE));
        if (paths.size() == 1) {
            appendPathDetails(lines, paths.getFirst(), "");
            return;
        }
        for (int i = 0; i < paths.size(); i++) {
            boolean isLast = i == paths.size() - 1;
            appendTreeLabel(lines, "", isLast,
                    "screen.unsuspiciousblock.archaeology_journal.acquisition_path", i + 1);
            appendPathDetails(lines, paths.get(i), isLast ? "   " : "│  ");
        }
    }

    // 每条路径先列掉落条件与来源，再列该路径的生成函数，始终共用同一条路径编号。
    private static void appendPathDetails(List<Component> lines, LootAcquisitionPath path, String prefix) {
        List<Component> sources = new ArrayList<>();
        appendSourceTable(sources, path.sourceChildTable());
        appendSourceItemTag(sources, path.sourceItemTag());
        List<LootFunctionInfo> functions = displayFunctions(path.functions(), 0);
        boolean hasFunctions = !functions.isEmpty();
        boolean conditionsLast = sources.isEmpty() && !hasFunctions;
        String conditionsPrefix = prefix + (conditionsLast ? "   " : "│  ");
        if (path.hasConditions()) {
            appendTreeLabel(lines, prefix, conditionsLast,
                    "screen.unsuspiciousblock.archaeology_journal.conditions_header");
            // 继承条件继续保留归属，避免把父表的门槛误当作本条目的额外条件。
            if (!path.inheritedConditions().isEmpty()) {
                boolean inheritedLast = path.entryConditions().isEmpty();
                appendTreeLabel(lines, conditionsPrefix, inheritedLast,
                        "screen.unsuspiciousblock.archaeology_journal.inherited_conditions_header");
                appendConditionTree(lines, path.inheritedConditions(),
                        conditionsPrefix + (inheritedLast ? "   " : "│  "));
            }
            appendConditionTree(lines, path.entryConditions(), conditionsPrefix);
        } else if (!hasFunctions && sources.isEmpty()) {
            appendTreeLabel(lines, prefix, conditionsLast,
                    "screen.unsuspiciousblock.archaeology_journal.acquisition_path_conditions_unconditional");
        }
        for (int index = 0; index < sources.size(); index++) {
            boolean isLast = index == sources.size() - 1 && !hasFunctions;
            lines.add(Component.literal(prefix + (isLast ? "└─ " : "├─ "))
                    .withStyle(TooltipBuilder.HINT).append(sources.get(index)));
        }
        if (hasFunctions) {
            appendTreeLabel(lines, prefix, true,
                    "screen.unsuspiciousblock.archaeology_journal.function.rules_header");
            appendFunctionChain(lines, functions, prefix + "   ", 0);
        }
    }

    // 树枝采用次要色，分组标题采用强调色，所有文案继续使用本地化 key。
    private static void appendTreeLabel(List<Component> lines, String prefix, boolean isLast,
                                        String key, Object... args) {
        lines.add(Component.literal(prefix + (isLast ? "└─ " : "├─ "))
                .withStyle(TooltipBuilder.HINT)
                .append(Component.translatable(key, args).withStyle(TooltipBuilder.ACCENT)));
    }

    private static void appendSourceTable(List<Component> lines, ResourceLocation sourceChildTable) {
        if (sourceChildTable == null
                || !ArchaeologyJournalClientState.getCatalog().containsKey(sourceChildTable)) {
            return;
        }
        Component childTableName = LootTableNames.resolveDisplayName(sourceChildTable);
        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.from_child_table", childTableName)
                .withStyle(TooltipBuilder.ACCENT, ChatFormatting.ITALIC));
    }

    private static void appendSourceItemTag(List<Component> lines, @Nullable ResourceLocation sourceItemTag) {
        if (sourceItemTag == null) {
            return;
        }
        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.from_item_tag",
                Component.translatableWithFallback(
                        "tag.item." + sourceItemTag.getNamespace() + "."
                                + sourceItemTag.getPath().replace('/', '.'),
                        "#" + sourceItemTag))
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
