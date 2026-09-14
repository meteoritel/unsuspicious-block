# 方块与物品

本文档描述 `block/`、`item/`、`specimen/`、`pottery/`、`recipe/`、`inventory/` 包的架构：模组方块与物品的实现，便携容器机制，制陶系统与自定义配方。

## 1. 职责概述

- **不可疑方块**：可放置的"已刷完"可疑方块，封存战利品物品，支持重力与碎裂掉落。
- **陶轮与未烧制陶罐**：制陶工作站，可携带四面纹饰的陶罐半成品。
- **可疑解析仪**：扫描可疑方块内部战利品，支持范围扫描与能量系统。
- **考古铲**：快速取出已扫描可疑方块的战利品，向下连续挖掘。
- **淘盘**：对闪烁的光长按淘洗的工具；淘洗点机制见 [淘洗系统](panning.md)。
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

### 3.2 淘盘（CopperPanItem）

[`CopperPanItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/CopperPanItem.java) 对闪烁的光长按右键淘洗：使用行为复用原版「正在使用物品」减速规则（不施加药水效果），客户端渲染入口负责专属摇洗动画。耐久 32，可用铜锭铁砧修复，经 `minecraft:enchantable/durability` 兼容耐久与经验修补。淘洗结算、战利品表与淘洗点实体详见 [淘洗系统](panning.md)。

### 3.3 考古铲（ArchaeologicalShovelItem）

右键已扫描的可疑方块直接取出战利品，无需完整刷拭。直接挖掘可疑方块时，未潜行虽然会显示破坏裂纹，但最终无法破坏，也不会触发向下连挖；必须按住 Shift 才能挖掘，此时沿用潜行单格挖掘。挖掘普通铲类方块时向下连续挖掘最多 3 格，并避开可疑方块及其支撑方块。挖掘沙子/红沙/砂砾时有 0.3% 概率找到古代金币。

### 3.4 考古笔记（ArchaeologyJournalItem）

右键或按 `C` 键打开考古笔记 GUI。物品本身不持有进度数据，仅作为打开 UI 的入口；进度状态通过 mixin 附加在玩家 NBT。详见 [客户端与 GUI](client-ui.md)。

### 3.5 猫之瞳（EyeOfCatItem）

放在背包/饰品栏/标本箱中时，提供三项信息能力：附魔台完整候选、铁砧成本分解、砂轮预览。详见 [附魔系统](enchantment.md) 第 8 节与 [客户端与 GUI](client-ui.md)。

### 3.6 猫之手（HandOfCatItem）

[`HandOfCatItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/HandOfCatItem.java) 是猫族身份信物，绑定逻辑通过 `CUSTOM_DATA` 存储 `owner_uuid` + `owner_name`：

- `bindTo(stack, player)`：空白信物绑定玩家 UUID，已绑定信物不可覆盖。
- `isBound` / `isBoundTo` / `getOwnerUuid` / `getOwnerName`：查询绑定状态。
- **tooltip**：WIP 提示（首行）-> 未绑定显示"未绑定" / 已绑定显示主人 -> 本人信物显示羁绊值、当前阶段（`CatBondStage`）、残存命数 -> shift 详尽模式列出全部能力（`CatFavorAbility`）及解锁状态。
- 客户端状态由 `HandOfCatClientState` 缓存（favor/lives），通过 `SyncCatFavorPayload` 同步。

绑定与关系建立流程见 [猫族关系系统](cat-favor.md) 第 3 节。物品仍在开发中（WIP），见 [commit e6df5b0](https://github.com/meteoritel/unsuspicious-block/commit/e6df5b0)。

### 3.7 古代金币 / 失落书页 / 基页

- **古代金币**：连接考古与猫国经济的通用稀有资源。解析仪充能、铁砧修复（每枚恢复 25% 最大耐久）、流浪商人交易（1 枚换 5 绿宝石）、猫猫商人回收。
- **失落书页**：稀有考古战利品，与基页 + 书在锻造台合成随机附魔书（见 [附魔系统](enchantment.md) 第 7 节）。
- **基页**：由瓶子草合成，失落书页锻造的中间材料。

### 3.8 标本箱（SpecimenBoxItem）

[`SpecimenBoxItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/SpecimenBoxItem.java) 是 5 格便携容器，实现 [`PortableContainer`](../../common/src/main/java/com/meteorite/unsuspiciousblock/inventory/PortableContainer.java) 接口：

- 右键打开 `SpecimenBoxMenu`（5 格 GUI）。
- `getContents`：返回盒内非空物品流，供 `InventoryPresenceRegistry` 递归扫描。
- `mutateFirst`：修改第一个匹配物品（如绑定猫之手），写回 `CONTAINER` 组件。
- `getTooltipImage`：悬停时预览盒内物品（`SpecimenBoxTooltip` -> `ClientSpecimenBoxTooltip`）。
- 放背包/饰品栏时，箱内需要"随身携带"或"装备"才生效的物品（考古笔记、猫之瞳）仍正常工作。
- 箱内不死图腾可代理原版死亡保护：Fabric 通过 `ServerLivingEntityEvents.ALLOW_DEATH` 接入，NeoForge 通过可取消的 `LivingDeathEvent` 接入；两端共用 `SpecimenBoxTotemProxy` 消费箱内图腾并施加原版保护结果。
- 装备到饰品栏（Trinkets/Curios）后，代理箱内兼容饰品的属性与效果（Artifacts 适配），详见 [配置与第三方联动](config-integrations.md)。

`SpecimenBoxContents` 封装盒内物品的读写（`read` / `writeIfChanged`）。

### 3.9 回溯粉（RewindDustItem）

对世界生成结构的任意部件包围盒内方块右键，消费一份回溯粉（创造模式不消耗），由 `world/StructureRewindService` 在原位置逐区块重新放置整个结构。客户端仅预测交互，实际识别与写入均在服务端执行。未找到结构、布局读取失败、越过世界边界或已有任务时不消耗。

- **识别与布局**：`getStructureWithPieceAt(pos, holder -> true)` 使用当前区块的结构引用识别所有注册结构，包含第三方结构；仅有 Feature 或玩家手工搭建的建筑没有 StructureStart，无法识别。使用持久化起点的 NBT 副本，保留坐标、朝向、高度缓存和布局，不修改原起点或引用计数。
- **随机序列**：每区块使用 `WorldgenRandom(XoroshiroRandomSource)`，先 `setDecorationSeed(worldSeed, chunkMinX, chunkMinZ)`，再按结构在相同 Decoration 阶段中的注册顺序调用 `setFeatureSeed`，最后调用 `placeInChunk`。写入范围复用原版区块水平范围与世界高度上下各留一格的规则。
- **状态重置**：对已核实的原版部件重置沙漠神殿宝箱、丛林神庙宝箱/陷阱、要塞走廊宝箱、要塞/下界要塞刷怪笼、矿井蜘蛛刷怪笼及沼泽小屋实体的一次性标记。第三方 NBT 保持原样。下界要塞转角的 `Chest` 同时表达随机布局选择与未生成状态，不能从持久化结果恢复最初选择，保留原值。
- **调度与表现**：两端已有的服务端 tick / stopped 事件驱动服务；每台服务器最多一个任务，每 tick 放置一个区块，附加 REVERSE_PORTAL 粒子及起止音效。区块按 X 后 Z 遍历；任务仅驻留内存，停服取消，异常中止并记录日志，已完成的写入不会回滚。
- **行为边界**：这是重新执行结构放置，并非历史快照。可能覆盖玩家改建、重新产生战利品及重复生成实体；不会清除整个包围盒，也不会恢复所有原始地形或装饰 Feature。部件包围盒外的地基不一定能触发识别。同类结构在一个区块重叠时，原版共享的随机数消耗序列无法单独精确重放；当前地形、已有容器及第三方处理器也会影响结果，不能保证宝箱全部刷新或逐方块一致。
- **性能与兼容**：不一次性加载全部目标区块，但单个大型部件的 `postProcess`、同步区块加载或第三方生成器仍可能导致 tick 卡顿。生成器须支持 ServerLevel 上的放置；原版 writableArea 是传给生成器的约束，不能阻止第三方实现越界写入。权限检查采用物品编辑权限及点击位置原版权限，未接入第三方领地保护 API。

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

失落书页锻造由 `EnchantedBookRoller` 处理（见 [附魔系统](enchantment.md) 第 7 节），由数据配方 `data/unsuspiciousblock/recipe/enchant_book_smithing.json` + `SmithingMenuMixin`（拦截 `SmithingMenu.onTake`）触发，不经 `ModRecipeSerializers`。

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
