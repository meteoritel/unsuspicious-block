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
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
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
        ArchaeologyChallengeChecker.checkAndGrant(player, state);
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
        ArchaeologyChallengeChecker.checkAndGrant(player, state);
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
                level.dimension().location(), sourceBlockId, resolveStructureId(level, pos), resolveBiomeId(level, pos), pos);
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

    // 容器战利品确认处理：结算旧条目（如有）→ 解锁物品 + 创建待定日志条目 + 记录追踪状态
    // 仅第一个触发解析的玩家会创建追踪，后续玩家跳过
    public static void onContainerLootResolved(ServerPlayer player,
                                               TrackedContainerLootState container,
                                               ResourceLocation tableId,
                                               Map<String, Integer> itemCounts) {
        long gameTime = player.serverLevel().getGameTime();
        long dayTime = player.serverLevel().getDayTime();
        long timeoutTicks = Services.LOOT_TABLE_CONFIG.getTrackingTimeoutTicks();

        // 多玩家并发安全：若追踪玩家 UUID 已存在且不是当前玩家，跳过
        UUID trackedPlayerUuid = container.unsuspiciousblock$getTrackedPlayerUuid();
        if (trackedPlayerUuid != null && !trackedPlayerUuid.equals(player.getUUID())) {
            // 超时检查：即使不是当前玩家，也要检查是否需要清理过期追踪
            if (container.unsuspiciousblock$isTrackingExpired(gameTime, timeoutTicks)) {
                flushTrackingToJournal(player, container);
            }
            return;
        }

        // 超时检查：若追踪已超时，先 flush 旧条目再创建新追踪
        if (trackedPlayerUuid == null && container.unsuspiciousblock$isTrackingExpired(gameTime, timeoutTicks)) {
            flushTrackingToJournal(player, container);
        } else if (container.unsuspiciousblock$getPendingJournalEntry() != null
                && container.unsuspiciousblock$getTrackedLootTableName() != null) {
            // 若容器有未结算的旧追踪条目（非超时），先将旧条目写入日志再覆盖追踪状态
            ResourceLocation oldTableId = container.unsuspiciousblock$getTrackedLootTableName();
            ExcavationLogEntry oldPendingEntry = container.unsuspiciousblock$getPendingJournalEntry();
            if (oldPendingEntry != null) {
                JournalLogRecorder.upsertExcavationEntry(player, oldTableId, oldPendingEntry,
                        toSignatureCounts(oldPendingEntry.actualLoot()));
            }
        }

        onLootDiscovered(player, tableId, itemCounts, LootSourceType.LOOT_CONTAINER, gameTime, dayTime);
        if (itemCounts.isEmpty()) {
            container.unsuspiciousblock$clearAllTrackingState();
            return;
        }
        BlockPos pos = resolveContainerPos(container);
        container.unsuspiciousblock$setPendingJournalEntry(createPendingEntry(player, LootSourceType.LOOT_CONTAINER,
                resolveContainerSourceBlockId(container), pos, itemCounts, gameTime, dayTime));
        container.unsuspiciousblock$setTrackedLoot(tableId, itemCounts);
        container.unsuspiciousblock$setTrackedPlayerUuid(player.getUUID());
    }

    @Nullable
    // 捕获容器菜单打开前的快照（记录追踪容器与玩家物品栏/光标中相关物品的计数）
    public static MenuTrackingSnapshot captureMenuTrackingSnapshot(ServerPlayer player, Collection<Container> rootContainers) {
        return captureMenuTrackingSnapshot(player, rootContainers, ItemStack.EMPTY);
    }

    @Nullable
    // 捕获容器菜单操作前的快照，同时记录光标上的追踪物品
    // 仅收集当前玩家拥有的追踪容器，超时的容器先 flush
    public static MenuTrackingSnapshot captureMenuTrackingSnapshot(ServerPlayer player, Collection<Container> rootContainers, ItemStack carriedItem) {
        long gameTime = player.serverLevel().getGameTime();
        long timeoutTicks = Services.LOOT_TABLE_CONFIG.getTrackingTimeoutTicks();

        List<TrackedContainerLootState> trackedContainers = collectOwnTrackedContainers(player, rootContainers, gameTime, timeoutTicks);
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

    // 应用容器菜单快照核对：计算增量 → 更新日志条目，返回 true 表示有更新被应用
    public static boolean applyMenuTrackingSnapshot(ServerPlayer player, MenuTrackingSnapshot snapshot) {
        return applyMenuTrackingSnapshot(player, snapshot, ItemStack.EMPTY);
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
            return false;
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
                // 追踪已全部结算，清除容器的完整追踪状态
                trackedContainer.unsuspiciousblock$clearAllTrackingState();
            }
        }
        return true;
    }

    // 核对容器追踪状态：清理已过期或已标记移除的追踪，并结算残留的待定日志条目
    // 仅处理当前玩家拥有的追踪容器，超时的先 flush
    public static void reconcileTrackedContainers(ServerPlayer player, Collection<Container> rootContainers) {
        long gameTime = player.serverLevel().getGameTime();
        long timeoutTicks = Services.LOOT_TABLE_CONFIG.getTrackingTimeoutTicks();

        for (TrackedContainerLootState trackedContainer : collectOwnTrackedContainers(player, rootContainers, gameTime, timeoutTicks)) {
            // reconcile 前获取旧条目和 tableId
            ExcavationLogEntry pendingEntry = trackedContainer.unsuspiciousblock$getPendingJournalEntry();
            ResourceLocation tableId = trackedContainer.unsuspiciousblock$getTrackedLootTableName();

            trackedContainer.unsuspiciousblock$reconcileTrackedLoot();

            // reconcile 后若追踪状态已清空但有残留的 pendingJournalEntry，将旧条目写入日志并清除
            if (!trackedContainer.unsuspiciousblock$hasTrackedLoot() && pendingEntry != null) {
                if (tableId != null) {
                    ServerPlayer recipient = resolveTrackingPlayer(player, trackedContainer);
                    JournalLogRecorder.upsertExcavationEntry(recipient, tableId, pendingEntry,
                            toSignatureCounts(pendingEntry.actualLoot()));
                }
                trackedContainer.unsuspiciousblock$clearAllTrackingState();
            }
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

    // 仅收集当前玩家拥有的追踪容器；超时的追踪先 flush 后移除
    private static List<TrackedContainerLootState> collectOwnTrackedContainers(ServerPlayer player,
                                                                               Collection<Container> rootContainers,
                                                                               long currentGameTime,
                                                                               long timeoutTicks) {
        List<TrackedContainerLootState> allTracked = collectTrackedContainers(rootContainers);
        List<TrackedContainerLootState> result = new ArrayList<>();
        UUID playerUuid = player.getUUID();
        for (TrackedContainerLootState tracked : allTracked) {
            UUID owner = tracked.unsuspiciousblock$getTrackedPlayerUuid();
            // 超时：先 flush 再移除，不参与本次快照
            if (tracked.unsuspiciousblock$isTrackingExpired(currentGameTime, timeoutTicks)) {
                flushTrackingToJournal(player, tracked);
                continue;
            }
            // 仅当前玩家拥有的追踪容器参与
            if (owner != null && !owner.equals(playerUuid)) {
                continue;
            }
            result.add(tracked);
        }
        return result;
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

    // flush 追踪容器的待定日志条目到日志系统，然后清除完整追踪状态
    public static void flushTrackingToJournal(ServerPlayer fallbackPlayer, TrackedContainerLootState container) {
        ExcavationLogEntry pendingEntry = container.unsuspiciousblock$getPendingJournalEntry();
        ResourceLocation tableId = container.unsuspiciousblock$getTrackedLootTableName();
        if (pendingEntry != null && tableId != null) {
            ServerPlayer recipient = resolveTrackingPlayer(fallbackPlayer, container);
            JournalLogRecorder.upsertExcavationEntry(recipient, tableId, pendingEntry,
                    toSignatureCounts(pendingEntry.actualLoot()));
        }
        container.unsuspiciousblock$clearAllTrackingState();
    }

    // 解析追踪容器的日志接收者：优先追踪玩家 UUID 对应的在线玩家，回退到当前操作玩家
    private static ServerPlayer resolveTrackingPlayer(ServerPlayer fallbackPlayer, TrackedContainerLootState container) {
        UUID trackedUuid = container.unsuspiciousblock$getTrackedPlayerUuid();
        if (trackedUuid != null) {
            var trackedPlayer = fallbackPlayer.server.getPlayerList().getPlayer(trackedUuid);
            if (trackedPlayer != null) {
                return trackedPlayer;
            }
        }
        return fallbackPlayer;
    }

    // 容器方块被破坏时 flush 其追踪状态
    public static void onContainerBlockDestroyed(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TrackedContainerLootState trackedContainer)) {
            return;
        }
        if (!trackedContainer.unsuspiciousblock$hasTrackedLoot()
                && trackedContainer.unsuspiciousblock$getPendingJournalEntry() == null) {
            return;
        }
        // 尝试按追踪玩家 UUID 查找在线玩家，若离线则使用 null 作为回退（放弃该条目）
        UUID trackedUuid = trackedContainer.unsuspiciousblock$getTrackedPlayerUuid();
        ServerPlayer recipient = null;
        if (trackedUuid != null) {
            recipient = level.getServer().getPlayerList().getPlayer(trackedUuid);
        }
        if (recipient == null) {
            // 追踪玩家不在线，无法写入日志，清除追踪状态
            trackedContainer.unsuspiciousblock$clearAllTrackingState();
            return;
        }
        flushTrackingToJournal(recipient, trackedContainer);
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

    // 解析单个物品栈在追踪签名中的匹配计数
    private static Map<String, Integer> resolveItemCounts(ItemStack stack, Collection<LootResultSignature> trackedSignatures) {
        if (stack.isEmpty() || trackedSignatures.isEmpty()) {
            return Map.of();
        }
        List<LootResultSignature> candidates = new ArrayList<>(trackedSignatures);
        LootResultSignature signature = LootResultMatcher.resolve(stack, candidates);
        if (signature == null) {
            return Map.of();
        }
        return Map.of(signature.toStoredKey(), stack.getCount());
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
        // 一步获取该 chunk 内所有已解析的结构开端
        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ResourceLocation bestMatch = null;

        for (StructureStart start : structureManager.startsForStructure(new ChunkPos(pos), structure -> true)) {
            if (!start.isValid() || !start.getBoundingBox().isInside(pos)) {
                continue;
            }
            ResourceLocation id = structureRegistry.getKey(start.getStructure());
            if (id == null) {
                continue;
            }
            if (bestMatch == null || id.toString().compareTo(bestMatch.toString()) < 0) {
                bestMatch = id;
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
                                       Map<String, Integer> beforeInventoryCounts,
                                       Map<String, Integer> beforeCarriedCounts) {
        public MenuTrackingSnapshot {
            trackedContainers = List.copyOf(trackedContainers);
            beforeInventoryCounts = Map.copyOf(beforeInventoryCounts);
            beforeCarriedCounts = Map.copyOf(beforeCarriedCounts);
        }
    }

    private record ContainerLootUpdate(ResourceLocation tableId,
                                       LinkedHashMap<String, Integer> actualLoot) {
        private ContainerLootUpdate(ResourceLocation tableId) {
            this(tableId, new LinkedHashMap<>());
        }
    }
}
