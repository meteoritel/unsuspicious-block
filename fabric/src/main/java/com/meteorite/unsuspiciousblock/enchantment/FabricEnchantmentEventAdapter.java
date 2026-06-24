package com.meteorite.unsuspiciousblock.enchantment;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.adapter.IEnchantmentEventAdapter;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Fabric 平台附魔事件适配器——注册 Fabric 事件回调，抹平平台差异后路由到 EnchantmentManager */
public class FabricEnchantmentEventAdapter implements IEnchantmentEventAdapter {

    // 本次方块破坏使用的工具——PlayerBlockBreakEvents.AFTER 触发时工具可能已碎裂导致槽位切换，
    // 故在 BEFORE 阶段（工具未消耗）缓存，AFTER 阶段读取。
    // 服务端主线程单线程触发，无需 ThreadLocal 同步。
    private ItemStack cachedBreakTool = ItemStack.EMPTY;

    @Override
    public void registerListeners() {
        // BEFORE 阶段缓存实际使用的工具——此时工具尚未消耗、槽位尚未切换
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp) {
                cachedBreakTool = sp.getMainHandItem();
            }
            return true;
        });

        // 方块破坏 → BLOCK_BREAK 触发，使用 BEFORE 阶段缓存的工具
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp) {
                TriggerContext ctx = TriggerContext.builder(sp, sp.serverLevel())
                        .pos(pos)
                        .blockState(state)
                        .tool(cachedBreakTool)
                        .build();
                cachedBreakTool = ItemStack.EMPTY;
                EnchantmentManager.dispatch(TriggerType.BLOCK_BREAK, ctx);
            }
        });
    }
}
