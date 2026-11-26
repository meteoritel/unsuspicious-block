package com.meteorite.unsuspiciousblock.core.network;

import com.meteorite.unsuspiciousblock.core.journal.tracking.ArchaeologyChallengeChecker;
import com.meteorite.unsuspiciousblock.core.journal.tracking.JournalCompletionRewardChecker;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalStateHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.LootTableManagementHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import com.meteorite.unsuspiciousblock.platform.Services;

/**
 * 考古日记网络同步调度入口。
 * 各子领域委托给独立的 Handler 类处理。
 */
public final class ArchaeologyJournalNetwork {
    private ArchaeologyJournalNetwork() {}

    /** 玩家数据恢复后安排日志摘要、目录公告与进度基线。 */
    public static void syncPreparedPlayerOnJoin(ServerPlayer player) {
        if (Services.NETWORK.supportsJournal(player)) {
            JournalLogHandler.syncLogSummary(player);
            JournalCatalogHandler.announce(player);
            LootTableManagementHandler.syncNames(player);
            JournalStateHandler.syncStateFull(player);
        } else {
            player.sendSystemMessage(Component.translatableWithFallback(
                    "screen.unsuspiciousblock.archaeology_journal.incompatible",
                    "Journal requires the same mod version on both sides."));
        }
        // 奖励检查使用已恢复的服务端数据，客户端按可用目录解析通知。
        JournalCompletionRewardChecker.checkAndRewardAll(player);
        // 补发考古收集类成就检测，避免 catalog 未就绪或事件漏触发时遗漏授予
        ArchaeologyChallengeChecker.checkAndGrantAll(player);
    }
}
