package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.client.state.SuspiciousReaderClientState;
import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.ui.screen.SpecimenBoxScreen;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.SpecimenBoxClientState;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncSpecimenBoxViewPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;

public class UnsuspiciousBlockFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 注册按键绑定
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.SCAN_LEVEL_CYCLE);

        ArchaeologyJournalUi.registerOpener(state -> Minecraft.getInstance().setScreen(new ArchaeologyJournalScreen(state)));
        MenuScreens.register(SpecimenBoxMenu.TYPE, SpecimenBoxScreen::new);

        ClientPlayNetworking.registerGlobalReceiver(SyncArchaeologyCatalogPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveCatalog(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncJournalStatePayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveState(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncJournalLogPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveLogUpdate(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncJournalLogSnapshotPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveLogSnapshot(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncSpecimenBoxViewPayload.TYPE,
                (payload, context) -> SpecimenBoxClientState.receiveView(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SpecimenBoxClientState.clearAll();
            ArchaeologyJournalClientState.resetOnDisconnect();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ArchaeologyJournalClientState.tick();
            SpecimenBoxClientState.tick();
            SuspiciousReaderClientState.tick();
        });
    }
}
