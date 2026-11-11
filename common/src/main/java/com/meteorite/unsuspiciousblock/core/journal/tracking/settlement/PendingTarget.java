package com.meteorite.unsuspiciousblock.core.journal.tracking.settlement;

import com.meteorite.unsuspiciousblock.core.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.core.journal.tracking.PendingJournal;
import org.jetbrains.annotations.Nullable;

/** 待定日志与宿主身份、余量的配套更新。 */
public sealed interface PendingTarget {
    void stage(LootSession.Commit commit, @Nullable ExcavationLogEntry entry);

    /** 刷拭宿主只暂存非空条目。 */
    record Brush(PendingJournal pending) implements PendingTarget {
        @Override
        public void stage(LootSession.Commit commit, @Nullable ExcavationLogEntry entry) {
            if (entry != null) pending.stage(entry);
        }
    }

    /** 容器绑定日志、最终余量和首位玩家；空结果清除完整追踪状态。 */
    record Container(TrackedContainerLootState container) implements PendingTarget {
        @Override
        public void stage(LootSession.Commit commit, @Nullable ExcavationLogEntry entry) {
            if (commit.finalItemCounts().isEmpty()) {
                container.unsuspiciousblock$clearAllTrackingState();
                return;
            }
            if (entry == null) return;
            container.unsuspiciousblock$pendingJournal().stage(entry);
            container.unsuspiciousblock$setTrackedLoot(commit.rootContext().rootTableId(), commit.finalItemCounts());
            container.unsuspiciousblock$setTrackedPlayerUuid(commit.rootContext().player().getUUID());
        }
    }
}
