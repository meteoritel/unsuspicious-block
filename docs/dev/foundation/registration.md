# 注册架构

> 本文档说明如何在 `common` 声明内容清单，由 Fabric 与 NeoForge 各自完成平台注册与引用回写。
> 本文件是「清单 + 回调」注册模式与**新增可注册内容流程**的唯一权威。

## 1. 设计目标与即时/延迟机制

多平台模组注册的核心差异在于生命周期时序：

- **Fabric**：在模组初始化期间直接调用 `Registry.register(...)`，创建对象并**即时回写**到 `common` 的静态字段或持有者。
- **NeoForge**：在模组构造阶段的静态块绑定注册器后，由 `DeferredRegister` 将对象创建推迟到注册事件阶段；因此 `ModContent` 按统一声明顺序调用平台适配器，并将真实实例或延迟代理（`DeferredHolder` / `Supplier`）交回给 `common`。

**Item 静态字段延迟回写的关键原因**：物品工厂可能依赖已完成创建的方块实例（例如 `BlockItem` 构造时需调用 `ModBlocks.XXX.get()`），而方块的 `DeferredHolder` 在 `FMLCommonSetupEvent` 之前尚未填充。因此 Item 的静态字段不能在注册回调期立即赋值，平台需收集 `(DeferredItem, setter)` 列表并在 `FMLCommonSetupEvent#enqueueWork` 中统一写回：

```java
private void syncCommonItemRefs(FMLCommonSetupEvent event) {
    event.enqueueWork(() -> {
        for (ItemSyncEntry entry : ITEM_SYNC_LIST) {
            entry.setter().accept(entry.deferred().get());
        }
    });
}
```

不同内容族采用与各自时序匹配的回写类型：`Block` / `BlockEntity` / `Entity` / `RecipeSerializer` 回写 `Supplier`，`MobEffect` / `SoundEvent` 回写 `Holder`；通用注册接口 `Registrar<T, H>` 明确约束注册原值与回写句柄类型，实体属性、方块实体、菜单等关联注册使用专用具名接口，地物（`Feature`）与创造模式标签页使用无回写注册入口。

## 2. 菜单与条件类型的获取时机

- **菜单类型 (`MenuType`)**：注册时通过 setter 将 `Supplier<MenuType<T>>` 写入 `ModContent`，菜单构造器与客户端 `ModMenuScreens` 清单统一调用同一 getter。Fabric 在注册后立即可求值，NeoForge 在注册事件完成后的客户端 Screen 注册阶段求值，避免依赖物品的 `enqueueWork` 队列。
- **战利品条件类型 (`LootItemConditionType`)**：平台将工具附魔条件类型的 `Supplier` 传入 `UnsuspiciousBlockCommon.init`。`ModLootConditions.registerAnalysisHandlers` 在初始化期完成引用绑定与描述符登记而不提前求值（未绑定或重复绑定会立即 fail-fast 报错），待注册表可用时再实际解析条件实例。

## 3. 清单 + 遍历回调模式

「清单 + 遍历回调」模式同样用于所有需要双端对称执行的批量注册或渲染装配任务：

| 清单类 | 用途 | 遍历方式与详细文档 |
|---|---|---|
| `ModPayloads` | 网络 Payload 声明（C2S / S2C） | 平台遍历 `C2S_PAYLOADS` / `S2C_PAYLOADS`；客户端遍历 `ClientPayloadBindings`（见[网络与同步](network.md)） |
| `ModEntityRenderers` | 实体渲染器注册清单 | `forEach(EntityRendererRegistrar)`（见[客户端与 GUI](../subsystems/client-ui.md)） |
| `ModModelLayers` | 模型层注册清单 | `forEach(LayerRegistrar)` |
| `ModBlockEntityRenderers` | 方块实体渲染器清单 | `forEach(RendererRegistrar)` |
| `ModMenuScreens` | 菜单与 Screen 绑定清单 | `forEach(ScreenRegistrar)` |
| `ModTooltipComponents` | 客户端富 Tooltip 工厂清单 | 平台在 Tooltip 注册事件中遍历 |
| `ModKeyBindings` | 客户端按键清单 | 平台遍历 `MANIFEST` |
| `ModHudRenderers` | HUD 叠加绘制清单 | `renderAll`（各 HUD 自持显示门禁） |
| `ModWorldRenderers` | 世界半透明后阶段叠加绘制清单 | `renderAll` |

## 4. 创造模式物品栏与派生视图

`ModContent` 集中声明菜单、创造标签页标题、图标及展示顺序；平台提供类型化注册适配器与标签页构建器。

`ModItems.ItemEntry` 同时声明物品工厂、静态字段回写 setter、延迟 getter、可选创造栏顺序与分类标记：

- `CREATIVE_TAB_ITEMS`：按 `creativeOrder` 升序投影为 `Supplier<Item>` 列表（初始化时校验重复排序值，隐藏物品填 `null`）。
- `PAN_ITEMS`：按 `Category.PAN` 筛选投影，供客户端动态注册淘盘模型属性时遍历。

所有投影视图仅保存 `Supplier`，直到游戏内实际构造物品栏或注册客户端属性时才求值，以此兼容 NeoForge 的延迟初始化时序。

## 5. 扩展点：新增注册内容

以新增一个普通物品为例：

1. 在 `ModItems` 中声明静态字段：`public static XxxItem FOO;`
2. 编写静态工厂方法：`public static XxxItem createFoo() { return new XxxItem(new Item.Properties()...); }`
3. 在 `ModItems.REGISTRY_MANIFEST` 追加条目：`new ItemEntry("foo", ModItems::createFoo, item -> FOO = item, () -> FOO, order, Category.STANDARD)`（创造栏排序值须唯一，隐藏物品填 `null`，淘盘类选择 `Category.PAN`）。
4. 添加客户端与数据资源：`assets/unsuspiciousblock/models/item/foo.json`、`en_us.json` / `zh_cn.json` 键及所需配方 JSON。
5. **无需改动任何平台代码**，`fabric` 与 `neoforge` 会自动遍历清单完成注册。

新增方块、方块实体、实体、配方序列化器或状态效果流程相同，只需按对应 `ModXxx` 的清单记录格式追加一行；若涉及新玩法逻辑，需同步更新对应子系统文档的「代码地图」与「扩展点」。

## 6. 约束与陷阱

- **清单声明期不得直接引用尚未回写的静态字段**：跨对象依赖一律通过 `Supplier` 或工厂方法内的延迟调用获取。
- **不将注册条目总数写入文档**：条目数量随版本迭代持续变动，具体清单一律以源码为准。
- **实体渲染器注册接口兼容性**：`EntityRegistrar` 因泛型约束需使用匿名内部类实现，不可简写为 Lambda 表达式。
- **资源缺失不触发注册期异常**：漏写模型或语言键仅在游戏中表现为紫黑块或未翻译 key，新增内容后应核对 `assets/` 与语言文件。

## 7. 相关文档

- [架构总览](architecture.md)
- [平台抽象](platform-spi.md)
- [网络与同步](network.md)
- [客户端与 GUI](../subsystems/client-ui.md)
- [方块与物品](../subsystems/blocks-items.md)
- [实体与 AI](../subsystems/entities-world.md)
