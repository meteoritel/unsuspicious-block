package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.network.SyncArchaeologyCatalogPayload;
import com.meteorite.unsuspiciousblock.network.SyncJournalStatePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;

/** Fabric 平台入口（服务端） */
public class UnsuspiciousBlockFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        UnsuspiciousBlockCommon.init();

        // 物品注册（Fabric 端在 onInitialize 时注册表尚未冻结，可直接使用 Registry.register）
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

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(ModItems.SUSPICIOUS_READER);
            entries.accept(ModItems.LUOYANG_SPADE);
            entries.accept(ModItems.ARCHAEOLOGY_JOURNAL);
        });

        // 注册考古笔记网络包类型
        PayloadTypeRegistry.playS2C().register(SyncArchaeologyCatalogPayload.TYPE, SyncArchaeologyCatalogPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJournalStatePayload.TYPE, SyncJournalStatePayload.STREAM_CODEC);

        // 玩家加入时同步目录和状态
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ArchaeologyJournalNetwork.syncOnJoin(handler.player));

        // 注册 USB 调试指令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                UsbCommand.register(dispatcher));

        Constants.LOG.info("UnsuspiciousBlock Fabric initialized.");
    }
}
