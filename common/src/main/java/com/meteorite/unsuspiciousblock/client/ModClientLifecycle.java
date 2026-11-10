package com.meteorite.unsuspiciousblock.client;

import com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState;
import com.meteorite.unsuspiciousblock.client.pan.PanningSoundController;
import com.meteorite.unsuspiciousblock.client.pan.PanningVisuals;
import com.meteorite.unsuspiciousblock.client.state.ArchaeologyJournalKeyHandler;
import com.meteorite.unsuspiciousblock.client.state.CatHandClientState;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHudState;
import com.meteorite.unsuspiciousblock.client.state.SuspiciousReaderClientState;
import com.meteorite.unsuspiciousblock.client.tooltip.ClientTooltipHooks;
import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.client.ui.toast.JournalUnlockToast;
import com.meteorite.unsuspiciousblock.text.ClientTooltipBridge;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class ModClientLifecycle {
    private ModClientLifecycle() {
    }

    public static void install() {
        ClientTooltipBridge.install(new ClientTooltipHooks());
        ClientLootTableLanguageStore.initialize();
        PanningVisuals.register();
        ArchaeologyJournalUi.registerOpener((state, itemId) ->
                Minecraft.getInstance().setScreen(new ArchaeologyJournalScreen(state, itemId)));
        ArchaeologyJournalClientState.registerTableUnlockNotifier(JournalUnlockToast::addTableUnlocks);
        ArchaeologyJournalClientState.registerTableCompletionNotifier(JournalUnlockToast::addTableCompletion);
        ArchaeologyJournalClientState.registerItemUnlockNotifier((names, icons) -> {
            List<JournalUnlockToast.Entry> entries = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                entries.add(new JournalUnlockToast.Entry(names.get(i), icons.get(i)));
            }
            JournalUnlockToast.addItemUnlocks(entries);
        });
    }

    public static void tick() {
        ArchaeologyJournalClientState.tick();
        ArchaeologyJournalKeyHandler.tick();
        SuspiciousReaderClientState.tick();
        CatHandClientState.tick();
        ReaderScanHudState.tick();
        PanningSoundController.tick();
    }

    public static void disconnect() {
        ArchaeologyJournalClientState.resetOnDisconnect();
        ClientLootTableLanguageStore.resetOnDisconnect();
        LootTableManagementClientState.reset();
        HandOfCatClientState.reset();
        ReaderScanHudState.reset();
        PanningSoundController.reset();
        EnchantmentRevealClientState.reset();
    }
}
