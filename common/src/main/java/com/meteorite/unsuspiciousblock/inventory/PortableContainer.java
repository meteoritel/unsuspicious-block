package com.meteorite.unsuspiciousblock.inventory;

import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 便携容器扩展点——物品实现此接口后，{@link InventoryPresenceRegistry#isPresent}
 * 会递归扫描容器内物品，使"容器在背包 → 容器内物品效果触发"成立。
 * <p>
 * 短路匹配（找到即停止），且无实现时无需分配空集合。
 */
public interface PortableContainer {
    // 返回容器内物品流；实现应从容器 ItemStack 的数据组件中读取内容
    Stream<ItemStack> getContents(ItemStack container);

    // 修改第一层中首个匹配物品；实现必须把修改后的内容写回父物品。
    default boolean mutateFirst(ItemStack container, Predicate<ItemStack> predicate,
                                Consumer<ItemStack> mutator) {
        return false;
    }
}
