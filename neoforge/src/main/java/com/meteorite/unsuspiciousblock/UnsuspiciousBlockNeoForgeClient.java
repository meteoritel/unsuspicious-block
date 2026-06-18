package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.client.renderer.ModEntityRenderers;
import com.meteorite.unsuspiciousblock.client.renderer.SuspiciousReaderRangeHighlight;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.state.CatHandClientState;
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
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

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
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onRenderLevelStage);
        });
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(SpecimenBoxMenu.TYPE, SpecimenBoxScreen::new);
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ModKeyBindings.SCAN_LEVEL_CYCLE);
        event.register(ModKeyBindings.JOURNAL_OPEN);
        event.register(ModKeyBindings.CAT_DETERRENCE_TOGGLE);
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Constants.MOD_ID).versioned("2.0");
        // 遍历 ModPayloads 客户端清单注册 S2C，避免手写重复
        for (ModPayloads.Client.S2C<?> s2c : ModPayloads.Client.S2C_PAYLOADS) {
            registerS2C(registrar, s2c);
        }
    }

    // 注册单个 S2C payload 到 NeoForge 网络注册器
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerS2C(
            PayloadRegistrar registrar,
            ModPayloads.Client.S2C<?> s2cRaw) {
        ModPayloads.Client.S2C<T> s2c = (ModPayloads.Client.S2C<T>) s2cRaw;
        registrar.playToClient(s2c.type(), s2c.streamCodec(),
                (payload, context) -> s2c.handler().accept(payload));
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 渲染器注册可能早于 FMLCommonSetupEvent 的回写，这里先确保实体类型静态字段已回写
        UnsuspiciousBlockNeoForge.syncEntityRefs();
        // 遍历渲染器清单，统一注册实体渲染器
        ModEntityRenderers.forEach(event::registerEntityRenderer);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        ArchaeologyJournalClientState.tick();
        SpecimenBoxClientState.tick();
        SuspiciousReaderClientState.tick();
        CatHandClientState.tick();
        ReaderScanHighlightState.tick();
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SpecimenBoxClientState.clearAll();
        ArchaeologyJournalClientState.resetOnDisconnect();
        HandOfCatClientState.reset();
        CatHandClientState.reset();
        ReaderScanHighlightState.reset();
    }

    // 在半透明方块渲染之后绘制范围扫描高亮，实现透视效果
    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        SuspiciousReaderRangeHighlight.render(event.getPoseStack(), event.getCamera());
    }
}
