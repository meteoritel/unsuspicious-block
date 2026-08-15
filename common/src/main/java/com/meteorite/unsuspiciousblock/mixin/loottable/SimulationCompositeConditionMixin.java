package com.meteorite.unsuspiciousblock.mixin.loottable;

import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationCompositeConditionAccess;
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** 只读暴露组合条件子项，供静态分析建立条件树。 */
@Mixin(CompositeLootItemCondition.class)
public abstract class SimulationCompositeConditionMixin implements SimulationCompositeConditionAccess {
    @Shadow @Final protected List<LootItemCondition> terms;

    @Override
    public List<LootItemCondition> unsuspiciousblock$getTerms() {
        return this.terms;
    }
}
