package com.meteorite.unsuspiciousblock.mixin.journal;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogLegacyAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 旧版考古日志 NBT 迁移 mixin——仅负责读取并暂存旧版玩家 NBT 中的日志数据。
 * 不再向玩家 NBT 写回日志数据（持久化已迁移到 JournalLogSavedData）。
 * 暂存的旧版 tag 会在玩家登录时由 JournalLogHandler 消费并迁移到 SavedData，
 * 之后由于 addAdditionalSaveData 不再注入，旧 tag 自然从玩家 NBT 中剥离。
 *
 * @deprecated 将于 1.5.0 移除，旧版 NBT 迁移完成后不再需要此 Mixin
 */
@Deprecated
@Mixin(Player.class)
public abstract class PlayerJournalLogStateMixin implements ArchaeologyJournalLogLegacyAccess {
    @Unique
    @Deprecated // 将于 1.5.0 移除
    private static final String UNSUSPICIOUSBLOCK_LEGACY_JOURNAL_LOG_TAG = "unsuspiciousblock_archaeology_journal_log";

    // 暂存从旧版 NBT 读取的日志 tag，等待登录时消费
    @Unique
    @Nullable
    private CompoundTag unsuspiciousblock$pendingLegacyLogTag;

    // 消费并返回暂存的旧版日志 tag
    @Override
    @Nullable
    public CompoundTag unsuspiciousblock$consumeLegacyJournalLogTag() {
        CompoundTag tag = this.unsuspiciousblock$pendingLegacyLogTag;
        this.unsuspiciousblock$pendingLegacyLogTag = null;
        return tag;
    }

    // 读取旧版 NBT tag 暂存，等待登录时迁移到 SavedData
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$loadLegacyJournalLogTag(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains(UNSUSPICIOUSBLOCK_LEGACY_JOURNAL_LOG_TAG, Tag.TAG_COMPOUND)) {
            this.unsuspiciousblock$pendingLegacyLogTag = tag.getCompound(UNSUSPICIOUSBLOCK_LEGACY_JOURNAL_LOG_TAG);
        }
    }
}
