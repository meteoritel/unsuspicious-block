package com.meteorite.unsuspiciousblock.enchantment;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.adapter.IEnchantmentEventAdapter;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
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
                    .tool(sp.getMainHandItem())
                    .build();
            EnchantmentManager.dispatch(TriggerType.BLOCK_BREAK, ctx);
        }
    }

    // 玩家右键绵羊剪羊毛——在 NeoForge 的 ShearsItem.interactLivingEntity 走 IShearable 路径之前触发
    // PlayerInteractEvent.EntityInteract 会对手部逐个触发（main → off），需避免双手都持剪刀时双重 dispatch：
    // 原版 useEntity 链路优先用主手，主手为剪刀时副手不会真正执行剪毛，故副手事件在主手已是剪刀时应跳过
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (!(event.getTarget() instanceof Sheep sheep) || !sheep.readyForShearing()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!stack.is(Items.SHEARS)) {
            return;
        }
        if (event.getHand() == InteractionHand.OFF_HAND && sp.getMainHandItem().is(Items.SHEARS)) {
            return;
        }
        TriggerContext ctx = TriggerContext.builder(sp, sp.serverLevel())
                .targetEntity(sheep)
                .tool(stack)
                .build();
        EnchantmentManager.dispatch(TriggerType.ENTITY_SHEAR, ctx);
    }
}
