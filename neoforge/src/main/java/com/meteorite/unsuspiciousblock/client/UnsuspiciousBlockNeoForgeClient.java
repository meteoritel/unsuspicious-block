package com.meteorite.unsuspiciousblock.client;


import com.meteorite.unsuspiciousblock.Constants;

import com.meteorite.unsuspiciousblock.client.tooltip.BreakdownPreview;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.ModTooltipComponents;
import com.meteorite.unsuspiciousblock.client.pan.PanningAnimation;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.client.hud.ModHudRenderers;
import com.meteorite.unsuspiciousblock.client.renderer.ModBlockEntityRenderers;
import com.meteorite.unsuspiciousblock.client.renderer.ModEntityRenderers;
import com.meteorite.unsuspiciousblock.client.renderer.ModModelLayers;
import com.meteorite.unsuspiciousblock.client.renderer.ModWorldRenderers;
import com.meteorite.unsuspiciousblock.client.state.ArchaeologyJournalKeyHandler;
import com.meteorite.unsuspiciousblock.client.ui.screen.ModMenuScreens;
import com.meteorite.unsuspiciousblock.client.network.ClientPayloadBindings;
import com.meteorite.unsuspiciousblock.core.network.ModPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
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
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public final class UnsuspiciousBlockNeoForgeClient {
    private UnsuspiciousBlockNeoForgeClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ModClientLifecycle.install(PacketDistributor::sendToServer);
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onClientTick);
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onRenderHand);
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onClientLogout);
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onRenderLevelStage);
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onRenderGui);
            NeoForge.EVENT_BUS.addListener(UnsuspiciousBlockNeoForgeClient::onScreenKeyPressed);
            // 注意 ItemTooltipEvent 用 getToolTip()（历史拼写），返回可变列表可直接追加
            NeoForge.EVENT_BUS.addListener((ItemTooltipEvent tooltipEvent) ->
                    BreakdownPreview.appendRegistered(tooltipEvent.getItemStack(), tooltipEvent.getToolTip()));
        });
    }

    // NeoForge 原生事件入口，与 Fabric 共用淘盘动画实现。
    private static void onRenderHand(RenderHandEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (PanningAnimation.renderFirstPerson(client.gameRenderer.itemInHandRenderer, client.player,
                event.getHand(), event.getItemStack(), event.getPartialTick(), event.getEquipProgress(),
                event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight())) {
            event.setCanceled(true);
        }
    }

    private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (ArchaeologyJournalKeyHandler.handleScreenKey(
                event.getScreen(), event.getKeyCode(), event.getScanCode())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        ModMenuScreens.forEach(new ModMenuScreens.ScreenRegistrar() {
            @Override
            public <T extends AbstractContainerMenu> void register(
                    MenuType<T> type,
                    ModMenuScreens.ScreenFactory<T> factory) {
                event.register(type, factory::create);
            }
        });
    }

    @SubscribeEvent
    public static void registerTooltipComponents(RegisterClientTooltipComponentFactoriesEvent event) {
        for (var entry : ModTooltipComponents.MANIFEST) {
            registerTooltipComponent(event, entry);
        }
    }

    private static <T extends TooltipComponent> void registerTooltipComponent(
            RegisterClientTooltipComponentFactoriesEvent event,
            ModTooltipComponents.ComponentEntry<T> entry) {
        event.register(entry.type(), entry.factory());
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        for (var keyMapping : ModKeyBindings.MANIFEST) {
            event.register(keyMapping);
        }
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Constants.MOD_ID).versioned(ModPayloads.PROTOCOL_VERSION);
        // 接收绑定统一经过连接宿主，保留入队与 epoch 门禁。
        for (ClientPayloadBindings.Binding<?> binding : ClientPayloadBindings.all()) {
            registerS2C(registrar, binding);
        }
    }

    // 注册单个 S2C payload 到 NeoForge 网络注册器
    private static <T extends CustomPacketPayload> void registerS2C(
            PayloadRegistrar registrar,
            ClientPayloadBindings.Binding<T> binding) {
        registrar.playToClient(binding.declaration().type(), binding.declaration().streamCodec(),
                (payload, context) -> {
                    if (context.listener() instanceof ClientPacketListener source) {
                        JournalClientConnection.receive(source, () -> binding.receiver().accept(payload));
                    }
                });
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 实体类型 Supplier 已在 mod 构造器静态块中回写，此处直接遍历渲染器清单注册
        ModEntityRenderers.forEach(event::registerEntityRenderer);
        ModBlockEntityRenderers.forEach(event::registerBlockEntityRenderer);
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        // 遍历模型层清单，统一注册 LayerDefinition（须在渲染器烘焙前完成）
        ModModelLayers.forEach(event::registerLayerDefinition);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        ModClientLifecycle.tick();
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ModClientLifecycle.disconnect();
    }

    // 在半透明方块渲染之后绘制范围扫描高亮与猫之恩惠保护罩，实现透视效果
    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        ModWorldRenderers.renderAll(event.getPoseStack(), event.getCamera());
    }

    // 渲染猫之恩惠快捷栏与解析仪扫描结果 HUD
    private static void onRenderGui(RenderGuiEvent.Post event) {
        GuiGraphics gui = event.getGuiGraphics();
        ModHudRenderers.renderAll(gui);
    }
}
