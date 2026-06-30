package com.meteorite.unsuspiciousblock.enchantment.reveal;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 附魔揭示注册表与查询入口。
 * 集中管理 {@link IEnchantmentRevealCondition}，供服务端在 {@link EnchantmentMenu#slotsChanged}
 * 后判断是否需要将完整候选列表下发客户端。
 * <p>
 * 完整候选列表由服务端权威计算（直接调用 vanilla {@code EnchantmentMenu#getEnchantmentList}），
 * 通过 S2C payload 同步到客户端，客户端仅负责展示，不再自行复刻算法，
 * 以避免客户端 registryAccess 与服务端不一致导致的列表差异。
 */
public final class EnchantmentRevealManager {

    private EnchantmentRevealManager() {}

    // 触发条件集合：任一满足即揭示
    private static final List<IEnchantmentRevealCondition> conditions = new ArrayList<>();

    /** 添加一个揭示触发条件；任一条件返回 true 即触发揭示 */
    public static synchronized void registerCondition(IEnchantmentRevealCondition condition) {
        if (condition != null) {
            conditions.add(condition);
        }
    }

    /** 查询当前是否应当揭示完整候选列表（服务端调用，player 为 ServerPlayer） */
    public static boolean shouldReveal(Player player, EnchantmentMenu menu, ItemStack target) {
        if (player == null || menu == null || target == null || target.isEmpty()) {
            return false;
        }
        List<IEnchantmentRevealCondition> snapshot;
        synchronized (EnchantmentRevealManager.class) {
            if (conditions.isEmpty()) {
                return false;
            }
            snapshot = new ArrayList<>(conditions);
        }
        for (IEnchantmentRevealCondition c : snapshot) {
            if (c.shouldReveal(player, menu, target)) {
                return true;
            }
        }
        return false;
    }
}
