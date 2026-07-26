package com.meteorite.unsuspiciousblock.mixin.compat.lootr;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.plugin.lootr.LootrTrackingBridge;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
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
    // 进入 Lootr 自定义 filler 时准备根追踪上下文
    @Inject(method = "unpackLootTable", at = @At("HEAD"))
    private void unsuspiciousblock$beginTracking(
            ILootrInfoProvider provider, Player player, Container inventory, CallbackInfo ci,
            @Share("lootrTrackingSession") LocalRef<LootrTrackingBridge.Session> sessionRef) {
        LootrTrackingBridge.Session session = LootrTrackingBridge.prepare(
                provider, player, LootSourceType.LOOT_CONTAINER);
        sessionRef.set(session);
    }

    // 仅在 Lootr 实际填充战利品期间激活上下文
    @WrapOperation(
            method = "unpackLootTable",
            at = @At(value = "INVOKE",
                    target = "Lnoobanidus/mods/lootr/common/api/data/DefaultLootFiller;fill(Lnoobanidus/mods/lootr/common/api/data/ILootrInfoProvider;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/storage/loot/LootTable;Lnet/minecraft/world/Container;Lnet/minecraft/world/level/storage/loot/LootParams;J)V")
    )
    private void unsuspiciousblock$scopeLootRoll(
            DefaultLootFiller filler, ILootrInfoProvider provider, Player player,
            ResourceKey<LootTable> tableKey, LootTable lootTable, Container inventory,
            LootParams lootParams, long seed, Operation<Void> original,
            @Share("lootrTrackingSession") LocalRef<LootrTrackingBridge.Session> sessionRef) {
        try (var ignored = LootrTrackingBridge.openScope(sessionRef.get())) {
            original.call(filler, provider, player, tableKey, lootTable, inventory, lootParams, seed);
        }
    }

    // Lootr 已填充库存后提交最终物品结果
    @Inject(method = "unpackLootTable", at = @At("RETURN"))
    private void unsuspiciousblock$commitTracking(
            ILootrInfoProvider provider, Player player, Container inventory, CallbackInfo ci,
            @Share("lootrTrackingSession") LocalRef<LootrTrackingBridge.Session> sessionRef) {
        LootrTrackingBridge.Session session = sessionRef.get();
        LootrTrackingBridge.commit(session, inventory);
    }
}
