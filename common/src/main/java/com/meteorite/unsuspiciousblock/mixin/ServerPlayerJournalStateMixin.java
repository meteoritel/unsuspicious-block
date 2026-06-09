package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogStateHolder;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSession;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为服务端玩家补充日志同步会话，并在重生时复制相关状态。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerJournalStateMixin implements ArchaeologyJournalLogSyncSessionHolder {
    @Unique
    private final ArchaeologyJournalLogSyncSession unsuspiciousblock$journalLogSyncSession = new ArchaeologyJournalLogSyncSession();

    // 返回服务端玩家当前的日志同步会话
    @Override
    public ArchaeologyJournalLogSyncSession unsuspiciousblock$getArchaeologyJournalLogSyncSession() {
        return this.unsuspiciousblock$journalLogSyncSession;
    }

    // 在玩家实体恢复时复制考古笔记状态、日志状态和日志同步会话
    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void unsuspiciousblock$copyJournalState(ServerPlayer oldPlayer, boolean alive, CallbackInfo ci) {
        if (oldPlayer instanceof ArchaeologyJournalStateHolder holder && this instanceof ArchaeologyJournalStateHolder self) {
            self.unsuspiciousblock$getArchaeologyJournalState().copyFrom(holder.unsuspiciousblock$getArchaeologyJournalState());
        }
        // 复制日志持久化状态（NBT 数据）
        if (oldPlayer instanceof ArchaeologyJournalLogStateHolder oldHolder
                && this instanceof ArchaeologyJournalLogStateHolder newHolder) {
            newHolder.unsuspiciousblock$getArchaeologyJournalLogState()
                    .copyFrom(oldHolder.unsuspiciousblock$getArchaeologyJournalLogState());
        }
        if (oldPlayer instanceof ArchaeologyJournalLogSyncSessionHolder holder) {
            ArchaeologyJournalLogSyncSessionHolder self = this;
            self.unsuspiciousblock$getArchaeologyJournalLogSyncSession()
                    .copyFrom(holder.unsuspiciousblock$getArchaeologyJournalLogSyncSession());
        }
    }
}
