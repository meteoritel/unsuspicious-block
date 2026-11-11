package com.meteorite.unsuspiciousblock.client;

import com.meteorite.unsuspiciousblock.client.tooltip.BreakdownPreview;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.ModTooltipComponents;
import com.meteorite.unsuspiciousblock.client.keybind.ModKeyBindings;
import com.meteorite.unsuspiciousblock.client.hud.ModHudRenderers;
import com.meteorite.unsuspiciousblock.client.renderer.ModBlockEntityRenderers;
import com.meteorite.unsuspiciousblock.client.renderer.ModEntityRenderers;
import com.meteorite.unsuspiciousblock.client.renderer.ModModelLayers;
import com.meteorite.unsuspiciousblock.client.renderer.ModWorldRenderers;
import com.meteorite.unsuspiciousblock.client.state.ArchaeologyJournalKeyHandler;
import com.meteorite.unsuspiciousblock.client.ui.screen.ModMenuScreens;
import com.meteorite.unsuspiciousblock.client.network.ClientPayloadBindings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

public class UnsuspiciousBlockFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModClientLifecycle.install(ClientPlayNetworking::send);
        // 注册按键绑定
        for (var keyMapping : ModKeyBindings.MANIFEST) {
            KeyBindingHelper.registerKeyBinding(keyMapping);
        }

        // 遍历渲染器清单，统一注册实体渲染器
        ModEntityRenderers.forEach(EntityRendererRegistry::register);
        ModBlockEntityRenderers.forEach(BlockEntityRenderers::register);

        // 遍历模型层清单，统一注册 LayerDefinition
        // fabric 用 TexturedModelDataProvider 函数式接口，通过 supplier::get 桥接
        ModModelLayers.forEach((location, supplier) ->
                EntityModelLayerRegistry.registerModelLayer(location, supplier::get));
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                ScreenKeyboardEvents.allowKeyPress(screen).register((current, keyCode, scanCode, modifiers) ->
                        !ArchaeologyJournalKeyHandler.handleScreenKey(current, keyCode, scanCode)));
        // Menu 注册完成后解析 Screen 清单中的类型。
        ModMenuScreens.forEach(new ModMenuScreens.ScreenRegistrar() {
            @Override
            public <T extends AbstractContainerMenu> void register(
                    MenuType<T> type,
                    ModMenuScreens.ScreenFactory<T> factory) {
                MenuScreens.register(type, factory::create);
            }
        });
        TooltipComponentCallback.EVENT.register(ModTooltipComponents::create);

        // 接收绑定统一经过连接宿主，保留入队与 epoch 门禁。
        for (ClientPayloadBindings.Binding<?> binding : ClientPayloadBindings.all()) {
            registerS2C(binding);
        }
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ModClientLifecycle.disconnect());

        ClientTickEvents.END_CLIENT_TICK.register(client -> ModClientLifecycle.tick());

        // 半透明方块渲染之后绘制范围扫描高亮与猫之恩惠保护罩，实现透视效果
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
                ModWorldRenderers.renderAll(context.matrixStack(), context.camera()));

        // 注册快捷栏状态与解析仪扫描结果 HUD
        HudRenderCallback.EVENT.register((guiGraphics, tickCounter) -> ModHudRenderers.renderAll(guiGraphics));

        ItemTooltipCallback.EVENT.register((stack, context, type, lines) ->
                BreakdownPreview.appendRegistered(stack, lines));
    }

    // 注册 S2C 客户端接收器
    private static <T extends CustomPacketPayload> void registerS2C(ClientPayloadBindings.Binding<T> binding) {
        ClientPlayNetworking.registerGlobalReceiver(binding.declaration().type(),
                (payload, context) -> JournalClientConnection.receive(context.player().connection,
                        () -> binding.receiver().accept(payload)));
    }
}
