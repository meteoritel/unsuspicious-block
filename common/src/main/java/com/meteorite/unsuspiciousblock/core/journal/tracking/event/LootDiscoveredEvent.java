package com.meteorite.unsuspiciousblock.core.journal.tracking.event;

import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.core.journal.tracking.settlement.LootSettlementTarget;

/**
 * 一次聚合战利品会话的提交事件。
 * <p>
 * 订阅者按顺序完成解锁、首次发现、结算、成就与完成奖励，同一 loot roll 只发布一次。
 */
public record LootDiscoveredEvent(LootSession.Commit commit,
                                  ArchaeologyJournalState state,
                                  LootSettlementTarget settlementTarget) {
}
