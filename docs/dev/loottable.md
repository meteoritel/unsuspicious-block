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
├── catalog/      目录数据结构与收录匹配
│   ├── LootTableCatalog            TableDefinition / ItemDefinition 等记录类型
│   ├── LootTablePattern            收录规则解析与匹配
│   ├── LootTableNames              表名/本地化 key 工具
│   ├── LootTableTranslationStore      服务端整合包级补充语言存储
│   ├── MissingTranslationKeyExporter  游戏资源与服务端配置的缺失 key 诊断快照
│   └── (ArchaeologyJournalCatalog 在 journal/catalog/，调用本包)
├── analysis/     战利品表 JSON 解析
│   ├── LootTableJsonParser         解析 loot_table JSON 为 TableDefinition
│   ├── LootConditionHandler(s|Info)  战利品条件分析与描述
│   ├── LootFunctionHandler(s)      战利品函数分析
│   └── LootParseUtil               解析工具
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
│   └── ProbabilityFormat           概率格式化
├── injection/    战利品注入
│   ├── ArchaeologyLootInjector     注入器接口
│   └── ArchaeologyLootInjectors    全局注册器（单例）
└── condition/    自定义战利品条件
    ├── ModLootConditions           条件类型注册
    ├── MudDredgingCondition         泥底打捞附魔资格条件
    └── ToolEnchantmentChanceCondition 工具附魔等级概率条件
```

## 3. 目录数据结构

[`LootTableCatalog`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/LootTableCatalog.java) 定义跨模块共享的记录类型，供 `journal`、`network`、`client`、`command` 等包统一引用：

```
TableDefinition{
  id: ResourceLocation,          表 ID
  displayName: Component,        展示名
  type: String,                  声明类型
  items: List<ItemDefinition>,   物品条目
  simulationCount: int,          模拟次数（0 表示未模拟）
  childTables: List<ResourceLocation>, 引用的子表
  childTableProbabilities: List       子表至少产出一个物品的摘要与分场景概率
}

ItemDefinition{
  id, displayName, tooltipHint,
  probability: String,           概率字符串（"?"/"0"/"<0.01%"/"12%"，按 2 位有效数字格式化）
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

`acquisitionPaths` 记录一个物品在表中的所有获取路径（可能来自不同 pool、不同子表、不同条件），用于在 UI 中展示"如何获得"。`injected=true` 表示该条目不在原始 JSON 中，而是模拟期由 GLM 或 LootTableEvents.MODIFY 注入发现的。

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

管理页面的候选集合以服务端 `ReloadableServerRegistries` 中 `Registries.LOOT_TABLE` 的 key 为唯一权威，不读取客户端资源列表，也不猜测不存在的表。当前明确排除 path 以 `entities/`、`blocks/` 开头的实体和方块掉落表。

追踪规则仍支持前缀和精确 ID。为允许从宽泛前缀中移除单表，`excluded_loot_tables` 保存精确排除项：关闭一张被前缀命中的表时加入排除项；重新开启时移除排除项，若原规则未命中则把精确 ID 加入追踪规则。修改权限要求服务端权限等级 2，变更后重建目录并广播最新管理快照。

### 5.2 自定义表名

名称 key 始终由 `LootTableNames.createTranslationKey(ResourceLocation)` 自动生成。游戏资源语言是正式、只读来源；服务端配置只补充资源中不存在的 key。服务端按标准语言 JSON 保存到 `config/unsuspiciousblock/loot_table_lang/<language>.json`，适合整合包作者直接分发，也便于开发者把确认后的条目复制回 `assets/unsuspiciousblock/lang/`。旧世界的 `serverconfig/unsuspiciousblock-loot-table-names.json` 会在每次加载时合并迁移（仅补充缺失 key，幂等；服务端启动与 `/reload` 均会触发），旧文件保留。

客户端收到的服务端名称只驻留当前连接的内存，断开连接时清除，不再写入客户端全局配置。`ClientLootTableLanguageStore` 扫描当前游戏资源栈的语言 JSON，记录最终提供 key 的 Resource Pack ID；`ClientLanguageMixin` 仅在同语言资源缺少 key 时合并服务端补充值。因此资源值优先、只读，服务端配置不会覆盖模组或 Resource Pack 已提供的正式翻译。

管理页面以 `(languageCode, tableId)` 保存未提交草稿，切换表或语言不会丢失。一次“应用更改”通过批量 payload 提交全部草稿；服务端整体校验后每种受影响语言只写盘一次、广播一次。客户端语言查询直接读取新的内存快照，无需重载资源。管理员还可选择本地标准语言 JSON，预览新增、更新、资源跳过、未匹配与非法条目后批量导入。只有当前服务端已注册且可管理、并由自动规则构造的 key 会进入提交。

缺失 key 的检测与告警仍采用批处理，但只读取当前游戏资源和服务端补充配置，不再自动生成客户端覆盖文件。已有模组资源翻译不会被误判为缺失。

## 6. 解析流程

[`LootTableJsonParser`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootTableJsonParser.java) 解析 loot_table JSON 为 `TableDefinition`：

- 遍历 pools / entries，提取物品、条件、函数。
- `LootConditionHandler` / `LootFunctionHandler` 把原版条件/函数转译为可读的 `LootConditionInfo`（含描述与概率提示），供 UI 展示。
- 识别 `loot_table` 类型 entry（嵌套表引用），建立父子关系。
- 此阶段概率字段为 `"?"` 占位符，等待模拟填充。

解析结果交给 [`ArchaeologyJournalCatalog.load`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/catalog/ArchaeologyJournalCatalog.java)（在 `journal/catalog/` 包），构建完整目录与分类结构。解析细节见 [考古笔记系统](journal.md) 的目录构建部分。

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

| 出现次数 | 条件 | 概率字符串 |
|---|---|---|
| - | 当前代表场景静态不可达 | `"0"` |
| 0 | `hasConditions` | `"?"`（条件性物品，模拟可能未覆盖） |
| 0 | 无条件 | `"<0.01%"` |
| >0 | - | `formatPercent(appearances / 10000)` |

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
实体目标、伤害来源与计分范围提供具体描述。存在上述场景条件时，概率文字明确标为
“条件满足时至少出现一次”，不是这些条件在自然游戏过程中的发生概率。

`ItemDefinition.scenarioProbabilities` 保存代表场景的内部统计结果。静态条件证明不可达的物品或子表在
对应场景中记为 `0`，可触发但 10000 次均未出现才记为 `<0.01%`。目录摘要取这些场景的最小值与最大值；
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

模拟结果通过 `LootProbabilityData`（SavedData，附加在 overworld）持久化。每个签名和子表入口同时保存摘要概率与 `scenario_key -> probability`；恢复时由规划器重建场景条件描述。嵌套引用的获取路径在每个根表视角下保留第一层子表来源，使孙表条件导致的 `0` 场景能够汇总到直接子表。动态条目的直接来源标记（`hasDirectSource`）与子表来源列表（`sourceChildTables`）会一并持久化到父表缓存，恢复时优先使用缓存的来源信息，仅在其缺失时才用直接子表及其后代缓存中的相同签名重建获取路径。旧单值 NBT 可读，但统计口径或运行时表来源变化会通过缓存版本自动失效；当前版本为 `loot-analysis-v12`。模拟异常或无法取得有效表时不写入缓存。`/usb journal reload` 只清除此处的概率缓存与内存目录，不清除玩家笔记进度。详见 [考古笔记系统](journal.md) 的目录构建部分。

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
- **平台注入器**：Fabric 端如需新的注入逻辑，实现 `ArchaeologyLootInjector` 并在 `onInitialize` 调 `ArchaeologyLootInjectors.register`。
- **模拟调优**：`SIMULATION_COUNT`（精度 vs 性能）、`TICK_BUDGET_NANOS` 与批次大小（吞吐 vs tick 占用）是主要可调参数。

## 11. 相关文档

- [考古笔记系统](journal.md) - 目录构建、玩家进度、追踪的上层
- [配置与第三方联动](config-integrations.md) - 收录前缀等配置项
- [Mixin 总览](mixin.md) - `NestedLootTableMixin`（嵌套表捕获）
- [docs/journal-categories.md](../journal-categories.md) - 目录分类规则与领域语言
