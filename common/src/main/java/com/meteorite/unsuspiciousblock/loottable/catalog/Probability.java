package com.meteorite.unsuspiciousblock.loottable.catalog;

import java.util.OptionalDouble;

/**
 * 概率值类型——把原先共用一个字符串的五种语义拆成互斥状态，使"没算到"（未知）与
 * "算出来是零"（不可达）不再可能混淆，也让概率可以被数值比较与聚合。
 * <p>
 * 语义边界：
 * <ul>
 *   <li>{@link Unknown}：未被任何代表场景覆盖，或条目带条件而抽样零出现——<b>不是</b>不可达；</li>
 *   <li>{@link Unreachable}：在对应场景中被静态判定不可达，即该场景下确实不可能产出；</li>
 *   <li>{@link Measured}：实际抽样得到的比例；{@code upper} 为空表示单值，非空表示跨场景的区间。</li>
 * </ul>
 * 展示文本只在 UI 边界生成（见 {@code ProbabilityFormat}），本类型不携带任何格式化结果。
 * 注意"抽样 10000 次一次未出现"是 {@code Measured(0.0)} 而非 {@link Unreachable}——
 * 前者是测量结果，展示为 {@code <0.01%}，后者是静态结论，展示为 {@code 0%}。
 */
public sealed interface Probability {
    /** 未知：未被覆盖，或带条件条目抽样零出现。 */
    record Unknown() implements Probability {
        public static final Unknown INSTANCE = new Unknown();
    }

    /** 不可达：静态判定该场景下不可能产出。 */
    record Unreachable() implements Probability {
        public static final Unreachable INSTANCE = new Unreachable();
    }

    /**
     * 已测量：{@code lower} 为下界或单值，{@code upper} 非空时表示区间上界。
     * 构造时强制有限数值与 {@code 0 <= lower <= upper <= 1}。
     */
    record Measured(double lower, OptionalDouble upper) implements Probability {
        public Measured {
            requireFraction(lower, "lower");
            if (upper.isPresent()) {
                requireFraction(upper.getAsDouble(), "upper");
                if (upper.getAsDouble() < lower) {
                    throw new IllegalArgumentException(
                            "概率区间上界不得小于下界: lower=" + lower + ", upper=" + upper.getAsDouble());
                }
            }
        }

        // 拒绝 NaN / Infinity / 越界值：非法概率必须在构造点暴露，而不是流到 UI 才显示成乱码
        private static void requireFraction(double value, String name) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("概率必须是有限数值: " + name + "=" + value);
            }
            if (value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("概率必须落在 [0, 1]: " + name + "=" + value);
            }
        }
    }

    /** 单值测量结果。 */
    static Probability measured(double fraction) {
        return new Measured(fraction, OptionalDouble.empty());
    }

    /** 区间测量结果。 */
    static Probability measuredRange(double lower, double upper) {
        return new Measured(lower, OptionalDouble.of(upper));
    }

    /** 未知值（单例，便于复用）。 */
    static Probability unknown() {
        return Unknown.INSTANCE;
    }

    /** 不可达值（单例，便于复用）。 */
    static Probability unreachable() {
        return Unreachable.INSTANCE;
    }

    /**
     * 数值下界，用于排序与聚合：未知为 {@code -1}（排在末尾），不可达为 {@code 0}，
     * 已测量为 {@code lower}。
     */
    default double lowerBound() {
        return switch (this) {
            case Unknown ignored -> -1.0;
            case Unreachable ignored -> 0.0;
            case Measured measured -> measured.lower();
        };
    }

    /**
     * 数值上界：未知与不可达同 {@link #lowerBound()}，已测量在无区间时为 {@code lower}。
     */
    default double upperBound() {
        return switch (this) {
            case Unknown ignored -> -1.0;
            case Unreachable ignored -> 0.0;
            case Measured measured -> measured.upper().orElse(measured.lower());
        };
    }

    /**
     * 是否"已测量但抽样中一次未出现"——展示为 {@code <0.01%}。
     * 与 {@link Unreachable} 区分开，是"未知不等于零"之外的第二处语义边界。
     */
    default boolean isBelowDisplayThreshold() {
        return this instanceof Measured(double lower, OptionalDouble upper)
                && upper.isEmpty() && lower == 0.0;
    }

    /** 是否为未知值。 */
    default boolean isUnknown() {
        return this instanceof Unknown;
    }
}
