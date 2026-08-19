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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public class UnsuspiciousBlockFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientLootTableLanguageStore.initialize();
        // 注册按键绑定
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.SCAN_LEVEL_CYCLE);
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.JOURNAL_OPEN);
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.CAT_DETERRENCE_TOGGLE);
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.CAT_LIGHT_STEP_TOGGLE);

        // 遍历渲染器清单，统一注册实体渲染器
        ModEntityRenderers.forEach(EntityRendererRegistry::register);
        BlockEntityRenderers.register(ModBlockEntities.POTTERY_WHEEL.get(), PotteryWheelRenderer::new);

        // 遍历模型层清单，统一注册 LayerDefinition
        // fabric 用 TexturedModelDataProvider 函数式接口，通过 supplier::get 桥接
        ModModelLayers.forEach((location, supplier) ->
                EntityModelLayerRegistry.registerModelLayer(location, supplier::get));

        ArchaeologyJournalUi.registerOpener((state, itemId) ->
                Minecraft.getInstance().setScreen(new ArchaeologyJournalScreen(state, itemId)));
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                ScreenKeyboardEvents.allowKeyPress(screen).register((current, keyCode, scanCode, modifiers) ->
                        !ArchaeologyJournalKeyHandler.handleScreenKey(current, keyCode, scanCode)));
        // 注册解锁通知回调：将 ClientState 的通知桥接到 Toast 弹窗
        ArchaeologyJournalClientState.registerTableUnlockNotifier(JournalUnlockToast::addTableUnlocks);
        ArchaeologyJournalClientState.registerItemUnlockNotifier((names, icons) -> {
            java.util.List<JournalUnlockToast.Entry> entries = new java.util.ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                entries.add(new JournalUnlockToast.Entry(names.get(i), icons.get(i)));
            }
            JournalUnlockToast.addItemUnlocks(entries);
        });
        // 注册标本箱界面（漏斗贴图）
        MenuScreens.register(SpecimenBoxMenu.TYPE, SpecimenBoxScreen::new);
        MenuScreens.register(PotteryWheelMenu.TYPE, PotteryWheelScreen::new);
        TooltipComponentCallback.EVENT.register(component ->
                component instanceof SpecimenBoxTooltip tooltip
                        ? new ClientSpecimenBoxTooltip(tooltip)
                        : null);

        // 注册 S2C 接收器：遍历 ModPayloads 客户端清单
        for (ModPayloads.Client.S2C<?> s2c : ModPayloads.Client.S2C_PAYLOADS) {
            registerS2C(s2c);
        }
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ArchaeologyJournalClientState.resetOnDisconnect();
            ClientLootTableLanguageStore.resetOnDisconnect();
            HandOfCatClientState.reset();
            ReaderScanHighlightState.reset();
            com.meteorite.unsuspiciousblock.client.enchantment.EnchantmentRevealClientState.reset();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ArchaeologyJournalKeyHandler.tick();
            ArchaeologyJournalClientState.tick();
            SuspiciousReaderClientState.tick();
            CatHandClientState.tick();
            ReaderScanHighlightState.tick();
        });

        // 半透明方块渲染之后绘制范围扫描高亮与猫之恩惠保护罩，实现透视效果
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            SuspiciousReaderRangeHighlight.render(context.matrixStack(), context.camera());
            CatFavorShieldRenderer.render(context.matrixStack(), context.camera());
        });

        // 注册猫之恩惠快捷栏 HUD
        HudRenderCallback.EVENT.register((guiGraphics, tickCounter) -> CatFavorHud.render(guiGraphics));

        // 铁砧结果槽 tooltip 成本分解：持有猫之瞳时追加分解行
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            AnvilBreakdownTooltipAppender.appendIfApplicable(stack, lines);
            GrindstoneBreakdownTooltipAppender.appendIfApplicable(stack, lines);
        });
    }

    // 注册 S2C 客户端接收器
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerS2C(ModPayloads.Client.S2C<?> s2cRaw) {
        ModPayloads.Client.S2C<T> s2c = (ModPayloads.Client.S2C<T>) s2cRaw;
        ClientPlayNetworking.registerGlobalReceiver(s2c.type(),
                (payload, context) -> s2c.handler().accept(payload));
    }
}
