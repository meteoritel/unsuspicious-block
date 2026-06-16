package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 猫之九命（恩惠=100）——拦截致死伤害，判定在不死图腾之后。
 * 注入 LivingEntity.checkTotemDeathProtection 的返回处：若图腾已触发则不处理；
 * 否则对满足条件的玩家执行九命复活并令该方法返回 true 以阻止死亡。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityNineLivesMixin {

    @Inject(method = "checkTotemDeathProtection", at = @At("RETURN"), cancellable = true)
    private void unsuspiciousblock$nineLives(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        // 图腾已生效则不再触发九命
        if (cir.getReturnValueZ()) {
            return;
        }
        if ((Object) this instanceof ServerPlayer player && CatPassiveAbilities.canTriggerNineLives(player)) {
            CatPassiveAbilities.triggerNineLives(player);
            cir.setReturnValue(true);
        }
    }
}
