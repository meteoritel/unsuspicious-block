package com.meteorite.unsuspiciousblock.journal.migration;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * 旧版玩家日志 NBT 的事务式迁移访问接口，由 Mixin 附加到 Player。
 * 源数据会一直保留到新分片与索引全部持久化成功，之后才允许确认清理。
 */
public interface LegacyJournalLogAccess {
    // 返回待迁移数据的副本；读取不会清除源数据
    @Nullable
    CompoundTag unsuspiciousblock$peekLegacyJournalLogTag();

    // 仅在全部目标数据持久化成功后确认清理源数据
    void unsuspiciousblock$acknowledgeLegacyJournalLogMigration();
}
