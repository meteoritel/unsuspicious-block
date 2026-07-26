package com.meteorite.unsuspiciousblock.cat;

import com.meteorite.unsuspiciousblock.cat.adapter.ICatEventAdapter;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Cat;

/**
 * Fabric 平台猫族关系事件适配器——将猫伤害、猫死亡、登录与服务端 tick 路由到公共逻辑。
 */
public class FabricCatEventAdapter implements ICatEventAdapter {

    @Override
    public void registerListeners() {
        // 猫死亡：击杀者总计扣 50；主人非亲手击杀时额外扣 10。
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof Cat cat) {
                ServerPlayer killer = damageSource.getEntity() instanceof ServerPlayer player ? player : null;
                if (killer != null) {
                    CatFavorManager.onKillCat(killer, cat);
                }
                if (cat.getOwner() instanceof ServerPlayer owner
                        && (killer == null || !owner.getUUID().equals(killer.getUUID()))) {
                    CatFavorManager.onOwnCatDeath(owner);
                }
            }
        });

        // 有效猫伤害先扣 5，若同次伤害致死则由死亡入口补足至总计 50。
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof Cat cat
                    && source.getEntity() instanceof ServerPlayer player) {
                CatFavorManager.onHitCat(player, cat);
            }
            return true;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CatFavorManager.sync(handler.player));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                CatFavorManager.serverTick(player);
                CatPassiveAbilities.serverTick(player);
            }
        });
    }
}
