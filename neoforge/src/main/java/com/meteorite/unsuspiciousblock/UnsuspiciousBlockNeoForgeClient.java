package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.client.anvil.AnvilBreakdownTooltipAppender;
import com.meteorite.unsuspiciousblock.client.grindstone.GrindstoneBreakdownTooltipAppender;
import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.client.hud.CatFavorHud;
import com.meteorite.unsuspiciousblock.client.renderer.ModEntityRenderers;
import com.meteorite.unsuspiciousblock.client.renderer.ModModelLayers;
import com.meteorite.unsuspiciousblock.client.renderer.PotteryWheelRenderer;
import com.meteorite.unsuspiciousblock.client.renderer.SuspiciousReaderRangeHighlight;
import com.meteorite.unsuspiciousblock.client.renderer.CatFavorShieldRenderer;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.state.CatHandClientState;
import com.meteorite.unsuspiciousblock.client.state.ArchaeologyJournalKeyHandler;
import com.meteorite.unsuspiciousblock.client.state.ReaderScanHighlightState;
import com.meteorite.unsuspiciousblock.client.state.SuspiciousReaderClientState;
import com.meteorite.unsuspiciousblock.client.ui.ArchaeologyJournalUi;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.ui.screen.SpecimenBoxScreen;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.ClientSpecimenBoxTooltip;
import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import com.meteorite.unsuspiciousblock.client.ui.toast.JournalUnlockToast;
import com.meteorite.unsuspiciousblock.network.ModPayloads;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.pottery.PotteryWheelMenu;
import com.meteorite.unsuspiciousblock.blockentity.ModBlockEntities;
import com.meteorite.unsuspiciousblock.client.ui.screen.PotteryWheelScreen;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxTooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public final class UnsuspiciousBlockNeoForgeClient {
    private UnsuspiciousBlockNeoForgeClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientLootTableLanguageStore.initialize();
            ArchaeologyJournalUi.registerOpener((state, itemId) ->
                    Minecraft.getInstance().setScreen(new ArchaeologyJournalScreen(state, itemId)));
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
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onRenderGui);
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onScreenKeyPressed);
            // 铁砧结果槽 tooltip 成本分解：持有猫之瞳时追加分解行
            // 注意 ItemTooltipEvent 用 getToolTip()（历史拼写），返回可变列表可直接追加
            NeoForge.EVENT_BUS.addListener((ItemTooltipEvent tooltipEvent) -> {
                AnvilBreakdownTooltipAppender.appendIfApplicable(tooltipEvent.getItemStack(), tooltipEvent.getToolTip());
                GrindstoneBreakdownTooltipAppender.appendIfApplicable(tooltipEvent.getItemStack(), tooltipEvent.getToolTip());
            });
        });
    }

    private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (ArchaeologyJournalKeyHandler.handleScreenKey(
                event.getScreen(), event.getKeyCode(), event.getScanCode())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        // 直接从 DeferredHolder 获取 MenuType：FMLCommonSetupEvent 的 enqueueWork 可能尚未执行，
        // 此时 SpecimenBoxMenu.TYPE 静态字段可能为 null，直接用会导致注册失败
        SpecimenBoxMenu.TYPE = UnsuspiciousBlockNeoForge.getSpecimenBoxMenuType();
        event.register(SpecimenBoxMenu.TYPE, SpecimenBoxScreen::new);
        PotteryWheelMenu.TYPE = UnsuspiciousBlockNeoForge.getPotteryWheelMenuType();
        event.register(PotteryWheelMenu.TYPE, PotteryWheelScreen::new);
    }

    @SubscribeEvent
    public static void registerTooltipComponents(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(SpecimenBoxTooltip.class, ClientSpecimenBoxTooltip::new);
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ModKeyBindings.SCAN_LEVEL_CYCLE);
        event.register(ModKeyBindings.JOURNAL_OPEN);
        event.register(ModKeyBindings.CAT_DETERRENCE_TOGGLE);
        event.register(ModKeyBindings.CAT_LIGHT_STEP_TOGGLE);
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Constants.MOD_ID).versioned("4.0");
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
        // 实体类型 Supplier 已在 mod 构造器静态块中回写，此处直接遍历渲染器清单注册
        ModEntityRenderers.forEach(event::registerEntityRenderer);
        event.registerBlockEntityRenderer(ModBlockEntities.POTTERY_WHEEL.get(), PotteryWheelRenderer::new);
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        // 遍历模型层清单，统一注册 LayerDefinition（须在渲染器烘焙前完成）
        ModModelLayers.forEach(event::registerLayerDefinition);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        ArchaeologyJournalKeyHandler.tick();
        ArchaeologyJournalClientState.tick();
        SuspiciousReaderClientState.tick();
        CatHandClientState.tick();
        ReaderScanHighlightState.tick();
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ArchaeologyJournalClientState.resetOnDisconnect();
        ClientLootTableLanguageStore.resetOnDisconnect();
        HandOfCatClientState.reset();
        ReaderScanHighlightState.reset();
        com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState.reset();
    }

    // 在半透明方块渲染之后绘制范围扫描高亮与猫之恩惠保护罩，实现透视效果
    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        SuspiciousReaderRangeHighlight.render(event.getPoseStack(), event.getCamera());
        CatFavorShieldRenderer.render(event.getPoseStack(), event.getCamera());
    }

    // 渲染猫之恩惠快捷栏 HUD
    private static void onRenderGui(RenderGuiEvent.Post event) {
        GuiGraphics gui = event.getGuiGraphics();
        CatFavorHud.render(gui);
    }
}
