package com.meteorite.unsuspiciousblock.inventory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

import java.util.Set;

/**
 * 玩家背包存在 diff 状态持有者接口——由 mixin 附加到 {@link Player}，
 * 供 {@link InventoryPresenceRegistry} 读写每玩家每 tick 的 trigger 物品集合。
 * 状态为运行时临时数据，不持久化，随 Player 对象生命周期自动清理。
 */
public interface PlayerPresenceStateHolder {
    // 返回当前在背包（含饰品栏/容器）的 trigger 物品集合（transient，不持久化）
    Set<Item> unsuspiciousblock$getPresentTriggerItems();
}
