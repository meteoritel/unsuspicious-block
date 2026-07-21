package com.meteorite.unsuspiciousblock.mixin.compat.lootr;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import noobanidus.mods.lootr.common.data.LootrInventory;
import noobanidus.mods.lootr.common.data.LootrSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在 LootrSavedData 反序列化完成后恢复每玩家库存的本模组追踪状态。
 */
@Mixin(value = LootrSavedData.class, remap = false)
public abstract class LootrSavedDataMixin {
    // Lootr 构造 LootrInventory 时只传递物品列表，因此在 load 返回后按 UUID 补读追踪 NBT
    @Inject(method = "load", at = @At("RETURN"))
    private static void unsuspiciousblock$loadTrackedInventoryData(
            CompoundTag tag, HolderLookup.Provider registries,
            CallbackInfoReturnable<LootrSavedData> cir) {
        LootrSavedData data = cir.getReturnValue();
        ListTag inventories = tag.getList("inventories", Tag.TAG_COMPOUND);
        for (Tag rawEntry : inventories) {
            if (!(rawEntry instanceof CompoundTag entry)
                    || !entry.hasUUID("uuid")
                    || !entry.contains("chest", Tag.TAG_COMPOUND)) {
                continue;
            }

            LootrInventory inventory = data.getInventory(entry.getUUID("uuid"));
            if (inventory instanceof TrackedContainerLootState trackedContainer) {
                trackedContainer.unsuspiciousblock$readTrackedLootData(entry.getCompound("chest"));
            }
        }
    }
}
