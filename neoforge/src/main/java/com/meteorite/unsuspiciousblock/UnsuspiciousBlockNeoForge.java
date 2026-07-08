package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.command.UsbCommand;
import com.meteorite.unsuspiciousblock.entity.EntityRegistrar;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.meteorite.unsuspiciousblock.world.NaturalBoneBlockTracker;
import com.meteorite.unsuspiciousblock.world.NeoForgeBoneBlockTracker;
import com.meteorite.unsuspiciousblock.inventory.NeoForgeInventoryPresenceAdapter;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.loot.AddItemLootModifier;
import com.meteorite.unsuspiciousblock.loot.InjectItemLootModifier;
import com.meteorite.unsuspiciousblock.platform.NeoForgeLootTableConfig;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.loottable.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.meteorite.unsuspiciousblock.network.ModPayloads;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.BasicItemListing;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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

    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Constants.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Constants.MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, Constants.MOD_ID);

    // 全局战利品修改器序列化器注册——add_item 类型供 JSON 文件引用
    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> LOOT_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Constants.MOD_ID);
    private static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<? extends IGlobalLootModifier>> ADD_ITEM =
            LOOT_MODIFIERS.register("add_item", () -> AddItemLootModifier.CODEC);
    // 埋藏宝藏注入猫之瞳（临时方案，未来会更改到自定义结构中）
    private static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<? extends IGlobalLootModifier>> INJECT_ITEM =
            LOOT_MODIFIERS.register("inject_item", () -> InjectItemLootModifier.CODEC);
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

    /** 存储 (DeferredHolder, 属性工厂) 对，泛型化以消除强制转换 */
    private record EntitySyncEntry<T extends LivingEntity>(
            DeferredHolder<EntityType<?>, EntityType<T>> deferred,
            Supplier<AttributeSupplier.Builder> attributes) {

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
                    Consumer<Supplier<EntityType<T>>> setter, Supplier<AttributeSupplier.Builder> attributes) {
                DeferredHolder<EntityType<?>, EntityType<T>> deferred = ENTITY_TYPES.register(name, factory);
                // DeferredHolder 本身即 Supplier，直接回写，无需等待 FMLCommonSetupEvent
                setter.accept(deferred);
                ENTITY_SYNC_LIST.add(new EntitySyncEntry<>(deferred, attributes));
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
        NeoForgeBoneBlockTracker.ATTACHMENT_TYPES.register(modEventBus);
        LOOT_MODIFIERS.register(modEventBus);

        modEventBus.addListener(this::syncCommonItemRefs);
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::registerEntityAttributes);

        NeoForge.EVENT_BUS.register(this);

        // 注册 NeoForge 端背包存在触发适配器（tick 驱动 diff，下线清理状态）
        NeoForgeInventoryPresenceAdapter.register();

        Constants.LOG.info("UnsuspiciousBlock NeoForge initialized.");
    }

    private void syncCommonItemRefs(FMLCommonSetupEvent event) {
        // MenuType 同步回写：RegisterMenuScreensEvent 可能在 enqueueWork 任务执行前触发，
        // 同步执行确保运行时 TYPE 就绪；客户端注册 Screen 时另有 getter 兜底
        SpecimenBoxMenu.TYPE = SPECIMEN_BOX_MENU.get();
        event.enqueueWork(() -> {
            for (ItemSyncEntry entry : ITEM_SYNC_LIST) {
                entry.setter().accept(entry.deferred().get());
            }
        });
    }

    // 供客户端在 RegisterMenuScreensEvent 时获取 MenuType，避免 enqueueWork 时序问题
    public static MenuType<SpecimenBoxMenu> getSpecimenBoxMenuType() {
        return SPECIMEN_BOX_MENU.get();
    }

//    public static MenuType<SpecimenBoxMenu> specimenBoxMenu() {
//        return SPECIMEN_BOX_MENU.get();
//    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Constants.MOD_ID).versioned("2.0");
        // 遍历 ModPayloads 统一清单注册 C2S，避免手写重复
        for (ModPayloads.C2S<?> c2s : ModPayloads.C2S_PAYLOADS) {
            registerC2S(registrar, c2s);
        }
    }

    // 注册单个 C2S payload 到 NeoForge 网络注册器
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void registerC2S(
            net.neoforged.neoforge.network.registration.PayloadRegistrar registrar,
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
            ArchaeologyJournalNetwork.syncOnJoin(sp);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        UsbCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LootProbabilitySimulationWorker.start();
        ArchaeologyJournalServerCatalog.ensureLoaded(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        LootProbabilitySimulationWorker.stop();
        ArchaeologyJournalServerCatalog.invalidate();
        NaturalBoneBlockTracker.clearPendingPlayerBreaks();
    }

    // 服务端每 tick 末尾：驱动概率模拟主线程分片消费
    @SubscribeEvent
    public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        LootProbabilitySimulationWorker.tickIfPresent(event.getServer());
    }

    // chunk 首次生成时扫描骨块并标记为自然生成
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        ChunkAccess chunk = event.getChunk();
        NaturalBoneBlockTracker.scanChunk(serverLevel, chunk.getPos().x, chunk.getPos().z);
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
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        if (!right.is(ModItems.ANCIENT_COIN)) {
            return;
        }
        if (!left.isDamageableItem() || left.getDamageValue() <= 0) {
            return;
        }

        ItemStack result = left.copy();
        // 每枚金币修复 1/4 最大耐久；maxDamage 至少为 1 以免除零
        int repairPerCoin = Math.max(1, result.getMaxDamage() / 4);
        int remaining = result.getDamageValue();
        int coinsUsed = 0;
        while (remaining > 0 && coinsUsed < right.getCount()) {
            remaining -= repairPerCoin;
            coinsUsed++;
        }
        result.setDamageValue(Math.max(0, remaining));

        // 同步重命名：原版 AnvilMenu#createResult 在 onAnvilChange 返回 false 后跳过命名处理，
        // 故此处需自行复用原版命名规则——非空且不同的名称设为 CUSTOM_NAME，空名称清除现有 CUSTOM_NAME
        int renameCost = 0;
        String name = event.getName();
        if (name != null && !StringUtil.isBlank(name)) {
            if (!name.equals(left.getHoverName().getString())) {
                result.set(DataComponents.CUSTOM_NAME, Component.literal(name));
                renameCost = 1;
            }
        } else if (name != null && left.has(DataComponents.CUSTOM_NAME)) {
            // 用户清空名称栏 → 移除自定义名
            result.remove(DataComponents.CUSTOM_NAME);
            renameCost = 1;
        }

        // 等价原版同类材料修复：base = 左右修复成本之和，每枚金币 +1，重命名 +1
        int baseCost = left.getOrDefault(DataComponents.REPAIR_COST, 0)
                + right.getOrDefault(DataComponents.REPAIR_COST, 0);
        event.setOutput(result);
        event.setCost(baseCost + coinsUsed + renameCost);
        // materialCost 默认 0 会消耗整堆金币，必须显式设为实际消耗数
        event.setMaterialCost(coinsUsed);
    }

}
