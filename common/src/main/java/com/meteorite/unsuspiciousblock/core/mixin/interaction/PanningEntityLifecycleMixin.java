package com.meteorite.unsuspiciousblock.core.mixin.interaction;

import com.meteorite.unsuspiciousblock.core.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.core.pan.PanningSession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在实体管理器接纳淘洗点后登记，并在玩家移出维度前释放会话。 */
@Mixin(Entity.class)
public abstract class PanningEntityLifecycleMixin {
    @Inject(method = "setLevelCallback", at = @At("TAIL"))
    private void unsuspiciousblock$registerShimmer(EntityInLevelCallback callback, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (callback != EntityInLevelCallback.NULL && entity instanceof ShimmerEntity shimmer
                && shimmer.level() instanceof ServerLevel level) {
            shimmer.registerInLedger(level);
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void unsuspiciousblock$stopPanningOnRemoval(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (entity instanceof ServerPlayer player && PanningSession.of(player) != null) {
            player.stopUsingItem();
        }
    }
}
