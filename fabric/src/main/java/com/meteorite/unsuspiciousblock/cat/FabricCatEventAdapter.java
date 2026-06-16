package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.cat.adapter.ICatEventAdapter;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Cat;

/**
 * Fabric 平台猫之手事件适配器——注册玩家死亡、杀猫、登录、服务端 tick 事件并路由到 CatFavorManager。
 * 喂食/驯服行为由 common 中的 mixin 直接处理，不在此注册。
 */
public class FabricCatEventAdapter implements ICatEventAdapter {

    @Override
    public void registerListeners() {
        // 实体死亡：区分玩家死亡与玩家杀猫
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                CatFavorManager.onPlayerDeath(player);
            } else if (entity instanceof Cat
                    && damageSource.getEntity() instanceof ServerPlayer killer) {
                CatFavorManager.onKillCat(killer);
            }
        });

        // 玩家登录：初始同步恩惠值到客户端
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CatFavorManager.sync(handler.player));

        // 服务端每 tick：驱动每个在线玩家的被动能力评估
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                CatPassiveAbilities.serverTick(player);
            }
        });
    }
}
