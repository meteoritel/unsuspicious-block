package com.meteorite.unsuspiciousblock.loottable.analysis;

import java.util.OptionalDouble;

/**
 * 单条获取路径的**幸运门槛**结论——由 {@link LuckGateAnalysis} 从 {@link LuckSpec} 推出。
 * <p>
 * 存在的理由（决策 20/28/34）：玩家要能直接照着填的数，而不是"约 1/3"这种填不进去的人话。
 * 因此门阈值必须**向上对齐到 0.01 网格**并用真实公式回验过，且**按路径分别保留**——
 * 首版把多路径的并集取最小值，合并后的数字无法指回是哪一条路径需要它。
 * <p>
 * 三种形态互斥，且都不是用文本表达的（展示文案在 UI 边界生成）：
 * <ul>
 *   <li>{@code impossible}：在任何可表示的幸运下有效权重都恒为 0。这**不是**"抽样没命中"，
 *       而是静态证明——只有它才允许展示 {@code 0%}；</li>
 *   <li>{@code minLuck} 非空：满足集合在 {@code [0, +∞)} 上是上闭的，因此可以写
 *       "需要幸运 ≥ x"。{@code x = 0} 的情形不会产生本对象（见 {@link #NONE}）；</li>
 *   <li>{@code rangeLimited}：满足集合有上限或多段不连续，**不给具体数值**——
 *       给一个数就等于承诺"高过它就能拿到"，而那正是负 quality 条目会打破的承诺。</li>
 * </ul>
 * {@code bonusRollsGate} 是第二条独立信息：使 {@code bonus_rolls} 开始产生额外抽取所需的最小幸运。
 * 它与权重门槛可以同时成立，因此单独成字段而不是与 {@code minLuck} 取大值。
 *
 * @param impossible     是否静态证明在任何可表示的幸运下都拿不到
 * @param minLuck        使权重恢复为正所需的最小幸运（已对齐网格并回验）；空表示该门槛不适用
 * @param rangeLimited   是否存在上限或多段不连续区间（因此不给具体数值）
 * @param bonusRollsGate 使奖励抽取开始生效所需的最小幸运；空表示该池没有可静态求值的额外抽取
 */
public record LuckGate(boolean impossible, OptionalDouble minLuck, boolean rangeLimited,
                       OptionalDouble bonusRollsGate) {
    /**
     * 与幸运无关的路径——门阈为空，调用方无需再判断。
     * <p>
     * 可选字段的契约：两个 {@code OptionalDouble} 由调用方负责给出
     * {@link OptionalDouble#empty()} 而不是 {@code null}；本记录只校验**语义**不变量
     * （不可达时不给出数值），不替调用方做 null 兜底——那会把一个编程错误变成一个看起来正常的门阈。
     */
    public static final LuckGate NONE =
            new LuckGate(false, OptionalDouble.empty(), false, OptionalDouble.empty());

    public LuckGate {
        if (impossible && (minLuck.isPresent() || bonusRollsGate.isPresent())) {
            throw new IllegalArgumentException("不可达的路径不应同时给出幸运门槛数值");
        }
    }

    /** 该门阈是否不含任何可陈述的信息——调用方据此完全跳过它。 */
    public boolean isTrivial() {
        return !this.impossible && this.minLuck.isEmpty() && !this.rangeLimited
                && this.bonusRollsGate.isEmpty();
    }
}
