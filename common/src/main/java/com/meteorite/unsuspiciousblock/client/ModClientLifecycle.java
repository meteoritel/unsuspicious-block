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
import com.meteorite.unsuspiciousblock.core.text.ClientTooltipBridge;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.platform.OptionalModIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.function.Consumer;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;

import java.util.ArrayList;
import java.util.List;

/** 客户端安装、tick 与断连清理的公共生命周期入口。 */
public final class ModClientLifecycle {
    private ModClientLifecycle() {
    }

    /** 安装客户端能力，网络发送由当前 Loader 提供。 */
    public static void install(Consumer<CustomPacketPayload> packetSender) {
        ClientNetwork.install(packetSender);
        if (Services.PLATFORM.isModLoaded("jade")) {
            OptionalModIntegration.instantiate(
                    "com.meteorite.unsuspiciousblock.client.plugin.jade.JadeClientIntegration",
                    Runnable.class).run();
        }
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
