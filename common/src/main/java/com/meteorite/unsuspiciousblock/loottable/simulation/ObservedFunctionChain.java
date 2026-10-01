package com.meteorite.unsuspiciousblock.loottable.simulation;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 展平后的一条**运行时观测链**——供结果签名聚合与后续展示使用。
 * <p>
 * 只保留函数注册名与捕获状态：不携带 {@code LootItemFunction} 对象、不携带物品对象身份，
 * 便于后续按有界结构编码传输（规划 §4.7 / §4.8）。
 *
 * @param functionTypes   按执行顺序排列的函数注册名，重复执行保留重复项
 * @param state           该链的捕获状态
 * @param contentExpanded 链上是否所有函数的内容都已展开（容器函数会置 false）
 */
public record ObservedFunctionChain(List<ResourceLocation> functionTypes,
                                    State state,
                                    boolean contentExpanded) {

    public ObservedFunctionChain {
        functionTypes = functionTypes == null ? List.of() : List.copyOf(functionTypes);
    }

    // 该链是否至少记录了一个执行节点
    public boolean isEmpty() {
        return this.functionTypes.isEmpty();
    }

    /** 单条观测链的捕获状态；描述完整度由静态侧 {@code FunctionFidelity} 单独表达，两者不可互推。 */
    public enum State {
        /** 已按对象身份完整建立本轮执行链。 */
        COMPLETE,
        /** 链被预算截断（单链节点上限或链建立期间触发了截断），只能作为片段使用。 */
        PARTIAL
    }
}
