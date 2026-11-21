package com.meteorite.unsuspiciousblock.core.mixin.catfavor;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meteorite.unsuspiciousblock.core.entity.ai.aura.AuraRepulsion;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 在失眠与生成随机判定之前，跳过受猫之威慑或区域光环保护的玩家。 */
@Mixin(PhantomSpawner.class)
public abstract class PhantomSpawnerMixin {

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
    private boolean unsuspiciousblock$skipProtectedPlayer(ServerPlayer player, Operation<Boolean> original) {
        return original.call(player) || AuraRepulsion.hasPhantomSpawnProtection(player);
    }
}
