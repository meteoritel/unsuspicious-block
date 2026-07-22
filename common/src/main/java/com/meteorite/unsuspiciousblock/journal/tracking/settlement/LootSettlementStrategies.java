package com.meteorite.unsuspiciousblock.journal.tracking.settlement;

import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 内建战利品结算策略工厂。
 */
public final class LootSettlementStrategies {
    private static final LootSettlementStrategy IMMEDIATE = new ImmediateStrategy();

    private LootSettlementStrategies() {
    }

    // 生成完成时立即记录实际获取与最终日志，适用于钓鱼等直接进入玩家所有权的来源
    public static LootSettlementStrategy immediate() {
        return IMMEDIATE;
    }

    // 生成时只创建待定日志，实际取出或掉落物品后再更新获得记录
    public static LootSettlementStrategy deferred(Consumer<ExcavationLogEntry> pendingEntryConsumer) {
        return new DeferredStrategy(Objects.requireNonNull(pendingEntryConsumer, "pendingEntryConsumer"));
    }

    /**
     * 立即写入最终日志的结算策略。
     */
    private static final class ImmediateStrategy implements LootSettlementStrategy {
        @Override
        public boolean recordsItemsImmediately() {
            return true;
        }

        @Override
        public void settle(LootSession.Commit commit) {
            ArchaeologyLootRuntimeTracker.recordCompletedSession(commit);
        }
    }

    /**
     * 将日志交给容器或可疑方块暂存的延迟结算策略。
     */
    private record DeferredStrategy(Consumer<ExcavationLogEntry> pendingEntryConsumer)
            implements LootSettlementStrategy {
        @Override
        public boolean recordsItemsImmediately() {
            return false;
        }

        @Override
        public void settle(LootSession.Commit commit) {
            ExcavationLogEntry pendingEntry = ArchaeologyLootRuntimeTracker.createPendingEntry(commit);
            if (pendingEntry != null) {
                this.pendingEntryConsumer.accept(pendingEntry);
            }
        }
    }
}
