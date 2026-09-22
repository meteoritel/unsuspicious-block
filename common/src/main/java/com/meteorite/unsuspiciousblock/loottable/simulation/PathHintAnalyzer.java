package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.ParameterKind;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.UnknownReason;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从获取路径的条件树派生**静态信息性提示**，并据此把测量值提升为「需要条件」。
 * <p>
 * 只做静态判定：它回答的是"这条路径的条件树里出现没出现过可调整的旋钮或场景控制条件"，
 * 答案就在编译产物里，不需要任何搜索。**它不承诺可达成**——按决策 34，可点击的推荐必须由
 * 联合见证搜索产出，搜不到就只渲染本类给出的纯文本。
 * <p>
 * 本类同时承担"测量值 → 展示值"的派生（见 {@link #deriveDisplay}）。派生规则刻意保持保守：
 * <ol>
 *   <li>测到了非零值就报测量值——这是玩家最想要的那个事实；</li>
 *   <li>静态不可达照实报 {@code 0%}（全部路径都被逐路径幸运门槛证明不可达时）；</li>
 *   <li>表级失败（规则未解析、计算失败、尚未请求、已淘汰）原样透传，不被"需要条件"覆盖；</li>
 *   <li>零命中且路径引用了旋钮 → 「需要条件」，并逐条列出引用目标（P1 起带**逐路径幸运门槛**的
 *       具体数值，可直接照着填）；</li>
 *   <li>零命中且路径不引用任何旋钮 → 「未命中」——这是老实的抽样陈述。</li>
 * </ol>
 * 子表入口走同构的 {@link #deriveEntryDisplay}：入口没有逐路径幸运门槛，但"零命中 + 有可陈述的
 * 入口门槛 → 需要条件"这条规则一致。
 */
public final class PathHintAnalyzer {
    private static final String KEY_PREFIX = "screen.unsuspiciousblock.archaeology_journal.";
    private static final String KEY_LUCK_AT_LEAST = KEY_PREFIX + "path_hint.luck_at_least";
    private static final String KEY_LUCK_RANGE_LIMITED = KEY_PREFIX + "path_hint.luck_range_limited";
    private static final String KEY_LUCK_BONUS_ROLLS = KEY_PREFIX + "path_hint.luck_bonus_rolls";
    private static final ResourceLocation MATCH_TOOL =
            ResourceLocation.withDefaultNamespace("match_tool");
    private static final ResourceLocation ALL_OF = ResourceLocation.withDefaultNamespace("all_of");
    private static final ResourceLocation ANY_OF = ResourceLocation.withDefaultNamespace("any_of");
    private static final ResourceLocation INVERTED = ResourceLocation.withDefaultNamespace("inverted");
    private static final ResourceLocation TABLE_BONUS =
            ResourceLocation.withDefaultNamespace("table_bonus");
    private static final ResourceLocation ENCHANTMENT_ACTIVE_CHECK =
            ResourceLocation.withDefaultNamespace("enchantment_active_check");
    private static final ResourceLocation RANDOM_CHANCE_WITH_ENCHANTED_BONUS =
            ResourceLocation.withDefaultNamespace("random_chance_with_enchanted_bonus");

    private PathHintAnalyzer() {
    }

    /** 把基准输入的测量值派生为展示值。 */
    public static Probability deriveDisplay(Probability baseline, List<LootAcquisitionPath> paths) {
        // 静态可证明的不可达优先（规划 §4.3 的静态判据：有效权重恒为 0）。只有**全部**路径都
        // 在任何可表示的幸运下拿不到时才成立；任一条路径可达就不能写 0%。
        if (isStaticallyUnreachable(paths)) {
            return Probability.unreachable();
        }
        if (baseline instanceof Probability.NeedsCondition) {
            return baseline;
        }
        if (baseline instanceof Probability.Measured measured && measured.lower() > 0.0) {
            return baseline;
        }
        if (baseline instanceof Probability.Unreachable) {
            return baseline;
        }
        // 表级失败优先于"需要条件"：规则没解析出来时，说"需要某个条件"同样是编造
        if (baseline instanceof Probability.Unknown(UnknownReason reason)
                && reason != UnknownReason.UNCOVERED) {
            return baseline;
        }
        List<PathHint> hints = hintsFor(paths);
        if (!hints.isEmpty()) {
            return Probability.needsCondition(hints);
        }
        // 适用、抽样零命中、且路径不引用任何旋钮：这是真正的抽样结论
        return baseline instanceof Probability.Measured ? baseline : Probability.uncovered();
    }

    // 该条目的全部获取路径是否都被逐路径幸运门槛证明不可达；无静态路径的动态条目不算
    private static boolean isStaticallyUnreachable(List<LootAcquisitionPath> paths) {
        if (paths.isEmpty()) {
            return false;
        }
        for (LootAcquisitionPath path : paths) {
            if (!path.luckImpossible()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 某条路径在当前场景下不可用时该展示什么。
     * <p>
     * D2 的拆分点：**不是**"静态不可达"，而是"这个场景的布尔赋值下不成立"。引用了可调整的
     * 旋钮或场景条件时展示为「需要条件」并列出引用目标，否则如实报"未覆盖"。
     * 模拟与存档恢复两条路径共用这一份判定，避免两处各写一套而漂移。
     */
    public static Probability inapplicableScenarioDisplay(List<PathHint> hints) {
        return hints.isEmpty() ? Probability.uncovered() : Probability.needsCondition(hints);
    }

    /**
     * 子表入口的展示派生——与 {@link #deriveDisplay} 同构，区别只是入口没有逐路径幸运门槛。
     * <p>
     * 为什么零命中要报「需要条件」：子表入口被入口门槛（附魔、群系等）挡住时，"抽样零命中"是
     * **错误归因**——它把一个确定的原因说成了运气，玩家看不到自己缺什么。反过来说，把这一项
     * 与物品用同一条规则派生，也让"调好条件后它变成数字"这件事不需要另一套逻辑。
     */
    public static Probability deriveEntryDisplay(Probability measured, List<PathHint> hints) {
        if (measured instanceof Probability.Measured value && value.lower() > 0.0) {
            return measured;
        }
        if (measured instanceof Probability.Unreachable) {
            return measured;
        }
        // 表级失败（规则未解析、计算失败、尚未请求、已淘汰）原样透传：那些时候说"需要某个条件"
        // 同样是编造
        if (measured instanceof Probability.Unknown(UnknownReason reason)
                && reason != UnknownReason.UNCOVERED) {
            return measured;
        }
        return hints.isEmpty() ? measured : Probability.needsCondition(hints);
    }

    /**
     * 收集该条目全部获取路径引用到的旋钮与场景条件。
     * 参数提示按 {@link ParameterKind} 的声明顺序排在前，场景条件合并为一条排在最后——
     * 先读"要调什么"、再读"要满足什么"。
     * <p>
     * 幸运提示优先给出**逐路径幸运门槛**的具体数值（"需要幸运 ≥ 0.34"）：那是玩家能直接照着填的值。
     * 门槛为"区间受限"或不可数时降级为对应陈述；只有连门槛都算不出来（路径只带
     * {@code luckAffected} 标记）时才退回无具体目标的那条文案。
     */
    public static List<PathHint> hintsFor(List<LootAcquisitionPath> paths) {
        Map<ParameterKind, Map<String, Component>> parameterDetails = new EnumMap<>(ParameterKind.class);
        LinkedHashMap<String, LootConditionInfo> scenarioConditions = new LinkedHashMap<>();
        LinkedHashMap<String, LootConditionInfo> unresolvedConditions = new LinkedHashMap<>();
        LinkedHashMap<String, Component> luckDetails = new LinkedHashMap<>();
        boolean luckReferenced = false;

        for (LootAcquisitionPath path : paths) {
            luckReferenced |= path.luckAffected();
            Component luckDetail = luckDetail(path.luckGate());
            if (luckDetail != null) {
                luckDetails.putIfAbsent(luckDetail.getString(), luckDetail);
            }
            for (LootConditionInfo condition : path.allConditions()) {
                collect(condition, parameterDetails, scenarioConditions, unresolvedConditions);
            }
        }
        return assembleHints(luckDetails, luckReferenced, parameterDetails, scenarioConditions,
                unresolvedConditions);
    }

    /**
     * 把一组**不在任何获取路径上**的条件翻译成同样的提示——目前只有子表入口用它。
     * <p>
     * 注入边声明的入口门槛不写在任何 JSON 里，因此也不在任何 {@link LootAcquisitionPath} 上；
     * 但它的分类规则与物品完全共用（{@link #collect}），所以同一个条件在物品 tooltip 与子表入口
     * tooltip 上说同一句话，不会出现"物品说要附魔、子表入口却说别的东西"。
     */
    public static List<PathHint> hintsForConditions(List<LootConditionInfo> conditions) {
        Map<ParameterKind, Map<String, Component>> parameterDetails = new EnumMap<>(ParameterKind.class);
        LinkedHashMap<String, LootConditionInfo> scenarioConditions = new LinkedHashMap<>();
        LinkedHashMap<String, LootConditionInfo> unresolvedConditions = new LinkedHashMap<>();
        for (LootConditionInfo condition : conditions) {
            collect(condition, parameterDetails, scenarioConditions, unresolvedConditions);
        }
        return assembleHints(new LinkedHashMap<>(), false, parameterDetails, scenarioConditions,
                unresolvedConditions);
    }

    private static List<PathHint> assembleHints(LinkedHashMap<String, Component> luckDetails,
                                                boolean luckReferenced,
                                                Map<ParameterKind, Map<String, Component>> parameterDetails,
                                                LinkedHashMap<String, LootConditionInfo> scenarioConditions,
                                                LinkedHashMap<String, LootConditionInfo> unresolvedConditions) {
        List<PathHint> hints = new ArrayList<>();
        for (Component detail : luckDetails.values()) {
            hints.add(new PathHint.ReferencesParameter(ParameterKind.LUCK, detail));
        }
        if (luckDetails.isEmpty() && luckReferenced) {
            addParameter(parameterDetails, ParameterKind.LUCK, "", null);
        }
        for (ParameterKind kind : ParameterKind.values()) {
            Map<String, Component> details = parameterDetails.get(kind);
            if (details == null) {
                continue;
            }
            for (Component detail : details.values()) {
                hints.add(new PathHint.ReferencesParameter(kind, detail));
            }
        }
        if (!scenarioConditions.isEmpty()) {
            hints.add(new PathHint.ReferencesScenario(List.copyOf(scenarioConditions.values())));
        }
        if (!unresolvedConditions.isEmpty()) {
            hints.add(new PathHint.UnresolvedConditions(List.copyOf(unresolvedConditions.values())));
        }
        return List.copyOf(hints);
    }

    /**
     * 把逐路径幸运门槛渲染成一条静态陈述；无可陈述内容时返回 {@code null}。
     * <p>
     * 三种形态各自有专属文案：可填的数值门槛、只报"区间受限"不给数值、以及只有额外抽取门槛。
     * 不可达由 {@code Probability.Unreachable} 承担，不在这里再写一遍——同一个结论两处表达
     * 迟早会互相矛盾。
     */
    @Nullable
    private static Component luckDetail(@Nullable LuckGate gate) {
        if (gate == null || gate.isTrivial() || gate.impossible()) {
            return null;
        }
        if (gate.minLuck().isPresent()) {
            return Component.translatable(KEY_LUCK_AT_LEAST, ProbabilityFormat.formatLuck(gate.minLuck().getAsDouble()));
        }
        if (gate.rangeLimited()) {
            return Component.translatable(KEY_LUCK_RANGE_LIMITED);
        }
        if (gate.bonusRollsGate().isPresent()) {
            return Component.translatable(KEY_LUCK_BONUS_ROLLS,
                    ProbabilityFormat.formatLuck(gate.bonusRollsGate().getAsDouble()));
        }
        return null;
    }

    // 递归分类单条条件；组合条件只按类型本身归类，其子条件各自递归
    private static void collect(LootConditionInfo condition,
                                Map<ParameterKind, Map<String, Component>> parameterDetails,
                                LinkedHashMap<String, LootConditionInfo> scenarioConditions,
                                LinkedHashMap<String, LootConditionInfo> unresolvedConditions) {
        ResourceLocation type = condition.conditionType();
        boolean composite = isComposite(type);
        if (LootConditionHandlers.FIDELITY_UNREADABLE.equals(
                condition.metadata().get(LootConditionHandlers.FIDELITY_METADATA_KEY))
                && (!composite || condition.children().isEmpty())) {
            unresolvedConditions.putIfAbsent(detailKey(condition), condition);
        }
        if (MATCH_TOOL.equals(type)) {
            // 决策 8：match_tool 不再由场景伪造布尔，工具真实求值，因此它是"工具"旋钮的引用
            addParameter(parameterDetails, ParameterKind.TOOL, detailKey(condition), condition.description());
        } else if (isEnchantmentLevelMechanism(type)) {
            // 附魔等级是函数/条件在运行时读取的输入（决策 26），不是条件谓词
            addParameter(parameterDetails, ParameterKind.ENCHANT_LEVEL, detailKey(condition),
                    condition.description());
        } else if (SimulationScenarioPlanner.isScenarioControlled(type)) {
            scenarioConditions.putIfAbsent(detailKey(condition), condition);
        }
        // 只有**组合条件**的子节点是"另一条条件"；其它类型的子节点是同一条条件的**展示子行**
        // （例：{@code tool_enchantment} 的"概率：基础 20%，每级变化 10%"子行）。递归进去会把同一条门槛
        // 重复列一遍，还会把概率子行写成"该路径需要工具带 概率：… 附魔"这种读不通的句子。
        if (composite) {
            for (LootConditionInfo child : condition.children()) {
                collect(child, parameterDetails, scenarioConditions, unresolvedConditions);
            }
        }
    }

    // 组合条件的三种类型——只有它们的子节点是语义上独立的另一条条件
    private static boolean isComposite(ResourceLocation type) {
        return ALL_OF.equals(type) || ANY_OF.equals(type) || INVERTED.equals(type);
    }

    // 读工具附魔等级的机制：模组的 tool_enchantment 与三处原版机制。等级是资格也是概率来源
    private static boolean isEnchantmentLevelMechanism(ResourceLocation type) {
        return type.equals(com.meteorite.unsuspiciousblock.loottable.condition.ModLootConditions.TOOL_ENCHANTMENT)
                || TABLE_BONUS.equals(type)
                || ENCHANTMENT_ACTIVE_CHECK.equals(type)
                || RANDOM_CHANCE_WITH_ENCHANTED_BONUS.equals(type);
    }

    private static void addParameter(Map<ParameterKind, Map<String, Component>> target,
                                     ParameterKind kind, String detailKey, @Nullable Component detail) {
        Map<String, Component> details = target.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
        // 无 detail 的引用用空串占位，保证 LUCK 这类"无具体目标"的旋钮只出现一次
        details.putIfAbsent(detailKey, detail == null ? Component.empty() : detail);
    }

    // 同一条件在解析期与运行时的实例不同，用类型 + 展示文本做稳定去重键（与指纹同型）
    private static String detailKey(LootConditionInfo condition) {
        String detail = condition.description().getString();
        return condition.conditionType() + "|" + detail;
    }
}
