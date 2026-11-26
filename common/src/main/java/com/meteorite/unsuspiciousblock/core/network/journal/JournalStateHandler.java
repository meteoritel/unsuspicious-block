package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.core.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestJournalStateFullPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalTransferPayload.Kind;
import net.minecraft.nbt.CompoundTag;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/** 发布个人进度基线和实际更新的表，发布顺序随连接延续。 */
public final class JournalStateHandler {
    private JournalStateHandler() {}

    /** 受理脏表作为一个进度修订。 */
    public static void syncState(ServerPlayer player) {
        if (!Services.NETWORK.supportsJournal(player) || !(player instanceof ArchaeologyJournalStateHolder holder)
                || !(player instanceof ArchaeologyJournalLogSyncSessionHolder sessions)) return;
        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        Set<ResourceLocation> dirty = state.drainDirtyTables();
        if (dirty.isEmpty()) return;
        var session = sessions.unsuspiciousblock$getArchaeologyJournalLogSyncSession();
        Kind kind = Kind.PROGRESS_CHANGE;
        session.progress().offer(kind, state.getRevision(), session.progressTickets().latest(),
                JournalWireNbt.encode(state.writeDirtyTablesToTag(dirty), kind.maxBytes()));
    }

    /** 登录、破坏性更新及恢复均以当前修订覆盖既有队列。 */
    public static void syncStateFull(ServerPlayer player) {
        if (!Services.NETWORK.supportsJournal(player) || !(player instanceof ArchaeologyJournalStateHolder holder)
                || !(player instanceof ArchaeologyJournalLogSyncSessionHolder sessions)) return;
        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        var session = sessions.unsuspiciousblock$getArchaeologyJournalLogSyncSession();
        state.drainDirtyTables();
        CompoundTag document = state.writeDirtyTablesToTag(state.getTables().keySet());
        Kind kind = Kind.PROGRESS_BASELINE;
        session.progress().clear();
        session.progress().offer(kind, state.getRevision(), session.progressTickets().latest(),
                JournalWireNbt.encode(document, kind.maxBytes()));
    }

    /** 新票据的全量响应覆盖在途旧数据。 */
    public static void handleRequestFull(ServerPlayer player, RequestJournalStateFullPayload request) {
        if (player instanceof ArchaeologyJournalLogSyncSessionHolder holders
                && holders.unsuspiciousblock$getArchaeologyJournalLogSyncSession().progressTickets().advance(request.ticket())) {
            syncStateFull(player);
        }
    }

    /** 重生后继承旧队列并交付尚未发布的变化。 */
    public static void tick(ServerPlayer player) {
        if (player instanceof ArchaeologyJournalLogSyncSessionHolder holders) {
            syncState(player);
            holders.unsuspiciousblock$getArchaeologyJournalLogSyncSession().progress().tick(player);
        }
    }
}
