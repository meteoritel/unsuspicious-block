package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.cat.adapter.ICatEventAdapter;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.animal.Cat;

/**
 * Fabric 平台猫之手事件适配器——注册玩家死亡、杀猫、击打猫、玩家所属驯服猫死亡、登录、服务端 tick 事件并路由到 CatFavorManager。
 * 喂食/驯服行为由 common 中的 mixin 直接处理，不在此注册。
 */
public class FabricCatEventAdapter implements ICatEventAdapter {

    @Override
    public void registerListeners() {
        // 实体死亡：区分玩家死亡、玩家杀猫、玩家所属驯服猫死亡
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                CatFavorManager.onPlayerDeath(player);
            } else if (entity instanceof Cat cat) {
                // 玩家杀死猫：扣减杀手恩惠
                if (damageSource.getEntity() instanceof ServerPlayer killer) {
                    CatFavorManager.onKillCat(killer);
                }
                // 玩家所属驯服猫死亡：扣减主人恩惠（与杀猫独立，可叠加）
                if (cat.getOwner() instanceof ServerPlayer owner) {
                    CatFavorManager.onOwnCatDeath(owner);
                }
            }
        });

        // 实体即将受伤：检测玩家击打猫（每次伤害扣减恩惠，返回 true 不阻止伤害）
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof Cat
                    && source.getEntity() instanceof ServerPlayer player) {
                CatFavorManager.onHitCat(player);
            }
            return true;
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

        // 玩家右键村民/流浪商人：在原版打开交易 GUI 之前应用古国往礼折扣
        // 返回 PASS 让原版交互逻辑继续执行，从而将修改后的 offers 发送给客户端
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide
                    && player instanceof ServerPlayer serverPlayer
                    && entity instanceof AbstractVillager villager) {
                CatPassiveAbilities.tryApplyTradeDiscount(serverPlayer, villager);
            }
            return InteractionResult.PASS;
        });
    }
}
