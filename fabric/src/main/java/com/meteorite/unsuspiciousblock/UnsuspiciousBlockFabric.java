package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.core.bootstrap.UnsuspiciousBlockCommon;

import com.meteorite.unsuspiciousblock.core.bootstrap.ModServerLifecycle;
import com.meteorite.unsuspiciousblock.core.bootstrap.ModContent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlags;
import java.util.function.BiFunction;
import com.meteorite.unsuspiciousblock.core.block.UnsuspiciousBlockInteractions;
import com.meteorite.unsuspiciousblock.core.blockentity.BlockEntityRegistrar;
import com.meteorite.unsuspiciousblock.core.command.UsbCommand;
import com.meteorite.unsuspiciousblock.core.entity.EntityRegistrar;
import com.meteorite.unsuspiciousblock.core.world.NaturalBoneBlockTracker;
import com.meteorite.unsuspiciousblock.core.world.ModFeatures;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.levelgen.GenerationStep;
import com.meteorite.unsuspiciousblock.core.journal.JournalPlayerDataService;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import com.meteorite.unsuspiciousblock.core.loottable.injection.ArchaeologyLootInjectors;
import com.meteorite.unsuspiciousblock.platform.loot.BuriedTreasureLootInjection;
import com.meteorite.unsuspiciousblock.platform.loot.FabricArchaeologyLootInjector;
import com.meteorite.unsuspiciousblock.platform.loot.FishingLootInjection;
import com.meteorite.unsuspiciousblock.platform.loot.VillageWeaponsmithLootInjection;
import com.meteorite.unsuspiciousblock.core.loottable.condition.ModLootConditions;
import com.meteorite.unsuspiciousblock.core.specimen.SpecimenBoxTotemProxy;
import com.meteorite.unsuspiciousblock.core.network.ModPayloads;
import com.meteorite.unsuspiciousblock.platform.OptionalModIntegration;
import com.meteorite.unsuspiciousblock.core.bootstrap.DataPackReloadListener;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.crafting.RecipeSerializer;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class UnsuspiciousBlockFabric implements ModInitializer {

    @Override
    public void onInitialize() {

        BiomeModifications.addFeature(BiomeSelectors.tag(BiomeTags.IS_RIVER),
                GenerationStep.Decoration.VEGETAL_DECORATION, ModFeatures.RIVER_SHIMMER);
        BiomeModifications.addFeature(BiomeSelectors.tag(BiomeTags.IS_NETHER),
                GenerationStep.Decoration.VEGETAL_DECORATION, ModFeatures.NETHER_SHIMMER);

        // 注册自定义战利品条件类型（Fabric 端直接 Registry.register，在 common init 前完成）
        LootItemConditionType toolEnchantmentType = Registry.register(
                BuiltInRegistries.LOOT_CONDITION_TYPE,
                ModLootConditions.TOOL_ENCHANTMENT,
                new LootItemConditionType(ModLootConditions.TOOL_ENCHANTMENT_CODEC));
        UnsuspiciousBlockCommon.init(() -> toolEnchantmentType);

        // 注册 Fabric 端考古战利品注入器（mixin 与概率模拟器共用，NeoForge 端通过 GLM 实现等价语义）
        ArchaeologyLootInjectors.register(FabricArchaeologyLootInjector.INSTANCE);

        // 注册埋藏宝藏战利品注入（临时方案，未来会更改到自定义结构中）
        BuriedTreasureLootInjection.register();

        // 注册泥地打捞钓鱼战利品注入——向原版钓鱼表追加 mud_dredging 池
        FishingLootInjection.register();

        // 注册村庄铁匠铺标本箱战利品注入：30% 概率生成 1 个
        VillageWeaponsmithLootInjection.register();

        // 注册 Fabric 端背包存在触发适配器（tick 驱动 diff，下线清理状态）

        // 原版手持图腾未能阻止死亡时，尝试使用随身标本箱内的图腾。
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, damageAmount) ->
                !SpecimenBoxTotemProxy.tryActivate(entity, source, LivingEntity::removeAllEffects));

        registerContent();

        // 花火粉可作为燃料——800t 可烧炼 4 个物品（煤炭 1600t 烧 8 个）
        FuelRegistry.INSTANCE.add(ModItems.SPARK_DUST, 800);

        // 刷子或考古铲右键不可疑方块时立即破坏，潜行交互同样生效
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
                UnsuspiciousBlockInteractions.tryBreak(
                        level, hitResult.getBlockPos(), player, player.getItemInHand(hand))
                        ? InteractionResult.sidedSuccess(level.isClientSide())
                        : InteractionResult.PASS);

        // 通过反射跨越可选依赖边界，避免主入口在 Trinkets 缺失时解析其 API。
        if (Services.PLATFORM.isModLoaded("trinkets")) {
            OptionalModIntegration.instantiate(
                    "com.meteorite.unsuspiciousblock.platform.plugin.trinket.FabricTrinketsIntegration",
                    Runnable.class).run();
        }

        // 注册 payload：遍历 ModPayloads 统一清单，避免手写重复
        for (ModPayloads.C2S<?> c2s : ModPayloads.C2S_PAYLOADS) {
            registerC2S(c2s);
        }
        for (ModPayloads.S2C<?> spec : ModPayloads.S2C_PAYLOADS) {
            registerS2C(spec);
        }

        // 数据包重载：只置脏标记，重建交给 tick 路径（D9）。Fabric 用服务器数据包重载监听器；
        // 启动时的首次资源加载同样会触发，但那时目录未加载，标记会被忽略。
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
                new IdentifiableResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "loottable_reload_watch");
                    }

                    @Override
                    public @NotNull CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
                                                                   ProfilerFiller preparationsProfiler,
                                                                   ProfilerFiller reloadProfiler,
                                                                   Executor backgroundExecutor, Executor gameExecutor) {
                        return DataPackReloadListener.INSTANCE.reload(barrier, manager, preparationsProfiler,
                                reloadProfiler, backgroundExecutor, gameExecutor);
                    }
                });

        ServerLifecycleEvents.SERVER_STARTED.register(ModServerLifecycle::started);
        ServerLifecycleEvents.SERVER_STOPPED.register(ModServerLifecycle::stopped);

        // 服务端每 tick 末尾：驱动概率模拟主线程分片消费
        ServerTickEvents.END_SERVER_TICK.register(ModServerLifecycle::tick);

        // 直接扫描事件提供的 chunk，避免其进入 chunk map 前重新触发生成
        ServerChunkEvents.CHUNK_GENERATE.register((world, chunk) ->
                NaturalBoneBlockTracker.scanChunk(chunk));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                JournalPlayerDataService.onPlayerJoined(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                JournalPlayerDataService.onPlayerLeft(handler.player));

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

        Constants.LOG.info(Constants.LOG_TAG + "Fabric entrypoint initialized");
    }

    private static void registerContent() {
        ModContent.register(new ModContent.Registrars(
                (name, factory, setter) -> {
                    Block registered = Registry.register(
                            BuiltInRegistries.BLOCK,
                            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                            factory.get()
                    );
                    setter.accept(() -> registered);
                },
                new BlockEntityRegistrar() {
                    @Override
                    public <T extends BlockEntity> void register(String name, Supplier<BlockEntityType<T>> factory,
                                                                 Consumer<Supplier<BlockEntityType<T>>> setter) {
                        BlockEntityType<T> registered = Registry.register(
                                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                                factory.get()
                        );
                        setter.accept(() -> registered);
                    }
                },
                (name, factory, setter) -> {
                    Item registered = Registry.register(
                            BuiltInRegistries.ITEM,
                            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                            factory.get()
                    );
                    setter.accept(registered);
                },
                (name, factory, setter) -> {
                    RecipeSerializer<?> registered = Registry.register(
                            BuiltInRegistries.RECIPE_SERIALIZER,
                            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                            factory.get()
                    );
                    setter.accept(() -> registered);
                },
                new EntityRegistrar() {
                    @Override
                    public <T extends Entity> void register(String name, Supplier<EntityType<T>> factory,
                                                            Consumer<Supplier<EntityType<T>>> setter, Supplier<AttributeSupplier.Builder> attributes) {
                        EntityType<T> type = Registry.register(
                                BuiltInRegistries.ENTITY_TYPE,
                                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                                factory.get()
                        );
                        // 包装为不可变 Supplier，与 NeoForge 的 DeferredHolder 行为对齐
                        setter.accept(() -> type);
                        // 非生物实体（如闪烁的光）没有默认属性，跳过属性注册
                        if (attributes != null) {
                            registerDefaultAttributes(type, attributes.get().build());
                        }
                    }
                },
                (name, factory, setter) -> {
                    ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name);
                    Registry.register(BuiltInRegistries.MOB_EFFECT, rl, factory.get());
                    Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.getHolder(
                            ResourceKey.create(Registries.MOB_EFFECT, rl)).orElseThrow();
                    setter.accept(holder);
                },
                (name, factory, setter) -> {
                    ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name);
                    Registry.register(BuiltInRegistries.SOUND_EVENT, rl, factory.get());
                    Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.getHolder(
                            ResourceKey.create(Registries.SOUND_EVENT, rl)).orElseThrow();
                    setter.accept(holder);
                },
                (name, factory) -> Registry.register(BuiltInRegistries.FEATURE,
                        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name), factory.get()),
                new ModContent.MenuRegistrar() {
                    @Override
                    public <T extends AbstractContainerMenu> void register(
                            String name, BiFunction<Integer, Inventory, T> factory,
                            Consumer<Supplier<MenuType<T>>> setter) {
                        MenuType<T> type = Registry.register(BuiltInRegistries.MENU,
                                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
                                new MenuType<>(factory::apply, FeatureFlags.DEFAULT_FLAGS));
                        setter.accept(() -> type);
                    }
                },
                (name, factory) -> Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name), factory.get()),
                FabricItemGroup::builder));
    }

    // 注册实体默认属性——非生物实体没有属性，因此仅在 attributes 非空时调用；
    // 此处的向下转型是安全的：调用方只在条目提供了属性工厂（即生物实体）时才进入该分支
    @SuppressWarnings("unchecked")
    private static <T extends Entity> void registerDefaultAttributes(EntityType<T> type,
                                                                     AttributeSupplier supplier) {
        FabricDefaultAttributeRegistry.register((EntityType<? extends LivingEntity>) type, supplier);
    }

    // 注册 C2S payload 类型与服务端接收器，调度到主线程执行 handler
    private static <T extends CustomPacketPayload> void registerC2S(ModPayloads.C2S<T> c2s) {
        PayloadTypeRegistry.playC2S().register(c2s.type(), c2s.streamCodec());
        ServerPlayNetworking.registerGlobalReceiver(c2s.type(),
                (payload, context) -> context.server().execute(
                        () -> c2s.handler().accept(context.player(), payload)));
    }

    // 注册 S2C payload 类型编解码器（服务端发送需要）
    private static <T extends CustomPacketPayload> void registerS2C(ModPayloads.S2C<T> spec) {
        PayloadTypeRegistry.playS2C().register(spec.type(), spec.streamCodec());
    }
}
