package com.meteorite.unsuspiciousblock.core.mixin.compat.lootr;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootCapture.CaptureHandle;
import com.meteorite.unsuspiciousblock.core.plugin.lootr.LootrTrackingBridge;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.resources.ResourceKey;
import noobanidus.mods.lootr.common.api.data.DefaultBrushableLootFiller;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 在 Lootr 可疑方块的每玩家战利品生成边界接入考古追踪。
 */
@Mixin(DefaultBrushableLootFiller.class)
public abstract class DefaultBrushableLootFillerMixin {
    // 仅在 Lootr 可疑方块实际生成物品期间激活上下文
    @WrapOperation(
            method = "unpackLootTable", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V",
                    remap = true)
    )
    private void unsuspiciousblock$scopeLootRoll(
            LootTable lootTable, LootContext contextData, Consumer<ItemStack> output,
            Operation<Void> original,
            @Local(argsOnly = true) ILootrInfoProvider provider, @Local(argsOnly = true) Player player,
            @Local(name = "lootTable") ResourceKey<LootTable> table,
            @Share("lootCapture") LocalRef<CaptureHandle> captureRef) {
        CaptureHandle capture = LootrTrackingBridge.prepare(provider, player, table, LootSourceType.ARCHAEOLOGY);
        if (capture == null) {
            original.call(lootTable, contextData, output);
            return;
        }
        captureRef.set(capture);
        capture.roll(() -> original.call(lootTable, contextData, output));
    }

    // Filter 可以替换或移除原始抽取结果，写槽后才有权威结果。
    @Inject(method = "unpackLootTable", at = @At("RETURN"), remap = false)
    private void unsuspiciousblock$commitTracking(
            ILootrInfoProvider provider, Player player, Container inventory, CallbackInfo ci,
            @Share("lootCapture") LocalRef<CaptureHandle> captureRef) {
        try (var capture = captureRef.get()) {
            LootrTrackingBridge.complete(capture, inventory);
        }
    }
}
