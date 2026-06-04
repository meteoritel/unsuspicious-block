package com.meteorite.unsuspiciousblock.enchantment;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.adapter.IEnchantmentEventAdapter;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/** NeoForge 平台附魔事件适配器——注册 NeoForge 事件监听，抹平平台差异后路由到 EnchantmentManager */
public class NeoForgeEnchantmentEventAdapter implements IEnchantmentEventAdapter {

    @Override
    public void registerListeners() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer sp) {
            TriggerContext ctx = TriggerContext.builder(sp, sp.serverLevel())
                    .pos(event.getPos())
                    .blockState(event.getState())
                    .build();
            EnchantmentManager.dispatch(TriggerType.BLOCK_BREAK, ctx);
        }
    }
}
