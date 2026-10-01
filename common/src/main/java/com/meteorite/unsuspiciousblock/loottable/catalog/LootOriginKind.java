package com.meteorite.unsuspiciousblock.loottable.catalog;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一个物品结果的**来源类别**——取代"单个互斥布尔值"的来源表达（规划 §4.2 / D09）。
 * <p>
 * 同一个结果可以同时有普通静态来源与确认的模组联动来源，因此这里是集合而不是单选。
 * 关键区分：<b>动态发现不等于平台注入</b>。原版熔炼等静态未理解的变换同样会产生
 * 静态候选之外的结果，它属于 {@link #UNKNOWN_RUNTIME} 而不是 {@link #MOD_INTEGRATION}。
 */
public enum LootOriginKind {
    /** 资源 JSON 的静态获取路径。 */
    STATIC,
    /** 已确认的平台注入（自有注入执行点标记，或已有适配器明确声明）。 */
    MOD_INTEGRATION,
    /** 运行时出现、但无法确认来源（原版未理解的变换、第三方后处理、身份丢失等）。 */
    UNKNOWN_RUNTIME;

    /** 由既有 {@code injected} 兼容布尔值派生来源集合。 */
    public static Set<LootOriginKind> ofInjected(boolean injected) {
        return injected ? Set.of(MOD_INTEGRATION) : Set.of(STATIC);
    }

    // 规范化：空集合按"来源未知"处理，避免出现"没有任何来源"的条目
    public static Set<LootOriginKind> normalize(Set<LootOriginKind> kinds, boolean injected) {
        LinkedHashSet<LootOriginKind> result = new LinkedHashSet<>();
        if (kinds != null) {
            result.addAll(kinds);
        }
        if (injected) {
            result.add(MOD_INTEGRATION);
        }
        if (result.isEmpty()) {
            result.add(injected ? MOD_INTEGRATION : STATIC);
        }
        return Set.copyOf(result);
    }
}
