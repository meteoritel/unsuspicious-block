package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.cat.adapter.ICatEventAdapter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.animal.Cat;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * NeoForge 平台猫之手事件适配器——注册玩家死亡、杀猫、击打猫、玩家所属驯服猫死亡、登录、玩家 tick 事件并路由到 CatFavorManager。
 * 喂食/驯服行为由 common 中的 mixin 直接处理，不在此注册。
 */
public class NeoForgeCatEventAdapter implements ICatEventAdapter {

    @Override
    public void registerListeners() {
        NeoForge.EVENT_BUS.register(this);
    }

    // 实体死亡：区分玩家死亡、玩家杀猫、玩家所属驯服猫死亡
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CatFavorManager.onPlayerDeath(player);
        } else if (event.getEntity() instanceof Cat cat) {
            // 玩家杀死猫：扣减杀手恩惠
            if (event.getSource().getEntity() instanceof ServerPlayer killer) {
                CatFavorManager.onKillCat(killer);
            }
            // 玩家所属驯服猫死亡：扣减主人恩惠（与杀猫独立，可叠加）
            if (cat.getOwner() instanceof ServerPlayer owner) {
                CatFavorManager.onOwnCatDeath(owner);
            }
        }
    }

    // 实体即将受伤：检测玩家击打猫（每次伤害扣减恩惠，不阻止伤害）
    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Cat
                && event.getSource().getEntity() instanceof ServerPlayer player) {
            CatFavorManager.onHitCat(player);
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

    // 玩家右键村民/流浪商人：在原版打开交易 GUI 之前应用古国往礼折扣
    // 事件在 villager 自身交互逻辑之前触发，修改 offers 后原版会发送修改后的 offers 给客户端
    @SubscribeEvent
    public void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer
                && event.getTarget() instanceof AbstractVillager villager) {
            CatPassiveAbilities.tryApplyTradeDiscount(serverPlayer, villager);
        }
    }
}
