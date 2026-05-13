package com.meteorite.unsuspiciousblock.enchantment.archaeology;

import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 精掘附魔服务：在刷拭可疑方块时小概率翻倍战利品。 */
public final class PrecisionExcavationService {
    private static final double DOUBLE_LOOT_CHANCE = 0.10D;

    private PrecisionExcavationService() {
    }

    public static ItemStack tryApply(ServerPlayer player, ItemStack loot) {
        if (loot.isEmpty()) {
            return loot;
        }

        ItemStack brush = findPrecisionBrush(player);
        if (brush.isEmpty() || player.getRandom().nextDouble() >= DOUBLE_LOOT_CHANCE) {
            return loot;
        }

        ItemStack doubledLoot = loot.copy();
        int doubledCount = Math.min(doubledLoot.getMaxStackSize(), doubledLoot.getCount() * 2);
        doubledLoot.setCount(doubledCount);
        return doubledLoot;
    }

    private static ItemStack findPrecisionBrush(ServerPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        if (isPrecisionBrush(player, mainHand)) {
            return mainHand;
        }

        ItemStack offHand = player.getOffhandItem();
        return isPrecisionBrush(player, offHand) ? offHand : ItemStack.EMPTY;
    }

    private static boolean isPrecisionBrush(ServerPlayer player, ItemStack stack) {
        return stack.is(Items.BRUSH)
                && ModEnchantments.getEnchantmentLevel(player.registryAccess(), stack,
                ModEnchantments.PRECISION_EXCAVATION) > 0;
    }
}
