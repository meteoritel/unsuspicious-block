package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.loottable.LootCounts;
import com.meteorite.unsuspiciousblock.loottable.LootResultMatcher;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface TrackedContainerLootState extends Container {
    @Nullable
    ResourceLocation unsuspiciousblock$getTrackedLootTableName();

    Map<String, Integer> unsuspiciousblock$getTrackedLootCounts();

    void unsuspiciousblock$setTrackedLoot(ResourceLocation tableId, Map<String, Integer> itemCounts);

    int unsuspiciousblock$consumeTrackedLoot(String signatureKey, int amount);

    void unsuspiciousblock$reconcileTrackedLoot();

    void unsuspiciousblock$clearTrackedLoot();

    @Nullable
    ExcavationLogEntry unsuspiciousblock$getPendingJournalEntry();

    void unsuspiciousblock$setPendingJournalEntry(@Nullable ExcavationLogEntry entry);

    default void unsuspiciousblock$clearPendingJournalEntry() {
        this.unsuspiciousblock$setPendingJournalEntry(null);
    }

    /** 返回触发战利品表解析的追踪玩家 UUID */
    @Nullable
    UUID unsuspiciousblock$getTrackedPlayerUuid();

    /** 设置触发战利品表解析的追踪玩家 UUID */
    void unsuspiciousblock$setTrackedPlayerUuid(@Nullable UUID uuid);

    /** 判断当前追踪是否已超时 */
    default boolean unsuspiciousblock$isTrackingExpired(long currentGameTime, long timeoutTicks) {
        ExcavationLogEntry pendingEntry = this.unsuspiciousblock$getPendingJournalEntry();
        if (pendingEntry == null) {
            return false;
        }
        return currentGameTime - pendingEntry.createdGameTime() > timeoutTicks;
    }

    /** 清空全部追踪状态（trackedLoot + pendingJournalEntry + trackedPlayerUuid） */
    default void unsuspiciousblock$clearAllTrackingState() {
        this.unsuspiciousblock$clearTrackedLoot();
        this.unsuspiciousblock$clearPendingJournalEntry();
        this.unsuspiciousblock$setTrackedPlayerUuid(null);
    }

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
        return LootCounts.normalize(itemCounts);
    }
}
