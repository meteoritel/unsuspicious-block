package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatFavorAction;
import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在玩家成功驯服一只猫时累积猫之恩惠。
 * TamableAnimal.tame 是所有可驯服动物的驯服入口，此处仅对 Cat 生效。
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalTameMixin {

    // 驯服完成时累积恩惠（仅猫，服务端权威，由 Manager 校验持有猫之手与冷却）
    @Inject(method = "tame", at = @At("TAIL"))
    private void unsuspiciousblock$onTame(Player player, CallbackInfo ci) {
        if ((Object) this instanceof Cat && player instanceof ServerPlayer serverPlayer) {
            CatFavorManager.tryAccumulate(serverPlayer, CatFavorAction.TAME_CAT);
        }
    }
}
