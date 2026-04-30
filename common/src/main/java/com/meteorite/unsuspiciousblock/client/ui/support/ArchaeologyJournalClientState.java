package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.network.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.SyncJournalStatePayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 客户端缓存：保存服务端同步的目录和玩家状态 */
public final class ArchaeologyJournalClientState {
    private static volatile Map<ResourceLocation, TableDefinition> serverCatalog = Collections.emptyMap();
    private static volatile ArchaeologyJournalState journalState = new ArchaeologyJournalState();
    private static final AtomicLong catalogRevision = new AtomicLong();
    private static final AtomicLong stateRevision = new AtomicLong();

    private ArchaeologyJournalClientState() {
    }

    /** 客户端接收全量目录 */
    public static void receiveCatalog(SyncArchaeologyCatalogPayload payload) {
        serverCatalog = Collections.unmodifiableMap(new LinkedHashMap<>(payload.catalog()));
        catalogRevision.incrementAndGet();
    }

    /** 客户端接收玩家状态 */
    public static void receiveState(SyncJournalStatePayload payload) {
        if (payload.state() == null) return;
        ArchaeologyJournalState updated = new ArchaeologyJournalState();
        updated.readFrom(payload.state());
        journalState = updated;
        stateRevision.incrementAndGet();
    }

    /** 获取服务端同步的目录（只读） */
    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        return serverCatalog;
    }

    public static long getCatalogRevision() {
        return catalogRevision.get();
    }

    public static long getStateRevision() {
        return stateRevision.get();
    }

    /** 获取当前缓存的玩家状态 */
    public static ArchaeologyJournalState getState() {
        return journalState;
    }
}
