package com.meteorite.unsuspiciousblock.mixin.container;

import com.meteorite.unsuspiciousblock.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.enchantment.EnchantedBookRoller;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/***
 * 锻造台取出结果时为"待鉴定附魔书"赋予随机附魔。
 * 合成结果是一本带 custom_data 标记、无附魔的附魔书（预览不可见附魔）；
 * 仅在服务端玩家取出时才真正写入随机附魔并清除标记。
 */
@Mixin(SmithingMenu.class)
public abstract class SmithingMenuMixin {

    // 待鉴定标记 key，与配方 result 的 custom_data 一致
    @Unique
    private static final String PENDING_ENCHANT_KEY = "unsuspiciousblock_pending_enchant";

    // 在结果被取走时（HEAD），检测标记并赋予随机附魔
    @Inject(method = "onTake", at = @At("HEAD"))
    private void unsuspiciousblock$revealEnchantments(Player player, ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!stack.is(Items.ENCHANTED_BOOK)) {
            return;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(PENDING_ENCHANT_KEY)) {
            return;
        }
        EnchantedBookRoller.roll(stack, serverPlayer.serverLevel().getRandom(),
                serverPlayer.serverLevel().registryAccess());
        stack.remove(DataComponents.CUSTOM_DATA);
        // 首次在锻造台合成出随机附魔书时授予「Ancient Scholarship」成就
        AchievementManager.grantIfNotAlready(serverPlayer, ModAchievements.ANCIENT_SCHOLARSHIP);
    }
}
