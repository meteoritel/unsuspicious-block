package com.meteorite.unsuspiciousblock.core.inventory;

import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** 玩家实时携带查询与首个修改，包含饰品栏及便携容器第一层。 */
public final class CarriedItems {
    private CarriedItems() {}

    // 实时查询：物品是否在玩家背包（原版背包 + 饰品栏 + 便携容器第一层）
    public static boolean isPresent(Player player, Item item) {
        if (item == null) {
            return false;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) {
                return true;
            }
            if (stack.getItem() instanceof PortableContainer pc
                    && pc.getContents(stack).anyMatch(s -> s.getItem() == item)) {
                return true;
            }
        }
        // 饰品栏：遍历所有已装备物品栈，扫描便携容器第一层物品
        for (ItemStack stack : Services.ACCESSORY.streamEquippedStacks(player).toList()) {
            if (stack.getItem() == item) {
                return true;
            }
            if (stack.getItem() instanceof PortableContainer pc
                    && pc.getContents(stack).anyMatch(s -> s.getItem() == item)) {
                return true;
            }
        }
        return false;
    }

    // 查询个人携带范围内是否存在满足条件的物品栈。
    public static boolean containsMatching(Player player, Predicate<ItemStack> predicate) {
        Inventory inventory = player.getInventory();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            if (matchesCarriedStack(inventory.getItem(index), predicate)) {
                return true;
            }
        }
        return Services.ACCESSORY.streamEquippedStacks(player)
                .anyMatch(stack -> matchesCarriedStack(stack, predicate));
    }

    // 修改个人携带范围内首个满足条件的物品栈，包含便携容器第一层。
    public static boolean mutateFirst(Player player, Predicate<ItemStack> predicate,
                                      Consumer<ItemStack> mutator) {
        Inventory inventory = player.getInventory();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            if (mutateCarriedStack(inventory.getItem(index), predicate, mutator)) {
                return true;
            }
        }
        for (ItemStack stack : Services.ACCESSORY.streamEquippedStacks(player).toList()) {
            if (mutateCarriedStack(stack, predicate, mutator)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesCarriedStack(ItemStack stack, Predicate<ItemStack> predicate) {
        if (stack.isEmpty()) {
            return false;
        }
        if (predicate.test(stack)) {
            return true;
        }
        return stack.getItem() instanceof PortableContainer container
                && container.getContents(stack).anyMatch(predicate);
    }

    private static boolean mutateCarriedStack(ItemStack stack, Predicate<ItemStack> predicate,
                                              Consumer<ItemStack> mutator) {
        if (stack.isEmpty()) {
            return false;
        }
        if (predicate.test(stack)) {
            mutator.accept(stack);
            return true;
        }
        return stack.getItem() instanceof PortableContainer container
                && container.mutateFirst(stack, predicate, mutator);
    }

}
