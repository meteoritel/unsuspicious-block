# 战利品表机制细节

> 本文档说明战利品表子系统的底层实现：四层解析管线、签名与预览缓存、单输入模拟引擎与场景规划、主线程 tick 调度、结果缓存失效与按需请求链路。
> 本文件不参与主任务导航，由[战利品表系统](../subsystems/loottable.md)按需链接；函数规则描述、执行捕获与展示派生规则见[战利品表函数与展示规则](loottable-functions-and-display.md)。

## 1. 解析管线细则

[`ArchaeologyJournalCatalog.load`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/journal/catalog/ArchaeologyJournalCatalog.java) 按四层流水线构建静态目录：

```mermaid
flowchart LR
    Snap["1. capture 快照"] --> Graph["2. build 引用图"]
    Graph --> SCC["3. 追踪可达集 SCC 环排除"]
    SCC --> Compile["4. compile 单表编译"]
    Compile --> Project["5. project 沿引用路径投影"]
    Project --> Hash["6. 组装读模型 + 算每表哈希"]
    Hash --> Pub["7. 单个 volatile 原子发布"]
```

- **快照（`LootTableSourceSnapshot.capture`）**：通过一次 `listMatchingResourceStacks` 获取全部表。栈首是当前重载生效的 JSON 原文，整个资源栈用于计算稳定内容哈希；原文与 `JsonElement` 采用惰性解析并缓存。
- **引用图（`LootTableReferenceGraph.build`）**：合并静态 JSON 引用边与平台声明注入边。合成边启用双端存在守卫（目标已被 JSON 引用覆盖时不重复加边），邻接表去重保序。
- **环排除**：仅对收录根的初始可达集计算强连通分量（SCC），将闭环成员移出目录并记录一次性告警；与考古目录无关的第三方表循环不产生噪音日志。
- **编译（`LootTableCompiler`）**：将单表 JSON 转为上下文无关的 `CompiledLootTable`。遇到 `loot_table` 引用只记录 `ReferenceSite` 而不展开；物品标签在编译期直接展开为具体物品签名。
- **投影（`LootTableProjector`）**：沿引用边拼接编译产物，落地三条投影规则：
  1. 首跳子表归属（孙表汇总回直接子表）；
  2. 条件继承顺序（外层传入 → 本表累计 → 当前条目自身）；
  3. 函数链继承与签名降级。同一子表在多处以不同条件被引用时保留各自独立路径，不合并丢失上下文。
- **静态读模型**：`StaticTableProjection` 展平出的目录只保留实际存在物品产出的表（分析会话仍保留空表投影）；未模拟条目的初始概率占位为 `Probability.unknown(UnknownReason.UNCOVERED)`。

**编译与投影解耦的原因**：同一张子表在不同父表引用点的条件与函数链各不相同，因此编译阶段绝不缓存展开后的子表结果，由投影阶段按引用路径实时合成，且条件分析、函数链拼接与多路径归并各自保持唯一实现（`LootParseUtil`、`ItemDefinitionAccumulator`）。

### 1.1 多路径物品提示合并

`ItemDefinitionAccumulator` 按 `Component` 抽象语法树（含翻译键与参数）而非渲染后文本对同签名路径的非空提示去重，按路径出现顺序使用本地化 `alternative_separator` 拼接（展示名分歧同样按组件结构判定）。不同路径若拥有相同的数量区间（如内置河流淘洗金粒的两条 `1-3` 路径）仅展示一次；不同区间则展示为 `数量: 1-3 / 数量: 3-6`。该说明表达各独立路径的函数效果，而非单次抽取总产量。

## 2. 签名机制

[`LootResultSignature`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/signature/LootResultSignature.java) 将“目录物品定义”与“玩家实际获得的物品栈”绑定为一致的身份键：

```java
public record LootResultSignature(ResourceLocation itemId, SignatureType type, @Nullable String data) {}
```

| `SignatureType` | 语义 | 构造场景 |
|---|---|---|
| `PLAIN` | 纯物品身份，忽略组件差异 | 无特殊组件的普通物品，或含实例态排除组件的物品 |
| `COMPONENT_EXACT` | 严格组件匹配 | 含非排除 `DataComponentPatch` 的物品（`data` 存组件 JSON） |
| `ENCHANTED_APPROX` | 附魔近似归类 | 带附魔的物品与附魔书（避免海量附魔组合引起条目爆炸） |
| `ENCHANTED_RANDOM` / `ENCHANTED_LEVEL` | 历史附魔签名兼容值 | 读取旧数据时自动折叠为 `ENCHANTED_APPROX` |
| `APPROX_ITEM_ONLY` | 异常回退签名 | 组件编码失败等边界情况 |

### 2.1 实例态组件排除

配置 `ILootTableConfig.getSignatureExcludedComponents()`（默认包含 `relics:data`）声明的组件会在每次实例生成或读取时随机物化（例如 Relics 在 `verifyComponentsAfterLoad` 中随机写回品质），不属于战利品表静态定义。命中此类组件的物品在签名派生时统一退化为 `PLAIN`：

- **退化为 `PLAIN` 而非“剔除后编码 `COMPONENT_EXACT`”**：精确匹配哈希与组件比对读取未剔除的原始 `ItemStack`，双侧不对称会导致永远匹配失败。退化为 `PLAIN` 后仅比对物品 ID，并与静态路径保持同键合并，代价是不区分随机实例品质。

### 2.2 稳定存储键与附魔判定

- **存储键格式**：`toStoredKey()` 生成形如以下文本的持久化与网络键（`fromStoredKey()` 对不带 `usb_sig|` 前缀的旧键按 `PLAIN` 兼容读取）：
  ```text
  usb_sig|TYPE|itemId|base64(data)
  ```
- **附魔判定陷阱**：原版工具默认携带空的 `ENCHANTMENTS` 组件，`isActuallyEnchanted(stack)` 必须检查 `stack.isEnchanted()` 或非空 `STORED_ENCHANTMENTS`，不能使用 `stack.has(DataComponents.ENCHANTMENTS)`。

### 2.3 预览栈与缓存

`createPreviewStack()` 根据签名重构用于展示或匹配的 `ItemStack`。由于 `COMPONENT_EXACT` 需将 JSON 文本解码为组件补丁且成本较高，[`LootResultPreviewCache`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/signature/LootResultPreviewCache.java) 在掉落追踪、容器追踪与菜单快照三条热路径共享缓存预览栈（由签名值唯一确定，仅在数据包重载时清空以控制内存）；单表模拟工作器内部仍持有独立的无锁 `HashMap` 缓存以减少并发开销。

## 3. 概率模拟：引擎与条件规划

模拟以 `SimulationInput`（已勾选原子条件 ID 列表 `enabledConditions` + `ScenarioParams`）作为输入身份。其中场景段身份使用稳定键：未勾选任何条件时固定为 `baseline`；勾选至少一项原子条件时对规范去重排序后的条件 ID 列表取 SHA-256 前 16 位 hex，记为 `cond:<16位hex>`。缓存键格式化为如下形式：

```text
scenario=baseline|luck=0.00|tool=minecraft:diamond_pickaxe|ench=|n=10000
```

### 3.1 模拟引擎 (`LootProbabilitySimulator`)

单个模拟任务 `LootProbabilitySimulationJob` 对一组 `(tableId, scenario, input)` 执行抽取并累积原始计数：

```mermaid
flowchart TD
    Start["LootProbabilitySimulationJob 启动"] --> Reg["从 ReloadableServerRegistries 取运行时表 (保留 Fabric 加载期修改)"]
    Reg --> Profile["合成 SimulationProfile (场景条件赋值 + TOOL/ORIGIN/THIS_ENTITY 等 allowed 参数)"]
    Profile --> Init["初始化本场景适用候选签名与计数器"]
    Init --> Loop{"循环 sampleCount 次"}
    Loop -->|"每次抽取"| Roll["lootTable.getRandomItems(lootParams)"]
    Roll --> Inject["ArchaeologyLootInjectors.get().maybeReplace (Fabric 显式注入 / NeoForge 由 GLM 完成)"]
    Inject --> Match["遍历产物并按 LootResultMatcher.resolve 匹配"]
    Match -->|"未匹配"| Derive["派生新签名并标记来源"]
    Match -->|"已匹配"| Dedup["单轮按签名去重计数"]
    Derive --> Dedup
    Dedup --> Loop
    Loop -->|"完成全部轮次"| Out["输出 SimulationMeasurement (含签名计数 / 直接子表计数 / 动态发现记录)"]
```

**关键机制说明**：
- **单次至少出现一次口径**：每轮抽取对同一签名去重后最多计数一次，统计“单次抽取出现该物品的概率”，不累计掉落堆叠总数或均值。
- **幸运处理与 `luckAffected`**：幸运值经 `LootParams.withLuck` 注入；编译器在编译阶段扫描每个池的 `bonus_rolls` 与 `quality`，若存在非零 `quality` 或无法证明恒零的 `bonus_rolls` 则标记 `LootAcquisitionPath.luckAffected = true`（用于向玩家提示“随幸运变化”，但无法静态覆盖第三方运行时注入暗读幸运的情况）。
- **钓鱼上下文判断**：依据表自身声明的 `type`（而非路径字符串）判定钓鱼表，并为其填充 `SimulationFishingHook`，使 `entity_properties` 下的 `in_open_water` 条件可正常求值。参数填充覆盖 `paramSet.allowed` 内的全部 required 与 optional 字段；缺少 `ORIGIN` 支持的 `paramSet`（如 `barter`）在构建期即被拦下。
- **热路径匹配优化**：`LootResultMatcher.CandidateIndex` 先按 `Item` 分组，再对精确组件候选按 `ItemStack.hashItemAndComponents` 分桶，同桶内仍严格执行 `ItemStack.isSameItemAndComponents` 校验，保持歧义返回 `null` 与单轮去重语义不变。

### 3.2 条件组合模型与按需规划 (`SimulationScenarioPlanner`)

运行时抽取直接使用加载后的 `LootTable` 和完整活谓词，保留 `all_of / any_of / inverted`、加载期修改与平台注入。静态阅读和推荐使用冻结后的条件事实，环境候选投影与实际执行各有职责。

- **逻辑与展示分离**：`LootConditionInfo` 的 `ALL / ANY / NOT / LEAF` 与 `logicChildren` 表达原树，`ConditionDisplayRow` 只负责文本。`ScenarioConditionEvaluator` 用同一份 `SimulationInput` 一次折叠整树；条件与附加门槛按顶层 AND 合并，幸运门槛独立核对。类别只用于说明可调参数。
- **冻结叶子事实**：分析 handler 生成 `ConditionSemantics`，随 `CatalogStreamCodec` 往返并参与目录冻结摘要。环境叶子按已签发 identity 读取勾选值；工具叶子保存物品/标签集合、数量与 vanilla `ENCHANTMENTS` 子谓词的附魔集合及等级范围，并保留组件存在性要求。附魔读取与 `ScenarioParams.createToolStack` 一致：空手没有附魔，非空覆盖替换默认组件，否则沿用工具默认组件。额外组件与未覆盖子谓词保留 `Unknown` 原因；已知约束为假仍能确定阻塞。
- **随机支持与未知**：`random_chance`、`tool_enchantment`、`table_bonus` 保存概率或等级曲线，正向判断 `p > 0`、反向判断 `p < 1`，超出档位的 `table_bonus` 沿用末档。求值传播极性与 Kleene 三值，已知短路保留，未解释规则只影响依赖它的路径；阅读与推荐均使用支持集，不抽签。页面据此区分阻塞、存在机会和未知，函数保留项单独展示。

| 条件类型 | 实际模拟处理 |
|---|---|
| `location_check`、`weather_check`、`time_check`、`entity_scores` | 注册 `SCENARIO` 能力，提取为 `ConditionOption`；单目标 Mixin 在 `LootSimulationScope` 内使用玩家勾选赋值，世界天气、区块和计分板保持原状 |
| `match_tool`、`tool_enchantment`、`table_bonus` | 使用 `SimulationProfile` 填充的真实 `TOOL` 栈和附魔执行原版或自定义谓词 |
| `block_state_property`、`damage_source_properties`、`entity_properties` | 使用填充的方块状态、伤害来源或假玩家/鱼钩上下文执行活谓词；静态未覆盖语义显示未知 |

- **环境候选投影**：`buildScenario` 只解释可控环境叶子，其余叶子取 `UNKNOWN`；仅剔除确定为假的路径。工具、随机与上下文条件继续交给实际抽取。
- **原子提取与身份**：`planConditions` 沿获取路径、函数条件和子表入口提取去重保序选项，整表共享节点与条件数量预算。预算耗尽或分析不完整时停用该表数值模拟并保留静态条目。`ConditionIdentity` 经实际注册表上下文编码、JSON 键排序后生成稳定摘要；不透明条件保留本代身份并执行真实逻辑，签发选项只采用稳定身份。
- **分支范围与路径见证**：`ScenarioBranchCatalog.Plan` 同源派生展示分支和完整候选。展示同义去重合并候选集合，原始 `sourceKey` 区分同名注入来源；`CatalogGeneration` 保存本代索引。`BRANCH` 只搜索该引用的集合，`ITEM / CHILD_TABLE` 可选目标的任意候选。空子表入口保留入口条件；纯运行时观测分支没有可规划路径，tag 分支保留目标级候选并说明分支推荐能力。成功返回分支、实际目标、物品签名与 pathId；引用在 generation/hash 所属内容版本内有效。
- **结构化有界搜索**：`RecommendationSolver` 先检查范围内是否已有路径支持当前完整输入，再沿 `ALL / ANY / NOT` 惰性生成需求。AND 合并时剔除同 identity 的真假冲突，OR 保留替代项，未涉及的勾选和附魔沿用当前值。工具与附魔仅搜索需求引用的维度，等级取当前值及语义变化点；wildcard 在已签发附魔域内构造候选，求值仍读取默认工具实际组件。幸运按真实 `LuckSpec` 公式在合法网格查找，动态 provider 保留未知。候选经约束目录及完整树回验，执行尝试与时间预算由求解器统一管理。

搜索终态为 `FOUND`、`NO_CANDIDATE`、`UNSUPPORTED`、`BUDGET_EXHAUSTED`；未知诊断保留条件身份或幸运原因。推荐只提供可核对的输入见证，实际掉落与数值概率由显式模拟产生。

### 3.3 逐路径幸运门槛 (`LuckGateAnalysis`)

[`LuckGateAnalysis`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/analysis/LuckGateAnalysis.java) 基于编译期采集的 [`LuckSpec`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/analysis/LuckSpec.java) 计算每条获取路径的最小幸运门槛（独立保存在 `LootAcquisitionPath.luckGate`，不跨路径合并）：

1. **原版真实公式**：同时校验权重竞争 `max(floor(weight + quality * luck), 0) >= 1` 与额外抽取 `floor(bonus_rolls * luck) >= 1`。
2. **网格对齐与回验**：代数求得候选解后向上对齐至 `0.01` 步长，并带入原版两条公式回验，必要时按 `0.01` 递增直至通过。
3. **安全降级**：遇到负 `quality` 导致的上限约束、超界、步进超时或动态 `bonus_rolls` 时，不输出误导数值，改标为“区间受限”或“未知”。
4. **编译期采集细节**：原版缺省 `bonus_rolls` 等于常量 `0`；`group` 与 `sequence` 的子节点不参与权重竞争，其 `weight` / `quality` 按缺省值处理。

### 3.4 嵌套子表观测

`NestedLootTableMixin` 在模拟作用域中直接观测父表抽取时触发的直接子表调用，使用 `IdentityHashMap` 与轻量列表统计“该直接子表至少产出一个物品”的次数（不通过物品签名反推，防止父子表共用物品产生归因错位）。NeoForge GLM 触发嵌套子表时同样显式写入观测作用域。子表入口与普通物品共用 `PathHintAnalyzer` 展示派生，其条件树取自服务端计算的共同前置条件与注入门槛（`RuntimeLootLinks`）。

## 4. 主线程调度与性能观测

[`LootProbabilitySimulationWorker`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/simulation/LootProbabilitySimulationWorker.java) 在服务端 `END_SERVER_TICK` 调度全部模拟任务：

- **单线程安全性**：`getRandomItems` 与 `LootContextParamFiller` 访问非线程安全的 `LegacyRandomSource`，故严格在服务端主线程分片运行，每 tick 使用 `15ms` 软预算（每 32 次抽取检查一次截止时间），并在时间片内复用模拟作用域。
- **双队列与抢占**：`highQueue`（玩家按需请求与解锁插队）优先于 `lowQueue`（启动基准填充）；已排在低队列的同输入会被提升，新到达的高优先级任务可在下个 tick 边界无损抢占低优先级任务。
- **去重与限流**：任务按 `(tableId, inputKey)` 去重；多请求者命中已有任务时合并等待关系，同玩家保存最新票据，完成后按各自票据回执并释放一次额度。`highQueue` 待处理上限 `32`、每玩家在途按需请求上限 `2`（仅约束玩家手动按需请求，不限制启动批处理与物品解锁插队）。
- **三口径完成日志**：完成日志分别报告**线程 CPU 时间**（衡量单表计算真实成本）、**有效计算耗时**（受启动阶段并发争抢影响较大）与**跨 tick 总历时**。

### 4.1 可选性能观测 (`LootSimulationMetrics`)

默认关闭；开发期可通过 JVM 参数 `-Dusb.loot.profile=true` 或环境变量 `USB_LOOT_PROFILE=true` 开启（完成日志追加 `profile=v1` 与各分段统计）：

| 字段 | 统计口径 |
|---|---|
| `SCENARIOS` / `ROLLS` | 实际准备的场景数 / 根表 `getRandomItems` 调用总次数 |
| `DROPS` | 经过注入处理后送入匹配的非空 `ItemStack` 数量 |
| `SCANS` | `resolve` 扫描候选总数（含当前场景与原始回退扫描） |
| `EXACT_SERIALIZATIONS` / `STORED_KEY_CALLS` / `PREVIEW_BUILDS` | 组件实际序列化、存储键编码与预览缓存未命中构造次数 |
| `MATCHED` / `RAW_SKIPPED` / `DERIVED` | 三个掉落处理分支计数，正常任务三者之和等于 `DROPS` |
| `CANDIDATES_ADDED` | 各场景动态新增的候选签名总数 |

各阶段 `*_ns` 字段记录 `System.nanoTime` 累计墙钟纳秒；其中 `GENERATE` 包含原版、Mixin 与 NeoForge GLM，而 `SERIALIZE_DETAIL` / `STORED_KEY_DETAIL` / `PREVIEW_DETAIL` 为嵌套子明细（已包含在父段内，不可重复叠加）。使用 [`tools/diagnostics/loot_simulation_profile.py`](../../../tools/diagnostics/loot_simulation_profile.py) 解析 `/usb journal catalog rebuild` 标记后的完整测试轮次。

## 5. 结果缓存与失效机制

模拟结果通过 `LootProbabilityData`（挂载于主世界 `SavedData`）持久化，当前存储结构版本 `format_version = 5`（更早版本或 NBT 标签类型不匹配时整体按缓存未命中处理，不做迁移）。

| 缓存层级 | 键与内容 | 淘汰与失效策略 |
|---|---|---|
| `hash` | 整棵子树内容与依赖摘要（SHA-256） | 变化即整表作废 |
| `discovery` | 表级动态发现签名及来源标记（`hasDirectSource` / `sourceChildTables`） | **永不进入 LRU**（防止动态条目在重启后消失） |
| `inputs` | `inputKey → { 抽样数, 签名测量, 直接子表测量, 函数观测摘要 }` | 按参数组合（不含抽样数）执行上限 `8` 的 LRU 淘汰 |

### 内容哈希、重载盐与分析版本

- **子树摘要覆盖范围**：每表 SHA-256 包含子树完整资源栈原文、编译产物摘要、实例态排除组件配置、`SIMULATION_CACHE_VERSION`（以 `ArchaeologyJournalServerCatalog` 常量为准），以及被引用附魔 ID 与 `max_level`。
- **外部依赖重载盐**：对包含 `reference`、`furnace_smelt`（或解析超节点预算）的表及其祖先在每次目录生成时混入新的重载盐，使修饰器或配方变更在跨重载时触发重算，同时让普通静态表跨重启持久命中。
- **明确不纳入摘要的残留范围**：附魔的铁砧花费/权重字段、第三方 GLM 私有外部状态、以及（经确认已由条件身份与 Profile 隔离的）群系 Tag 和伤害类型定义。

### 启动、重载与失败保护

- **启动只算基准输入**：首次加载仅从缓存恢复或入队每表的 `baseline` 输入，按「哈希变化」与「缺少测量」分别统计未命中原因；其余场景按需触发。
- **数据包重载**：`DataPackReloadListener` 仅置脏标记，由 `ServerLootTableConfigManager` 在服务端 tick 路径统一失效重建（避免阻塞平台重载回调）。
- **构建期机制拦截**：`LootMechanismSupport` 在构建期拦截 `paramSet` 不兼容的表（如 `barter` 缺少 `ORIGIN`、或在非工具上下文引用 `enchantment_level`），直接标为 `Unknown(UNPARSED)` 并输出一次可行动日志；同时遵循原版缺省语义，将空 `type` 视为 `generic`。
- **确定性失败不自动重排**：执行异常的输入记录为失败并维持「未知」展示，直到下次数据包重载或执行 `/usb journal catalog rebuild` 时才重新尝试。
- **唯一展示派生路径**：从缓存恢复与刚刚模拟完成统一调用 `ArchaeologyJournalServerCatalog.deriveTable` 装配展示结果。

## 6. 约束目录与按需请求链路

[`SimulationConstraintCatalog`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/simulation/SimulationConstraintCatalog.java) 签发原子环境条件、基座工具、被引用附魔最高等级与抽样档位，按完整输入校验而不预先枚举组合。默认工具复用分类 `default_tool`，缺省为空手；工具来自整棵子树的 `match_tool`，标签取代表成员并保留谓词说明。附魔引用覆盖路径和子表入口的冻结语义，工具选项仅承载选择域，当前附魔真值由完整输入解释。

```mermaid
sequenceDiagram
    actor C as 客户端
    participant S as 服务端主线程
    participant W as Worker
    participant D as LootProbabilityData

    C->>S: RequestScenarioSimulationPayload(requestId, ScenarioSelection)
    S->>S: 核对 generation/hash、签发域及等待关系/额度
    alt 过期、越界或限流
        S-->>C: 拒绝(requestId, 原内容上下文, currentGeneration, 原因)
    else 命中缓存
        D-->>S: 读取 inputKey 测值
        S-->>C: 结果(requestId, simulation=true, 测值身份, DTO)
    else 入队或加入同输入工作
        S->>W: 保存该玩家最新票据，按 tick 分片执行
        W->>S: 完成并携带等待票据快照
        S->>D: 校验当前代次/hash后提交测值
        S-->>C: 按个人票据单播结果或过期终态
    end
```

- **共享工作与个人票据**：同代 `(tableId, inputKey)` 只运行一项工作；同玩家重试替换票据，额度按玩家与该工作的等待关系计一次。完成只消费一次提交权，释放等待额度。基准工作可同时接收玩家等待者，基准发布与个人单播各按其归属交付。
- **结果缓存与交互终态**：客户端按内容身份验证测值，再写入对应输入缓存；晚到的有效旧输入可供以后切换查看，当前选择保持原值。模拟回执仅结束同 requestId 的 pending，缓存拉取保留独立种类。拒绝先匹配原票据，再触发所需目录刷新。完整字段与线程边界见[网络与同步](../foundation/network.md#31-通用协议原则)。
- **推荐通道**：`SimulationAssistHandler` 校验同代内容和签发目标后调用范围内求解，独立冷却、超时与预览由辅助交互管理；应用推荐使用原草稿版本，显式计算另行触发。客户端动作、快照和版本草稿见[笔记 GUI 内部机制](journal-ui-internals.md#4-场景条件页与文档动作)。

## 7. 相关文档

- [战利品表系统](../subsystems/loottable.md)
- [战利品表函数与展示规则](loottable-functions-and-display.md)
- [笔记目录构建细节](journal-catalog-build.md)
- [网络与同步](../foundation/network.md)
- [本地持久化与数据迁移](../foundation/persistence.md)
