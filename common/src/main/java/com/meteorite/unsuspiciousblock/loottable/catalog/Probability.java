package com.meteorite.unsuspiciousblock.loottable.catalog;

import java.util.List;
import java.util.OptionalDouble;

/**
 * 概率值类型——把原先共用一个字符串的五种语义拆成互斥状态，使"没算到"（未知）与
 * "算出来是零"（不可达）不再可能混淆，也让概率可以被数值比较与聚合。
 * <p>
 * 语义边界：
 * <ul>
 *   <li>{@link Unknown}：没有可展示的测量值，成因由 {@link UnknownReason} 分述——<b>不是</b>不可达；</li>
 *   <li>{@link Unreachable}：静态判定不可达，即该输入下确实不可能产出（展示为 {@code 0%}）；</li>
 *   <li>{@link Measured}：实际抽样得到的比例；{@code upper} 为空表示单值，非空表示区间；</li>
 *   <li>{@link NeedsCondition}：当前输入下没有可用路径，但路径引用了可调整的旋钮或条件——
 *       这是"需要条件"，<b>不是</b>"拿不到"，更不是"没算过"。</li>
 * </ul>
 * 展示文本只在 UI 边界生成（见 {@code ProbabilityFormat}），本类型不携带任何格式化结果。
 * <p>
 * 特别注意"抽样 N 次一次未出现"是 {@code Measured(0.0)} 而非 {@link Unreachable}——
 * 前者是测量结果，展示为「未命中」并附当前抽样次数；后者是静态结论，展示为 {@code 0%}
 * （决策 40：绝不用 {@code <0.01%} 之类的写法把抽样分辨率说成概率上界）。
 */
public sealed interface Probability {
    /** 未知：没有可展示的测量值，成因见 {@link UnknownReason}。 */
    record Unknown(UnknownReason reason) implements Probability {
        public Unknown {
            if (reason == null) {
                throw new IllegalArgumentException("未知概率必须携带原因");
            }
        }
    }

    /** 不可达：静态判定该输入下不可能产出。 */
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

    /** 需要条件：当前输入下无可用路径，但路径引用了可调整的旋钮或条件。 */
    record NeedsCondition(List<PathHint> hints) implements Probability {
        public NeedsCondition {
            hints = List.copyOf(hints);
        }

        /** 该值是否由服务端找到了联合见证（P2 起才有非空推荐）。 */
        public boolean hasRecommendation() {
            return false;
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

    /** 未知值。 */
    static Probability unknown(UnknownReason reason) {
        return new Unknown(reason);
    }

    /** 未覆盖的未知值（最常用的一档）。 */
    static Probability uncovered() {
        return new Unknown(UnknownReason.UNCOVERED);
    }

    /** 不可达值（单例，便于复用）。 */
    static Probability unreachable() {
        return Unreachable.INSTANCE;
    }

    /** 需要条件。 */
    static Probability needsCondition(List<PathHint> hints) {
        return new NeedsCondition(hints);
    }

    /**
     * 数值下界，用于排序与聚合：未知与需要条件为 {@code -1}（排在末尾），不可达为 {@code 0}，
     * 已测量为 {@code lower}。
     */
    default double lowerBound() {
        return switch (this) {
            case Unknown ignored -> -1.0;
            case NeedsCondition ignored -> -1.0;
            case Unreachable ignored -> 0.0;
            case Measured measured -> measured.lower();
        };
    }

    /**
     * 数值上界：未知、需要条件与不可达同 {@link #lowerBound()}，已测量在无区间时为 {@code lower}。
     */
    default double upperBound() {
        return switch (this) {
            case Unknown ignored -> -1.0;
            case NeedsCondition ignored -> -1.0;
            case Unreachable ignored -> 0.0;
            case Measured measured -> measured.upper().orElse(measured.lower());
        };
    }

    /**
     * 是否"已测量但抽样中一次未出现"——展示为「未命中」。
     * 与 {@link Unreachable} 区分开，是"未知不等于零"之外的第二处语义边界。
     */
    default boolean isZeroHit() {
        return this instanceof Measured(double lower, OptionalDouble upper)
                && upper.isEmpty() && lower == 0.0;
    }

    /** 是否为未知值。 */
    default boolean isUnknown() {
        return this instanceof Unknown;
    }

    /** 是否已经得出一个可展示的确定结论（已测量或静态不可达）。 */
    default boolean isDisplayable() {
        return this instanceof Measured || this instanceof Unreachable;
    }

    /** 是否携带可展示的测量值（供显示优先级链判断）。 */
    default boolean isMeasured() {
        return this instanceof Measured;
    }
}
