package com.meteorite.unsuspiciousblock.journal.state;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * 旧版考古日志 NBT 迁移访问接口——通过 mixin 附加到 Player。
 * 仅用于在玩家登录时消费残留的旧版 NBT tag（{@code unsuspiciousblock_archaeology_journal_log}），
 * 将其迁移到 {@link com.meteorite.unsuspiciousblock.world.JournalLogSavedData}。
 * 迁移完成后该 tag 不再写回玩家 NBT，自然剥离。
 *
 * @deprecated 将于 1.5.0 移除，旧版 NBT 迁移完成后不再需要此接口
 */
@Deprecated
public interface ArchaeologyJournalLogLegacyAccess {
    // 消费并返回暂存的旧版日志 NBT tag；无暂存数据时返回 null
    @Nullable
    CompoundTag unsuspiciousblock$consumeLegacyJournalLogTag();
}
