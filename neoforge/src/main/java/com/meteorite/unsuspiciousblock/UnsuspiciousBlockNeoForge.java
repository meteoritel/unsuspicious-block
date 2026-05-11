package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.item.ArchaeologyJournalItem;
import com.meteorite.unsuspiciousblock.item.LuoyangSpadeItem;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.item.SuspiciousReaderItem;
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
import net.neoforged.fml.common.Mod;
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

@Mod(Constants.MOD_ID)
public class UnsuspiciousBlockNeoForge {

    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Constants.MOD_ID);

    private static final DeferredItem<SuspiciousReaderItem> SUSPICIOUS_READER =
            ITEMS.register("suspicious_reader", ModItems::createSuspiciousReader);

    private static final DeferredItem<LuoyangSpadeItem> LUOYANG_SPADE =
            ITEMS.register("luoyang_spade", ModItems::createLuoyangSpade);
    private static final DeferredItem<ArchaeologyJournalItem> ARCHAEOLOGY_JOURNAL =
            ITEMS.register("archaeology_journal", ModItems::createArchaeologyJournal);
    private static final DeferredItem<Item> ANCIENT_COIN =
            ITEMS.register("ancient_coin", ModItems::createAncientCoin);
    private static final DeferredItem<Item> LOST_PAGE =
            ITEMS.register("lost_page", ModItems::createLostPage);
    private static final DeferredItem<Item> PAGE_BASE =
            ITEMS.register("page_base", ModItems::createPageBase);

    private static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Constants.MOD_ID);

    private static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.unsuspiciousblock.main"))
                    .icon(() -> new ItemStack(ARCHAEOLOGY_JOURNAL.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(SUSPICIOUS_READER.get());
                        output.accept(ARCHAEOLOGY_JOURNAL.get());
                        output.accept(ANCIENT_COIN.get());
                        output.accept(LOST_PAGE.get());
                        output.accept(PAGE_BASE.get());
                    })
                    .build());

    public UnsuspiciousBlockNeoForge(IEventBus modEventBus) {
        UnsuspiciousBlockCommon.init();

        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::syncCommonItemRefs);
        modEventBus.addListener(this::registerPayloads);

        NeoForge.EVENT_BUS.register(this);

        Constants.LOG.info("UnsuspiciousBlock NeoForge initialized.");
    }

    private void syncCommonItemRefs(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ModItems.SUSPICIOUS_READER = SUSPICIOUS_READER.get();
            ModItems.LUOYANG_SPADE = LUOYANG_SPADE.get();
            ModItems.ARCHAEOLOGY_JOURNAL = ARCHAEOLOGY_JOURNAL.get();
            ModItems.ANCIENT_COIN = ANCIENT_COIN.get();
            ModItems.LOST_PAGE = LOST_PAGE.get();
            ModItems.PAGE_BASE = PAGE_BASE.get();
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
