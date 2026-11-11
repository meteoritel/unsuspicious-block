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

Block / BlockEntity / Entity / Recipe 的回交引用为 Supplier，Effect / Sound 为 Holder，可在声明阶段保存，注册完成后求值。`Registrar<T,H>` 明确注册值与回交值的类型；Entity 属性、BlockEntity 及 Menu 的泛型关联保留具名注册接口，Feature 与创造标签用具名入口表达无回写注册。

## 菜单与条件类型的获取相位

Menu 注册通过 setter 将 `Supplier<MenuType<T>>` 保存到 `ModContent`；菜单构造与客户端 Screen 清单都调用同一 getter。Fabric 注册后可求值，NeoForge 在注册事件完成后的 Screen 注册相位求值。Menu 不维护公共可写 TYPE，也不依赖 Item 的 enqueueWork 回写。

平台将工具附魔条件类型 Supplier 作为 `UnsuspiciousBlockCommon.init` 的输入。`ModLootConditions.registerAnalysisHandlers` 一次绑定引用并登记描述符，绑定时不求值。未绑定或重复绑定提供具名异常；实际条件调用在注册表可用时求值。

## 5. 其他清单模式

"清单 + 遍历回调"模式不只用于注册，也用于其他需要双平台对称执行的批量任务：

| 清单类 | 用途 | 遍历方法 |
|---|---|---|
| `ModPayloads` | 网络 payload 注册清单（C2S / S2C） | 平台入口遍历 `C2S_PAYLOADS` / `S2C_PAYLOADS`；客户端遍历 `ClientPayloadBindings` |
| `ModEntityRenderers` | 实体渲染器注册清单 | `forEach(EntityRendererRegistrar)` |
| `ModModelLayers` | 模型层注册清单 | `forEach(LayerRegistrar)` |
| `ModBlockEntityRenderers` | 方块实体渲染器注册清单 | `forEach(RendererRegistrar)` |
| `ModMenuScreens` | Menu 与 Screen 注册清单 | `forEach(ScreenRegistrar)` |
| `ModTooltipComponents` | 客户端 tooltip component 工厂清单 | 平台事件遍历 |
| `ModKeyBindings` | 按键注册清单 | 平台遍历 `MANIFEST` |
| `ModHudRenderers` | HUD 绘制清单 | `renderAll`，各 HUD 保留显示门禁 |
| `ModWorldRenderers` | 世界叠加绘制清单 | `renderAll`，平台保留世界阶段适配 |

详见 [网络与同步](network.md) 与 [客户端与 GUI](../subsystems/client-ui.md)。

## 6. 创造模式物品栏

`ModContent` 声明菜单工厂和创造标签标题、图标及内容；平台提供类型化注册 Adapter 与创造标签 builder。

`ModItems.ItemEntry` 同时声明注册工厂、回写、延迟 getter、可选创造栏顺序和类别。`CREATIVE_TAB_ITEMS` 按顺序投影，重复排序值在初始化时拒绝；`PAN_ITEMS` 按 PAN 类别投影。投影只保存 Supplier，使用时才读取已回写物品，保持 NeoForge 的延迟相位。

`UnsuspiciousCarrier` 是封存与空方块制作共用的载体。序列化器清单用载体和合成种类组成键，初始化检查重复与缺失组合；烧制项保留独立注册。配方从同一映射获取已注册 serializer。

## 7. 扩展点：新增注册内容

以新增一个物品为例：

1. 在 `ModItems` 加静态字段：`public static XxxItem FOO;`
2. 加工厂方法：`public static XxxItem createFoo() { return new XxxItem(new Item.Properties()...); }`
3. 在 `REGISTRY_MANIFEST` 加一行：`new ItemEntry("foo", ModItems::createFoo, item -> FOO = item, () -> FOO, order, Category.STANDARD)`
4. 创造栏顺序填唯一排序值，隐藏项填 null；淘盘选择 `Category.PAN`，客户端模型属性视图随清单派生
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
