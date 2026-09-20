package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.DeclaredChance;
import com.meteorite.unsuspiciousblock.loottable.catalog.ParameterKind;
import com.meteorite.unsuspiciousblock.loottable.catalog.PathHint;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.UnknownReason;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 概率格式化工具——把 {@link Probability} 值渲染成人类可读的文本。
 * <p>
 * 这是**唯一**的概率格式化入口，只在 UI 边界调用：数据层一律持有数值，
 * 不再出现"把界面文本反解回数值来排序"这种既脆弱又不可逆的做法。
 * <p>
 * 文案口径（决策 40）：
 * <ul>
 *   <li>{@code ?} —— 未知。原因不在网格里展开，由 tooltip 用 {@link #describeUnknown} 分述；</li>
 *   <li>{@code 0%} —— 只留给静态可证明的不可达；</li>
 *   <li>「未命中」—— 抽样零命中（{@code Measured(0.0)}）。<b>不再</b>写作 {@code <0.01%}：
 *       那等于声称 p 小于抽样分辨率，而 n=10000 时真实概率 0.01% 仍有约 36.8% 的概率零命中；</li>
 *   <li>「需要条件」—— 当前输入下没有可用路径，但路径引用了可调整的旋钮或条件。</li>
 * </ul>
 */
public final class ProbabilityFormat {

    private static final MathContext MC_2SIG = new MathContext(2, RoundingMode.HALF_UP);
    private static final String KEY_PREFIX = "screen.unsuspiciousblock.archaeology_journal.";

    private ProbabilityFormat() {
    }

    // 声明值不套用抽样精度或零出现阈值，0、微小概率及区间均保留原值。
    public static Component formatDeclaredChances(List<DeclaredChance> chances) {
        var result = Component.empty();
        for (int i = 0; i < chances.size(); i++) {
            if (i > 0) {
                result.append(Component.translatable(
                        KEY_PREFIX + "item_hint.alternative_separator"));
            }
            DeclaredChance chance = chances.get(i);
            String lower = exactPercent(chance.lower());
            result.append(chance.lower() == chance.upper()
                    ? lower : lower + "-" + exactPercent(chance.upper()));
        }
        return result;
    }

    private static String exactPercent(double fraction) {
        return BigDecimal.valueOf(fraction).movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * 把概率值渲染为**展示用组件**——这是网格与子表入口共用的短文案入口。
     * <p>
     * 四种状态各自有专属文案，绝不共用：未知 {@code ?}、不可达 {@code 0%}、
     * 零命中「未命中」、需要条件「需要条件」。数值部分走 {@link #formatNumeric}。
     */
    public static Component formatComponent(Probability probability) {
        return switch (probability) {
            case Probability.Unknown ignored -> Component.translatable(KEY_PREFIX + "probability_question");
            case Probability.Unreachable ignored -> Component.literal("0%");
            case Probability.NeedsCondition ignored ->
                    Component.translatable(KEY_PREFIX + "probability_needs_condition");
            // 单值零命中在这里被替换成「未命中」，避免把抽样事实写成 0%
            case Probability.Measured measured -> measured.upper().isPresent()
                    ? Component.literal(formatBound(measured.lower()) + "-"
                    + formatBound(measured.upper().getAsDouble()))
                    : measured.lower() == 0.0
                    ? Component.translatable(KEY_PREFIX + "probability_miss")
                    : Component.literal(formatFraction(measured.lower()));
        };
    }

    /** 供 tooltip 与排序使用的纯数值渲染（不做零命中的文案替换）。 */
    public static String formatNumeric(Probability probability) {
        return switch (probability) {
            case Probability.Unknown ignored -> "?";
            case Probability.NeedsCondition ignored -> "?";
            case Probability.Unreachable ignored -> "0%";
            case Probability.Measured measured -> measured.upper().isPresent()
                    ? formatBound(measured.lower()) + "-" + formatBound(measured.upper().getAsDouble())
                    : formatFraction(measured.lower());
        };
    }

    /** tooltip 里"为什么这里是问号"的一行——每个原因各有独立文案（决策 36）。 */
    public static Component describeUnknown(UnknownReason reason) {
        return Component.translatable(switch (reason) {
            case UNCOVERED -> KEY_PREFIX + "unknown_reason.uncovered";
            case UNPARSED -> KEY_PREFIX + "unknown_reason.unparsed";
            case NOT_SIMULATED -> KEY_PREFIX + "unknown_reason.not_simulated";
            case SIMULATION_FAILED -> KEY_PREFIX + "unknown_reason.simulation_failed";
            case EVICTED -> KEY_PREFIX + "unknown_reason.evicted";
        });
    }

    /**
     * 抽样零命中的说明：报出抽样事实与当前抽样次数，并提示可以提高次数。
     * <b>不给</b>置信上界——那是另一种把"没算到"说成"不可能"的方式（决策 40）。
     */
    public static Component describeZeroHit(int sampleCount) {
        return Component.translatable(KEY_PREFIX + "probability_miss_detail", sampleCount);
    }

    /** 抽样零命中时可操作的一步：提高抽样次数。 */
    public static Component describeZeroHitHint() {
        return Component.translatable(KEY_PREFIX + "probability_miss_hint");
    }

    /** 静态信息性提示的逐行文案；只说"这条路径引用了什么"，不承诺可达成（决策 34）。 */
    public static List<Component> describePathHints(List<PathHint> hints) {
        List<Component> lines = new ArrayList<>(hints.size());
        for (PathHint hint : hints) {
            switch (hint) {
                case PathHint.ReferencesParameter parameter ->
                        lines.add(describeParameterReference(parameter));
                case PathHint.ReferencesScenario scenario ->
                        lines.add(Component.translatable(KEY_PREFIX + "path_hint.scenario",
                                joinConditionDescriptions(scenario)));
            }
        }
        return List.copyOf(lines);
    }

    private static Component describeParameterReference(PathHint.ReferencesParameter parameter) {
        Component detail = parameter.detail();
        boolean hasDetail = detail != null && !detail.getString().isBlank();
        return switch (parameter.kind()) {
            case LUCK -> Component.translatable(KEY_PREFIX + "path_hint.luck");
            case TOOL -> Component.translatable(KEY_PREFIX
                            + (hasDetail ? "path_hint.tool" : "path_hint.tool_unspecified"),
                    hasDetail ? detail : Component.empty());
            case ENCHANT_LEVEL -> Component.translatable(KEY_PREFIX
                            + (hasDetail ? "path_hint.enchant_level" : "path_hint.enchant_level_unspecified"),
                    hasDetail ? detail : Component.empty());
            case SAMPLE_COUNT -> Component.translatable(KEY_PREFIX + "path_hint.sample_count");
        };
    }

    // 场景条件逐条列出，用与条件树相同的分隔符拼接；保留条件本身的保真度标记不动
    private static Component joinConditionDescriptions(PathHint.ReferencesScenario scenario) {
        var result = Component.empty();
        List<LootConditionInfo> conditions = scenario.conditions();
        for (int index = 0; index < conditions.size(); index++) {
            if (index > 0) {
                result.append(Component.translatable(KEY_PREFIX + "item_hint.separator"));
            }
            result.append(conditions.get(index).description().copy());
        }
        return result;
    }

    // 区间端点的渲染：0 直接写 0%，其余走常规比例渲染
    private static String formatBound(double fraction) {
        return fraction == 0.0 ? "0%" : formatFraction(fraction);
    }

    /**
     * 将比例值（0.0~1.0）格式化为百分数字符串。
     * <ul>
     *   <li>≥1.0 → "100%"</li>
     *   <li>其他 → 百分数保留 2 位有效数字（如 "12%", "1.5%", "0.12%", "0.001%"）</li>
     * </ul>
     * 不再把极小值折叠成 {@code <0.01%}：那个写法声称了抽样分辨率之上的上界。
     */
    private static String formatFraction(double fraction) {
        if (fraction >= 1.0) return "100%";
        double percent = fraction * 100.0;
        BigDecimal bd = new BigDecimal(percent, MC_2SIG);
        return bd.stripTrailingZeros().toPlainString() + "%";
    }
}
