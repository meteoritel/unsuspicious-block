package com.meteorite.unsuspiciousblock.blockentity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public interface TrackedContainerLootState extends Container {
    @Nullable
    ResourceLocation unsuspiciousblock$getTrackedLootTableName();

    Map<ResourceLocation, Integer> unsuspiciousblock$getTrackedLootCounts();

    void unsuspiciousblock$setTrackedLoot(ResourceLocation tableId, Map<ResourceLocation, Integer> itemCounts);

    int unsuspiciousblock$consumeTrackedLoot(ResourceLocation itemId, int amount);

    void unsuspiciousblock$reconcileTrackedLoot();

    void unsuspiciousblock$clearTrackedLoot();

    void unsuspiciousblock$writeTrackedLootData(CompoundTag tag);

    void unsuspiciousblock$readTrackedLootData(CompoundTag tag);

    default boolean unsuspiciousblock$hasTrackedLoot() {
        return this.unsuspiciousblock$getTrackedLootTableName() != null
                && !this.unsuspiciousblock$getTrackedLootCounts().isEmpty();
    }

    default Map<ResourceLocation, Integer> unsuspiciousblock$collectContainerItemCounts() {
        LinkedHashMap<ResourceLocation, Integer> itemCounts = new LinkedHashMap<>();
        for (int slot = 0; slot < this.getContainerSize(); slot++) {
            ItemStack stack = this.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            itemCounts.merge(itemId, stack.getCount(), Integer::sum);
        }
        return itemCounts;
    }
}
