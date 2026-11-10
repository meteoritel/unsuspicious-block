package com.meteorite.unsuspiciousblock.core.journal.tracking;

import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalStateHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Map;

/** 日志与取得进度的协调写入；全部表更新后统一同步进度。 */
public final class JournalLogRecorder {
    private JournalLogRecorder() {
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

    // 将签名计数 Map 同步到日记进度状态，有变更时发送增量
    public static void recordItemsAcquired(ServerPlayer player, ResourceLocation tableId,
                                                 Map<LootResultSignature, Integer> signatureCounts) {
        ArchaeologyJournalState state = ArchaeologyJournalStateHolder.getState(player);
        if (state == null) {
            return;
        }
        boolean changed = state.recordItemsAcquired(tableId, signatureCounts);
        if (changed) {
            JournalStateHandler.syncState(player);
        }
    }
}
