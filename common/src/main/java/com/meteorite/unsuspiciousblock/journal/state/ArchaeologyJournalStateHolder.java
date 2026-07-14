package com.meteorite.unsuspiciousblock.journal.state;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 考古日记状态持有者接口——通过 mixin 附加到 ServerPlayer。
 * 用于从服务端玩家实例中获取 ArchaeologyJournalState。
 */
public interface ArchaeologyJournalStateHolder {
    // 获取玩家的考古日记进度状态实例
    ArchaeologyJournalState unsuspiciousblock$getArchaeologyJournalState();

    // 从 ServerPlayer 安全提取考古日记状态，未实现本接口时返回 null
    @Nullable
    static ArchaeologyJournalState getState(ServerPlayer player) {
        if (player instanceof ArchaeologyJournalStateHolder holder) {
            return holder.unsuspiciousblock$getArchaeologyJournalState();
        }
        return null;
    }
}
