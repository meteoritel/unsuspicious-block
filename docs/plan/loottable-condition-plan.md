# 战利品条件解析与展示补全规划

> 状态：**已实施，待实机验收**（2026-09-18）。批次①（概率条件不再说谎 + 解析保真度）与批次②（模组自定义条件合并泛化）已同批完成并通过构建与产物检查，实施结果与偏离见第九节。
>
> 当前机制的唯一权威描述是 [战利品表系统](../dev/loottable.md)；本文记录改造背景、核实依据与决策记录，机制描述已在实施完成后同步回该文档与 [文本格式规范](../dev/tooltip.md)。
>
> 修订记录：初版按 [开发者文档约定](../dev/README.md)《计划文档统一结构》编写，含批次① 全部定案内容；第二版并入批次② 的完整设计；第三版补入 Fabric 回调签名等源码核实结果；第四版根据事实审查收紧迁移范围：旧条件格式仅由本模组使用，新版本随内置资源原子迁移，不保留旧注册名或 `swamp` 字段兼容，并修正验收口径与概率文案；第五版追加实施结果（§九）。

## 一、现状

> 本节记录的是**实施前**状态；行号以写作时的工作区为准，会随实施失效。

### 1.1 条件描述链路（批次① 相关）

战利品条件的静态分析只做一件事：把运行时条件对象转译成"玩家能读的一句话"。它与条件求值本身无关。

| 关注点 | 归属 | 职责 |
|---|---|---|
| 条件处理器注册表 | [`LootConditionHandlers`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootConditionHandlers.java) `:62` | `Map<ResourceLocation, LootConditionHandler>`，内建全部 19 种原版条件（`:67-95`），键为条件的注册表 key |
| 自定义条件处理器 | [`ModLootConditions.registerAnalysisHandlers`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/ModLootConditions.java) `:54-62` | 用 `DescriptionHandler`（`:65-84`）为 `mud_dredging` 与 `random_chance_with_tool_enchantment` 注册纯文案处理器 |
| 分析结果类型 | [`LootConditionInfo`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootConditionInfo.java) `:19-25` | record：`conditionType` / `description` / `probability` / `children` / `metadata` |
| 批量入口 | `LootConditionHandlers.analyzeAll` `:177-201` | 对每个已由游戏 Codec 解码的条件查分析 handler 并调 `analyze`；未登记 handler 或 handler 抛异常时回落到 `fallbackInfo`（`:228-235`） |
| 描述文本的生产 | 服务端编译期 | `LootParseUtil:74-76` 调用 `analyzeAll`；条件在 `LootTableCompiler:158` 解析、`:126-133`（pool）/`:167-174`（组合 entry）/`:183-188`（递归子级）累积为 `inheritedConditions`，链接期由 `LootTableProjector:169`、`:288-289` 重新合并 |
| 描述文本的传输 | `SyncArchaeologyCatalogPayload` `:253-302` | 递归编码 `description`（Component）、`children` 与 `metadata`（`:264-265`） |
| 描述文本的渲染 | [`JournalTooltipBuilder`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/JournalTooltipBuilder.java) | `appendConditionTree` `:261-276` 用 `└─`/`├─` 递归画树；`getConditionColor` `:279-287` 按条件类型反查 handler 的 `uncertaintyLevel` 上色 |
| 不确定性的消费 | `LootTableCatalog.ItemDefinition.uncertaintyLevel()` | `computeUncertaintyLevel`（`:142-155`）递归取最差子项，驱动网格与 tooltip 的概率文案与颜色 |
| 机器可读元数据 | `LootConditionHandlers.withSimulationMetadata` `:204-210` | 写入 `simulation_fingerprint` 与 `simulation_fingerprint_stable`，由 `SimulationScenarioPlanner` 消费 |

结构性的关键点：**条件的展示文本在服务端编译期生成，客户端只做渲染**。客户端从不解析原始 JSON，也不自行判断条件语义。

### 1.2 模组自定义条件（批次② 相关）

两个自定义条件都定义在 `loottable/condition/` 下，形态差异很大：

| 条件 | 记录组成 | `test()` 语义 | 备注 |
|---|---|---|---|
| [`MudDredgingCondition`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/MudDredgingCondition.java) `:24` | `(boolean swamp)` | 工具带有 `ModEnchantments.MUD_DREDGING` 且等级 > 0（`:32-41`） | `swamp` 字段自带注释说明"仅为旧数据包兼容保留，不再参与判断"；该类同时承载 `ResourceKey<LootTable> MUD_DREDGING`（`:28-29`）；实现了 `LootItemCondition.Builder`（`:24`） |
| [`ToolEnchantmentChanceCondition`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/ToolEnchantmentChanceCondition.java) `:24` | `(Holder<Enchantment> enchantment, LevelBasedValue chance)` | `level > 0 && random < chance.calculate(level)`（`:35-43`） | 未实现 `LootItemCondition.Builder` |

周边归属：

- codec 与类型持有者：`ModLootConditions` `:23-25`、`:27-28`、`:34-51`；平台注册在 Fabric `UnsuspiciousBlockFabric.java:97-107`、NeoForge `UnsuspiciousBlockNeoForge.java:129-133`、`:252-253`。
- 条件 id 常量：`RuntimeLootLinks.MUD_DREDGING_CONDITION`（`:29-30`）；表 id 常量：`RuntimeLootLinks.MUD_DREDGING_TABLE`（`:25-26`）。注入关系由**表 id** 判定（`RuntimeLootLinks.syntheticEdges:43`）。
- 平台注入：Fabric 通过 `LootTableEvents.MODIFY` 向钓鱼表追加一个受条件门控的 pool（`fabric/.../loot/FishingLootInjection.java:28-37`），其回调第 4 个参数 `registries` 目前**未被使用**；NeoForge 走 GLM，条件写在 `neoforge/src/main/resources/data/unsuspiciousblock/loot_modifiers/mud_dredging_fishing.json:9`。
- 场景规划：`SCENARIO_CONDITIONS` 含条件 id（`SimulationScenarioPlanner.java:44-53`）；表级固定 III 级工具（`:34`、`:118-133`、`:136-160`）；假定说明行的构造在 `:162-166`；工具构造在 `:168-174`。
- 资源：`common/src/main/resources/data/unsuspiciousblock/loot_table/gameplay/fishing/mud_dredging.json` 的同一个 pool 上**并列**两条条件（`:8` 的 `mud_dredging` 与 `:11-12` 的 `random_chance_with_tool_enchantment`）。
- lang：`condition.mud_dredging`、`condition.tool_enchantment_chance`、`mud_dredging_level`、`mud_dredging_bonus_biome`、`mud_dredging_normal_biome`。

### 1.3 需要改造的耦合点

#### (a) 概率条件会把动态值显示成 100%

`RandomChanceHandler`（`:362-385`）只识别 `ConstantValue`，其余 `NumberProvider` 一律保留 `chance` 的初始值 `1.0f`：

```java
float chance = 1.0f;
if (condition instanceof LootItemRandomChanceCondition(NumberProvider chance1)) {
    if (chance1 instanceof ConstantValue(float value)) {
        chance = value;
    }
}
```

于是 `uniform` / `binomial` / `score` 会显示成"随机概率: 100%"，同时把伪造的 `1.0f` 写进 `LootConditionInfo.probability`。这与项目已经确立的"宁可承认没算到，也不装作算到了"（[`Probability`](../dev/loottable.md) 三态值类型）直接冲突。

#### (b) 展示已知有损，但玩家无从识别

以下位置都只展示约束的**存在**、不展示其内容，而界面上没有任何标记说明"这里没说全"（源码核实）：

| 位置 | 行 | 丢失了什么 |
|---|---|---|
| `simpleDesc` 工厂注册的三类 | `:77-80`、`:318-337` | `value_check` / `table_bonus` / `enchantment_active_check` 的参数完全不展示 |
| `RandomChanceWithEnchantedBonusHandler` | `:388-410` | 只展示 `unenchantedChance()`，附魔加成从未展示 |
| `LocationCheckHandler` 的存在性子行 | `:525-546` | `position` / `light` / `block` / `fluid` 四行只有"需要满足…" |
| `EntityPropertiesHandler.genericInfo` | `:696-699` | 除 `entityType` 与 `fishing_hook` 外，整个实体谓词塌成一句 |
| `MatchToolHandler` | `:430-459` | 只看 `items`，`count` / `durability` / `enchantments` / 组件谓词均不展示 |
| `DamageSourcePropertiesHandler` 的嵌套行 | `:760-767` | `directEntity` / `sourceEntity` 只有存在性 |
| `describeRange` 的 JSON 回落 | `:255-288` | 动态 `IntRange` 回落为紧凑 JSON，`TimeCheckHandler:591-615` 与 `EntityScoresHandler:713-738` 共用 |
| `ReferenceHandler` | `:875-896` | 给出被引用的条件 id，但不解析其内容（条件的注册表就在手边） |

#### (c) 未识别与解析失败在界面上不可分

`analyzeAll` 的两条回落路径——条件类型已在游戏注册表中存在、但没有分析 handler（`:195-198`），以及 handler 抛异常/返回 null（`:186-194`）——都产出同一个 `fallbackInfo`，文案都是 `condition.unknown`（"未知条件：<id>"），玩家分不出"这个条件没有解析器"与"解析器本次失败"。完全未注册的 JSON 条件会在 Codec 阶段使整张表解析失败，不会进入这里。

#### (d) 渲染器没有长度约束

`appendConditionTree`（`:261-276`）递归展开整棵条件树，**没有折叠、没有行数上限**。这意味着"多解析一点"与"别把 tooltip 撑爆"是一对直接冲突，是任何细节补充都必须先解决的约束。

#### (e) 两个自定义条件语义重叠且拆成两条

合并前需要为一个池同时写两条条件才能表达"需要某附魔"与"按该附魔等级掷概率"。在 mod 自己的数据里这两条条件位于同一个 pool 的 `conditions` 数组，因此**每件物品的 tooltip 上必然相邻出现**（数据包核实），而两条的文案都读不出各自的角色（一条是资格门槛、一条是概率掷骰）。

`MudDredgingCondition` 还额外承载了三件与"条件"无关的东西：一个被忽略的 legacy 字段、一个 `ResourceKey<LootTable>`、以及一个仅为 `LootTableEvents.MODIFY` 注入服务的 `Builder` 实现。

#### (f) 按附魔写死

`SimulationScenarioPlanner` 硬编码 `MUD_DREDGING_SIMULATION_LEVEL = 3`（`:34`）；分析 handler 的文案键也是按具体条件写死的（`ModLootConditions:58-61`）。接入第二个附魔就需要改代码。

#### (g) 死代码

- `hasAnyUncertainty`（`:217-225`）：全仓无调用者（源码核实，语义已被 `computeUncertaintyLevel` 覆盖）。
- `I18N_PREFIX`（`:63`）与 `I18N_FALLBACK_PREFIX`（`:64`）：两个常量逐字节相同。
- 6 个 lang 键无任何代码引用：`condition.and` / `condition.or` / `condition.block_state_property` / `condition.time_check` / `condition.entity_properties` / `condition.entity_scores`。

### 1.4 现存缺陷

| 编号 | 缺陷 | 性质 | 归属 |
|---|---|---|---|
| C1 | 动态 NumberProvider 被展示成 100%，并污染 `LootConditionInfo.probability` | 静默假信息 | 批次① |
| C2 | 已注册类型缺少分析 handler 与 handler 解析失败的文本相同、颜色不同，玩家无法判断"这条没被读懂" | 可信度不可辨识 | 批次① |
| C3 | 已知有损的展示无任何标记（§1.3(b) 全表） | 可信度不可辨识 | 批次① |
| C4 | 死代码与死 lang 键，干扰后续判断"某键是没用上还是漏接" | 可维护性 | 批次① |
| C5 | 表达一个"工具附魔门槛 + 按等级概率"需要并列两条条件，且两条文案读不出角色差异 | 表达力 / 玩家体验 | 批次② |
| C6 | 条件类型按具体附魔写死：类名、注册名、文案键、模拟等级常量都绑定在泥地打捞上 | 扩展性 | 批次② |
| C7 | `MudDredgingCondition` 一个类混装条件 codec、表 `ResourceKey` 与注入用 Builder | 职责混装 | 批次② |

## 二、目的

### 2.1 目标能力

**批次①**

1. **概率条件不再说谎**：`random_chance` 在拿不到常量概率时，绝不展示编造的数值。
2. **解析保真度可被玩家看见**：每条条件都能表达"完整 / 有保留 / 未读到"三态之一，且不靠加长文本来表达。
3. **分析层未识别的条件是诚实的**：对已成功解码、但没有可用分析结果的条件保留其 id，不再假装读懂；完全未注册的 JSON 条件仍由游戏 Codec 拒绝。
4. **清理死代码**，为后续可能的内容补全留出干净的判断基线。

**批次②**

5. **一个条件表达完整语义**："需要工具附魔 + 可选等级门槛 + 可选按等级变化的概率"，不再是两条并列条件。
6. **按附魔解耦**：文案随条件里的附魔 `Holder` 变化，注册名与类名不再绑定泥地打捞，接入第二个附魔不需要改代码。
7. **模拟口径统一为满级**：该条件的概率模拟一律取该附魔的最大等级，避免附魔等级与环境条件形成笛卡尔积。
8. **一次完成内置资源迁移**：新版本只注册规范名，并与本模组两处旧格式 JSON 原子更新；旧格式仅由本模组使用，不承担跨版本兼容。

### 2.2 架构目标

- 保真度语义**由服务端决定**，客户端只做样式映射，不允许客户端自行推断条件语义（保持"解析只有一份实现"的既有不变量）。
- 不改变 `LootConditionInfo` 的 record 结构，避免牵动网络 codec、目录哈希与条件指纹三条下游。
- 展示文本与不确定性等级两个既有机制保持不动，保真度作为**正交的第三维**叠加，不与之争夺颜色。
- 批次② 保持"条件只描述自己、注入关系由表 id 判定"的既有分工，不把条件语义扩散成注入依据。

### 2.3 明确不做（本轮范围外）

| 项 | 理由 |
|---|---|
| 谓词字段深度补全（`location_check` 的 position/light/block/fluid 取值、`entity_properties` 的 flags/equipment/effects/distance、`match_tool` 的 count/durability/enchantments） | 当前收录范围内真实用到的谓词字段只有 `location_check.predicate.biomes` 与 `entity_properties` 的 `fishing_hook.in_open_water`，两者都已解析；补其余字段是为整合包服务的广度工作，与"修说谎"目标不同源 |
| `reference` 递归解析被引用条件的内容 | 同上，价值面向整合包作者；且它与条件注册表的取用时机相关，应按需单开 |
| 为 19 类型补齐低频项（`value_check` / `table_bonus` / `enchantment_active_check` 的 provider 与附魔取值） | 同上 |
| 重写存量啰嗦文案（如"需要满足实体状态"） | 改动面大且会淹没本轮真实验收点；等谓词字段深度工作一并处理 |
| 把合并后的条件纳入代表场景控制（`SCENARIO_CONDITIONS`） | 需要为它设计场景指纹与最小布尔赋值，而概率的等级曲线在场景假设里无法表达；表中条件继续按真实 `test()` 求值（决策 B4） |
| 为第三方表里的该条件自动规划满级场景 | 需要让 `SimulationProfile` 读取条件树，与"profile 不读条件树"的现有分工不一致（决策 B5） |
| 满级工具的等级改为可配置 | `mud_dredging` 的 `max_level` 为 3，与今天的写死值一致；不必引入新配置项 |

## 三、技术原理

### 3.1 已核实的事实

| 编号 | 事实 | 证据等级 |
|---|---|---|
| F1 | 按默认前缀与引用闭包静态推导，收录范围约 22 张表（原版 14 + 模组 8） | 数据包核实 + 推算 |
| F2 | 该范围内实际出现的条件类型只有 5 种：`random_chance`×12、`entity_properties`×3、`location_check`×2、`mud_dredging`×1、`random_chance_with_tool_enchantment`×1；**全部已有 handler** | 数据包核实 |
| F3 | 6 张原版 archaeology 表**零条件**；`chests/buried_treasure`、`chests/ancient_city`、`chests/ancient_city_ice_box` 亦为零条件 | 字节码核实（`minecraft-merged.jar` 内 `data/minecraft/loot_table/**`） |
| F4 | 真实用到的谓词字段只有 `location_check.predicate.biomes`（已解析出群系名）与 `entity_properties` 的 `type_specific.type=fishing_hook` + `in_open_water`（已解析为"开阔水域"） | 数据包核实 |
| F5 | 该范围内 12 处 `random_chance` 的概率值**全部是常量**，无一处动态 provider | 数据包核实 |
| F6 | 展示文本由服务端编译期生成成 `Component` 并经网络下发，客户端只负责画树与上色 | 源码核实 |
| F7 | 条件的 `description` 与 `metadata` 参与 `catalogHash`（`CatalogGeneration.updateConditionListDigest:229`、`:232-236`），但**不参与**模拟缓存的失效键 `tableHash`（`ArchaeologyJournalServerCatalog.computeTableHashes:490-521`，输入只有 `SIMULATION_CACHE_VERSION`、模拟次数、子树原始资源栈 JSON、物品签名与 id） | 源码核实 |
| F8 | 改写真述文本或新增 metadata 的后果是 `catalogHash` 变化，进而触发客户端目录一致性重取（`ArchaeologyJournalClientState:125`），不触发重新模拟 | 源码核实 |
| F9 | `LootConditionInfo.probability` 不被任何 UI 读取；`JournalTooltipBuilder` 只使用 `description` / `children` / `conditionType` | 源码核实 |
| F10 | 改动 `probability` 不会移动条件指纹：`analyzeAll` 经 `withSimulationMetadata` 总会写入 `simulation_fingerprint`，而 `LootConditionFingerprint.of(info)` 优先返回该 metadata（`:36-38`），读 `probability` 的拼接（`:42-44`）只在元数据缺失时走到 | 源码核实 |
| F11 | `LootConditionInfo.withMetadata:55-60` 是**合并**语义（拷贝进 `LinkedHashMap` 后 put），因此 handler 自设的 metadata 能穿过 `withSimulationMetadata` 存活 | 源码核实 |
| F12 | metadata 已经在网络上传输（`SyncArchaeologyCatalogPayload:264-265` 递归编码），无需新增通道 | 源码核实 |
| F13 | `UniformGenerator(NumberProvider min, NumberProvider max)`——uniform 的两端**本身是 provider**，可以嵌套动态 | 源码核实 |
| F14 | `LevelBasedValue` 在 1.21.1 有 6 个变体：`Constant(value)` / `Linear(base, perLevelAboveFirst)` / `LevelsSquared(added)` / `Clamped(value,min,max)` / `Fraction(numerator,denominator)` / `Lookup(values,fallback)`；其中 `Linear.calculate(level) = base + perLevelAboveFirst * (level - 1)` | 源码核实 |
| F15 | `describeRange`（`:255-288`）对动态 `IntRange` 已采用"回落到紧凑 JSON"的做法，是批次① 可选方案的现成先例 | 源码核实 |
| F16 | 客户端渲染器对条件树是**无上限**递归展开，没有折叠或截断 | 源码核实 |
| F17 | `Enchantment` 是 record `(description, definition, exclusiveSet, effects)`，`definition()` 返回 `EnchantmentDefinition`，后者含 `int maxLevel`；`max_level` 受 `intRange(1, 255)` 校验 | 源码核实 |
| F18 | `mud_dredging` 的 `max_level = 3`，**恰等于**今天写死的 `MUD_DREDGING_SIMULATION_LEVEL`；另三个模组附魔为 `precision_excavation`=3、`textile_recovery`=1、`fossil_hunter`=1 | 数据包核实 |
| F19 | Fabric `LootTableEvents.MODIFY` 的回调第 4 个参数是 `HolderLookup.Provider`（`LootTableEvents.java:113-122`，javadoc 写明 "the registry wrapper lookup"） | 源码核实 |
| F20 | 引用未注册条件类型会让**整张表**解析失败并不注册（`LootItemCondition.TYPED_CODEC:19-22` → `LootDataType:33-37` 记 error 返回空 → `ReloadableServerRegistries.scanDirectory:66-72` 跳过注册），后续查询得到 `LootTable.EMPTY` | 源码核实 |
| F21 | `common/src/test/` 下两个测试文件都不涉及这些条件类；风险只在"删类必须与 main 改动同批" | 源码核实 |
| F22 | `SimulationScenarioPlanner` 的注入关系判定依据是**表 id**（`RuntimeLootLinks.syntheticEdges:43`），与条件类型 id 无关 | 源码核实 |
| F23 | 全仓没有 `instanceof MudDredgingCondition`，也没有按 `.getType()` 判类型的分支 | 源码核实 |

### 3.2 路线选择依据

**为什么保真度走 `metadata`，而不是给 `LootConditionInfo` 加字段。** F12 说明 metadata 通道已经在网络上传输，F11 说明它会与既有的指纹 metadata 并存而不互相覆盖。反过来若新增 record 字段，必须同步改动网络 codec、`catalogHash` 摘要输入与指纹回落逻辑三处下游，而本轮只需要多带一个枚举语义。另有一个惯例支撑：`simulation_fingerprint_stable` 已经确立了"标记缺失即取默认值"的写法（`LootConditionFingerprint.isStable:73-76`），保真度沿用同一惯例。

**为什么颜色继续表示不确定性等级，保真度改用字体。** 颜色这一维已被 `getConditionColor`（`:279-287`）占用且被 `ItemGridPanel` 的概率文案复用，语义是"这个条件的概率是怎么来的"。保真度回答的是另一个问题："这句话读全了吗"。两者正交。选择让颜色留在原位、保真度用字重/斜体承载，是因为颜色的既有语义已进入玩家的阅读习惯（金色=概率型、黄色=运行时），而字重此前未被使用。

**为什么"未识别"与"解析失败"合并。** 这两态的区分只对诊断有价值，而项目已明确不做开发期日志、玩家也不关心失败原因，于是没有任何消费者。保留两态需要额外一条文案键与一条判断分支，收益为零。合并后仍保留条件 id，整合包作者依然能定位到具体是哪条条件没被读懂。

**为什么文案继续由服务端生成。** 唯一会出现在客户端的新逻辑是"读一个 metadata 标记决定字体"，而不是"判断这条条件该怎么描述"。这是刻意的边界：客户端一旦开始推导条件语义，`docs/dev/loottable.md` 7.2 所依赖的"解析只有一份实现"就会被撕开。

**为什么 `random_chance` 的区间走整数百分比。** 与既有的 `condition.random_chance`（`Math.round(chance * 100)`）保持一致，避免同一类条件在常量与区间两种形态下出现不同的精度口径。

**为什么合并后的字段用"必填附魔 + 可选门槛 + 可选概率"，而不是两个独立类型。** 合并前的两个条件在语义上是一层关系（门槛是概率的前提），拆成两个类型就会在数据里重复表达同一条关系的两半；而 `chance` 缺省即纯门槛，正好覆盖两者的并集。

**为什么 `min_level` 下界强制 ≥ 1。** 合并前的两个 `test()` 都要求 `level > 0`（`MudDredgingCondition:40`、`ToolEnchantmentChanceCondition:42`），所以"需要工具附魔"本身就是这个类型的固有语义，`min_level = 0` 的"无附魔也可通过"没有来源需求。此外允许 0 会让 `Linear.calculate(0)` 外推出 `base - perLevelAboveFirst`（即 0.2 配置下给出 0.1），这个值没有业务语义。数据包输入使用 `Codec.intRange(1, 255)` 校验，构造器保留相同的防御性检查，兼顾清晰报错与代码侧直接构造。

**为什么直接迁移到一个规范名。** `mud_dredging` 与 `random_chance_with_tool_enchantment` 两个旧条件格式只由本模组内置资源使用；发布新版本时，代码与两处 JSON 会在同一个构建产物中一起更新。旧 `mud_dredging` 格式又没有新 codec 必填的 `enchantment` 字段，强行把旧名注册成新 codec 的别名并不能形成真实兼容。因此本轮只注册 `unsuspiciousblock:tool_enchantment`，删除两个旧注册名和 `swamp` 字段，靠原子迁移保证新版本内部一致。

**为什么模拟取满级而不是保留写死的 III 级。** 用户的观察是"附魔等级与环境条件会形成笛卡尔积"，而取满级把这个维度压成单点。F17/F18 说明这件事可以做成数据驱动的：读 `definition().maxLevel()`，而现有数据下仍取 3，所以工具等级口径和理论概率不变。另一个附带好处是它随数据包改变——整合包提高 `max_level` 时模拟自动跟随。

**为什么只在泥地打捞路径上取满级。** 该条件的概率模拟发生在两个表级路径（泥地打捞表与钓鱼表）里，那里已经有显式的工具注入点。把"凡是出现该条件就规划满级场景"做成通用规则，需要让 `SimulationProfile` 读取条件树，与现有分工不一致（决策 B5），而当前没有第三方数据依赖它。

### 3.3 必须遵守的边界

1. **不改 `LootConditionInfo` 的 record 结构**，也不改 `LootConditionHandler` 接口签名（保真度经 metadata 传递）。
2. **客户端不得推断条件语义**，只允许读 metadata 做样式映射。
3. **不新增按实例的风险等级颜色**：`UncertaintyLevel` 是类型级接口，按实例降级需要改接口，超出本轮预算。
4. **lang 键与代码必须同批落地**，否则界面上会显示原始 key。
5. **`en_us.json` 与 `zh_cn.json` 必须同步**（项目 i18n 规范）。
6. **批次② 的资源改动必须原子**：`common/.../mud_dredging.json` 与 `neoforge/.../mud_dredging_fishing.json` 必须与条件注册代码同批，否则按 F20 会整表消失。
7. **条件不得成为注入关系的判定依据**：注入仍由表 id 决定（F22）。
8. **字段约束由 Codec 明确表达**：`min_level` 使用有范围的 `Codec` 解码，构造器只保留防御性检查；不依赖构造器抛异常来报告数据包格式错误。
9. 收尾后按项目准则执行静态检查与 `./gradlew build`，**不新增测试文件**（`AGENTS.md` 明确要求），实机验证交由用户。

## 四、技术路线

### 4.1 分层设计与目标结构

不新增分层。

**批次①** 的变更集中在三处：

```
LootConditionHandlers（服务端）
  ├─ 决定 description 文本          ← 改：random_chance 三条分支
  ├─ 决定 analysis_fidelity 元数据   ← 新增：partial / unreadable
  └─ 决定 probability 字段           ← 改：动态时置 null

LootConditionInfo（不变）
  └─ metadata 通道承载保真度

JournalTooltipBuilder（客户端）
  └─ 读 metadata → 映射样式；不推导语义
       fidelity=unreadable → CONDITION_UNREADABLE + 斜体
       fidelity=partial    → 不确定性等级色 + 斜体
       无该标记（完整）     → 不确定性等级色 + 常规
```

**批次②** 的目标结构：

```
loottable/condition/
  ├─ ToolEnchantmentCondition（新，取代 ToolEnchantmentChanceCondition）
  │    record (Holder<Enchantment> enchantment, int minLevel, Optional<LevelBasedValue> chance)
  │    规范名 unsuspiciousblock:tool_enchantment
  │    实现 LootItemCondition.Builder（供平台注入构造）
  └─ MudDredgingCondition（删除）

RuntimeLootLinks
  └─ 新增 MUD_DREDGING_TABLE_KEY : ResourceKey<LootTable>（由既有 MUD_DREDGING_TABLE 派生）
     删除 MUD_DREDGING_CONDITION（条件 id 常量移到 ModLootConditions）

ModLootConditions
  └─ 只注册规范名并保留一个类型持有者；规范名常量为 TOOL_ENCHANTMENT
```

不引入新的"条件类型注册表"抽象，也不改 `LootConditionHandlers` 的注册机制。

### 4.2 关键类型

**批次①：新增两个 metadata 常量与两个取值**

```java
// LootConditionHandlers 内；客户端跨包复用，因此必须公开
public static final String FIDELITY_METADATA_KEY = "analysis_fidelity";
public static final String FIDELITY_PARTIAL = "partial";       // 描述成立，但明知有未展示的约束
public static final String FIDELITY_UNREADABLE = "unreadable"; // 没有 handler，或 handler 未能产出描述
// 完整（full）不写该键——沿用 LootConditionFingerprint.isStable 的"缺失即默认"惯例
```

`random_chance` 的展示分支：

| provider 形态 | 展示 | `probability` | 保真度 |
|---|---|---|---|
| `ConstantValue` | `condition.random_chance`，整数百分比 | 该常量 | 完整 |
| `uniform`，两端皆为 `ConstantValue` | `condition.random_chance_range`，如"随机概率: 10% ~ 50%" | `null` | 完整 |
| `uniform`，恰一端为 `ConstantValue` | 同键，如"随机概率: 10% ~ ?" | `null` | 有保留 |
| `uniform`，两端皆非 `ConstantValue` | `condition.random_chance_dynamic` | `null` | 有保留 |
| 其他 provider（`binomial` / `score` 等） | `condition.random_chance_dynamic` | `null` | 有保留 |

`condition.random_chance_range` 的两个参数是 Java 预先格式化好的百分比片段（如 `"10%"` 与 `"?"`），而不是整数——因为 `?` 那一端无法作为整数传入。句子语序仍留在 lang 文件内，只有数字格式化在 Java，理由见 §3.2。

**有保留（`partial`）的覆盖清单**：

- `RandomChanceHandler` 上表中的三类降级
- `RandomChanceWithEnchantedBonusHandler`——附带条件：只看基础概率
- `LocationCheckHandler.simpleChild` 的 `position` / `light` / `block` / `fluid` 四处存在性行（`:525-546`）
- `EntityPropertiesHandler.genericInfo`（`:696-699`）
- `MatchToolHandler`——条件是：predicate 里除 `items` 外还有别的约束
- `DamageSourcePropertiesHandler` 的 `directEntity` / `sourceEntity` 存在性行（`:760-767`）
- `TimeCheckHandler`（`:591-615`）与 `EntityScoresHandler`（`:713-738`）——条件是：`describeRange` 回落到紧凑 JSON
- `simpleDesc` 工厂的 `value_check` / `table_bonus` / `enchantment_active_check`
- `ReferenceHandler`——给出指向但不给内容
- 组合条件 `inverted`（`:786-812`）/ `any_of`（`:815-841`）/ `all_of`（`:844-870`）继承最差子项，与 `uncertaintyLevelOf:157-169` 的递归口径保持一致

不打标记（描述完整）：`survives_explosion`、`weather_check`、`block_state_property`、`killed_by_player`、`location_check` 的群系/维度/结构行、`entity_properties` 的 `entityType` 与 `fishing_hook` 分支、`entity_scores` 的常量范围。

**批次②：新条件类型（API 草案）**

```java
public record ToolEnchantmentCondition(Holder<Enchantment> enchantment, int minLevel,
                                       Optional<LevelBasedValue> chance)
        implements LootItemCondition, LootItemCondition.Builder {
    // enchantment 必填（Enchantment.CODEC）
    // min_level  可选，默认 1；Codec.intRange(1, 255) 负责输入校验
    // chance     可选；缺省表示纯资格门槛，无概率掷骰
    @Override public boolean test(LootContext context) {
        // level = 工具上该附魔的等级
        // return level >= minLevel && (chance.isEmpty() || random < chance.get().calculate(level));
    }
    @Override public Set<LootContextParam<?>> getReferencedContextParams() {
        return Set.of(LootContextParams.TOOL);
    }
    @Override public LootItemCondition build() { return this; }
}
```

**合并后条件的展示**（父行 + 按需子行，复用 `JournalTooltipBuilder` 已有的递归渲染）：

| 情形 | 渲染 |
|---|---|
| 父行（总是） | 附魔的展示名，取自 `enchantment.value().description()`（F17：`Enchantment` 自带 `description` 组件，无需手工拼 `enchantment.<ns>.<path>`） |
| 子行 1（仅当 `min_level > 1`） | `condition.tool_enchantment_min_level` → "需要等级 ≥ %s" |
| 子行 2（仅当 `chance` 存在） | `Linear`：`condition.tool_enchantment_chance_linear` → "概率：基础 %s%%，每级变化 %s%%"；`Constant`：`condition.tool_enchantment_chance_constant` → "概率：%s%%"；其余 4 个变体：`condition.tool_enchantment_chance_dynamic` → "概率按附魔等级计算"，并标记 `partial` |

`min_level <= 1` 与 `chance` 缺省都不产生子行，避免"需要等级 ≥ 1"与"无额外概率"这类噪音行。`Constant` 与 `Linear` 能在一行内准确表达；`LevelsSquared`、`Clamped`、`Fraction`、`Lookup` 不保证单调递增，统一使用中性文案并通过 `partial` 明示没有展开完整公式。

### 4.3 数据流

**批次①**（`description` 与 `metadata` 的既有通道不变）：

```
LootTableCompiler（不变）
   └─ LootParseUtil.parseConditions
        └─ LootConditionHandlers.analyzeAll
             ├─ handler.analyze(condition)
             │    └─ 视情况 info.withMetadata(FIDELITY_METADATA_KEY, partial)
             ├─ fallback 路径 → withMetadata(..., unreadable)   ← 新增
             └─ withSimulationMetadata（不变，与保真度元数据并存）
   ↓ LootTableProjector 链接、ItemDefinitionAccumulator 合并（均不变）
   ↓ SyncArchaeologyCatalogPayload（不变，metadata 本来就传）
   ↓ ArchaeologyJournalClientState → CatalogTableDto.toTableDefinition（不变）
   ↓ JournalTooltipBuilder.appendConditionTree
        └─ 读 info.metadata() 决定样式 + getConditionColor 决定颜色   ← 改动点
```

**批次②**（画的是"合并前两条条件"如何变成"一条条件 + 子行"）：

```
mud_dredging.json pool.conditions
  合并前：[ minecraft:... 无关 ] × 0
          [ unsuspiciousblock:mud_dredging ]                      ← 门槛
          [ unsuspiciousblock:random_chance_with_tool_enchantment ] ← 门槛 + 概率
  合并后：[ unsuspiciousblock:tool_enchantment
             enchantment = unsuspiciousblock:mud_dredging
             chance      = linear(base 0.2, per_level_above_first 0.1) ]
            min_level 缺省 = 1

平台注入（Fabric FishingLootInjection）
  合并前：.when(new MudDredgingCondition(false))                      ← 无注册表依赖
  合并后：.when(new ToolEnchantmentCondition(holder(registries, MUD_DREDGING), 1, Optional.empty()))
          （F19：回调已提供 registries，当前未被使用）

模拟
  合并前：MUD_DREDGING_SIMULATION_LEVEL = 3（写死）
  合并后：holder.value().definition().maxLevel()（F17；当前仍取 3，理论概率不变）
```

### 4.4 文件改动清单

**批次① 修改**

| 文件 | 改动 |
|---|---|
| `common/.../loottable/analysis/LootConditionHandlers.java` | ① 新增保真度常量；② 重写 `RandomChanceHandler`（`:362-385`）实现 §4.2 的分支表；③ 在 §4.2 清单各处打 `partial`；④ 两条 fallback 路径（`:186-194`、`:195-198`）打 `unreadable`；⑤ `fallbackInfo` 文案键指向新的极简键；⑥ 删 `hasAnyUncertainty`（`:217-225`）；⑦ 把 `I18N_FALLBACK_PREFIX` 合并进 `I18N_PREFIX` |
| `common/.../client/tooltip/TooltipBuilder.java` | 为条件树补四个语义别名：`CONDITION_STATIC`=`POSITIVE`、`CONDITION_PROBABILISTIC`=`TITLE`、`CONDITION_RUNTIME`=`NAME`、`CONDITION_UNREADABLE`=`LABEL`；只复用现有颜色，不新增色值 |
| `common/.../client/ui/support/JournalTooltipBuilder.java` | `appendConditionTree`（`:261-276`）读 metadata 决定常规/斜体；`getConditionColor`（`:279-287`）改用 `TooltipBuilder` 的条件树语义别名，`unreadable` 使用 `CONDITION_UNREADABLE` |
| `common/src/main/resources/assets/unsuspiciousblock/lang/en_us.json` | 新增 `condition.random_chance_range`、`condition.random_chance_dynamic`；改 `condition.unknown`（`:705`）为 `Unrecognized: %s`；删除 `:651`、`:667`、`:682`、`:683`、`:687`、`:695` 六个死键 |
| `common/src/main/resources/assets/unsuspiciousblock/lang/zh_cn.json` | 同上（行号与 en_us 一致：`:705` 改；`:651`、`:667`、`:682`、`:683`、`:687`、`:695` 删） |

**批次② 新增 / 删除 / 修改**

| 文件 | 改动 |
|---|---|
| `common/.../loottable/condition/MudDredgingCondition.java` | **删除**（表 `ResourceKey` 迁出，`Builder` 责任由新条件承担） |
| `common/.../loottable/condition/ToolEnchantmentChanceCondition.java` | **重命名并泛化**为 `ToolEnchantmentCondition.java`：新增 `int minLevel` 与 `Optional<LevelBasedValue> chance`，实现 `Builder`，以 `Codec.intRange(1, 255)` 校验输入并保留构造器防御；继续声明对 `LootContextParams.TOOL` 的引用 |
| `common/.../loottable/condition/ModLootConditions.java` | 删除两个旧 codec/type 持有者，只注册规范名 `tool_enchantment`；`registerAnalysisHandlers` 改为注册新条件的 handler（含父行/子行文案）；新增规范名常量 |
| `common/.../loottable/graph/RuntimeLootLinks.java` | 新增 `MUD_DREDGING_TABLE_KEY`（由 `MUD_DREDGING_TABLE` 派生）；删除 `MUD_DREDGING_CONDITION` |
| `common/.../loottable/simulation/SimulationScenarioPlanner.java` | 删 `SCENARIO_CONDITIONS` 里的条件 id 死条目（`:44-53`）；`MUD_DREDGING_SIMULATION_LEVEL` 常量（`:34`）改为读 `definition().maxLevel()`；场景 key（`:130`、`:156`）与假定说明（`:162-166`）的参数随之动态化；假定行的 `conditionType` 指向规范名 |
| `common/src/main/resources/data/unsuspiciousblock/loot_table/gameplay/fishing/mud_dredging.json` | `:8` 与 `:11-12` 两条条件**合并为一条** `tool_enchantment`（`enchantment` + `chance`，`min_level` 缺省不写） |
| `neoforge/src/main/resources/data/unsuspiciousblock/loot_modifiers/mud_dredging_fishing.json` | `:9` 条件名改为规范名 |
| `fabric/.../loot/FishingLootInjection.java` | 用回调的 `registries`（现在未使用）取 `Holder<Enchantment>`，构造纯门槛形态的新条件；表 `ResourceKey` 改用 `RuntimeLootLinks.MUD_DREDGING_TABLE_KEY` |
| `neoforge/.../loot/FishingLootModifier.java` | 表 `ResourceKey` 引用（`:68`、`:74`、`:90`）改用 `RuntimeLootLinks.MUD_DREDGING_TABLE_KEY` |
| `fabric/.../UnsuspiciousBlockFabric.java`、`neoforge/.../UnsuspiciousBlockNeoForge.java` | 删除两个旧注册名及其类型持有者，只注册 `tool_enchantment` 并回写一个规范类型持有者（Fabric `:97-107`、NeoForge `:129-133`、`:252-253`） |
| `common/src/main/resources/assets/unsuspiciousblock/lang/{en_us,zh_cn}.json` | 删除 `condition.mud_dredging`、`condition.tool_enchantment_chance`；新增父行与子行文案键 |

**不改**：`LootConditionInfo`、`LootConditionHandler`、`LootConditionFingerprint`、`SyncArchaeologyCatalogPayload`、`CatalogGeneration`、`LootTableCatalog`、`ItemGridPanel`、`LootTableCompiler`、`LootTableProjector`、`LootParseUtil`、`SIMULATION_CACHE_VERSION`（批次② 的资源变化已经让 `tableHash` 自然失效，无需 bump）。

### 4.5 分阶段实施

**批次①（设计已定案）**

1. `LootConditionHandlers`：保真度常量 + `RandomChanceHandler` 重写 + 清单各处 `partial` + 两条 fallback 路径 `unreadable` + 文案键切换。
2. `TooltipBuilder` + `JournalTooltipBuilder`：补条件树语义别名，并完成保真度样式映射。
3. 两个 lang 文件：增 2 键、改 1 键、删 6 键。
4. 死代码清理（`hasAnyUncertainty`、重复前缀常量）。
5. 验证（§七）。

步骤 1–3 必须同批完成（§3.3 边界 4）。

**批次②（设计已定案）**

1. 新条件类型：`ToolEnchantmentCondition`（含 `Builder`、构造校验）替换 `ToolEnchantmentChanceCondition`；删除 `MudDredgingCondition`。
2. 常量搬迁：`RuntimeLootLinks` 新增 `MUD_DREDGING_TABLE_KEY`、删除 `MUD_DREDGING_CONDITION`；规范名常量进 `ModLootConditions`。
3. `ModLootConditions`：删除两个旧注册名，只注册规范名及其分析 handler（父行 + 按需子行）。
4. 平台注册与注入：Fabric 用 `registries` 构造门槛形态条件；NeoForge 更新类型持有者；两个平台的表 `ResourceKey` 引用改写。
5. 资源迁移：两个 JSON 同批改（边界 6）。
6. 场景规划：删死条目、满级规则、key 与假定说明动态化。
7. lang：删 2 键、增新键。
8. 验证（§七）。

步骤 1–5 必须同批完成，否则按 F20 会整表消失。建议在 5 之后先做一次编译 + 重载验证，再进 6–7，把"资源与注册名是否对齐"这个最高风险点单独隔离出来。

### 4.6 文档同步

**批次① 完成后**：

- [`docs/dev/loottable.md`](../dev/loottable.md) 第 7.2 节末段与第 10 节：补"条件展示有保真度三态，由服务端以 metadata 给出、客户端只做样式映射"，以及"动态概率不展示数值"。
- [`docs/dev/tooltip.md`](../dev/tooltip.md)：新增条件树映射表，记录四个条件语义别名与保真度字体约定。该文档已经规定 GUI hover tooltip 必须从 `TooltipBuilder` 取色，因此实现不得在 `JournalTooltipBuilder` 新增 `ChatFormatting` 字面量。

**批次② 完成后**：

- [`docs/dev/loottable.md`](../dev/loottable.md)：第 9 节（`:410-421`）的两种条件描述重写为一种；第 10 节扩展点（`:427`）的示例类换成新条件；子包结构树（`:67-70`）、场景告警举例（`:320-323`）、平台注入说明（`:362-366`）同步。
- [`docs/dev/enchantment.md`](../dev/enchantment.md)：第 6 节（`:139-147`）与 `:51`、`:98`、`:208` 的"资格条件 + 概率条件"两段式描述改为单一条件。
- [`docs/dev/architecture-overview.md`](../dev/architecture-overview.md) `:122`、`:128`：初始化引导链里"两种条件 / 两个 holder"的表述改为一种。
- [`docs/dev/client-ui.md`](../dev/client-ui.md) `:106`、[`docs/dev/mixin.md`](../dev/mixin.md) `:78`：核对是否需要随行为调整。

## 五、决策记录

### 5.1 批次①

| 编号 | 决策点 | 结论 | 被否决的备选与理由 |
|---|---|---|---|
| A1 | 范围判据 | 只做"修说谎 + 保真度 + 死代码" | 全量谓词字段深度、整合包广度：与"修静默假信息"不同源，且当前收录数据不触发（F2/F4） |
| A2 | 改动分层 | 服务端解析层为准，客户端仅做样式映射 | 客户端自行解析原始 JSON：会撕开"解析只有一份实现"（`loottable.md` 7.2 的前提） |
| A3 | 未解析条件的可辨识 | 加标记 | 不标记：玩家无法判断可信度；解析失败就隐藏：玩家会误以为该物品无条件 |
| A4 | 验收方式 | IDEA 静态检查 + `./gradlew build` + 用户实机手测 | 开发期日志（已排除）、新增条件侧单测（与 `AGENTS.md` 冲突） |
| A5 | 动态 `random_chance` 的展示 | uniform 两端常量给区间；恰一端动态则分端展示、另一端写 `?`；两端动态或非 uniform 一律"动态"；`probability` 置 `null` | 一律只写"动态"（丢掉 uniform 的可用信息）；回退紧凑 JSON（玩家读不懂）；区间与 JSON 并列（tooltip 出现机器文本）；两端动态时写 `?–?`（读起来像渲染错误） |
| A6 | 保真度载体 | 复用 `LootConditionInfo.metadata` | 新增 record 字段（牵动 codec / 哈希 / 指纹三处下游）；客户端自行推断（推不出"有保留"这一档） |
| A7 | 视觉编码 | 颜色 = 不确定性等级，字体 = 保真度 | 颜色 = 保真度（需重排已进入玩家习惯的色表）；合并成单一色阶（会把"纯运行时"与"有保留"混为一谈）；只区分"没读到"（放弃表达"半懂"） |
| A8 | 未识别文案 | 一句极简文案 + 保留条件 id，两态合并 → `未识别：%s` | 不显示 id（同时失去唯一的定位入口）；保留两态（无消费者），见 E1 |
| A9 | `partial` 的判定范围 | 覆盖所有已知保留（§4.2 清单） | 只标本轮新改的三处（`location_check` 的存在性行同样是"没说完"）；由接口自声明（需改接口与全部 19 个 handler） |
| A10 | 轮次切分 | 拆两批：批次① 交付已定案项，条件合并单独成批 | 本轮一起做完（验收点混杂，且合并部分前置未知）；合并优先（会让已定案的两项停摆） |
| A11 | 模组两条条件的文案 | 批次① **完全不碰**（含标记） | 批次① 先打 `partial` 标记（会产生"标了有保留但文案没改"的中间态） |
| A12 | 存量啰嗦文案 | 不动 | 顺带压缩最啰嗦的几条；全面重审（属独立一轮，会淹没本轮验收点） |
| A13 | 死代码处置 | 全清（含 6 个 lang 键） | 保留 4 个"类型级"键（真需要时从 git 取回成本极低）；只清 `and`/`or` |

### 5.2 批次②

| 编号 | 决策点 | 结论 | 被否决的备选与理由 |
|---|---|---|---|
| B1 | 合并方向 | 把 `MudDredgingCondition` 的功能合并进工具附魔条件，泛化为"需要工具附魔 + 可选等级门槛 + 可选随等级变化的概率" | 两条条件各自保留、只改文案（玩家仍读不出"资格"与"概率"的分工） |
| B2 | 注册名策略 | 只注册规范名 `unsuspiciousblock:tool_enchantment`，删除 `mud_dredging` 与 `random_chance_with_tool_enchantment` | 保留旧名作别名：旧 `mud_dredging` 格式缺少新 codec 必填的 `enchantment`，共享 codec 并不能兼容；两个旧格式又只有本模组内置资源使用，没有长期维护别名的收益；沿用 `random_chance_with_tool_enchantment`：名字里的 Chance 与"可无概率"不符 |
| B3 | 字段形态 | `enchantment` 必填 + `min_level` 可选（默认 1，以 `Codec.intRange(1, 255)` 校验）+ `chance` 可选 | `chance` 必填、纯门槛用 `chance: 1.0` 表达（要求数据包写一个恒为 1 的概率值）；等级用 `IntRange`（表达力过剩，"等级落在区间内"几乎用不到） |
| B4 | 场景规划 | 新条件**不进** `SCENARIO_CONDITIONS`，删掉旧 id 死条目，条件按真实 `test()` 求值 | 进集合保持"场景接管"（需为它设计场景指纹与最小布尔赋值，且概率的等级曲线在场景假设里无法表达）；进集合并把旧概率条件也纳入（要重估场景模型） |
| B5 | 满级规则的适用范围 | 只改泥地打捞路径：把写死的 3 换成 `definition().maxLevel()` | 通用（任何表都规划满级场景）：需让 `SimulationProfile` 读条件树，与现有分工不一致且当前无第三方依赖；通用但不进场景集合：同样要求 profile 构建期看到条件内容 |
| B6 | 自家资源迁移 | `mud_dredging.json` 与 NeoForge GLM JSON 迁到规范名，并把 pool 上两条条件**合并为一条**；接受因此触发的一次性重模拟 | 不迁移只泛化类型（自家数据不验证新形态，且两行相邻文案的问题在存量数据里照旧）；改条件名但不合并（一个类型能表达的事拆成两条） |
| B7 | 类名 | 改名为 `ToolEnchantmentCondition`，与新注册名对齐 | 保留 `ToolEnchantmentChanceCondition`（类名里的 Chance 与"可无概率"不符）；改成更长的 `ToolEnchantmentRequirementCondition`（偏长） |
| B8 | 假定说明行的条件 id | 指向规范名 `unsuspiciousblock:tool_enchantment` | 保留旧 id 字符串（类型已注销，渲染成灰色且目录哈希里留脏数据）；新增"非条件呈现"（要动 assumptions 的类型与网络/哈希链路） |
| B9 | 展示结构 | 父行 = 附魔展示名；`min_level > 1` 时出等级子行；`chance` 存在时出概率子行：`Constant`/`Linear` 给准确数值，其余变体用中性文案"概率按附魔等级计算"并标记 `partial` | 两个子行总是出现（大量噪音行）；所有变体都声称"随等级提升"（`Constant` 不变化，`Lookup` 等也不保证单调，属于假信息）；概率子行一律不给数字（浪费可准确展示的简单形态） |
| B10 | `SIMULATION_CACHE_VERSION` | 不 bump | bump（会强制全部表重算，而资源变化已通过 `tableHash` 让相关子树自然失效） |
| B11 | 条件 id 常量的归属 | 移到 `ModLootConditions`（与既有概率条件的生成方式统一），删除 `RuntimeLootLinks.MUD_DREDGING_CONDITION` | 留在 `RuntimeLootLinks`（条件 id 不是"运行时关系标识符"，该类的文档定位是平台注入关系与钓鱼上下文判定） |
| B12 | 是否兼容旧条件格式 | **不兼容**。两个旧注册名与 legacy `swamp` 字段都删除，内置 JSON 与代码原子迁移 | 保留 `swamp` 或旧名：旧格式仅由本模组使用；其中 `mud_dredging` 还缺少新格式必填的 `enchantment`，保留同 codec 别名会形成虚假兼容 |

### 5.3 讨论中修正的自身错误留档

| 编号 | 错误 | 修正 |
|---|---|---|
| E1 | 初版主张区分"已注册类型没有分析 handler"与"handler 解析失败"两态，并为此设计了两条文案键 | 该区分只对诊断有价值；项目已排除开发期日志、玩家不关心原因，两态**没有任何消费者**，属过度设计。合并为一态（A8）。完全未注册的 JSON 条件在 Codec 阶段失败，不属于 tooltip fallback |
| E2 | 先给出"模组两条条件只展示附魔名"的结论（早期轮次），随后用户提出"同时展示随等级变化"并要求合并两个条件 | 两者相反。以合并方向为准（B1），且批次① 完全不碰这两条条件（A11），避免在半成品状态上改写文案 |
| E3 | 把"客户端一行不动"当作批次① 的不变量 | "用字体传递信息"必然要求客户端读 metadata，二者不可兼得。放宽为"服务端决定语义、客户端只做样式映射"（A2），并把这条边界写进 §3.3 |
| E4 | 一度担心改写展示文本会让玩家存档的概率模拟缓存全部失效 | F7/F8 核实后否定：展示文本只进 `catalogHash`（触发一次客户端目录重取），不进模拟缓存键 `tableHash`。此担心不成立，无需为它设计迁移 |
| E5 | 我曾判断合并后无法在平台注入点构造 `Holder<Enchantment>`，准备把字段降级成 `ResourceKey<Enchantment>` | F19 核实 Fabric 回调第 4 参就是 `HolderLookup.Provider`（现未被使用），否定该判断。字段保持 `Holder`，展示侧也才能直接用 `Enchantment.description()` |
| E6 | 我曾把"模拟用 III 级工具"当作需要原样保留的写死常量 | 用户提出"一律取最高附魔等级"。F17/F18 核实 `max_level` 可读且恰为 3，故可改为数据驱动，同时保持当前工具等级与理论概率口径不变 |
| E7 | 一度计划给两个旧注册名挂同一个新 codec，并把它称为兼容 | 旧 `mud_dredging` JSON 没有新 codec 必填的 `enchantment`，别名无法成功解码。确认旧格式只有本模组使用后，改为代码与内置资源原子迁移，不保留旧注册名或旧字段 |
| E8 | 一度把重新模拟后的百分比完全一致作为验收 | 当前模拟使用随机抽样且没有固定复现种子；语义等价只能保证理论概率不变。验收改为检查场景、可达性与概率分布无结构性变化，并允许抽样波动 |

## 六、风险与限制

| 风险 | 说明 | 处置 |
|---|---|---|
| 客户端开始理解解析层内部标记 | `JournalTooltipBuilder` 会读 `analysis` 包的 metadata 键，形成一处新的跨层耦合 | 只允许读"保真度"这一个语义键；键常量由 `LootConditionHandlers` 提供单一来源，禁止在客户端写字面量 |
| `partial` 覆盖偏广，斜体出现频率可能高于预期 | 清单含 3 个 `simpleDesc` 类型、4 处 `location_check` 存在性行、2 处 `damage_source_properties` 嵌套行等 | F4/F5 表明当前默认收录数据不会触发这些有损分支；斜体主要在扩展数据包场景出现。若实机观感过吵，再按真实数据收窄 |
| 合并"未识别"与"解析失败"后失去诊断入口 | 玩家与整合包作者都无法区分两种失败 | 保留条件 id 作为定位入口（A8）；若日后需要诊断，走日志开关而不是文案 |
| `condition.random_chance_range` 的参数是预格式化字符串 | 与 `condition.random_chance` 的整数参数口径不一致 | 句子语序仍在 lang 内，仅数字格式化在 Java；替代方案（拆成多个键）会让键数量随"哪端动态"的组合增长 |
| `catalogHash` 变化触发客户端目录重取 | 新增 metadata 与改写文案都会改变 `catalogHash` | 一次性重取目录，非重新模拟（F7/F8）；属预期行为 |
| 批次② 的资源迁移会触发一次真实重模拟 | `tableHash` 覆盖子树内每张表的完整资源栈摘要，改 `mud_dredging.json` 会让泥地打捞表与 `minecraft:gameplay/fishing`（经运行时注入边）重新模拟 | 属预期行为；F18 保证满级仍为 3，因此理论概率不变，但随机抽样结果允许有正常波动 |
| 旧格式不再加载 | 新版本不再注册 `mud_dredging` 与 `random_chance_with_tool_enchantment`，也不读取 `swamp` | 这是明确接受的迁移边界：旧格式只有本模组内置资源使用，发布包内代码与 JSON 同步更新；不对外承诺旧格式兼容 |
| 资源与注册名不同批会静默炸整表 | 按 F20，引用未注册类型的表整张不注册，只留一条 error 日志 | §4.5 把资源迁移与类型注册放在同一批，并建议在资源迁移后单独跑一次编译 + 重载验证 |
| 新条件进入第三方数据包时的可读性 | 泛化后任何附魔都能用，`min_level > 1` 与各 `LevelBasedValue` 变体都会出现 | `Constant`/`Linear` 精确展示；其他变体使用不承诺单调性的中性文案并标记 `partial`（B9） |
| 行号会随实施失效 | 本文所有 `文件:行` 引用以写作时工作区为准 | 实施时以类名与方法名定位，不依赖行号 |

## 七、验证方式

### 7.1 编译验证（开发执行）

1. 通过 IDEA MCP（`http://127.0.0.1:64342/stream`）检查工作区改动文件的报错与警告，忽略 markdown 文件的格式问题。
2. 执行 `./gradlew build`。按项目准则：等待时间不短于 120 秒；若返回运行中的任务 ID 则持续等待至结束；未确认上一次构建结束前不重复启动，避免并发占用 `build` 目录导致误报。
3. 不做新增测试文件（`AGENTS.md` 明确要求）。

### 7.2 实机验证（用户执行）

**批次① 基础核对**（现有收录数据即可触发）：

1. 泥地打捞表物品的 tooltip：两条模组条件的行样式与改动前**一致**（批次① 不碰它们），确认没有回归。
2. 原版 `minecraft:gameplay/fishing` 与其子表物品：`entity_properties`（开阔水域）与 `location_check`（群系）行显示正常。
3. 触发一次完整重载，确认没有新的 missing lang key 告警（6 个被删的键确认无引用）。
4. 确认客户端目录能正常同步（`catalogHash` 变化后重取一次属预期）。

**批次① 新增分支核对**（现有收录数据**不覆盖**）：F5 说明 12 处 `random_chance` 全为常量，动态分支与"分端展示"分支在默认数据下不可触发。临时在 `common/src/main/resources/data/unsuspiciousblock/loot_table/gameplay/fossil_hunter/` 下新增一张被默认前缀命中的表，放置四种 `random_chance`：

| 写法 | 期望 |
|---|---|
| `"chance": 0.4` | "随机概率: 40%"，常规字重 |
| `{"type":"minecraft:uniform","min":0.1,"max":0.5}` | "随机概率: 10% ~ 50%"，常规字重 |
| `{"type":"minecraft:uniform","min":0.1,"max":{"type":"minecraft:score", ...}}` | "随机概率: 10% ~ ?"，**斜体** |
| `{"type":"minecraft:binomial", ...}` | "随机概率: 动态"，**斜体** |

fallback 不能用完全未注册的 JSON 条件验证：这类条件会在 Codec 阶段使整张表解析失败（F20），不会进入 tooltip。若要覆盖"未识别"分支，开发时临时让一个**已注册且可正常解码**的条件不登记分析 handler（例如临时移除 `value_check` 的 handler 注册），确认其显示"未识别：minecraft:value_check"且为灰色斜体；验证后恢复注册。

**批次② 核对**：

1. **语义不变**：F18 表明满级仍为 3，因此门槛、等级和理论概率不变。迁移后检查泥地打捞表全部物品、钓鱼表中的子表入口、适用场景与可达性没有结构性变化；显示百分比来自随机模拟，允许正常抽样波动，不做逐项精确相等断言。
2. **展示合并**：泥地打捞表物品的 tooltip 上，原来相邻的两条条件应变为**一行附魔名**；因为 `min_level` 缺省为 1 且 `chance` 为 `Linear`，应出现一个概率子行（"概率：基础 20%，每级变化 +10%"），且不出现等级子行。
3. **重模拟发生**：日志应显示泥地打捞表与钓鱼表重新模拟（`tableHash` 变化），其余表不受影响。
4. **旧格式已移除**：确认两端只注册 `unsuspiciousblock:tool_enchantment`，发布包内不再出现 `unsuspiciousblock:mud_dredging`、`unsuspiciousblock:random_chance_with_tool_enchantment` 或 `swamp` 字段。无需验证旧格式加载成功。
5. **纯门槛形态**：临时表里写一条只带 `enchantment` 的 `tool_enchantment` 条件（无 `chance`、无 `min_level`），确认父行只有附魔名、**不出现任何子行**。
6. **等级门槛与概率变体**：临时表里写 `"min_level": 2`，确认出现"需要等级 ≥ 2"子行；`Constant` 显示固定百分比，`Linear` 显示基础值与每级变化；换成 `levels_squared` 或 `lookup` 后显示中性的"概率按附魔等级计算"并带 `partial` 样式，不声称概率必然提升。
7. **另一个附魔**：把临时表里的 `enchantment` 换成 `unsuspiciousblock:fossil_hunter`，确认文案随之变化（验证 B1 的"按附魔解耦"），同时确认没有任何代码路径假设它是泥地打捞。

验证完毕请删除所有临时表，避免污染默认收录范围。

## 八、待确认项

| 编号 | 事项 | 推荐处置 |
|---|---|---|
| Q1 | C/D 级工作（§2.3 前三项）是否排期 | 未排期。它们面向整合包受众，价值随第三方数据包规模增长；建议在批次② 收尾后按实际反馈决定 |
| Q2 | 存量啰嗦文案的重写时机 | 建议与 C 级（谓词字段深度）合并处理，届时 `location_check` 与 `entity_properties` 的文案本就要重写 |

## 九、实施结果

### 9.1 批次① 与批次②（2026-09-18，同批完成）

两个批次在同一工作区连续实施，中间各跑一次构建验证。

**构建结果**

| 项 | 结果 |
|---|---|
| `./gradlew build` | BUILD SUCCESSFUL（common / fabric / neoforge 全部通过，含 `remapJar`） |
| IDEA 静态检查 | 改动文件无 error；剩余告警均为与本次无关的既有风格提示（"可被替换为记录模式"、`getRandomItemsRaw` 过时 API） |
| 新增测试文件 | 无（`AGENTS.md` 要求） |

**产物检查**（版本 `1.5.2`）

| 项 | 结果 |
|---|---|
| 三个 jar 内的条件类 | 只剩 `ToolEnchantmentCondition`（及 `ModLootConditions$ToolEnchantmentDescriptionHandler`）；`MudDredgingCondition` / `ToolEnchantmentChanceCondition` 已消失 |
| common jar 的 `mud_dredging.json` | pool 上只剩一条 `unsuspiciousblock:tool_enchantment`，带 `enchantment` 与 `chance`（`linear` 0.2 / 0.1），不写 `min_level` |
| neoforge jar 的 GLM JSON | `unsuspiciousblock:tool_enchantment` + `enchantment`，无 `chance` |
| lang 键集合 | en_us / zh_cn 各 732 键，两文件键集合完全一致；旧条件键与 6 个死键均已移除 |

**与计划的偏离**

| # | 偏离 | 原因 |
|---|---|---|
| D1 | NeoForge 的 `mud_dredging_fishing.json` 除改条件名外还**补了 `enchantment` 字段**（计划 §4.4 只写"条件名改为规范名"） | 新 codec 的 `enchantment` 必填。只改名字会让 GLM 条件解码失败，注入随之失效 |
| D2 | `MatchToolHandler` 判断"除 items 外还有隐藏约束"时改用 `ItemPredicate` 的记录访问器与默认值比较，不用 Codec 编码结果数 key | `ItemPredicate.CODEC` 用的是 `optionalFieldOf`，**编码时总会写出 `count`/`components`/`predicates` 字段**，按 key 判断会把"只有 items"也误判成有隐藏约束，使该行恒为斜体 |
| D3 | `RandomChanceWithEnchantedBonusHandler` 的 `probability` 仍保留基础概率，只加 `partial` 标记 | §4.2 表格只覆盖 `random_chance` 的三态；该 handler 的文案本身写明是"基础"，不构成假信息，不在本轮改成 `null` |
| D4 | `describeRange` 改为返回 `RangeDescription(text, lossy)`，不再只返回字符串 | 需要把"是否回落到紧凑 JSON"这一信息传给 `TimeCheckHandler` / `EntityScoresHandler` 才能打标记 |
| D5 | 合并后条件的 `uncertaintyLevel` 取 `RUNTIME` | 该接口是类型级的，只能取一个值（§3.3 边界 3）。合并前两条条件共同决定的等级就是 `RUNTIME`（`RUNTIME` 序数高于 `PROBABILISTIC`），取它可让泥地打捞条目的概率文案保持"条件概率"而非"概率估算"，避免非计划内的文案变动 |
| D6 | `LootConditionHandlers.I18N_PREFIX` 提升为 `public`，并新增 `public static partial(info)` | 自定义条件的展示描述要用到文案键前缀与"有保留"标记。就地复制字面量会重新引入批次① 刚删掉的重复常量 |
| D7 | 删除 `SimulationScenarioPlanner` 中未使用的 `java.util.Comparator` 导入 | IDE 告警，预存在；一行清理 |

**已验证的等价性**（静态推导，非实机）

- 合并前后的判定对任意等级完全等价：新条件即 `level >= min_level && (chance 缺省 || rand < chance(level))`，内置数据下 `min_level = 1`，与旧的"`level > 0` 且（`level > 0` 且 `rand < chance`）"一致。
- 模拟工具等级：`max_level = 3`（数据核实），与旧写死值相同，理论概率不变。
- `ToolEnchantmentCondition` 声明引用 `LootContextParams.TOOL`；`LootContextParamSets.FISHING` 要求 `ORIGIN` + `TOOL`，两端注入的表均为 fishing 类型，故 `LootTable.validate` 通过（NeoForge GLM 侧同理）。
- 该条件不进 `SCENARIO_CONDITIONS`，因此泥地打捞表的场景数由 2 降为 1（仅 `default` + 满级工具假设），条件按真实 `test()` 求值——即决策 B4 的预期结果。

**待用户实机验证项**（§7.2 的清单，已按批次①/② 合并）

1. **批次① 基础核对**：泥地打捞表物品 tooltip 的条件行样式正常；原版 `minecraft:gameplay/fishing` 与子表的 `entity_properties`（开阔水域）、`location_check`（群系）行显示正常；完整重载后无 missing lang key 告警；客户端目录同步一次（`catalogHash` 变化属预期）。
2. **批次① 动态概率分支**（默认数据不覆盖，需临时表）：按 §7.2 的四行表验证 `0.4` / `uniform` 两端常量 / 一端动态 / `binomial` 的字重与文案；再用"已注册但不登记分析 handler"的方式验证 `未识别：<id>` 的灰色斜体。验证后删除临时表、恢复 handler 注册。
3. **批次② 展示合并**：泥地打捞表物品上原来相邻的两条条件应变成"一行附魔展示名（泥底打捞）+ 一行概率子行（概率：基础 20%，每级变化 10%）"，不出现等级子行。
4. **批次② 重模拟**：日志应显示泥地打捞表与钓鱼表重新模拟，其余表不受影响。
5. **批次② 旧格式已移除**：发布包内不再出现 `unsuspiciousblock:mud_dredging`、`unsuspiciousblock:random_chance_with_tool_enchantment` 或 `swamp` 条件字段。
6. **批次② 变体核对**（需临时表）：纯门槛形态（只有 `enchantment`）不出现任何子行；`min_level: 2` 出现"需要等级 ≥ 2"；`Constant` / `Linear` 给准确百分比，`levels_squared` / `lookup` 给中性文案并带斜体；把 `enchantment` 换成 `unsuspiciousblock:fossil_hunter` 后文案随之变化。验证后删除临时表。

**收尾状态**：本文档暂留在 `docs/plan/`；待用户完成上述实机验证并确认后，再归档到 `docs/archive/`（该目录不入库）。
