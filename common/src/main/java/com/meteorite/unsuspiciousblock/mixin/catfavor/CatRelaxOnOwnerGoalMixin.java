package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatFavorAction;
import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.cat.CatGiftService;
import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入原版猫的「主人睡觉时上床相伴」AI（Cat$CatRelaxOnOwnerGoal）：
 * - 开始相伴时累积「与猫一同入睡」恩惠；
 * - 赠送晨礼时触发「古国往礼」（恩惠≥90 时生成幽灵猫送更丰厚礼物）。
 */
@Mixin(targets = "net.minecraft.world.entity.animal.Cat$CatRelaxOnOwnerGoal")
public abstract class CatRelaxOnOwnerGoalMixin {

    @Shadow
    private Player ownerPlayer;

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private Cat cat;

    // 猫开始上床相伴主人入睡：累积恩惠；若恩惠已封顶则额外增加一条九命
    @Inject(method = "start", at = @At("TAIL"))
    private void unsuspiciousblock$onStartRelax(CallbackInfo ci) {
        if (this.ownerPlayer instanceof ServerPlayer serverPlayer) {
            CatFavorManager.tryAccumulate(serverPlayer, CatFavorAction.SLEEP_WITH_CAT);
            // 恩惠封顶后与猫入睡增加一条命（上限 9）
            CatPassiveAbilities.tryAddLifeOnSleep(serverPlayer);
        }
    }

    // 猫赠送晨礼：满足条件时叠加幽灵猫的丰厚礼物
    @Inject(method = "giveMorningGift", at = @At("HEAD"))
    private void unsuspiciousblock$onMorningGift(CallbackInfo ci) {
        if (this.ownerPlayer instanceof ServerPlayer serverPlayer) {
            CatGiftService.tryGhostGift(serverPlayer, this.cat);
        }
    }
}
