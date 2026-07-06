package com.meteorite.unsuspiciousblock.inventory;

import net.minecraft.world.item.ItemStack;

import java.util.stream.Stream;

/**
 * 便携容器扩展点——物品实现此接口后，{@link InventoryPresenceRegistry#isPresent}
 * 会递归扫描容器内物品，使"容器在背包 → 容器内物品效果触发"成立。
 * <p>
 * 当前无实现，预留给未来便携容器物品。{@link #getContents} 返回 {@link Stream}
 * 以便短路匹配（找到即停止），且无实现时无需分配空集合。
 */
public interface PortableContainer {
    // 返回容器内物品流；实现应从容器 ItemStack 的数据组件中读取内容
    Stream<ItemStack> getContents(ItemStack container);
}
