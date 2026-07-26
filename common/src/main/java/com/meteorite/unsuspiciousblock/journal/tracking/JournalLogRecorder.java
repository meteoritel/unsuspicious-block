package com.meteorite.unsuspiciousblock.journal.tracking;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalStateHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 考古日志记录器——服务端日志变更的统一入口。
 * <p>
 * 封装日记进度状态（JournalState）同步与日志条目（JournalLog）记录的协调更新，
 * 提供简洁的 API 供业务层（mixin / item / tracker）直接调用。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>首次解锁元数据记录 + 日记进度同步</li>
 *   <li>日志条目 upsert + 日记进度同步</li>
 *   <li>日志清空操作</li>
 * </ul>
 * 不负责：战利品签名解析、容器追踪、待定条目创建（这些属于 ArchaeologyLootRuntimeTracker）。
 */
public final class JournalLogRecorder {
    private JournalLogRecorder() {
    }

    // 记录表首次解锁事件（含触发类型），同时同步日记进度状态
    public static void recordFirstUnlock(ServerPlayer player, ResourceLocation tableId,
                                         @Nullable LootSourceType lootSource,
                                         long gameTime, long dayTime) {
        JournalLogHandler.recordFirstUnlock(player, tableId, lootSource, gameTime, dayTime);
    }

    // 插入或更新挖掘日志条目，同时同步日记进度状态中已获取物品的计数
    public static void upsertExcavationEntry(ServerPlayer player, ResourceLocation tableId,
                                             ExcavationLogEntry entry,
                                             @Nullable Map<LootResultSignature, Integer> acquiredSignatures) {
        Map<ResourceLocation, Map<LootResultSignature, Integer>> acquiredByTable =
                acquiredSignatures == null || acquiredSignatures.isEmpty()
                        ? Map.of()
                        : Map.of(tableId, acquiredSignatures);
        upsertExcavationEntries(player, java.util.List.of(tableId), entry, acquiredByTable);
    }

    // 批量写入同一聚合日志，并在全部表计数更新后只发送一次进度增量
    public static void upsertExcavationEntries(
            ServerPlayer player, Iterable<ResourceLocation> tableIds, ExcavationLogEntry entry,
            Map<ResourceLocation, Map<LootResultSignature, Integer>> acquiredByTable) {
        LinkedHashSet<ResourceLocation> uniqueTableIds = new LinkedHashSet<>();
        tableIds.forEach(uniqueTableIds::add);

        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        boolean stateChanged = false;
        if (state != null && acquiredByTable != null && !acquiredByTable.isEmpty()) {
            for (ResourceLocation tableId : uniqueTableIds) {
                Map<LootResultSignature, Integer> counts = acquiredByTable.get(tableId);
                if (counts != null && !counts.isEmpty()) {
                    stateChanged |= state.recordItemsAcquired(tableId, counts);
                }
            }
        }

        for (ResourceLocation tableId : uniqueTableIds) {
            JournalLogHandler.upsertExcavationEntry(player, tableId, entry);
        }
        if (stateChanged) {
            JournalStateHandler.syncState(player);
        }
    }

    // 仅更新日记进度状态中的已获取物品计数（不更新日志条目）
    public static void recordItemsAcquired(ServerPlayer player, ResourceLocation tableId,
                                           Map<LootResultSignature, Integer> signatureCounts) {
        syncItemAcquiredState(player, tableId, signatureCounts);
    }

    // 清空全部日志
    public static void clearLogs(ServerPlayer player) {
        JournalLogHandler.clearLogs(player);
    }

    // 清空指定表的日志
    public static void clearLogsForTable(ServerPlayer player, ResourceLocation tableId) {
        JournalLogHandler.clearLogsForTable(player, tableId);
    }

    // 将签名计数 Map 同步到日记进度状态，返回是否有变更
    private static boolean syncItemAcquiredState(ServerPlayer player, ResourceLocation tableId,
                                                 Map<LootResultSignature, Integer> signatureCounts) {
        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        if (state == null) {
            return false;
        }
        boolean changed = state.recordItemsAcquired(tableId, signatureCounts);
        if (changed) {
            JournalStateHandler.syncState(player);
        }
        return changed;
    }
}
