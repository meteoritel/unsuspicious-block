package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LuckGate;
import java.util.List;

/** 服务端派生的单条完整获取路径；场景可达性与装备门槛分离，不承诺必得。 */
public record ScenarioBranch(String kind, String target, List<LootConditionInfo> conditions,
                             List<LootConditionInfo> requirements, LuckGate luck,
                             List<String> activeScenes, boolean uncertain, String injectionSource, String injectionMode) {
    public ScenarioBranch {
        conditions = List.copyOf(conditions);
        requirements = List.copyOf(requirements);
        activeScenes = List.copyOf(activeScenes);
    }

    public ScenarioBranch(String kind, String target, List<LootConditionInfo> conditions,
                          List<LootConditionInfo> requirements, LuckGate luck, List<String> activeScenes, boolean uncertain) {
        this(kind, target, conditions, requirements, luck, activeScenes, uncertain, "", "");
    }
}
