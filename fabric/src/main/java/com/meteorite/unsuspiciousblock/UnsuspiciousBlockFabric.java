package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.cat.merchant.CatMerchantSpawner;
import com.meteorite.unsuspiciousblock.entity.EntityRegistrar;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.meteorite.unsuspiciousblock.effect.ModEffects;
import com.meteorite.unsuspiciousblock.sound.ModSounds;
import com.meteorite.unsuspiciousblock.world.NaturalBoneBlockTracker;
import com.meteorite.unsuspiciousblock.inventory.FabricInventoryPresenceAdapter;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.loottable.injection.ArchaeologyLootInjectors;
import com.meteorite.unsuspiciousblock.loot.BuriedTreasureLootInjection;
import com.meteorite.unsuspiciousblock.loot.FabricArchaeologyLootInjector;
import com.meteorite.unsuspiciousblock.loot.FishingLootInjection;
import com.meteorite.unsuspiciousblock.loot.VillageWeaponsmithLootInjection;
import com.meteorite.unsuspiciousblock.loottable.condition.ModLootConditions;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.network.ModPayloads;
import com.meteorite.unsuspiciousblock.platform.OptionalModIntegration;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
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
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class UnsuspiciousBlockFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // 注册自定义战利品条件类型（Fabric 端直接 Registry.register，在 common init 前完成）
        LootItemConditionType mudDredgingType = Registry.register(
                BuiltInRegistries.LOOT_CONDITION_TYPE,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "mud_dredging"),
                new LootItemConditionType(ModLootConditions.MUD_DREDGING_CODEC));
        ModLootConditions.setMudDredgingType(() -> mudDredgingType);

        UnsuspiciousBlockCommon.init();

        // 注册 Fabric 端考古战利品注入器（mixin 与概率模拟器共用，NeoForge 端通过 GLM 实现等价语义）
        ArchaeologyLootInjectors.register(FabricArchaeologyLootInjector.INSTANCE);

        // 注册埋藏宝藏战利品注入（临时方案，未来会更改到自定义结构中）
        BuriedTreasureLootInjection.register();

        // 注册泥地打捞钓鱼战利品注入——向原版钓鱼表追加 mud_dredging 池
        FishingLootInjection.register();

        // 注册村庄铁匠铺标本箱战利品注入：30% 概率生成 1 个
        VillageWeaponsmithLootInjection.register();

        // 注册 Fabric 端背包存在触发适配器（tick 驱动 diff，下线清理状态）
        FabricInventoryPresenceAdapter.register();

        // 遍历物品注册清单，统一注册并回写静态字段
        ModItems.forEach((name, factory, setter) -> {
            Item registered = Registry.register(
                    BuiltInRegistries.ITEM,
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                    factory.get()
            );
            setter.accept(registered);
        });

        // 通过反射跨越可选依赖边界，避免主入口在 Trinkets 缺失时解析其 API。
        if (Services.PLATFORM.isModLoaded("trinkets")) {
            OptionalModIntegration.instantiate(
                    "com.meteorite.unsuspiciousblock.plugin.trinket.FabricTrinketsIntegration",
                    Runnable.class).run();
        }

        // 遍历实体注册清单，统一注册类型、回写 Supplier 并注册默认属性
        ModEntities.forEach(new EntityRegistrar() {
            @Override
            public <T extends LivingEntity> void register(String name, Supplier<EntityType<T>> factory,
                    Consumer<Supplier<EntityType<T>>> setter, Supplier<AttributeSupplier.Builder> attributes) {
                EntityType<T> type = Registry.register(
                        BuiltInRegistries.ENTITY_TYPE,
                        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                        factory.get()
                );
                // 包装为不可变 Supplier，与 NeoForge 的 DeferredHolder 行为对齐
                setter.accept(() -> type);
                AttributeSupplier supplier = attributes.get().build();
                FabricDefaultAttributeRegistry.register(type, supplier);
            }
        });

        // 遍历效果注册清单，统一注册并回写 Holder 供 common 代码引用
        ModEffects.forEach((name, factory, setter) -> {
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name);
            Registry.register(BuiltInRegistries.MOB_EFFECT, rl, factory.get());
            Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.getHolder(
                    ResourceKey.create(Registries.MOB_EFFECT, rl)).orElseThrow();
            setter.accept(holder);
        });

        // 遍历声音注册清单，统一注册并回写 Holder 供 common 代码引用
        ModSounds.forEach((name, setter) -> {
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name);
            Registry.register(BuiltInRegistries.SOUND_EVENT, rl, SoundEvent.createVariableRangeEvent(rl));
            Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.getHolder(
                    ResourceKey.create(Registries.SOUND_EVENT, rl)).orElseThrow();
            setter.accept(holder);
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

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LootProbabilitySimulationWorker.start();
            ArchaeologyJournalServerCatalog.ensureLoaded(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LootProbabilitySimulationWorker.stop();
            ArchaeologyJournalServerCatalog.invalidate();
            NaturalBoneBlockTracker.clearPendingPlayerBreaks();
        });

        // 服务端每 tick 末尾：驱动概率模拟主线程分片消费
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LootProbabilitySimulationWorker.tickIfPresent(server);
            CatMerchantSpawner.tick(server);
        });

        // 直接扫描事件提供的 chunk，避免其进入 chunk map 前重新触发生成
        ServerChunkEvents.CHUNK_GENERATE.register((world, chunk) ->
                NaturalBoneBlockTracker.scanChunk(chunk));

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
