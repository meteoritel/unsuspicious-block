package com.meteorite.unsuspiciousblock.loottable.analysis;

import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.jetbrains.annotations.Nullable;

/**
 * 战利品条件处理器——静态分析 loot table entry 的 conditions 数组，
 * 生成人类可读的条件描述信息。
 * <p>
 * 外部模组可通过 {@link LootConditionHandlers#register} 注册自定义条件处理逻辑。
 * 设计模式与 {@link LootFunctionHandler} 一致。
 */
public interface LootConditionHandler {

    /**
     * 条件不确定性等级——用于 UI 概率可信度展示。
     */
    enum UncertaintyLevel {
        /** 无条件或仅可静态求值条件（如 survives_explosion、match_tool 等） */
        NONE,
        /** 有概率型条件（如 random_chance、weather_check 等） */
        PROBABILISTIC,
        /** 有纯运行时条件（如 entity_properties、killed_by_player 等）或未知 function */
        RUNTIME
    }

    /**
     * 尝试静态分析条件。
     *
     * @param condition 通过 {@link LootItemCondition#DIRECT_CODEC} 解析后的类型化条件对象
     * @return 条件分析结果；返回 null 表示纯运行时条件，无法静态描述
     */
    @Nullable
    LootConditionInfo analyze(LootItemCondition condition);

    /**
     * 该条件是否引入不确定性（影响模拟结果置信度）。
     *
     * @return true 表示模拟时该条件的结果不可预测
     */
    boolean addsUncertainty();

    /**
     * 该条件的不确定性等级。
     * 默认实现：addsUncertainty() 为 true 时返回 RUNTIME，否则返回 NONE。
     * 子类应覆写以提供更精确的分级。
     */
    default UncertaintyLevel uncertaintyLevel() {
        return addsUncertainty() ? UncertaintyLevel.RUNTIME : UncertaintyLevel.NONE;
    }
}