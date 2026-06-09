package com.meteorite.unsuspiciousblock.client.ui.entry;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.TriggerType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 考古条目的日志数据引用——首次解锁时间元数据与日志条目列表。
 * 从 {@link ArchaeologyJournalLogState.TableLogHistory} 提取不可变快照，与条目本身解耦。
 */
public record ArchaeologyEntryLogRef(
        @Nullable Long firstUnlockedGameTime,
        @Nullable Long firstUnlockedDayTime,
        @Nullable TriggerType firstUnlockTriggerType,
        List<ExcavationLogEntry> logEntries
) {
    /** 空日志引用，用于未解锁或无日志数据的情况 */
    public static final ArchaeologyEntryLogRef EMPTY =
            new ArchaeologyEntryLogRef(null, null, null, List.of());

    /**
     * 从 {@link ArchaeologyJournalLogState.TableLogHistory} 创建日志引用快照。
     * logHistory 为 null 时返回 {@link #EMPTY}。
     */
    public static ArchaeologyEntryLogRef from(@Nullable ArchaeologyJournalLogState.TableLogHistory logHistory) {
        if (logHistory == null) {
            return EMPTY;
        }
        return new ArchaeologyEntryLogRef(
                logHistory.getFirstUnlockedGameTime(),
                logHistory.getFirstUnlockedDayTime(),
                logHistory.getFirstUnlockTriggerType(),
                logHistory.getEntries()  // 已是不可变列表，无需再次 List.copyOf
        );
    }
}