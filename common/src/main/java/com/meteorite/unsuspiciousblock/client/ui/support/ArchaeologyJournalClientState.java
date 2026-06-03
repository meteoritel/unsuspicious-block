package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalLogState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.network.payload.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalStatePayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class ArchaeologyJournalClientState {
    private static volatile Map<ResourceLocation, TableDefinition> serverCatalog = Collections.emptyMap();
    private static volatile ArchaeologyJournalState journalState = new ArchaeologyJournalState();
    @Nullable
    private static volatile ResourceLocation lastSelectedTableId;
    private static final AtomicLong catalogRevision = new AtomicLong();
    private static final AtomicLong stateRevision = new AtomicLong();

    private ArchaeologyJournalClientState() {
    }

    public static void receiveCatalog(SyncArchaeologyCatalogPayload payload) {
        ArchaeologyJournalLogLocalStore.tick();
        serverCatalog = Collections.unmodifiableMap(new LinkedHashMap<>(payload.catalog()));
        catalogRevision.incrementAndGet();
    }

    public static void receiveState(SyncJournalStatePayload payload) {
        ArchaeologyJournalLogLocalStore.tick();
        if (payload.state() == null) return;
        ArchaeologyJournalState updated = new ArchaeologyJournalState();
        updated.readFrom(payload.state());
        journalState = updated;
        stateRevision.incrementAndGet();
    }

    public static void receiveLogUpdate(SyncJournalLogPayload payload) {
        ArchaeologyJournalLogLocalStore.applyUpdate(payload);
    }

    public static void receiveLogSnapshot(SyncJournalLogSnapshotPayload payload) {
        ArchaeologyJournalLogLocalStore.applySnapshot(payload);
    }

    public static void tick() {
        ArchaeologyJournalLogLocalStore.tick();
    }

    public static Map<ResourceLocation, TableDefinition> getCatalog() {
        return serverCatalog;
    }

    public static long getCatalogRevision() {
        return catalogRevision.get();
    }

    public static long getStateRevision() {
        return stateRevision.get();
    }

    public static long getLogRevision() {
        return ArchaeologyJournalLogLocalStore.getRevision();
    }

    public static ArchaeologyJournalState getState() {
        return journalState;
    }

    public static ArchaeologyJournalLogState getLogState() {
        return ArchaeologyJournalLogLocalStore.getState().copy();
    }

    @Nullable
    public static ResourceLocation getLastSelectedTableId() {
        return lastSelectedTableId;
    }

    public static void rememberLastSelectedTable(@Nullable ResourceLocation tableId) {
        lastSelectedTableId = tableId;
    }
}
