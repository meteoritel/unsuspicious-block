package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultMatcher;
import com.meteorite.unsuspiciousblock.loottable.signature.LootCounts;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.network.journal.JournalStateHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
/**
 * 考古战利品运行时追踪器——服务端核心业务逻辑入口。
 * 负责：战利品解锁、日志条目创建与更新、签名解析与转换。
 * 战利品发现事件由 {@link com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents} 发布，
 * 容器追踪生命周期由 {@link ContainerTrackingService} 承载，
 * 菜单快照机制由 {@link MenuTrackingSnapshotService} 承载。
 */
public final class ArchaeologyLootRuntimeTracker {
    private ArchaeologyLootRuntimeTracker() {
    }

    // 一次性应用聚合会话中每个表的发现结果；根表计数已由 LootSession 使用最终结果校正
    public static void unlockSession(LootSession.Commit commit, boolean recordItemCounts) {
        ServerPlayer player = commit.rootContext().player();
        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        if (state == null) {
            return;
        }

        boolean changed = false;
        for (Map.Entry<ResourceLocation, Map<String, Integer>> discovery : commit.discoveredLoot().entrySet()) {
            ResourceLocation tableId = discovery.getKey();
            if (!ArchaeologyJournalServerCatalog.isTrackedTable(tableId)) {
                continue;
            }
            List<LootResultSignature> signatures = toSignatures(discovery.getValue());
            Map<LootResultSignature, Integer> signatureCounts = recordItemCounts
                    ? toSignatureCounts(discovery.getValue())
                    : Map.of();
            boolean tableChanged = state.unlockTable(tableId);
            tableChanged |= state.unlockItems(tableId, signatures);
            if (recordItemCounts && !signatureCounts.isEmpty()) {
                tableChanged |= state.recordItemsAcquired(tableId, signatureCounts);
            }
            changed |= tableChanged;
        }
        if (changed) {
            JournalStateHandler.syncState(player);
            triggerPrioritySimulation(commit.rootContext().rootTableId());
        }
    }

    // 为立即结算的会话创建最终日志；物品获得计数已在 unlockSession 中批量更新
    public static void recordCompletedSession(LootSession.Commit commit) {
        Map<String, Integer> normalizedLoot = LootCounts.normalize(commit.finalItemCounts());
        if (normalizedLoot.isEmpty()) {
            return;
        }
        LootTrackingContext trackingContext = commit.rootContext();
        ServerPlayer player = trackingContext.player();
        ServerLevel level = player.serverLevel();
        ExcavationLogEntry.ExcavationContext context = new ExcavationLogEntry.ExcavationContext(
                level.dimension().location(), trackingContext.sourceBlockId(),
                WorldContextResolver.resolveStructureId(level, trackingContext.pos()),
                WorldContextResolver.resolveBiomeId(level, trackingContext.pos()), trackingContext.pos());
        ExcavationLogEntry.GameTimestamp timestamp = new ExcavationLogEntry.GameTimestamp(
                Math.max(0L, trackingContext.gameTime()), Math.max(0L, trackingContext.dayTime()));
        ExcavationLogEntry entry = new ExcavationLogEntry(UUID.randomUUID(), trackingContext.lootSource(), context,
                timestamp, timestamp, normalizedLoot, normalizedLoot, "", commit.tableStack());
        upsertForTrackedTables(player, trackingContext.rootTableId(), entry, null);
    }

    // 解锁触发：若该表尚未纳入概率缓存，向后台工作线程插队模拟
    private static void triggerPrioritySimulation(ResourceLocation tableId) {
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) return;
        if (!ArchaeologyJournalServerCatalog.hasSimulatedData(tableId)) {
            TableDefinition rawTable = ArchaeologyJournalServerCatalog.getRawTable(tableId);
            if (rawTable != null) {
                worker.enqueuePriority(tableId, rawTable);
            }
        }
    }

    @Nullable
    // 从聚合会话创建待定日志，保留本次发现的完整表链路
    public static ExcavationLogEntry createPendingEntry(LootSession.Commit commit) {
        LootTrackingContext context = commit.rootContext();
        return createPendingEntry(context.player(), context.lootSource(), context.sourceBlockId(), context.pos(),
                commit.finalItemCounts(), context.gameTime(), context.dayTime(), commit.tableStack());
    }

    @Nullable
    private static ExcavationLogEntry createPendingEntry(ServerPlayer player, LootSourceType lootSource,
                                                         @Nullable ResourceLocation sourceBlockId, BlockPos pos,
                                                         Map<String, Integer> expectedLoot,
                                                         long gameTime, long dayTime,
                                                         @Nullable List<ResourceLocation> tableStack) {
        Map<String, Integer> normalizedExpectedLoot = LootCounts.normalize(expectedLoot);
        if (normalizedExpectedLoot.isEmpty()) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        ExcavationLogEntry.ExcavationContext context = new ExcavationLogEntry.ExcavationContext(
                level.dimension().location(), sourceBlockId, WorldContextResolver.resolveStructureId(level, pos), WorldContextResolver.resolveBiomeId(level, pos), pos);
        ExcavationLogEntry.GameTimestamp timestamp = new ExcavationLogEntry.GameTimestamp(
                Math.max(0L, gameTime), Math.max(0L, dayTime));
        return new ExcavationLogEntry(UUID.randomUUID(), lootSource, context,
                timestamp, timestamp, normalizedExpectedLoot, Map.of(), "", tableStack);
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
            JournalLogRecorder.recordItemsAcquired(player, tableId,
                    canonicalizeSignatureCounts(tableId, toSignatureCounts(normalizedActualLoot)));
            return null;
        }

        ExcavationLogEntry updatedEntry = pendingEntry.withActualLootMerged(normalizedActualLoot, gameTime, dayTime);
        upsertForTrackedTables(player, tableId, updatedEntry, toSignatureCounts(normalizedActualLoot));
        return updatedEntry;
    }

    // 仅将待定条目的当前状态写入日志；实际获得计数已在 applyPendingLoot 的增量阶段处理
    public static void finalizePendingEntry(ServerPlayer player, ResourceLocation tableId,
                                            @Nullable ExcavationLogEntry pendingEntry) {
        if (pendingEntry != null) {
            upsertForTrackedTables(player, tableId, pendingEntry, null);
        }
    }

    // 将同一条聚合日志写入根表及本次实际发现的所有已追踪子表
    private static void upsertForTrackedTables(ServerPlayer player, ResourceLocation fallbackTableId,
                                               ExcavationLogEntry entry,
                                               @Nullable Map<LootResultSignature, Integer> acquiredSignatures) {
        LinkedHashSet<ResourceLocation> tableIds = new LinkedHashSet<>();
        tableIds.add(fallbackTableId);
        if (entry.tableStack() != null) {
            tableIds.addAll(entry.tableStack());
        }
        LinkedHashMap<ResourceLocation, Map<LootResultSignature, Integer>> acquiredByTable =
                new LinkedHashMap<>();
        LinkedHashSet<ResourceLocation> trackedTableIds = new LinkedHashSet<>();
        for (ResourceLocation tableId : tableIds) {
            if (ArchaeologyJournalServerCatalog.isTrackedTable(tableId)) {
                trackedTableIds.add(tableId);
                if (acquiredSignatures != null && !acquiredSignatures.isEmpty()) {
                    acquiredByTable.put(tableId,
                            canonicalizeSignatureCounts(tableId, acquiredSignatures));
                }
            }
        }
        JournalLogRecorder.upsertExcavationEntries(player, trackedTableIds, entry, acquiredByTable);
    }

    @Nullable
    // 解析物品栈在指定战利品表中匹配的签名
    public static LootResultSignature resolveSignature(ResourceLocation tableId, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }

        TableDefinition table = resolveTableDefinition(tableId);
        if (table == null || table.items().isEmpty()) {
            return LootResultSignature.plain(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }

        List<LootResultSignature> candidates = new ArrayList<>();
        for (ItemDefinition item : table.items()) {
            candidates.add(item.signature());
        }
        return LootResultMatcher.resolve(stack, candidates);
    }

    // 获取容器物品匹配候选；目录签名优先，容器现状的普通签名用于补充运行时注入物品
    public static List<LootResultSignature> resolveCandidateSignatures(ResourceLocation tableId, Container container) {
        LinkedHashSet<LootResultSignature> candidates = new LinkedHashSet<>();
        List<LootResultSignature> catalogCandidates = new ArrayList<>();
        TableDefinition table = resolveTableDefinition(tableId);
        if (table != null && !table.items().isEmpty()) {
            for (ItemDefinition item : table.items()) {
                catalogCandidates.add(item.signature());
            }
            candidates.addAll(catalogCandidates);
        }

        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && catalogCandidates.stream()
                    .noneMatch(candidate -> LootResultMatcher.matches(stack, candidate))) {
                candidates.add(LootResultSignature.plain(BuiltInRegistries.ITEM.getKey(stack.getItem())));
            }
        }
        return List.copyOf(candidates);
    }

    // 将历史或其他表产生的签名安全映射为指定表的规范签名；存在歧义时保留原签名
    public static LootResultSignature canonicalizeSignature(ResourceLocation tableId,
                                                              LootResultSignature signature) {
        TableDefinition table = resolveTableDefinition(tableId);
        if (table == null || table.items().isEmpty()) {
            return signature;
        }

        LinkedHashSet<LootResultSignature> matchingItem = new LinkedHashSet<>();
        for (ItemDefinition item : table.items()) {
            LootResultSignature candidate = item.signature();
            if (candidate.equals(signature)) {
                return signature;
            }
            if (candidate.itemId().equals(signature.itemId())) {
                matchingItem.add(candidate);
            }
        }
        return matchingItem.size() == 1 ? matchingItem.getFirst() : signature;
    }

    // 按目标表规范化获取计数，供多表日志与累计统计共享同一签名边界
    public static Map<LootResultSignature, Integer> canonicalizeSignatureCounts(
            ResourceLocation tableId, Map<LootResultSignature, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<LootResultSignature, Integer> canonical = new LinkedHashMap<>();
        for (Map.Entry<LootResultSignature, Integer> entry : counts.entrySet()) {
            LootResultSignature signature = entry.getKey();
            Integer count = entry.getValue();
            if (signature == null || count == null || count <= 0) {
                continue;
            }
            LootResultSignature resolved = canonicalizeSignature(tableId, signature);
            canonical.merge(resolved, count, Integer::sum);
        }
        return canonical;
    }

    @Nullable
    private static TableDefinition resolveTableDefinition(ResourceLocation tableId) {
        TableDefinition table = ArchaeologyJournalServerCatalog.getCatalog().get(tableId);
        return table != null ? table : ArchaeologyJournalServerCatalog.getRawTable(tableId);
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
}
