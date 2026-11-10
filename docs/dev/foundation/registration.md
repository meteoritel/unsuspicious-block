# 注册架构

> 如何在 `common` 声明注册清单，由 Fabric / NeoForge 各自完成实际注册并回写引用。
> 本文件是"清单 + 回调"注册模式与**新增内容标准流程**的唯一权威。

## 1. 设计目标

多平台 mod 的注册难点在于：Fabric 用 `Registry.register`，NeoForge 的 static 块在注册器与回写队列就绪后调用 `registerContent`。`ModContent` 按同一声明顺序调用 Adapter，所有工厂继续由 `DeferredRegister` 延迟执行。Item 回调保存 `DeferredItem` 与 setter 到 `ITEM_SYNC_LIST`；其他族回写 holder 或 supplier。

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

## 时序陷阱与兜底

NeoForge 客户端注册 Screen 时，`RegisterMenuScreensEvent` 可能在 `enqueueWork` 之前触发，此时 `SpecimenBoxMenu.TYPE` 静态字段可能为 null。因此 `UnsuspiciousBlockNeoForge` 提供静态 getter 兜底：

```java
public static MenuType<SpecimenBoxMenu> getSpecimenBoxMenuType() {
    return ModContent.specimenBoxMenu();  // 从声明阶段回写的 Supplier 取
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

详见 [网络与同步](network.md) 与 [客户端与 GUI](../subsystems/client-ui.md)。

## 6. 创造模式物品栏

`ModContent` 声明菜单工厂和创造标签标题、图标及内容；平台仅提供菜单注册 Adapter 与各自的创造标签 builder。菜单 Supplier 保存在 common，Fabric 即时调用 `syncMenuTypes`，NeoForge 在 common setup 同步回写 TYPE，客户端 getter 在 Screen 事件兜底。

`ModItems` 声明创造模式物品栏的图标与内容：

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

## 7. 扩展点：新增注册内容

以新增一个物品为例：

1. 在 `ModItems` 加静态字段：`public static XxxItem FOO;`
2. 加工厂方法：`public static XxxItem createFoo() { return new XxxItem(new Item.Properties()...); }`
3. 在 `REGISTRY_MANIFEST` 加一行：`new ItemEntry("foo", ModItems::createFoo, item -> FOO = item)`
4. 如需出现在创造模式物品栏，在 `CREATIVE_TAB_ITEMS` 加 `() -> FOO`
5. 添加数据资源（`assets/.../models/item/foo.json`、`lang` 键、可能的 `recipe` 等）

**无需修改任何平台代码** —— `forEach` 会自动遍历新条目，Fabric / NeoForge 的注册逻辑都是通用的。方块、实体、效果等同理，只是条目 record 字段略有不同。

> 新增内容若带玩法逻辑，记得同步该子系统的「代码地图」与「扩展点」两节（见[文档维护](../../README.md)）。

## 8. 约束与陷阱

- **清单内不得引用尚未回写的静态字段**：需要延迟求值时用 `Supplier`（第 6 节）。
- **条目数不要写进文档**：它会随每次新增功能变化。
- **`EntityRegistrar` 只能用匿名内部类**，不能用 lambda（泛型方法的限制）。
- **新注册内容的资源缺失不会在注册期报错**：缺 `lang` 键或模型只会在游戏里显示为 `item.unsuspiciousblock.foo` 字面量或紫黑方块，排查时先看 `assets/`。

## 9. 相关文档

- [架构总览](architecture.md) —— 三模块划分与初始化流程
- [平台抽象](platform-spi.md) —— SPI 接口如何被加载
- [网络与同步](network.md) —— `ModPayloads` 清单
- [客户端与 GUI](../subsystems/client-ui.md) —— 渲染器与模型层清单
- [方块与物品](../subsystems/blocks-items.md) —— 各物品/方块类的具体行为
- [实体与 AI](../subsystems/entities-world.md) —— 实体注册与属性
