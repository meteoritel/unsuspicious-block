package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.entity.EntityRegistrar;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.meteorite.unsuspiciousblock.world.PlacedBoneBlockTracker;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.network.ModPayloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class UnsuspiciousBlockFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        UnsuspiciousBlockCommon.init();

        // 遍历物品注册清单，统一注册并回写静态字段
        ModItems.forEach((name, factory, setter) -> {
            Item registered = Registry.register(
                    BuiltInRegistries.ITEM,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                    factory.get()
            );
            setter.accept(registered);
        });

        // 遍历实体注册清单，统一注册类型、回写静态字段并注册默认属性
        ModEntities.forEach(new EntityRegistrar() {
            @Override
            public <T extends LivingEntity> void register(String name, Supplier<EntityType<T>> factory,
                    Consumer<EntityType<T>> setter, Supplier<AttributeSupplier.Builder> attributes) {
                EntityType<T> type = Registry.register(
                        BuiltInRegistries.ENTITY_TYPE,
                        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                        factory.get()
                );
                setter.accept(type);
                AttributeSupplier supplier = attributes.get().build();
                FabricDefaultAttributeRegistry.register(type, supplier);
            }
        });

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

        // 注册 payload：遍历 ModPayloads 统一清单，避免手写重复
        for (ModPayloads.C2S<?> c2s : ModPayloads.C2S_PAYLOADS) {
            registerC2S(c2s);
        }
        for (ModPayloads.S2CSpec<?> spec : ModPayloads.S2C_SPECS) {
            registerS2CSpec(spec);
        }

        ServerLifecycleEvents.SERVER_STARTED.register(ArchaeologyJournalServerCatalog::ensureLoaded);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ArchaeologyJournalServerCatalog.invalidate();
            PlacedBoneBlockTracker.clearPendingPlayerBreaks();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ArchaeologyJournalNetwork.syncOnJoin(handler.player));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                UsbCommand.register(dispatcher));

        // 流浪商人交易：1 古代金币 → 5 绿宝石，最多交易 3 次
        TradeOfferHelper.registerWanderingTraderOffers(1, factories ->
                factories.add((entity, random) -> new MerchantOffer(
                        new ItemCost(ModItems.ANCIENT_COIN, 1),
                        new ItemStack(Items.EMERALD, 5),
                        3, 0, 0.05f
                ))
        );

        Constants.LOG.info("UnsuspiciousBlock Fabric initialized.");
    }

    // 注册 C2S payload 类型与服务端接收器，调度到主线程执行 handler
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerC2S(ModPayloads.C2S<?> c2sRaw) {
        ModPayloads.C2S<T> c2s = (ModPayloads.C2S<T>) c2sRaw;
        PayloadTypeRegistry.playC2S().register(c2s.type(), c2s.streamCodec());
        ServerPlayNetworking.registerGlobalReceiver(c2s.type(),
                (payload, context) -> context.server().execute(
                        () -> c2s.handler().accept(context.player(), payload)));
    }

    // 注册 S2C payload 类型编解码器（服务端发送需要）
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerS2CSpec(ModPayloads.S2CSpec<?> specRaw) {
        ModPayloads.S2CSpec<T> spec = (ModPayloads.S2CSpec<T>) specRaw;
        PayloadTypeRegistry.playS2C().register(spec.type(), spec.streamCodec());
    }
}
