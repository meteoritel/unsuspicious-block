package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.network.payload.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.network.payload.UploadJournalLogSnapshotPayload;
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
import net.minecraft.world.item.ItemStack;

public class UnsuspiciousBlockFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        UnsuspiciousBlockCommon.init();

        ModItems.SUSPICIOUS_READER = Registry.register(
                BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "suspicious_reader"),
                ModItems.createSuspiciousReader()
        );
        ModItems.LUOYANG_SPADE = Registry.register(
                BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "luoyang_spade"),
                ModItems.createLuoyangSpade()
        );
        ModItems.ARCHAEOLOGY_JOURNAL = Registry.register(
                BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "archaeology_journal"),
                ModItems.createArchaeologyJournal()
        );
        ModItems.ANCIENT_COIN = Registry.register(
                BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "ancient_coin"),
                ModItems.createAncientCoin()
        );
        ModItems.LOST_PAGE = Registry.register(
                BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "lost_page"),
                ModItems.createLostPage()
        );
        ModItems.PAGE_BASE = Registry.register(
                BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "page_base"),
                ModItems.createPageBase()
        );

        Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "main"),
                FabricItemGroup.builder()
                        .title(Component.translatable("itemGroup.unsuspiciousblock.main"))
                        .icon(() -> new ItemStack(ModItems.ARCHAEOLOGY_JOURNAL))
                        .displayItems((context, entries) -> {
                            entries.accept(ModItems.SUSPICIOUS_READER);
                            entries.accept(ModItems.ARCHAEOLOGY_JOURNAL);
                            entries.accept(ModItems.ANCIENT_COIN);
                            entries.accept(ModItems.LOST_PAGE);
                            entries.accept(ModItems.PAGE_BASE);
                        })
                        .build()
        );

        PayloadTypeRegistry.playS2C().register(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalLogPayload.TYPE, SyncJournalLogPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalLogSnapshotPayload.TYPE, SyncJournalLogSnapshotPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(UploadJournalLogSnapshotPayload.TYPE, UploadJournalLogSnapshotPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(UploadJournalLogSnapshotPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> ArchaeologyJournalNetwork.handleUploadedLogSnapshot(context.player(), payload)));

        ServerLifecycleEvents.SERVER_STARTED.register(ArchaeologyJournalServerCatalog::ensureLoaded);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ArchaeologyJournalServerCatalog.invalidate());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ArchaeologyJournalNetwork.syncOnJoin(handler.player));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                UsbCommand.register(dispatcher));

        Constants.LOG.info("UnsuspiciousBlock Fabric initialized.");
    }
}
