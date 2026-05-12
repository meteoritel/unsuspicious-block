package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.NeoForgeLootTableConfig;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.network.payload.UploadJournalLogSnapshotPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Mod(Constants.MOD_ID)
public class UnsuspiciousBlockNeoForge {

    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Constants.MOD_ID);

    /** 存储 (DeferredItem, Consumer<Item>) 对，供 FMLCommonSetupEvent 中回写 */
    private record ItemSyncEntry(DeferredItem<Item> deferred, Consumer<Item> setter) {}

    private static final List<ItemSyncEntry> ITEM_SYNC_LIST = new ArrayList<>();

    static {
        for (ModItems.ItemEntry entry : ModItems.REGISTRY_MANIFEST) {
            DeferredItem<Item> deferred = ITEMS.register(entry.name(), entry.factory());
            ITEM_SYNC_LIST.add(new ItemSyncEntry(deferred, entry.setter()));
        }
    }

    private static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Constants.MOD_ID);

    private static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.unsuspiciousblock.main"))
                    .icon(ModItems.CREATIVE_TAB_ICON)
                    .displayItems((parameters, output) -> {
                        for (Supplier<Item> sup : ModItems.CREATIVE_TAB_ITEMS) {
                            output.accept(sup.get());
                        }
                    })
                    .build());

    public UnsuspiciousBlockNeoForge(IEventBus modEventBus, ModContainer container) {
        UnsuspiciousBlockCommon.init();

        container.registerConfig(ModConfig.Type.COMMON, NeoForgeLootTableConfig.CONFIG_SPEC);

        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::syncCommonItemRefs);
        modEventBus.addListener(this::registerPayloads);

        NeoForge.EVENT_BUS.register(this);

        Constants.LOG.info("UnsuspiciousBlock NeoForge initialized.");
    }

    private void syncCommonItemRefs(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            for (ItemSyncEntry entry : ITEM_SYNC_LIST) {
                entry.setter().accept(entry.deferred().get());
            }
        });
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Constants.MOD_ID).versioned("1.0");
        registrar.playToServer(UploadJournalLogSnapshotPayload.TYPE, UploadJournalLogSnapshotPayload.STREAM_CODEC,
                (payload, context) -> ArchaeologyJournalNetwork.handleUploadedLogSnapshot((ServerPlayer) context.player(), payload));
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ArchaeologyJournalNetwork.syncOnJoin(sp);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        UsbCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ArchaeologyJournalServerCatalog.ensureLoaded(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ArchaeologyJournalServerCatalog.invalidate();
    }
}
