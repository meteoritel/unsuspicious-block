package com.meteorite.unsuspiciousblock.platform.mixin.aura;

import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Fabric 补齐目标写入事件，将受保护的新目标转换为空目标。 */
@Mixin(Mob.class)
public abstract class MobTargetAuraMixin {
    @ModifyVariable(method = "setTarget", at = @At("HEAD"), argsOnly = true)
    private @Nullable LivingEntity unsuspiciousblock$filterAuraTarget(@Nullable LivingEntity target) {
        return AuraRepulsion.isProtectedTarget((Mob) (Object) this, target) ? null : target;
    }
}
