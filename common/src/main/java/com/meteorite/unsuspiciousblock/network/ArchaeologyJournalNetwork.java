package com.meteorite.unsuspiciousblock.network;

import com.meteorite.unsuspiciousblock.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalStateHandler;
import net.minecraft.server.level.ServerPlayer;

/**
 * 考古日记网络同步调度入口。
 * 各子领域委托给独立的 Handler 类处理。
 */
public final class ArchaeologyJournalNetwork {
    private ArchaeologyJournalNetwork() {}

    // 玩家加入时全量同步：从 NBT 恢复日志并下发快照 → 下发目录 → 下发状态
    public static void syncOnJoin(ServerPlayer player) {
        JournalLogHandler.restoreAndSyncOnJoin(player);
        JournalCatalogHandler.syncCatalog(player);
        JournalStateHandler.syncStateFull(player);
    }
}