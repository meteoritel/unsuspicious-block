package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalStateHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为玩家实体附加考古笔记状态并负责存档。
 */
@Mixin(Player.class)
public abstract class PlayerJournalStateMixin implements ArchaeologyJournalStateHolder {
    @Unique
    private static final String UNSUSPICIOUSBLOCK_JOURNAL_TAG = "unsuspiciousblock_archaeology_journal";

    @Unique
    private final ArchaeologyJournalState unsuspiciousblock$journalState = new ArchaeologyJournalState();

    // 返回挂载在玩家身上的考古笔记状态
    @Override
    public ArchaeologyJournalState unsuspiciousblock$getArchaeologyJournalState() {
        return this.unsuspiciousblock$journalState;
    }

    // 在玩家保存附加数据时写入考古笔记状态
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$saveJournalState(CompoundTag tag, CallbackInfo ci) {
        tag.put(UNSUSPICIOUSBLOCK_JOURNAL_TAG, this.unsuspiciousblock$journalState.toTag());
    }

    // 在玩家读取附加数据时恢复考古笔记状态
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void unsuspiciousblock$loadJournalState(CompoundTag tag, CallbackInfo ci) {
        if (!tag.contains(UNSUSPICIOUSBLOCK_JOURNAL_TAG, Tag.TAG_COMPOUND)) {
            this.unsuspiciousblock$journalState.clear();
            return;
        }

        this.unsuspiciousblock$journalState.readFrom(tag.getCompound(UNSUSPICIOUSBLOCK_JOURNAL_TAG));
    }
}
