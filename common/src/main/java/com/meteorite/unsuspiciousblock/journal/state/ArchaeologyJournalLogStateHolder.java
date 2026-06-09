package com.meteorite.unsuspiciousblock.journal.state;

/**
 * 考古日志状态持有者接口——通过 mixin 附加到 Player。
 * 用于从玩家实例中获取 ArchaeologyJournalLogState（服务端持久化）。
 */
public interface ArchaeologyJournalLogStateHolder {
    // 获取玩家的考古日志状态实例
    ArchaeologyJournalLogState unsuspiciousblock$getArchaeologyJournalLogState();
}