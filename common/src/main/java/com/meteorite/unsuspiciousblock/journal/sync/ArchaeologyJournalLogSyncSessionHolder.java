package com.meteorite.unsuspiciousblock.journal.sync;

/**
 * 日志同步会话持有者接口——通过 mixin 附加到 ServerPlayer。
 * 用于从服务端玩家实例中获取 ArchaeologyJournalLogSyncSession。
 */
public interface ArchaeologyJournalLogSyncSessionHolder {
    // 获取玩家的日志同步会话实例
    ArchaeologyJournalLogSyncSession unsuspiciousblock$getArchaeologyJournalLogSyncSession();
}
