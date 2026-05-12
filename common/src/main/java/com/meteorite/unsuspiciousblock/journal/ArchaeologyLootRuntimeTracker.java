package com.meteorite.unsuspiciousblock.journal;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.LootResultMatcher;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
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
        LootResultSignature signature = resolveSignature(tableId, stack);
        if (signature != null) {
            changed |= state.unlockItem(tableId, signature);
        }
        if (changed) {
            ArchaeologyJournalNetwork.syncState(player);
        }
    }

    public static void unlockResolvedLoot(ServerPlayer player, ResourceLocation tableId, Map<String, Integer> itemCounts) {
        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }

        boolean changed = state.unlockTable(tableId);
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            LootResultSignature signature = LootResultSignature.fromStoredKey(entry.getKey());
            if (signature == null) {
                continue;
            }
            changed |= state.unlockItem(tableId, signature);
        }
        if (changed) {
            ArchaeologyJournalNetwork.syncState(player);
        }
    }

    /**
     * 一站式处理：解锁战利品 + 若为首次解锁则记录日志。
     * 替代多处重复的 holder 提取与首次解锁判断逻辑。
     */
    public static void onLootDiscovered(ServerPlayer player, ResourceLocation tableId,
                                         ItemStack loot, long gameTime, long dayTime) {
        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }
        boolean tableUnlockedBefore = state.isTableUnlocked(tableId);
        unlockResolvedLoot(player, tableId, loot);
        if (!tableUnlockedBefore) {
            ArchaeologyJournalLogCollector.recordFirstUnlock(player, tableId, gameTime, dayTime);
        }
    }

    public static void recordItemAcquired(ServerPlayer player, ResourceLocation tableId, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }

        LootResultSignature signature = resolveSignature(tableId, stack);
        if (signature == null) {
            return;
        }
        recordItemAcquired(player, tableId, signature, stack.getCount());
    }

    public static void recordItemAcquired(ServerPlayer player, ResourceLocation tableId,
                                          LootResultSignature signature, int count) {
        if (count <= 0) {
            return;
        }

        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }

        if (state.recordItemAcquired(tableId, signature, count)) {
            ArchaeologyJournalNetwork.syncState(player);
        }
    }

    public static void onContainerLootResolved(ServerPlayer player,
                                               TrackedContainerLootState container,
                                               ResourceLocation tableId,
                                               Map<String, Integer> itemCounts) {
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

        LinkedHashMap<String, LootResultSignature> trackedSignatures = new LinkedHashMap<>();
        for (TrackedContainerLootState trackedContainer : trackedContainers) {
            for (Map.Entry<String, Integer> entry : trackedContainer.unsuspiciousblock$getTrackedLootCounts().entrySet()) {
                if (entry.getValue() <= 0) {
                    continue;
                }
                LootResultSignature signature = LootResultSignature.fromStoredKey(entry.getKey());
                if (signature != null) {
                    trackedSignatures.putIfAbsent(signature.toStoredKey(), signature);
                }
            }
        }
        if (trackedSignatures.isEmpty()) {
            return null;
        }

        return new MenuTrackingSnapshot(trackedContainers,
                capturePlayerInventoryCounts(player.getInventory(), trackedSignatures.values()));
    }

    public static void applyMenuTrackingSnapshot(ServerPlayer player, MenuTrackingSnapshot snapshot) {
        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }

        List<LootResultSignature> signatures = new ArrayList<>();
        for (String signatureKey : snapshot.beforeInventoryCounts().keySet()) {
            LootResultSignature signature = LootResultSignature.fromStoredKey(signatureKey);
            if (signature != null) {
                signatures.add(signature);
            }
        }

        Map<String, Integer> afterCounts = capturePlayerInventoryCounts(player.getInventory(), signatures);
        boolean changed = false;
        for (Map.Entry<String, Integer> entry : afterCounts.entrySet()) {
            String signatureKey = entry.getKey();
            int before = snapshot.beforeInventoryCounts().getOrDefault(signatureKey, 0);
            int delta = entry.getValue() - before;
            if (delta <= 0) {
                continue;
            }

            LootResultSignature signature = LootResultSignature.fromStoredKey(signatureKey);
            if (signature == null) {
                continue;
            }

            int remaining = delta;
            for (TrackedContainerLootState trackedContainer : snapshot.trackedContainers()) {
                ResourceLocation tableId = trackedContainer.unsuspiciousblock$getTrackedLootTableName();
                if (tableId == null) {
                    continue;
                }

                int consumed = trackedContainer.unsuspiciousblock$consumeTrackedLoot(signatureKey, remaining);
                if (consumed <= 0) {
                    continue;
                }

                changed |= state.recordItemAcquired(tableId, signature, consumed);
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

    @Nullable
    public static LootResultSignature resolveSignature(ResourceLocation tableId, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }

        TableDefinition table = ArchaeologyJournalServerCatalog.getCatalog().get(tableId);
        if (table == null || table.items().isEmpty()) {
            return LootResultSignature.plain(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }

        List<LootResultSignature> candidates = new ArrayList<>();
        for (ItemDefinition item : table.items()) {
            candidates.add(item.signature());
        }
        LootResultSignature matched = LootResultMatcher.resolve(stack, candidates);
        if (matched != null) {
            return matched;
        }
        return null;
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

    private static Map<String, Integer> capturePlayerInventoryCounts(Inventory inventory, Collection<LootResultSignature> trackedSignatures) {
        List<LootResultSignature> candidates = new ArrayList<>(trackedSignatures);
        LinkedHashMap<String, Integer> itemCounts = new LinkedHashMap<>();
        for (LootResultSignature signature : candidates) {
            itemCounts.put(signature.toStoredKey(), 0);
        }

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            LootResultSignature signature = LootResultMatcher.resolve(stack, candidates);
            if (signature == null) {
                continue;
            }
            itemCounts.merge(signature.toStoredKey(), stack.getCount(), Integer::sum);
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
                                       Map<String, Integer> beforeInventoryCounts) {
        public MenuTrackingSnapshot {
            trackedContainers = List.copyOf(trackedContainers);
            beforeInventoryCounts = Map.copyOf(beforeInventoryCounts);
        }
    }
}
