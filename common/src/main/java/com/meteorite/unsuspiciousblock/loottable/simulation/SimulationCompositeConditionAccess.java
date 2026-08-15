package com.meteorite.unsuspiciousblock.loottable.simulation;

import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.util.List;

/** 为模拟条件策略暴露原版组合条件的只读子条件列表。 */
public interface SimulationCompositeConditionAccess {
    List<LootItemCondition> unsuspiciousblock$getTerms();
}
