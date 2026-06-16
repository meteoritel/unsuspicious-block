package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.entity.EntityRegistrar;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.meteorite.unsuspiciousblock.world.PlacedBoneBlockTracker;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.NeoForgeLootTableConfig;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.network.journal.ReaderScanLevelHandler;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateReaderScanLevelPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UploadJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.CatNightVisionPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.CatDeterrenceTogglePayload;
import com.meteorite.unsuspiciousblock.cat.CatNetworkHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
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
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Constants.MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, Constants.MOD_ID);
    private static final DeferredHolder<MenuType<?>, MenuType<SpecimenBoxMenu>> SPECIMEN_BOX_MENU =
            MENUS.register("specimen_box", () -> IMenuTypeExtension.create((containerId, inventory, extraData) ->
                    new SpecimenBoxMenu(containerId, inventory)));

    /** 存储 (DeferredItem, Consumer<Item>) 对，供 FMLCommonSetupEvent 中回写 */
    private record ItemSyncEntry(DeferredItem<Item> deferred, Consumer<Item> setter) {}

    private static final List<ItemSyncEntry> ITEM_SYNC_LIST = new ArrayList<>();

    static {
        ModItems.forEach((name, factory, setter) -> {
            DeferredItem<Item> deferred = ITEMS.register(name, factory);
            ITEM_SYNC_LIST.add(new ItemSyncEntry(deferred, setter));
        });
    }

    /** 存储 (DeferredHolder, Consumer, Supplier<AttributeSupplier.Builder>) 对，泛型化以消除强制转换 */
    private record EntitySyncEntry<T extends LivingEntity>(
            DeferredHolder<EntityType<?>, EntityType<T>> deferred,
            Consumer<EntityType<T>> setter,
            Supplier<AttributeSupplier.Builder> attributes) {

        // 回写 common 静态字段
        void writeback() {
            setter.accept(deferred.get());
        }

        // 注册实体默认属性
        void putAttributes(EntityAttributeCreationEvent event) {
            event.put(deferred.get(), attributes.get().build());
        }
    }

    private static final List<EntitySyncEntry<?>> ENTITY_SYNC_LIST = new ArrayList<>();

    static {
        ModEntities.forEach(new EntityRegistrar() {
            @Override
            public <T extends LivingEntity> void register(String name, Supplier<EntityType<T>> factory,
                    Consumer<EntityType<T>> setter, Supplier<AttributeSupplier.Builder> attributes) {
                DeferredHolder<EntityType<?>, EntityType<T>> deferred = ENTITY_TYPES.register(name, factory);
                ENTITY_SYNC_LIST.add(new EntitySyncEntry<>(deferred, setter, attributes));
            }
        });
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
        MENUS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::syncCommonItemRefs);
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::registerEntityAttributes);

        NeoForge.EVENT_BUS.register(this);

        Constants.LOG.info("UnsuspiciousBlock NeoForge initialized.");
    }

    private void syncCommonItemRefs(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            for (ItemSyncEntry entry : ITEM_SYNC_LIST) {
                entry.setter().accept(entry.deferred().get());
            }
            syncEntityRefs();
            SpecimenBoxMenu.TYPE = SPECIMEN_BOX_MENU.get();
        });
    }

    /**
     * 回写实体类型静态字段。
     * <p>
     * 幂等操作：DeferredHolder 在 RegisterEvent 阶段即已绑定，因此本方法可在
     * 渲染器注册（EntityRenderersEvent.RegisterRenderers）前提前调用，避免渲染器
     * 注册早于 FMLCommonSetupEvent 回写而取到 null 实体类型。
     */
    public static void syncEntityRefs() {
        for (EntitySyncEntry<?> entry : ENTITY_SYNC_LIST) {
            entry.writeback();
        }
    }

    public static MenuType<SpecimenBoxMenu> specimenBoxMenu() {
        return SPECIMEN_BOX_MENU.get();
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Constants.MOD_ID).versioned("2.0");
        registrar.playToServer(UploadJournalLogSnapshotPayload.TYPE, UploadJournalLogSnapshotPayload.STREAM_CODEC,
                (payload, context) -> JournalLogHandler.handleUploadedLogSnapshot((ServerPlayer) context.player(), payload));
        registrar.playToServer(UpdateReaderScanLevelPayload.TYPE, UpdateReaderScanLevelPayload.STREAM_CODEC,
                (payload, context) -> ReaderScanLevelHandler.handleUpdateReaderScanLevel(payload, (ServerPlayer) context.player()));
        registrar.playToServer(RequestCatalogPayload.TYPE, RequestCatalogPayload.STREAM_CODEC,
                (payload, context) -> JournalCatalogHandler.handleRequestCatalog((ServerPlayer) context.player(), payload));
        registrar.playToServer(CatNightVisionPayload.TYPE, CatNightVisionPayload.STREAM_CODEC,
                (payload, context) -> CatNetworkHandler.handleNightVision((ServerPlayer) context.player(), payload));
        registrar.playToServer(CatDeterrenceTogglePayload.TYPE, CatDeterrenceTogglePayload.STREAM_CODEC,
                (payload, context) -> CatNetworkHandler.handleDeterrenceToggle((ServerPlayer) context.player()));
    }

    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        for (EntitySyncEntry<?> entry : ENTITY_SYNC_LIST) {
            entry.putAttributes(event);
        }
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
        PlacedBoneBlockTracker.clearPendingPlayerBreaks();
    }

}
