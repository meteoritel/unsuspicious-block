package com.meteorite.unsuspiciousblock.loottable.simulation;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 某个物品栈当前持有的**观测链句柄**——按对象身份挂在产物对象上，不写入物品组件或 NBT。
 * <p>
 * 句柄只在本轮抽取内有效：{@code LootSimulationScope.beginRoll()} 会清空全部关联，
 * 因此不会把上一轮的观测带到下一轮，也不需要跨轮清理逻辑。
 * <p>
 * 链是展平后的执行顺序（深度优先），重复执行保留为多个节点；超过单链节点预算时显式截断并置位
 * {@link #truncated()}，不静默丢弃。
 */
public final class TraceHandle {
    private final List<TraceNode> chain;
    private final boolean truncated;

    private TraceHandle(List<TraceNode> chain, boolean truncated) {
        this.chain = chain;
        this.truncated = truncated;
    }

    // 由前缀链与本帧节点建立新句柄：前缀已是**有界**不可变列表，本帧只做**有界**深度优先展平，
    // 达到预算立即停止并置 truncated。不做"先全展平后裁剪"——长 sequence / reference 链下
    // 后者会把整棵本帧子树展平一遍再丢掉，工作量随链长平方增长
    static TraceHandle of(@Nullable TraceHandle prefix, TraceNode node) {
        List<TraceNode> nodes = new ArrayList<>(FunctionTraceSession.MAX_CHAIN_NODES);
        boolean cut = false;
        if (prefix != null) {
            nodes.addAll(prefix.chain);
            cut = prefix.truncated;
        }
        if (flattenBounded(nodes, node)) {
            cut = true;
        }
        return new TraceHandle(List.copyOf(nodes), cut);
    }

    // 展平后的执行顺序链（深度优先，重复执行保留为多个节点）
    public List<TraceNode> chain() {
        return this.chain;
    }

    // 该链是否因预算不足被截断
    public boolean truncated() {
        return this.truncated;
    }

    // 有界深度优先展平：外层节点先于其内层节点，内层按完成顺序排列；重复执行保留为多个节点。
    // 返回 true 表示因达到单链节点预算而截断（仍有节点未写入，调用方据此置 truncated）
    private static boolean flattenBounded(List<TraceNode> target, TraceNode node) {
        if (target.size() >= FunctionTraceSession.MAX_CHAIN_NODES) {
            return true;
        }
        target.add(node);
        for (TraceNode child : node.children()) {
            if (flattenBounded(target, child)) {
                return true;
            }
        }
        return false;
    }
}
