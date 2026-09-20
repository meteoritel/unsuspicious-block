package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;
import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.loottable.simulation.ProbabilityFormat;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

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

        // 未发现物品只展示状态与获取条件，避免提前泄露物品身份
        if (data.discovered()) {
            lines.add(data.stack().getHoverName().copy().withStyle(ChatFormatting.WHITE));
            JournalItemDetailAppender.append(lines, data.stack());
        } else {
            lines.add(Component.translatable("screen.unsuspiciousblock.archaeology_journal.undiscovered")
                    .copy().withStyle(ChatFormatting.GRAY));
        }

        // 获取数量
        if (data.discovered() && data.count() >= 0) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.acquired", data.count())
                    .copy().withStyle(ChatFormatting.GREEN));
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
        if (data.discovered() && data.hint() != null) {
            boolean probUncertain = data.probability() != null && data.probability().isUnknown();
            boolean hintIsApprox = data.hint().getString().equals(
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.item_hint.approximate").getString());
            if (!(probUncertain && hintIsApprox)) {
                lines.add(data.hint().copy().withStyle(ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC));
            }
        }

        // 外部注入标记
        if (data.discovered() && data.injected()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.injected_loot")
                    .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
        }

        appendAcquisitionPaths(lines, data.acquisitionPaths());

        return lines;
    }

    // 构建子表入口 tooltip；概率与物品使用相同摘要格式，并展示父表中的公共触发条件。
    public static List<Component> buildChildTable(Component displayName, ResourceLocation tableId,
                                                   Probability probability,
                                                   List<ScenarioProbability> scenarioProbabilities,
                                                   List<LootConditionInfo> conditions,
                                                   boolean luckAffected) {
        List<Component> lines = new ArrayList<>();
        lines.add(displayName.copy().withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal(tableId.toString()).withStyle(TooltipBuilder.HINT));
        // 显示优先级与物品同一条链：**状态词优先于数值**。子表区间是"其它代表场景的范围"，
        // 用它顶掉「需要条件」就等于"为什么看不到数字"永远看不到；而且区间里的 0% 会与
        // 「需要条件」互相打脸（0% 读起来像"不可能"，而它只是"当前输入下进不去"）。
        if (!probability.isDisplayable()) {
            lines.add(ProbabilityFormat.formatComponent(probability).copy().withStyle(ChatFormatting.GRAY));
        } else {
            ProbabilityBounds bounds = scenarioProbabilityBounds(scenarioProbabilities);
            if (bounds != null && !bounds.minimum().equals(bounds.maximum())) {
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.probability_minimum", bounds.minimum())
                        .withStyle(ChatFormatting.GREEN));
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.probability_maximum", bounds.maximum())
                        .withStyle(ChatFormatting.GREEN));
            } else {
                lines.add(ProbabilityFormat.formatComponent(probability).copy().withStyle(
                        probability.isDisplayable() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            }
        }
        if (luckAffected) {
            appendLuckNote(lines);
        }
        // 入口条件（路径共同条件 + 注入边门槛）由服务端下发；不含它时"进不去"就只剩一个状态词
        if (!conditions.isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.parent_table_conditions_header")
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
            appendConditionTree(lines, conditions, "");
        }
        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.child_table_open")
                .withStyle(ChatFormatting.GRAY));
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
                        "screen.unsuspiciousblock.archaeology_journal.probability_needs_condition_detail")
                        .withStyle(TooltipBuilder.HINT));
                for (Component hint : ProbabilityFormat.describePathHints(hints)) {
                    lines.add(hint.copy().withStyle(TooltipBuilder.LABEL));
                }
            }
            case Probability.Unknown(UnknownReason reason) -> lines.add(
                    ProbabilityFormat.describeUnknown(reason).copy().withStyle(ChatFormatting.GRAY));
            case Probability.Measured measured -> appendNumericProbabilityLines(lines, data, measured);
            case Probability.Unreachable unreachable -> appendNumericProbabilityLines(lines, data, unreachable);
        }
    }

    // 已测量 / 静态不可达：先给出数值，再附其它代表场景的区间
    private static void appendNumericProbabilityLines(List<Component> lines, ItemGridPanel.TooltipData data,
                                                      Probability probability) {
        if (probability.isZeroHit()) {
            lines.add(ProbabilityFormat.describeZeroHit(data.simulationCount())
                    .copy().withStyle(ChatFormatting.GRAY));
            lines.add(ProbabilityFormat.describeZeroHitHint()
                    .copy().withStyle(TooltipBuilder.HINT));
        }
        boolean probUncertain = probability.isUnknown();
        ChatFormatting probColor = switch (data.uncertaintyLevel()) {
            case PROBABILISTIC -> ChatFormatting.GOLD;
            case RUNTIME -> ChatFormatting.YELLOW;
            default -> probUncertain ? ChatFormatting.GRAY : ChatFormatting.GREEN;
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

    // 说明模拟基准，不将幸运敏感误写为必须拥有幸运效果才可获得。
    private static void appendLuckNote(List<Component> lines) {
        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.probability_luck_dependent",
                Float.toString(SimulationProfile.CATALOG_LUCK)).withStyle(TooltipBuilder.HINT));
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
                .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
        for (int i = 0; i < paths.size(); i++) {
            LootAcquisitionPath path = paths.get(i);
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.acquisition_path", i + 1)
                    .copy().withStyle(ChatFormatting.AQUA));
            if (path.hasConditions()) {
                appendConditionTree(lines, path.allConditions(), "  ");
            } else if (path.sourceChildTable() == null) {
                lines.add(Component.literal("  ").append(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.acquisition_path_unconditional"))
                        .withStyle(ChatFormatting.GREEN));
            }
            appendSourceTable(lines, path.sourceChildTable(), "  ");
            appendSourceItemTag(lines, path.sourceItemTag(), "  ");
        }
    }

    private static void appendSinglePath(List<Component> lines, LootAcquisitionPath path) {
        if (!path.entryConditions().isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.conditions_header")
                    .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
            appendConditionTree(lines, path.entryConditions(), "");
        }
        if (!path.inheritedConditions().isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.inherited_conditions_header")
                    .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
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
                .withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
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
                .withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
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
            lines.add(Component.literal(prefix + branch).withStyle(ChatFormatting.DARK_GRAY).append(text));

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
