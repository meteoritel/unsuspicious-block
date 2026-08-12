package com.meteorite.unsuspiciousblock.network;

import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyChallengeChecker;
import com.meteorite.unsuspiciousblock.journal.tracking.JournalCompletionRewardChecker;
import com.meteorite.unsuspiciousblock.journal.tracking.JournalProgressSignatureMigrator;
import com.meteorite.unsuspiciousblock.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalStateHandler;
import com.meteorite.unsuspiciousblock.network.journal.LootTableManagementHandler;
import net.minecraft.server.level.ServerPlayer;

/**
 * 考古日记网络同步调度入口。
 * 各子领域委托给独立的 Handler 类处理。
 */
public final class ArchaeologyJournalNetwork {
    private ArchaeologyJournalNetwork() {}

    // 玩家加入时全量同步：日志 → 目录哈希 → 管理索引/名称 → 签名迁移 → 状态
    public static void syncOnJoin(ServerPlayer player) {
        JournalLogHandler.restoreAndSyncOnJoin(player);
        JournalCatalogHandler.syncCatalogHash(player);
        LootTableManagementHandler.sync(player);
        JournalProgressSignatureMigrator.migrate(player);
        JournalStateHandler.syncStateFull(player);
        // 状态全量同步之后再补发完成奖励，确保客户端 catalog 已就绪可解析表名
        JournalCompletionRewardChecker.checkAndRewardAll(player);
        // 补发考古收集类成就检测，避免 catalog 未就绪或事件漏触发时遗漏授予
        ArchaeologyChallengeChecker.checkAndGrantAll(player);
    }
}
