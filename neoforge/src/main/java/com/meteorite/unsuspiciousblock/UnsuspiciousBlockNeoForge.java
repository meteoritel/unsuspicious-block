package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.platform.loot.LootInjectionDescriptions;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
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
import net.minecraft.world.level.levelgen.feature.Feature;
import com.meteorite.unsuspiciousblock.core.world.NaturalBoneBlockTracker;
import com.meteorite.unsuspiciousblock.core.journal.JournalPlayerDataService;
import com.meteorite.unsuspiciousblock.platform.world.NeoForgeBoneBlockTracker;
import com.meteorite.unsuspiciousblock.platform.inventory.NeoForgeInventoryPresenceAdapter;
import com.meteorite.unsuspiciousblock.core.inventory.AncientCoinRepair;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import com.meteorite.unsuspiciousblock.platform.loot.AddItemLootModifier;
import com.meteorite.unsuspiciousblock.platform.loot.FishingLootModifier;
import com.meteorite.unsuspiciousblock.platform.loot.InjectItemLootModifier;
import com.meteorite.unsuspiciousblock.core.loottable.condition.ModLootConditions;
import com.meteorite.unsuspiciousblock.platform.NeoForgeLootTableConfig;
import com.meteorite.unsuspiciousblock.platform.NeoForgePanningConfig;
import com.meteorite.unsuspiciousblock.core.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.core.specimen.SpecimenBoxTotemProxy;
import com.meteorite.unsuspiciousblock.core.pottery.PotteryWheelMenu;
import com.meteorite.unsuspiciousblock.core.network.ModPayloads;
import com.meteorite.unsuspiciousblock.platform.OptionalModIntegration;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.core.bootstrap.DataPackReloadListener;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.BasicItemListing;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.EffectCures;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.village.WandererTradesEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Mod(Constants.MOD_ID)
public class UnsuspiciousBlockNeoForge {

    private static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Constants.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Constants.MOD_ID);
    private static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, Constants.MOD_ID);
    private static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, Constants.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Constants.MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, Constants.MOD_ID);
    private static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, Constants.MOD_ID);

    private static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, Constants.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Constants.MOD_ID);

    // 自定义战利品条件类型注册——NeoForge 使用 DeferredRegister，避免 Registry is already frozen
    private static final DeferredRegister<LootItemConditionType> LOOT_CONDITIONS =
            DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, Constants.MOD_ID);
    private static final DeferredHolder<LootItemConditionType, LootItemConditionType> TOOL_ENCHANTMENT_TYPE =
            LOOT_CONDITIONS.register(ModLootConditions.TOOL_ENCHANTMENT.getPath(),
                    () -> new LootItemConditionType(ModLootConditions.TOOL_ENCHANTMENT_CODEC));

    // 全局战利品修改器序列化器注册——add_item 类型供 JSON 文件引用
    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> LOOT_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Constants.MOD_ID);

    static {
        LOOT_MODIFIERS.register("add_item", () -> AddItemLootModifier.CODEC);
        LOOT_MODIFIERS.register("inject_item", () -> InjectItemLootModifier.CODEC);
        LOOT_MODIFIERS.register("fishing_loot", () -> FishingLootModifier.CODEC);
    }

    /**
     * 存储 (DeferredItem, Consumer<Item>) 对，供 FMLCommonSetupEvent 中回写
     */
    private record ItemSyncEntry(DeferredItem<Item> deferred, Consumer<Item> setter) {
    }

    private static final List<ItemSyncEntry> ITEM_SYNC_LIST = new ArrayList<>();

    /**
     * 存储 (DeferredHolder, 属性工厂) 对，泛型化以消除强制转换
     */
    private record EntitySyncEntry<T extends Entity>(
            DeferredHolder<EntityType<?>, EntityType<T>> deferred,
            @Nullable Supplier<AttributeSupplier.Builder> attributes) {

        // 注册实体默认属性；非生物实体没有默认属性，跳过。
        // 转型安全：只有条目提供了属性工厂（即生物实体）时才会进入该分支
        @SuppressWarnings("unchecked")
        void putAttributes(EntityAttributeCreationEvent event) {
            if (attributes == null) {
                return;
            }
            event.put((EntityType<? extends LivingEntity>) deferred.get(), attributes.get().build());
        }
    }

    private static final List<EntitySyncEntry<?>> ENTITY_SYNC_LIST = new ArrayList<>();

    private static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Constants.MOD_ID);

    static {
        registerContent();
    }

    private static void registerContent() {
        ModContent.register(new ModContent.Registrars(
                (name, factory, setter) ->
                        setter.accept(BLOCKS.register(name, factory)),
                new BlockEntityRegistrar() {
                    @Override
                    public <T extends BlockEntity> void register(String name, Supplier<BlockEntityType<T>> factory,
                                                                 Consumer<Supplier<BlockEntityType<T>>> setter) {
                        DeferredHolder<BlockEntityType<?>, BlockEntityType<T>> deferred =
                                BLOCK_ENTITY_TYPES.register(name, factory);
                        setter.accept(deferred);
                    }
                },
                (name, factory, setter) -> {
                    DeferredItem<Item> deferred = ITEMS.register(name, factory);
                    ITEM_SYNC_LIST.add(new ItemSyncEntry(deferred, setter));
                },
                (name, factory, setter) ->
                        setter.accept(RECIPE_SERIALIZERS.register(name, factory)),
                new EntityRegistrar() {
                    @Override
                    public <T extends Entity> void register(String name, Supplier<EntityType<T>> factory,
                                                            Consumer<Supplier<EntityType<T>>> setter, Supplier<AttributeSupplier.Builder> attributes) {
                        DeferredHolder<EntityType<?>, EntityType<T>> deferred = ENTITY_TYPES.register(name, factory);
                        // DeferredHolder 本身即 Supplier，直接回写，无需等待 FMLCommonSetupEvent
                        setter.accept(deferred);
                        ENTITY_SYNC_LIST.add(new EntitySyncEntry<>(deferred, attributes));
                    }
                },
                (name, factory, setter) -> {
                    Holder<MobEffect> holder = EFFECTS.register(name, factory);
                    setter.accept(holder);
                },
                (name, setter) -> {
                    Holder<SoundEvent> holder = SOUND_EVENTS.register(name,
                            () -> SoundEvent.createVariableRangeEvent(
                                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name)));
                    setter.accept(holder);
                },
                FEATURES::register,
                new ModContent.MenuRegistrar() {
                    @Override
                    public <T extends AbstractContainerMenu> Supplier<MenuType<T>> register(
                            String name, BiFunction<Integer, Inventory, T> factory) {
                        return MENUS.register(name, () -> new MenuType<>(factory::apply, FeatureFlags.DEFAULT_FLAGS));
                    }
                },
                CREATIVE_MODE_TABS::register, CreativeModeTab::builder));
    }

    public UnsuspiciousBlockNeoForge(IEventBus modEventBus, ModContainer container) {
        // 在 common init 前设置平台注册的 LootItemConditionType，供 ToolEnchantmentCondition 运行时使用
        ModLootConditions.setToolEnchantmentType(TOOL_ENCHANTMENT_TYPE);

        UnsuspiciousBlockCommon.init();

        container.registerConfig(ModConfig.Type.SERVER, NeoForgeLootTableConfig.SERVER_CONFIG_SPEC);
        container.registerConfig(ModConfig.Type.COMMON, NeoForgeLootTableConfig.COMMON_CONFIG_SPEC);
        container.registerConfig(ModConfig.Type.SERVER, NeoForgePanningConfig.SERVER_CONFIG_SPEC,
                NeoForgePanningConfig.SERVER_CONFIG_FILE_NAME);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        EFFECTS.register(modEventBus);
        SOUND_EVENTS.register(modEventBus);
        MENUS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        FEATURES.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        NeoForgeBoneBlockTracker.ATTACHMENT_TYPES.register(modEventBus);
        LOOT_MODIFIERS.register(modEventBus);
        LOOT_CONDITIONS.register(modEventBus);

        modEventBus.addListener(this::syncCommonItemRefs);
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::registerEntityAttributes);

        NeoForge.EVENT_BUS.register(this);

        // 注册 NeoForge 端背包存在触发适配器（tick 驱动 diff，下线清理状态）
        NeoForgeInventoryPresenceAdapter.register();

        Constants.LOG.info(Constants.LOG_TAG + "NeoForge entrypoint initialized");
    }

    private void syncCommonItemRefs(FMLCommonSetupEvent event) {
        // MenuType 同步回写：RegisterMenuScreensEvent 可能在 enqueueWork 任务执行前触发，
        // 同步执行确保运行时 TYPE 就绪；客户端注册 Screen 时另有 getter 兜底
        ModContent.syncMenuTypes();
        event.enqueueWork(() -> {
            for (ItemSyncEntry entry : ITEM_SYNC_LIST) {
                entry.setter().accept(entry.deferred().get());
            }
            // 通过反射跨越可选依赖边界，避免主入口在 Curios 缺失时解析其 API。
            if (Services.PLATFORM.isModLoaded("curios")) {
                OptionalModIntegration.instantiate(
                        "com.meteorite.unsuspiciousblock.platform.plugin.curio.NeoForgeCuriosIntegration",
                        Runnable.class).run();
            }
        });
    }

    // 供客户端在 RegisterMenuScreensEvent 时获取 MenuType，避免 enqueueWork 时序问题
    public static MenuType<SpecimenBoxMenu> getSpecimenBoxMenuType() {
        return ModContent.specimenBoxMenu();
    }

    // 供客户端注册陶轮台 Screen 时获取 MenuType
    public static MenuType<PotteryWheelMenu> getPotteryWheelMenuType() {
        return ModContent.potteryWheelMenu();
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        // 两端使用同一线格式版本，字段变化时共同升级。
        var registrar = event.registrar(Constants.MOD_ID).versioned(ModPayloads.PROTOCOL_VERSION);
        // 遍历 ModPayloads 统一清单注册 C2S，避免手写重复
        for (ModPayloads.C2S<?> c2s : ModPayloads.C2S_PAYLOADS) {
            registerC2S(registrar, c2s);
        }
        // 独立服务端必须同样登记 clientbound 通道：NeoForge 握手时要求客户端声明的必需通道
        // 全部存在于服务端注册表中，缺任一条即断开连接。接收器仍由客户端侧的
        // UnsuspiciousBlockNeoForgeClient 注册，此处只补类型与编解码，并限制在独立服务端，
        // 避免同一通道在客户端被重复注册（重复注册会直接抛异常）
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            for (ModPayloads.S2CSpec<?> spec : ModPayloads.S2C_SPECS) {
                registerS2CSpec(registrar, spec);
            }
        }
    }

    // 独立服务端登记 clientbound payload：只登记类型与编解码；该方向的包不会发往服务端，handler 不会被调用
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerS2CSpec(
            PayloadRegistrar registrar,
            ModPayloads.S2CSpec<?> specRaw) {
        ModPayloads.S2CSpec<T> spec = (ModPayloads.S2CSpec<T>) specRaw;
        registrar.playToClient(spec.type(), spec.streamCodec(), (payload, context) -> {
        });
    }

    // 注册单个 C2S payload 到 NeoForge 网络注册器
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerC2S(
            PayloadRegistrar registrar,
            ModPayloads.C2S<?> c2sRaw) {
        ModPayloads.C2S<T> c2s = (ModPayloads.C2S<T>) c2sRaw;
        registrar.playToServer(c2s.type(), c2s.streamCodec(),
                (payload, context) -> c2s.handler().accept((ServerPlayer) context.player(), payload));
    }

    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        for (EntitySyncEntry<?> entry : ENTITY_SYNC_LIST) {
            entry.putAttributes(event);
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            JournalPlayerDataService.onPlayerJoined(sp);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && sp.getServer() != null) {
            JournalPlayerDataService.onPlayerLeft(sp);
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (SpecimenBoxTotemProxy.tryActivate(
                event.getEntity(), event.getSource(),
                entity -> entity.removeEffectsCuredBy(EffectCures.PROTECTED_BY_TOTEM))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        UsbCommand.register(event.getDispatcher());
    }

    // 数据包重载：只置脏标记，重建交给服务端 tick 路径（D9）。
    // 挂在这里而不是别处：/reload 与整合包换表都走服务器数据包重载，本事件是唯一能保证
    // "重载真的发生了"的入口；在回调里同步跑全量构建会拖住重载本身。
    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        LootInjectionDescriptions.beginReload();
        event.addListener(DataPackReloadListener.INSTANCE);
    }

    // 花火粉可作为燃料——800t 可烧炼 4 个物品（煤炭 1600t 烧 8 个）
    @SubscribeEvent
    public void onFurnaceFuel(FurnaceFuelBurnTimeEvent event) {
        if (event.getItemStack().is(ModItems.SPARK_DUST)) {
            event.setBurnTime(800);
        }
    }

    @SubscribeEvent
    public void onRightClickUnsuspiciousBlock(PlayerInteractEvent.RightClickBlock event) {
        if (UnsuspiciousBlockInteractions.tryBreak(
                event.getLevel(), event.getPos(), event.getEntity(), event.getItemStack())) {
            event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ModServerLifecycle.started(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ModServerLifecycle.stopped(event.getServer());
    }

    // 服务端每 tick 末尾：驱动概率模拟主线程分片消费
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        ModServerLifecycle.tick(event.getServer());
    }

    // chunk 首次生成时扫描骨块并标记为自然生成
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (!event.isNewChunk()) {
            return;
        }
        ChunkAccess chunk = event.getChunk();
        NaturalBoneBlockTracker.scanChunk(chunk);
    }

    @SubscribeEvent
    public void onWandererTrades(WandererTradesEvent event) {
        // 1 古代金币 → 5 绿宝石，最多交易 3 次
        event.getGenericTrades().add(new BasicItemListing(
                new ItemStack(ModItems.ANCIENT_COIN, 1),
                ItemStack.EMPTY,
                new ItemStack(Items.EMERALD, 5),
                3, 0, 0.05f
        ));
    }

    // 古代金币铁砧修复：低优先级监听，让其他 mod 先处理；仅在结果为空时介入。
    // 每枚金币修复目标物品 25% 最大耐久（与原版同类材料修复一致），并同步原版重命名逻辑。
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onAnvilUpdate(AnvilUpdateEvent event) {
        if (!event.getOutput().isEmpty()) {
            return;
        }
        AncientCoinRepair.Result repair = AncientCoinRepair.calculate(
                event.getLeft(), event.getRight(), event.getName());
        if (repair == null) {
            return;
        }
        event.setOutput(repair.output());
        event.setCost(repair.experienceCost());
        // 默认值会消耗整堆，必须写入实际金币消耗。
        event.setMaterialCost(repair.materialCost());
    }

}
