package com.meteorite.unsuspiciousblock.journal;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArchaeologyLootRuntimeTracker {
    private ArchaeologyLootRuntimeTracker() {
    }

    public static void unlockResolvedLoot(ServerPlayer player, ResourceLocation tableId, ItemStack stack) {
        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }

        boolean changed = state.unlockTable(tableId);
        if (!stack.isEmpty()) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            changed |= state.unlockItem(tableId, itemId);
        }
        if (changed) {
            ArchaeologyJournalNetwork.syncState(player);
        }
    }

    public static void unlockResolvedLoot(ServerPlayer player, ResourceLocation tableId, Map<ResourceLocation, Integer> itemCounts) {
        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }

        boolean changed = state.unlockTable(tableId);
        for (Map.Entry<ResourceLocation, Integer> entry : itemCounts.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            changed |= state.unlockItem(tableId, entry.getKey());
        }
        if (changed) {
            ArchaeologyJournalNetwork.syncState(player);
        }
    }

    public static void recordItemAcquired(ServerPlayer player, ResourceLocation tableId, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        recordItemAcquired(player, tableId, BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount());
    }

    public static void recordItemAcquired(ServerPlayer player, ResourceLocation tableId, ResourceLocation itemId, int count) {
        if (count <= 0) {
            return;
        }

        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }

        if (state.recordItemAcquired(tableId, itemId, count)) {
            ArchaeologyJournalNetwork.syncState(player);
        }
    }

    public static void onContainerLootResolved(ServerPlayer player,
                                               TrackedContainerLootState container,
                                               ResourceLocation tableId,
                                               Map<ResourceLocation, Integer> itemCounts) {
        unlockResolvedLoot(player, tableId, itemCounts);
        if (itemCounts.isEmpty()) {
            container.unsuspiciousblock$clearTrackedLoot();
            return;
        }
        container.unsuspiciousblock$setTrackedLoot(tableId, itemCounts);
    }

    @Nullable
    public static MenuTrackingSnapshot captureMenuTrackingSnapshot(ServerPlayer player, Collection<Container> rootContainers) {
        List<TrackedContainerLootState> trackedContainers = collectTrackedContainers(rootContainers);
        if (trackedContainers.isEmpty()) {
            return null;
        }

        LinkedHashSet<ResourceLocation> trackedItemIds = new LinkedHashSet<>();
        for (TrackedContainerLootState trackedContainer : trackedContainers) {
            for (Map.Entry<ResourceLocation, Integer> entry : trackedContainer.unsuspiciousblock$getTrackedLootCounts().entrySet()) {
                if (entry.getValue() > 0) {
                    trackedItemIds.add(entry.getKey());
                }
            }
        }
        if (trackedItemIds.isEmpty()) {
            return null;
        }

        return new MenuTrackingSnapshot(trackedContainers, capturePlayerInventoryCounts(player.getInventory(), trackedItemIds));
    }

    public static void applyMenuTrackingSnapshot(ServerPlayer player, MenuTrackingSnapshot snapshot) {
        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }

        Map<ResourceLocation, Integer> afterCounts = capturePlayerInventoryCounts(player.getInventory(), snapshot.beforeInventoryCounts().keySet());
        boolean changed = false;
        for (Map.Entry<ResourceLocation, Integer> entry : afterCounts.entrySet()) {
            ResourceLocation itemId = entry.getKey();
            int before = snapshot.beforeInventoryCounts().getOrDefault(itemId, 0);
            int delta = entry.getValue() - before;
            if (delta <= 0) {
                continue;
            }

            int remaining = delta;
            for (TrackedContainerLootState trackedContainer : snapshot.trackedContainers()) {
                ResourceLocation tableId = trackedContainer.unsuspiciousblock$getTrackedLootTableName();
                if (tableId == null) {
                    continue;
                }

                int consumed = trackedContainer.unsuspiciousblock$consumeTrackedLoot(itemId, remaining);
                if (consumed <= 0) {
                    continue;
                }

                changed |= state.recordItemAcquired(tableId, itemId, consumed);
                remaining -= consumed;
                if (remaining <= 0) {
                    break;
                }
            }
        }

        if (changed) {
            ArchaeologyJournalNetwork.syncState(player);
        }
    }

    public static void reconcileTrackedContainers(Collection<Container> rootContainers) {
        for (TrackedContainerLootState trackedContainer : collectTrackedContainers(rootContainers)) {
            trackedContainer.unsuspiciousblock$reconcileTrackedLoot();
        }
    }

    private static List<TrackedContainerLootState> collectTrackedContainers(Collection<Container> rootContainers) {
        List<TrackedContainerLootState> trackedContainers = new ArrayList<>();
        Set<Container> visitedContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<TrackedContainerLootState> visitedTrackedContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Container rootContainer : rootContainers) {
            collectTrackedContainers(rootContainer, visitedContainers, visitedTrackedContainers, trackedContainers);
        }
        return trackedContainers;
    }

    private static void collectTrackedContainers(Container container,
                                                 Set<Container> visitedContainers,
                                                 Set<TrackedContainerLootState> visitedTrackedContainers,
                                                 List<TrackedContainerLootState> trackedContainers) {
        if (!visitedContainers.add(container)) {
            return;
        }

        if (container instanceof TrackedContainerLootState trackedContainer
                && trackedContainer.unsuspiciousblock$hasTrackedLoot()
                && visitedTrackedContainers.add(trackedContainer)) {
            trackedContainers.add(trackedContainer);
        }

        if (container instanceof CompoundContainerAccess access) {
            collectTrackedContainers(access.unsuspiciousblock$getFirstContainer(),
                    visitedContainers, visitedTrackedContainers, trackedContainers);
            collectTrackedContainers(access.unsuspiciousblock$getSecondContainer(),
                    visitedContainers, visitedTrackedContainers, trackedContainers);
        }
    }

    private static Map<ResourceLocation, Integer> capturePlayerInventoryCounts(Inventory inventory, Collection<ResourceLocation> trackedItemIds) {
        LinkedHashSet<ResourceLocation> trackedSet = new LinkedHashSet<>(trackedItemIds);
        LinkedHashMap<ResourceLocation, Integer> itemCounts = new LinkedHashMap<>();
        for (ResourceLocation itemId : trackedSet) {
            itemCounts.put(itemId, 0);
        }

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!trackedSet.contains(itemId)) {
                continue;
            }
            itemCounts.merge(itemId, stack.getCount(), Integer::sum);
        }
        return itemCounts;
    }

    @Nullable
    private static ArchaeologyJournalState getState(ServerPlayer player) {
        if (!(player instanceof ArchaeologyJournalStateHolder holder)) {
            return null;
        }
        return holder.unsuspiciousblock$getArchaeologyJournalState();
    }

    public record MenuTrackingSnapshot(List<TrackedContainerLootState> trackedContainers,
                                       Map<ResourceLocation, Integer> beforeInventoryCounts) {
        public MenuTrackingSnapshot {
            trackedContainers = List.copyOf(trackedContainers);
            beforeInventoryCounts = Map.copyOf(beforeInventoryCounts);
        }
    }
}
