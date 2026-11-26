package com.meteorite.unsuspiciousblock.client;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState;
import com.meteorite.unsuspiciousblock.client.pan.PanningSoundController;
import com.meteorite.unsuspiciousblock.client.pan.PanningMediumIndex;
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
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.support.LootTableManagementClientState;
import com.meteorite.unsuspiciousblock.client.ui.toast.JournalUnlockToast;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import com.meteorite.unsuspiciousblock.core.item.SuspiciousReaderItem;
import com.meteorite.unsuspiciousblock.core.text.ClientTooltipBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;

import java.util.ArrayList;
import java.util.List;

/** 客户端安装、tick 与断连清理的公共生命周期入口。 */
public final class ModClientLifecycle {
    private static final ResourceLocation READER_MODEL_STATE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "reader_model_state");
    // 物品属性限制在 [0, 1]，为断电模型保留最高门槛。
    private static final float READER_SCAN_LEVEL_MODEL_STEP = 0.25F;
    private static final float READER_DEPLETED_MODEL_STATE = 1.0F;

    private ModClientLifecycle() {
    }

    /** 安装客户端能力，网络发送由当前 Loader 提供。 */
    public static void install(Consumer<CustomPacketPayload> packetSender, BooleanSupplier journalCapability) {
        ClientNetwork.install(packetSender, journalCapability);
        JournalClientConnection.registerConnectionReset(ClientLootTableLanguageStore::resetOnDisconnect);
        JournalClientConnection.registerConnectionReset(LootTableManagementClientState::reset);
        JournalClientConnection.registerConnectionReset(HandOfCatClientState::reset);
        JournalClientConnection.registerConnectionReset(ReaderScanHudState::reset);
        JournalClientConnection.registerConnectionReset(PanningSoundController::reset);
        JournalClientConnection.registerConnectionReset(EnchantmentRevealClientState::reset);
        ClientTooltipBridge.install(new ClientTooltipHooks());
        ClientLootTableLanguageStore.initialize();
        PanningVisuals.register();
        ItemProperties.register(ModItems.SUSPICIOUS_READER, READER_MODEL_STATE, (stack, level, entity, seed) -> {
            if (SuspiciousReaderItem.getEnergyOrDefault(stack) <= 0) {
                return READER_DEPLETED_MODEL_STATE;
            }
            return SuspiciousReaderItem.getScanLevel(stack) * READER_SCAN_LEVEL_MODEL_STEP;
        });
        ArchaeologyJournalUi.registerOpener(itemId ->
                Minecraft.getInstance().setScreen(new ArchaeologyJournalScreen(itemId)));
        ArchaeologyJournalClientState.registerTableUnlockNotifier(JournalUnlockToast::addTableUnlocks);
        ArchaeologyJournalClientState.registerTableCompletionNotifier(JournalUnlockToast::addTableCompletion);
        ArchaeologyJournalClientState.registerItemUnlockNotifier((names, icons) -> {
            List<JournalUnlockToast.Entry> entries = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                entries.add(new JournalUnlockToast.Entry(names.get(i), icons.get(i)));
            }
            JournalUnlockToast.addItemUnlocks(entries);
        });
        JournalClientConnection.registerLogEditNotifier(message -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(message, true);
            }
        });
    }

    /** 在客户端 tick 推进连接状态与表现索引。 */
    public static void tick() {
        JournalClientConnection.tick();
        PanningMediumIndex.refresh(Minecraft.getInstance());
        ArchaeologyJournalKeyHandler.tick();
        SuspiciousReaderClientState.tick();
        CatHandClientState.tick();
        ReaderScanHudState.tick();
        PanningSoundController.tick();
    }

    /** 保存旧路径待写数据并复位连接作用域状态。 */
    public static void disconnect() {
        JournalClientConnection.disconnect();
    }
}
