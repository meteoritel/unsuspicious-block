package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在钓鱼收杆流程中替换原版战利品表。
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Unique
    private ItemStack unsuspiciousblock$fishingRod = ItemStack.EMPTY;

    // 在收杆开始时缓存本次使用的鱼竿
    @Inject(method = "retrieve", at = @At("HEAD"))
    private void unsuspiciousblock$captureFishingRod(ItemStack fishingRod, CallbackInfoReturnable<Integer> cir) {
        this.unsuspiciousblock$fishingRod = fishingRod;
    }

    // 在原版查询战利品表参数时改写为本模组解析出的目标表
    @ModifyArg(
            method = "retrieve",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/ReloadableServerRegistries$Holder;getLootTable(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/level/storage/loot/LootTable;"
            ),
            index = 0
    )
    private ResourceKey<LootTable> unsuspiciousblock$replaceFishingLootTable(ResourceKey<LootTable> originalLootTable) {
        FishingHook fishingHook = (FishingHook) (Object) this;
        if (!(fishingHook.level() instanceof ServerLevel serverLevel)
                || !(fishingHook.getPlayerOwner() instanceof ServerPlayer sp)) {
            return originalLootTable;
        }
        TriggerContext ctx = TriggerContext.builder(sp, serverLevel)
                .tool(this.unsuspiciousblock$fishingRod)
                .targetEntity(fishingHook)
                .build();
        return EnchantmentManager.dispatchValue(TriggerType.FISHING_LOOT_TABLE_QUERY, ctx, originalLootTable);
    }

    // 在收杆结束后清理缓存的鱼竿引用
    @Inject(method = "retrieve", at = @At("RETURN"))
    private void unsuspiciousblock$clearFishingRod(ItemStack fishingRod, CallbackInfoReturnable<Integer> cir) {
        this.unsuspiciousblock$fishingRod = ItemStack.EMPTY;
    }
}