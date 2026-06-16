package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.client.renderer.EntityRendererRegistrar;
import com.meteorite.unsuspiciousblock.client.renderer.ModEntityRenderers;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.state.SuspiciousReaderClientState;
import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.ui.screen.SpecimenBoxScreen;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.SpecimenBoxClientState;
import com.meteorite.unsuspiciousblock.client.ui.toast.JournalUnlockToast;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStateIncrementalPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncSpecimenBoxViewPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatFavorPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

public class UnsuspiciousBlockFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 注册按键绑定
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.SCAN_LEVEL_CYCLE);
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.JOURNAL_OPEN);

        // 遍历渲染器清单，统一注册实体渲染器
        ModEntityRenderers.forEach(new EntityRendererRegistrar() {
            @Override
            public <T extends Entity> void register(EntityType<T> type, EntityRendererProvider<T> provider) {
                EntityRendererRegistry.register(type, provider);
            }
        });

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
        MenuScreens.register(SpecimenBoxMenu.TYPE, SpecimenBoxScreen::new);

        ClientPlayNetworking.registerGlobalReceiver(SyncArchaeologyCatalogPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveCatalog(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncCatalogHashPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveCatalogHash(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncJournalStatePayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveState(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncJournalStateIncrementalPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveStateIncremental(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncJournalLogPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveLogUpdate(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncJournalLogSnapshotPayload.TYPE,
                (payload, context) -> ArchaeologyJournalClientState.receiveLogSnapshot(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncSpecimenBoxViewPayload.TYPE,
                (payload, context) -> SpecimenBoxClientState.receiveView(payload));
        ClientPlayNetworking.registerGlobalReceiver(SyncCatFavorPayload.TYPE,
                (payload, context) -> HandOfCatClientState.receive(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SpecimenBoxClientState.clearAll();
            ArchaeologyJournalClientState.resetOnDisconnect();
            HandOfCatClientState.reset();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ArchaeologyJournalClientState.tick();
            SpecimenBoxClientState.tick();
            SuspiciousReaderClientState.tick();
        });
    }
}
