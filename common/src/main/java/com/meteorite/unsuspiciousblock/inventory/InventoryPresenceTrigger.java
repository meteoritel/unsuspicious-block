package com.meteorite.unsuspiciousblock.inventory;

import net.minecraft.server.level.ServerPlayer;

/**
 * 背包存在触发回调——物品注册后，由 {@link InventoryPresenceRegistry} 在物品
 * 进入/离开玩家背包（含饰品栏、便携容器递归）时调用对应方法。
 * <p>
 * 三种回调覆盖不同场景：
 * <ul>
 *   <li>{@link #onEnter}：物品进入背包的瞬时事件，适合加属性修饰符等一次性应用</li>
 *   <li>{@link #onLeave}：物品离开背包的瞬时事件，适合清理 onEnter 应用过的状态</li>
 *   <li>{@link #onTick}：物品在背包时每 tick 调用，适合依赖动态条件的状态（如阈值、环境）</li>
 * </ul>
 * 默认空实现，物品按需覆盖。未注册 trigger 的物品（如猫之手、猫之眼）仅用
 * {@link InventoryPresenceRegistry#isPresent} 实时查询，不参与 diff 调度。
 */
public interface InventoryPresenceTrigger {
    // 物品进入玩家背包时调用（瞬时）
    default void onEnter(ServerPlayer player) {}

    // 物品离开玩家背包时调用（瞬时，用于清理）
    default void onLeave(ServerPlayer player) {}

    // 物品在背包时每 tick 调用（用于动态条件评估）
    default void onTick(ServerPlayer player) {}
}
