package com.meteorite.unsuspiciousblock.mixin.catfavor;

import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在玩家喂食猫（野猫或驯服的猫）时累积猫之恩惠。
 * Cat.usePlayerItem 在原版处理喂食/治疗/繁殖时消耗手中食物，作为喂食判定入口。
 */
@Mixin(Cat.class)
public abstract class CatFeedMixin {

    // 延迟结算喂食；若同次交互成功驯服，驯服入口会取消该奖励。
    @Inject(method = "usePlayerItem", at = @At("HEAD"))
    private void unsuspiciousblock$onFeed(Player player, InteractionHand hand, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            CatFavorManager.queueFeedReward(serverPlayer);
        }
    }
}
