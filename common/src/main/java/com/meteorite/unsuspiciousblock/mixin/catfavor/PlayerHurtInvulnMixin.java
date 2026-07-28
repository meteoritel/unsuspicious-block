package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import com.meteorite.unsuspiciousblock.cat.SwordsmanCatService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 猫之九命无敌窗口——触发后 2 秒内免疫所有伤害，虚空伤害除外。
 * 注入 Player.hurt，在 buff 存在期间取消伤害结算。
 */
@Mixin(Player.class)
public abstract class PlayerHurtInvulnMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void unsuspiciousblock$nineLivesInvuln(DamageSource source, float amount,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if ((Player) (Object) this instanceof ServerPlayer player
                && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker) {
            SwordsmanCatService.notifyOwnerHurt(player, attacker);
        }
        // 虚空伤害仍然生效，避免卡在世界外
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return;
        }
        if (CatPassiveAbilities.isNineLivesInvulnerable((Player) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
