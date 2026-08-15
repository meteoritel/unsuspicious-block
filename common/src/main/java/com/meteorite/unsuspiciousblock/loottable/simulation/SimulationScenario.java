package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;

import java.util.List;
import java.util.Set;

/**
 * 一组自洽条件下的战利品模拟输入，以及该场景适用的解析期物品签名。
 */
public record SimulationScenario(String key, SimulationProfile profile,
                                 List<LootConditionInfo> assumptions,
                                 Set<String> applicableSignatures) {
    public SimulationScenario {
        assumptions = List.copyOf(assumptions);
        applicableSignatures = Set.copyOf(applicableSignatures);
    }
}
