package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.cat.adapter.ICatEventAdapter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Cat;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * NeoForge 平台猫族关系事件适配器——将猫伤害、猫死亡、登录与玩家 tick 路由到公共逻辑。
 */
public class NeoForgeCatEventAdapter implements ICatEventAdapter {

    @Override
    public void registerListeners() {
        NeoForge.EVENT_BUS.register(this);
    }

    // 猫死亡：击杀者总计扣 50；主人非亲手击杀时额外扣 10。
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Cat cat) {
            ServerPlayer killer = event.getSource().getEntity() instanceof ServerPlayer player ? player : null;
            if (killer != null) {
                CatFavorManager.onKillCat(killer, cat);
            }
            if (cat.getOwner() instanceof ServerPlayer owner
                    && (killer == null || !owner.getUUID().equals(killer.getUUID()))) {
                CatFavorManager.onOwnCatDeath(owner);
            }
        }
    }

    // 有效猫伤害先扣 5，若同次伤害致死则由死亡入口补足至总计 50。
    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Cat cat
                && event.getSource().getEntity() instanceof ServerPlayer player) {
            CatFavorManager.onHitCat(player, cat);
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CatFavorManager.sync(player);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CatFavorManager.serverTick(player);
            CatPassiveAbilities.serverTick(player);
        }
    }
}
