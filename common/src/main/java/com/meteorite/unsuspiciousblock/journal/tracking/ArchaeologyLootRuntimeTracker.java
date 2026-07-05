package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.loottable.LootResultMatcher;
import com.meteorite.unsuspiciousblock.loottable.LootCounts;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import com.meteorite.unsuspiciousblock.network.journal.JournalStateHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            triggerPrioritySimulation(tableId);
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
            triggerPrioritySimulation(tableId);
        }
    }

    // 解锁触发：若该表尚未纳入概率缓存，向后台工作线程插队模拟
    private static void triggerPrioritySimulation(ResourceLocation tableId) {
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) return;
        if (!ArchaeologyJournalServerCatalog.hasSimulatedData(tableId)) {
            worker.enqueuePriority(tableId);
        }
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
                level.dimension().location(), sourceBlockId, WorldContextResolver.resolveStructureId(level, pos), WorldContextResolver.resolveBiomeId(level, pos), pos);
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

    @Nullable
    private static ArchaeologyJournalState getState(ServerPlayer player) {
        if (!(player instanceof ArchaeologyJournalStateHolder holder)) {
            return null;
        }
        return holder.unsuspiciousblock$getArchaeologyJournalState();
    }
}
