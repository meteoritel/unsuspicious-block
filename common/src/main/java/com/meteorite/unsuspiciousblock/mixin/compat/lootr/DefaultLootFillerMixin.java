package com.meteorite.unsuspiciousblock.mixin.compat.lootr;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.plugin.lootr.LootrTrackingBridge;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import noobanidus.mods.lootr.common.api.data.DefaultLootFiller;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Lootr 普通容器的每玩家战利品生成边界接入考古笔记追踪。
 */
@SuppressWarnings("UnstableApiUsage")
@Mixin(value = DefaultLootFiller.class, remap = false)
public abstract class DefaultLootFillerMixin {
    // 进入 Lootr 自定义 filler 时压入根追踪上下文
    @Inject(method = "unpackLootTable", at = @At("HEAD"))
    private void unsuspiciousblock$beginTracking(
            ILootrInfoProvider provider, Player player, Container inventory, CallbackInfo ci,
            @Share("lootrTrackingSession") LocalRef<LootrTrackingBridge.Session> sessionRef) {
        LootrTrackingBridge.Session session = LootrTrackingBridge.prepare(
                provider, player, LootSourceType.LOOT_CONTAINER);
        sessionRef.set(session);
        LootrTrackingBridge.open(session);
    }

    // Lootr 已填充库存后先恢复上下文栈，再提交最终物品结果
    @Inject(method = "unpackLootTable", at = @At("RETURN"))
    private void unsuspiciousblock$commitTracking(
            ILootrInfoProvider provider, Player player, Container inventory, CallbackInfo ci,
            @Share("lootrTrackingSession") LocalRef<LootrTrackingBridge.Session> sessionRef) {
        LootrTrackingBridge.Session session = sessionRef.get();
        LootrTrackingBridge.close(session);
        LootrTrackingBridge.commit(session, inventory);
    }
}
