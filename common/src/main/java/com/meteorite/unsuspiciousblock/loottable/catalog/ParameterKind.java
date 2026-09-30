package com.meteorite.unsuspiciousblock.loottable.catalog;

/**
 * 可由玩家调整的模拟旋钮种类。
 * <p>
 * 玩家可编辑白名单经第四轮收窄为三项：幸运、工具、附魔等级；抽样次数仍保留为历史路径提示与网络兼容类型，当前 UI 不提供编辑入口。
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
    /** 抽样次数：历史路径提示与兼容类型，当前固定为 10,000，不是玩家旋钮。 */
    SAMPLE_COUNT
}
