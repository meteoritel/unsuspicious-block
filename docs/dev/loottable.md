# 战利品表系统

本文档描述 `loottable/` 包的架构：如何解析、收录、注入战利品表，如何为物品生成稳定签名，以及如何通过模拟抽取估算概率。这是考古笔记目录的底层支撑。

## 1. 职责概述

`loottable/` 包不直接管理玩家进度，而是提供五个底层能力：

1. **目录数据结构**：定义 `TableDefinition` / `ItemDefinition` 等跨模块共享的记录类型。
2. **签名机制**：为战利品结果生成稳定签名，用于进度匹配与持久化。
3. **收录范围匹配**：把配置规则解析为可匹配的模式，判断哪些战利品表被纳入目录。
4. **概率模拟**：**按模拟输入（条件赋值 + 参数旋钮）**执行模拟抽取，统计物品出现概率。启动只算每表的基准输入，其余输入由玩家按需触发，结果按 `(表哈希, 输入键)` 全服共享缓存。
5. **战利品注入**：平台相关的注入钩子，确保模组注入的物品被纳入概率统计。

## 2. 子包结构

```
loottable/
├── source/       单轮重载的资源快照
│   └── LootTableSourceSnapshot  一次列举拿到全表"有效原文 + 完整资源栈"，解析/建图/哈希共用
├── graph/        引用关系权威
│   ├── LootTableReferenceGraph  直接子表 / 可达集 / 子树 / 强连通环 / 子树摘要
│   ├── LootTableEdge            边类型：JSON_REFERENCE 参与静态语义，RUNTIME_INJECTION 只参与结构
│   └── RuntimeLootLinks         平台注入关系与"钓鱼上下文"判定的唯一来源
├── analysis/     战利品表 JSON 编译
│   ├── LootTableCompiler        单表 JSON → 上下文无关的编译产物
│   ├── CompiledLootTable        事件流：本表直接物品路径 + 引用位置（保序、不去重）
│   ├── LuckSpec                 条目/池里与幸运有关的原始数值（weight/quality/rolls/bonus_rolls）
│   ├── LuckGateAnalysis          逐路径最小幸运门槛（对齐 0.01 网格 + 真实公式回验）
│   ├── LuckGate                 单条路径的门槛结论（不可达 / 数值门槛 / 区间受限）
│   ├── LootConditionHandler(s|Info)  战利品条件分析与描述
│   ├── LootFunctionHandler(s)      战利品函数分析
│   └── LootParseUtil               条件分析、函数链拼接与类型规范化的唯一实现
├── catalog/      读模型与查询
│   ├── LootTableCatalog         跨模块共享的记录类型
│   ├── Probability              概率值类型（未知 / 不可达 / 已测量 / 需要条件）
│   ├── DeclaredChance           数据表声明的 random_chance 触发率区间（网格“触发率”的唯一来源）
│   ├── LootTableProjector       图 + 编译产物 → 静态投影
│   ├── StaticTableProjection    静态展平物品路径与子表入口（概率为占位）
│   ├── ItemDefinitionAccumulator 同签名多路径合并的唯一实现
│   ├── CatalogQueryIndex        子树物品等跨表聚合的唯一入口
│   ├── CatalogTableDto          网络形态：场景假设与每表内容哈希
│   ├── LootTablePattern            收录规则解析与匹配
│   ├── LootTableNames              表名/本地化 key 工具
│   ├── LootTableTranslationStore      服务端整合包级补充语言存储
│   ├── MissingTranslationKeyExporter  游戏资源与服务端配置的缺失 key 诊断快照
│   └── (journal/catalog/ 的三个合作者：ArchaeologyJournalCatalog 组装静态读模型，
│        LootTableAnalysisSession 承载一代分析结果，CatalogGeneration 原子发布整代目录)
├── signature/    结果签名
│   ├── LootResultSignature         签名 record（核心）
│   ├── LootResultMatcher           运行时掉落与候选签名匹配
│   ├── LootResultPreviewCache      签名 → 预览栈的进程内缓存（玩家侧匹配共用）
│   └── LootCounts                  计数工具
├── simulation/   概率模拟
│   ├── SimulationInput            模拟输入身份 = 条件布尔赋值 + 参数旋钮
│   ├── ScenarioParams             参数旋钮（幸运 / 工具基座 / 工具附魔等级 / 抽样次数）
│   ├── SimulationInputKey         输入 ↔ 规范字符串（缓存键、网络标识、参数组合分组）
│   ├── SimulationConstraintCatalog 目录签发的约束描述（**不枚举候选输入**）
│   ├── ToolOption                 签发的工具基座（基座物品 + 谓词原文）
│   ├── LootProbabilitySimulator    模拟引擎（按输入身份创建任务）
│   ├── LootProbabilitySimulationJob 可续跑的**单输入**模拟状态
│   ├── SimulationMeasurement       一次模拟的原始测量值（不含派生展示结论）
│   ├── LootProbabilitySimulationWorker  主线程 tick 驱动器（按 (表, 输入) 去重）
│   ├── SimulationProfile             模拟工具/方块/实体参数与条件资格场景
│   ├── SimulationScenarioPlanner     从获取路径规划代表场景（≤32，带展开预算）
│   ├── SimulationScenario            单个自洽条件场景
│   ├── SimulationCompositeConditionAccess 复合条件求值访问
│   ├── LootConditionFingerprint      解析期与运行时条件指纹
│   ├── LootSimulationScope           主线程模拟期间的 profile 作用域
│   ├── LootContextParamFiller      模拟用 LootParams 构建（宽松回退）
│   ├── SimulationFakePlayer        模拟用假玩家
│   ├── SimulationFishingHook       模拟用假钓鱼浮标（钓鱼表 THIS_ENTITY）
│   ├── PathHintAnalyzer            静态信息性提示 + 基准值→展示值派生
│   └── ProbabilityFormat           概率值与声明触发率 → 展示文本（唯一格式化入口，仅 UI 边界调用）
├── injection/    战利品注入
│   ├── ArchaeologyLootInjector     注入器接口
│   └── ArchaeologyLootInjectors    全局注册器（单例）
└── condition/    自定义战利品条件
    ├── ModLootConditions           条件类型注册与展示描述
    └── ToolEnchantmentCondition    工具附魔门槛 + 可选按等级概率（唯一自定义条件）
```

解析与追踪按**四层管线**组织，每层只依赖上一层：

```
ResourceManager
   └─(每次重载只读一次)→ LootTableSourceSnapshot
          ├─→ LootTableReferenceGraph ─→ 闭包 / SCC / 子树摘要 / 分型边
          └─→ CompiledLootTable ──────→ LootTableProjector → StaticTableProjection
                                            ↑                       │
                        LootTableReferenceGraph ────────────────────┘

StaticTableProjection ─→ 概率模拟 / 缓存恢复 ─→ 模拟结果 → CatalogTableDto
```

- **快照**：全表有效原文（编译用，最高优先级层）与完整资源栈（哈希用），`JsonElement` 按需重解析。
- **图**：引用关系唯一权威；`loot_table` 类型识别、环检测、闭包、子树摘要都从这里出。
- **编译**：单表**上下文无关**的局部语义。同一个子表在不同引用位置产出的物品路径不同（条件与函数被引用位置改写），因此编译产物只记"本表直接物品路径 + 引用位置"，绝不缓存展开后的子表结果；item tag 在编译期展开为具体物品。
- **投影**：链接期把父表的继承条件与函数链拼到子表语义上，复现首跳归属、条件继承顺序、函数继承与近似降级三条语义。

## 3. 目录数据结构

[`LootTableCatalog`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/LootTableCatalog.java) 定义跨模块共享的记录类型，供 `journal`、`network`、`client`、`command` 等包统一引用：

```
TableDefinition{
  id: ResourceLocation,          表 ID
  displayName: Component,        展示名
  type: String,                  声明类型
  items: List<ItemDefinition>,   物品条目
  simulationCount: int,          模拟次数（0 表示未模拟）
  childTables: List<ResourceLocation>, 引用的子表（只含真正产出物品的表）
  childTableProbabilities: List       子表至少产出一个物品的摘要与分场景概率
}

ItemDefinition{
  id, displayName, tooltipHint,
  probability: Probability,      概率值（未知 / 不可达 / 已测量）
  signature: LootResultSignature,物品签名
  acquisitionPaths: List<LootAcquisitionPath>,  获取路径
  injected: boolean,             是否模拟期发现的注入条目
  scenarioProbabilities: List    各自洽条件场景下的至少出现一次概率
}

LootAcquisitionPath{
  sourceChildTable,        来自哪个子表
  sourceItemTag,           来自哪个 item tag
  entryConditions,         条目自身条件
  inheritedConditions,     继承自 pool/组合 entry/表引用的条件
  functionUncertainty,     函数求值等级——与条件树分开，只描述函数（见 6.1）
  luckAffected             本路径所在池是否受幸运影响（见 7.1）
}
```

`acquisitionPaths` 记录一个物品在表中的所有获取路径（可能来自不同 pool、不同子表、不同条件），用于在 UI 中展示"如何获得"。`injected=true` 表示该条目不在原始 JSON 中，而是模拟期由 GLM 或 LootTableEvents.MODIFY 注入发现的。`sourceChildTable` 只记**第一跳**：从根表观察时孙表的条件汇总回直接子表，父表页签的归属才不会错位。

### 3.1 概率值类型

概率**不是**字符串。[`Probability`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/Probability.java) 是 sealed 值类型，**四个**互斥状态取代了原先共用一个字符串的五种语义：

| 状态 | 含义 | 展示文本 |
|---|---|---|
| `Unknown(reason)` | 没有可展示的测量值，成因见 `UnknownReason`（**不是**不可达） | `?` |
| `Unreachable` | 静态可证明在任何可表示的输入下都不可产出 | `0%` |
| `Measured(lower, upper?)` | 实际抽样比例；`upper` 非空表示跨场景区间 | `12%` / `3%-7%` |
| `NeedsCondition(hints)` | 当前输入下没有可用路径，但路径引用了可调整的旋钮或条件 | 「需要条件」 |

- `UnknownReason ∈ { UNCOVERED, UNPARSED, NOT_SIMULATED, SIMULATION_FAILED, EVICTED }`。网格一律 `?`，由 tooltip 逐条分述——这正是"把不同来源的结论塞进同一个值"（D2）的反面。
- 构造即校验有限数值与 `0 ≤ lower ≤ upper ≤ 1`，非法值在构造点抛出，不会流到 UI 变成乱码。
- **抽样零命中是 `Measured(0.0)`，展示为「未命中」**；`0%` 只留给静态可证明的不可达。旧写法 `<0.01%` 已作废——它等于声称 p 小于抽样分辨率，而 n=10000 时真实概率 0.01% 仍有约 36.8% 的概率零命中。
- 可适用性（`NeedsCondition` / `Unreachable` / `Unknown(UNCOVERED)`）与计算状态（`Measured` / `Unknown(NOT_SIMULATED / SIMULATION_FAILED / EVICTED)`）是**两个轴**，由服务端派生；客户端只按显示优先级链渲染（可适用性状态 → 可展示时的声明触发率 → 模拟值）。
- **落盘用窄类型** [`SimulatedValue`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/SimulatedValue.java)（只有 `Unknown` / `Measured`）：存档里只放测量事实，派生结论一律在读取时按当前输入与静态结构重新推导。
- **格式化只发生在 UI 边界**（`ProbabilityFormat.formatComponent` / `formatNumeric` / `describeUnknown` / `describePathHints`）。数据层、存档缓存、网络与排序一律用数值，不存在"把界面文本反解回数值来排序"的做法。
- 服务端内部保留 `lowerBound()` / `upperBound()` 访问器用于排序与聚合；不统计产量期望，也不持久化展示文本。

## 4. 签名机制

[`LootResultSignature`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/signature/LootResultSignature.java) 是进度匹配与持久化的核心--它让"目录中的物品"与"玩家实际获得的物品"能对应起来。

### 4.1 签名类型

```
record LootResultSignature(ResourceLocation itemId, SignatureType type, @Nullable String data)
```

| SignatureType | 含义 | 构造场景 |
|---|---|---|
| `PLAIN` | 普通物品，无组件差异 | 无特殊组件的物品 |
| `COMPONENT_EXACT` | 严格组件签名 | 有 DataComponentPatch 的物品（编码为 JSON 字符串） |
| `ENCHANTED_APPROX` | 附魔近似 | 附魔物品（不区分具体附魔，避免签名爆炸） |
| `ENCHANTED_RANDOM` / `ENCHANTED_LEVEL` | 旧版附魔签名 | 仅作兼容别名，加载时折叠为 `ENCHANTED_APPROX` |
| `APPROX_ITEM_ONLY` | 近似回退 | 组件编码失败等异常情况 |

**实例态组件排除**：签名派生时，若物品的组件补丁含配置声明的**实例态组件**
（`ILootTableConfig.getSignatureExcludedComponents()`，默认 `relics:data`），该物品直接退化为 `PLAIN`（物品级）签名。
这类组件随物品实例随机化或随玩家进度变化，不属于战利品定义。以 Relics 为例：`relics:data` 是所有 `IRelicItem`
的**默认组件**（初值 `RelicComponent.EMPTY`，即未解析态），而该模组在 `Item.verifyComponentsAfterLoad` 里
"读取即物化"地用未播种随机数写回品质，`ItemStack` 构造、`copy()`、`applyComponents*` 都会触发它——
把这种组件编进签名等于把 RNG 结果当物品身份，同一件饰品会随掉落次数无限增生条目（实测冰窖表 1802 条里 1796 条
是两件饰品的伪变体，使该表完成度不可达）。

必须落 `PLAIN`、而不是"剔除该组件后仍编 `COMPONENT_EXACT`"：匹配比较（`isSameItemSameComponents`）与候选索引
哈希（`hashItemAndComponents`）读的都是**未剔除**的组件，两侧不对称会让这类条目永远匹配不上、索引也永远查不到桶。
`PLAIN` 匹配只比物品 id，不碰组件，且与静态路径产生的 `PLAIN` 共用同一个存储键，因此同一物品不会出现两条条目。
代价是不再按随机变体分别收集——与附魔折叠为 `ENCHANTED_APPROX`（"避免签名爆炸"）是同一取舍。

### 4.2 稳定存储键

`toStoredKey()` 生成稳定字符串，用于 NBT 持久化、网络传输与 Map key：

```
usb_sig|TYPE|itemId|base64(data)
```

`fromStoredKey()` 反向解析，并**兼容旧版**：不以 `usb_sig|` 开头的 key 视为旧版仅存 item id 的格式，转为 `PLAIN` 签名。这保证了玩家旧存档不丢失。

### 4.3 附魔判定的陷阱

`isActuallyEnchanted(stack)` 不能用 `stack.has(ENCHANTMENTS)`，因为工具/武器默认带有**空的** `ENCHANTMENTS` 组件，`has()` 会误返回 true。正确做法是检查 `stack.isEnchanted()` 或 `STORED_ENCHANTMENTS` 非空（附魔书场景）。

### 4.4 预览栈

`createPreviewStack()` 根据签名重建用于 UI 展示或精确匹配的物品栈：`COMPONENT_EXACT` 应用组件 patch，附魔变体设置附魔光效覆盖。

`COMPONENT_EXACT` 的预览栈需要把签名的 `data` 当 JSON 解码再套用组件补丁（`data` 本身不是 base64，base64 只出现在 `toStoredKey` 的存储键里，见第 4.2 节），成本远高于其它类型的匹配。因此
[`LootResultPreviewCache`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/signature/LootResultPreviewCache.java)
按签名内容缓存预览栈——预览栈由签名值唯一决定，缓存结果永远有效、不需要失效策略；玩家侧的掉落实时匹配
（掉落追踪、容器已追踪状态、菜单快照三条来源）都通过 `LootResultPreviewCache.PROVIDER` 取用，避免每次掉落、每个槽位重复解码。
缓存只在数据包重载时清空以限制内存。概率模拟热路径不共用它：单表模拟自带 `HashMap` 缓存，单线程且无并发开销，更快。
含实例态组件的物品已退化为 `PLAIN`（见 4.1），其预览栈是裸物品栈、不套用任何组件补丁，也不再需要预览参与精确匹配。

## 5. 收录范围匹配

[`LootTablePattern`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/LootTablePattern.java) 把配置字符串解析为可匹配的规则：

| 语法 | 含义 | 示例 |
|---|---|---|
| `namespace:path` | 限定命名空间 | `minecraft:gameplay/fishing` |
| `path`（裸） | 匹配所有命名空间 | `archaeology/` |
| path 以 `/` 结尾 | 前缀匹配 | `archaeology/` 命中所有考古表 |
| path 不以 `/` 结尾 | 精确匹配 | `minecraft:chests/buried_treasure` |

默认规则定义在 [`ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/ILootTableConfig.java)：

```java
List.of(
    "archaeology/", "archeology/", "pots/",
    "minecraft:gameplay/fishing",
    "minecraft:chests/buried_treasure",
    "minecraft:chests/ancient_city", "minecraft:chests/ancient_city_ice_box",
    "unsuspiciousblock:gameplay/fishing/",
    "unsuspiciousblock:gameplay/fossil_hunter/",
    "unsuspiciousblock:gameplay/panning/"
)
```

命中规则的表被纳入目录，并通过引用闭包递归发现子表（见 [`docs/journal-categories.md`](../journal-categories.md) 的"收录根表"与"引用闭包"概念）。`stripFrom` 剥离命中前缀，用于生成本地化 key 与展示名。

### 5.1 可视化追踪管理

> 本节与 5.2 是追踪管理页与自定义表名机制的**权威描述**：客户端页面交互见 [客户端与 GUI](client-ui.md) 第 4.2 节，payload 流转见 [网络与同步](network.md) 第 8.6 节，配置文件位置见 [配置与第三方联动](config-integrations.md) 第 2.6 节。

管理页面的候选集合以服务端 `ReloadableServerRegistries` 中 `Registries.LOOT_TABLE` 的 key 为唯一权威，不读取客户端资源列表，也不猜测不存在的表。当前明确排除 path 以 `entities/`、`blocks/` 开头的实体和方块掉落表。

追踪规则仍支持前缀和精确 ID。为允许从宽泛前缀中移除单表，`excluded_loot_tables` 保存精确排除项：关闭一张被前缀命中的表时加入排除项；重新开启时移除排除项，若原规则未命中则把精确 ID 加入追踪规则。修改权限要求服务端权限等级 2，变更后重建目录并广播最新管理快照。

### 5.2 自定义表名

名称 key 始终由 `LootTableNames.createTranslationKey(ResourceLocation)` 自动生成。游戏资源语言是正式、只读来源；服务端配置只补充资源中不存在的 key。服务端按标准语言 JSON 保存到 `config/unsuspiciousblock/loot_table_lang/<language>.json`，适合整合包作者直接分发，也便于开发者把确认后的条目复制回 `assets/unsuspiciousblock/lang/`。旧世界的 `serverconfig/unsuspiciousblock-loot-table-names.json` 会在每次加载时合并迁移（仅补充缺失 key，幂等；服务端启动与 `/reload` 均会触发），旧文件保留。

客户端收到的服务端名称只驻留当前连接的内存，断开连接时清除，不再写入客户端全局配置。`ClientLootTableLanguageStore` 扫描当前游戏资源栈的语言 JSON，记录最终提供 key 的 Resource Pack ID；`ClientLanguageMixin` 仅在同语言资源缺少 key 时合并服务端补充值。因此资源值优先、只读，服务端配置不会覆盖模组或 Resource Pack 已提供的正式翻译。

管理页面以 `(languageCode, tableId)` 保存未提交草稿，切换表或语言不会丢失。一次“应用更改”通过批量 payload 提交全部草稿；服务端整体校验后每种受影响语言只写盘一次、广播一次。客户端语言查询直接读取新的内存快照，无需重载资源。管理员还可选择本地标准语言 JSON，预览新增、更新、资源跳过、未匹配与非法条目后批量导入。只有当前服务端已注册且可管理、并由自动规则构造的 key 会进入提交。

缺失 key 的检测与告警仍采用批处理，但只读取当前游戏资源和服务端补充配置，不再自动生成客户端覆盖文件。已有模组资源翻译不会被误判为缺失。

## 6. 解析流程

[`ArchaeologyJournalCatalog.load`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/catalog/ArchaeologyJournalCatalog.java)（在 `journal/catalog/` 包）按四层管线构建目录：

```
capture 快照 → build 引用图 → 在追踪根初始可达集内算 SCC 排除集 → compile 闭包内每张表
             → project 每张表 → 组装静态读模型与分类结构 → 算每表哈希 → 原子发布
```

- **快照**（`LootTableSourceSnapshot.capture`）：一次 `listMatchingResourceStacks` 拿到全表。栈首是该表在本次重载下的**有效原文**（等价 `listMatchingResources` / `getResource`），整个栈用于哈希（等价 `getResourceStack`）；原文与 `JsonElement` 惰性读取并缓存。
- **图**（`LootTableReferenceGraph.build`）：有效 JSON 引用边 + 平台注入合成边。合成边施加**双端存在守卫**，目标已由 JSON 引用覆盖时不重复加边；邻接表按目标去重但保序。
- **环排除**：只对追踪根的初始可达集计算强连通分量，其成员被排除出收录闭包并输出一次性告警；与考古目录无关的第三方表循环不新增告警。
- **编译**（`LootTableCompiler`）：单表 JSON → 上下文无关的 `CompiledLootTable`。遇到 `loot_table` 引用只记 `ReferenceSite` 不跟随；`item` / `tag` 记物品路径，其中 **item tag 在编译期展开为具体物品**（签名按具体物品生成，存档缓存也按具体签名索引）。
- **投影**（`LootTableProjector`）：沿 JSON 引用把编译产物链接起来，复现三条语义——首跳子表归属、条件继承顺序（上层传入 → 本表内已继承 → 本事件自身）、函数继承与 `APPROX_ITEM_ONLY` 降级。引用位置按出现顺序逐个进入，**不去重也不合并**：同一子表在两处被引用且条件不同时两条获取路径都要保留。
- **读模型**：`StaticTableProjection` 展平出的 `TableDefinition` 只收真正产出物品的表（会话保留全部追踪表的投影，含空表）；无物品的表既不进目录也不作为子表入口。此阶段概率为 `Probability.unknown(UnknownReason.UNCOVERED)` 占位，等待模拟填充。本轮的会话、分类结构与每表哈希一同装进 `CatalogGeneration`，构建完成后整体原子发布（见 7.4 的重载一致性）。

`LootConditionHandler` / `LootFunctionHandler` 把原版条件/函数转译为可读的 `LootConditionInfo`；条件分析、函数链拼接与多路径合并各只有一份实现（`LootParseUtil`、`ItemDefinitionAccumulator`），保证编译路径与投影路径产出逐位一致的条件指纹与签名。

### 6.1 多路径物品提示

`ItemDefinitionAccumulator` 对同签名条目的非空提示按 `Component` 结构（包括翻译键与参数）去重，按路径首次出现顺序保留，用本地化的 `alternative_separator` 拼接；不使用已翻译文本判断相等。展示名的分歧判断也使用组件结构。空提示不抹去其他路径的已知提示。

各路径区间相同时只出现一次——内置河流淘洗的金粒两条路径同为 `数量: 1-3`，因此不出现分隔符；区间不同时按路径出现顺序拼接，形如 `数量: 1-3 / 数量: 3-6`。这些是各路径的函数效果，并非整张表一次抽取的总产量，也不表示各路径一定同时触发。`uniform` 两端相等时显示 `数量: N`。

提示仍以单个 `Component` 经过目录同步协议传输，客户端只展示；目录哈希已包含提示组件，因此提示变更会触发目录更新。常量 `count` 静态应用成功后不产生额外提示的限制仍保留。

`LootAcquisitionPath.functionUncertainty` 单独记录函数求值等级。`set_count` 的已知常量或等端点 `uniform` 为 `NONE`，常量端点的非退化 `uniform` 为 `PROBABILISTIC`，动态数量来源和未识别函数保守为 `RUNTIME`。投影器对函数链取最保守等级，条目再与真实条件树合并；因此已知随机数量不再单独触发“条件概率”。物品签名及其存储键保持兼容，既有发现记录无需迁移。

## 7. 概率模拟

模拟的输入身份是 [`SimulationInput`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/SimulationInput.java)
= **场景身份** + 条件布尔赋值 + [`ScenarioParams`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/ScenarioParams.java)
（幸运 / 工具基座 / 工具附魔等级 / 抽样次数）。缓存键是 `(表哈希, SimulationInputKey)`，
因此”同一张表换个幸运值”是新增一条缓存，而不是覆盖旧的一条。

**场景身份是稳定字面量，不编码条件指纹**（这一点是缓存能否命中的关键）。基准场景恒为
`baseline`，其余为 `scene-1`、`scene-2`…（序号＝规划器的发射顺序）。条件指纹**仍然是场景的运行时语义**
（`SimulationProfile.conditionOutcomes`，模拟时按运行时指纹查表回答条件成立与否），但它**不再进键**：
指纹由条件对象的 `toString()` 派生，`entity_properties` / `location_check` 这类未按字段值生成文本的类型
会退化成 `类名@identityHash`，跨 JVM 运行不重复，于是同一个场景每次启动都会换一个新键——
读路径永远找不到旧的，写路径又不断新增，而 LRU 按参数组合计数，这些孤儿键既不会被淘汰也不会被覆盖，
在存档里无限累积。缓存键的形态因此是：

```text
scenario=baseline|luck=0.00|tool=minecraft:diamond_pickaxe|ench=|n=10000
```

### 7.1 模拟引擎

[`LootProbabilitySimulator`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/LootProbabilitySimulator.java)
对**单个输入**执行模拟抽取：

```
simulateOne/createJob(tableId, rawTable, level, input, scenario)
  ├─ 从 ReloadableServerRegistries 取得数据包重载后的运行时 LootTable
  │    保留 Fabric LootTableEvents.MODIFY 等加载期注入；不从原始 JSON 重建表
  ├─ 合成 profile = 场景条件赋值 + 输入参数（ScenarioParams.applyTo）
  │    SimulationProfile 提供 TOOL、BLOCK_STATE、DAMAGE_SOURCE、ORIGIN 等值
  │    钓鱼上下文（由表**声明的 type** 判定，不看表 id 的 path）的 THIS_ENTITY 用
  │    SimulationFishingHook 填充，使 entity_properties + fishing_hook + in_open_water
  │    条件在模拟中可判定。
  │    填充规则：只填该 paramSet `allowed` 内的参数，required 与 optional 都填——
  │    只填 required 会让 optional 参数缺失，把”没填”伪装成”条件不成立”。
  │    paramSet 不允许 ORIGIN（如 barter）时明确失败，这类表在构建期就被拦下，不走这里
  ├─ 初始化该场景适用的候选签名 + appearanceCounts
  └─ 循环 input.params().sampleCount() 次（默认 10000，档位见 7.2）：
       ├─ lootTable.getRandomItems(lootParams)
       ├─ ArchaeologyLootInjectors.get().maybeReplace(tableId, drops, random)
       │    Fabric 显式调用注入器；NeoForge 空实现（GLM 已在 getRandomItems 内部完成）
       ├─ 对每个 drop：
       │    ├─ LootResultMatcher.resolve(stack, candidates) 匹配已知签名
       │    └─ 未匹配 -> deriveSignature 派生签名（附魔折叠为近似，其他组件严格保留）
       │         若已是已知签名 -> 保守跳过（歧义掉落）
       │         否则登记为注入条目（injected=true）
       └─ 对本轮出现的签名去重后计数
```

物品概率口径是“单次战利品表抽取中，该签名至少出现一次的概率”。同一轮返回多个相同签名的
`ItemStack` 时只计一次，不统计物品数量或平均产量。

模拟输出的不再是”填好展示概率的表”，而是原始测量值
[`SimulationMeasurement`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/SimulationMeasurement.java)：
按签名记录测量值、按直接子表记录”至少产出一个物品”的测量值，外加模拟期动态发现的签名与其来源。
**展示值由服务端当场派生**（见 7.4），因此”从缓存恢复”与”刚刚算完”走同一条代码，不存在两套口径。

幸运由 `LootParams.withLuck` 注入，取 `input.params().luck()`，不再是固定常量；基准输入的幸运为 0。
需要更高幸运阈值的条目会如实落到「需要条件」（见 7.5），而不是被伪造成一个数字。

编译器在每个 pool 检查 `bonus_rolls` 与所有候选（含组合 entry 子节点）的 `quality`：非零质量会改变同池
权重竞争，因此整个池标记为幸运敏感；无法证明恒零的奖励抽取 provider 也保守标记。该标记随引用位置
向子表传递，再写入 `LootAcquisitionPath.luckAffected`，独立于真实条件树、函数等级及物品签名。
物品和父表中的子表入口 tooltip 显示“随幸运变化”，不把它误写成“必须幸运才能获得”。此静态标记覆盖
JSON 的标准幸运机制；第三方运行时注入或自定义条件/函数暗中读取幸运时无法保证识别。

模拟热路径先按 `Item` 分组，再由 `LootResultMatcher.CandidateIndex` 对精确组件候选按
`ItemStack.hashItemAndComponents` 分桶。索引用**原签名解码并校验后的缓存预览**构建，查询用真实
产物的同一哈希；同桶内仍逐个执行 `ItemStack.isSameItemSameComponents`，不把哈希相等当作匹配。
普通/附魔近似/物品近似候选另存一个小列表，与命中的精确桶共用最高优先级和歧义状态，精确候选
有歧义时仍返回 null，不错误回退。候选在动态发现时增量加入，场景结束后释放当前场景索引；原始
候选回退也使用相同索引。玩家进度匹配仍可使用原 List 入口，两种入口共用判定逻辑。
哈希碰撞只增加同桶扫描成本，不改变结果；提供者的缓存预览在索引存活期间不得修改。

匹配使用局部状态，不为每次匹配创建排序 Map 或临时 List；每个候选缓存 stable key，并由带轮次标记的
原始计数器完成单轮去重，避免每轮重建 `HashSet` 节点和 `Integer` 装箱。上述优化不改变签名优先级、
歧义回退或“每轮最多计一次”的概率口径。

模拟必须调用普通 `LootTable.getRandomItems`：NeoForge 会在原始抽取结束后由该入口应用
Global Loot Modifier（GLM），而 `getRandomItemsRaw` 不会应用 GLM。嵌套表由原版 resolver
从同一个运行时注册表解析，且嵌套抽取使用 `getRandomItemsRaw`，因此 GLM 只在根表应用一次。

概率计算规则：

| 出现次数 | 条件 | 每个场景的概率值 |
|---|---|---|
| - | 该场景下没有可用路径 | `NeedsCondition`（引用了旋钮/条件）或 `Unknown(UNCOVERED)`（什么都没引用），**不是** `0%` |
| 0 | 有可用路径但抽样零命中 | `Measured(0.0)`（展示「未命中」，附当前抽样次数） |
| >0 | - | `Measured(appearances / 模拟次数)` |

**基准场景（条件全部不成立的那个）**决定网格上的数字：由 `PathHintAnalyzer.deriveDisplay` 从基准测量值 + 路径静态结构派生
`Measured` / `NeedsCondition` / `Unknown` 三选一，再叠加表级失败（`Unknown(UNPARSED / SIMULATION_FAILED / NOT_SIMULATED / EVICTED)`）。
其余场景只在场景列表与 tooltip 里出现。展示文本由 `ProbabilityFormat` 在 UI 边界生成。

模拟期才发现的动态条目同样按每个代表场景中的实际出现次数计算概率。由于这些条目没有可供静态
判定的获取路径，只保存实际观测到该签名的场景。动态条目中的非附魔 `DataComponentPatch` 使用
`COMPONENT_EXACT` 保存，确保药水等动态变体经过 SavedData 缓存和网络同步后仍能恢复；附魔结果
继续折叠为 `ENCHANTED_APPROX`。

组装动态条目目录时复用任务内已经解码的预览栈，并传入独占副本读取名称，避免再次 JSON 解码，
也避免第三方物品取名逻辑修改共享的匹配预览。展示名的空栈回退及 `Component.copy` 规则保持一致。

可选性能观测仅记录上述抽样的执行次数与耗时，不改变抽取次数、签名优先级、歧义处理、
单轮去重或动态条目发现。`componentExact` 对非空补丁执行 JSON 序列化；签名的 `data` 本身
不是 base64，只有 `toStoredKey` 对 data 做一次 base64。现有签名及存储键保持兼容。

### 7.2 条件场景策略

运行时表保留全部条件，不能再通过读取原始 JSON、删除条件并重新解码来生成模拟副本。那种做法会
绕过 Fabric 加载期修改，也无法可靠表达 `all_of`、`any_of`、`inverted` 等组合条件的语义。

`SimulationScenarioPlanner` 从每个 `LootAcquisitionPath` 提取**七类**资格条件（`SCENARIO_CONDITIONS`，
见下表；`match_tool` 已移出，理由见该行），为每条可达路径建立最小布尔赋值场景，并为每个场景算出
静态可达的签名集合与直接子表集合。**其中一个场景被标记为基准场景**（条件全部不成立的那个），
网格只读它——取跨场景最大值等于把"你站在沼泽里"那个数当成玩家的处境展示。解析阶段把
`simulation_fingerprint` 写入每个条件的 metadata；运行时 Mixin 用同一指纹查询 `SimulationProfile`
中的精确 true/false 结果，因此同类型的两个 `location_check` 不会混淆。

**场景数量的两条独立约束**（决策 14/33）：

- **硬上界 32**：候选按"覆盖的获取路径数"降序截断（覆盖更多条目的场景优先留下），被丢弃的数量如实
  上报（`ScenarioPlan.truncatedCount`），构建时逐表打进日志。**基准场景先占名额**——它由全假赋值产生、
  覆盖度天然最低，排序后会第一个被丢掉。
- **展开预算**：`requirementsFor` 对 `all_of` 走叉乘、对 `any_of` 累加，因此"`all_of` 里嵌多个 `any_of`"
  的条件树会在 32 的截断**之前**就指数膨胀——上界约束的是展开的*结果数量*，约束不了展开的*过程成本*。
  因此节点数与中间组合集合各有一个预算，超限后该表**整体**按"无约束"降级并告警一次。降级方向是保守的：
  条目保留在所有场景中，数值可能偏乐观，但不会把可达条目伪装成不可达。

每条路径的需求集合只计算一次，供"候选收集 / 覆盖度排序 / 适用性判定"三处共用。

**发射顺序必须可复现，因为它同时决定场景键**。场景键是缓存键的一段，顺序一变键就变。为此有两条约束：
候选集合按**路径枚举顺序**插入（来自解析后的 JSON，与运行无关）；覆盖度排序**只按覆盖路径数降序**，
并列时保持插入顺序（Java 的排序是稳定的），**不按指纹字符串破并列**——指纹跨运行不稳定，拿它当二级键
会让并列候选每次启动互换序号，带并列场景的表（例如原版 `minecraft:gameplay/fishing` 的群系条目，每条
恰好覆盖一条路径）因此每次启动都全量重算。

指纹取自条件对象的 `toString()`，因此**只对按字段值生成文本的类型成立**（record 即是）。解析阶段
同时写入 `simulation_fingerprint_stable`：识别出 `类名@identityHash` 这类默认实现时，该条件不参与
场景规划（按无约束处理，条目因此保留在各场景中而非被判成不可达）并告警一次——否则解析期与运行时
的两个实例必然算出不同指纹，场景覆盖会静默失效。运行时另有兜底：属于场景控制类型、却没有被任何
代表场景覆盖的条件，会在该表模拟完成时汇总告警，提示这部分数值是按真实逻辑求值得到的。

**这个稳定性判定只检查条件对象自身，看不进嵌套字段**——这是已知的缺口（未处理）。三条已核实的事实：

1. 解析期分析的是 `LootTableCompiler` 从 JSON 解出的**另一份实例**，与运行时
   `ReloadableServerRegistries` 里的对象不同，因此只有**整条** `toString` 链都是按值生成的类型才能复现指纹；
2. `isStableSource` 只问"最外层是不是 record / 是不是 `类名@identityHash`"，
   `LootItemEntityPropertyCondition`、`EntityPredicate`、`FishingHookPredicate`、`LocationPredicate`
   都是 record，一律被判定为稳定，嵌套的非 record 对象不参与判定；
3. `location_check` 带标签时必然不稳定：`LocationPredicate.biomes` 是 `Optional<HolderSet<Biome>>`，
   标签集 `HolderSet.Named` 的 `toString` 会逐个打印 `Holder.Reference`，而后者打印的是
   **它持有的值对象**（`"Reference{" + key + "=" + value + "}"`）——`Biome` 没有按值生成 `toString`，
   于是整条链落到 `类名@identityHash`。存档里这些表的键每次启动都不同，正与此一致。

后果是：这类条件在运行时查不到对应指纹，按**真实逻辑**求值并计入"未覆盖"汇总告警，数值口径因此与
场景估算不同。告警本身按 `条件 id + (简单类名)` 去重，所以"有 N 个"报的是**类型数**而不是条件条数，
不能读成"只有 N 条条件未覆盖"。

**该告警的常见来源不止一处**（`minecraft:gameplay/fishing` 重模拟时的实际输出依赖上面第 3 条）：
追加的泥地打捞注入场景有意只带 `location_check` 的类型默认值、**不带**基础场景的条件指纹表，好让注入
路径上的 `entity_properties`（`in_open_water`）交给真实逻辑（假浮标 `SimulationFishingHook`）判定——
若把基础场景的指纹表传进去，"默认"场景会把 `in_open_water` 归一到 `false`，反而把宝箱与泥地打捞的条目
强制判成不可达；同时，基础场景里**任何**指纹不稳的条件（如带标签的 `location_check`）也会计入同一条
告警。它只在该表**实际发生模拟**时输出（缓存命中时不会出现），无需处理。


| 条件 | 当前处理方式 |
|---|---|
| `match_tool` | **已移出场景控制类型**（决策 8）：工具由 profile 填充的真实 `TOOL` 求值，能否匹配交给 `ItemPredicate` 自己回答。此前它被伪造成布尔，于是"工具匹配"与"时运等级"互不相干——条件说匹配成功，而读真实 `TOOL` 的 `apply_bonus` / `table_bonus` 拿到的是无附魔镐，基础场景的时运曲线恒为 0 级。引用它的路径在基准（无附魔钻石镐 / 钓竿）下落到「需要条件」并指名"工具"旋钮 |
| `block_state_property` | profile 填充 `BLOCK_STATE`；条件门槛按匹配场景处理 |
| `damage_source_properties` | profile 填充 `DamageSource`；复杂伤害谓词按匹配场景处理 |
| `entity_properties` | 填充假玩家；钓鱼表的 THIS_ENTITY 使用 `SimulationFishingHook` |
| `location_check` | 场景按具体条件指纹分别取 true/false，不查找或生成真实区块 |
| `weather_check`、`time_check` | 场景按具体条件取值，不修改服务器天气或时间 |
| `entity_scores` | 场景按具体条件取值，不创建 objective、不写真实 scoreboard |

**注入场景的自带工具不被参数覆盖**：原版 fishing 的泥地打捞注入场景把"满级钓竿"写进了场景定义本身
（`SimulationScenario.keepBaseTool = true`），因为那条场景的全部意义就是带着能通过 `tool_enchantment`
门槛的工具去抽注入池。若用输入的默认工具覆盖它，注入池永远抽空、注入条目再也发现不了——那是信息丢失，
不是"参数生效"。因此这类场景只接受输入的幸运，工具保持场景自带的设定。

`LootSimulationScope` 通过 `ThreadLocal` 仅在当前主线程该输入的全部抽取期间暴露 profile，并由
`try-with-resources` 确保异常时清理。窄 Mixin 只把场景控制叶条件的 `test` 转交作用域；
`all_of` / `any_of` / `inverted` 始终由原版逻辑根据叶子结果求值，随机条件、权重和 rolls 不覆盖。

UI 继续递归展示 `LootConditionInfo` 条件树，并对工具/方块、群系/维度/结构、天气、时间、
实体目标、伤害来源与计分范围提供具体描述。条件展示带**保真度三态**，由服务端解析层在
`analyzeAll` 时以 metadata 给出（键 `analysis_fidelity`，见 `LootConditionHandlers`）：描述完整时不写该键，
只给出"成立但有未展示约束"的描述时标 `partial`，没有解析器或解析器失败时标 `unreadable`
（此时文案统一为"未解析条件：<条件注册名>"，保留 id 作为唯一的定位入口）。JSON 解码失败与条件对象
缺少分析器都保留 `unreadable` 节点；无法取得注册 id 时使用明确的占位 id。编译时按表和类型去重
告警，便于定位第三方条件。客户端只据该标记映射样式
（`partial` 与 `unreadable` 用斜体，`unreadable` 另用 `TooltipBuilder.CONDITION_UNREADABLE` 上色），
不推断条件语义——"解析只有一份实现"的边界不因此破开。同理，拿不到确定数值的概率条件一律承认是
动态值（`random_chance` 只在常量或 `uniform` 两端皆为常量时给出百分比），绝不展示编造的 `100%`。
`entity_properties` 同时展示已识别的实体类型与 `type_specific` 子谓词，未覆盖的其它字段仍标 `partial`；
状态属性的 `min`/`max` 区间显示为可读范围。`value_check`、`table_bonus`、
`enchantment_active_check` 和 `random_chance_with_enchanted_bonus` 已补充参数描述，动态或尚未完整解释的
值继续标 `partial`。

概率文本与颜色共用**一份**判定：`ItemDefinition.uncertaintyLevel()` 与 `hasConditions()` 读同一批数据
（全部获取路径的条件树 + 近似签名及函数分级），UI 不再在客户端另起规则、也不再用 tooltip 文案比较来识别近似条目
（那种做法会受语言差异影响）。等级只决定数值的**颜色**；状态词（`?`、「需要条件」「未命中」）一律由服务端派生的
`Probability` 决定，不再出现"颜色说不确定、概率却从不显示 `?`"或反过来的错配。存在场景条件时，概率文字明确标为
“条件满足时至少出现一次”，不是这些条件在自然游戏过程中的发生概率。

`ItemDefinition.probability` 是**服务端派生的当前输入展示状态**（`Probability` 四态，可适用性 × 计算状态两轴），
`ItemDefinition.scenarioProbabilities` 保存各代表场景的内部统计结果（**同一份参数**下逐个场景查缓存，
没算过的场景是 `Unknown(NOT_SIMULATED)`）。判定规则（`PathHintAnalyzer.deriveDisplay`）：

- 全部获取路径都被**逐路径幸运门槛**证明在任何可表示的幸运下拿不到 → `Unreachable`，网格 `0%`。
  这是唯一的静态不可达判据（有效权重恒为 0）；只有**所有**路径都如此才成立，任一条可达就不写 `0%`；
- 当前输入下适用且测到非零值 → 报测量值；适用但零命中且无路径提示 → `Measured(0.0)`，网格显示「未命中」；
- 当前输入下不适用或测值为零、但路径引用了可调整旋钮/场景条件 → `NeedsCondition`，网格显示「需要条件」，
  tooltip 逐条列出引用了什么（**只是陈述**，不承诺调完一定能拿到）；
- 上述路径若含未解析条件，则同样保留提示与测量事实，但网格优先显示「条件未解析」；该文案只说明
  解析缺口，不断言未知条件必然造成零命中。tooltip 列出条件 id，正数测量与静态不可达仍优先；
- 条目在**所有**代表场景中都不适用（条件组合因场景上限被截断，见 `MAX_SCENARIOS`）→ `Unknown(UNCOVERED)`，
  网格 `?`，而不是逐个写 `0`——否则"没算到"会被显示成"不可能获得"；
- 某条路径在某个场景下不可用**不是**静态不可达，它在该场景里记为「需要条件」，不再写 `0%`。

### 7.5 逐路径幸运门槛

[`LuckGateAnalysis`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LuckGateAnalysis.java)
从编译期记下的 [`LuckSpec`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LuckSpec.java)
推出每条路径的最小幸运门槛，结果按**路径分别保留**（`LootAcquisitionPath.luckGate`）——取多路径的
最小值会得到一个无法指回是哪条路径需要它的数字。tooltip 因此**逐条目、逐路径**各自显示自己的门槛
（同一物品的不同路径门槛不同时两条都会出现），不合并成一条全表通用的说明。

判定读原版的两条真实公式（1.21.1）：权重竞争 `max(floor(weight + quality × luck), 0)`（只有 ≥ 1 才进入抽取），
奖励抽取 `rolls + floor(bonus_rolls × luck)`（额外抽取只在 `floor(bonus_rolls × luck) ≥ 1` 时出现）。
**没有任何原版战利品条件直接读幸运**，因此这两条就是"幸运能不能改变这条路径"的全部入口。

算法分两步：先由代数解出候选门槛，**向上对齐到 0.01 网格**，再用上面两条真实公式**回验**；回验不通过
就按 0.01 步进直到通过或触及预算。这样给出的数值是"照着填就能生效"的值（`1/3` 的上对齐结果是 `0.34`，
且 `floor(3 × 0.34) = 1` 确实成立），而不是填不进去的"约 1/3"。

降级原则：满足集合不是"从某个数往上全满足"时（负 quality 造成的上限、代数解落在可表示范围之外、
或步进预算耗尽），一律**不给数值**并标记为"区间受限"——给一个数就等于承诺"高过它就能拿到"。
同理，无法静态求值的 `bonus_rolls` 记为"未知"而不是零。工具不参与门槛：它由 `ItemPredicate` 单独判定。

`LuckSpec` 的采集有两条易错点，都在编译期处理：`bonus_rolls` **缺省**在原版是 `constant 0`（**不是**"未知"），
而字段存在但值是动态提供器时必须保留为"未知"；`group` / `sequence` 的子节点**不参与权重选择**（只有
`alternatives` 才按权重挑一个），因此那里的 `weight`/`quality` 按原版缺省处理——照抄数值会把"组内必然产出"
的条目误判为需要幸运或不可达。

### 7.5.1 静态信息性提示的组成规则

`PathHintAnalyzer.hintsFor` 把一个条目的获取路径翻译成"需要条件"的逐行陈述。两条易错的组成规则：

未解析条件另用 `PathHint.UnresolvedConditions` 表达，与可调旋钮和场景条件分开；组合条件递归收集其
未解析叶子，避免把组合父行和叶子重复列出。该提示由 `CatalogStreamCodec` 下发，并参与目录摘要。

- **只有组合条件（`all_of` / `any_of` / `inverted`）的子节点是"另一条条件"**。其它类型的
  `LootConditionInfo.children()` 是**同一条条件的展示子行**（例：`tool_enchantment` 的
  "概率：基础 20%，每级变化 10%"子行）。早期实现无条件递归，于是同一条门槛被列两遍，第二遍还把
  概率子行写成"该路径需要工具带 概率：… 附魔"这种读不通的句子。子行里的数值（等级门槛、按等级概率）
  仍由条件树原样展示，信息不丢。
- **附魔提示不写死等级门槛**。原文案硬编码"（等级 ≥ 1）"，但 `min_level` 由数据包给出，写死就会在
  `min_level > 1` 的表上说谎。提示只陈述"需要工具带 X 附魔"（"带有该附魔"本身就是该条件类型的固有
  语义），具体等级以条件树的子行为准。

幸运提示优先给具体数值：能算出门槛就写"该路径需要：幸运 ≥ 0.34"（玩家可直接照填），
只有 `luckAffected` 而无从计算时才退回"该路径需要幸运加成"这句无具体目标的陈述。

tooltip 里同时给出其它代表场景的最小/最大值供对照（网格给的是当前输入的值，两者口径不同，不互相冒充）。
代表场景用于控制组合数量与 UI 长度，因此范围不是所有现实条件组合的严格数学上下界。
同一场景内多条路径产出同一签名时仍由整表模拟自然合并。

**声明触发率与模拟掉落率分开**：`random_chance` 解析器把常量值或两端皆为常量的 `uniform` 范围写入条件 metadata 的 `declared_chance_min/max`。公共值类型 `DeclaredChance` 从当前页直接路径的条件树（含继承条件及组合条件的叶节点）收集这些值，保序去重。网格的**显示优先级链**固定为：可适用性状态 → （可展示时）声明触发率 → 否则模拟值。即状态为已测量/静态不可达时网格显示“触发率 X%”或范围（多条用 `/` 分隔）；状态是「需要条件」或未知时网格只显示状态词、不显示任何数字，声明值退到 tooltip。tooltip 里两者始终并列并注明各自口径，后者是整张表抽取的统计结果。

声明值描述各个 `random_chance` 节点自身，不对多个节点做乘法、并集或取反，也不把权重竞争、rolls 或多 pool 的结果混进声明值；完整条件树用于解释组合关系。动态或非法范围不作为明确声明值，未找到明确值时沿用模拟展示。声明值不套用抽样的两位有效数字或零出现阈值，因此 `0%`、小概率和明确区间均按数据值展示。

嵌套子表由 `NestedLootTableMixin` 在模拟作用域中直接观测。每次父表抽取内按子表 ID 去重，
统计“该直接子表至少产出一个物品”的概率；不通过物品签名反推，因此父子表产物重叠不会造成误判。
作用域只保留根表的直接子表，使用复用 List 记录本轮命中，并通过 `IdentityHashMap` 关联产物来源；
身份未保留时再按物品与组件相等回退。内部命中集合由同步回调直接遍历，不为每轮创建副本。
父表 UI 不再平铺 `sourceChildTable != null` 的物品路径，而是显示可点击的子表入口及该概率。
平台运行时注入的 `minecraft:gameplay/fishing -> unsuspiciousblock:gameplay/fishing/mud_dredging`
关系由公共目录加载器补入引用图；NeoForge GLM 直接调用子表时也显式写入同一模拟观测作用域。

原版 fishing JSON 看不到 Fabric 加载期注入池或 NeoForge GLM。规划器因此额外建立普通群系与加成群系
两个代表场景（使用泥地打捞满级工具，等级取自附魔自身的 `max_level`，且 `keepBaseTool = true` 使其
不被参数覆盖），只用于让运行时注入的条目仍被模拟发现。这两个场景**不是基准场景**——启动只跑基准输入
（无附魔钓竿），因此注入条目在**被请求之前**显示为「未覆盖」而不是某个"最有利组合"的数字。
把这条专门分支换成由 `RuntimeLootLinks` 注入边通用派生的父表约束描述，是 P2 的工作（决策 42）。

**注入门槛挂在注入处，被注入子表自己不写条件**：泥地打捞的资格与概率（`0.2 + 0.1/级`）由两端注入处
承载——Fabric 追加的父表 pool 条件、NeoForge 的 GLM 在 `doApply` 里按同一份声明判定；`gameplay/fishing/mud_dredging`
自己的池里**没有**这条条件。于是两个页面各回答一个问题：父表页答"能不能进本表"（基准输入下
「需要条件」并在 tooltip 给出该门槛），子表自己的页答"进了本表之后各物品的份额"（五个直接物品显示各自占比，
而不是被入口门槛判成「需要条件」）。子表里剩下的 `entity_properties`(`in_open_water`) 与
`location_check`(`#c:is_swamp`) 是**条目级**门槛，照常显示「需要条件」。

**父表页的子表入口按这条门槛报「需要条件」**：入口被门槛挡住时，报"抽样零命中"是错误归因——它把
确定的原因说成运气。因此子表入口的展示值走与物品同构的派生
（`PathHintAnalyzer.deriveEntryDisplay`：零命中 + 有可陈述门槛 → `NeedsCondition`），
条件树由服务端下发（路径共同成立的条件 ∩ + 注入门槛），客户端不再从物品路径本地重推——
注入边不写在任何 JSON 里，本地重推必然漏掉它（历史症状：泥底打捞入口显示成没有原因的「未命中」）。
调好条件后（例如带满级附魔的注入场景）该入口显示的就是那个场景测到的数值，不需要另一套逻辑。

这条改动的两个直接后果：

- 附魔身份与门槛参数只声明一处（[`RuntimeLootLinks`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/graph/RuntimeLootLinks.java)
  的 `InjectionGate`/`MUD_DREDGING_GATE`，由 `INJECTION_EDGES` 与合成边同源派生）：
  两端注入实现、每表哈希、附魔等级旋钮清单与父表页入口说明全部取自它，
  所以"玩法"与"tooltip 上写的话"不可能各说一套；
- 记在**发起注入的表**上而不是被注入的子表上：改变的是父表能产出什么（注入场景带满级工具），
  给子表挂旋钮只会多一个点了没反应的控件。

被注入子表自身**不**带注入场景的满级工具：它按自己的声明类型取基座 profile（无附魔钓竿），
因此它页面上的数字是"进来之后的内容分布"，而不是"带着满级附魔去抽"的结果。
掷概率的位置从"子表池内"移到"父表入口"后随机数消耗点改变，**掉落分布不变**。

### 7.3 主线程 tick 驱动

[`LootProbabilitySimulationWorker`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/LootProbabilitySimulationWorker.java) 是模拟的调度器。**关键约束**：`LootTable.getRandomItems` 与 `LootContextParamFiller` 会触碰 `ServerLevel` 关联的 `LegacyRandomSource`，后者不是线程安全的，后台线程与主线程 tick 并发会触发 `ThreadingDetector` 报错。因此**不用后台线程**，改为：

- 在服务端每 tick 末尾（`END_SERVER_TICK`）由 `tick(server)` 消费队列。
- 任务由 `LootProbabilitySimulationJob` 保存抽取次数、签名与计数，可跨 tick 续跑；一个任务只跑**一个输入**。
- 每 tick 使用 15ms 软预算，每 32 次抽取检查一次时间；同一输入的模拟作用域在当前 tick 时间片内复用，避免每批重建观测容器。该预算优先保证服务端启动阶段尽快完成模拟，单次抽取无法中断，因此极端复杂的单次抽取仍可能略超预算。
- 高优先级请求会把已在低队列中的**同一输入**提升到高队列；不同的高优先级任务会在下个 tick 边界抢占当前低优先级任务，且不丢失进度。
- 完成日志输出三个时间，用途各不相同：**线程 CPU 时间**回答"这张表本身有多贵"；**有效计算耗时**是累计在 `advance` 内的墙钟时间，在服务端启动阶段会因与区块生成、视距调整等工作争抢 CPU 而明显虚高；**跨 tick 历时**还包含任务在 tick 之间等待的时间。判断某张表是否变慢要看 CPU 时间，不要直接比"有效计算耗时"。日志同时报出该输入的场景键与参数（`scenario=…, luck=…, tool=…, n=…`）。
- **双优先级队列**：`highQueue`（玩家按需请求与解锁插队）先于 `lowQueue`（启动基准填充）。
- **去重键是 `(表, 输入键)` 而不是表**：抽样次数与参数都进了输入身份，同一张表的两个参数组合是两个不同的问题，按表去重会让后一个请求把前一个顶掉。
- **限流**（决策 15）：HIGH 队列待处理上界 32、每玩家在途上界 2。两条都**只约束玩家触发**的部分——启动批量填充由服务端自己排定、数量等于收录表数，把上界也套在它身上只会让"表很多"被误判成"被刷爆"。玩家解锁触发的插队（`enqueuePriority`）同样不计入玩家额度：解锁是游戏进程自然发生、每表至多一次的事件。
- **同一个输入的多个等待者都会收到结果**：队列里已有 `(表, 输入)` 时不重复排一次，而是把新请求者登记为等待者，完成时逐个回执——否则"启动批量正在算的正好是我要的那个输入"会让玩家点了却永远收不到结果。
- 数据包重载期间 `pauseForReload()` / `resumeAfterReload()` 暂停消费。
- 队列排空后触发 `queueDrainedHandler`（批量广播目录哈希）。
- `isBusy()` 同时检查 reload 暂停、当前任务和待处理队列；需要读取完整动态物品集合的命令据此拒绝半成品目录。

#### 可选性能观测与日志解析

`LootSimulationMetrics` 默认关闭；当前归因阶段的 NeoForge 开发 `runClient` 已在 Gradle 配置中
开启，IDEA 的 Gradle 面板启动同样生效，发布 mod 不受影响。其他启动方式以环境变量 `USB_LOOT_PROFILE=true` 启动游戏，或在**游戏 JVM**
设置 `-Dusb.loot.profile=true` 后，完成日志保留原有格式并追加 `profile=v1` 和以下字段。
观测器只在 `advance` 时间片内绑定当前线程，正常返回、提前让出与异常路径都会恢复绑定。
它不修改 15ms 预算、32 次检查间隔或随机源；开启时的时钟和计数开销仍会占用真实时间，
所以可能增加跨 tick 切片数，不能宣称零性能开销。关闭时不读取分段时钟、不查线程绑定、不追加字段。

| 字段 | 口径 |
|---|---|
| `SCENARIOS` / `ROLLS` | 实际准备的场景数 / 根表 `getRandomItems` 调用数，含全部场景 |
| `DROPS` | 显式注入处理后送入匹配的非空 `ItemStack` 数，不是栈内物品数量 |
| `SCANS` | `resolve` 实际扫描的候选总数，包含当前场景及原始候选的回退扫描 |
| `EXACT_SERIALIZATIONS` | `encodeComponentPatch` 的实际编码次数；空补丁及缓存命中不计 |
| `STORED_KEY_CALLS` | 时间片内实际进入 `toStoredKey` 的次数，含准备与结果组装；存储键缓存命中不计 |
| `PREVIEW_BUILDS` | 任务内预览缓存未命中的构造次数 |
| `MATCHED` / `RAW_SKIPPED` / `DERIVED` | 三个掉落处理分支，成功任务中三者之和必须等于 `DROPS` |
| `CANDIDATES_ADDED` | 各场景实际新增候选数，包含静态初始候选及动态发现 |

分段的 `_ns` 字段均为 **`System.nanoTime` 累计墙钟纳秒，不是 CPU 时间**：

- `PREPARE` / `FINISH` / `RESULT`：上下文和候选准备 / 场景收尾 / 最终结果组装。
- `BEGIN_ROLL` / `GENERATE` / `INJECT`：清理单轮作用域 / `getRandomItems` / 显式注入。
  `GENERATE` 包含 vanilla、现有 Mixin 与 **NeoForge GLM**；不能直接称为“vanilla CPU”。
- `MATCH` / `RAW_MATCH`：当前候选匹配 / 原始候选回退，包含候选查找和预览缓存查询。
- `DERIVE` / `KEY_LOOKUP` / `RECORD` / `CHILD_RECORD`：派生签名 / 查询存储键缓存 /
  更新计数、发现来源和增量候选索引 / 子表计数。精确候选首次加入索引时构造缓存预览，
  所以其 `PREVIEW_DETAIL` 会落在候选准备或 `RECORD` 内；原始索引构建计入任务创建。
- `SERIALIZE_DETAIL` / `STORED_KEY_DETAIL` / `PREVIEW_DETAIL` 是嵌套明细，已包含在父段中，
  **不得再次相加**。未分配时间还包括任务创建/场景规划、循环、观测本身及时间片作用域维护。

使用 [`scripts/loot_simulation_profile.py`](../../scripts/loot_simulation_profile.py) 解析明确的
`/usb journal reload` 标记后的完整轮次，拒绝启动轮、未结束轮及重复表；用同一脚本导出基线和
后续数据。原始热运行证据与操作说明保存在本地目录 `docs/plan/loot-performance/`——该目录含本机
路径、存档摘要与原始日志，**不入库**（见 `.gitignore`），因此这里只写路径、不做链接。
小任务的线程 CPU 可能因平台计时分辨率显示 0ms；不能据此断言零成本。
最终性能收益需在相同观测模式、相同抽样次数和表集合、完成预热的条件下复测。

### 7.4 模拟结果缓存

模拟结果通过 `LootProbabilityData`（SavedData，附加在 overworld）持久化。测量值按**输入键**保存（`inputKey -> {该输入的每签名测量值, 每直接子表测量值}`，见下文存档结构）；**落盘只用窄类型 `SimulatedValue`（`Unknown` / `Measured`），并只持久化"该输入测到了多少"**——不可用场景的「需要条件」恢复时由规划出的场景与路径静态结构重新派生，因此改了静态分析规则不需要迁移存档。嵌套引用的获取路径在每个根表视角下保留第一层子表来源，使孙表条件导致的不可达场景能够汇总到直接子表。动态条目的直接来源标记（`hasDirectSource`）与子表来源列表（`sourceChildTables`）会一并持久化到父表缓存，恢复时优先使用缓存的来源信息，仅在其缺失时才用直接子表及其后代缓存中的相同签名重建获取路径。

**缓存格式与失效**：

候选哈希索引只用于单次任务的内存查找，不是新的签名或存储键；它不限制发现数量，也不改编解码。
存储键格式不变。**实例态组件排除**（见 4.1）改变了签名派生结果，因此
`SIMULATION_CACHE_VERSION` 升到 `loot-analysis-v19`（v17 为"实例态组件排除"引入，v18 为"输入参数真正进入抽取"引入，
v19 为"场景键改为稳定身份"引入），并把该组件 id 列表并入每表哈希的输入：前者覆盖
"派生规则本身变了"（第三方 GLM 注入的饰品条目不在静态编译产物摘要里，只靠子树摘要无法失效），后者覆盖
"改配置"。两者都只让相关表重算，不需要迁移玩家进度。验证时通过 `/usb journal reload` 强制重算。

**存档结构分三层**（`format_version = 4`；P1 之前的 3 是"扁平 items + 每签名分场景值"，版本不符即整体
按缓存未命中处理，不做迁移）：

| 层 | 内容 | 淘汰 |
|---|---|---|
| `hash` | 该表的内容哈希（SHA-256） | 变化即整表作废 |
| `discovery` | 表级发现记录：动态发现签名的"是否直接产出 / 来源直接子表" | **LRU 不淘汰** |
| `inputs` | `inputKey → { 抽样次数, 每签名测量值, 每直接子表测量值 }` | 按参数组合 LRU |

- **发现记录挂表级、LRU 不淘汰**（决策 37）：动态条目（GLM / `LootTableEvents.MODIFY` 注入）没有静态路径，
  它的"直接来源 / 来源子表"只能从模拟观测里得到。若把它放进会被淘汰的测量值里，淘汰一次就会让这批条目
  在重启后**从网格里消失**——那是信息丢失，不是缓存失效。
- **测量值按输入键索引**：键是 `(表哈希, SimulationInputKey)`，于是"同一张表换个幸运值"是新增一条，
  而不是覆盖旧的一条。
- **LRU 按参数组合计数**（决策 46）：每个参数组合（**不含抽样次数**）最多保留全部次数档位，参数组合数
  上限 8，实际条目 ≤ 8 × 档位数。理由是换参数是换**问题**，换次数是换**答案的精度**——高精度答案不该
  把问题本身挤出缓存。访问顺序在内存中按"最近使用"更新（`LinkedHashMap` 访问顺序），落盘沿用上一次
  写盘时的顺序；重启后精度可能回退到上次写盘的状态，代价只是"换一个组合被淘汰"，不影响正确性。
- 存档根带 `format_version`（当前 4）。读取时先校验版本与**严格的 NBT tag 类型**——根缺少版本、版本不符、概率仍是旧版 `StringTag`（注意这并**不是**"字段缺失"，必须按类型显式判定），或单个表的条目无法解析时，一律把对应表按**缓存未命中**处理：既不报错中断，也不迁移数值、更不把类型不匹配解成 0。
- 每表还存一份内容哈希（SHA-256），输入覆盖**整棵子树**：每张表的完整资源栈摘要 + 编译产物摘要（物品签名与 id）+ **被引用附魔的定义摘要**（附魔 id 与其 `max_level`）。必须覆盖子树而不只是本表，否则"子表引用的 item tag 成员变化"（JSON 文本不变、只有展开结果变）不会让父表失效。该哈希一并发给客户端（`CatalogTableDto.hash`），供按需请求声明"我按的是这一版内容"。
- **失效保证拆成两句，不要读成一句更大的保证**：
  1. **"表 JSON 变化必然失效"**——由资源栈摘要承担，成立；
  2. **"影响概率的所有数据变化必然失效"**——并入被引用附魔定义摘要（决定模拟用的满级工具与等级控件范围）后成立。
- **已知残余**（不在上述摘要覆盖内，明确列出以免把"没进摘要"一律当成漏洞）：
  - 附魔定义的 `max_level` 之外的字段（anvil 花费、权重等）——它们不影响任何概率；
  - 其它外部注册表依赖（例如整合包用全局战利品修改器引用的第三方数据）；
  - **经核实不受影响、因此不列入残余**的两类：biome tag 成员（`location_check` 由条件指纹回答，不查真实区块与 tag）与 damage type 定义（`DAMAGE_SOURCE` 由 profile 填充，不读注册表）。
- 统计口径或运行时表来源变化通过 `SIMULATION_CACHE_VERSION` 失效，当前为 `loot-analysis-v19`。函数分级及幸运影响标记加入目录哈希；声明触发率元数据随条件树同步并参与哈希。抽样次数**不再**是全局常量、也不再进表哈希——它现在是输入身份的一维（决策 38）。模拟异常或无法取得有效表时不写入缓存。
- **启动只跑基准输入**（P1）：由 SavedData 里**该表基准输入**的那一条测量值决定缓存是否命中；未命中才入队。基准输入 = 基准场景（条件全不成立）的条件赋值 + 基准参数（默认工具、幸运 0、基准档位、无附魔）。因此"启动成本"从"每表 × 场景数"降到"每表 × 1"，其余场景在玩家请求时才算。启动日志按原因分账（`概率缓存命中 X 个表的基准输入，Y 个待模拟（未命中原因：哈希变化 A 个、缺少该输入的测量值 B 个）`）：**哈希变化**＝内容变了、本该重算，**缺少该输入的测量值**＝内容没变但这条输入没算过；混成一个数字时"缓存机制坏了"与"内容确实变了"看起来一模一样。判缓存是否正常就看第二次启动能不能把这批表全部命中。
- **按需结果只回给请求者**，不写共享目录：按内容去重的缓存是全服共享的，但"当前展示哪个输入"是每个玩家自己的选择，写进共享目录会让两个玩家互相覆盖对方的界面（决策 37）。共享目录里的数字始终是**基准输入**的。
- **数据包重载会真正触发重算（D9）**：Fabric 用 `ResourceManagerHelper.get(PackType.SERVER_DATA)`、NeoForge 用 `AddReloadListenerEvent` 注册 [`DataPackReloadListener`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/DataPackReloadListener.java)，它**只置脏标记**，重建由 `ServerLootTableConfigManager.tick` 在服务端 tick 路径上消费（在重载回调里同步跑全量构建会拖住重载，且两个平台的重载事件时序不同）。服务端启动时的首次资源加载也会触发监听器，但那时目录尚未加载，标记被忽略。
- **不可用机制的表在构建期被拦下**：`LootMechanismSupport` 识别"paramSet 不允许的参数引用"（如 `enchantment_level` 提供器、`enchantment_active_check`）与和填充模型根本不相容的 paramSet（如 `barter`，其 allowed 集合不含 `ORIGIN`）。判定时**空 `type` 按 vanilla 语义等价于 `generic`**（`LootTable.DIRECT_CODEC` 里 `type` 缺省为 `ALL_PARAMS`）——实测有整套模组（BetterArcheology 的 7 张宝箱表）不写 `type`，把空串当成"未知 paramSet"会把它们误判为不可用。这类表不入队模拟（否则会在 `getRandomItems` 里抛异常后静默失败），而是标记为 `Unknown(UNPARSED)` 并输出一次可行动的诊断。参数填充本身也只填 paramSet `allowed` 内的参数，required 与 optional 都填——只填 required 会把"没填"伪装成"条件不成立"。
- **失败输入不会自动重试**：能确定性失败的情形（注册表里没有该表、条件求值抛异常）用同一份输入重跑只会再失败一次并持续占用 tick 预算，因此失败输入只记录不重排。它的概率保持「未知」（不会显示成 0%），本轮队列排空时汇总列出一次 `N 个模拟输入失败…将在下次数据包重载或 /usb journal reload 时重新尝试`。
- `/usb journal reload` 只清除此处的概率缓存与内存目录，不清除玩家笔记进度。

**展示派生只有一份实现**：`ArchaeologyJournalServerCatalog.deriveTable` 从"该表场景规划 + 指定输入的
测量值 + 表级发现记录"派生展示用的 `TableDefinition`，**从缓存恢复与刚刚算完都走它**。它因此不可能
出现"两条路径两套口径"，也解释了为什么测量值类型（`SimulationMeasurement`）刻意不含展示结论。

### 7.7 约束目录与按需管线

**目录只发布约束，不枚举候选输入**（决策 32）。
[`SimulationConstraintCatalog`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/SimulationConstraintCatalog.java)
是一张表的四份清单：场景候选（≤32，附截断数量）、工具基座、被引用附魔的等级上限、抽样次数档位。

为什么不枚举：首版计划让目录生成"场景 × 基座工具 × 附魔等级赋值"的完整集合，但那是一个**乘积**——
32 的上限只约束其中一维，6 个附魔各 4 档就是 4⁶ × 32 = 131,072 个组合。关键认识是缓存 LRU 限制的是
**结果数量**，限制不了这部分**计算**，所以必须从源头取消枚举：单个输入在请求时构造，由
`SimulationConstraintCatalog.resolve` 逐项校验（场景已签发、幸运有限且在界内且已量化到 0.01、工具已签发、
每个附魔等级在 `0..max_level`、抽样次数在档位内）。任何一项越权即整体拒绝，**不做部分修正**——
把一个越权值悄悄改成最近合法值，会让玩家看到的数字与他填的参数不一致，那比直接拒绝更坏。

**工具与附魔取自整棵子树**（`referenceGraph.descendantsInclusive`）：父表页签里出现的物品来自子表，
其 `match_tool` 谓词与读附魔的机制也都写在子表里，只看本表 JSON 会让这些旋钮在父表上凭空消失。
工具基座的枚举方式有一条被明确接受的代价（决策 45）：**标签谓词只取首个成员**，因为
`ItemPredicate` 可含任意物品、标签、组件、数量与子谓词，穷举不可能。因此 `ToolOption` 必须同时携带
**谓词原文**，提示里才能写清"下拉不等于谓词允许的全部物品"；未列入下拉的成员必然找不到联合见证，
于是行为是诚实的降级（只渲染静态提示），而不是挂着一个点不动的死按钮。

**按需请求的完整链路**：

```text
客户端（P2 起）        服务端主线程                                 存档
──────────────        ────────────────────────────────────        ──────────────────
选场景/改旋钮
  → RequestScenarioSimulationPayload
    (generation, 表 id, 表哈希, 场景 key, 幸运, 工具, 附魔等级, 次数)
                      ScenarioSimulationHandler
                        ├─ 每玩家在途 ≤2（worker.canAcceptFor）
                        └─ ScenarioParams 构造（越界即拒绝）
                      ArchaeologyJournalServerCatalog.requestSimulation
                        ├─ 表仍被追踪？ 哈希一致？ 输入被签发？
                        ├─ 命中缓存则不重算（见下）
                        └─ 入 HIGH 队列，去重键 (表, 输入键)
                      worker 在主线程分片推进 → 写 SavedData
                        └─ 只回给请求者（不写共享目录）
  ← SyncScenarioResultPayload(generation, 表哈希, inputKey, 单表 DTO)
  ← （被拒绝时）ScenarioRequestRejectedPayload(generation, 表 id, inputKey, 原因)
```

- 三条校验都**必须在服务端**：客户端持有的目录可能已经过期（哈希不一致 → `STALE_HASH`，让它先重新同步），
  输入可能被自造（→ `REJECTED_INPUT`）。判定的权威是约束描述本身，不是网络层的另一份副本。
- **被拒绝的请求一定回执**：这些情形都不会产出结果包，没有回执的话"点了没反应"与"还在计算中"在界面上
  无法区分。回执不携带任何概率，也不进缓存。
- 结果包的三个标识缺一不可（决策 36）：**代次**（切参数或 `/reload` 之后旧结果可能后到）、
  **表哈希**（哈希变了说明这些数字属于上一版内容）、**输入键**（客户端据此判断"这是不是我此刻选中的
  那个输入"）。三者不符即丢弃。
- 客户端缓存的键与缓存键同源（`表 id + '#' + inputKey`），容量有界、按最旧淘汰。

**目录构建期就要算出约束描述**：启动只跑基准输入，而"基准输入是哪一个"需要先知道基准场景的条件赋值，
因此这一步是缓存查询的前置条件，不是可以省的预计算。构建时为每张表打印一行摘要（只对有信息量的表：
场景数 >1、有截断、或超预算），运维据此核对上界与降级行为。

### 7.8 声明触发率与模拟掉落率

**声明触发率与模拟掉落率分开**：`random_chance` 解析器把常量值或两端皆为常量的 `uniform` 范围写入条件 metadata 的 `declared_chance_min/max`。公共值类型 `DeclaredChance` 从当前页直接路径的条件树（含继承条件及组合条件的叶节点）收集这些值，保序去重。网格的**显示优先级链**固定为：可适用性状态 → （可展示时）声明触发率 → 否则模拟值。即状态为已测量/静态不可达时网格显示“触发率 X%”或范围（多条用 `/` 分隔）；状态是「需要条件」或未知时网格只显示状态词、不显示任何数字，声明值退到 tooltip。tooltip 里两者始终并列并注明各自口径，后者是整张表抽取的统计结果。

**重载一致性**：整轮重载的静态部分（快照 / 图 / 编译产物 / 静态投影 / 每表哈希 / 分类结构）在同一轮局部构建完成，再通过单个 volatile 引用**原子发布**，读取方只会看到上一代的完整状态或新一代的完整静态部分。模拟结果作为该代的 overlay 随进度增长，提交时校验代次，旧代结果不会写入新代。`invalidate()` 只需丢掉引用，资源快照与投影随之释放。详见 [考古笔记系统](journal.md) 的目录构建部分。

## 8. 战利品注入

[`ArchaeologyLootInjectors`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/injection/ArchaeologyLootInjectors.java) 是全局单例注入器，**平台差异化**：

| 平台 | 注入器 | 机制 |
|---|---|---|
| Fabric | `FabricArchaeologyLootInjector` | 模拟时显式调用 `maybeReplace`，向 drops 注入模组物品 |
| NeoForge | 空实现（默认） | 注入由 `IGlobalLootModifier`（GLM）在 `getRandomItems` 内部完成 |

> NeoForge 端不注册注入器，因为 GLM 已经在 `getRandomItems` 内部完成了注入，模拟器自然能抽取到注入的物品。Fabric 端没有 GLM，需要在模拟时显式调用注入器，确保模组物品被纳入概率统计与签名派生。

注入器接口 `ArchaeologyLootInjector` 是 `@FunctionalInterface`，签名为 `(tableId, drops, random) -> {}`。Fabric 端的注入实现见 [`fabric/.../loot/`](../../fabric/src/main/java/com/meteorite/unsuspiciousblock/loot/)（多个 `LootInjection` 类）。NeoForge 端的 GLM 见 [`neoforge/.../loot/`](../../neoforge/src/main/java/com/meteorite/unsuspiciousblock/loot/)。

## 9. 自定义战利品条件

`condition/` 包只定义**一个**模组自定义战利品条件：

- [`ModLootConditions`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/ModLootConditions.java) 注册唯一规范名 `unsuspiciousblock:tool_enchantment`，并登记它的展示描述：
  - Fabric：`Registry.register` 直接注册（`onInitialize` 开头）。
  - NeoForge：`DeferredRegister` 注册（避免 registry frozen）。
- [`ToolEnchantmentCondition`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/ToolEnchantmentCondition.java) 是一个类型表达完整语义的条件：
  - `enchantment` 必填（`Enchantment.CODEC`，即 `Holder<Enchantment>`）；
  - `min_level` 可选，默认 `1`，以 `Codec.intRange(1, 255)` 校验——"带有该附魔"本身就是该类型的固有语义；
  - `chance` 可选（`LevelBasedValue`）：缺省表示**纯资格门槛**，给出时再按实际附魔等级掷一次概率。
  - 因此"需要工具附魔"与"按该附魔等级掷概率"不再需要并列两条条件，tooltip 也从相邻两行变成
    "一行附魔展示名 + 按需的等级/概率子行"。
- Fabric 只向原版 fishing 表追加一个父表 pool：**资格与概率都写在这条 pool 条件上**，条件由
  [`RuntimeLootLinks.MUD_DREDGING_GATE`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/graph/RuntimeLootLinks.java)
  构造（`enchantment` = 泥地打捞、`min_level` 缺省 1、`chance` = `linear(0.2, 0.1)`，附魔 `Holder`
  由 `LootTableEvents.MODIFY` 回调提供的 `HolderLookup.Provider` 取得）；NeoForge 的 GLM 数据里**只留**
  `loot_table_id` 过滤，同一条门槛在 `FishingLootModifier.doApply` 里按同一份声明判定
  （判定时机与原先"作为 GLM 条件"一致：每次施放一次、在抽取子表之前）。
  被注入子表 `gameplay/fishing/mud_dredging` 自己的池**不写条件**，只描述"进来之后产出什么"：
  基础物品直接平铺在根表，`common`（开放水域）与 `swamp`（开放水域 + `#c:is_swamp` 群系 tag）
  两个子表 entry 按权重参与竞争，沼泽表权重更高。因此父表页与子表页各自回答一个问题
  （能不能进 / 进来之后各物品的份额），详见 7.2。

条件类型的注册时序约束见 [架构总览](architecture-overview.md) 的初始化流程--必须在 `UnsuspiciousBlockCommon.init()` 之前完成。

> 旧注册名 `mud_dredging` 与 `random_chance_with_tool_enchantment`、旧 `swamp` 字段都已删除：
> 两个旧格式只由本模组内置资源使用，代码与 JSON 在同一构建产物里原子更新，不承诺跨版本兼容。
> 引用未注册条件类型会让**整张表**解析失败并不注册，因此资源与注册名必须同批发布。

## 10. 扩展点

- **新增收录范围**：修改配置的追踪前缀列表（`ILootTableConfig.getArchaeologyPathPrefixes()`），或通过数据包新增命中前缀的战利品表。
- **新增实例态组件排除**：在 `ILootTableConfig.getSignatureExcludedComponents()` 里加组件 id（默认 `relics:data`），含这些组件的物品即按物品级（`PLAIN`）收录，见第 4.1 节。该列表已并入每表哈希，改配置会让相关表重算；但旧存档里按组件变体存下的进度键会成为孤儿——不渲染、不参与完成度闭包，需要按需清理（本项改动未提供存档迁移）。
- **自定义签名类型**：在 `LootResultSignature.SignatureType` 添加枚举，注意 `fromStoredKey` 的兼容性。签名类型变更会影响玩家存档，需在 `JournalNbtMigrator` 补充连续迁移步骤。
- **新增战利品条件**：参考 `ToolEnchantmentCondition`，在 `ModLootConditions` 注册类型与展示描述，两端各自注册到注册表；资源与本批必须同批落地（见第 9 节）。展示描述只能做到"成立但有未展示约束"时，调 `LootConditionHandlers.partial(info)` 告诉客户端改用斜体，别让半懂乍看像读懂。
- **新增场景控制类型**：把类型加入 `SimulationScenarioPlanner` 的 `SCENARIO_CONDITIONS`，并为其补一个 `test` 转交作用域的窄 Mixin；类型须实现为 record 或覆写 `toString()`，否则指纹稳定性判定会把它排除出场景规划（见 7.2）。加之前先问"它该不该由玩家调参代替"——`match_tool` 的教训是：把它做成场景布尔会让它与读同一个上下文的函数互不相干（见 7.2 表格首行）。
- **新增参数旋钮**：在 `ScenarioParams` 加字段（并在构造点给出不变量）、在 `SimulationInputKey.of` 加一段规范编码（**顺序固定**，缓存键与网络标识共用它）、在 `SimulationConstraintCatalog.resolve` 加一条签发校验、在 `SimulationProfile` 加一个 `withXxx` 并由 `ScenarioParams.applyTo` 套用。四处缺一不可：少了校验就是越权入口，少了 key 段就会与别的参数共用缓存。
- **新增运行时联动边（平台注入 / 表间运行时关系）**：在 `RuntimeLootLinks` 声明标识符与边，并在 `LootTableEdge.Kind` 中显式选型——`RUNTIME_INJECTION` 只参与收录闭包、目录层级与哈希，**不参与静态语义链接**，条目仍由模拟期动态发现并保持 `injected=true`。合成边只在两端资源都存在时才会注入。注意：注入物的**参数入口**目前由 P2 的"注入边参与父表约束描述"（决策 42）负责，在那之前注入物只能靠专门分支的场景被发现。
- **修改概率口径**：`Probability` 是不变式载体，新增状态需同时更新存档与网络的穷尽 codec 与 `ProbabilityFormat`；任何情况下都不要把展示文本写回数据层，也不要反解文本做数值比较。
- **新增存档字段**：`format_version` 必须 +1，并在读取路径上把"旧版本/类型不符"一律按**缓存未命中**处理（既不报错中断、也不迁移数值、更不把类型不匹配解成 0）。`discovery` 层的东西永远不能进会被 LRU 淘汰的层——那会让条目在淘汰后从界面上消失。
- **平台注入器**：Fabric 端如需新的注入逻辑，实现 `ArchaeologyLootInjector` 并在 `onInitialize` 调 `ArchaeologyLootInjectors.register`。
- **模拟调优**：`ScenarioParams.SAMPLE_COUNT_TIERS`（精度 vs 性能，同时是档位白名单与硬上限）、`MAX_PARAMETER_COMBINATIONS`（缓存规模）、`TICK_BUDGET_NANOS` 与批次大小（吞吐 vs tick 占用）、`MAX_SCENARIOS` / 展开预算（场景规模 vs 构建成本）是主要可调参数。**注意抽样次数不再是全局常量**：它进了输入身份，改档位会让同一参数的旧缓存键失效（这是刻意行为——换了精度就是换了一个问题）。

## 11. 相关文档

- [考古笔记系统](journal.md) - 目录构建、玩家进度、追踪的上层
- [配置与第三方联动](config-integrations.md) - 收录前缀等配置项
- [Mixin 总览](mixin.md) - `NestedLootTableMixin`（嵌套表捕获）
- [docs/journal-categories.md](../journal-categories.md) - 目录分类规则与领域语言
