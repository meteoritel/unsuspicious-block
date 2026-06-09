package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogStateHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为玩家实体附加考古日志状态并负责存档。
 * 日志数据持久化到玩家 NBT，服务端成为日志数据的权威来源。
 */
@Mixin(Player.class)
public abstract class PlayerJournalLogStateMixin implements ArchaeologyJournalLogStateHolder {
    @Unique
    private static final String UNSUSPICIOUSBLOCK_JOURNAL_LOG_TAG = "unsuspiciousblock_archaeology_journal_log";

    @Unique
    private final ArchaeologyJournalLogState unsuspiciousblock$journalLogState = new ArchaeologyJournalLogState();

    // 返回挂载在玩家身上的考古日志状态
    @Override
    public ArchaeologyJournalLogState unsuspiciousblock$getArchaeologyJournalLogState() {
        return this.unsuspiciousblock$journalLogState;
    }

    // 在玩家保存附加数据时写入考古日志状态
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$saveJournalLogState(CompoundTag tag, CallbackInfo ci) {
        tag.put(UNSUSPICIOUSBLOCK_JOURNAL_LOG_TAG, this.unsuspiciousblock$journalLogState.toTag());
    }

    // 在玩家读取附加数据时恢复考古日志状态
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$loadJournalLogState(CompoundTag tag, CallbackInfo ci) {
        if (!tag.contains(UNSUSPICIOUSBLOCK_JOURNAL_LOG_TAG, Tag.TAG_COMPOUND)) {
            this.unsuspiciousblock$journalLogState.clear();
            return;
        }
        this.unsuspiciousblock$journalLogState.readFrom(tag.getCompound(UNSUSPICIOUSBLOCK_JOURNAL_LOG_TAG));
    }
}