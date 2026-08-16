package com.meteorite.unsuspiciousblock.mixin.journal;

import com.meteorite.unsuspiciousblock.journal.migration.LegacyJournalLogAccess;
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
 * 正常日志不再写入玩家 NBT，仅在迁移未确认时保留旧 tag。
 * 暂存的旧版 tag 会在玩家登录时由 JournalDataMigrationManager 迁移到分片存储。
 * 迁移确认前继续写回玩家 NBT，全部目标数据落盘后才停止写回。
 */
@Mixin(Player.class)
public abstract class PlayerJournalLogStateMixin implements LegacyJournalLogAccess {
    @Unique
    private static final String UNSUSPICIOUSBLOCK_LEGACY_JOURNAL_LOG_TAG = "unsuspiciousblock_archaeology_journal_log";

    // 暂存从旧版 NBT 读取的日志 tag，等待登录时消费
    @Unique
    @Nullable
    private CompoundTag unsuspiciousblock$pendingLegacyLogTag;

    // 查看待迁移日志但不清除源数据，迁移器可以在失败后安全重试
    @Override
    @Nullable
    public CompoundTag unsuspiciousblock$peekLegacyJournalLogTag() {
        return this.unsuspiciousblock$pendingLegacyLogTag != null
                ? this.unsuspiciousblock$pendingLegacyLogTag.copy()
                : null;
    }

    // 目标分片与索引全部落盘后才清除待迁移源数据
    @Override
    public void unsuspiciousblock$acknowledgeLegacyJournalLogMigration() {
        this.unsuspiciousblock$pendingLegacyLogTag = null;
    }

    // 读取旧版 NBT tag 暂存，等待登录时迁移到 v2 分片存储
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$loadLegacyJournalLogTag(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains(UNSUSPICIOUSBLOCK_LEGACY_JOURNAL_LOG_TAG, Tag.TAG_COMPOUND)) {
            this.unsuspiciousblock$pendingLegacyLogTag = tag.getCompound(UNSUSPICIOUSBLOCK_LEGACY_JOURNAL_LOG_TAG);
        }
    }

    // 迁移尚未确认时继续写回旧 tag，避免玩家保存早于分片提交而丢失源数据
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$preservePendingLegacyJournalLogTag(CompoundTag tag, CallbackInfo ci) {
        if (this.unsuspiciousblock$pendingLegacyLogTag != null) {
            tag.put(UNSUSPICIOUSBLOCK_LEGACY_JOURNAL_LOG_TAG,
                    this.unsuspiciousblock$pendingLegacyLogTag.copy());
        }
    }
}
