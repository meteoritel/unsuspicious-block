package com.meteorite.unsuspiciousblock.core.mixin.effect;

import com.meteorite.unsuspiciousblock.core.effect.PurrmintEffectService;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 喵步反幽匿振动入口——在原版振动监听器接收事件前过滤静音实体的移动、跳跃着陆与涉水振动，
 * 同时覆盖幽匿感测体、校频幽匿感测体与监守者。
 */
@Mixin(VibrationSystem.Listener.class)
public abstract class VibrationListenerSilenceMixin {

    @Inject(method = "handleGameEvent", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$silenceMovementVibration(ServerLevel level, Holder<GameEvent> gameEvent,
                                                            GameEvent.Context context, Vec3 pos,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (PurrmintEffectService.shouldSilenceVibration(context.sourceEntity(), gameEvent)) {
            cir.setReturnValue(false);
        }
    }
}
