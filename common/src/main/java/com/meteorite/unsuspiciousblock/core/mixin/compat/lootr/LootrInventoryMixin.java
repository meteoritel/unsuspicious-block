package com.meteorite.unsuspiciousblock.core.mixin.compat.lootr;

import com.meteorite.unsuspiciousblock.core.journal.tracking.GeneratedLootInventory;
import com.meteorite.unsuspiciousblock.core.journal.tracking.InventoryLootTracking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import noobanidus.mods.lootr.common.api.data.ILootrSavedData;
import noobanidus.mods.lootr.common.data.LootrInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


/** 将生成来源与余量随 Lootr 实际库存保存。 */
@Mixin(value = LootrInventory.class, remap = false)
public abstract class LootrInventoryMixin implements GeneratedLootInventory {

    @Unique
    private final InventoryLootTracking unsuspiciousblock$tracking =
            new InventoryLootTracking(this::setChanged);

    @Override
    public InventoryLootTracking unsuspiciousblock$inventoryLoot() {
        return this.unsuspiciousblock$tracking;
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public boolean unsuspiciousblock$isCurrentInventory(ServerPlayer player) {
        LootrInventory inventory = (LootrInventory) (Object) this;
        return inventory.getInfo() instanceof ILootrSavedData data && data.getInventory(player) == inventory;
    }

    // Lootr 保存库存物品后，将本模组追踪字段写入同一个 CompoundTag
    @Inject(method = "saveToTag", at = @At("RETURN"))
    private void unsuspiciousblock$appendTrackingData(CallbackInfoReturnable<CompoundTag> cir) {
        this.unsuspiciousblock$tracking.write(cir.getReturnValue());
    }
}
