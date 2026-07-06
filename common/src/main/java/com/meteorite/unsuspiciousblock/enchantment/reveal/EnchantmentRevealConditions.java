package com.meteorite.unsuspiciousblock.enchantment.reveal;

import com.meteorite.unsuspiciousblock.inventory.InventoryPresenceRegistry;
import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.world.item.ItemStack;

/**
 * 附魔揭示内建触发条件注册。
 * 由 {@code UnsuspiciousBlockCommon.init()} 在服务端初始化时调用一次，
 * 条件在服务端评估（玩家背包状态服务端可见），决定是否将完整候选列表下发客户端。
 */
public final class EnchantmentRevealConditions {

    private EnchantmentRevealConditions() {}

    private static boolean registered = false;

    /** 注册内建揭示条件；幂等，重复调用安全 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        // 条件：玩家背包中持有「猫之瞳」时，揭示完整附魔候选列表
        EnchantmentRevealManager.registerCondition((player, menu, target) -> {
            if (ModItems.EYE_OF_CAT == null) {
                return false;
            }
            return InventoryPresenceRegistry.isPresent(player, ModItems.EYE_OF_CAT);
        });
    }
}
