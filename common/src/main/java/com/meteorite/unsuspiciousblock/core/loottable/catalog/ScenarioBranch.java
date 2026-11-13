package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGate;

import java.util.List;

/**
 * 服务端派生的单条完整获取路径；环境条件与装备/幸运/随机门槛分离展示，
 * 是否满足由 {@link com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioConditionEvaluator} 实时判定。
 */
public record ScenarioBranch(String kind, String target, List<LootConditionInfo> conditions,
                             List<LootConditionInfo> requirements, LuckGate luck,
                             boolean uncertain, String injectionSource, String injectionMode, String reference,
                             boolean recommendable, String sourceKey) {
    public ScenarioBranch(String kind, String target, List<LootConditionInfo> conditions,
                          List<LootConditionInfo> requirements, LuckGate luck, boolean uncertain,
                          String source, String mode) {
        this(kind, target, conditions, requirements, luck, uncertain, source, mode, "", false, source);
    }

    public ScenarioBranch withReference(String value, boolean supported) {
        return new ScenarioBranch(kind, target, conditions, requirements, luck, uncertain,
                injectionSource, injectionMode, value, supported, sourceKey);
    }

    public ScenarioBranch {
        conditions = List.copyOf(conditions);
        requirements = List.copyOf(requirements);
    }

    /**
     * 构造普通非注入分支。
     */
    public ScenarioBranch(String kind, String target, List<LootConditionInfo> conditions,
                          List<LootConditionInfo> requirements, LuckGate luck, boolean uncertain) {
        this(kind, target, conditions, requirements, luck, uncertain, "", "");
    }
}
