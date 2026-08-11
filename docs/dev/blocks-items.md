# 方块与物品

本文档描述 `block/`、`item/`、`specimen/`、`pottery/`、`recipe/`、`inventory/` 包的架构：模组方块与物品的实现，便携容器机制，制陶系统与自定义配方。

## 1. 职责概述

- **不可疑方块**：可放置的"已刷完"可疑方块，封存战利品物品，支持重力与碎裂掉落。
- **陶轮与未烧制陶罐**：制陶工作站，可携带四面纹饰的陶罐半成品。
- **可疑解析仪**：扫描可疑方块内部战利品，支持范围扫描与能量系统。
- **考古铲**：快速取出已扫描可疑方块的战利品，向下连续挖掘。
- **考古笔记**：打开考古笔记 GUI 的物品。
- **猫之瞳 / 猫之手**：猫族系统的信息能力信物与身份信物。
- **古代金币 / 失落书页 / 基页**：经济与附魔材料。
- **标本箱**：便携容器，箱内物品的"背包生效"功能正常触发。
- **便携容器机制**：`PortableContainer` 接口 + `InventoryPresenceRegistry` 递归扫描。

## 2. 方块

### 2.1 不可疑方块（UnsuspiciousBlock）

[`UnsuspiciousBlock`](../../common/src/main/java/com/meteorite/unsuspiciousblock/block/UnsuspiciousBlock.java) 是"不可疑的沙子"与"不可疑的沙砾"的共用实现，继承 `FallingBlock` + `EntityBlock`：

- **重力检测**：`tick` 中检测下方是否空闲，空闲则 `destroyBlock` 掉落（与原版沙子一致）。
- **封存数据**：`setPlacedBy` 从物品的 `CONTAINER` 组件加载封存物品到方块实体；`onRemove` 取出封存物品 `popResource`。
- **方块实体**：[`UnsuspiciousBlockEntity`](../../common/src/main/java/com/meteorite/unsuspiciousblock/blockentity/UnsuspiciousBlockEntity.java) 持有封存物品与合成者身份。
- **属性**：`ofFullCopy(SUSPICIOUS_SAND/SUSPICIOUS_GRAVEL)`，与原版可疑方块一致。

[`SealedContents`](../../common/src/main/java/com/meteorite/unsuspiciousblock/block/SealedContents.java) 是封存内容与合成者信息的读写工具：

- `seal(carrier, item)`：将物品写入 `CONTAINER` 组件。
- `getSealedItem` / `isSealed`：读取封存物品。
- `recordCrafter` / `getCrafter`：记录合成者身份（`CrafterIdentity{uuid, name}`），用于解析仪显示"由谁封存"。
- `SealedContentsDisplay`：客户端渲染封存物品的展示。

### 2.2 陶轮（PotteryWheelBlock）

[`PotteryWheelBlock`](../../common/src/main/java/com/meteorite/unsuspiciousblock/block/PotteryWheelBlock.java) + [`PotteryWheelBlockEntity`](../../common/src/main/java/com/meteorite/unsuspiciousblock/blockentity/PotteryWheelBlockEntity.java) + [`PotteryWheelMenu`](../../common/src/main/java/com/meteorite/unsuspiciousblock/pottery/PotteryWheelMenu.java) 构成制陶工作站：

- 纹饰陶轮台，方块属性沿用橡木板 + 强度 2.5 + noOcclusion。
- 方块实体持有陶轮的加工状态。
- 菜单提供玩家交互界面，客户端有 `PotteryWheelScreen` 与 `PotteryWheelRenderer`（方块实体渲染）。

### 2.3 未烧制陶罐

[`UnfiredDecoratedPotBlock`](../../common/src/main/java/com/meteorite/unsuspiciousblock/block/UnfiredDecoratedPotBlock.java) + [`UnfiredDecoratedPotItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/UnfiredDecoratedPotItem.java) + [`UnfiredDecoratedPotBlockEntity`](../../common/src/main/java/com/meteorite/unsuspiciousblock/blockentity/UnfiredDecoratedPotBlockEntity.java)：

- 可携带四面纹饰数据的陶罐半成品，烧制后成为完整纹饰陶罐。
- `UnfiredDecoratedSherdItem`：未烧制的纹饰陶片，保存纹饰数据。
- 烧制配方由 `UnfiredDecoratedPotSmeltingRecipe` / `UnfiredDecoratedSherdSmeltingRecipe` 处理。

## 3. 物品

### 3.1 可疑解析仪（SuspiciousReaderItem）

[`SuspiciousReaderItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/SuspiciousReaderItem.java) 是模组核心工具，能量与扫描等级通过 `CUSTOM_DATA` 组件存储：

| 常量 | 值 | 说明 |
|---|---|---|
| `MAX_ENERGY` | 512 | 最大能量 |
| `ENERGY_PER_COIN` | 256 | 一枚古代金币补充能量 |
| `MAX_SCAN_LEVEL` | 3 | 最大扫描等级（3x3x3/5x5x5/7x7x7） |

**扫描模式**（`V` 键切换）：

- **0 级单方块**：右键可疑方块，免费查看内部战利品（不取出）。
- **1-3 级范围**：以点击面反向偏移 `scanLevel` 格为中心，扫描 `(2*level+1)^3` 立方体。消耗 = 等级数 + 新扫描的可疑方块数（已扫描的不额外消耗）。同时高亮战利品容器。

**能量与充能**：

- 耐久条显示能量（`isBarVisible` / `getBarWidth` / `getBarColor`，绿/黄/红三色）。
- 能量不足时自动从背包消耗古代金币充能（`tryRecharge`）。
- shift+右键空气手动充能，带**浪费保护**：剩余空间不足一枚金币的充能值时，首次提示，20t 内双击强制充能。

**主副手协作**：主手解析仪 + 副手考古铲时，点击已扫描的可疑方块会**让位给副手考古铲**取出战利品（`useOn` 开头判定，客户端须返回 PASS 触发原版副手流程）。

**追踪接入**：`scanBrushable` 解析战利品后，通过 `LootTrackingContext` + `LootTrackingEvents.submit` 接入考古笔记追踪，使用 `deferred` 结算策略（待定日志暂存到方块实体，物品实际取出后转正）。详见 [考古笔记系统](journal.md)。

### 3.2 考古铲（ArchaeologicalShovelItem）

右键已扫描的可疑方块直接取出战利品，无需完整刷拭。向下连续挖掘最多 3 格铲类方块；潜行时单格挖掘，避开可疑方块及其支撑方块。挖掘沙子/红沙/砂砾时有 0.3% 概率找到古代金币。

### 3.3 考古笔记（ArchaeologyJournalItem）

右键或按 `C` 键打开考古笔记 GUI。物品本身不持有进度数据，仅作为打开 UI 的入口；进度状态通过 mixin 附加在玩家 NBT。详见 [客户端与 GUI](client-ui.md)。

### 3.4 猫之瞳（EyeOfCatItem）

放在背包/饰品栏/标本箱中时，提供三项信息能力：附魔台完整候选、铁砧成本分解、砂轮预览。详见 [附魔系统](enchantment.md) 第 8 节与 [客户端与 GUI](client-ui.md)。

### 3.5 猫之手（HandOfCatItem）

[`HandOfCatItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/HandOfCatItem.java) 是猫族身份信物，绑定逻辑通过 `CUSTOM_DATA` 存储 `owner_uuid` + `owner_name`：

- `bindTo(stack, player)`：空白信物绑定玩家 UUID，已绑定信物不可覆盖。
- `isBound` / `isBoundTo` / `getOwnerUuid` / `getOwnerName`：查询绑定状态。
- **tooltip**：WIP 提示（首行）-> 未绑定显示"未绑定" / 已绑定显示主人 -> 本人信物显示羁绊值、当前阶段（`CatBondStage`）、残存命数 -> shift 详尽模式列出全部能力（`CatFavorAbility`）及解锁状态。
- 客户端状态由 `HandOfCatClientState` 缓存（favor/lives），通过 `SyncCatFavorPayload` 同步。

绑定与关系建立流程见 [猫族关系系统](cat-favor.md) 第 3 节。物品仍在开发中（WIP），见 [commit e6df5b0](https://github.com/meteoritel/unsuspicious-block/commit/e6df5b0)。

### 3.6 古代金币 / 失落书页 / 基页

- **古代金币**：连接考古与猫国经济的通用稀有资源。解析仪充能、铁砧修复（每枚恢复 25% 最大耐久）、流浪商人交易（1 枚换 5 绿宝石）、猫猫商人回收。
- **失落书页**：稀有考古战利品，与基页 + 书在锻造台合成随机附魔书（见 [附魔系统](enchantment.md) 第 7 节）。
- **基页**：由瓶子草合成，失落书页锻造的中间材料。

### 3.7 标本箱（SpecimenBoxItem）

[`SpecimenBoxItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/SpecimenBoxItem.java) 是 5 格便携容器，实现 [`PortableContainer`](../../common/src/main/java/com/meteorite/unsuspiciousblock/inventory/PortableContainer.java) 接口：

- 右键打开 `SpecimenBoxMenu`（5 格 GUI）。
- `getContents`：返回盒内非空物品流，供 `InventoryPresenceRegistry` 递归扫描。
- `mutateFirst`：修改第一个匹配物品（如绑定猫之手），写回 `CONTAINER` 组件。
- `getTooltipImage`：悬停时预览盒内物品（`SpecimenBoxTooltip` -> `ClientSpecimenBoxTooltip`）。
- 放背包/饰品栏时，箱内需要"随身携带"或"装备"才生效的物品（考古笔记、猫之瞳）仍正常工作。
- 装备到饰品栏（Trinkets/Curios）后，代理箱内兼容饰品的属性与效果（Artifacts 适配），详见 [配置与第三方联动](config-integrations.md)。

`SpecimenBoxContents` 封装盒内物品的读写（`read` / `writeIfChanged`）。

## 4. 便携容器与背包存在检测

`inventory/` 包实现"容器在背包 -> 容器内物品效果触发"的递归扫描机制：

- [`PortableContainer`](../../common/src/main/java/com/meteorite/unsuspiciousblock/inventory/PortableContainer.java)：物品实现此接口后，`InventoryPresenceRegistry` 会递归扫描容器内物品。短路匹配（找到即停止），无实现时无需分配空集合。
- [`InventoryPresenceRegistry`](../../common/src/main/java/com/meteorite/unsuspiciousblock/inventory/InventoryPresenceRegistry.java)：统一查询入口，`isPresent(player, item)` / `containsMatching` / `mutateFirst` 递归扫描主背包 + 饰品栏 + 便携容器内容。
- [`InventoryPresenceTrigger`](../../common/src/main/java/com/meteorite/unsuspiciousblock/inventory/InventoryPresenceTrigger.java)：触发器，定义哪些物品需要被检测。
- [`PlayerPresenceStateHolder`](../../common/src/main/java/com/meteorite/unsuspiciousblock/inventory/PlayerPresenceStateHolder.java)：mixin 接口，附加到玩家持有 tick 驱动的 diff 状态。
- 平台适配：`FabricInventoryPresenceAdapter` / `NeoForgeInventoryPresenceAdapter`，tick 驱动 diff 检测，下线清理状态。

这一机制让"猫之瞳/考古笔记/猫之手放在标本箱里是否生效"有统一答案：标本箱实现 `PortableContainer`，`InventoryPresenceRegistry` 递归扫描时会把箱内物品纳入检测。

## 5. 制陶系统

`pottery/` 包（目前仅 `PotteryWheelMenu`）+ `block/PotteryWheelBlock` + `blockentity/PotteryWheelBlockEntity` 构成制陶工作站。未烧制陶罐/陶片通过烧制配方转为成品，配方序列化器见下节。

## 6. 配方序列化器

[`ModRecipeSerializers`](../../common/src/main/java/com/meteorite/unsuspiciousblock/recipe/ModRecipeSerializers.java) 注册自定义配方序列化器（沿用清单 + 回调模式）：

| 序列化器 | 用途 |
|---|---|
| `UnsuspiciousCreationRecipe` | 不可疑方块合成（封存物品到方块） |
| `UnsuspiciousSealingRecipe` | 封存配方（将物品封入不可疑方块） |
| `UnfiredDecoratedPotSmeltingRecipe` | 未烧制陶罐烧制为纹饰陶罐 |
| `UnfiredDecoratedSherdSmeltingRecipe` | 未烧制纹饰陶片烧制 |

失落书页锻造由 `EnchantedBookRoller` 处理（见 [附魔系统](enchantment.md) 第 7 节），通过配方序列化器触发。

## 7. 扩展点

- **新增物品/方块**：按 [注册架构](registration.md) 第 7 节的步骤，在 `ModItems` / `ModBlocks` 清单加条目。
- **新增便携容器**：物品实现 `PortableContainer` 接口，`InventoryPresenceRegistry` 自动递归扫描。
- **新增封存方块**：参考 `UnsuspiciousBlock` + `SealedContents`，用 `CONTAINER` 组件存储封存物品。
- **新增配方**：实现 `RecipeSerializer`，在 `ModRecipeSerializers` 清单注册，添加 `data/unsuspiciousblock/recipe/` JSON。
- **解析仪能量调优**：修改 `MAX_ENERGY` / `ENERGY_PER_COIN` / `MAX_SCAN_LEVEL` 常量。

## 8. 相关文档

- [注册架构](registration.md) - `ModItems` / `ModBlocks` 清单模式
- [考古笔记系统](journal.md) - 解析仪的追踪接入
- [猫族关系系统](cat-favor.md) - 猫之手绑定
- [附魔系统](enchantment.md) - 猫之瞳、失落书页锻造
- [客户端与 GUI](client-ui.md) - 标本箱/陶轮 Screen、tooltip
- [配置与第三方联动](config-integrations.md) - 标本箱饰品代理
