package com.meteorite.unsuspiciousblock.core.blockentity;

import com.meteorite.unsuspiciousblock.core.journal.tracking.PendingJournal;

/** 仅支持持久化待定日志的宿主实现此能力。 */
public interface PendingJournalHost {
    PendingJournal unsuspiciousblock$pendingJournal();
}
