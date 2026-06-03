package com.meteorite.unsuspiciousblock.journal.state;

/**
 * 考古日记状态持有者接口——通过 mixin 附加到 ServerPlayer。
 * 用于从服务端玩家实例中获取 ArchaeologyJournalState。
 */
public interface ArchaeologyJournalStateHolder {
    // 获取玩家的考古日记进度状态实例
    ArchaeologyJournalState unsuspiciousblock$getArchaeologyJournalState();
}
