package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.DeclaredChance;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * 概率格式化工具——把 {@link Probability} 值渲染成人类可读的文本。
 * <p>
 * 这是**唯一**的概率格式化入口，按规划只在 UI 边界调用：数据层一律持有数值，
 * 不再出现"把界面文本反解回数值来排序"这种既脆弱又不可逆的做法。
 * 未知渲染为 {@code ?}，不可达渲染为 {@code 0%}，"抽样零出现"渲染为 {@code <0.01%}，
 * 区间渲染为 {@code 下界-上界}。
 */
public final class ProbabilityFormat {

    private static final MathContext MC_2SIG = new MathContext(2, RoundingMode.HALF_UP);

    private ProbabilityFormat() {
    }

    // 声明值不套用抽样精度或零出现阈值，0、微小概率及区间均保留原值。
    public static Component formatDeclaredChances(List<DeclaredChance> chances) {
        var result = Component.empty();
        for (int i = 0; i < chances.size(); i++) {
            if (i > 0) {
                result.append(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.alternative_separator"));
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
     * 把概率值渲染为展示文本；未知为 {@code ?}，不可达为 {@code 0%}。
     * <p>
     * 区间两端用 {@link #formatBound(double)} 而非 {@link #formatFraction(double)}：区间下界为 0 时
     * 无法从数值上区分来源是"某场景静态不可达"还是"某场景抽样零出现"，统一渲染为 {@code 0%}
     * （两者都成立且不会把"抽样零出现"误述成"不可达"）。单值仍保留 {@code <0.01%} 的细分语义。
     */
    public static String format(Probability probability) {
        return switch (probability) {
            case Probability.Unknown ignored -> "?";
            case Probability.Unreachable ignored -> "0%";
            case Probability.Measured measured -> measured.upper().isPresent()
                    ? formatBound(measured.lower()) + "-" + formatBound(measured.upper().getAsDouble())
                    : formatFraction(measured.lower());
        };
    }

    // 区间端点的渲染：0 直接写 0%，其余走常规比例渲染
    private static String formatBound(double fraction) {
        return fraction == 0.0 ? "0%" : formatFraction(fraction);
    }

    /**
     * 将比例值（0.0~1.0）格式化为百分数字符串。
     * <ul>
     *   <li>≥1.0 → "100%"</li>
     *   <li>&lt;0.0001 → "&lt;0.01%"</li>
     *   <li>其他 → 百分数保留2位有效数字（如 "12%", "1.5%", "0.12%"）</li>
     * </ul>
     */
    private static String formatFraction(double fraction) {
        if (fraction >= 1.0) return "100%";
        if (fraction < 0.0001) return "<0.01%";
        double percent = fraction * 100.0;
        BigDecimal bd = new BigDecimal(percent, MC_2SIG);
        return bd.stripTrailingZeros().toPlainString() + "%";
    }
}
