package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.client.state.SuspiciousReaderClientState;
import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.ui.screen.SpecimenBoxScreen;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.SpecimenBoxClientState;
import com.meteorite.unsuspiciousblock.client.ui.toast.JournalUnlockToast;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStateIncrementalPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncSpecimenBoxViewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = Constants.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class UnsuspiciousBlockNeoForgeClient {
    private UnsuspiciousBlockNeoForgeClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ArchaeologyJournalUi.registerOpener(state -> Minecraft.getInstance().setScreen(new ArchaeologyJournalScreen(state)));
            // 注册解锁通知回调：将 ClientState 的通知桥接到 Toast 弹窗
            ArchaeologyJournalClientState.registerTableUnlockNotifier(JournalUnlockToast::addTableUnlocks);
            ArchaeologyJournalClientState.registerItemUnlockNotifier((names, icons) -> {
                java.util.List<JournalUnlockToast.Entry> entries = new java.util.ArrayList<>();
                for (int i = 0; i < names.size(); i++) {
                    entries.add(new JournalUnlockToast.Entry(names.get(i), icons.get(i)));
                }
                JournalUnlockToast.addItemUnlocks(entries);
            });
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onClientTick);
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onClientLogout);
        });
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(SpecimenBoxMenu.TYPE, SpecimenBoxScreen::new);
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ModKeyBindings.SCAN_LEVEL_CYCLE);
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Constants.MOD_ID).versioned("2.0");
        registrar.playToClient(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC,
                (payload, context) -> ArchaeologyJournalClientState.receiveCatalog(payload));
        registrar.playToClient(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC,
                (payload, context) -> ArchaeologyJournalClientState.receiveState(payload));
        registrar.playToClient(SyncJournalStateIncrementalPayload.TYPE, SyncJournalStateIncrementalPayload.STREAM_CODEC,
                (payload, context) -> ArchaeologyJournalClientState.receiveStateIncremental(payload));
        registrar.playToClient(SyncJournalLogPayload.TYPE, SyncJournalLogPayload.STREAM_CODEC,
                (payload, context) -> ArchaeologyJournalClientState.receiveLogUpdate(payload));
        registrar.playToClient(SyncJournalLogSnapshotPayload.TYPE, SyncJournalLogSnapshotPayload.STREAM_CODEC,
                (payload, context) -> ArchaeologyJournalClientState.receiveLogSnapshot(payload));
        registrar.playToClient(SyncSpecimenBoxViewPayload.TYPE, SyncSpecimenBoxViewPayload.STREAM_CODEC,
                (payload, context) -> SpecimenBoxClientState.receiveView(payload));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        ArchaeologyJournalClientState.tick();
        SpecimenBoxClientState.tick();
        SuspiciousReaderClientState.tick();
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SpecimenBoxClientState.clearAll();
        ArchaeologyJournalClientState.resetOnDisconnect();
    }
}
