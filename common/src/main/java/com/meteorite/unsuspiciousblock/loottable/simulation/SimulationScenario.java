package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/**
 * 一组自洽条件下的战利品模拟输入，以及该场景适用的解析期物品签名。
 *
 * @param baseline 是否为**基准场景**——条件全部不成立的场景（决策 2）。
 *                 网格只展示基准场景的数字，其余场景只在场景 Tab 与 tooltip 里出现。
 *                 某个表若因专门分支额外派生了场景（原版 fishing 的注入场景），
 *                 基准标记随派生一起保留，因此"基准"始终能唯一指认。
 */
public record SimulationScenario(String key, SimulationProfile profile,
                                 List<LootConditionInfo> assumptions,
                                 Set<String> applicableSignatures,
                                 Set<ResourceLocation> applicableChildTables,
                                 boolean baseline) {
    public SimulationScenario {
        assumptions = List.copyOf(assumptions);
        applicableSignatures = Set.copyOf(applicableSignatures);
        applicableChildTables = Set.copyOf(applicableChildTables);
    }

    /** 非基准场景的便利构造。 */
    public SimulationScenario(String key, SimulationProfile profile,
                              List<LootConditionInfo> assumptions,
                              Set<String> applicableSignatures,
                              Set<ResourceLocation> applicableChildTables) {
        this(key, profile, assumptions, applicableSignatures, applicableChildTables, false);
    }
}
