package com.meteorite.unsuspiciousblock.mixin.loottable;

import com.meteorite.unsuspiciousblock.loottable.simulation.LootSimulationScope;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.TimeCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 在模拟作用域内把时间条件转交给 SimulationProfile。 */
@Mixin(TimeCheck.class)
public abstract class SimulationTimeConditionMixin {
    @Inject(method = "test(Lnet/minecraft/world/level/storage/loot/LootContext;)Z", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$applySimulationProfile(LootContext context, CallbackInfoReturnable<Boolean> cir) {
        Boolean result = LootSimulationScope.overrideResult((LootItemCondition) (Object) this);
        if (result != null) cir.setReturnValue(result);
    }
}
