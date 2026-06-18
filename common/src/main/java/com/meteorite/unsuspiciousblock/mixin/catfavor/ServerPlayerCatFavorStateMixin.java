package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.state.CatFavorStateHolder;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在玩家重生/复制实体时保留猫之恩惠状态。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerCatFavorStateMixin {

    // 在玩家实体恢复时复制猫之恩惠状态（保证死亡重生后恩惠值保留，含死亡扣减）
    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void unsuspiciousblock$copyCatFavorState(ServerPlayer oldPlayer, boolean alive, CallbackInfo ci) {
        if (oldPlayer instanceof CatFavorStateHolder holder && this instanceof CatFavorStateHolder self) {
            self.unsuspiciousblock$getCatFavorState().copyFrom(holder.unsuspiciousblock$getCatFavorState());
        }
    }
}
