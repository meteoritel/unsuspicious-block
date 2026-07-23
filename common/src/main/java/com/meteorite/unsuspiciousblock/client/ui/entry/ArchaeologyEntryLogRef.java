package com.meteorite.unsuspiciousblock.client.ui.entry;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 考古条目的日志数据引用——首次解锁时间元数据与日志条目列表。
 * 从 {@link ArchaeologyJournalLogState.TableLogHistory} 提取不可变快照，与条目本身解耦。
 */
public record ArchaeologyEntryLogRef(
        @Nullable Long firstUnlockedGameTime,
        @Nullable Long firstUnlockedDayTime,
        @Nullable LootSourceType firstUnlockLootSource,
        @Nullable ExcavationLogEntry.GameTimestamp latestUpdateTimestamp,
        List<ExcavationLogEntry> logEntries
) {
    // 空日志引用，用于未解锁或无日志数据的情况
    public static final ArchaeologyEntryLogRef EMPTY =
            new ArchaeologyEntryLogRef(null, null, null, null, List.of());

    /**
     * 从 {@link ArchaeologyJournalLogState.TableLogHistory} 创建日志引用快照。
     * logHistory 为 null 时返回 {@link #EMPTY}。
     */
    public static ArchaeologyEntryLogRef from(@Nullable ArchaeologyJournalLogState.TableLogHistory logHistory) {
        if (logHistory == null) {
            return EMPTY;
        }
        List<ExcavationLogEntry> entries = logHistory.getEntries();
        Long firstUnlockedGameTime = logHistory.getFirstUnlockedGameTime();
        Long firstUnlockedDayTime = logHistory.getFirstUnlockedDayTime();
        return new ArchaeologyEntryLogRef(
                firstUnlockedGameTime,
                firstUnlockedDayTime,
                logHistory.getFirstUnlockLootSource(),
                latestUpdateTimestamp(firstUnlockedGameTime, firstUnlockedDayTime, entries),
                entries  // 已是不可变列表，无需再次 List.copyOf
        );
    }

    // 计算目录“更新时间”排序键：优先使用最新日志更新时间，无日志时回退到首次解锁时间。
    @Nullable
    private static ExcavationLogEntry.GameTimestamp latestUpdateTimestamp(@Nullable Long firstUnlockedGameTime,
                                                                          @Nullable Long firstUnlockedDayTime,
                                                                          List<ExcavationLogEntry> entries) {
        ExcavationLogEntry.GameTimestamp latest = firstUnlockedGameTime != null
                ? new ExcavationLogEntry.GameTimestamp(firstUnlockedGameTime,
                firstUnlockedDayTime != null ? firstUnlockedDayTime : firstUnlockedGameTime)
                : null;

        for (ExcavationLogEntry entry : entries) {
            ExcavationLogEntry.GameTimestamp candidate = entry.lastUpdated();
            if (isAfter(candidate, latest)) {
                latest = candidate;
            }
        }
        return latest;
    }

    // 判断候选时间是否晚于当前时间。
    private static boolean isAfter(ExcavationLogEntry.GameTimestamp candidate,
                                   @Nullable ExcavationLogEntry.GameTimestamp current) {
        if (current == null) {
            return true;
        }
        return candidate.gameTime() > current.gameTime()
                || candidate.gameTime() == current.gameTime() && candidate.dayTime() > current.dayTime();
    }
}
