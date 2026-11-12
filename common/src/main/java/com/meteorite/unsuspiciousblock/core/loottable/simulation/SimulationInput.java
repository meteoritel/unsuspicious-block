package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 单次模拟输入：已勾选的环境条件 ID 列表（空表代表基准场景）与工具/幸运/附魔参数。
 */
public record SimulationInput(List<String> enabledConditions, ScenarioParams params) {
    public SimulationInput {
        Objects.requireNonNull(enabledConditions, "enabledConditions");
        Objects.requireNonNull(params, "params");
        enabledConditions = canonicalize(enabledConditions);
    }

    /** 是否为基准场景（未勾选任何环境条件）。 */
    public boolean isBaseline() {
        return enabledConditions.isEmpty();
    }

    /** 该输入对应的稳定场景段落（{@code baseline} 或 {@code cond:<16位hex>}）。 */
    public String scenarioKey() {
        return SimulationInputKey.scenarioSegment(enabledConditions);
    }

    /** 缓存与网络共用的完整规范输入键。 */
    public String key() {
        return SimulationInputKey.of(enabledConditions, params);
    }

    /** 规范化已勾选条件：去重、过滤空白并按字典序排列。 */
    public static List<String> canonicalize(List<String> conditions) {
        if (conditions.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(conditions.size());
        for (String condition : conditions) {
            if (condition != null && !condition.isBlank()) {
                unique.add(condition);
            }
        }
        return unique.stream().sorted(Comparator.naturalOrder()).toList();
    }
}
