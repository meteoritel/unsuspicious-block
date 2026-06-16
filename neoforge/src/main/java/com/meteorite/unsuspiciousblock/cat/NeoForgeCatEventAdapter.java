package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.cat.adapter.ICatEventAdapter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Cat;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * NeoForge 平台猫之手事件适配器——注册玩家死亡、杀猫、登录、玩家 tick 事件并路由到 CatFavorManager。
 * 喂食/驯服行为由 common 中的 mixin 直接处理，不在此注册。
 */
public class NeoForgeCatEventAdapter implements ICatEventAdapter {

    @Override
    public void registerListeners() {
        NeoForge.EVENT_BUS.register(this);
    }

    // 实体死亡：区分玩家死亡与玩家杀猫
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CatFavorManager.onPlayerDeath(player);
        } else if (event.getEntity() instanceof Cat
                && event.getSource().getEntity() instanceof ServerPlayer killer) {
            CatFavorManager.onKillCat(killer);
        }
    }

    // 玩家登录：初始同步恩惠值到客户端
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CatFavorManager.sync(player);
        }
    }

    // 玩家每 tick：驱动被动能力评估（仅服务端）
    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CatPassiveAbilities.serverTick(player);
        }
    }
}
