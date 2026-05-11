package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.journal.LootResultMatcher;
import com.meteorite.unsuspiciousblock.journal.LootResultSignature;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface TrackedContainerLootState extends Container {
    @Nullable
    ResourceLocation unsuspiciousblock$getTrackedLootTableName();

    Map<String, Integer> unsuspiciousblock$getTrackedLootCounts();

    void unsuspiciousblock$setTrackedLoot(ResourceLocation tableId, Map<String, Integer> itemCounts);

    int unsuspiciousblock$consumeTrackedLoot(String signatureKey, int amount);

    void unsuspiciousblock$reconcileTrackedLoot();

    void unsuspiciousblock$clearTrackedLoot();

    void unsuspiciousblock$writeTrackedLootData(CompoundTag tag);

    void unsuspiciousblock$readTrackedLootData(CompoundTag tag);

    default boolean unsuspiciousblock$hasTrackedLoot() {
        return this.unsuspiciousblock$getTrackedLootTableName() != null
                && !this.unsuspiciousblock$getTrackedLootCounts().isEmpty();
    }

    default Map<String, Integer> unsuspiciousblock$collectContainerItemCounts(Iterable<LootResultSignature> candidates) {
        List<LootResultSignature> candidateList = new ArrayList<>();
        for (LootResultSignature candidate : candidates) {
            candidateList.add(candidate);
        }

        LinkedHashMap<String, Integer> itemCounts = new LinkedHashMap<>();
        for (int slot = 0; slot < this.getContainerSize(); slot++) {
            ItemStack stack = this.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            LootResultSignature signature = LootResultMatcher.resolve(stack, candidateList);
            if (signature == null) {
                continue;
            }
            itemCounts.merge(signature.toStoredKey(), stack.getCount(), Integer::sum);
        }
        return itemCounts;
    }
}
