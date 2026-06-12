package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.LootResultMatcher;
import com.meteorite.unsuspiciousblock.loottable.LootCounts;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import com.meteorite.unsuspiciousblock.journal.recording.JournalLogRecorder;
import com.meteorite.unsuspiciousblock.network.journal.JournalStateHandler;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 考古战利品运行时追踪器——服务端核心业务逻辑入口。
 * 负责：战利品解锁、日志条目创建与更新、容器物品追踪与核对。
 * 通过 captureMenuTrackingSnapshot / applyMenuTrackingSnapshot 实现
 * "打开容器前快照 → 打开容器后核对" 的两阶段追踪模式。
 */
public final class ArchaeologyLootRuntimeTracker {
    private ArchaeologyLootRuntimeTracker() {
    }

    // 将单个物品栈标记为已解锁（解析签名后处理）
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
            JournalStateHandler.syncState(player);
        }
    }

    // 将多个物品按签名批量标记为已解锁
    public static void unlockResolvedLoot(ServerPlayer player, ResourceLocation tableId, Map<String, Integer> itemCounts) {
        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }

        boolean changed = state.unlockTable(tableId);
        changed |= state.unlockItems(tableId, toSignatures(itemCounts));
        if (changed) {
            JournalStateHandler.syncState(player);
        }
    }

    // 战利品发现事件处理（单物品栈）：解锁 + 记录首次发现时间
    public static void onLootDiscovered(ServerPlayer player, ResourceLocation tableId,
                                        ItemStack loot, @Nullable LootSourceType lootSource,
                                        long gameTime, long dayTime) {
        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }
        unlockResolvedLoot(player, tableId, loot);
        JournalLogRecorder.recordFirstUnlock(player, tableId, lootSource, gameTime, dayTime);
    }

    // 战利品发现事件处理（批量物品）：解锁 + 记录首次发现时间
    public static void onLootDiscovered(ServerPlayer player, ResourceLocation tableId,
                                        Map<String, Integer> itemCounts, @Nullable LootSourceType lootSource,
                                        long gameTime, long dayTime) {
        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }
        unlockResolvedLoot(player, tableId, itemCounts);
        JournalLogRecorder.recordFirstUnlock(player, tableId, lootSource, gameTime, dayTime);
    }

    @Nullable
    // 创建待定的日志条目（从单物品栈预期战利品）
    public static ExcavationLogEntry createPendingEntry(ServerPlayer player, ResourceLocation tableId,
                                                        LootSourceType lootSource, @Nullable ResourceLocation sourceBlockId,
                                                        BlockPos pos, ItemStack expectedLoot,
                                                        long gameTime, long dayTime) {
        if (expectedLoot.isEmpty()) {
            return null;
        }
        LootResultSignature signature = resolveSignature(tableId, expectedLoot);
        if (signature == null) {
            return null;
        }
        return createPendingEntry(player, lootSource, sourceBlockId, pos,
                Map.of(signature.toStoredKey(), expectedLoot.getCount()), gameTime, dayTime);
    }

    @Nullable
    // 创建待定的日志条目（从批量预期战利品，同时解析生物群系与结构）
    public static ExcavationLogEntry createPendingEntry(ServerPlayer player, LootSourceType lootSource,
                                                        @Nullable ResourceLocation sourceBlockId, BlockPos pos,
                                                        Map<String, Integer> expectedLoot,
                                                        long gameTime, long dayTime) {
        Map<String, Integer> normalizedExpectedLoot = LootCounts.normalize(expectedLoot);
        if (normalizedExpectedLoot.isEmpty()) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        ExcavationLogEntry.ExcavationContext context = new ExcavationLogEntry.ExcavationContext(
                sourceBlockId, resolveStructureId(level, pos), resolveBiomeId(level, pos), pos);
        ExcavationLogEntry.GameTimestamp timestamp = new ExcavationLogEntry.GameTimestamp(
                Math.max(0L, gameTime), Math.max(0L, dayTime));
        return new ExcavationLogEntry(UUID.randomUUID(), lootSource, context,
                timestamp, timestamp, normalizedExpectedLoot, Map.of());
    }

    @Nullable
    // 将实际获取物品应用到待定日志条目（单物品栈版本）
    public static ExcavationLogEntry applyPendingLoot(ServerPlayer player, ResourceLocation tableId,
                                                      @Nullable ExcavationLogEntry pendingEntry,
                                                      ItemStack stack, long gameTime, long dayTime) {
        if (stack.isEmpty()) {
            return pendingEntry;
        }
        LootResultSignature signature = resolveSignature(tableId, stack);
        if (signature == null) {
            return pendingEntry;
        }
        return applyPendingLoot(player, tableId, pendingEntry,
                Map.of(signature.toStoredKey(), stack.getCount()), gameTime, dayTime);
    }

    @Nullable
    // 将实际获取物品应用到待定日志条目（批量版本），同时更新日记状态
    public static ExcavationLogEntry applyPendingLoot(ServerPlayer player, ResourceLocation tableId,
                                                      @Nullable ExcavationLogEntry pendingEntry,
                                                      Map<String, Integer> actualLoot,
                                                      long gameTime, long dayTime) {
        Map<String, Integer> normalizedActualLoot = LootCounts.normalize(actualLoot);
        if (normalizedActualLoot.isEmpty()) {
            return pendingEntry;
        }

        if (pendingEntry == null) {
            // 仅有物品获取记录但无待定条目，只更新日记进度状态
            JournalLogRecorder.recordItemsAcquired(player, tableId, toSignatureCounts(normalizedActualLoot));
            return null;
        }

        ExcavationLogEntry updatedEntry = pendingEntry.withActualLootMerged(normalizedActualLoot, gameTime, dayTime);
        JournalLogRecorder.upsertExcavationEntry(player, tableId, updatedEntry, toSignatureCounts(normalizedActualLoot));
        return updatedEntry;
    }

    // 容器战利品确认处理：解锁物品 + 创建待定日志条目 + 记录追踪状态
    public static void onContainerLootResolved(ServerPlayer player,
                                               TrackedContainerLootState container,
                                               ResourceLocation tableId,
                                               Map<String, Integer> itemCounts) {
        long gameTime = player.serverLevel().getGameTime();
        long dayTime = player.serverLevel().getDayTime();
        onLootDiscovered(player, tableId, itemCounts, LootSourceType.LOOT_CONTAINER, gameTime, dayTime);
        if (itemCounts.isEmpty()) {
            container.unsuspiciousblock$clearTrackedLoot();
            return;
        }
        BlockPos pos = resolveContainerPos(container);
        container.unsuspiciousblock$setPendingJournalEntry(createPendingEntry(player, LootSourceType.LOOT_CONTAINER,
                resolveContainerSourceBlockId(container), pos, itemCounts, gameTime, dayTime));
        container.unsuspiciousblock$setTrackedLoot(tableId, itemCounts);
    }

    @Nullable
    // 捕获容器菜单打开前的快照（记录追踪容器与玩家物品栏中相关物品的计数）
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

    // 应用容器菜单关闭后的核对快照：计算增量 → 更新日志条目
    public static void applyMenuTrackingSnapshot(ServerPlayer player, MenuTrackingSnapshot snapshot) {
        List<LootResultSignature> signatures = new ArrayList<>();
        for (String signatureKey : snapshot.beforeInventoryCounts().keySet()) {
            LootResultSignature signature = LootResultSignature.fromStoredKey(signatureKey);
            if (signature != null) {
                signatures.add(signature);
            }
        }

        Map<String, Integer> afterCounts = capturePlayerInventoryCounts(player.getInventory(), signatures);
        LinkedHashMap<TrackedContainerLootState, ContainerLootUpdate> updates = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : afterCounts.entrySet()) {
            String signatureKey = entry.getKey();
            int before = snapshot.beforeInventoryCounts().getOrDefault(signatureKey, 0);
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

                int consumed = trackedContainer.unsuspiciousblock$consumeTrackedLoot(signatureKey, remaining);
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
            return;
        }

        long gameTime = player.serverLevel().getGameTime();
        long dayTime = player.serverLevel().getDayTime();
        for (Map.Entry<TrackedContainerLootState, ContainerLootUpdate> entry : updates.entrySet()) {
            TrackedContainerLootState trackedContainer = entry.getKey();
            ContainerLootUpdate update = entry.getValue();
            ExcavationLogEntry updatedEntry = applyPendingLoot(player, update.tableId(),
                    trackedContainer.unsuspiciousblock$getPendingJournalEntry(),
                    update.actualLoot(), gameTime, dayTime);
            if (trackedContainer.unsuspiciousblock$hasTrackedLoot()) {
                trackedContainer.unsuspiciousblock$setPendingJournalEntry(updatedEntry);
            } else {
                trackedContainer.unsuspiciousblock$clearPendingJournalEntry();
            }
        }
    }

    // 核对容器追踪状态：清理已过期或已标记移除的追踪
    public static void reconcileTrackedContainers(Collection<Container> rootContainers) {
        for (TrackedContainerLootState trackedContainer : collectTrackedContainers(rootContainers)) {
            trackedContainer.unsuspiciousblock$reconcileTrackedLoot();
        }
    }

    @Nullable
    // 解析物品栈在指定战利品表中匹配的签名
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
        return LootResultMatcher.resolve(stack, candidates);
    }

    static List<LootResultSignature> toSignatures(Map<String, Integer> itemCounts) {
        if (itemCounts == null || itemCounts.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, LootResultSignature> signatures = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            Integer count = entry.getValue();
            if (count == null || count <= 0) {
                continue;
            }

            LootResultSignature signature = LootResultSignature.fromStoredKey(entry.getKey());
            if (signature == null) {
                continue;
            }
            signatures.putIfAbsent(signature.toStoredKey(), signature);
        }
        return List.copyOf(signatures.values());
    }

    static Map<LootResultSignature, Integer> toSignatureCounts(Map<String, Integer> itemCounts) {
        if (itemCounts == null || itemCounts.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<LootResultSignature, Integer> signatureCounts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            Integer count = entry.getValue();
            if (count == null || count <= 0) {
                continue;
            }

            LootResultSignature signature = LootResultSignature.fromStoredKey(entry.getKey());
            if (signature == null) {
                continue;
            }
            signatureCounts.merge(signature, count, Integer::sum);
        }
        return signatureCounts;
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


    private static BlockPos resolveContainerPos(TrackedContainerLootState container) {
        if (container instanceof BlockEntity blockEntity) {
            return blockEntity.getBlockPos();
        }
        return BlockPos.ZERO;
    }

    @Nullable
    private static ResourceLocation resolveContainerSourceBlockId(TrackedContainerLootState container) {
        if (container instanceof BlockEntity blockEntity) {
            return BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        }
        return null;
    }

    private static ResourceLocation resolveBiomeId(ServerLevel level, BlockPos pos) {
        Holder<Biome> biomeHolder = level.getBiome(pos);
        return biomeHolder.unwrapKey()
                .map(ResourceKey::location)
                .orElse(ResourceLocation.withDefaultNamespace("plains"));
    }

    @Nullable
    private static ResourceLocation resolveStructureId(ServerLevel level, BlockPos pos) {
        StructureManager structureManager = level.structureManager();
        Map<Structure, LongSet> structureReferences = structureManager.getAllStructuresAt(pos);
        if (structureReferences.isEmpty()) {
            return null;
        }
        ResourceLocation bestMatch = null;
        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Structure structure : structureReferences.keySet()) {
            StructureStart structureStart = structureManager.getStructureAt(pos, structure);
            if (!structureStart.isValid()) {
                continue;
            }
            ResourceLocation structureId = structureRegistry.getKey(structure);
            if (structureId == null) {
                continue;
            }
            if (bestMatch == null || structureId.toString().compareTo(bestMatch.toString()) < 0) {
                bestMatch = structureId;
            }
        }
        return bestMatch;
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

    private record ContainerLootUpdate(ResourceLocation tableId,
                                       LinkedHashMap<String, Integer> actualLoot) {
        private ContainerLootUpdate(ResourceLocation tableId) {
            this(tableId, new LinkedHashMap<>());
        }
    }
}
