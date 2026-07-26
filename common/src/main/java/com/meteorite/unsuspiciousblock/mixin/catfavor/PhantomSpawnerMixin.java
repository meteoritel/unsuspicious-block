package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 猫之威慑——幻翼不再以该玩家为目标生成。
 * PhantomSpawner.tick 遍历所有非旁观玩家，对满足失眠条件的玩家在其上方生成幻翼。
 * 通过重定向 isSpectator() 调用，让拥有猫的陪伴的玩家也被视作"旁观者"而被跳过，
 * 从而避免幻翼以此玩家为生成目标。
 */
@Mixin(PhantomSpawner.class)
public abstract class PhantomSpawnerMixin {

    // 重定向遍历玩家时的 isSpectator() 检查：威慑生效的玩家同样被跳过。
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
    private boolean unsuspiciousblock$skipCatCompanion(ServerPlayer player) {
        if (player.isSpectator()) {
            return true;
        }
        return CatPassiveAbilities.hasPhantomDeterrence(player);
    }
}
