package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStateIncrementalPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/** 玩家进度状态同步处理器 */
public final class JournalStateHandler {
    private JournalStateHandler() {}

    // 向玩家增量同步考古日记状态（仅发送变更的表进度）
    public static void syncState(ServerPlayer player) {
        if (!(player instanceof ArchaeologyJournalStateHolder holder)) return;

        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        Set<ResourceLocation> dirty = state.drainDirtyTables();
        if (dirty.isEmpty()) {
            return;
        }
        net.minecraft.nbt.CompoundTag changedTag = state.writeDirtyTablesToTag(dirty);
        Services.NETWORK.sendToPlayer(player,
                new SyncJournalStateIncrementalPayload(state.getRevision(), changedTag));
    }

    // 向玩家全量同步考古日记状态（用于玩家加入/重连场景）
    public static void syncStateFull(ServerPlayer player) {
        if (!(player instanceof ArchaeologyJournalStateHolder holder)) return;

        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        state.drainDirtyTables(); // 清空脏标记，避免后续增量同步重复发送
        Services.NETWORK.sendToPlayer(player,
                new SyncJournalStatePayload(state.getRevision(), state.toTag()));
    }

    // 处理客户端主动请求的全量重同步（revision 间隙恢复路径）
    public static void handleRequestFull(ServerPlayer player,
                                         com.meteorite.unsuspiciousblock.network.payload.c2s.RequestJournalStateFullPayload payload) {
        syncStateFull(player);
    }
}