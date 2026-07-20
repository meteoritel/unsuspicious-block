package com.meteorite.unsuspiciousblock.inventory;

import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxQuickInteraction;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * NeoForge 平台背包存在触发适配器——注册玩家 tick 与下线事件，
 * 驱动 {@link InventoryPresenceRegistry} 的 diff 调度与状态清理。
 * <p>
 * 参照 {@code NeoForgeCatEventAdapter} 模式：NeoForge 的 {@link PlayerTickEvent.Post}
 * 按玩家触发，无需手动遍历在线列表。
 */
public final class NeoForgeInventoryPresenceAdapter {

    private NeoForgeInventoryPresenceAdapter() {}

    // 注册到 NeoForge 事件总线
    public static void register() {
        NeoForge.EVENT_BUS.register(new NeoForgeInventoryPresenceAdapter());
    }

    // 玩家每 tick：驱动 trigger diff 评估（事件按玩家触发）
    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            InventoryPresenceRegistry.serverTick(player);
        }
    }

    // 玩家下线：清理 diff 状态
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            InventoryPresenceRegistry.clearPlayer(player);
            SpecimenBoxQuickInteraction.clearPlayer(player);
        }
    }
}
