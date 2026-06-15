package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.world.PlacedBoneBlockTracker;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.network.journal.ReaderScanLevelHandler;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStateIncrementalPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncSpecimenBoxViewPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateReaderScanLevelPayload;
import com.meteorite.unsuspiciousblock.network.payload.c2s.UploadJournalLogSnapshotPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

public class UnsuspiciousBlockFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        UnsuspiciousBlockCommon.init();

        // 遍历物品注册清单，统一注册并回写静态字段
        for (ModItems.ItemEntry entry : ModItems.REGISTRY_MANIFEST) {
            Item registered = Registry.register(
                    BuiltInRegistries.ITEM,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, entry.name()),
                    entry.factory().get()
            );
            entry.setter().accept(registered);
        }

        // 注册 menu
        SpecimenBoxMenu.TYPE = Registry.register(
                BuiltInRegistries.MENU,
                SpecimenBoxMenu.ID,
                new MenuType<>(SpecimenBoxMenu::new, FeatureFlags.DEFAULT_FLAGS)
        );

        // 注册创造模式物品栏
        Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "main"),
                FabricItemGroup.builder()
                        .title(Component.translatable("itemGroup.unsuspiciousblock.main"))
                        .icon(ModItems.CREATIVE_TAB_ICON)
                        .displayItems((context, entries) -> {
                            for (Supplier<Item> sup : ModItems.CREATIVE_TAB_ITEMS) {
                                entries.accept(sup.get());
                            }
                        })
                        .build()
        );

        // 注册 payload
        PayloadTypeRegistry.playS2C().register(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncCatalogHashPayload.TYPE, SyncCatalogHashPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalStateIncrementalPayload.TYPE, SyncJournalStateIncrementalPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalLogPayload.TYPE, SyncJournalLogPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalLogSnapshotPayload.TYPE, SyncJournalLogSnapshotPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncSpecimenBoxViewPayload.TYPE, SyncSpecimenBoxViewPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(UploadJournalLogSnapshotPayload.TYPE, UploadJournalLogSnapshotPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(UpdateReaderScanLevelPayload.TYPE, UpdateReaderScanLevelPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(RequestCatalogPayload.TYPE, RequestCatalogPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(UploadJournalLogSnapshotPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> JournalLogHandler.handleUploadedLogSnapshot(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(UpdateReaderScanLevelPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> ReaderScanLevelHandler.handleUpdateReaderScanLevel(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(RequestCatalogPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> JournalCatalogHandler.handleRequestCatalog(context.player(), payload)));

        ServerLifecycleEvents.SERVER_STARTED.register(ArchaeologyJournalServerCatalog::ensureLoaded);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ArchaeologyJournalServerCatalog.invalidate();
            PlacedBoneBlockTracker.clearPendingPlayerBreaks();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ArchaeologyJournalNetwork.syncOnJoin(handler.player));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                UsbCommand.register(dispatcher));

        Constants.LOG.info("UnsuspiciousBlock Fabric initialized.");
    }
}
