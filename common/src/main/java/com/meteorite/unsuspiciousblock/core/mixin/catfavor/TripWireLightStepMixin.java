package com.meteorite.unsuspiciousblock.core.mixin.catfavor;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.cat.CatPassiveAbilities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.TripWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 让绊线忽略拥有轻步的玩家。 */
@Mixin(TripWireBlock.class)
public abstract class TripWireLightStepMixin {

    @WrapOperation(method = "checkPressed", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;isIgnoringBlockTriggers()Z"))
    private boolean unsuspiciousblock$filterLightStep(Entity entity, Operation<Boolean> original) {
        return original.call(entity) || entity instanceof Player player
                && CatPassiveAbilities.hasLightStepPressurePlateIgnored(player);
    }
}
