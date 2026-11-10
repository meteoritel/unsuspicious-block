package com.meteorite.unsuspiciousblock.core.blockentity;

import com.meteorite.unsuspiciousblock.core.journal.tracking.ContainerLootTracking;
import com.meteorite.unsuspiciousblock.core.journal.tracking.PendingJournal;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

public interface TrackedContainerLootState extends Container, PendingJournalHost {
    ContainerLootTracking unsuspiciousblock$tracking();

    default @Nullable ResourceLocation unsuspiciousblock$getTrackedLootTableName() {
        return unsuspiciousblock$tracking().table();
    }

    default Map<String, Integer> unsuspiciousblock$getTrackedLootCounts() {
        return unsuspiciousblock$tracking().remaining();
    }

    default void unsuspiciousblock$setTrackedLoot(ResourceLocation tableId, Map<String, Integer> counts) {
        unsuspiciousblock$tracking().track(tableId, counts);
    }

    default void unsuspiciousblock$reconcileTrackedLoot() { unsuspiciousblock$tracking().reconcile(this); }
    default PendingJournal unsuspiciousblock$pendingJournal() { return unsuspiciousblock$tracking().pending(); }
    default @Nullable UUID unsuspiciousblock$getTrackedPlayerUuid() { return unsuspiciousblock$tracking().owner(); }
    default void unsuspiciousblock$setTrackedPlayerUuid(@Nullable UUID uuid) { unsuspiciousblock$tracking().owner(uuid); }

    default boolean unsuspiciousblock$isTrackingExpired(long now, long timeoutTicks) {
        var entry = unsuspiciousblock$pendingJournal().current();
        return entry != null && now - entry.createdGameTime() > timeoutTicks;
    }

    default void unsuspiciousblock$clearAllTrackingState() { unsuspiciousblock$tracking().clear(); }
    default void unsuspiciousblock$writeTrackedLootData(CompoundTag tag) { unsuspiciousblock$tracking().write(tag); }
    default void unsuspiciousblock$readTrackedLootData(CompoundTag tag) { unsuspiciousblock$tracking().read(tag); }

    default boolean unsuspiciousblock$hasTrackedLoot() {
        return unsuspiciousblock$getTrackedLootTableName() != null && !unsuspiciousblock$getTrackedLootCounts().isEmpty();
    }

    default Map<String, Integer> unsuspiciousblock$collectContainerItemCounts(Iterable<LootResultSignature> candidates) {
        return ContainerLootTracking.collect(this, candidates);
    }
}
