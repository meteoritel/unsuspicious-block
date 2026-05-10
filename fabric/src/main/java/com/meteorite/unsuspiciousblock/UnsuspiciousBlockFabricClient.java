package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.network.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.SyncJournalStatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class UnsuspiciousBlockFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ArchaeologyJournalUi.registerOpener(state -> Minecraft.getInstance().setScreen(new ArchaeologyJournalScreen(state)));

        ClientPlayNetworking.registerGlobalReceiver(SyncArchaeologyCatalogPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveCatalog(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncJournalStatePayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveState(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncJournalLogPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveLogUpdate(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncJournalLogSnapshotPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveLogSnapshot(payload));
        ClientTickEvents.END_CLIENT_TICK.register(client -> ArchaeologyJournalClientState.tick());
    }
}
