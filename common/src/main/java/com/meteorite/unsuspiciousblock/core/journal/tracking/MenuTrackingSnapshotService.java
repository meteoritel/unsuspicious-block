package com.meteorite.unsuspiciousblock.core.journal.tracking;

import com.meteorite.unsuspiciousblock.core.loottable.ServerLootTableConfigManager;
import com.meteorite.unsuspiciousblock.core.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultMatcher;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultPreviewCache;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 容器菜单追踪快照服务。
 * 实现"打开容器前快照 → 打开容器后核对"的两阶段追踪模式：
 * 点击菜单前捕获玩家背包与光标中相关物品的计数，点击/关闭后通过增量结算真正进入背包的战利品。
 */
public final class MenuTrackingSnapshotService {
    private MenuTrackingSnapshotService() {
    }

    /** 单次服务端点击所见的 Lootr 库存及玩家物品；点击完成后即弃用。 */
    public record GeneratedInventoryClick(
            List<InventoryBeforeClick> inventories, List<LootResultSignature> signatures,
            Map<String, Integer> playerCounts) {
    }

    /** 同一份库存点击前的签名数量。 */
    public record InventoryBeforeClick(GeneratedLootInventory inventory, Map<String, Integer> counts) {
    }

    /** 只为当前菜单中仍分配给玩家的库存记录来源槽位。 */
    public static @Nullable GeneratedInventoryClick captureGeneratedInventories(
            ServerPlayer player, Collection<Container> roots, ItemStack carried) {
        List<InventoryBeforeClick> inventories = new ArrayList<>();
        LinkedHashMap<String, LootResultSignature> signatures = new LinkedHashMap<>();
        for (Container root : roots) {
            if (!(root instanceof GeneratedLootInventory inventory)
                    || !inventory.unsuspiciousblock$isCurrentInventory(player)) continue;
            InventoryLootTracking tracking = inventory.unsuspiciousblock$inventoryLoot();
            if (tracking.remaining().isEmpty()) continue;
            tracking.reconcileMenu(inventory, player.serverLevel().getGameTime());
            List<LootResultSignature> candidates = ArchaeologyLootRuntimeTracker.toSignatures(tracking.remaining());
            if (candidates.isEmpty()) continue;
            inventories.add(new InventoryBeforeClick(inventory, ContainerLootTracking.collect(inventory, candidates)));
            for (LootResultSignature candidate : candidates) signatures.putIfAbsent(candidate.toStoredKey(), candidate);
        }
        if (inventories.isEmpty()) return null;
        List<LootResultSignature> candidates = List.copyOf(signatures.values());
        return new GeneratedInventoryClick(inventories, candidates, capturePlayerPossession(player, carried, candidates));
    }

    /** 用同一次点击的来源减量和玩家增量确认领取；余量由库存再次限额。 */
    public static void settleGeneratedInventories(ServerPlayer player, GeneratedInventoryClick click, ItemStack carried) {
        Map<String, Integer> playerGain = capturePlayerPossession(player, carried, click.signatures());
        playerGain.replaceAll((signature, count) -> Math.max(0, count - click.playerCounts().getOrDefault(signature, 0)));
        long gameTime = player.serverLevel().getGameTime();
        for (InventoryBeforeClick before : click.inventories()) {
            GeneratedLootInventory inventory = before.inventory();
            if (!inventory.unsuspiciousblock$isCurrentInventory(player)) continue;
            InventoryLootTracking tracking = inventory.unsuspiciousblock$inventoryLoot();
            List<LootResultSignature> candidates = ArchaeologyLootRuntimeTracker.toSignatures(before.counts());
            Map<String, Integer> after = ContainerLootTracking.collect(inventory, candidates);
            Map<String, Integer> acquired = new LinkedHashMap<>();
            before.counts().forEach((signature, count) -> {
                int removed = count - after.getOrDefault(signature, 0);
                int taken = Math.min(removed, playerGain.getOrDefault(signature, 0));
                taken = Math.min(taken, tracking.remaining().getOrDefault(signature, 0));
                if (taken > 0) {
                    acquired.put(signature, taken);
                    playerGain.merge(signature, -taken, Integer::sum);
                }
            });
            if (!acquired.isEmpty()) tracking.acquire(player, acquired);
            tracking.reconcileMenu(inventory, gameTime);
        }
    }

    private static Map<String, Integer> capturePlayerPossession(
            ServerPlayer player, ItemStack carried, List<LootResultSignature> signatures) {
        Map<String, Integer> counts = capturePlayerInventoryCounts(player.getInventory(), signatures);
        resolveItemCounts(carried, signatures).forEach((signature, count) -> counts.merge(signature, count, Integer::sum));
        return counts;
    }

    /** 关闭菜单只校准仍归该玩家或队伍的实际库存。 */
    public static void reconcileGeneratedInventories(ServerPlayer player, Collection<Container> roots) {
        long gameTime = player.serverLevel().getGameTime();
        for (Container root : roots) {
            if (root instanceof GeneratedLootInventory inventory && inventory.unsuspiciousblock$isCurrentInventory(player)) {
                inventory.unsuspiciousblock$inventoryLoot().reconcileMenu(inventory, gameTime);
            }
        }
    }

    @Nullable
    // 捕获容器菜单操作前的快照，同时记录光标上的追踪物品
    // 仅收集当前玩家拥有的追踪容器，超时的容器先 flush
    public static MenuTrackingSnapshot captureMenuTrackingSnapshot(ServerPlayer player, Collection<Container> rootContainers, ItemStack carriedItem) {
        long gameTime = player.serverLevel().getGameTime();
        long timeoutTicks = ServerLootTableConfigManager.snapshot().settings().timeoutTicks();

        List<TrackedContainerLootState> trackedContainers = ContainerTrackingService.collectOwnTrackedContainers(player, rootContainers, gameTime, timeoutTicks);
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

        Map<String, Integer> beforeCarriedCounts = resolveItemCounts(carriedItem, trackedSignatures.values());
        return new MenuTrackingSnapshot(trackedContainers,
                capturePlayerInventoryCounts(player.getInventory(), trackedSignatures.values()),
                beforeCarriedCounts);
    }

    // 应用容器菜单快照核对，同时检查光标物品，返回 true 表示有更新被应用
    public static boolean applyMenuTrackingSnapshot(ServerPlayer player, MenuTrackingSnapshot snapshot, ItemStack afterCarriedItem) {
        List<LootResultSignature> signatures = new ArrayList<>();
        for (String signatureKey : snapshot.beforeInventoryCounts().keySet()) {
            LootResultSignature signature = LootResultSignature.fromStoredKey(signatureKey);
            if (signature != null) {
                signatures.add(signature);
            }
        }

        // 计算背包增量
        Map<String, Integer> afterInventoryCounts = capturePlayerInventoryCounts(player.getInventory(), signatures);
        // 计算光标增量：afterCarried - beforeCarried
        Map<String, Integer> afterCarriedCounts = resolveItemCounts(afterCarriedItem, signatures);

        LinkedHashMap<TrackedContainerLootState, ContainerLootUpdate> updates = new LinkedHashMap<>();
        // 遍历所有追踪签名，计算（背包增量 + 光标增量）
        LinkedHashMap<String, Integer> allSignatureKeys = new LinkedHashMap<>();
        for (LootResultSignature sig : signatures) {
            allSignatureKeys.put(sig.toStoredKey(), 0);
        }
        for (String signatureKey : allSignatureKeys.keySet()) {
            int beforeInv = snapshot.beforeInventoryCounts().getOrDefault(signatureKey, 0);
            int afterInv = afterInventoryCounts.getOrDefault(signatureKey, 0);
            int invDelta = afterInv - beforeInv;

            int beforeCarried = snapshot.beforeCarriedCounts().getOrDefault(signatureKey, 0);
            int afterCarried = afterCarriedCounts.getOrDefault(signatureKey, 0);
            int carriedDelta = afterCarried - beforeCarried;

            int totalDelta = invDelta + carriedDelta;
            if (totalDelta <= 0) {
                continue;
            }

            int remaining = totalDelta;
            for (TrackedContainerLootState trackedContainer : snapshot.trackedContainers()) {
                ResourceLocation tableId = trackedContainer.unsuspiciousblock$getTrackedLootTableName();
                if (tableId == null) {
                    continue;
                }

                int consumed = Math.min(remaining, trackedContainer.unsuspiciousblock$getTrackedLootCounts()
                        .getOrDefault(signatureKey, 0));
                if (consumed <= 0) {
                    continue;
                }

                ContainerLootUpdate update = updates.computeIfAbsent(trackedContainer,
                        ignored -> new ContainerLootUpdate(tableId));
                update.actualLoot().merge(signatureKey, consumed, Integer::sum);
                remaining -= consumed;
                if (remaining <= 0) {
                    break;
                }
            }
        }

        if (updates.isEmpty()) {
            return false;
        }

        long gameTime = player.serverLevel().getGameTime();
        long dayTime = player.serverLevel().getDayTime();
        for (Map.Entry<TrackedContainerLootState, ContainerLootUpdate> entry : updates.entrySet()) {
            TrackedContainerLootState trackedContainer = entry.getKey();
            ContainerLootUpdate update = entry.getValue();
            trackedContainer.unsuspiciousblock$tracking().acquire(
                    player, update.tableId(), update.actualLoot(), gameTime, dayTime);
            if (!trackedContainer.unsuspiciousblock$hasTrackedLoot()) {
                // 追踪已全部结算，清除容器的完整追踪状态
                trackedContainer.unsuspiciousblock$clearAllTrackingState();
            }
        }
        return true;
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

            LootResultSignature signature = LootResultMatcher.resolve(
                    stack, candidates, LootResultPreviewCache.PROVIDER);
            if (signature == null) {
                continue;
            }
            itemCounts.merge(signature.toStoredKey(), stack.getCount(), Integer::sum);
        }
        return itemCounts;
    }

    // 解析单个物品栈在追踪签名中的匹配计数
    private static Map<String, Integer> resolveItemCounts(ItemStack stack, Collection<LootResultSignature> trackedSignatures) {
        if (stack.isEmpty() || trackedSignatures.isEmpty()) {
            return Map.of();
        }
        List<LootResultSignature> candidates = new ArrayList<>(trackedSignatures);
        LootResultSignature signature = LootResultMatcher.resolve(
                stack, candidates, LootResultPreviewCache.PROVIDER);
        if (signature == null) {
            return Map.of();
        }
        return Map.of(signature.toStoredKey(), stack.getCount());
    }

    private record ContainerLootUpdate(ResourceLocation tableId,
                                       LinkedHashMap<String, Integer> actualLoot) {
        private ContainerLootUpdate(ResourceLocation tableId) {
            this(tableId, new LinkedHashMap<>());
        }
    }
}
