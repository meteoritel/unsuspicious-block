package com.meteorite.unsuspiciousblock.core.mixin.container;

import com.meteorite.unsuspiciousblock.core.enchantment.EnchantedBookRoller;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 鉴定普通取出时实际交付的栈。 */
@Mixin(SmithingMenu.class)
public abstract class SmithingMenuMixin {
    @Inject(method = "onTake", at = @At("HEAD"))
    private void unsuspiciousblock$revealEnchantments(Player player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && EnchantedBookRoller.isPending(stack)) {
            EnchantedBookRoller.reveal(stack, serverPlayer);
        }
    }
}
