package com.meteorite.unsuspiciousblock.inventory;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric 平台背包存在触发适配器——注册服务端 tick 与玩家下线事件，
 * 驱动 {@link InventoryPresenceRegistry} 的 diff 调度与状态清理。
 * <p>
 * 参照 {@code FabricCatEventAdapter} 模式：Fabric 无按玩家 tick 事件，
 * 只能在 {@code END_SERVER_TICK} 中遍历在线玩家。
 */
public final class FabricInventoryPresenceAdapter {

    private FabricInventoryPresenceAdapter() {}

    // 注册事件监听：服务端 tick 驱动 diff，玩家下线清理状态
    public static void register() {
        // 服务端每 tick：驱动每个在线玩家的 trigger diff 评估
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                InventoryPresenceRegistry.serverTick(player);
            }
        });
        // 玩家下线：清理 diff 状态（mixin 字段随 Player 对象回收，此处显式清理保险）
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                InventoryPresenceRegistry.clearPlayer(handler.player));
    }
}
