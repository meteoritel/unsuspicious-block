package com.meteorite.unsuspiciousblock.platform.services;

import net.minecraft.world.entity.player.Player;

/**
 * 饰品栏查询 SPI——平台中立接口。
 * 由各平台实现：NeoForge 端走 Curios API，Fabric 端走 Trinkets API。
 * 当对应饰品模组未安装时，实现应让所有查询返回 false。
 */
public interface IAccessoryHelper {

    // 判断玩家是否在饰品栏中装备了考古手册；未安装饰品模组时返回 false
    boolean isJournalEquipped(Player player);
}
