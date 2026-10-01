package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionInfo;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 本轮内一次**已发生的函数执行**在观测链上的节点。
 * <p>
 * 节点只说明"进入过这个函数的执行体"这一事实，不宣称物品一定发生变化（规划 §3.2）。
 * {@code children} 表达执行帧的包装层次：{@code filtered} / {@code reference} 的内层修饰器挂在
 * 触发它们的外层节点下；顺序与重复次数有意义，连续两次同类函数就是两个节点，不折叠。
 * <p>
 * 节点不可变，因此兄弟产物共享链节点不会互相污染（规划 §4.5.2）。
 *
 * @param functionType    函数注册名
 * @param functionInfo    按函数对象实例缓存的静态描述；运行时对象没有原始 JSON，故为兼容摘要级别
 * @param children        在该帧内完成的内层执行节点，按执行顺序
 * @param contentExpanded false 表示这是容器函数（set_contents / modify_contents）的包装节点，
 *                        其子内容在采集时被隔离，未展开
 */
public record TraceNode(ResourceLocation functionType,
                        LootFunctionInfo functionInfo,
                        List<TraceNode> children,
                        boolean contentExpanded) {

    public TraceNode {
        children = children == null ? List.of() : List.copyOf(children);
    }

    // 追加内层执行节点：链节点不可变，返回新实例
    public TraceNode withChildren(List<TraceNode> values) {
        return new TraceNode(this.functionType, this.functionInfo, values, this.contentExpanded);
    }
}
