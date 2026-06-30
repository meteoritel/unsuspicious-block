package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.client.renderer.ModEntityRenderers;
import com.meteorite.unsuspiciousblock.client.renderer.SuspiciousReaderRangeHighlight;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.state.CatHandClientState;
import com.meteorite.unsuspiciousblock.client.state.ArchaeologyJournalKeyHandler;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHighlightState;
import com.meteorite.unsuspiciousblock.client.state.SuspiciousReaderClientState;
import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.ui.screen.SpecimenBoxScreen;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.SpecimenBoxClientState;
import com.meteorite.unsuspiciousblock.client.ui.toast.JournalUnlockToast;
import com.meteorite.unsuspiciousblock.network.ModPayloads;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public class UnsuspiciousBlockFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 注册按键绑定
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.SCAN_LEVEL_CYCLE);
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.JOURNAL_OPEN);
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.CAT_DETERRENCE_TOGGLE);

        // 遍历渲染器清单，统一注册实体渲染器
        ModEntityRenderers.forEach(EntityRendererRegistry::register);

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

        // 注册 S2C 接收器：遍历 ModPayloads 客户端清单
        for (ModPayloads.Client.S2C<?> s2c : ModPayloads.Client.S2C_PAYLOADS) {
            registerS2C(s2c);
        }
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SpecimenBoxClientState.clearAll();
            ArchaeologyJournalClientState.resetOnDisconnect();
            HandOfCatClientState.reset();
            CatHandClientState.reset();
            ReaderScanHighlightState.reset();
            com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState.reset();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ArchaeologyJournalKeyHandler.tick();
            ArchaeologyJournalClientState.tick();
            SpecimenBoxClientState.tick();
            SuspiciousReaderClientState.tick();
            CatHandClientState.tick();
            ReaderScanHighlightState.tick();
        });

        // 半透明方块渲染之后绘制范围扫描高亮，实现透视效果
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
                SuspiciousReaderRangeHighlight.render(context.matrixStack(), context.camera()));
    }

    // 注册 S2C 客户端接收器
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerS2C(ModPayloads.Client.S2C<?> s2cRaw) {
        ModPayloads.Client.S2C<T> s2c = (ModPayloads.Client.S2C<T>) s2cRaw;
        ClientPlayNetworking.registerGlobalReceiver(s2c.type(),
                (payload, context) -> s2c.handler().accept(payload));
    }
}
