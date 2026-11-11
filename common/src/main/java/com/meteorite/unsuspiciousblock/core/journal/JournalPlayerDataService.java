package com.meteorite.unsuspiciousblock.core.journal;

import com.meteorite.unsuspiciousblock.core.journal.migration.JournalDataMigrationManager;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.core.journal.sync.ArchaeologyJournalLogSyncSession;
import com.meteorite.unsuspiciousblock.core.journal.sync.ArchaeologyJournalLogSyncSessionHolder;
import com.meteorite.unsuspiciousblock.core.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.core.world.JournalLogStorage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 玩家考古手册数据的统一管理入口。
 * <p>
 * 解锁进度仍由玩家 NBT 持久化，日志仍由分片存储持久化；本服务只统一二者的
 * 服务端生命周期、登录恢复和迁移顺序，不改变任何现有数据格式。
 */
public final class JournalPlayerDataService {
    private static final String PLAYER_TAG = "unsuspiciousblock_archaeology_journal";

    public static void writePlayerTag(ArchaeologyJournalState state, CompoundTag tag) {
        tag.put(PLAYER_TAG, state.toTag());
    }

    public static void readPlayerTag(ArchaeologyJournalState state, CompoundTag tag) {
        if (tag.contains(PLAYER_TAG, Tag.TAG_COMPOUND)) state.readFrom(tag.getCompound(PLAYER_TAG));
        else state.clear();
    }

    private JournalPlayerDataService() {
    }

    // 服务端启动时初始化日志存储并执行必要的存储布局迁移
    public static void onServerStarted(MinecraftServer server) {
        JournalLogStorage.start(server);
    }

    // 服务端停止前统一刷新日志分片
    public static void onServerStopped(MinecraftServer server) {
        JournalLogStorage.stop(server);
    }

    // 服务端 tick 驱动日志分片的限量落盘
    public static void onServerTick(MinecraftServer server) {
        JournalLogStorage.tick(server);
    }

    // 玩家登录：恢复日志、完成两类数据迁移，再统一下发客户端状态
    public static void onPlayerJoined(ServerPlayer player) {
        preparePlayerData(player);
        ArchaeologyJournalNetwork.syncPreparedPlayerOnJoin(player);
    }

    // 玩家退出：刷新并卸载该玩家的日志缓存；进度仍由原版玩家保存流程负责
    public static void onPlayerLeft(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            JournalLogStorage.unloadPlayer(server, player.getUUID());
        }
    }

    // 玩家重生会替换实体实例，两类运行时状态必须一起移交给新实体
    public static void copyForRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        if (oldPlayer instanceof ArchaeologyJournalStateHolder oldState
                && newPlayer instanceof ArchaeologyJournalStateHolder newState) {
            newState.unsuspiciousblock$getArchaeologyJournalState()
                    .copyFrom(oldState.unsuspiciousblock$getArchaeologyJournalState());
        }
        if (oldPlayer instanceof ArchaeologyJournalLogSyncSessionHolder oldSession
                && newPlayer instanceof ArchaeologyJournalLogSyncSessionHolder newSession) {
            newSession.unsuspiciousblock$getArchaeologyJournalLogSyncSession()
                    .copyFrom(oldSession.unsuspiciousblock$getArchaeologyJournalLogSyncSession());
        }
    }

    // 登录和日志快照恢复共用：建立服务端日志基线后统一执行玩家数据迁移
    public static void preparePlayerData(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        ArchaeologyJournalLogSyncSession session = getLogSession(player);
        if (server == null || session == null) {
            if (session != null) {
                session.reset();
            }
            return;
        }

        restoreLogState(server, player, session);
        JournalDataMigrationManager.migratePlayerData(player, session);
    }

    private static void restoreLogState(MinecraftServer server, ServerPlayer player,
                                        ArchaeologyJournalLogSyncSession session) {
        ArchaeologyJournalLogState persisted = JournalLogStorage.getPlayerState(server, player.getUUID());
        ArchaeologyJournalLogSyncSession.ReplayResult replay = session.restoreFromPersisted(persisted);
        if (!replay.hadQueuedMutations()) {
            return;
        }

        Set<ResourceLocation> tables = new LinkedHashSet<>(replay.affectedTables());
        if (replay.clearAll()) tables.addAll(session.mirroredState().getTables().keySet());
        JournalLogStorage.persistTablesNow(server, player.getUUID(), tables);
    }

    @Nullable
    private static ArchaeologyJournalLogSyncSession getLogSession(ServerPlayer player) {
        return player instanceof ArchaeologyJournalLogSyncSessionHolder holder
                ? holder.unsuspiciousblock$getArchaeologyJournalLogSyncSession()
                : null;
    }
}
