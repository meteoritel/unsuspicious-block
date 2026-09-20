package com.meteorite.unsuspiciousblock.loottable.catalog;

/**
 * 可由玩家调整的模拟旋钮种类。
 * <p>
 * 白名单经第四轮收窄为四项（决策 49）：幸运、工具、附魔等级、抽样次数。
 * **不做爆炸与击杀/抢夺**——它们分别只服务于方块破坏表与实体掉落表，
 * 而这两类表不在追踪范围内（见规划 §2.3）。
 */
public enum ParameterKind {
    /** 幸运：只影响权重与抽取次数，没有任何原版条件直接读取它。 */
    LUCK,
    /** 工具基座：由 {@code match_tool} 谓词决定资格。 */
    TOOL,
    /** 工具附魔等级：由读 {@code TOOL} 的机制（{@code apply_bonus} / {@code table_bonus} / {@code tool_enchantment}）读取。 */
    ENCHANT_LEVEL,
    /** 抽样次数：统计口径，不是玩家处境。 */
    SAMPLE_COUNT
}
