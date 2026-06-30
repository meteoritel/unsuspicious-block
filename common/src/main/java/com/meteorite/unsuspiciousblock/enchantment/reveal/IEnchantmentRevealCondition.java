package com.meteorite.unsuspiciousblock.enchantment.reveal;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 附魔揭示触发条件 SPI——下一步将添加具体条件实现。
 * 当 {@link #shouldReveal} 返回 true 时，附魔台 tooltip 显示完整候选列表而非单条随机条目。
 * 默认无注册条件，全部返回 false，行为与原版一致。
 */
public interface IEnchantmentRevealCondition {

    /**
     * 判断当前是否应当揭示完整附魔候选列表。
     *
     * @param player 客户端玩家
     * @param menu   当前附魔台菜单
     * @param target 待附魔物品（slot 0 中的物品）
     * @return true 表示揭示完整列表
     */
    boolean shouldReveal(Player player, EnchantmentMenu menu, ItemStack target);
}
