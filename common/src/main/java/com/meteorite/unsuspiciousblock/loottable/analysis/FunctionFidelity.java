package com.meteorite.unsuspiciousblock.loottable.analysis;

/**
 * 函数静态描述的**保真度**——回答"这条规则被我们理解到什么程度"。
 * <p>
 * 与运行时捕获完整度（{@code FunctionCaptureState}）是**两个独立的轴**，不可互相推断：
 * 描述完整不代表执行被完整捕获，反之亦然（规划 D12）。
 * <p>
 * 动态数值（如随机等级提供器）是**合法描述**，不因此降级为 {@link #PARTIAL}：
 * 只要规则本身被如实表达，它就是 {@link #FULL}。
 */
public enum FunctionFidelity {
    /** 类型、参数语义、条件与子函数都被如实表达；动态部分以动态表达记录。 */
    FULL,
    /** 识别出类型，但部分字段无法取得或只能给出近似表达。 */
    PARTIAL,
    /** 完全无法描述（未知 mod 函数、Codec 解码失败等）。 */
    UNRESOLVED;
}
