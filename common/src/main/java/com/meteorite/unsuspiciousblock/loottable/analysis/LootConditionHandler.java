package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonObject;
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
     * 尝试静态分析条件 JSON。
     *
     * @param conditionJson 该条件的 JSON 对象（含 "condition" 字段）
     * @return 条件分析结果；返回 null 表示纯运行时条件，无法静态描述
     */
    @Nullable
    LootConditionInfo analyze(JsonObject conditionJson);

    /**
     * 该条件是否引入不确定性（影响模拟结果置信度）。
     *
     * @return true 表示模拟时该条件的结果不可预测
     */
    boolean addsUncertainty();
}