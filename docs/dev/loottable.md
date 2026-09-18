# 战利品表系统

本文档描述 `loottable/` 包的架构：如何解析、收录、注入战利品表，如何为物品生成稳定签名，以及如何通过模拟抽取估算概率。这是考古笔记目录的底层支撑。

## 1. 职责概述

`loottable/` 包不直接管理玩家进度，而是提供五个底层能力：

1. **目录数据结构**：定义 `TableDefinition` / `ItemDefinition` 等跨模块共享的记录类型。
2. **签名机制**：为战利品结果生成稳定签名，用于进度匹配与持久化。
3. **收录范围匹配**：把配置规则解析为可匹配的模式，判断哪些战利品表被纳入目录。
4. **概率模拟**：对每张表执行大量模拟抽取，统计物品出现概率。
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
│   ├── LootConditionHandler(s|Info)  战利品条件分析与描述
│   ├── LootFunctionHandler(s)      战利品函数分析
│   └── LootParseUtil               条件分析、函数链拼接与类型规范化的唯一实现
├── catalog/      读模型与查询
│   ├── LootTableCatalog         跨模块共享的记录类型
│   ├── Probability              概率值类型（未知 / 不可达 / 已测量）
│   ├── LootTableProjector       图 + 编译产物 → 静态投影
│   ├── StaticTableProjection    静态展平物品路径与子表入口（概率为占位）
│   ├── ItemDefinitionAccumulator 同签名多路径合并的唯一实现
│   ├── CatalogQueryIndex        子树物品等跨表聚合的唯一入口
│   ├── CatalogTableDto          网络形态：场景假设每表只发一次
│   ├── LootTablePattern            收录规则解析与匹配
│   ├── LootTableNames              表名/本地化 key 工具
│   ├── LootTableTranslationStore      服务端整合包级补充语言存储
│   ├── MissingTranslationKeyExporter  游戏资源与服务端配置的缺失 key 诊断快照
│   └── (ArchaeologyJournalCatalog 在 journal/catalog/，调用本包)
├── signature/    结果签名
│   ├── LootResultSignature         签名 record（核心）
│   ├── LootResultMatcher           运行时掉落与候选签名匹配
│   └── LootCounts                  计数工具
├── simulation/   概率模拟
│   ├── LootProbabilitySimulator    模拟引擎（单表 10000 次抽取）
│   ├── LootProbabilitySimulationJob 可续跑的单表模拟状态
│   ├── LootProbabilitySimulationWorker  主线程 tick 驱动器
│   ├── SimulationProfile             模拟工具/方块/实体参数与条件资格场景
│   ├── SimulationScenarioPlanner     从获取路径规划代表场景
│   ├── SimulationScenario            单个自洽条件场景
│   ├── SimulationCompositeConditionAccess 复合条件求值访问
│   ├── LootConditionFingerprint      解析期与运行时条件指纹
│   ├── LootSimulationScope           主线程模拟期间的 profile 作用域
│   ├── LootContextParamFiller      模拟用 LootParams 构建（宽松回退）
│   ├── SimulationFakePlayer        模拟用假玩家
│   ├── SimulationFishingHook       模拟用假钓鱼浮标（钓鱼表 THIS_ENTITY）
│   └── ProbabilityFormat           概率值 → 展示文本（唯一格式化入口，仅 UI 边界调用）
├── injection/    战利品注入
│   ├── ArchaeologyLootInjector     注入器接口
│   └── ArchaeologyLootInjectors    全局注册器（单例）
└── condition/    自定义战利品条件
    ├── ModLootConditions           条件类型注册
    ├── MudDredgingCondition         泥底打捞附魔资格条件
    └── ToolEnchantmentChanceCondition 工具附魔等级概率条件
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
  sourceChildTable,    来自哪个子表
  sourceItemTag,       来自哪个 item tag
  entryConditions,     条目自身条件
  inheritedConditions  继承自 pool/组合 entry/表引用的条件
}
```

`acquisitionPaths` 记录一个物品在表中的所有获取路径（可能来自不同 pool、不同子表、不同条件），用于在 UI 中展示"如何获得"。`injected=true` 表示该条目不在原始 JSON 中，而是模拟期由 GLM 或 LootTableEvents.MODIFY 注入发现的。`sourceChildTable` 只记**第一跳**：从根表观察时孙表的条件汇总回直接子表，父表页签的归属才不会错位。

### 3.1 概率值类型

概率**不是**字符串。[`Probability`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/Probability.java) 是 sealed 值类型，三个互斥状态取代了原先共用一个字符串的五种语义：

| 状态 | 含义 | 展示文本 |
|---|---|---|
| `Unknown` | 未被任何代表场景覆盖，或条目带条件而抽样零出现（**不是**不可达） | `?` |
| `Unreachable` | 该场景被静态判定不可达 | `0%` |
| `Measured(lower, upper?)` | 实际抽样比例；`upper` 非空表示跨场景区间 | `12%` / `<0.01%` / `3%-7%` |

- 构造即校验有限数值与 `0 ≤ lower ≤ upper ≤ 1`，非法值在构造点抛出，不会流到 UI 变成乱码。
- "抽样 10000 次一次未出现"是 `Measured(0.0)`（展示 `<0.01%`），"静态不可达"是 `Unreachable`（展示 `0%`）。两者曾经都写成 `"0"`，正是"把没算到显示成不可能"那类问题的根源。
- **格式化只发生在 UI 边界**（`ProbabilityFormat.format`）。数据层、存档缓存、网络与排序一律用数值，不存在"把界面文本反解回数值来排序"的做法。
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

`COMPONENT_EXACT` 的预览栈需要 base64 + JSON 解码再套用组件补丁，成本远高于其它类型的匹配。因此
[`LootResultPreviewCache`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/signature/LootResultPreviewCache.java)
按签名内容缓存预览栈——预览栈由签名值唯一决定，缓存结果永远有效、不需要失效策略；玩家侧的掉落实时匹配
（掉落追踪、容器/菜单扫描）都通过 `LootResultPreviewCache.PROVIDER` 取用，避免每次掉落、每个槽位重复解码。
缓存只在数据包重载时清空以限制内存。概率模拟热路径不共用它：单表模拟自带 `HashMap` 缓存，单线程且无并发开销，更快。

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
- **读模型**：`LootTableProjection` 展平出的 `TableDefinition` 只收真正产出物品的表；无物品的表既不进目录也不作为子表入口。此阶段概率为 `Probability.unknown()` 占位，等待模拟填充。

`LootConditionHandler` / `LootFunctionHandler` 把原版条件/函数转译为可读的 `LootConditionInfo`；条件分析、函数链拼接与多路径合并各只有一份实现（`LootParseUtil`、`ItemDefinitionAccumulator`），保证编译路径与投影路径产出逐位一致的条件指纹与签名。

## 7. 概率模拟

### 7.1 模拟引擎

[`LootProbabilitySimulator.simulateOne`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/LootProbabilitySimulator.java) 对单张表执行 `SIMULATION_COUNT = 10_000` 次模拟抽取：

```
simulateOne(tableId, rawTable, level)
  ├─ 从 ReloadableServerRegistries 取得数据包重载后的运行时 LootTable
  │    保留 Fabric LootTableEvents.MODIFY 等加载期注入；不从原始 JSON 重建表
  ├─ SimulationScenarioPlanner 从所有获取路径生成至多 8 个代表条件场景
  ├─ 每个场景按声明的 paramSet 构建独立 LootParams
  │    SimulationProfile 提供 TOOL、BLOCK_STATE、DAMAGE_SOURCE、ORIGIN 等值
  │    钓鱼类表（path 含 "fishing"）的 THIS_ENTITY 用 SimulationFishingHook 填充，
  │    使 entity_properties + fishing_hook + in_open_water 条件在模拟中可判定。
  │    注意：fishing paramSet 中 THIS_ENTITY 是 optional（required 仅 ORIGIN+TOOL），
  │    Filler 需在 required 遍历之外按 allowed 集合补填，否则条件恒 false
  ├─ 初始化该场景适用的候选签名 + appearanceCounts
  └─ 每个场景循环 10000 次：
       ├─ lootTable.getRandomItems(lootParams)
       ├─ ArchaeologyLootInjectors.get().maybeReplace(tableId, drops, random)
       │     Fabric 显式调用注入器；NeoForge 空实现（GLM 已在 getRandomItems 内部完成）
       ├─ 对每个 drop：
       │    ├─ LootResultMatcher.resolve(stack, candidates) 匹配已知签名
       │    └─ 未匹配 -> deriveSignature 派生签名（附魔折叠为近似，其他组件严格保留）
       │         若已是已知签名 -> 保守跳过（歧义掉落）
       │         否则登记为注入条目（injected=true）
       └─ 对本轮出现的签名去重后计数
```

物品概率口径是“单次战利品表抽取中，该签名至少出现一次的概率”。同一轮返回多个相同签名的
`ItemStack` 时只计一次，不统计物品数量或平均产量。

模拟热路径按 `Item` 预先索引候选，只扫描当前掉落物的签名变体。`LootResultMatcher` 用局部状态记录
最高优先级与歧义，不为每次匹配创建排序 Map 或临时 List；每个候选缓存 stable key，并由带轮次标记的
原始计数器完成单轮去重，避免每轮重建 `HashSet` 节点和 `Integer` 装箱。上述优化不改变签名优先级、
歧义回退或“每轮最多计一次”的概率口径。

模拟必须调用普通 `LootTable.getRandomItems`：NeoForge 会在原始抽取结束后由该入口应用
Global Loot Modifier（GLM），而 `getRandomItemsRaw` 不会应用 GLM。嵌套表由原版 resolver
从同一个运行时注册表解析，且嵌套抽取使用 `getRandomItemsRaw`，因此 GLM 只在根表应用一次。

概率计算规则：

| 出现次数 | 条件 | 概率值 |
|---|---|---|
| - | 当前代表场景静态不可达 | `Unreachable`（展示 `0%`） |
| - | 条目在所有代表场景中都不适用（条件组合超出代表场景上限） | `Unknown`（未被覆盖，不等于不可达） |
| 0 | `hasConditions` | `Unknown`（条件性物品，模拟可能未覆盖） |
| 0 | 无条件 | `Measured(0.0)`（展示 `<0.01%`） |
| >0 | - | `Measured(appearances / 10000)` |

条目摘要由该条目的场景集合汇总：全等取其值；含 `Unknown` 则整体 `Unknown`；否则取跨场景区间的下界与上界（`Measured(lower, upper)`）。展示文本由 `ProbabilityFormat` 在 UI 边界生成。

模拟期才发现的动态条目同样按每个代表场景中的实际出现次数计算概率。由于这些条目没有可供静态
判定的获取路径，只保存实际观测到该签名的场景。动态条目中的非附魔 `DataComponentPatch` 使用
`COMPONENT_EXACT` 保存，确保药水等动态变体经过 SavedData 缓存和网络同步后仍能恢复；附魔结果
继续折叠为 `ENCHANTED_APPROX`。

### 7.2 条件场景策略

运行时表保留全部条件，不能再通过读取原始 JSON、删除条件并重新解码来生成模拟副本。那种做法会
绕过 Fabric 加载期修改，也无法可靠表达 `all_of`、`any_of`、`inverted` 等组合条件的语义。

`SimulationScenarioPlanner` 从每个 `LootAcquisitionPath` 提取八类资格条件，为每条可达路径建立最小
布尔赋值场景。解析阶段把 `simulation_fingerprint` 写入每个条件的 metadata；运行时 Mixin 用同一
指纹查询 `SimulationProfile` 中的精确 true/false 结果，因此同类型的两个 `location_check` 不会混淆。

指纹取自条件对象的 `toString()`，因此**只对按字段值生成文本的类型成立**（record 即是）。解析阶段
同时写入 `simulation_fingerprint_stable`：识别出 `类名@identityHash` 这类默认实现时，该条件不参与
场景规划（按无约束处理，条目因此保留在各场景中而非被判成不可达）并告警一次——否则解析期与运行时
的两个实例必然算出不同指纹，场景覆盖会静默失效。运行时另有兜底：属于场景控制类型、却没有被任何
代表场景覆盖的条件，会在该表模拟完成时汇总告警，提示这部分数值是按真实逻辑求值得到的。

**已知的预期告警**：`minecraft:gameplay/fishing` 重模拟时会出现一条
`有 1 个场景控制类型条件未被代表场景覆盖 … minecraft:entity_properties`。这来自追加的泥地打捞
注入场景——它们有意只带 `location_check` 的类型默认值、**不带**基础场景的条件指纹表，好让注入路径上的
`entity_properties`（`in_open_water`）交给真实逻辑（假浮标 `SimulationFishingHook`）判定：若把基础场景的
指纹表传进去，"默认"场景会把 `in_open_water` 归一到 `false`，反而把宝箱与泥地打捞的条目强制判成不可达。
因此这条告警是"该场景按真实逻辑求值"的诚实信号，不是指纹失效；它只在该表**实际发生模拟**时输出
（缓存命中时不会出现），无需处理。

| 条件 | 当前处理方式 |
|---|---|
| `match_tool`、`block_state_property` | profile 填充 `TOOL`、`BLOCK_STATE`；条件门槛按匹配场景处理 |
| `damage_source_properties` | profile 填充 `DamageSource`；复杂伤害谓词按匹配场景处理 |
| `entity_properties` | 填充假玩家；钓鱼表的 THIS_ENTITY 使用 `SimulationFishingHook` |
| `location_check` | 场景按具体条件指纹分别取 true/false，不查找或生成真实区块 |
| `weather_check`、`time_check` | 场景按具体条件取值，不修改服务器天气或时间 |
| `entity_scores` | 场景按具体条件取值，不创建 objective、不写真实 scoreboard |

`LootSimulationScope` 通过 `ThreadLocal` 仅在当前主线程的 10000 次抽取期间暴露 profile，并由
`try-with-resources` 确保异常时清理。窄 Mixin 只把八类叶条件的 `test` 转交作用域；
`all_of` / `any_of` / `inverted` 始终由原版逻辑根据叶子结果求值，随机条件、权重和 rolls 不覆盖。

UI 继续递归展示 `LootConditionInfo` 条件树，并对工具/方块、群系/维度/结构、天气、时间、
实体目标、伤害来源与计分范围提供具体描述。

概率文本与颜色共用**一份**判定：`ItemDefinition.uncertaintyLevel()` 与 `hasConditions()` 读同一批数据
（全部获取路径的条件树 + 近似签名），UI 不再在客户端另起规则、也不再用 tooltip 文案比较来识别近似条目
（那种做法会受语言差异影响）。两者的关系是单向蕴含：等级非 `NONE` 必然说明条目带条件或签名近似
（即概率可能显示 `?`）；反过来，只有可静态求值的条件（如 `match_tool`）时等级为 `NONE` 而概率仍可能为
`?`——此时网格与 tooltip 都按"未知"着色，不会出现"颜色说不确定、概率却从不显示 `?`"或反过来的错配。存在上述场景条件时，概率文字明确标为
“条件满足时至少出现一次”，不是这些条件在自然游戏过程中的发生概率。

`ItemDefinition.scenarioProbabilities` 保存代表场景的内部统计结果。静态条件证明不可达的物品或子表在
对应场景中记为 `0`，可触发但 10000 次均未出现才记为 `<0.01%`。条目或子表在**所有**代表场景中都不
适用时（其条件组合因场景上限被截断，见 `MAX_SCENARIOS`），视为未被覆盖，摘要统一记为 `"?"` 而不是
逐个写 `0`——否则"没算到"会被显示成"不可能获得"。目录摘要取这些场景的最小值与最大值；
网格和 tooltip 只展示该范围，不再逐场景展开重复条件树。代表场景用于控制组合数量与 UI 长度，
因此范围不是所有现实条件组合的严格数学上下界。同一场景内多条路径产出同一签名时仍由整表模拟自然合并。

嵌套子表由 `NestedLootTableMixin` 在模拟作用域中直接观测。每次父表抽取内按子表 ID 去重，
统计“该直接子表至少产出一个物品”的概率；不通过物品签名反推，因此父子表产物重叠不会造成误判。
作用域只保留根表的直接子表，使用复用 List 记录本轮命中，并通过 `IdentityHashMap` 关联产物来源；
身份未保留时再按物品与组件相等回退。内部命中集合由同步回调直接遍历，不为每轮创建副本。
父表 UI 不再平铺 `sourceChildTable != null` 的物品路径，而是显示可点击的子表入口及该概率。
平台运行时注入的 `minecraft:gameplay/fishing -> unsuspiciousblock:gameplay/fishing/mud_dredging`
关系由公共目录加载器补入引用图；NeoForge GLM 直接调用子表时也显式写入同一模拟观测作用域。

原版 fishing JSON 看不到 Fabric 加载期注入池或 NeoForge GLM。规划器因此使用泥地打捞 III 级工具，
额外建立普通群系与加成群系两个代表场景，只收集运行时发现的注入物；父表自身也统一按 III 级模拟。

### 7.3 主线程 tick 驱动

[`LootProbabilitySimulationWorker`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/simulation/LootProbabilitySimulationWorker.java) 是模拟的调度器。**关键约束**：`LootTable.getRandomItems` 与 `LootContextParamFiller` 会触碰 `ServerLevel` 关联的 `LegacyRandomSource`，后者不是线程安全的，后台线程与主线程 tick 并发会触发 `ThreadingDetector` 报错。因此**不用后台线程**，改为：

- 在服务端每 tick 末尾（`END_SERVER_TICK`）由 `tick(server)` 消费队列。
- 单表由 `LootProbabilitySimulationJob` 保存当前场景、抽取次数、签名与计数，可跨 tick 续跑。
- 每 tick 使用 15ms 软预算，每 32 次抽取检查一次时间；同一场景的模拟作用域在当前 tick 时间片内复用，避免每批重建观测容器。该预算优先保证服务端启动阶段尽快完成模拟，单次抽取无法中断，因此极端复杂的单次抽取仍可能略超预算。
- 高优先级请求会把已在低队列中的同表任务提升到高队列；不同的高优先级任务会在下个 tick 边界抢占当前低优先级任务，且不丢失进度。
- 完成日志分别输出“有效计算耗时”和“跨 tick 历时”；后者包含任务在 tick 之间等待的墙钟时间，不能用于和旧版同步模拟耗时直接比较。
- **双优先级队列**：`highQueue`（玩家解锁触发，插队）先于 `lowQueue`（启动批量填充）。
- `enqueued` 集合去重，避免同一表重复入队。
- 数据包重载期间 `pauseForReload()` / `resumeAfterReload()` 暂停消费。
- 队列排空后触发 `queueDrainedHandler`（批量广播目录哈希）。
- `isBusy()` 同时检查 reload 暂停、当前任务和待处理队列；需要读取完整动态物品集合的命令据此拒绝半成品目录。

### 7.4 模拟结果缓存

模拟结果通过 `LootProbabilityData`（SavedData，附加在 overworld）持久化。每个签名和子表入口同时保存摘要概率与 `scenario_key -> probability`，概率以值类型的三态结构存储；恢复时由规划器重建场景条件描述。嵌套引用的获取路径在每个根表视角下保留第一层子表来源，使孙表条件导致的不可达场景能够汇总到直接子表。动态条目的直接来源标记（`hasDirectSource`）与子表来源列表（`sourceChildTables`）会一并持久化到父表缓存，恢复时优先使用缓存的来源信息，仅在其缺失时才用直接子表及其后代缓存中的相同签名重建获取路径。

**缓存格式与失效**：

- 存档根带 `format_version`（当前 2）。读取时先校验版本与**严格的 NBT tag 类型**——根缺少版本、版本不符、概率仍是旧版 `StringTag`（注意这并**不是**"字段缺失"，必须按类型显式判定），或单个表的条目无法解析时，一律把对应表按**缓存未命中**处理：既不报错中断，也不迁移数值、更不把类型不匹配解成 0。
- 每表还存一份内容哈希，输入为该表**子树内每张表**的完整资源栈摘要与编译产物摘要。后者必须覆盖整棵子树而不只是本表，否则"子表引用的 item tag 成员变化"（JSON 文本不变、只有展开结果变）不会让父表失效。
- 统计口径或运行时表来源变化通过 `SIMULATION_CACHE_VERSION` 失效，当前为 `loot-analysis-v13`。模拟异常或无法取得有效表时不写入缓存。
- **失败表不会自动重试**：能确定性失败的情形（注册表里没有该表、条件求值抛异常）用同一份输入重跑只会再失败一次并持续占用 tick 预算，因此失败表只记录不重排。它的概率保持"未知"（不会显示成 0%），本轮队列排空时汇总列出一次 `N 张表的概率模拟失败…将在下次数据包重载或 /usb journal reload 时重新尝试`。
- `/usb journal reload` 只清除此处的概率缓存与内存目录，不清除玩家笔记进度。

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

`condition/` 包定义模组自定义的战利品条件：

- [`ModLootConditions`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/ModLootConditions.java) 注册 `mud_dredging` 与 `random_chance_with_tool_enchantment` 两种条件：
  - Fabric：`Registry.register` 直接注册（`onInitialize` 开头）。
  - NeoForge：`DeferredRegister` 注册（避免 registry frozen）。
- [`MudDredgingCondition`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/MudDredgingCondition.java) 只检查工具是否具有泥地打捞附魔；旧 `swamp` 字段仅用于数据兼容。
- [`ToolEnchantmentChanceCondition`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/condition/ToolEnchantmentChanceCondition.java) 从 `TOOL` 读取指定附魔等级并用 `LevelBasedValue` 计算概率。
- Fabric 只向原版 fishing 表追加一个带资格条件的父表 pool；NeoForge GLM 也只执行同一父表。父表为单 pool：资格与统一触发概率（0.2 + 0.1/级）写在 pool 条件上，基础物品直接平铺在根表，`common`（开放水域）与 `swamp`（开放水域 + `#c:is_swamp` 群系 tag）两个子表 entry 按权重参与竞争，沼泽表权重更高。

条件类型的注册时序约束见 [架构总览](architecture-overview.md) 的初始化流程--必须在 `UnsuspiciousBlockCommon.init()` 之前完成。

## 10. 扩展点

- **新增收录范围**：修改配置的追踪前缀列表（`ILootTableConfig.getArchaeologyPathPrefixes()`），或通过数据包新增命中前缀的战利品表。
- **自定义签名类型**：在 `LootResultSignature.SignatureType` 添加枚举，注意 `fromStoredKey` 的兼容性。签名类型变更会影响玩家存档，需在 `JournalNbtMigrator` 补充连续迁移步骤。
- **新增战利品条件**：参考 `MudDredgingCondition`，在 `ModLootConditions` 注册类型，两端各自注册到注册表。
- **新增场景控制类型**：把类型加入 `SimulationScenarioPlanner` 的 `SCENARIO_CONDITIONS`，并为其补一个 `test` 转交作用域的窄 Mixin；类型须实现为 record 或覆写 `toString()`，否则指纹稳定性判定会把它排除出场景规划（见 7.2）。
- **新增运行时联动边（平台注入 / 表间运行时关系）**：在 `RuntimeLootLinks` 声明标识符与边，并在 `LootTableEdge.Kind` 中显式选型——`RUNTIME_INJECTION` 只参与收录闭包、目录层级与哈希，**不参与静态语义链接**，条目仍由模拟期动态发现并保持 `injected=true`。合成边只在两端资源都存在时才会注入。
- **修改概率口径**：`Probability` 是不变式载体，新增状态需同时更新存档与网络的穷尽 codec 与 `ProbabilityFormat`；任何情况下都不要把展示文本写回数据层，也不要反解文本做数值比较。
- **平台注入器**：Fabric 端如需新的注入逻辑，实现 `ArchaeologyLootInjector` 并在 `onInitialize` 调 `ArchaeologyLootInjectors.register`。
- **模拟调优**：`SIMULATION_COUNT`（精度 vs 性能）、`TICK_BUDGET_NANOS` 与批次大小（吞吐 vs tick 占用）是主要可调参数。

## 11. 相关文档

- [考古笔记系统](journal.md) - 目录构建、玩家进度、追踪的上层
- [配置与第三方联动](config-integrations.md) - 收录前缀等配置项
- [Mixin 总览](mixin.md) - `NestedLootTableMixin`（嵌套表捕获）
- [docs/journal-categories.md](../journal-categories.md) - 目录分类规则与领域语言
