# 注册架构

本文档描述模组的"清单 + 回调"注册模式：如何在 `common` 声明注册清单，由 Fabric / NeoForge 各自完成实际注册并回写引用。

## 1. 设计目标

多平台 mod 的注册难点在于：Fabric 用 `Registry.register`，NeoForge 用 `DeferredRegister`，两者 API、时机、返回类型都不同。本项目需要一个让 `common` 玩法代码能**统一引用注册结果**、且**新增内容只改一处**的方案。

解决方案是**清单 + 回调**：

- `common` 持有 `REGISTRY_MANIFEST`（注册清单，描述"注册什么"）和静态字段（持有引用）。
- `common` 提供 `forEach(registrar)` 方法，遍历清单调用平台回调。
- 平台提供 `XxxRegistrar` 回调实现，描述"怎么注册"，注册后回写 `common` 静态字段。

这样 `common` 既不依赖平台注册 API，又能用 `ModItems.FOO`、`ModBlocks.BAR.get()` 直接引用注册结果。

## 2. Registrar 接口家族

每种可注册内容都有一个 `XxxRegistrar` 函数式接口（位于对应内容包）：

| Registrar | 位置 | 签名特点 | 回写形式 |
|---|---|---|---|
| `ItemRegistrar` | `item/` | `void register(String, Supplier<Item>, Consumer<Item>)` | 直接回写 `Item` 实例 |
| `BlockRegistrar` | `block/` | `void register(String, Supplier<Block>, Consumer<Supplier<Block>>)` | 回写 `Supplier<Block>` |
| `BlockEntityRegistrar` | `blockentity/` | `<T> void register(String, Supplier<BlockEntityType<T>>, Consumer<Supplier<...>>)` | 回写 `Supplier`（泛型方法） |
| `EntityRegistrar` | `entity/` | `<T> void register(String, Supplier<EntityType<T>>, Consumer<Supplier<...>>, Supplier<AttributeSupplier.Builder>)` | 回写 `Supplier` + 注册属性 |

### 2.1 回写形式的差异

- **Item** 直接回写 `Item` 实例（`Consumer<Item>`），因为 Fabric 注册时即可拿到实例。
- **Block / BlockEntity / Entity** 回写 `Supplier<XxxType>`（`Consumer<Supplier<...>>`），以抹平平台时机差异：
  - Fabric：包装为不可变 `Supplier` 立即回写。
  - NeoForge：`DeferredHolder` 本身即 `Supplier`，可直接回写，但实际实例在 setup 阶段才创建。

### 2.2 EntityRegistrar 的泛型设计

`EntityRegistrar` 用**泛型方法**而非泛型接口，使类型参数 `T` 在接口内被捕获，平台侧实现无需 unchecked 强制转换：

```java
@FunctionalInterface
public interface EntityRegistrar {
    <T extends LivingEntity> void register(
            String name,
            Supplier<EntityType<T>> factory,
            Consumer<Supplier<EntityType<T>>> setter,
            Supplier<AttributeSupplier.Builder> attributes);
}
```

代价是泛型方法**不能用 lambda 实现**，平台侧须用匿名内部类（见 `UnsuspiciousBlockFabric` 与 `UnsuspiciousBlockNeoForge` 中的 `new EntityRegistrar() {...}`）。

`ModEntities.forEach` 内部还有一个 `register(entry, registrar)` 泛型辅助方法，用于捕获通配符条目 `EntityEntry<?>` 的类型参数，避免平台侧强转。

## 3. 注册清单与持有类

每类内容都有一个 `ModXxx` 类，包含三部分：静态持有字段、`REGISTRY_MANIFEST` 清单、`forEach` 遍历方法。以 [`ModItems`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/ModItems.java) 为例：

```java
public class ModItems {
    // 1. 静态持有字段--由平台在注册阶段赋值
    public static SuspiciousReaderItem SUSPICIOUS_READER;
    public static Item ANCIENT_COIN;
    // ...

    // 2. 注册清单条目 record
    public record ItemEntry(String name, Supplier<Item> factory, Consumer<Item> setter) {}

    // 3. 注册清单--新增物品只需在此添加一行
    public static final List<ItemEntry> REGISTRY_MANIFEST = List.of(
            new ItemEntry("ancient_coin", ModItems::createAncientCoin, item -> ANCIENT_COIN = item),
            // ...
    );

    // 4. 遍历清单调用平台回调
    public static void forEach(ItemRegistrar registrar) {
        for (ItemEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.name(), entry.factory(), entry.setter());
        }
    }

    // 5. 工厂方法--供平台 Supplier 调用
    public static Item createAncientCoin() { return new Item(new Item.Properties()); }
}
```

### 3.1 各 ModXxx 清单

| 持有类 | 注册内容 | 条目数 | 平台回写时机 |
|---|---|---|---|
| `ModBlocks` | 方块 | 4 | Fabric 即时 / NeoForge static 块 |
| `ModItems` | 物品（含 BlockItem） | 14 | Fabric 即时 / NeoForge `FMLCommonSetupEvent.enqueueWork` |
| `ModBlockEntities` | 方块实体类型 | - | Fabric 即时 / NeoForge static 块 |
| `ModEntities` | 实体类型 | 4（信使/剑士/商人/灯笼宠物） | Fabric 即时 / NeoForge static 块；属性在 `EntityAttributeCreationEvent` |
| `ModEffects` | 药水效果 | - | Fabric 即时 / NeoForge static 块（回写 `Holder`） |
| `ModSounds` | 声音事件 | - | Fabric 即时 / NeoForge static 块（回写 `Holder`） |
| `ModRecipeSerializers` | 配方序列化器 | - | Fabric 即时 / NeoForge static 块 |

> `ModEffects` / `ModSounds` 回写的是 `Holder<MobEffect>` / `Holder<SoundEvent>`，因为 NeoForge 的 `DeferredHolder` 即 `Holder`，可直接回写；Fabric 端通过 `BuiltInRegistries.MOB_EFFECT.getHolder(...)` 取 `Holder` 回写，两端对 common 暴露统一类型。

## 4. 平台实现与回写时机

### 4.1 Fabric 端（即时回写）

Fabric 在 `onInitialize` 中直接遍历清单并 `Registry.register`，注册后立即回写：

```java
ModItems.forEach((name, factory, setter) -> {
    Item registered = Registry.register(
            BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
            factory.get());
    setter.accept(registered);  // 立即回写 ModItems 静态字段
});
```

实体因带属性，用匿名内部类：

```java
ModEntities.forEach(new EntityRegistrar() {
    @Override
    public <T extends LivingEntity> void register(String name, Supplier<EntityType<T>> factory,
            Consumer<Supplier<EntityType<T>>> setter, Supplier<AttributeSupplier.Builder> attributes) {
        EntityType<T> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, rl, factory.get());
        setter.accept(() -> type);  // 包装为不可变 Supplier
        FabricDefaultAttributeRegistry.register(type, attributes.get().build());
    }
});
```

### 4.2 NeoForge 端（延迟回写）

NeoForge 用 `DeferredRegister`，在 **static 块**中遍历清单注册 `DeferredHolder`：

```java
static {
    ModItems.forEach((name, factory, setter) -> {
        DeferredItem<Item> deferred = ITEMS.register(name, factory);
        ITEM_SYNC_LIST.add(new ItemSyncEntry(deferred, setter));  // 暂存，延后回写
    });
}
```

关键差异：**Item 不能在 static 块立即回写**，因为 Item 工厂可能依赖 Block 实例（如 `BlockItem` 需要 `ModBlocks.XXX.get()`），而 Block 的 `DeferredHolder` 在 `FMLCommonSetupEvent` 阶段才实例化。因此 Item 回写延迟到：

```java
private void syncCommonItemRefs(FMLCommonSetupEvent event) {
    event.enqueueWork(() -> {
        for (ItemSyncEntry entry : ITEM_SYNC_LIST) {
            entry.setter().accept(entry.deferred().get());  // 此时才回写真实 Item
        }
    });
}
```

而 Block / BlockEntity / Effect / Sound / Entity 的 `DeferredHolder` 本身即 `Supplier`，可在 static 块立即回写（`setter.accept(deferred)`），运行时 `.get()` 取值。

### 4.3 时序陷阱与兜底

NeoForge 客户端注册 Screen 时，`RegisterMenuScreensEvent` 可能在 `enqueueWork` 之前触发，此时 `SpecimenBoxMenu.TYPE` 静态字段可能为 null。因此 `UnsuspiciousBlockNeoForge` 提供静态 getter 兜底：

```java
public static MenuType<SpecimenBoxMenu> getSpecimenBoxMenuType() {
    return SPECIMEN_BOX_MENU.get();  // 直接从 DeferredHolder 取
}
```

客户端 `registerScreens` 中优先用 getter，并顺带回写静态字段：

```java
SpecimenBoxMenu.TYPE = UnsuspiciousBlockNeoForge.getSpecimenBoxMenuType();
event.register(SpecimenBoxMenu.TYPE, SpecimenBoxScreen::new);
```

## 5. 其他清单模式

"清单 + 遍历回调"模式不只用于注册，也用于其他需要双平台对称执行的批量任务：

| 清单类 | 用途 | 遍历方法 |
|---|---|---|
| `ModPayloads` | 网络 payload 注册清单（C2S / S2C） | 平台入口遍历 `C2S_PAYLOADS` / `Client.S2C_PAYLOADS` |
| `ModEntityRenderers` | 实体渲染器注册清单 | `forEach(BiConsumer)` |
| `ModModelLayers` | 模型层注册清单 | `forEach(BiConsumer)` |

详见 [network.md](network.md) 与 [client-ui.md](client-ui.md)。

## 6. 创造模式物品栏

`ModItems` 还声明了创造模式物品栏的图标与内容：

```java
public static final Supplier<ItemStack> CREATIVE_TAB_ICON = () -> new ItemStack(ARCHAEOLOGY_JOURNAL);

// 用 Supplier 延迟求值，因为静态字段在注册后才被赋值
public static final List<Supplier<Item>> CREATIVE_TAB_ITEMS = List.of(
        () -> ARCHAEOLOGY_JOURNAL,
        () -> UNSUSPICIOUS_SAND,
        // ...
);
```

注意 `CREATIVE_TAB_ITEMS` 用 `Supplier<Item>` 而非直接 `Item`，因为静态字段在清单声明时还是 null，必须延迟到注册完成后求值。这是清单模式的一个固有约束：**清单内不得直接引用尚未回写的静态字段值**。

## 7. 新增注册内容的步骤

以新增一个物品为例：

1. 在 `ModItems` 加静态字段：`public static XxxItem FOO;`
2. 加工厂方法：`public static XxxItem createFoo() { return new XxxItem(new Item.Properties()...); }`
3. 在 `REGISTRY_MANIFEST` 加一行：`new ItemEntry("foo", ModItems::createFoo, item -> FOO = item)`
4. 如需出现在创造模式物品栏，在 `CREATIVE_TAB_ITEMS` 加 `() -> FOO`
5. 添加数据资源（`assets/.../models/item/foo.json`、`lang` 键、可能的 `recipe` 等）

**无需修改任何平台代码**——`forEach` 会自动遍历新条目，Fabric / NeoForge 的注册逻辑都是通用的。方块、实体、效果等同理，只是条目 record 字段略有不同。

## 8. 相关文档

- [架构总览](architecture-overview.md)
- [平台抽象](platform-abstraction.md)
- [方块与物品](blocks-items.md) - 各物品/方块类的具体行为
- [实体与世界生成](entities-world.md) - 实体注册与属性
- [网络与同步](network.md) - `ModPayloads` 清单
