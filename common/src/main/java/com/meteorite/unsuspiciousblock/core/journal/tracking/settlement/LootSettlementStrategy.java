package com.meteorite.unsuspiciousblock.core.journal.tracking.settlement;

import com.meteorite.unsuspiciousblock.core.journal.tracking.LootSession;

/**
 * 一次聚合战利品会话的发现阶段结算策略。
 */
public interface LootSettlementStrategy {
    // 是否在发现阶段立即将物品计入“已获得”状态
    boolean recordsItemsImmediately();

    // 创建最终日志或保存待定日志
    void settle(LootSession.Commit commit);
}
