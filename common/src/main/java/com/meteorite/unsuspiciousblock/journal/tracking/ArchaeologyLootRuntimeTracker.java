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

    // 多表解锁：签名解析锚定 signatureAnchor（通常=rootTableId），同一份签名应用到 tableIds 中所有在 catalog 中的表
    // 用于嵌套表场景：一次发现同时解锁根表与子表（若两者都在 catalog 中）
    // recordItemCounts=true 时同时记录物品获取计数（钓鱼场景，无待定日志条目机制）；
    // recordItemCounts=false 时仅解锁，物品计数由待定日志条目机制（applyPendingLoot/flushTrackingToJournal）负责
    public static void unlockResolvedLootMultiTable(ServerPlayer player, List<ResourceLocation> tableIds,
                                                    ResourceLocation signatureAnchor,
                                                    Map<String, Integer> itemCounts,
                                                    boolean recordItemCounts) {
        ArchaeologyJournalState state = getState(player);
        if (state == null) {
            return;
        }

        List<LootResultSignature> signatures = toSignatures(itemCounts);
        Map<LootResultSignature, Integer> signatureCounts = recordItemCounts
                ? toSignatureCounts(itemCounts)
                : Map.of();
        boolean changed = false;
        for (ResourceLocation tableId : tableIds) {
            if (!isTableInCatalog(tableId)) {
                // 子表不在 catalog：跳过 unlockTable，不强制解锁未注册的表
                continue;
            }
            boolean tableChanged = state.unlockTable(tableId);
            tableChanged |= state.unlockItems(tableId, signatures);
            if (recordItemCounts && !signatureCounts.isEmpty()) {
                tableChanged |= state.recordItemsAcquired(tableId, signatureCounts);
            }
            changed |= tableChanged;
        }
        if (changed) {
            JournalStateHandler.syncState(player);
            // 仅对锚定表触发模拟（通常为根表，避免重复入队）
            triggerPrioritySimulation(signatureAnchor);
        }
    }

    // 判断指定表是否已在 catalog 中注册
    private static boolean isTableInCatalog(ResourceLocation tableId) {
        return ArchaeologyJournalServerCatalog.getCatalog().containsKey(tableId);
    }

    // 为钓鱼/开箱等无待定日志条目机制的追踪入口创建并 upsert ExcavationLogEntry。
    // 遍历 tableStack 对每个在 catalog 中的表 upsert 同一条日志（同 entryId 关联多个表）。
    // 物品计数已由 onUnlock 中的 recordItemsAcquired 负责，此处 acquiredSignatures 传 null 不重复记录。
    public static void recordExcavationEntryMultiTable(ServerPlayer player,
                                                       List<ResourceLocation> tableStack,
                                                       LootSourceType lootSource,
                                                       long gameTime, long dayTime,
                                                       Map<String, Integer> itemCounts,
                                                       BlockPos pos,
                                                       @Nullable ResourceLocation sourceBlockId) {
        Map<String, Integer> normalizedLoot = LootCounts.normalize(itemCounts);
        if (normalizedLoot.isEmpty()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ExcavationLogEntry.ExcavationContext context = new ExcavationLogEntry.ExcavationContext(
                level.dimension().location(), sourceBlockId,
                WorldContextResolver.resolveStructureId(level, pos),
                WorldContextResolver.resolveBiomeId(level, pos), pos);
        ExcavationLogEntry.GameTimestamp timestamp = new ExcavationLogEntry.GameTimestamp(
                Math.max(0L, gameTime), Math.max(0L, dayTime));
        // 钓鱼/开箱场景无"期望值 vs 实际值"差异，expectedLoot = actualLoot = normalizedLoot
        ExcavationLogEntry entry = new ExcavationLogEntry(UUID.randomUUID(), lootSource, context,
                timestamp, timestamp, normalizedLoot, normalizedLoot, "", tableStack);
        // 遍历 tableStack，对每个在 catalog 中的表 upsert 日志条目
        for (ResourceLocation tableId : tableStack) {
            if (!isTableInCatalog(tableId)) {
                continue;
            }
            JournalLogRecorder.upsertExcavationEntry(player, tableId, entry, null);
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
