package com.meteorite.unsuspiciousblock.inventory;

import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 统一背包存在查询与 trigger 调度器。
 * <p>
 * 核心职责：
 * <ol>
 *   <li>{@link #isPresent}：实时查询物品是否在玩家背包（原版 41 槽 + 饰品栏 + 便携容器递归），
 *       供猫之手、猫之眼、考古笔记等物品的检测逻辑统一调用</li>
 *   <li>{@link #serverTick}：每 tick 扫描注册了 trigger 的物品，diff 后触发 onEnter/onLeave/onTick</li>
 *   <li>{@link #clearPlayer}：玩家下线时清理 diff 状态</li>
 * </ol>
 * <p>
 * 设计说明——查询统一、调度分层：猫之手等依赖动态 favor 阈值的物品不注册 trigger，
 * 保持原有每 tick 评估逻辑，仅把背包检测委托给 {@link #isPresent}；
 * trigger 接口留给未来静态状态的物品（如便携容器内的物品）使用。
 */
public final class InventoryPresenceRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryPresenceRegistry.class);

    private InventoryPresenceRegistry() {}

    // trigger 注册表：Item → 回调
    private static final Map<Item, InventoryPresenceTrigger> TRIGGERS = new HashMap<>();

    // 实时查询：物品是否在玩家背包（原版 41 槽 + 饰品栏 + 容器递归）
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
        // 饰品栏：遍历所有已装备物品栈，递归扫描便携容器内物品
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

    // 注册 trigger：物品 → 回调
    public static void register(Item item, InventoryPresenceTrigger trigger) {
        TRIGGERS.put(item, trigger);
    }

    // 每 tick 调度：扫描注册物品 + diff + 触发 onEnter/onLeave + 对在背包的 trigger 调 onTick
    public static void serverTick(ServerPlayer player) {
        if (TRIGGERS.isEmpty()) {
            return;
        }
        Set<Item> present = scanPresent(player);
        Set<Item> previous = getPresentSet(player);

        // onEnter：本 tick 有、上 tick 没有
        for (Item item : present) {
            if (!previous.contains(item)) {
                TRIGGERS.get(item).onEnter(player);
            }
        }
        // onLeave：上 tick 有、本 tick 没有
        for (Item item : previous) {
            if (!present.contains(item)) {
                TRIGGERS.get(item).onLeave(player);
            }
        }
        // onTick：本 tick 在背包的所有 trigger
        for (Item item : present) {
            TRIGGERS.get(item).onTick(player);
        }

        // 更新 diff 状态
        previous.clear();
        previous.addAll(present);
    }

    // 玩家下线清理 diff 状态
    public static void clearPlayer(ServerPlayer player) {
        getPresentSet(player).clear();
    }

    // 一次扫描收集所有在背包（含饰品栏/容器）的 trigger 物品
    private static Set<Item> scanPresent(ServerPlayer player) {
        Set<Item> present = new HashSet<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            Item it = stack.getItem();
            if (TRIGGERS.containsKey(it)) {
                present.add(it);
            }
            if (it instanceof PortableContainer pc) {
                pc.getContents(stack).forEach(s -> {
                    Item inner = s.getItem();
                    if (TRIGGERS.containsKey(inner)) {
                        present.add(inner);
                    }
                });
            }
        }
        // 饰品栏：遍历所有已装备物品栈，递归扫描便携容器中的 trigger 物品
        for (ItemStack stack : Services.ACCESSORY.streamEquippedStacks(player).toList()) {
            Item it = stack.getItem();
            if (TRIGGERS.containsKey(it)) {
                present.add(it);
            }
            if (it instanceof PortableContainer pc) {
                pc.getContents(stack).forEach(s -> {
                    Item inner = s.getItem();
                    if (TRIGGERS.containsKey(inner)) {
                        present.add(inner);
                    }
                });
            }
        }
        return present;
    }

    // 从玩家 mixin 字段获取 diff 状态集合
    private static Set<Item> getPresentSet(ServerPlayer player) {
        if (player instanceof PlayerPresenceStateHolder holder) {
            return holder.unsuspiciousblock$getPresentTriggerItems();
        }
        // mixin 未注册时的兜底（不应发生）：返回临时集合，diff 机制失效但不崩溃
        LOGGER.warn("PlayerPresenceStateHolder mixin 未应用到 {}，背包存在 diff 机制将失效", player.getName().getString());
        return new HashSet<>();
    }
}
