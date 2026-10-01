# 战利品表系统

> `loottable/` 包的架构：如何解析、收录、注入战利品表，如何为物品生成稳定签名，以及如何估算概率。
> 本文件是战利品表子系统的唯一权威（**功能技术路线**）。解析细则、签名派生、场景规划、缓存格式与展示派生规则见 [战利品表机制细节](../internals/loottable-mechanics.md)；目录构建的上层调度与玩家进度见 [考古笔记系统](journal.md)。

## 1. 代码地图

| 层 | 位置 |
|---|---|
| 资源快照 | `loottable/source/LootTableSourceSnapshot.java`（一次列举拿到全表"有效原文 + 完整资源栈"） |
| 引用图 | `loottable/graph/`：`LootTableReferenceGraph`（直接子表/可达集/子树/SCC/子树摘要）、`LootTableEdge`（边类型）、`RuntimeLootLinks`（平台注入关系与钓鱼上下文判定的**唯一来源**） |
| 编译 | `loottable/analysis/`：`LootTableCompiler`、`CompiledLootTable`、`LuckSpec`、`LuckGateAnalysis`、`LuckGate`、`LootConditionHandler(s)`+`LootConditionInfo`、`LootFunctionHandler(s)`+`describe` 通道、`LootFunctionInfo`（`FunctionFidelity` / `FunctionEffectKind`）、`LootFunctionDescriptions`、`LootFunctionDescribeSupport`、`LootParseUtil`（条件分析/函数链/类型规范化的唯一实现） |
| 读模型与查询 | `loottable/catalog/`：`LootTableCatalog`（跨模块共享记录类型）、`LootOriginKind`（来源集合语义）、`Probability`、`DeclaredChance`、`LootTableProjector`、`StaticTableProjection`、`ItemDefinitionAccumulator`、`CatalogQueryIndex`、`CatalogTableDto`、`LootTablePattern`、`LootTableNames`、`LootTableTranslationStore`、`MissingTranslationKeyExporter` |
| 签名 | `loottable/signature/`：`LootResultSignature`（核心）、`LootResultMatcher`、`LootResultPreviewCache`、`LootCounts`、`SignatureExcludedComponents` |
| 模拟 | `loottable/simulation/`：输入与参数（`SimulationInput` / `ScenarioParams` / `SimulationInputKey`）、场景规划（`SimulationScenarioPlanner` / `SimulationScenario` / `LootConditionFingerprint`）、引擎（`LootProbabilitySimulator` / `LootProbabilitySimulationJob` / `SimulationMeasurement`）、调度（`LootProbabilitySimulationWorker`）、约束目录（`SimulationConstraintCatalog` / `ToolOption`）、上下文填充（`SimulationProfile` / `LootContextParamFiller` / `SimulationFakePlayer` / `SimulationFishingHook` / `LootSimulationScope` / `PlayerStateProbe`）、推荐与协助（`SimulationAssistTarget` / `RecommendationSolver`）、函数捕获（`FunctionTraceSession` / `TraceNode` / `TraceHandle` / `ObservedFunctionChain` / `FunctionObservationSummary`）、展示（`PathHintAnalyzer` / `ProbabilityFormat`） |
| 注入 | `loottable/injection/`：`ArchaeologyLootInjector`（接口）、`ArchaeologyLootInjectors`（全局单例） |
| 自定义条件 | `loottable/condition/`：`ModLootConditions`、`ToolEnchantmentCondition`（唯一自定义条件） |
| 诊断 | `loottable/diagnostics/LootSimulationMetrics.java`（可选性能观测，默认关闭） |
| 上层合作者 | `journal/catalog/`：`ArchaeologyJournalCatalog`（组装静态读模型）、`LootTableAnalysisSession`（一代分析结果）、`CatalogGeneration`（原子发布整代目录） |
| 数据资源 | `data/unsuspiciousblock/loot_table/`（含 `gameplay/`、`chests/`…）；名称补充 `config/unsuspiciousblock/loot_table_lang/<language>.json` |
| 网络 | `CatalogTableDto` 由 `CatalogStreamCodec` 编解码（清单见 [网络与同步](../foundation/network.md)） |
| Mixin | `interaction/NestedLootTableMixin`（嵌套表观测）、`loottable/Simulation*ConditionMixin`（7 个单目标，条件作用域转交）、`loottable/LootItemConditionalFunctionMixin`（包装 `run` 调用＝外层条件通过进入执行体）、`loottable/LootTableSplitterMixin`（包装拆栈器的输入/输出 `Consumer`，拆栈副本继承观测链） |
| 平台差异 | 注入器：Fabric 显式 `maybeReplace`，NeoForge 空实现（GLM 在 `getRandomItems` 内完成）；重载监听：`ResourceManagerHelper` vs `AddReloadListenerEvent` |

## 2. 数据流

**静态目录（四层管线）**

```
ResourceManager
   └─(每次重载只读一次)→ LootTableSourceSnapshot
          ├─→ LootTableReferenceGraph ─→ 闭包 / SCC / 子树摘要 / 分型边
          └─→ CompiledLootTable ──────→ LootTableProjector → StaticTableProjection
                                            ↑                       │
                        LootTableReferenceGraph ────────────────────┘

StaticTableProjection ─→ 概率模拟 / 缓存恢复 ─→ 测量值 → CatalogTableDto
```

每层只依赖上一层。**编译产物上下文无关**：同一个子表在不同引用位置产出的物品路径不同，因此只记"本表直接物品路径 + 引用位置"，不缓存展开后的子表结果；投影期才把父表条件与函数链拼上去。

**概率模拟（按输入）**

```
启动：每表只跑"基准输入"（基准场景 + 默认工具/幸运 0/无附魔）
玩家按需：选场景 + 调参数 → RequestScenarioSimulationPayload
   └─ ScenarioSimulationHandler（限流 + 参数构造）→ 约束目录逐项校验
        └─ 命中缓存？ → 直接派生展示值
        └─ 未命中 → HIGH 队列 → worker 主线程每 tick 15ms 预算分片推进
             └─ 结果只回给请求者（不写共享目录）
```

## 3. 关键类

| 类 | 职责 |
|---|---|
| `source/LootTableSourceSnapshot` | 一次列举拿到全表有效原文 + 完整资源栈，解析/建图/哈希共用 |
| `graph/LootTableReferenceGraph` | 引用关系唯一权威：直接子表 / 可达集 / 子树 / 强连通环 / 子树摘要 |
| `graph/RuntimeLootLinks` | 平台注入关系与"钓鱼上下文"判定的唯一来源；注入门槛（`MUD_DREDGING_GATE`）也在此声明 |
| `analysis/LootTableCompiler` | 单表 JSON → 上下文无关的编译产物 |
| `analysis/LootParseUtil` | 条件分析、函数链拼接与类型规范化的唯一实现 |
| `catalog/LootTableProjector` | 沿引用把编译产物链接起来（首跳归属、条件继承顺序、函数继承与降级） |
| `catalog/Probability` | 概率值 sealed 类型（四态），构造即校验 |
| `catalog/CatalogQueryIndex` | 子树物品等跨表聚合的唯一入口 |
| `signature/LootResultSignature` | 进度匹配与持久化的核心 |
| `simulation/LootProbabilitySimulator` | 单输入模拟引擎 |
| `simulation/LootProbabilitySimulationWorker` | 主线程 tick 驱动（双优先级队列、去重键 `(表, 输入键)`） |
| `simulation/SimulationConstraintCatalog` | 目录签发的约束描述（场景/工具/附魔上限/抽样档位），**不枚举候选输入** |
| `simulation/PathHintAnalyzer` | 静态信息性提示 + 基准值→展示值派生 |
| `simulation/ProbabilityFormat` | 概率值与声明触发率 → 展示文本（唯一格式化入口，仅 UI 边界调用） |
| `injection/ArchaeologyLootInjectors` | 全局注入器单例（平台差异化） |
| `condition/ModLootConditions` | 自定义条件类型的注册与展示描述 |

## 4. 目录数据结构

[`LootTableCatalog`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/LootTableCatalog.java) 定义跨模块共享的记录类型，供 `journal`、`network`、`client`、`command` 统一引用：

```
TableDefinition{ id, displayName, type, items, simulationCount,
                 childTables, childTableProbabilities }

ItemDefinition{ id, displayName, tooltipHint, probability, signature, acquisitionPaths,
                injected, scenarioProbabilities, origins, observedFunctions }

LootAcquisitionPath{ sourceChildTable, sourceItemTag, entryConditions, inheritedConditions,
                     functionUncertainty, luckAffected, luckGate, luckRequirements, functions }
```

- `acquisitionPaths` 记录一个物品在表中的所有获取路径（可能来自不同 pool、不同子表、不同条件），用于在 UI 中展示"如何获得"。
- `origins` 是来源**集合**（`LootOriginKind`：`STATIC` / `MOD_INTEGRATION` / `UNKNOWN_RUNTIME`），一个结果可同时有多类来源；兼容字段 `injected` 只是它的投影。**动态发现 ≠ 平台注入**。
- `sourceChildTable` 只记**第一跳**（孙表的条件汇总回直接子表，父表页签归属才不错位）；`functionUncertainty` 只描述函数求值等级；`luckAffected` 标记该路径所在池是否受幸运影响。

### 4.1 概率值类型

概率**不是**字符串。[`Probability`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/Probability.java) 是 sealed 值类型，**四个**互斥状态取代了原先共用一个字符串的五种语义：

| 状态 | 含义 | 展示文本 |
|---|---|---|
| `Unknown(reason)` | 没有可展示的测量值，成因见 `UnknownReason`（**不是**不可达） | `?` |
| `Unreachable` | 静态可证明在任何可表示的输入下都不可产出 | `0%` |
| `Measured(lower, upper?)` | 实际抽样比例；`upper` 非空表示跨场景区间 | `12%` / `3%-7%` |
| `NeedsCondition(hints)` | 当前输入下没有可用路径，但路径引用了可调整的旋钮或条件 | 「需要条件」 |

- `UnknownReason ∈ { UNCOVERED, UNPARSED, NOT_SIMULATED, SIMULATION_FAILED, EVICTED }`；网格一律 `?`，由 tooltip 逐条分述。构造即校验有限数值与 `0 ≤ lower ≤ upper ≤ 1`，非法值在构造点抛出。
- **抽样零命中是 `Measured(0.0)`，展示为「未命中」**；`0%` 只留给静态可证明的不可达。旧写法 `<0.01%` 已作废——它等于声称 p 小于抽样分辨率，而 n=10000 时真实概率 0.01% 仍有约 36.8% 的概率零命中。
- 可适用性（`NeedsCondition` / `Unreachable` / `Unknown(UNCOVERED)`）与计算状态（`Measured` / `Unknown(NOT_SIMULATED / SIMULATION_FAILED / EVICTED)`）是**两个轴**，由服务端派生；客户端只按显示优先级链渲染（可适用性状态 → 可展示时的声明触发率 → 模拟值）。
- **落盘用窄类型 `SimulatedValue`**（只有 `Unknown` / `Measured`），**格式化只发生在 UI 边界**（`ProbabilityFormat`）。数据层、存档缓存、网络与排序一律用数值，**不存在"把界面文本反解回数值来排序"的做法**。

## 5. 收录范围匹配

[`LootTablePattern`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/LootTablePattern.java) 把配置字符串解析为可匹配的规则：

| 语法 | 含义 | 示例 |
|---|---|---|
| `namespace:path` | 限定命名空间 | `minecraft:gameplay/fishing` |
| `path`（裸） | 匹配所有命名空间 | `archaeology/` |
| path 以 `/` 结尾 | 前缀匹配 | `archaeology/` 命中所有考古表 |
| path 不以 `/` 结尾 | 精确匹配 | `minecraft:chests/buried_treasure` |

默认规则定义在 `ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES`（完整列表见 [配置与第三方联动](../foundation/config-and-integrations.md)）。命中规则的表被纳入目录，并通过引用闭包递归发现子表；`stripFrom` 剥离命中前缀，用于生成本地化 key 与展示名。

### 5.1 追踪管理页与自定义表名

- **候选集合以服务端 `ReloadableServerRegistries` 中 `Registries.LOOT_TABLE` 的 key 为唯一权威**，不读客户端资源列表、不猜测不存在的表；明确排除 path 以 `entities/`、`blocks/` 开头的表。规则支持前缀与精确 ID，`excluded_loot_tables` 保存精确排除项；**修改权限要求服务端权限等级 2**，变更后重建目录并广播最新管理快照。
- **名称 key 始终由 `LootTableNames.createTranslationKey(ResourceLocation)` 生成**：游戏资源语言是正式、只读来源；服务端配置只补充资源中不存在的 key，保存到 `config/unsuspiciousblock/loot_table_lang/<language>.json`。客户端收到的名称只驻留当前连接内存，断开即清除；`ClientLanguageMixin` **仅在同语言资源缺少 key 时**合并服务端补充值。
- 管理页以 `(languageCode, tableId)` 保存未提交草稿，切换表或语言不丢失；一次"应用更改"批量提交全部草稿，每种受影响语言只写盘一次、广播一次。管理员还可选择本地标准语言 JSON，预览新增/更新/资源跳过/未匹配/非法条目后批量导入。**只有当前服务端已注册且可管理、并由自动规则构造的 key 会进入提交。**

## 6. 战利品注入

[`ArchaeologyLootInjectors`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/injection/ArchaeologyLootInjectors.java) 是全局单例注入器（接口 `ArchaeologyLootInjector` 为 `@FunctionalInterface`，签名 `(tableId, drops, random) -> {}`；Fabric 实现在 `fabric/.../loot/`，NeoForge GLM 在 `neoforge/.../loot/`），**平台差异化**：

| 平台 | 注入器 | 机制 |
|---|---|---|
| Fabric | `FabricArchaeologyLootInjector` | 模拟时显式调用 `maybeReplace`，把模组物品纳入概率统计与签名派生（Fabric 没有 GLM） |
| NeoForge | 空实现（默认） | 注入由 `IGlobalLootModifier`（GLM）在 `getRandomItems` 内部完成，模拟器自然能抽到注入物品，因此不注册注入器 |

**模拟必须调用普通 `LootTable.getRandomItems`**：NeoForge 会由该入口应用 GLM，而 `getRandomItemsRaw` 不会。嵌套表由原版 resolver 从同一运行时注册表解析，且嵌套抽取用 `getRandomItemsRaw`，因此 GLM 只在根表应用一次。

## 7. 自定义战利品条件

`condition/` 包只定义**一个**模组自定义战利品条件：

- `ModLootConditions` 注册唯一规范名 `unsuspiciousblock:tool_enchantment` 并登记展示描述（Fabric 用 `Registry.register`，NeoForge 用 `DeferredRegister`）。`ToolEnchantmentCondition` 是**一个类型表达完整语义**的条件：`enchantment` 必填、`min_level` 可选默认 1（`Codec.intRange(1, 255)`）、`chance`（`LevelBasedValue`）缺省表示纯资格门槛。因此"需要工具附魔"与"按该附魔等级掷概率"不再需要并列两条条件，tooltip 也从相邻两行变成"一行附魔展示名 + 按需的等级/概率子行"。
- **门槛只有一份声明**：`RuntimeLootLinks.MUD_DREDGING_GATE`（`enchantment` = 泥地打捞、`min_level` 缺省 1、`chance` = `linear(0.2, 0.1)`）。Fabric 在 fishing 表追加父表 pool；NeoForge 的 GLM 数据只留 `loot_table_id` 过滤，同一门槛在 `FishingLootModifier.doApply` 判定。**被注入子表 `gameplay/fishing/mud_dredging` 自己的池不写条件**，只描述"进来之后产出什么"——父表页答"能不能进本表"，子表页答"进来之后各物品的份额"。
- **条件类型的注册时序约束**：必须在 `UnsuspiciousBlockCommon.init()` 之前完成（见 [架构总览](../foundation/architecture.md) 的初始化流程）。
- 引用未注册条件类型会让**整张表**解析失败并不注册，因此**资源与注册名必须同批发布**。旧注册名 `mud_dredging` / `random_chance_with_tool_enchantment` 与旧 `swamp` 字段都已删除，不承诺跨版本兼容。

## 8. 函数规则描述与运行时观测

函数有两条**不可互推**的信息来源（注入点、预算与降级口径见 [机制细节](../internals/loottable-mechanics.md) 的「函数规则与执行捕获」）：

- **静态规则**：`LootFunctionInfo` 描述"声明了什么"（描述 + `FunctionFidelity` + `FunctionEffectKind` + 函数自身条件 + 子函数 + 有界元数据），随获取路径下发；`tooltipHint` 由它派生，描述与预览求值解耦。
- **运行时观测**：`FunctionObservationSummary` 记录"本次模拟实际进过哪些执行体"，按结果签名随输入级测量下发；只陈述进入过，不宣称产物变化、也不宣称生成过程已完整捕获。
- 函数自身的条件**原位保存在函数节点**，不等于物品掉落条件；两轴在 tooltip 分为「生成规则」与「本次模拟观测到的函数」两区，多条观测链分行并列，不拼成一条因果链。

## 9. 扩展点

- **新增收录范围**：改配置的追踪前缀列表（`ILootTableConfig.getArchaeologyPathPrefixes()`），或通过数据包新增命中前缀的战利品表。
- **新增实例态组件排除**：在 `ILootTableConfig.getSignatureExcludedComponents()` 加组件 id（默认 `relics:data`），含这些组件的物品即按物品级（`PLAIN`）收录。该列表已并入每表哈希，改配置会让相关表重算；但旧存档里按组件变体存下的进度键会成为孤儿——不渲染、不参与完成度闭包，需按需清理（**本项改动未提供存档迁移**）。
- **自定义签名类型**：在 `LootResultSignature.SignatureType` 添加枚举，注意 `fromStoredKey` 的兼容性。签名类型变更会影响玩家存档，需在 `JournalNbtMigrator` 补充连续迁移步骤。
- **新增函数描述**：在 `LootFunctionHandlers` 为该函数补 `describe`（文本/参数/元数据助手在 `LootFunctionDescribeSupport`），按 `FunctionEffectKind` 选效果类别、动态数值照实表达、内层函数挂 `children`；未迁移的 handler 会自动回退 `describeHint` 并标 `PARTIAL`。描述文本走 `function.*` 本地化 key，`en_us.json` / `zh_cn.json` 必须同步。
- **开发模式调试表**：把核对用表放进 `data/unsuspiciousblock/loot_table/archaeology/debug/`，并配 `journal_categories/dev_debug.json`；收录由 `LootDebugMode` 门禁（系统属性 `usb.loot.debug` / 环境变量 `USB_LOOT_DEBUG`），默认关闭时普通玩家看不到。覆盖范围与开启方式见 [机制细节](../internals/loottable-mechanics.md) 的「开发模式调试表」。
- **新增战利品条件**：参考 `ToolEnchantmentCondition`，在 `ModLootConditions` 注册类型与展示描述，两端各自注册到注册表；资源与本批必须同批落地。展示描述只能做到"成立但有未展示约束"时，调 `LootConditionHandlers.partial(info)` 告诉客户端改用斜体。
- **新增场景控制类型**：把类型加入 `SimulationScenarioPlanner` 的 `SCENARIO_CONDITIONS`，并为其补一个 `test` 转交作用域的窄 Mixin；类型须实现为 record 或覆写 `toString()`。**加之前先问"它该不该由玩家调参代替"**——`match_tool` 的教训是：把它做成场景布尔会让它与读同一个上下文的函数互不相干。
- **新增参数旋钮（四处缺一不可）**：`ScenarioParams` 加字段（构造点给不变量）→ `SimulationInputKey.of` 加一段规范编码（**顺序固定**，缓存键与网络标识共用）→ `SimulationConstraintCatalog.resolve` 加签发校验 → `SimulationProfile` 加 `withXxx` 并由 `ScenarioParams.applyTo` 套用。少了校验就是越权入口，少了 key 段就会与别的参数共用缓存。
- **新增运行时联动边**：在 `RuntimeLootLinks` 声明标识符与边，并在 `LootTableEdge.Kind` 中显式选型——`RUNTIME_INJECTION` 只参与收录闭包、目录层级与哈希，**不参与静态语义链接**，条目仍由模拟期动态发现并保持 `injected=true`。合成边只在两端资源都存在时才会注入。
- **修改概率口径**：`Probability` 是不变式载体，新增状态需同时更新存档与网络的穷尽 codec 与 `ProbabilityFormat`；任何情况下都不要把展示文本写回数据层。
- **新增存档字段**：`format_version` 必须 +1，并在读取路径上把"旧版本/类型不符"一律按**缓存未命中**处理。`discovery` 层的东西**永远不能进会被 LRU 淘汰的层**——那会让条目在淘汰后从界面上消失。
- **平台注入器**：Fabric 端如需新注入逻辑，实现 `ArchaeologyLootInjector` 并在 `onInitialize` 调 `ArchaeologyLootInjectors.register`。
- **模拟调优**：`ScenarioParams.DEFAULT_SAMPLE_COUNT` 固定为 10,000，并同时作为输入身份的一部分用于缓存一致性；`MAX_PARAMETER_COMBINATIONS`（缓存规模）、`TICK_BUDGET_NANOS` 与批次大小（吞吐 vs tick 占用）、`MAX_SCENARIOS` / 展开预算（场景规模 vs 构建成本）仍需按性能约束调整。抽样次数不再是玩家旋钮，也不提供旧档位迁移。

## 10. 约束与陷阱

- **不得从原始 JSON 重建模拟用表**：运行时表必须取自 `ReloadableServerRegistries`，否则会绕过 Fabric 加载期修改（`LootTableEvents.MODIFY`），也无法表达组合条件语义。
- **模拟不能放后台线程**：`getRandomItems` / `LootContextParamFiller` 触碰非线程安全的 `LegacyRandomSource`，只能在主线程 tick 里分片跑。
- **参数填充只能填 paramSet `allowed` 内的参数，且 required 与 optional 都要填**：只填 required 会把"没填"伪装成"条件不成立"。
- **判 paramSet 时空 `type` 等价于 `generic`**（vanilla 语义），不要当成"未知 paramSet"。
- **`isActuallyEnchanted` 不能用 `stack.has(ENCHANTMENTS)`**（工具默认带空组件会误判）。
- **`simulation_fingerprint` 只对按字段值生成 `toString()` 的类型稳定**；不稳定的条件会按真实逻辑求值并计入"未覆盖"告警，数值口径与场景估算不同（详见 [机制细节](../internals/loottable-mechanics.md) 的「条件场景策略」）。
- **展示值只能由服务端派生**（`deriveTable` / `deriveDisplay`），客户端不得本地重推——注入边不写在任何 JSON 里，本地重推必然漏项。
- **概率的落盘只用 `SimulatedValue` 窄类型**，派生结论不进存档（否则改静态规则就要迁移存档）。
- **按需结果不写共享目录**：写进去会让两个玩家互相覆盖对方的界面。
- **注入门槛只能声明一处**（`RuntimeLootLinks`），两端注入实现与 tooltip 都取自它——各写一份必然导致"玩法与 tooltip 各说一套"。
- **抽样次数固定为 10,000**：玩家不再编辑抽样档位；`ScenarioParams` 仍将该值纳入输入身份，用于服务端缓存一致性与结果口径校验。
- **缓存查询不等于模拟**：`RequestScenarioCachePayload` 只读取服务端共享缓存，只有显式“应用并计算”才会启动模拟；客户端状态必须按 generation、表哈希和输入键校验响应。
- **函数自身的条件不是物品的掉落条件**：函数条件原位保存在函数描述节点（`LootFunctionInfo.conditions()`）上，不并入条目条件；条件失败通常只让该函数不生效——有条件的 `set_count` 仍会掉基础物品，只有可能改变物品身份的变换才保守计入条目条件。
- **模拟期动态发现的条目 ≠ 平台注入**：来源分型为 `STATIC` / `MOD_INTEGRATION` / `UNKNOWN_RUNTIME`，动态发现默认记 `UNKNOWN_RUNTIME`；"没有静态候选"或"来自 mod 命名空间"都不是注入证据，只有自有注入执行点或适配器明确声明才算 `MOD_INTEGRATION`；同签名的普通来源与模组联动会**合并为一条条目**（注入路径并入、来源集合取并集），不会出现两条同签名条目。`injected` 只是兼容投影（详见 [机制细节](../internals/loottable-mechanics.md) 的「函数规则与执行捕获」）。

## 11. 相关文档

- [战利品表机制细节](../internals/loottable-mechanics.md) —— 解析细则、签名、场景规划、缓存格式、展示派生规则
- [考古笔记系统](journal.md) —— 目录构建、玩家进度、追踪的上层
- [附魔系统](enchantment.md) —— `ToolEnchantmentCondition` 与泥底打捞的完整玩法
- [配置与第三方联动](../foundation/config-and-integrations.md) —— 收录前缀、日志上限等配置项
- [网络与同步](../foundation/network.md) —— `CatalogStreamCodec` 与按需模拟链路
- [Mixin](../foundation/mixin.md) —— `NestedLootTableMixin` 与模拟条件作用域
- [客户端与 GUI](client-ui.md) —— 目录网格页、场景页与追踪管理页
- `docs/journal-categories.md` —— 目录分类规则（`docs/` 下同级文件）
