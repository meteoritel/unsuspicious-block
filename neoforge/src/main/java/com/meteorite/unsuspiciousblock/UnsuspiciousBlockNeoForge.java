package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.client.ui.support.ArchaeologyJournalClientState;
import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.item.ArchaeologyJournalItem;
import com.meteorite.unsuspiciousblock.item.LuoyangSpadeItem;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.item.SuspiciousReaderItem;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.network.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.SyncJournalStatePayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** NeoForge 平台入口 */
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

    public UnsuspiciousBlockNeoForge(IEventBus modEventBus) {
        UnsuspiciousBlockCommon.init();

        ITEMS.register(modEventBus);

        modEventBus.addListener(this::syncCommonItemRefs);
        modEventBus.addListener(this::addCreativeTabEntries);
        modEventBus.addListener(this::registerPayloads);

        // 注册玩家登录事件用于初始同步
        NeoForge.EVENT_BUS.register(this);

        Constants.LOG.info("UnsuspiciousBlock NeoForge initialized.");
    }

    private void syncCommonItemRefs(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ModItems.SUSPICIOUS_READER = SUSPICIOUS_READER.get();
            ModItems.LUOYANG_SPADE = LUOYANG_SPADE.get();
            ModItems.ARCHAEOLOGY_JOURNAL = ARCHAEOLOGY_JOURNAL.get();
        });
    }

    private void addCreativeTabEntries(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SUSPICIOUS_READER);
            event.accept(LUOYANG_SPADE);
            event.accept(ARCHAEOLOGY_JOURNAL);
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Constants.MOD_ID).versioned("1.0");
        registrar.playToClient(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC,
                (payload, context) -> ArchaeologyJournalClientState.receiveCatalog(payload));
        registrar.playToClient(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC,
                (payload, context) -> ArchaeologyJournalClientState.receiveState(payload));
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
