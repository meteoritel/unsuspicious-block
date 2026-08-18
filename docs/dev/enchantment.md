# 附魔系统

本文档描述 `enchantment/` 包的架构：模组四种附魔如何通过统一的效果框架注册与调度，失落书页如何在锻造台生成随机附魔书，以及猫之瞳如何揭示附魔台完整候选列表。

## 1. 职责概述

附魔系统提供四项内容：

1. **四种附魔**：泥底打捞（钓鱼竿）、织物采集（剪刀）、精准发掘（刷子）、化石猎手（镐），均为 1.21 数据驱动附魔。
2. **效果框架**：统一的 `EnchantmentManager` 调度器，按触发类型分发副作用效果与值变换效果。
3. **失落书页锻造**：基页 + 书 + 失落书页在锻造台生成随机附魔书（本模组附魔 / 原版附魔 / 两者组合）。
4. **附魔揭示**：持有猫之瞳时，附魔台显示完整附魔候选列表（而非原版的一条提示）。

## 2. 子包结构

```
enchantment/
├── ModEnchantments               4 个附魔 ResourceKey 集中定义
├── EnchantedBookRoller           失落书页锻造的随机附魔生成器
├── framework/                    效果调度框架
│   ├── EnchantmentManager        统一调度器（dispatch / dispatchValue）
│   ├── EnchantmentEffects        效果集中注册入口
│   ├── adapter/
│   │   └── IEnchantmentEventAdapter  平台事件适配 SPI
│   ├── effect/
│   │   ├── EnchantmentEffect     副作用效果接口（void apply）
│   │   ├── EnchantmentValueEffect<T>  值变换效果接口
│   │   └── EffectContext<T>      效果上下文（triggerCtx + item + level + key + value）
│   ├── trigger/
│   │   ├── TriggerType           触发类型枚举
│   │   └── TriggerContext        触发上下文（player + tool + blockState + pos + level）
│   └── builtin/                  内建效果实现
│       ├── FossilHunterEffect    化石猎手（BLOCK_BREAK 副作用）
│       ├── TextileRecoveryEffect 织物采集（ENTITY_SHEAR 副作用）
│       └── PrecisionExcavationEffect  精准发掘（BRUSH_ITEM_DROP 值变换）
└── reveal/                       附魔揭示
    ├── EnchantmentRevealManager  揭示条件注册表与查询
    ├── EnchantmentRevealConditions  内建条件注册（持有猫之瞳）
    └── IEnchantmentRevealCondition  揭示条件接口

client/enchantment/
└── EnchantmentRevealClientState  客户端揭示状态
```

## 3. 附魔定义（数据驱动）

[`ModEnchantments`](../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/ModEnchantments.java) 集中定义 4 个 `ResourceKey<Enchantment>`：

| Key | 附魔 | 适用物品 |
|---|---|---|
| `mud_dredging` | 泥底打捞 I-III | 钓鱼竿 |
| `textile_recovery` | 织物采集 | 剪刀 |
| `precision_excavation` | 精准发掘 I-III | 刷子 |
| `fossil_hunter` | 化石猎手 | 镐 |

附魔本身是 **1.21 数据驱动附魔**，定义在 `data/unsuspiciousblock/enchantment/*.json`，包含适用物品、稀有度、最大等级、冲突 tag 等。代码侧只持有 `ResourceKey`，通过 `RegistryAccess` 在运行时查找 `Holder<Enchantment>`。

## 4. 效果框架

### 4.1 调度器

[`EnchantmentManager`](../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/framework/EnchantmentManager.java) 是统一调度器，维护两个按 `TriggerType` 分组的注册表：

- `EFFECT_REGISTRY`：副作用效果（`EnchantmentEffect`，无返回值）
- `VALUE_EFFECT_REGISTRY`：值变换效果（`EnchantmentValueEffect<T>`，返回变换后的值）

调度流程：

```
dispatch(triggerType, ctx)               // 副作用
  ├─ 取该 TriggerType 下所有 EffectEntry
  └─ 对每个 entry：
       ├─ lookupEnchantment(registryAccess, key)  查附魔 Holder
       ├─ findEnchantmentLevel(ctx, holder)       查工具上的附魔等级（level <= 0 跳过）
       └─ effect.apply(EffectContext)             执行效果

dispatchValue(triggerType, ctx, originalValue)   // 值变换（链式）
  ├─ 取该 TriggerType 下所有 ValueEffectEntry
  └─ 对每个 entry（链式应用，前一个输出作为后一个输入）：
       ├─ 查附魔等级（<= 0 跳过）
       └─ currentValue = effect.apply(EffectContext with currentValue)
```

### 4.2 触发类型

[`TriggerType`](../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/framework/trigger/TriggerType.java) 定义三种触发：

| TriggerType | 场景 | 附魔 |
|---|---|---|
| `BLOCK_BREAK` | 玩家破坏方块 | 化石猎手（副作用） |
| `ENTITY_SHEAR` | 剪羊毛 | 织物采集（副作用） |
| `BRUSH_ITEM_DROP` | 刷拭可疑方块掉出物品 | 精准发掘（值变换） |

### 4.3 平台适配

[`IEnchantmentEventAdapter`](../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/framework/adapter/IEnchantmentEventAdapter.java) 是 SPI 接口（见 [平台抽象](platform-abstraction.md)），由 `FabricEnchantmentEventAdapter` / `NeoForgeEnchantmentEventAdapter` 实现，将平台特定事件（方块破坏、剪羊毛、刷拭）转换为统一的 `TriggerContext`，路由到 `EnchantmentManager.dispatch` / `dispatchValue`。

> 泥底打捞不通过此框架，因为它是在钓鱼 loot roll 时通过战利品条件注入实现的（见第 6 节）。

## 5. 内建效果实现

三种效果在 [`EnchantmentEffects.registerAll`](../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/framework/EnchantmentEffects.java) 中注册，由 `UnsuspiciousBlockCommon.init()` 调用：

```java
EnchantmentManager.register(BLOCK_BREAK, FOSSIL_HUNTER, new FossilHunterEffect());
EnchantmentManager.register(ENTITY_SHEAR, TEXTILE_RECOVERY, new TextileRecoveryEffect());
EnchantmentManager.registerValueEffect(BRUSH_ITEM_DROP, PRECISION_EXCAVATION, new PrecisionExcavationEffect());
```

### 5.1 化石猎手（FossilHunterEffect）

[`FossilHunterEffect`](../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/framework/builtin/FossilHunterEffect.java) 在破坏骨块时触发，是附魔效果与战利品追踪系统交互的典型示例：

```
apply(ctx)
  ├─ 仅骨块触发（state.is(BONE_BLOCK)）
  ├─ NaturalBoneBlockTracker.consumeNatural(level, pos)  消费自然生成标记
  │     无论是否发奖励，被破坏的自然骨块都不应保留标记
  ├─ 创造模式 / 关闭方块掉落 -> 不触发
  ├─ 非自然生成 -> 不触发（玩家/机器放置的骨块不奖励）
  ├─ 50% 概率未命中 -> 不触发
  └─ rollExtraLoot:
       ├─ 按维度选表（主世界 overworld_bone_block / 下界 nether_bone_block）
       ├─ 构建 LootParams（BLOCK paramSet）
       ├─ LootTrackingContext.root(..., FOSSIL_HUNTER, ...)  建立追踪上下文
       ├─ LootTrackingContextHolder.open(scope)  开启会话
       ├─ lootTable.getRandomItems  抽取
       └─ LootTrackingEvents.submit(session, generated, immediate())  提交追踪
            接入考古笔记系统：解锁化石采集分类条目、记录日志
```

> 关键点：化石猎手通过 `LootTrackingContext` + `LootTrackingEvents` 把额外掉落接入考古笔记的追踪流程，玩家用化石猎手获得的化石会自动解锁目录条目。详见 [考古笔记系统](journal.md) 与 [战利品表系统](loottable.md)。

### 5.2 织物采集与精准发掘

- `TextileRecoveryEffect`：剪羊毛时额外掉落 1-3 根线（副作用）。
- `PrecisionExcavationEffect`：刷拭可疑方块时按等级（28%/44%/60%）使战利品翻倍（值变换，变换 drops 列表）。

## 6. 泥底打捞（条件注入）

泥底打捞不通过效果框架，而是通过**自定义战利品条件**实现：

- [`MudDredgingCondition`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/MudDredgingCondition.java) 是附魔资格 `LootItemCondition`，只检查钓鱼竿是否具有泥底打捞；开放水域/沼泽群系分支与统一触发概率由父 loot table 的 `entity_properties`(fishing_hook)、`location_check`(`#c:is_swamp`) 和 `random_chance_with_tool_enchantment` 处理。
- 通过 `mud_dredging` 战利品池注入原版钓鱼表（Fabric 用 `FishingLootInjection`，NeoForge 用 `FishingLootModifier` GLM）。
- 命中时从 `gameplay/fishing/mud_dredging` 战利品表抽取额外宝物。

条件类型注册有时序约束（必须在 `init` 前完成），见 [战利品表系统](loottable.md) 第 9 节与 [架构总览](architecture-overview.md) 第 4 节。

## 7. 失落书页锻造

[`EnchantedBookRoller`](../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/EnchantedBookRoller.java) 实现锻造台随机附魔书生成，仅服务端调用：

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

锻造触发由数据配方 `data/unsuspiciousblock/recipe/enchant_book_smithing.json`（产出带 `unsuspiciousblock_pending_enchant` 标记的附魔书）+ `SmithingMenuMixin`（拦截 `SmithingMenu.onTake` 调用 `EnchantedBookRoller.roll`）实现，不经 `ModRecipeSerializers`。玩家在锻造台放入基页、书、失落书页并取出结果时触发。

## 8. 附魔揭示（猫之瞳）

### 8.1 揭示机制

持有猫之瞳时，附魔台显示完整附魔候选列表（而非原版的一条提示）。机制见 [`EnchantmentRevealManager`](../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/reveal/EnchantmentRevealManager.java)：

```
EnchantmentMenu.slotsChanged  (由 EnchantmentMenuMixin 拦截)
  └─ EnchantmentRevealManager.shouldReveal(player, menu, target)
       ├─ 遍历所有 IEnchantmentRevealCondition
       └─ 任一返回 true 即揭示
  └─ 若应揭示：服务端调用原版 getEnchantmentList 计算完整候选
       └─ S2C SyncEnchantmentRevealListPayload 下发客户端
  └─ 客户端 EnchantmentRevealClientState 持有列表，渲染时展示
```

### 8.2 关键设计

- **服务端权威**：完整候选列表由服务端计算，客户端仅展示，不复刻算法。这避免客户端 `registryAccess` 与服务端不一致导致的列表差异。
- **内建条件**：[`EnchantmentRevealConditions.register`](../../common/src/main/java/com/meteorite/unsuspiciousblock/enchantment/reveal/EnchantmentRevealConditions.java) 注册"持有猫之瞳即揭示"条件，通过 `InventoryPresenceRegistry.isPresent` 检查（含便携容器）。
- **条件可扩展**：`EnchantmentRevealManager.registerCondition` 允许注册更多揭示条件。
- 由 `UnsuspiciousBlockCommon.init()` 调用注册。

> 同样的"猫之瞳"还驱动铁砧成本分解与砂轮预览，见 [客户端与 GUI](client-ui.md) 的 `client/anvil/` 与 `client/grindstone/`。

## 9. Mixin 依赖

- `EnchantmentMenuMixin`（common）：拦截 `slotsChanged`，触发附魔揭示检查。
- `EnchantmentScreenMixin`（common client）：渲染完整候选列表。
- `SmithingMenuMixin`（common）：拦截 `SmithingMenu.onTake`，触发失落书页锻造。
- 刷拭/剪羊毛的触发由平台适配器通过事件或 mixin 接入（Fabric 部分用 mixin，如 `SheepMixin`、`BrushableBlockEntityMixin`）。钓鱼不再走附魔框架（见第 6 节）；`FishingHookMixin` 现属于考古笔记的钓鱼追踪上下文（见 [考古笔记系统](journal.md)）。

## 10. 扩展点

- **新增附魔效果**：
  1. 在 `data/unsuspiciousblock/enchantment/` 定义附魔 JSON。
  2. 在 `ModEnchantments` 加 `ResourceKey`。
  3. 若需要副作用/值变换：实现 `EnchantmentEffect` 或 `EnchantmentValueEffect`，在 `EnchantmentEffects.registerAll` 注册。
  4. 若需要新触发类型：在 `TriggerType` 加枚举，平台适配器中接入对应事件。
- **新增揭示条件**：实现 `IEnchantmentRevealCondition`，在合适时机调 `EnchantmentRevealManager.registerCondition`。
- **新增锻造分布**：修改 `EnchantedBookRoller.roll` 的概率分支。

## 11. 相关文档

- [战利品表系统](loottable.md) - `MudDredgingCondition` 与条件注册
- [考古笔记系统](journal.md) - `FossilHunterEffect` 的追踪接入
- [实体与世界生成](entities-world.md) - `NaturalBoneBlockTracker` 骨块追踪
- [网络与同步](network.md) - `SyncEnchantmentRevealListPayload`
- [客户端与 GUI](client-ui.md) - 附魔台揭示渲染、铁砧/砂轮分解
- [Mixin 总览](mixin.md) - `EnchantmentMenuMixin` 等
