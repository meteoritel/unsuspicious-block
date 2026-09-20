package com.meteorite.unsuspiciousblock.loottable.catalog;

import java.util.OptionalDouble;

/**
 * 持久化层的窄概率类型——只表达"算没算、算出多少"，不表达任何由静态结构派生的结论。
 * <p>
 * 存在的理由（决策 22）：{@link Probability} 的 {@code Unreachable} 与 {@code NeedsCondition}
 * 都是从**条件树与路径结构**推导出来的结论，而存档里应该只有测量事实。把两者塞进同一个值，
 * 正是 D2（"该场景下不适用"与"静态不可达"被折叠成同一状态）的根源。因此：
 * <ul>
 *   <li>落盘时只写 {@link Measured} 或 {@link Unknown}；</li>
 *   <li>读取时由展示层按当前输入重新推导 {@link Probability} 的完整四态。</li>
 * </ul>
 * 这样"改了静态分析规则"不需要迁移存档，也不需要给存档写版本升级逻辑——旧的测量值依然有效。
 */
public sealed interface SimulatedValue {
    /** 没有可用的测量值。 */
    record Unknown() implements SimulatedValue {
        public static final Unknown INSTANCE = new Unknown();
    }

    /** 实际抽样得到的比例；{@code upper} 非空时表示区间上界。 */
    record Measured(double lower, OptionalDouble upper) implements SimulatedValue {
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

        private static void requireFraction(double value, String name) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("概率必须是有限数值: " + name + "=" + value);
            }
            if (value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("概率必须落在 [0, 1]: " + name + "=" + value);
            }
        }
    }

    /** 把展示层的概率值收窄为可持久化的测量事实；一切派生状态都退化为"没有测量值"。 */
    static SimulatedValue from(Probability probability) {
        return switch (probability) {
            case Probability.Measured measured ->
                    new Measured(measured.lower(), measured.upper());
            case Probability.Unknown ignored -> Unknown.INSTANCE;
            case Probability.Unreachable ignored -> Unknown.INSTANCE;
            case Probability.NeedsCondition ignored -> Unknown.INSTANCE;
        };
    }

    /** 展开为展示层概率值；原因由调用方按上下文给出（存档里不保存原因）。 */
    default Probability toProbability(UnknownReason reasonWhenUnknown) {
        return switch (this) {
            case Unknown ignored -> Probability.unknown(reasonWhenUnknown);
            case Measured measured -> measured.upper().isPresent()
                    ? Probability.measuredRange(measured.lower(), measured.upper().getAsDouble())
                    : Probability.measured(measured.lower());
        };
    }
}
