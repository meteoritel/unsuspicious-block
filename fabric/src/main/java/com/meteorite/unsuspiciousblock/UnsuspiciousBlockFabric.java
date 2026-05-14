package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.enchantment.fossil.FossilHunterService;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.menu.ModMenus;
import com.meteorite.unsuspiciousblock.menu.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.network.payload.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalLogPayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalLogSnapshotPayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncJournalStatePayload;
import com.meteorite.unsuspiciousblock.network.payload.SyncSpecimenBoxViewPayload;
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

        ModMenus.SPECIMEN_BOX = Registry.register(
                BuiltInRegistries.MENU,
                ModMenus.SPECIMEN_BOX_ID,
                new MenuType<>(SpecimenBoxMenu::new, FeatureFlags.DEFAULT_FLAGS)
        );

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

        PayloadTypeRegistry.playS2C().register(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalLogPayload.TYPE, SyncJournalLogPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalLogSnapshotPayload.TYPE, SyncJournalLogSnapshotPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncSpecimenBoxViewPayload.TYPE, SyncSpecimenBoxViewPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(UploadJournalLogSnapshotPayload.TYPE, UploadJournalLogSnapshotPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(UploadJournalLogSnapshotPayload.TYPE,
                (payload, context) -> context.server().execute(
                        () -> ArchaeologyJournalNetwork.handleUploadedLogSnapshot(context.player(), payload)));

        ServerLifecycleEvents.SERVER_STARTED.register(ArchaeologyJournalServerCatalog::ensureLoaded);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ArchaeologyJournalServerCatalog.invalidate();
            FossilHunterService.clearPendingPlayerBreaks();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ArchaeologyJournalNetwork.syncOnJoin(handler.player));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                UsbCommand.register(dispatcher));

        Constants.LOG.info("UnsuspiciousBlock Fabric initialized.");
    }
}
