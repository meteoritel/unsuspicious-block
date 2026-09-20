package com.meteorite.unsuspiciousblock.loottable.catalog;

/**
 * 「未知」的成因——{@link Probability.Unknown} 现在背着五种互不相同的来源，
 * 只有把原因分开，tooltip 才能如实说明"为什么这里是一个问号"（决策 36）。
 * <p>
 * 分工：{@link #UNCOVERED} 与静态可适用性有关；{@link #UNPARSED} 与规则本身不可用有关；
 * 其余三者是纯粹的"计算状态"（尚未请求 / 计算失败 / 结果被淘汰），与静态结构无关。
 */
public enum UnknownReason {
    /** 未被任何代表场景覆盖：可能需要预置列表之外的条件或输入。 */
    UNCOVERED,
    /** 规则无法解析：条件或函数引用了该表 paramSet 不允许的参数。 */
    UNPARSED,
    /** 尚未请求计算。 */
    NOT_SIMULATED,
    /** 计算失败（模拟抛异常或注册表中没有该表）；不自动重试，等下次数据包重载。 */
    SIMULATION_FAILED,
    /** 结果已被 LRU 淘汰。 */
    EVICTED
}
