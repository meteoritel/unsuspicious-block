package com.meteorite.unsuspiciousblock.client.ui.journal;

import com.meteorite.unsuspiciousblock.client.ui.journal.ArchaeologyJournalCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.network.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.SyncJournalStatePayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 客户端缓存：保存服务端同步的目录和玩家状态 */
public final class ArchaeologyJournalClientState {
    private static final Map<ResourceLocation, TableDefinition> serverCatalog = new LinkedHashMap<>();
    private static final ArchaeologyJournalState journalState = new ArchaeologyJournalState();

    private ArchaeologyJournalClientState() {
    }

    /** 客户端接收全量目录 */
    public static void receiveCatalog(SyncArchaeologyCatalogPayload payload) {
        serverCatalog.clear();
        serverCatalog.putAll(payload.catalog());
    }

    /** 客户端接收玩家状态 */
    public static void receiveState(SyncJournalStatePayload payload) {
        if (payload.state() == null) return;
        journalState.readFrom(payload.state());
    }

    /** 获取服务端同步的目录（只读） */
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        return Collections.unmodifiableMap(serverCatalog);
    }

    /** 获取当前缓存的玩家状态 */
    public static ArchaeologyJournalState getState() {
        return journalState;
    }
}
