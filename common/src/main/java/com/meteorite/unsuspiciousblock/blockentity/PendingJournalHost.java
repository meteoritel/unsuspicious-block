package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.journal.tracking.PendingJournal;

/** 仅支持持久化待定日志的宿主实现此能力。 */
public interface PendingJournalHost {
    PendingJournal unsuspiciousblock$pendingJournal();
}
