package com.meteorite.unsuspiciousblock.core.mixin.interaction;

import com.meteorite.unsuspiciousblock.core.pan.PanningSession;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原版使用状态终点释放淘洗会话，包括换物和长按结束。 */
@Mixin(LivingEntity.class)
public abstract class PanningUseMixin {
    @Inject(method = "stopUsingItem", at = @At("TAIL"))
    private void unsuspiciousblock$stopPanning(CallbackInfo callback) {
        if ((Object) this instanceof ServerPlayer player) {
            PanningSession.stop(player);
        }
    }
}
