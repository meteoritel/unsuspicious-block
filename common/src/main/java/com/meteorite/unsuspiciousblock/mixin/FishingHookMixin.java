package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.enchantment.fishing.FishingLootOverrideService;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Unique
    private ItemStack unsuspiciousblock$fishingRod = ItemStack.EMPTY;

    @Inject(method = "retrieve", at = @At("HEAD"))
    private void unsuspiciousblock$captureFishingRod(ItemStack fishingRod, CallbackInfoReturnable<Integer> cir) {
        this.unsuspiciousblock$fishingRod = fishingRod;
    }

    // 替换原版战利品表为本模组的战利品表
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
        if (!(fishingHook.level() instanceof ServerLevel serverLevel) || this.unsuspiciousblock$fishingRod.isEmpty()) {
            return originalLootTable;
        }
        return FishingLootOverrideService.resolveLootTable(serverLevel, fishingHook,
                this.unsuspiciousblock$fishingRod, originalLootTable);
    }

    @Inject(method = "retrieve", at = @At("RETURN"))
    private void unsuspiciousblock$clearFishingRod(ItemStack fishingRod, CallbackInfoReturnable<Integer> cir) {
        this.unsuspiciousblock$fishingRod = ItemStack.EMPTY;
    }
}
