# 附魔系统

> `enchantment/` 包的架构：四种附魔如何通过统一的效果框架注册与调度，失落书页如何在锻造台生成随机附魔书，猫之瞳如何揭示附魔台完整候选列表。
> 本文件是附魔子系统的唯一权威。Mixin 注入点清单见 [Mixin](../foundation/mixin.md)，平台适配机制见 [平台抽象](../foundation/platform-spi.md)。

## 1. 代码地图

| 层 | 位置 |
|---|---|
| 附魔数据 | `data/unsuspiciousblock/enchantment/*.json`（1.21 数据驱动附魔） |
| 附魔 Key | `enchantment/ModEnchantments.java`（4 个 `ResourceKey<Enchantment>`） |
| 效果框架 | `enchantment/framework/`：`EnchantmentManager`（调度器）、`EnchantmentEffects`（注册入口）、`effect/`（接口）、`trigger/`（触发类型）、`builtin/`（三个内建效果） |
| 平台适配 | `enchantment/framework/adapter/IEnchantmentEventAdapter.java`；实现 `FabricEnchantmentEventAdapter` / `NeoForgeEnchantmentEventAdapter` |
| 泥底打捞 | `loottable/condition/ToolEnchantmentCondition.java` + `loottable/graph/RuntimeLootLinks.java`（门槛声明） |
| 失落书页锻造 | `enchantment/EnchantedBookRoller.java`；配方 `data/unsuspiciousblock/recipe/enchant_book_smithing.json` |
| 附魔揭示 | `enchantment/reveal/`（`EnchantmentRevealManager` / `EnchantmentRevealConditions` / `IEnchantmentRevealCondition`） |
| 客户端 | `client/enchantment/EnchantmentRevealClientState.java`；渲染入口 `mixin/client/EnchantmentScreenMixin` |
| 网络 | `SyncEnchantmentRevealListPayload`（清单见 [网络与同步](../foundation/network.md)） |
| Mixin | `enchantment/EnchantmentMenuMixin`（揭示触发）、`container/SmithingMenuMixin`（锻造触发）、`client/EnchantmentScreenMixin`（候选渲染）；Fabric 刷拭/剪羊毛触发用 `interaction/BrushableBlockEntityMixin` / `interaction/SheepMixin` |
| 平台差异 | 触发事件由 `IEnchantmentEventAdapter` 两套实现接入；其余无分叉 |

## 2. 数据流

**效果框架（副作用与值变换）**

```
平台事件（方块破坏 / 剪羊毛 / 刷拭）
  └─ IEnchantmentEventAdapter 转成统一的 TriggerContext
       └─ EnchantmentManager.dispatch / dispatchValue(triggerType, ctx)
            ├─ 取该 TriggerType 下所有注册项
            ├─ lookupEnchantment(registryAccess, key) 查附魔 Holder
            ├─ findEnchantmentLevel(ctx, holder) 查工具上的等级（<= 0 跳过）
            └─ effect.apply(EffectContext) 执行
                 dispatchValue 为链式：前一个输出作为后一个输入
```

**附魔揭示（猫之瞳）**

```
EnchantmentMenu.slotsChanged  （由 EnchantmentMenuMixin 拦截）
  └─ EnchantmentRevealManager.shouldReveal(player, menu, target)
       └─ 遍历所有 IEnchantmentRevealCondition，任一为 true 即揭示
  └─ 应揭示时：服务端调用原版 getEnchantmentList 算完整候选
       └─ S2C SyncEnchantmentRevealListPayload 下发
            └─ 客户端 EnchantmentRevealClientState 渲染展示
```

**泥底打捞（条件注入，不走效果框架）**

```
钓鱼 loot roll（父表 minecraft:gameplay/fishing）
  └─ 入口门槛：由 RuntimeLootLinks.MUD_DREDGING_GATE 一处声明的条件
       （附魔身份 + 最低等级 + 概率曲线 0.2 + 0.1/级）
  └─ 命中后进入子表 gameplay/fishing/mud_dredging 抽取额外宝物
       （子表池不写任何条件，只描述"进来之后产出什么"）
```

## 3. 关键类

| 类 | 职责 |
|---|---|
| `enchantment/ModEnchantments` | 4 个附魔 `ResourceKey` 集中定义 |
| `enchantment/framework/EnchantmentManager` | 统一调度器，维护 `EFFECT_REGISTRY` 与 `VALUE_EFFECT_REGISTRY` 两个按 `TriggerType` 分组的注册表 |
| `enchantment/framework/EnchantmentEffects` | 内建效果的集中注册入口，由 `UnsuspiciousBlockCommon.init()` 调用 |
| `enchantment/framework/effect/EnchantmentEffect` | 副作用效果接口（`void apply`） |
| `enchantment/framework/effect/EnchantmentValueEffect<T>` | 值变换效果接口 |
| `enchantment/framework/effect/EffectContext<T>` | 效果上下文（triggerCtx + item + level + key + value） |
| `enchantment/framework/trigger/TriggerType` | 触发类型枚举（见下） |
| `enchantment/framework/trigger/TriggerContext` | 触发上下文（player + tool + blockState + pos + level） |
| `enchantment/framework/builtin/FossilHunterEffect` | 化石猎手（`BLOCK_BREAK` 副作用） |
| `enchantment/framework/builtin/TextileRecoveryEffect` | 织物采集（`ENTITY_SHEAR` 副作用） |
| `enchantment/framework/builtin/PrecisionExcavationEffect` | 精准发掘（`BRUSH_ITEM_DROP` 值变换） |
| `enchantment/EnchantedBookRoller` | 失落书页锻造的随机附魔生成器（仅服务端） |
| `enchantment/reveal/EnchantmentRevealManager` | 揭示条件注册表与查询 |
| `client/enchantment/EnchantmentRevealClientState` | 客户端揭示状态 |

## 4. 附魔定义（数据驱动）

[`ModEnchantments`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/ModEnchantments.java) 集中定义 4 个 `ResourceKey<Enchantment>`：

| Key | 附魔 | 适用物品 |
|---|---|---|
| `mud_dredging` | 泥底打捞 I-III | 钓鱼竿 |
| `textile_recovery` | 织物采集 | 剪刀 |
| `precision_excavation` | 精准发掘 I-III | 刷子 |
| `fossil_hunter` | 化石猎手 | 镐 |

附魔本身是 **1.21 数据驱动附魔**，定义在 `data/unsuspiciousblock/enchantment/*.json`，包含适用物品、稀有度、最大等级、冲突 tag 等。代码侧只持有 `ResourceKey`，通过 `RegistryAccess` 在运行时查找 `Holder<Enchantment>`。

## 5. 效果框架

[`TriggerType`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/framework/trigger/TriggerType.java) 定义三种触发：

| TriggerType | 场景 | 附魔 |
|---|---|---|
| `BLOCK_BREAK` | 玩家破坏方块 | 化石猎手（副作用） |
| `ENTITY_SHEAR` | 剪羊毛 | 织物采集（副作用） |
| `BRUSH_ITEM_DROP` | 刷拭可疑方块掉出物品 | 精准发掘（值变换） |

三种效果在 [`EnchantmentEffects.registerAll`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/framework/EnchantmentEffects.java) 中注册：

```java
EnchantmentManager.register(BLOCK_BREAK, FOSSIL_HUNTER, new FossilHunterEffect());
EnchantmentManager.register(ENTITY_SHEAR, TEXTILE_RECOVERY, new TextileRecoveryEffect());
EnchantmentManager.registerValueEffect(BRUSH_ITEM_DROP, PRECISION_EXCAVATION, new PrecisionExcavationEffect());
```

[`IEnchantmentEventAdapter`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/framework/adapter/IEnchantmentEventAdapter.java) 是 SPI 接口（见 [平台抽象](../foundation/platform-spi.md)），由两个平台的适配器实现，把平台事件转换为统一的 `TriggerContext` 后路由到 `EnchantmentManager`。

> **泥底打捞不通过此框架**，它在钓鱼 loot roll 时通过战利品条件注入实现（见「泥底打捞」一节）。

### 5.1 化石猎手（FossilHunterEffect）

[`FossilHunterEffect`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/framework/builtin/FossilHunterEffect.java) 在破坏骨块时触发，流程为：仅骨块触发 → `NaturalBoneBlockTracker.consumeNatural` 消费自然生成标记（无论是否发奖励都要消费）→ 排除创造模式 / 关闭方块掉落 / 非自然生成（玩家或机器放置的骨块不奖励）/ 50% 未命中 → `rollExtraLoot` 按维度选表（主世界 `overworld_bone_block` / 下界 `nether_bone_block`）、构建 `BLOCK` paramSet 的 `LootParams`、经 `LootTrackingContext.root(..., FOSSIL_HUNTER, ...)` 与 `LootTrackingEvents.submit(..., immediate())` 抽取并提交追踪。

> 关键点：化石猎手通过 `LootTrackingContext` + `LootTrackingEvents` 把额外掉落接入考古笔记的追踪流程，玩家用化石猎手获得的化石会自动解锁目录条目。详见 [考古笔记系统](journal.md) 与 [战利品表系统](loottable.md)。

### 5.2 织物采集与精准发掘

- `TextileRecoveryEffect`：剪羊毛时额外掉落 1-3 根线（副作用）。
- `PrecisionExcavationEffect`：刷拭可疑方块时按等级（28%/44%/60%）使战利品翻倍（值变换，变换 drops 列表）。

## 6. 泥底打捞（条件注入）

泥底打捞**不通过效果框架**，而是通过自定义战利品条件在钓鱼 loot roll 时注入实现——这是它与其他三种附魔的根本差异。条件类型定义（[`ToolEnchantmentCondition`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/ToolEnchantmentCondition.java)）、门槛声明位置与"条件挂在注入处、不写在子表"的完整机制以 [战利品表系统](loottable.md) 的「自定义战利品条件」一节为权威；本节只记附魔侧要点：

- 门槛 `RuntimeLootLinks.MUD_DREDGING_GATE`（附魔 + 最低等级缺省 1 + 概率曲线 `0.2 + 0.1/级`）由 Fabric 的 `FishingLootInjection` 与 NeoForge 的 `FishingLootModifier` 共用，GLM 数据只留 `loot_table_id` 过滤，玩法与 tooltip 因此不可能各说一套。
- `injectionGateEnchantments` 记在**发起注入的表**上，每表哈希与附魔等级旋钮清单据此并入该附魔，保住"改 `max_level` 会失效"。
- 开放水域 / 沼泽群系分支由被注入子表 `gameplay/fishing/mud_dredging` 自身的 `entity_properties`(fishing_hook) 与 `location_check`(`#c:is_swamp`) 处理——它们是**条目级**门槛，按既有规则显示「需要条件」。

条件类型注册有时序约束（必须在 `init` 前完成），见 [架构总览](../foundation/architecture.md) 的「服务端初始化」与 [战利品表系统](loottable.md) 的「自定义战利品条件」。

## 7. 失落书页锻造

[`EnchantedBookRoller`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/EnchantedBookRoller.java) 实现锻造台随机附魔书生成，仅服务端调用：

```
roll(book, random, registries)
  ├─ 20% 概率：单一本模组附魔
  │     随机选一种模组附魔，等级 1~maxLevel
  ├─ 30% 概率：模组附魔 + 原版随机附魔
  │     先一种模组附魔，再叠加 10-20 经验等级的原版附魔
  └─ 50% 概率：纯原版随机附魔
        15-35 经验等级的原版附魔
```

原版附魔从 `EnchantmentTags.IN_ENCHANTING_TABLE` tag 池抽取，用普通书作探针调 `EnchantmentHelper.selectEnchantment`（原版对 BOOK 特判，接受所有附魔台可用附魔）。结果写入 `STORED_ENCHANTMENTS` 组件。

触发链路：数据配方 `data/unsuspiciousblock/recipe/enchant_book_smithing.json`（产出带 `unsuspiciousblock_pending_enchant` 标记的附魔书）+ `SmithingMenuMixin`（拦截 `SmithingMenu.onTake` 调用 `EnchantedBookRoller.roll`）。**不经 `ModRecipeSerializers`**。玩家在锻造台放入基页、书、失落书页并取出结果时触发。

## 8. 附魔揭示（猫之瞳）

- **服务端权威**：完整候选列表由服务端计算，客户端仅展示，不复刻算法。这避免客户端 `registryAccess` 与服务端不一致导致的列表差异。
- **内建条件**：[`EnchantmentRevealConditions.register`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/reveal/EnchantmentRevealConditions.java) 注册"持有猫之瞳即揭示"条件，通过 `InventoryPresenceRegistry.isPresent` 检查（含便携容器）。由 `UnsuspiciousBlockCommon.init()` 调用注册。
- **条件可扩展**：`EnchantmentRevealManager.registerCondition` 允许注册更多揭示条件。

> 同样的"猫之瞳"还驱动铁砧成本分解与砂轮预览，见 [客户端与 GUI](client-ui.md) 的「铁砧 / 砂轮成本分解」一节。

## 9. 扩展点：新增附魔 / 附魔效果

**新增一个附魔**

1. 在 `data/unsuspiciousblock/enchantment/` 定义附魔 JSON（适用物品、稀有度、最大等级、冲突 tag）。
2. 在 `ModEnchantments` 加 `ResourceKey`。
3. 语言文件补附魔名与描述，`en_us.json` 与 `zh_cn.json` 同步（见 [文本格式规范](../foundation/text-format.md)）。

**新增附魔效果**

1. 实现 `EnchantmentEffect`（副作用）或 `EnchantmentValueEffect<T>`（值变换）。
2. 在 `EnchantmentEffects.registerAll` 注册到对应 `TriggerType`。
3. 需要**新触发类型**时：在 `TriggerType` 加枚举，并在两个平台的 `IEnchantmentEventAdapter` 实现里接入对应事件。

**新增揭示条件**：实现 `IEnchantmentRevealCondition`，在合适时机调 `EnchantmentRevealManager.registerCondition`。

**调整锻造分布**：修改 `EnchantedBookRoller.roll` 的概率分支。

## 10. 约束与陷阱

- 附魔定义在数据包，代码侧只持 `ResourceKey`；**不要在代码里硬编码附魔属性**。
- 新触发类型必须**两个平台都接**，否则一端该附魔完全无效。
- 钓鱼（泥底打捞）**不走效果框架**，改动它请看「泥底打捞」一节，不要往 `EnchantmentManager` 里塞逻辑。
- 附魔揭示的候选列表**只能由服务端算**，客户端推导会与随机结果不一致。
- 附魔的等级上限改动会让既有的模拟缓存/命中率声明失效，这是刻意保留的行为（见「泥底打捞」）。

## 11. 相关文档

- [战利品表系统](loottable.md) —— `ToolEnchantmentCondition` 与条件注册
- [考古笔记系统](journal.md) —— `FossilHunterEffect` 的追踪接入
- [实体与 AI](entities-world.md) —— `NaturalBoneBlockTracker` 骨块追踪
- [网络与同步](../foundation/network.md) —— `SyncEnchantmentRevealListPayload`
- [客户端与 GUI](client-ui.md) —— 附魔台揭示渲染、铁砧/砂轮分解
- [Mixin](../foundation/mixin.md) —— `EnchantmentMenuMixin` 等注入点
