package com.meteorite.unsuspiciousblock.enchantment;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.adapter.IEnchantmentEventAdapter;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerPlayer;

/** Fabric 平台附魔事件适配器——注册 Fabric 事件回调，抹平平台差异后路由到 EnchantmentManager */
public class FabricEnchantmentEventAdapter implements IEnchantmentEventAdapter {

    @Override
    public void registerListeners() {
        // 方块破坏 → BLOCK_BREAK 触发
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp) {
                TriggerContext ctx = TriggerContext.builder(sp, sp.serverLevel())
                        .pos(pos)
                        .blockState(state)
                        .tool(sp.getMainHandItem())
                        .build();
                EnchantmentManager.dispatch(TriggerType.BLOCK_BREAK, ctx);
            }
        });
    }
}
