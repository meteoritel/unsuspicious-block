package com.meteorite.unsuspiciousblock.core.mixin.aura;

import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 自然引信在原版 AI tick 之前推进，因此在引信读取方向之前复核目标。 */
@Mixin(Creeper.class)
public abstract class CreeperFuseAuraMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void unsuspiciousblock$releaseAuraTargetBeforeFuse(CallbackInfo ci) {
        AuraRepulsion.clearProtectedTarget((Creeper) (Object) this);
    }
}
