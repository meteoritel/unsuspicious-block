# 方块与物品

> `block/`、`blockentity/`、`item/`、`specimen/`、`pottery/`、`recipe/`、`inventory/` 包：方块与物品的实现、便携容器机制、制陶系统、结构回溯与自定义配方。
> 本文件是这些内容的唯一权威。注册清单模式见 [注册架构](../foundation/registration.md)；淘洗点机制与变体系统见 [淘洗系统](panning.md)。

## 1. 代码地图

**方块与方块实体**

| 内容 | 类 | 角色 |
|---|---|---|
| 不可疑的沙子 / 沙砾 | `block/UnsuspiciousBlock` + `blockentity/UnsuspiciousBlockEntity` | 可放置的"已刷完"可疑方块，封存战利品，有重力与碎裂掉落 |
| 陶轮台 | `block/PotteryWheelBlock` + `blockentity/PotteryWheelBlockEntity` + `pottery/PotteryWheelMenu` | 制陶工作站 |
| 未烧制陶罐 | `block/UnfiredDecoratedPotBlock` + `blockentity/UnfiredDecoratedPotBlockEntity` | 带四面纹饰数据的陶罐半成品 |
| 扫描状态注入 | `blockentity/BrushableBlockEntityScanState` | 经 mixin 注入原版可疑方块实体（见 [Mixin](../foundation/mixin.md)） |
| 容器追踪状态 | `blockentity/TrackedContainerLootState`、`blockentity/DecoratedPotLootState` | 容器/陶罐的开箱追踪状态 |

**物品**（均在 `item/`；`ModItems.REGISTRY_MANIFEST` 是唯一清单）

| 物品 | 类 | 角色 |
|---|---|---|
| 可疑解析仪 | `SuspiciousReaderItem` | 扫描可疑方块内部战利品；范围扫描 + 能量系统 |
| 考古铲 | `ArchaeologicalShovelItem` | 对已扫描点直接取物；向下连挖 |
| 淘盘 ×3（铜/金/黑曜石） | `PanItem` + `PanProfile` | 对淘洗点长按淘洗；三把盘共用基类 |
| 考古笔记 | `ArchaeologyJournalItem` | 打开笔记 GUI 的入口（不持有数据） |
| 猫之瞳 | `EyeOfCatItem` | 附魔台候选 / 铁砧分解 / 砂轮预览的持有判据 |
| 猫之手 | `HandOfCatItem` | 猫族身份信物，绑定 owner UUID |
| 标本箱 | `SpecimenBoxItem` | 5 格便携容器 |
| 回溯粉 | `RewindDustItem` | 结构原位重生成 |
| 古代金币 / 失落书页 / 基页 / 花火粉 | 无独立类，`ModItems` 工厂返回 `Item` 或 `DescribedItem` | 经济与合成材料 |
| 素材类基类 | `DescribedItem` | 只需一行 GRAY 简介的物品基类 |

**其他**

| 层 | 位置 |
|---|---|
| 数据资源 | `data/unsuspiciousblock/recipe/*.json`；`assets/.../models/{block,item}/`、`blockstates/`、`lang/` |
| 网络 | 无专属 payload（解析仪扫描结果走 `SyncReaderScanResultPayload`，见 [网络与同步](../foundation/network.md)） |
| 客户端 | `client/ui/screen/{SpecimenBoxScreen,PotteryWheelScreen}`、`client/ui/tooltip/ClientSpecimenBoxTooltip`、`client/renderer/{PotteryWheelRenderer,PotteryWheelModel}`、`client/hud/SuspiciousReaderHud`、`client/state/SuspiciousReaderClientState`、`client/ui/PotteryPreviewRenderer`（见 [客户端与 GUI](client-ui.md)） |
| Mixin | `block/*`（扫描状态、陶罐行为）、`container/*`（容器追踪）、`inventory/PlayerPresenceStateMixin`（背包存在检测） |
| 平台差异 | 花火粉燃料：Fabric 用 `FuelRegistry`，NeoForge 用 `FurnaceFuelBurnTimeEvent`；标本箱图腾代理：Fabric 用 `ServerLivingEntityEvents.ALLOW_DEATH`，NeoForge 用 `LivingDeathEvent`；背包存在检测：两端的 `*InventoryPresenceAdapter` |

## 2. 数据流

**封存（合成 → 放置 → 取出）**

```
合成：UnsuspiciousSealingRecipe / UnsuspiciousCreationRecipe
  └─ SealedContents.seal(carrier, item)  写入 CONTAINER 组件（+ recordCrafter 记合成者）
放置：UnsuspiciousBlock.setPlacedBy
  └─ 从 CONTAINER 组件加载到 UnsuspiciousBlockEntity
取出：UnsuspiciousBlock.onRemove
  └─ popResource 取出封存物品
```

**解析仪扫描（接入考古笔记）**

```
SuspiciousReaderItem.useOn（0 级单方块 / 1-3 级范围）
  └─ scanBrushable 解析战利品
       └─ LootTrackingContext + LootTrackingEvents.submit（deferred 结算策略：
            日志暂存到方块实体，物品被实际取出后转正）
```

**便携容器存在检测**

```
玩家 tick（两端 *InventoryPresenceAdapter）
  └─ InventoryPresenceRegistry.isPresent(player, item)
       └─ 递归扫描 主背包 + 饰品栏 + PortableContainer 内容
            （标本箱实现 PortableContainer → 箱内物品纳入检测）
       └─ PlayerPresenceStateHolder 上的 diff 状态驱动"进出"事件
```

## 3. 关键类

| 类 | 职责 |
|---|---|
| `block/UnsuspiciousBlock` | 不可疑方块共用实现，继承 `FallingBlock` + `EntityBlock` |
| `block/SealedContents` | 封存内容读写：`seal` / `getSealedItem` / `isSealed` / `recordCrafter` / `getCrafter` |
| `block/SealedContentsDisplay` | 封存物品的展示行构建（物品 tooltip 与 Jade 共用） |
| `block/UnsuspiciousBlockInteractions` | 不可疑方块上的交互收敛点 |
| `item/SuspiciousReaderItem` | 解析仪：能量、扫描等级、范围扫描、主副手协作 |
| `item/ArchaeologicalShovelItem` | 考古铲：取物、连挖、沙子中找金币 |
| `item/PanItem` / `item/PanProfile` | 淘盘基类 / 三把盘的差异（可采变体、幸运加成、再生概率） |
| `item/SpecimenBoxItem` + `specimen/SpecimenBoxContents` | 标本箱物品与盒内物品读写 |
| `specimen/SpecimenBoxMenu` / `SpecimenBoxTooltip` / `SpecimenBoxTotemProxy` | 5 格菜单 / 悬停预览 / 图腾代理 |
| `item/RewindDustItem` + `world/StructureRewindService` + `world/StructureRewindCooldownData` | 结构回溯的入口、执行服务与冷却账本 |
| `inventory/PortableContainer` | 物品实现此接口即被递归扫描 |
| `inventory/InventoryPresenceRegistry` | 统一查询入口：`isPresent` / `containsMatching` / `mutateFirst` |
| `inventory/InventoryPresenceTrigger` | 定义哪些物品需要被检测 |
| `inventory/PlayerPresenceStateHolder` | mixin 接口，附加到玩家的 tick 驱动 diff 状态 |
| `recipe/ModRecipeSerializers` | 四个自定义配方序列化器的注册清单 |

## 4. 不可疑方块与封存

[`UnsuspiciousBlock`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/block/UnsuspiciousBlock.java) 是"不可疑的沙子"与"不可疑的沙砾"的共用实现：

- **重力检测**：`tick` 中检测下方是否空闲，空闲则 `destroyBlock` 掉落（与原版沙子一致）。
- **封存数据**：`setPlacedBy` 从物品的 `CONTAINER` 组件加载封存物品到方块实体；`onRemove` 取出封存物品 `popResource`。
- **方块实体**：`UnsuspiciousBlockEntity` 持有封存物品与合成者身份。
- **属性**：`ofFullCopy(SUSPICIOUS_SAND / SUSPICIOUS_GRAVEL)`，与原版可疑方块一致。

`SealedContents` 的 `recordCrafter` 记录合成者身份（`CrafterIdentity{uuid, name}`），用于解析仪显示"由谁封存"。

## 5. 制陶

- **陶轮台**：纹饰陶轮台，方块属性沿用橡木板 + 强度 2.5 + noOcclusion；方块实体持有加工状态；`PotteryWheelMenu` 提供交互界面，客户端有 `PotteryWheelScreen` 与 `PotteryWheelRenderer`。
- **未烧制陶罐 / 陶片**：`UnfiredDecoratedPotBlock` + `UnfiredDecoratedPotItem` + `UnfiredDecoratedPotBlockEntity` 构成可携带四面纹饰数据的半成品；`UnfiredDecoratedSherdItem` 保存纹饰数据。烧制由 `UnfiredDecoratedPotSmeltingRecipe` / `UnfiredDecoratedSherdSmeltingRecipe` 处理。

## 6. 可疑解析仪与考古铲

[`SuspiciousReaderItem`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/item/SuspiciousReaderItem.java) 是模组核心工具，能量与扫描等级存在 `CUSTOM_DATA` 组件：

| 常量 | 值 | 说明 |
|---|---|---|
| `MAX_ENERGY` | 512 | 最大能量 |
| `ENERGY_PER_COIN` | 256 | 一枚古代金币补充能量 |
| `MAX_SCAN_LEVEL` | 3 | 最大扫描等级（3x3x3 / 5x5x5 / 7x7x7） |

- **扫描模式**（`V` 键切换）：0 级右键单方块免费查看内部战利品（不取出）；1-3 级以点击面反向偏移 `scanLevel` 格为中心扫描 `(2*level+1)^3` 立方体，消耗 = 等级数 + 新扫描的可疑方块数（已扫描的不额外消耗），同时高亮战利品容器。
- **能量与充能**：耐久条显示能量（绿/黄/红三色）；能量不足时自动从背包消耗古代金币充能（`tryRecharge`）；shift+右键空气手动充能，带**浪费保护**——剩余空间不足一枚金币的充能值时先提示，20t 内双击强制充能。
- **主副手协作**：主手解析仪 + 副手考古铲时，点击已扫描的可疑方块会**让位给副手考古铲**取物（`useOn` 开头判定，客户端须返回 PASS 触发原版副手流程）。
- **追踪接入**：`scanBrushable` 用 `deferred` 结算策略接入考古笔记（见 [考古笔记系统](journal.md)）。

**考古铲**：右键已扫描的可疑方块直接取物，无需完整刷拭。直接挖掘可疑方块时，未潜行虽显示破坏裂纹但最终无法破坏、也不触发连挖，**必须按住 Shift**；挖掘普通铲类方块时向下连挖最多 3 格，避开可疑方块及其支撑方块；挖掘沙子/红沙/砂砾有 0.3% 概率找到古代金币。

## 7. 淘盘（PanItem + PanProfile）

[`PanItem`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/item/PanItem.java) 是三把盘的共同基类，对**本工具可采的**淘洗点长按右键淘洗；使用行为复用原版「正在使用物品」减速规则（不施加药水效果），客户端渲染入口负责专属摇洗动画。差异全部由 [`PanProfile`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/item/PanProfile.java) 提供，因此**新增淘盘不需要新类**：

| 淘盘 | 耐久 | 铁砧修复材料 | 可采变体 | 幸运加成（水域） | 再生概率 |
|---|---|---|---|---|---|
| 铜淘盘 `copper_pan` | 32 | `c:ingots/copper` | 水域 | 0 | 0 |
| 金淘盘 `gold_pan` | 24 | `c:ingots/gold` | 水域 | `+gold_pan_luck_bonus`（默认 1.0） | `gold_pan_regeneration_chance`（默认 0.10） |
| 黑曜石淘盘 `obsidian_pan` | 64 | `c:obsidians/normal` | 水域 + 幽微的光 | `-obsidian_pan_luck_penalty`（默认 0.5） | 0 |

三把盘经 `minecraft:enchantable/durability` 兼容耐久与经验修补。工具与点是**能力交集**判定（`PanProfile.harvestTargets` 与点的变体取交集），因此不支持的组合直接 `PASS` 让位给原版，不会出现"铜盘持续淘洗幽微的光"这类越界行为。淘洗结算、战利品表、变体系统与淘洗点实体详见 [淘洗系统](panning.md)。

## 8. 标本箱与便携容器机制

`inventory/` 包实现"容器在背包 → 容器内物品效果触发"的递归扫描：

- `PortableContainer`：物品实现此接口后，`InventoryPresenceRegistry` 会递归扫描容器内物品；短路匹配（找到即停止），无实现时无需分配空集合。
- `InventoryPresenceRegistry`：统一查询入口，`isPresent(player, item)` / `containsMatching` / `mutateFirst` 递归扫描主背包 + 饰品栏 + 便携容器内容。
- `InventoryPresenceTrigger` + `PlayerPresenceStateHolder`：定义哪些物品需要被检测，以及附加到玩家的 tick 驱动 diff 状态。
- 平台适配：两端的 `*InventoryPresenceAdapter` 做 tick 驱动 diff 检测，下线时清理状态。

[`SpecimenBoxItem`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/item/SpecimenBoxItem.java) 是 5 格便携容器：

- 右键打开 `SpecimenBoxMenu`；`getContents` 返回盒内非空物品流供递归扫描；`mutateFirst` 修改第一个匹配物品（如绑定猫之手）后写回 `CONTAINER` 组件。
- `getTooltipImage` 悬停预览盒内物品（`SpecimenBoxTooltip` → `ClientSpecimenBoxTooltip`）。
- 放背包/饰品栏时，箱内需要"随身携带"或"装备"才生效的物品（考古笔记、猫之瞳）仍正常工作——这是本机制要解决的核心问题。
- **箱内不死图腾代理**原版死亡保护：两端分别经 `ServerLivingEntityEvents.ALLOW_DEATH` / `LivingDeathEvent` 接入，共用 `SpecimenBoxTotemProxy` 消费箱内图腾并施加原版保护结果。
- 装备到饰品栏（Trinkets/Curios）后代理箱内兼容饰品的属性与效果（Artifacts 适配），见 [配置与第三方联动](../foundation/config-and-integrations.md)。

`SpecimenBoxContents` 封装盒内物品的读写（`read` / `writeIfChanged`）。

## 9. 结构回溯（回溯粉）

对世界生成结构的任意部件包围盒内方块右键，消费一份回溯粉（创造模式不消耗），由 `world/StructureRewindService` 在原位置逐区块重新放置整个结构。客户端仅预测交互，**实际识别与写入均在服务端执行**。未找到结构、布局读取失败、越过世界边界、处于冷却或已有任务时不消耗。

- **合成**：普通 3×3 工作台用 7 个回响碎片、1 个花火粉、1 瓶龙息。
- **双层冷却**：物品成功使用后保留原有 20t（约 1 秒）冷却；结构从任务成功启动起进入 48000t（服务器运行时间约 40 分钟）冷却，所有玩家与该结构的所有部件共用。`StructureRewindCooldownData` 以维度、结构类型和起始区块识别结构，并在维度 `SavedData` 中持久化截止游戏时间。任务启动后若中途失败，已消耗的粉末及结构冷却不返还。
- **识别与布局**：`getStructureWithPieceAt(pos, holder -> true)` 使用当前区块的结构引用识别所有注册结构，**包含第三方结构**；仅有 Feature 或玩家手工搭建的建筑没有 StructureStart，无法识别。使用持久化起点的 NBT 副本，保留坐标、朝向、高度缓存和布局，不修改原起点或引用计数。
- **随机序列**：每区块使用 `WorldgenRandom(XoroshiroRandomSource)`，先 `setDecorationSeed(worldSeed, chunkMinX, chunkMinZ)`，再按结构在相同 Decoration 阶段中的注册顺序调用 `setFeatureSeed`，最后调用 `placeInChunk`。写入范围复用原版区块水平范围与世界高度上下各留一格的规则。
- **状态重置**：对已核实的原版部件重置沙漠神殿宝箱、丛林神庙宝箱/陷阱、要塞走廊宝箱、要塞/下界要塞刷怪笼、矿井蜘蛛刷怪笼及沼泽小屋实体的一次性标记。第三方 NBT 保持原样。下界要塞转角的 `Chest` 同时表达随机布局选择与未生成状态，不能从持久化结果恢复最初选择，保留原值。
- **调度与表现**：两端已有的服务端 tick / stopped 事件驱动服务；每台服务器最多一个任务，每 tick 放置一个区块。区块重建时使用固定总量的 REVERSE_PORTAL / END_ROD 粒子，点击处有起止脉冲和音效；启动时对使用者施加 60t、隐藏图标和粒子的原版 Nausea 效果，借用客户端短暂的屏幕扭曲，并遵循其"扭曲效果"设置。区块按 X 后 Z 遍历；**任务仅驻留内存，停服取消**，异常中止并记录日志，已完成的写入不会回滚。
- **行为边界**：这是重新执行结构放置，**并非历史快照**。可能覆盖玩家改建、重新产生战利品及重复生成实体；不会清除整个包围盒，也不会恢复所有原始地形或装饰 Feature。部件包围盒外的地基不一定能触发识别。同类结构在一个区块重叠时，原版共享的随机数消耗序列无法单独精确重放；当前地形、已有容器及第三方处理器也会影响结果，不能保证宝箱全部刷新或逐方块一致。
- **性能与兼容**：不一次性加载全部目标区块，但单个大型部件的 `postProcess`、同步区块加载或第三方生成器仍可能导致 tick 卡顿。生成器须支持 ServerLevel 上的放置；原版 writableArea 是传给生成器的约束，不能阻止第三方实现越界写入。权限检查采用物品编辑权限及点击位置原版权限，**未接入第三方领地保护 API**。

## 10. 其他材料物品

| 物品 | 说明 |
|---|---|
| 古代金币 | 连接考古与猫国经济的通用稀有资源：解析仪充能、铁砧修复（每枚恢复 25% 最大耐久）、流浪商人交易（1 枚换 5 绿宝石）、猫猫商人回收 |
| 失落书页 | 稀有考古战利品，与基页 + 书在锻造台合成随机附魔书（见 [附魔系统](enchantment.md) 的「失落书页锻造」） |
| 基页 | 由瓶子草合成，失落书页锻造的中间材料 |
| 猫之手 | 猫族身份信物，`CUSTOM_DATA` 存 `owner_uuid` + `owner_name`；`bindTo` / `isBound` / `isBoundTo` / `getOwnerUuid` / `getOwnerName`；tooltip 五段式含羁绊值与能力清单（见 [猫族关系系统](cat-favor.md)） |
| 猫之瞳 | 放在背包/饰品栏/标本箱中即提供三项信息能力（见 [附魔系统](enchantment.md) 的「附魔揭示」与 [客户端与 GUI](client-ui.md)） |
| 花火粉 | 2 个火把花无序合成 4 个；燃料 800t（恰好烧炼 4 个物品）；1 花火粉 + 1 木棍 → 2 个火把。**刻意不加入 `minecraft:coals` 标签**——加入后会被原版火把配方识别为煤炭，产出变 4 个 |
| 考古笔记 | 右键或按 `C` 打开笔记 GUI；物品本身不持有数据，进度通过 mixin 附加在玩家 NBT（见 [客户端与 GUI](client-ui.md)） |

## 11. 配方序列化器

[`ModRecipeSerializers`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/recipe/ModRecipeSerializers.java) 注册自定义配方序列化器（沿用清单 + 回调模式）：

| 序列化器 | 用途 |
|---|---|
| `UnsuspiciousCreationRecipe` | 不可疑方块合成（封存物品到方块） |
| `UnsuspiciousSealingRecipe` | 封存配方（将物品封入不可疑方块） |
| `UnfiredDecoratedPotSmeltingRecipe` | 未烧制陶罐烧制为纹饰陶罐 |
| `UnfiredDecoratedSherdSmeltingRecipe` | 未烧制纹饰陶片烧制 |

**失落书页锻造不经此清单**：它由数据配方 `data/unsuspiciousblock/recipe/enchant_book_smithing.json` + `SmithingMenuMixin`（拦截 `SmithingMenu.onTake`）触发（见 [附魔系统](enchantment.md)）。

## 12. 扩展点：新增物品 / 方块 / 容器 / 配方

- **新增物品 / 方块**：按 [注册架构](../foundation/registration.md) 的「扩展点：新增注册内容」步骤，在 `ModItems` / `ModBlocks` 清单加一行。
- **新增便携容器**：物品实现 `PortableContainer` 接口，`InventoryPresenceRegistry` 自动递归扫描，无需改扫描逻辑。
- **新增封存方块**：参考 `UnsuspiciousBlock` + `SealedContents`，用 `CONTAINER` 组件存储封存物品。
- **新增配方**：实现 `RecipeSerializer`，在 `ModRecipeSerializers` 清单注册，并添加 `data/unsuspiciousblock/recipe/` JSON。
- **新增淘盘**：加一个 `PanProfile` + 在 `ModItems.PAN_ITEMS` 登记（见 [淘洗系统](panning.md) 的「新增淘盘」）。
- **解析仪能量调优**：修改 `MAX_ENERGY` / `ENERGY_PER_COIN` / `MAX_SCAN_LEVEL` 常量。

## 13. 约束与陷阱

- **花火粉不能进 `minecraft:coals` 标签**（见「其他材料物品」表）。
- **考古铲必须潜行才能挖掘可疑方块**；未潜行只显示裂纹，不破坏、不连挖，这是刻意行为。
- **淘盘能力交集必须三处共用**（起手 / 持续 / 结算），改 `canHarvest` 时不要只改一处。
- **标本箱的"箱内生效"依赖 `InventoryPresenceRegistry` 递归扫描**，新物品若要支持放进容器后生效，须加入 `InventoryPresenceTrigger` 的检测集合，且其判定要走 `isPresent` 而非直接查背包。
- **结构回溯是重新执行放置，不是历史快照**：会覆盖玩家改建、重复生成实体与战利品，任何"精确还原"的预期都不成立（见「结构回溯」的「行为边界」）。
- 结构回溯最多一个任务、每 tick 一个区块，且**仅驻留内存、停服取消**，已完成的写入不回滚。

## 14. 相关文档

- [注册架构](../foundation/registration.md) —— `ModItems` / `ModBlocks` 清单模式
- [考古笔记系统](journal.md) —— 解析仪的追踪接入
- [战利品表系统](loottable.md) —— 解析仪与考古铲涉及的战利品解析
- [淘洗系统](panning.md) —— 淘盘与淘洗点
- [猫族关系系统](cat-favor.md) —— 猫之手绑定
- [附魔系统](enchantment.md) —— 猫之瞳、失落书页锻造
- [客户端与 GUI](client-ui.md) —— 标本箱/陶轮 Screen、tooltip
- [配置与第三方联动](../foundation/config-and-integrations.md) —— 标本箱饰品代理、花火粉燃料接入
