package com.meteorite.unsuspiciousblock.platform.services;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.stream.Stream;

/**
 * 饰品栏查询 SPI--平台中立接口。
 * 由各平台实现：NeoForge 端走 Curios API，Fabric 端走 Trinkets API。
 * 当对应饰品模组未安装时，实现应让所有查询返回 false / 空流。
 */
public interface IAccessoryHelper {

    // 判断玩家是否在饰品栏中装备了考古笔记；未安装饰品模组时返回 false
    boolean isJournalEquipped(Player player);

    // 判断玩家饰品栏是否装备了指定物品；未安装饰品模组时返回 false
    boolean isPresent(Player player, Item item);

    // 返回饰品栏所有已装备物品栈流，供 InventoryPresenceRegistry 统一递归扫描（含便携容器）
    Stream<ItemStack> streamEquippedStacks(Player player);
}
