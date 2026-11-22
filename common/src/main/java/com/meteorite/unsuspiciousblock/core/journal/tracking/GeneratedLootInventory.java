package com.meteorite.unsuspiciousblock.core.journal.tracking;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;

/** 由外部模组分配给玩家或队伍的实际库存；领取者由每次操作确定。 */
public interface GeneratedLootInventory extends Container {
    /** 返回随该库存保存的生成来源与余量。 */
    InventoryLootTracking unsuspiciousblock$inventoryLoot();

    /** 判断这份库存当前是否仍分配给该玩家。 */
    boolean unsuspiciousblock$isCurrentInventory(ServerPlayer player);
}
