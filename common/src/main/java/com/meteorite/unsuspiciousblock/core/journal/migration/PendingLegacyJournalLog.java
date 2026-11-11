package com.meteorite.unsuspiciousblock.core.journal.migration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/** 玩家旧日志的迁移源，目标分片与索引提交成功前继续写回。 */
public final class PendingLegacyJournalLog {
    private static final String PLAYER_TAG = "unsuspiciousblock_archaeology_journal_log";
    private @Nullable CompoundTag pending;

    public void readPlayerTag(CompoundTag tag) {
        pending = tag.contains(PLAYER_TAG, Tag.TAG_COMPOUND) ? tag.getCompound(PLAYER_TAG).copy() : null;
    }

    public void writePlayerTag(CompoundTag tag) {
        if (pending != null) tag.put(PLAYER_TAG, pending.copy());
    }

    public @Nullable CompoundTag peek() {
        return pending == null ? null : pending.copy();
    }

    public void acknowledge() {
        pending = null;
    }
}
