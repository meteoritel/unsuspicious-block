package com.meteorite.unsuspiciousblock.core.journal.tracking.settlement;

import com.meteorite.unsuspiciousblock.core.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.core.journal.tracking.PendingJournal;

import java.util.Objects;

/** 一次发现的结算目的地：立即获得，或由真实宿主暂存。 */
public sealed interface LootSettlementTarget {
    Immediate IMMEDIATE = new Immediate();

    /** 发现时同时计入获得并记录最终日志。 */
    record Immediate() implements LootSettlementTarget {}

    /** 发现时只记录资格，领取增量由宿主处理。 */
    record Deferred(PendingTarget pending) implements LootSettlementTarget {
        public Deferred { Objects.requireNonNull(pending, "pending"); }
    }

    static Deferred brush(PendingJournal pending) { return new Deferred(new PendingTarget.Brush(pending)); }
    static Deferred container(TrackedContainerLootState container) { return new Deferred(new PendingTarget.Container(container)); }
}
