package com.meteorite.unsuspiciousblock.mixin.journal;

import com.meteorite.unsuspiciousblock.journal.JournalPlayerDataService;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSession;
import com.meteorite.unsuspiciousblock.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为服务端玩家补充日志同步会话，并将重生生命周期转交给统一玩家手册数据服务。
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

    // 玩家实体恢复时由统一数据服务移交解锁进度和日志同步会话
    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void unsuspiciousblock$copyJournalState(ServerPlayer oldPlayer, boolean alive, CallbackInfo ci) {
        JournalPlayerDataService.copyForRespawn(oldPlayer, (ServerPlayer) (Object) this);
    }
}
