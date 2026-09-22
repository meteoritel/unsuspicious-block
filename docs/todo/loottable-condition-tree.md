# 战利品条件树：覆盖、回退与谓词捕获待办

> 状态：**实施前审查快照**（2026-09-22）；D1–D5 与 D7 已在本轮修复，D6 的通用第三方扩展点保留待办。实施记录见 [修复计划](../plan/loottable-condition-tree-plan.md)。
>
> 机制的唯一权威描述是 [战利品表系统](../dev/loottable.md)；本文只记录经静态阅读核实的事实、缺陷与可动项。
> 本项目代码的行号以写作时的工作区为准；**原版代码的行号以 `common/build/moddev/artifacts/*-minecraft-sources.jar` 为准**
> （MCP 的反编译视图行号与它不一致）。行号会随改动失效。
> **全部结论未编译、未实机验证**，未验证的前提单列于第六节。

核心类：[`LootConditionHandlers`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootConditionHandlers.java)（19 种原版条件处理器）、
[`LootParseUtil`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootParseUtil.java)（JSON → 条件信息）、
[`LootConditionInfo`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootConditionInfo.java)（分析结果 record）。

---

## 一、已核实的现状（无待办）

| 项 | 事实 | 依据 |
|---|---|---|
| 原版条件覆盖 | 注册名与 1.21.1 `LootItemConditions` 的 19 个注册名逐字一致，无缺项、无多余、无重复 | 脚本比对；`LootConditionHandlers:88-114` |
| 条件树挂载位置 | pool 级、entry 级、`loot_table` 引用入口与继承链、条件函数自带 `conditions`、组合条件递归，全部覆盖 | `LootTableCompiler:292`、`:368`；`LootTableProjector:175`、`:209`、`:310` |
| 组合条件子项 | `all_of`/`any_of` 的 `terms` 经 `SimulationCompositeConditionMixin` 暴露；`@Shadow` 字段与 `CompositeLootItemCondition` 源码一致；common mixin config 在 `fabric.mod.json:38-41` 与 `neoforge.mods.toml:51-52` 均已声明 | 源码核对 |
| 客户端渲染 | `appendConditionTree` 递归渲染全部子行，无行数上限 | `JournalTooltipBuilder:323-341` |
| 网络传输 | 条件树的 `description`/`children`/`metadata` 递归下发 | `CatalogStreamCodec:372-393` |
| 表哈希 | 每表内容哈希只覆盖 `SIMULATION_CACHE_VERSION`、被引用附魔定义、item 数量 + signature + id，**不含条件元数据** | `ArchaeologyJournalServerCatalog:1140-1149` |
| 机器侧消费通道 | `LootConditionInfo.source()` 持有原版类型化条件对象，仅服务端本代内使用、不进网络编码；`PlayerStateProbe:64` 与 `RecommendationSolver.possible` 都直接使用它 | `LootConditionInfo:44-52` |
| 未知条件的降级方向 | 非场景控制类型在 `requirementsFor` 返回"无约束"，条目保留在所有代表场景中（保守方向） | `SimulationScenarioPlanner:316`、`:352` |

**由此确定的一条边界**：捕获谓词字段只影响 `description`，其真实读者有两个——条件树/提示文本，以及场景名
（`ScenarioLabel.collectLeafText:139` 取 `node.description().getString()`）。服务端机器逻辑不依赖捕获。

---

## 二、当前缺陷

### 2.1 会丢信息

| 编号 | 缺陷 | 位置 | 事实 |
|---|---|---|---|
| D1 | 解码失败的条件不标 `unreadable` | `LootParseUtil:76` | 该分支直接调 `fallbackInfo(...)`，未包 `unreadable(...)`；而同包 `analyzeAll` 的未识别路径（`:217`）是包了的。结果：该条件 `metadata` 无 `analysis_fidelity`、无指纹与稳定性元数据；客户端 `isFidelityIncomplete`（`JournalTooltipBuilder:344`）为 false → 不上斜体；若 id 是原版类型，`getConditionColor:349` 会走 handler 的常规配色，一行文案为「未识别：xxx」的条件看起来像正常读懂了 |
| D2 | 类型不在注册表时条件静默丢弃 | `LootConditionHandlers:215` | `else if (conditionId != null)` 无 `else` 兜底。`getKey(type)` 返回 null 时该条件既不入树、也不产生任何「未识别」行，整条消失。JSON 主路径（`DIRECT_CODEC` 解码成功必然已注册）不触发，但 `analyzeAll` 另有非 JSON 入口：`LootConditionFingerprint:27`、`SimulationConstraintCatalog:104` |
| D3 | `entityType` 与 `subPredicate` 互斥，后者信息丢失 | `LootConditionHandlers:775-801` | `EntityPropertiesHandler.analyze` 命中 `entityType` 即 `return`。`EntityPredicate` 的 `type` 与 `type_specific`（`subPredicate`）是两个独立字段，可同时存在；此时 `type_specific` 永不展示 |

### 2.2 会误报

| 编号 | 缺陷 | 位置 | 事实 |
|---|---|---|---|
| D4 | 第三方条件挡住条目时网格报「未命中」 | `PathHintAnalyzer:60`、`:231-243` | `collect` 只认三类条件：`match_tool`、附魔等级机制（`:262`）、`SimulationScenarioPlanner.isScenarioControlled`。未知类型不进任何提示类别 → `hintsFor` 为空 → `deriveDisplay` 落回"适用 + 零命中 + 无任何引用 = 真正的抽样结论"，返回 `Measured(0.0)`，网格显示「未命中」。真因只存在于 tooltip 条件树中那行「未识别：&lt;id&gt;」。这与 `PathHintAnalyzer.deriveEntryDisplay` 的注释所述原则相反（那里明确说零命中报「需要条件」的理由是避免"把确定的原因说成运气"） |

### 2.3 完全静默

| 编号 | 缺陷 | 位置 | 事实 |
|---|---|---|---|
| D5 | 无服务端诊断 | `LootConditionHandlers` 全类 | 该类无 logger。`LootParseUtil:64` 只在解码**失败**时 `LOGGER.warn`；"解码成功但没有 handler"（`:215-217`）这条路径不产生任何日志。运维无法从日志发现某张表带未识别条件 |
| D6 | 无第三方扩展点 | `LootConditionHandlers:123-134` | `register` 是 mod 内部 API，没有 datapack/配置级通用入口，也没有"按 JSON 结构泛化描述"的兜底。第三方条件要升级为可读文案只能依赖本模组并调用它 |

### 2.4 展示形态

| 编号 | 缺陷 | 位置 | 事实 |
|---|---|---|---|
| D7 | `StatePropertiesPredicate` 的区间取值展示成 JSON | `LootConditionHandlers:349-360`、`:342` | `RangedMatcher` 的编码形状是 `{"min":"a","max":"b"}`；`compactJsonValue` 对非 primitive 直接 `element.toString()`，输出形如 `facing={"min":"a","max":"b"}`。codec 形状已知，可在回读后识别 min/max 拼成 `a..b` |

### 2.5 已判定为刻意降级、不修的缺口

| 项 | 事实 | 理由 |
|---|---|---|
| `reference`（`ConditionReference`） | `:992-1013` 只展示被引用 id，标 `partial` | 解析内容需 `PREDICATE` 注册表，编译期是纯 JSON 路径、拿不到注册表 |
| `value_check` / `table_bonus` / `enchantment_active_check` | `simpleDesc`（`:377-397`）各给一句通用文案，标 `partial`，参数全不展示 | 见 3.2，属"可升级"而非"无法实现" |
| `location_check` 的 position/light/block/fluid | `:634-655` 只陈述"存在该约束"，标 `partial` | 见 3.2 |
| `damage_source_properties` 的 direct_entity/source_entity | `:877-884` 只陈述"存在该约束"，标 `partial` | 嵌套整个 `EntityPredicate`；且"被玩家击杀"已有独立条件 `killed_by_player`，语义重复 |
| 第三方条件不参与场景规划 | `SimulationScenarioPlanner:316` 返回无约束，因此也不触发"指纹不稳定"告警 | 方向保守，行为正确 |
| 附魔定义 `max_level` 之外的字段等 | 未进入表哈希 | 不影响任何概率，见 `docs/dev/loottable.md` §7.4 的"已知残余" |

---

## 三、可以修改

### 3.1 缺陷修复（对应第二节编号）

| 编号 | 改动点 | 规模 |
|---|---|---|
| D1 | `LootParseUtil:76` 的 `fallbackInfo(...)` 外包一层 `LootConditionHandlers.unreadable(...)`（同包，可直接调用） | 1 行 |
| D2 | `LootConditionHandlers:215` 的 `else if` 补 `else` 兜底，使条件仍带 `unreadable` 标记与一个可定位标识进入结果 | 数行 |
| D3 | `EntityPropertiesHandler.analyze` 改为累积 `entityType` 与 `subPredicate` 两个子行，而非命中即 return | 数十行 |
| D7 | `describeStateProperties` 回读后识别 `min`/`max` 键，拼为 `a..b` | 十行内 |

修复 D1/D2 的连带效果：两者都会把 `analysis_fidelity` 补成 `unreadable`，并经 `inheritChildFidelity`（`:252-265`）传播到组合父行。

### 3.2 谓词捕获（改现有 handler 的 `analyze`）

捕获即把谓词字段值写进 `description`。按可访问性分两类：

| 类 | 判定 | 例子 |
|---|---|---|
| A | 顶层 public record / public 访问器 → 直接解构 | `EnchantmentActiveCheck`、`BonusLevelTableCondition`、`ValueCheckCondition`、`LightPredicate`、`BlockPredicate`、`FluidPredicate`、`LocationPredicate`、`EntityPredicate`、`ItemPredicate`、`DamageSourcePredicate`、`FishingHookPredicate` |
| B | 嵌套成员未加 public → 成员不可访问，只能 `CODEC.encodeStart` 回读 JSON | `LocationPredicate.PositionPredicate`（`static record`，无修饰符）、`StatePropertiesPredicate.PropertyMatcher` / `ValueMatcher`（同上）、`EntitySubPredicates.*Instance` |

B 类的存在有现成证据：`describeStateProperties`（`:349`）是全类唯一的"编码回读"实现。

**第一批（成本≈一行，语义直接补全）**

| 条件 | 字段 | 原版事实 | 现状文案 |
|---|---|---|---|
| `enchantment_active_check` | `active` (boolean) | record 只有一个布尔字段 | 「需要激活特定附魔」（不分真假） |
| `value_check` | `range` (IntRange) + `provider` 类型 | `ValueCheckCondition(NumberProvider, IntRange)` | 「需要值在范围内」；`range` 可复用现成的 `describeRange`（`:304`）。`provider` 类型是诊断信息：`enchantment_level` 提供器会让整表不可模拟 |
| `table_bonus` | `enchantment` + `values` (List&lt;Float&gt;) | 语义 = `values.get(min(level, size-1))` 掷概率 | 「受附魔等级影响」。取值形态与 `ModLootConditions` 里已有的 `tool_enchantment_chance_linear` 同构，可提为公共方法 |
| `random_chance_with_enchanted_bonus` | `enchantedChance` (LevelBasedValue) + `enchantment` | record 三字段 | 「随机概率 (基础): %s%%」，只展示 unenchanted 且标 `partial` |

**第二批（中等成本）**

| 条件 | 字段 | 说明 |
|---|---|---|
| `location_check` → `light` | `LightPredicate.composite` (MinMaxBounds.Ints) | 一个整数区间，可直接展示 |
| `location_check` → `block` / `fluid` | `blocks`+`state` / `fluids`+`state` | `state` 可复用 `describeStateProperties`；会与 `block_state_property` 的展示重复，需先定是否统一文案 |
| `location_check` → `position` | `x`/`y`/`z` (MinMaxBounds.Doubles) | B 类。y 区间（如 `y<0`）是最常被玩家理解的约束 |
| `entity_properties` → `type_specific` 其余 18 种 | `EntitySubPredicates` 共 19 种 | 现只认 `fishing_hook`（唯一 public record 带 `inOpenWater()`）。其余 18 种中 **14 种是"variant 列表"型**（axolotl/boat/fox/mooshroom/rabbit/horse/llama/villager/parrot/tropical_fish/painting/cat/frog/wolf），codec 均只有 `variant: HolderSet` 一个字段 → 一次实现覆盖 14 种。为 B 类 |
| `match_tool` → `count` | `MinMaxBounds.Ints` | 已由 `hasNonItemConstraints`（`:554`）算出用于判定 `partial`，值本身不展示 |

捕获的一致性要求：新增展示形态必须同步 `en_us.json` 与 `zh_cn.json`；捕获完整后应摘掉 `partial`。

### 3.3 需留意的耦合点

`ScenarioLabel` 的折叠契约依赖 `description` 的 i18n key 后缀：`keySuffix`（`:143`）截取 `condition.` 前缀后的部分，与 `GROUPING_PARENTS`（`:37`，含 `location_check`/`weather_check`/`damage_source_properties`/`all_of`/`any_of`）比对，决定"父行只描述类别且恰好一个子行"时是否折叠。因此：

- 上述**父行**描述的 key 若改动，会静默改变场景名的折叠行为；
- 反向收益：`location_check` 只带 `position` 时会被折叠，场景名取的就是子行文本，捕获 `position` 会同时改善场景名。

---

## 四、可以新增

| 编号 | 项 | 内容 | 规模/边界 |
|---|---|---|---|
| N1 | 未知条件的提示通道 | 让 `PathHintAnalyzer.collect`（`:231`）对未知条件也产出一条可陈述项，使网格在只被第三方条件挡住时报「需要条件」而非「未命中」（修 D4）。需先定口径：未知条件算不算"可陈述的原因"，以及"所有未知条件都算"是否会把无关条件一并列进提示 | 影响 `collect` 与 `assembleHints`（`:179`）的去重与排序 |
| N2 | 服务端诊断告警 | 一次性告警"表 X 含未识别条件类型 Y"，按类型去重（可参考 `SimulationScenarioPlanner` 的 `WARNED_UNSTABLE_TYPES`/`WARNED_BUDGET_TABLES` 做法）。当前 `LootConditionHandlers` 无 logger，需新增 | 十行级 |
| N3 | 第三方条件的轻量扩展点候选设计 | 例如按条件 id 提供描述文案，或允许配置声明。现状只有 mod 内部的 `register`，第三方必须依赖本模组 | 设计层，涉及 `common/` 与平台层边界 |
| N4 | 捕获所需的 i18n 键 | 3.2 各项若实施，每项需新增 `condition.*` 键（含带参数的形态） | 与实现同批，两份 lang |
| N5 | 谓词字段的实测分布 | 收录范围内的表实际用到哪些谓词字段（一次性统计扫条件树）。当前"低收益"的判断依据是收录范围（§5.1 排除 `entities/`、`blocks/` 前缀），而非实测分布 | 一次性脚本 |
| N6 | 未识别条件的模组归属 | 在「未识别：&lt;id&gt;」上附加模组名（经 `IPlatformHelper.getModDisplayName` 解析 id 的命名空间）。见 7.2 的三条限制；`common/` 不得 import 平台类 | 数行 + 平台服务调用 |

---

## 五、明确不捕获的谓词（附理由）

| 谓词 | 理由 |
|---|---|
| `NbtPredicate`（entity `nbt`、`BlockPredicate.nbt`） | 内容不可读、长度不可控，属实例态数据 |
| `ItemPredicate.components`、`ItemSubPredicates` 的 14 种 | 组件内容做展示产生噪音。**例外待评估**：`enchantments`/`stored_enchantments` 若被 `match_tool` 用于表达工具附魔门槛，与 `ToolEnchantmentCondition` 语义重叠 |
| `EntityPredicate` 的 `periodicTick`/`team`/`movement`(7 分量)/`distance`(5 分量)/`vehicle`/`passenger`/`targetedEntity` | 语境罕见（主要在进度与实体表），而收录范围排除实体与方块掉落表；递归字段还会让条件树深度爆炸 |
| `EntityFlagsPredicate`(7 布尔)、`EntityEquipmentPredicate`(7 槽位)、`MobEffectsPredicate`、`SlotsPredicate` | 字段本身可读，但同上：实体掉落表不在收录范围内 |
| `DamageSourcePredicate.directEntity`/`sourceEntity` | 嵌套整个 `EntityPredicate`，成本高；且与 `killed_by_player` 语义重复 |

---

## 六、未验证的前提（影响排序，验证成本低）

1. **B 类是否真的不可解构**：依据 JLS 6.6.1 与 `describeStateProperties` 的现有实现推断，未编译验证。若实际可编译，B 类成本大幅下降，3.2 第二梯队排序会变。
2. **收录范围内的实际谓词分布**：见 N5。第一节与第五节的"罕见/低收益"依据是收录范围规则，非实测。

---

## 七、条件类型的注册表与模组归属

### 7.1 条件"单词"在哪个注册表

| 层级 | 注册表 | 装载方式 | 原版 id 形态 |
|---|---|---|---|
| 条件类型（条件对象的 `"condition"` 字段） | `BuiltInRegistries.LOOT_CONDITION_TYPE`（`BuiltInRegistries.java:183-184`，`registerSimple`） | **静态内置，数据包不可添加** | `minecraft:random_chance` 等 19 个（`LootItemConditions` 全部用 `ResourceLocation.withDefaultNamespace`） |
| 实体子谓词类型（`type_specific`） | `BuiltInRegistries.ENTITY_SUB_PREDICATE_TYPE`（`BuiltInRegistries.java:265-266`，`registerSimple`） | 同上 | `minecraft:fishing_hook` 等 19 个 |
| 物品子谓词类型（`predicates`） | `BuiltInRegistries.ITEM_SUB_PREDICATE_TYPE`（`BuiltInRegistries.java:268-269`，`registerSimple`） | 同上 | `minecraft:enchantments` 等 14 个 |
| 条件**实例**（`minecraft:reference` 的 `name`） | `Registries.PREDICATE`，由 `LootDataType.PREDICATE`（`LootDataType.java:21-22`）承载，与 `MODIFIER` / `TABLE` 同属 loot 数据包注册表（`LootDataType.java:41`） | **数据包可添加**，路径 `data/<ns>/predicate/*.json` | 命名空间属于数据包或模组，二者都可能 |

- 判别字段名不同：条件对象里是 `"condition"`，`loot_table` entry 里是 `"type"`。项目代码已按此区分（`LootParseUtil:62`、`:76` 传 `"condition"`）。
- "原版条件单词在注册表中"成立，但它是 **`ResourceLocation` 的 path**，命名空间恒为 `minecraft`。
- 数据包**不能**新增条件类型；能新增的只有条件**实例**（`reference` 的目标）。
- 确定性依据：`RegistryDataLoader` 的 `WORLDGEN_REGISTRIES`(25 项) / `DIMENSION_REGISTRIES`(1 项) / `SYNCHRONIZED_REGISTRIES`(11 项) 均**不含** `LOOT_CONDITION_TYPE`。

### 7.2 能否得知非原版条件是哪个模组添加的

**能，唯一信号是 id 的命名空间**，且项目已有现成抽象：`IPlatformHelper.getModDisplayName(String modId)`（`IPlatformHelper:12`）——Fabric 走 `FabricLoader.getModContainer(id).getMetadata().getName()`（`FabricPlatformHelper:22-27`），NeoForge 走 `ModList.getModContainerById(id).getModInfo().getDisplayName()`（`NeoForgePlatformHelper:22-27`），查不到时回退为传入字面量。

| 限制 | 说明 |
|---|---|
| 命名空间 ≠ mod id 只是惯例 | 两个查找入口均按 **mod id** 查询。模组若使用与 modid 不同的命名空间（`c`、`neoforge`、自定义）则查不到，回退为命名空间字面量。加载器层面没有"按注册表对象反查所属模组"的 API，命名空间是唯一入口 |
| 数据包命名空间不是模组 | `data/<ns>/predicate/*.json` 定义的条件实例及其被 `reference` 指向的 `ns`，与任何 mod container 无关；查不到是正确答案 |
| 映射不唯一 | 一个模组可拥有多个命名空间；同一命名空间理论上可被多个来源使用 |

**当前状态**：能力已具备（`IPlatformHelper` 两端均实现）但**未接入**条件展示——`LootConditionHandlers.fallbackInfo`（`:276-283`）只拼原始 id，文案「未识别：%s」。接入时 `common/` 不得 import 平台类，须经平台服务。

---

## 八、相关文档

- [战利品表系统](../dev/loottable.md) — 机制权威描述（§7.2 场景策略、§7.4 缓存与"已知残余"、§9 自定义条件、§10 扩展点）
- [客户端与 GUI](../dev/client-ui.md) — 条件树渲染与保真度三态
- [网络与同步](../dev/network.md) — 目录同步协议
