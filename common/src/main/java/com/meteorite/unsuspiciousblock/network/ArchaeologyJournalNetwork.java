package com.meteorite.unsuspiciousblock.network;

import com.meteorite.unsuspiciousblock.client.ui.journal.ArchaeologyJournalCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** 网络同步辅助方法 */
public final class ArchaeologyJournalNetwork {

    private ArchaeologyJournalNetwork() {
    }

    /** 玩家加入时调用：发送全量目录 + 玩家状态 */
    public static void syncOnJoin(ServerPlayer player) {
        syncCatalog(player);
        syncState(player);
    }

    /** 发送全量目录 */
    public static void syncCatalog(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getCatalog();
        if (catalog.isEmpty()) return;

        Services.NETWORK.sendToPlayer(player, new SyncArchaeologyCatalogPayload(catalog));
    }

    /** 发送玩家状态（解锁/计数变更后调用） */
    public static void syncState(ServerPlayer player) {
        if (!(player instanceof ArchaeologyJournalStateHolder holder)) return;

        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        Services.NETWORK.sendToPlayer(player, new SyncJournalStatePayload(state.toTag()));
    }

    /** 数据包重载后调用：重新加载服务端目录并广播给所有在线玩家 */
    public static void onDataPackReload(MinecraftServer server) {
        ArchaeologyJournalServerCatalog.invalidate();
        ArchaeologyJournalServerCatalog.ensureLoaded(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncCatalog(player);
        }
    }
}
