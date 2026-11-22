package com.meteorite.unsuspiciousblock.core.mixin.compat.lootr;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootCapture.CaptureHandle;
import com.meteorite.unsuspiciousblock.core.plugin.lootr.LootrTrackingBridge;
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
@Mixin(value = DefaultLootFiller.class, remap = false)
public abstract class DefaultLootFillerMixin {
    @WrapOperation(method = "performFill", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/loot/LootTable;fill(Lnet/minecraft/world/Container;Lnet/minecraft/world/level/storage/loot/LootParams;J)V",
            remap = true))
    private static void unsuspiciousblock$captureInventory(
            LootTable lootTable, Container container, LootParams params, long seed, Operation<Void> original,
            @Local(argsOnly = true) ILootrInfoProvider provider, @Local(argsOnly = true) Player player,
            @Local(argsOnly = true) ResourceKey<LootTable> tableKey,
            @Share("lootCapture") LocalRef<CaptureHandle> captureRef) {
        CaptureHandle capture = LootrTrackingBridge.prepare(provider, player, tableKey, LootSourceType.LOOT_CONTAINER);
        if (capture == null) {
            original.call(lootTable, container, params, seed);
            return;
        }
        captureRef.set(capture);
        capture.roll(() -> original.call(lootTable, container, params, seed));
    }

    // Lootr 清理填充状态后再通知笔记订阅者，避免订阅者触发的抽取沿用本次 Filter 上下文。
    @Inject(method = "performFill", at = @At("RETURN"))
    private static void unsuspiciousblock$commitTracking(CallbackInfo ci,
            @Local(argsOnly = true) Container container, @Share("lootCapture") LocalRef<CaptureHandle> captureRef) {
        try (var capture = captureRef.get()) {
            LootrTrackingBridge.complete(capture, container);
        }
    }
}
