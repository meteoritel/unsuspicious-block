package com.meteorite.unsuspiciousblock.loottable.analysis;

import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;

/**
 * 逐路径的最小幸运门槛判定——只做"最小门槛"，不做完整区间代数（决策 20）。
 * <p>
 * 判定读的是原版的两条真实公式（1.21.1，源码核实见规划 §3.1）：
 * <ul>
 *   <li>权重竞争：{@code max(floor(weight + quality × luck), 0)}——只有 ≥ 1 时该条目才进入抽取；</li>
 *   <li>奖励抽取：{@code rolls + floor(bonus_rolls × luck)}——额外抽取只在 {@code floor(bonus_rolls × luck) ≥ 1}
 *       时出现。</li>
 * </ul>
 * <b>没有任何原版战利品条件直接读幸运</b>，因此这两条就是"幸运能不能改变这条路径"的全部入口。
 * <p>
 * 算法刻意分两步（决策 34/28）：先由代数解出候选门槛，**向上对齐到 0.01 网格**，再用上面两条真实公式
 * **回验**；回验不通过就按 0.01 步进直到通过或触及预算。这样给出的数值是"照着填就能生效"的值，
 * 而不是"约 1/3"这种填不进去的人话（1/3 的上对齐结果是 0.34，且 0.34 确实使 floor(3×0.34) = 1）。
 * <p>
 * 降级原则：满足集合不是"从某个数往上全满足"时（负 quality 造成的上限、代数解落在可表示范围之外、
 * 或步进预算耗尽），一律**不给数值**并标记 {@link LuckGate#rangeLimited()}——给一个数就等于承诺
 * "高过它就能拿到"。同理，无法静态求值的 {@code bonus_rolls} 记为"未知"而不是零。
 */
public final class LuckGateAnalysis {
    /** 幸运的合法范围下界（决策 27：-5.00 ~ 10.00，精度 0.01）。 */
    public static final double MIN_LUCK = -5.0;
    /** 幸运的合法范围上界。 */
    public static final double MAX_LUCK = 10.0;
    /** 门槛对齐网格；与玩家输入精度一致，因此门槛值可以直接填进输入框（决策 7/27/34）。 */
    public static final double GRID = 0.01;
    private static final int MAX_GRID_STEP = (int) Math.round(MAX_LUCK / GRID);
    /** 回验步进的预算——覆盖整个可表示范围还有余量，触顶即降级为"区间受限"。 */
    private static final int MAX_GRID_STEPS = MAX_GRID_STEP + 1;
    private static final double EPSILON = 1e-9;
    /** 满足集合有上限或多段不连续、因此**不给数值**的那一档结论（负 quality 造成）。 */
    private static final LuckGate RANGE_LIMITED =
            new LuckGate(false, OptionalDouble.empty(), true, OptionalDouble.empty());

    private LuckGateAnalysis() {
    }

    /** 判定单条路径的幸运门槛；{@code spec} 为空或与幸运无关时返回 {@link LuckGate#NONE}。 */
    public static LuckGate analyze(@Nullable LuckSpec spec) {
        if (spec == null || spec.luckNeutral()) {
            return LuckGate.NONE;
        }
        boolean atZero = weightPositive(spec, 0.0);
        boolean atMin = weightPositive(spec, MIN_LUCK);
        boolean atMax = weightPositive(spec, MAX_LUCK);

        if (spec.quality() >= 0) {
            // 权重随幸运单调不减：满足集合在范围内是"从某点往上全满足"
            if (!atMax) {
                return unreachable();
            }
            if (atZero) {
                return weightFree(spec);
            }
            OptionalDouble gate = firstSatisfyingLuck(spec);
            return gate.isPresent()
                    ? new LuckGate(false, gate, false, bonusRollsGate(spec))
                    : RANGE_LIMITED;
        }

        // 负 quality：权重随幸运单调不增，满足集合有上限，因此不能写成"需要幸运 ≥ x"
        if (!atMin) {
            return unreachable();
        }
        if (atZero && atMax) {
            return weightFree(spec);
        }
        return RANGE_LIMITED;
    }

    /** 幸运 0 就能通过权重竞争：门槛只剩奖励抽取一侧。 */
    private static LuckGate weightFree(LuckSpec spec) {
        OptionalDouble bonus = bonusRollsGate(spec);
        return bonus.isPresent()
                ? new LuckGate(false, OptionalDouble.empty(), false, bonus)
                : LuckGate.NONE;
    }

    /**
     * 使 {@code floor(weight + quality × luck) ≥ 1} 成立的最小 0.01 网格点。
     * 代数解给出起点，真实公式回验；回验不过则逐格上探。
     */
    private static OptionalDouble firstSatisfyingLuck(LuckSpec spec) {
        double exact = (1.0 - spec.weight()) / (double) spec.quality();
        int startStep = (int) Math.ceil(exact / GRID - EPSILON);
        return stepUntilSatisfied(Math.max(startStep, 1), luck -> weightPositive(spec, luck));
    }

    /**
     * 使 {@code floor(bonus_rolls × luck) ≥ 1} 成立的最小 0.01 网格点。
     * {@code bonus_rolls} 无法静态求值（含非正数）时返回空——"未知"不等于"没有奖励抽取"。
     */
    private static OptionalDouble bonusRollsGate(LuckSpec spec) {
        Double bonusRolls = spec.bonusRolls();
        if (bonusRolls == null || bonusRolls <= 0.0) {
            return OptionalDouble.empty();
        }
        int startStep = (int) Math.ceil((1.0 / bonusRolls) / GRID - EPSILON);
        return stepUntilSatisfied(Math.max(startStep, 1),
                luck -> bonusRollTriggered(bonusRolls, luck));
    }

    // 从 startStep 起逐格上探；触顶或超预算即返回空，由调用方降级为"区间受限"而不是编一个数
    private static OptionalDouble stepUntilSatisfied(int startStep, java.util.function.DoublePredicate satisfied) {
        for (int step = startStep; step <= MAX_GRID_STEP && step - startStep <= MAX_GRID_STEPS; step++) {
            double luck = gridLuck(step);
            if (satisfied.test(luck)) {
                return OptionalDouble.of(luck);
            }
        }
        return OptionalDouble.empty();
    }

    // 网格点回写：34 → 0.34（而不是 34 × 0.01 的浮点残差），使门槛值可直接写进缓存键与输入框
    private static double gridLuck(int step) {
        return step / 100.0;
    }

    /** 原版权重公式：{@code max(floor(weight + quality × luck), 0)}。 */
    static int effectiveWeight(LuckSpec spec, double luck) {
        return (int) Math.max(Math.floor(spec.weight() + spec.quality() * luck), 0.0);
    }

    /** 该幸运下条目是否仍有正权重（即还能参与抽取）。 */
    static boolean weightPositive(LuckSpec spec, double luck) {
        return effectiveWeight(spec, luck) >= 1;
    }

    /** 原版奖励抽取公式：额外抽取只在 {@code floor(bonus_rolls × luck) ≥ 1} 时出现。 */
    static boolean bonusRollTriggered(double bonusRolls, double luck) {
        return Math.floor(bonusRolls * luck) >= 1.0;
    }

    private static LuckGate unreachable() {
        return new LuckGate(true, OptionalDouble.empty(), false, OptionalDouble.empty());
    }
}
