package com.meteorite.unsuspiciousblock.loottable.analysis;

/**
 * 函数对产物的**主要效果类别**——用于预览语义与函数条件的处理分支。
 * <p>
 * 刻意不用单一的 "addsRandomness" 布尔表达全部情况：数量函数与身份变换对
 * "基础物品是否可掉落"的结论完全不同（规划 F04 与 §4.4）。
 */
public enum FunctionEffectKind {
    /** 只改变数量（set_count / limit_count / enchanted_count_increase / apply_bonus…）。 */
    COUNT,
    /** 改变物品身份（set_item、成书转附魔书、熔炼…）。 */
    ITEM_TRANSFORM,
    /** 只写组件（set_components / set_name / set_potion / 附魔…）。 */
    COMPONENT,
    /** 包装器，本身不产出效果，效果在内层（sequence / filtered / reference）。 */
    WRAPPER,
    /** 容器内容（set_contents / modify_contents / set_loot_table）——内容不并入外层掉落。 */
    CONTAINER,
    /** 效果未知（未解析的第三方函数）。 */
    UNKNOWN;

    // 身份变换会改变最终物品；数量/组件类在条件失败时不影响基础物品能否掉落。
    public boolean transformsItemIdentity() {
        return this == ITEM_TRANSFORM;
    }

    // 包装器与容器都不直接产生"本层掉落"，预览与观测需按各自的边界处理。
    public boolean isWrapper() {
        return this == WRAPPER;
    }

    public boolean isContainer() {
        return this == CONTAINER;
    }
}
