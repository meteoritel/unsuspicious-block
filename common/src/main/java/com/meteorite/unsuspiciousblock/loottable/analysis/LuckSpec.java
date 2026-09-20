package com.meteorite.unsuspiciousblock.loottable.analysis;

import org.jetbrains.annotations.Nullable;

/**
 * 单条获取路径在**权重竞争**与**奖励抽取**两处的静态输入——条目/池局部语义里与幸运有关的部分。
 * <p>
 * 存在的理由（规划 §4.1）：{@code LuckGateAnalysis} 要从 {@code weight}/{@code quality}/{@code rolls}/
 * {@code bonus_rolls} 推出"使这条路径仍有正权重所需的最小幸运"，而这些数值只存在于 JSON 原文里。
 * 编译期把它们**原样**记下来（而不是先算成一个门槛），是为了让门槛算法只有一份实现、
 * 且改动算法不需要重新定义编译产物。
 * <p>
 * 可静态求值程度是逐字段的：{@code weight} 与 {@code quality} 在原版是整数字段，恒可求值；
 * {@code rolls} 与 {@code bonus_rolls} 是数字提供器，只在常量或等端点 {@code uniform} 时才给出数值，
 * 其余情形为 {@code null}（"未知"）。未知**不是零**——门槛算法据此降级为"区间受限"，
 * 而不是把"没算出来"当成"没有额外抽取"。
 *
 * @param weight     条目自身权重（原版缺省 1）
 * @param quality    条目自身品质（原版缺省 0）；负数表示幸运越高权重越低，即存在上限
 * @param rolls      所在池的抽取次数常量；{@code null} 表示动态
 * @param bonusRolls 所在池的奖励抽取常量；{@code null} 表示动态，负数表示幸运越高抽得越少
 */
public record LuckSpec(int weight, int quality, @Nullable Double rolls, @Nullable Double bonusRolls) {
    /** 默认条目语义：权重 1、品质 0、池抽取与奖励抽取均未知。 */
    public static final LuckSpec DEFAULT = new LuckSpec(1, 0, null, null);

    /**
     * 该语义是否与幸运无关——权重恒正、无品质，且**已知**没有奖励抽取。
     * <p>
     * {@code bonusRolls} 为 {@code null}（动态）时**不算**中性：那意味着我们不知道幸运会不会
     * 改变抽取次数，此时提前退出会把"没算出来"说成"不受幸运影响"。
     */
    public boolean luckNeutral() {
        return this.weight >= 1 && this.quality == 0
                && this.bonusRolls != null && this.bonusRolls == 0.0;
    }
}
