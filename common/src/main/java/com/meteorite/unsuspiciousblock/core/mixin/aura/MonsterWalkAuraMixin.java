package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 游走采样阶段跳过区内目的地，保留原版的有限候选选择。 */
@Mixin(Monster.class)
public abstract class MonsterWalkAuraMixin {
    @Inject(method = "getWalkTargetValue", at = @At("RETURN"), cancellable = true)
    private void unsuspiciousblock$excludeAuraDestination(BlockPos target, LevelReader level,
                                                        CallbackInfoReturnable<Float> cir) {
        if (!AuraRepulsion.canMoveTo((Monster) (Object) this, target)) {
            cir.setReturnValue(Float.NEGATIVE_INFINITY);
        }
    }
}
