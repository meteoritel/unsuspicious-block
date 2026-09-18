# 战利品表解析追踪重构规划（四层管线）

> 状态：**进行中（步骤① 已完成，待实机验证；步骤②③ 未开工）**。架构方向、实施边界与验收方式已对齐，开工前复审的修正已并入 §3.9；步骤① 的实施结果与偏离见 §九。
>
> 当前机制的唯一权威描述是 [战利品表系统](../dev/loottable.md)；本文记录改造背景、核实依据与决策记录，机制描述在实施完成后同步回该文档。问题清单与严重度分级见 [架构审查记录](../todo/loottable-architecture-review.md)。
>
> 修订记录：初版按 [开发者文档约定](../dev/README.md)《计划文档统一结构》编写；第二轮架构审查补入模拟完成态、专用网络 DTO、边类型语义、重载 session、旧 NBT 类型兼容与验收矩阵；第三轮开工前复审修正哈希失效链、合成边守卫、空表子表过滤等 7 处（见 §3.9 与 §五 留档）。

## 一、现状

> 本节记录的是**实施前**状态；其中引用的行号以写作时（提交 `a6b8c24` + 工作区 P1 修复）的代码为准。

### 1.1 现有实现

战利品表的解析与追踪分布在 `loottable/` 包（六个子包，33 个类）与 `journal/catalog/` 的编排层：

| 关注点 | 今天的归属 | 职责 |
|---|---|---|
| 读盘与资源 | `LootTableJsonParser.load`（`listMatchingResources` + `openAsReader`）、`ArchaeologyJournalServerCatalog.computeTableHashes`（`getResourceStack`）、`ArchaeologyJournalCatalog.buildReferenceGraph`（再次 `listMatchingResources`） | 三处各自读取 loot table 资源 |
| 引用关系 | `ArchaeologyJournalCatalog`：建图 `buildReferenceGraph`、闭包 `collectReachable`、环 `findCycleTables` / `dfsCycles`、合成边 `addRuntimeInjectedReferences` | 收录闭包与循环检测 |
| 引用展开 | `LootTableJsonParser.expandLootTableReference`：自己打开子表文件、自持 `expandingStack` 环保护 | 把子表物品内联进父表 |
| 单表语义 | `LootTableJsonParser` + 可变 `ParseContext`（`parentTableConditions` / `inheritedFunctions` / `sourceChildTable` / `expandingStack` 四字段靠 save-restore 传递） | 条件继承、函数继承、tag 展开、签名派生 |
| 签名 | `signature/`：`LootResultSignature` / `LootResultMatcher` / `LootCounts` | 模拟匹配与玩家进度共用同一套键 |
| 概率模拟 | `simulation/`：`LootProbabilitySimulator` / `LootProbabilitySimulationJob` / `LootProbabilitySimulationWorker` / `SimulationScenarioPlanner` / `LootSimulationScope` 等 13 类 | 主线程 tick 分片、场景覆盖、概率文本 |
| 查询 | `LootTableCatalog.collectSubtreeItems`（作用于整张 catalog map 的静态函数）、`ItemDefinition.sourceChildTable()`、`SimulationScenarioPlanner.applicableChildTables`、`ArchaeologyJournalServerCatalog.collectCachedSubtreeSignatures` | 子树物品、子表入口、签名集合 |
| 传输 | `network/payload/s2c/SyncArchaeologyCatalogPayload` 直接序列化 `TableDefinition` / `ItemDefinition` | 客户端目录 |

### 1.2 需要改造的耦合点

- **同一份资源被反复读**：`listMatchingResources` 建图、`listMatchingResources` 解析、`getResourceStack` 算哈希各一次；解析子表时再按引用逐个打开文件。
- **`loot_table` 类型识别有三套写法**：`ArchaeologyJournalCatalog.collectDirectReferences` 与 `ArchaeologyJournalServerCatalog.isLootTableEntry` 用字面量 `"loot_table"` / `"minecraft:loot_table"`，解析器用 `LootParseUtil.normalizeType`。
- **循环检测有三套机制**：闭包阶段预排除（`findCycleTables`）、解析期 `expandingStack`、`visited` 集合去重。
- **查询没有归属**：`LootTableCatalog.collectSubtreeItems(Map<ResourceLocation, TableDefinition>, rootId)` 把"查询"实现成对全量 map 的静态函数，四个调用点（`JournalViewModel:547`、`JournalCommand:107,121,309`、`JournalCompletionRewardChecker:68`）。
- **记录类型一型多角**：`TableDefinition` / `ItemDefinition` 同时承担解析结果、模拟结果、查询容器与网络 DTO；解析期用 `probability="?"` + `simulationCount=0`、模拟期换成真实值与 `10000`，非法状态可表示。`ItemDefinition` 有 6 个便捷构造器。
- **概率是格式化字符串**：`"?"` / `"0"` / `"<0.01%"` / `"12%"` / `"3%-7%"` 五种语义共用一个 String 字段，穿过 catalog、SavedData、网络与 UI，排序时用 `ProbabilityFormat.parsePercentToFraction` 反解。
- **fishing 相关关系硬编码在 5 处 Java 位置**：`ArchaeologyJournalCatalog:33,35`、`SimulationScenarioPlanner:34,39`、`MudDredgingCondition:30`、客户端 `JournalViewModel:52,54`、`FishingHookMixin:37`；另有 `SimulationProfile:42` 用 `path.contains("fishing")` 猜测"是否钓鱼上下文"（决定默认工具与 `THIS_ENTITY` 类型），命名含 fishing 的非钓鱼表会被误判。

### 1.3 现存缺陷

| 编号 | 缺陷 | 状态 |
|---|---|---|
| P1-1 | 代表场景被 `MAX_SCENARIOS` 截断时，条目概率被写成 `"0"`（不可达）而非 `"?"`（未知） | 已修复（工作区未提交） |
| P1-2 | 解析期与运行时的条件指纹依赖 `Object.toString()` 且无守卫，破坏后静默产出错误数值 | 已修复（工作区未提交） |
| P2-1 | 引用图遍历重复实现 4 套，循环处理 3 套 | 本文路线步骤① |
| P2-2 | 概率为字符串，无法数值聚合（P1-1 的根因） | 本文路线步骤③ |
| P2-3 | 解析态与模拟态共用记录类型 | 本文路线步骤② |
| P3-1 | 网络包按物品重复序列化同一场景的假设条件树 | 随步骤② 一起做 |
| P3-2 | 运行时签名匹配缺预览栈缓存 | 延后 |
| P3-3 | 模拟失败任务不会重试，日志措辞误导 | 延后 |
| P3-4 | fishing↔mud_dredging 关系硬编码 5 处 + 上下文启发式 | 步骤① 收敛（见 D9） |
| P3-5 | 两套"可信度"口径（tooltip 颜色 vs `?`）规则不同 | 延后 |

## 二、目的

### 2.1 目标能力

把当前混在 `TableDefinition` 里的四类关注点拆成四层管线，每层一个明确职责：

| 层 | 职责 | 明确不做 |
|---|---|---|
| `LootTableSourceSnapshot` | 一次重载建立一次：全表的有效资源原文（编译用）与完整资源栈摘要（哈希用）、直接引用 | 不解析语义、不碰注册表 |
| `LootTableReferenceGraph` | 引用关系唯一权威：直接子表、可达集、子树（含自己）、SCC 环集合、收录闭包、子树摘要、带来源类型的合成边 | 不产出物品、不做业务数据聚合 |
| `CompiledLootTable` | 单表**上下文无关**局部语义：本表直接物品路径、`ReferenceSite(target, conditions, functions)` | 不缓存展开后的子表结果；不含加载期注入 |
| 投影层 | `LootTableProjector` 把图与编译结果链接成 `StaticTableProjection`（展平静态路径、`itemsByFirstHopChild`、场景适用子表）；模拟完成后另产出 `SimulatedTable`；generation 内由 `CatalogQueryIndex` 聚合静态与动态签名；网络映射为专用 `CatalogTableDto` | 不让同一类型同时表达未模拟、已模拟与网络形态；不持有编译中间态 |

### 2.2 架构目标

- 引用关系只有一份实现，`loot_table` 类型识别、环检测、闭包、子树摘要都从这里出。
- 查询有明确归属，记录类型按"编译态 / 静态投影态 / 模拟完成态 / 网络 DTO"分型，未模拟与已模拟不再靠占位字段区分。
- 概率成为可数值化的值类型（步骤③），`"0"` 与 `"?"` 的语义混用不再可能出现。
- fishing↔mud_dredging 关系与上下文判定收敛为单一声明，并由 loot table 自己声明的 `type` 驱动。
- **收益定位**：买的是可维护性，不是性能。模组自有 loot table 仅 13 张、原版表 JSON 均为 KB 级，"重复读盘"的量级大概率只是毫秒级，不要按性能项目设验收标准。

### 2.3 明确不做（本轮范围外）

- 让快照补读运行时表（使编译层看到 Fabric 注入池），见 §3.3。
- 环内断边保留（让循环表成员留在目录），见 §3.7。
- 编译产物跨重载复用与编译缓存失效机制，见 §3.4。
- 产量期望的 UI 与持久化（只留服务端内部访问器，见 D13）。
- P3-2 / P3-3 / P3-5 三项独立小改动。
- 掉落表目录扩展到怪物 / 方块掉落（路线图冻结项）。
- 性能优化类改动。

## 三、技术原理

本节记录已核实的机制事实，它们是路线选择的依据。

### 3.1 为什么按「快照 → 图 → 编译 → 投影」分层（设计推导）

四个关注点的**变更频率与失效条件完全不同**：快照只在数据包重载时变；图只在有效资源文本的引用结构变时变；编译产物还受 tag 成员与注册表影响；静态投影随本轮重载一次性建立，模拟完成态再随缓存恢复与模拟进度增长。当前把它们压在同一个 record 上，导致任何一处变化都要重算全部，也让"某一层算错了"难以定位。分层后每层的失效条件可以单独陈述与验证。

### 3.2 为什么不能缓存「展开后的子表结果」（源码核实）

`LootTableJsonParser.expandLootTableReference` 用 `ParseContext` 的四个可变字段做 save-restore：`sourceChildTable` 只在第一层设置（首跳归属）、`parentTableConditions` 累积 pool / 组合 entry / 引用位置的条件、`inheritedFunctions` 累积引用位置的函数、`expandingStack` 防环。也就是说**同一个子表在不同引用位置下产出的物品路径不同**（条件与函数被引用位置改写）。因此编译产物必须是"本表局部语义 + 引用位置描述"，由投影在链接期组合；若缓存展开结果，第二个引用位置就会拿到错误的条件与函数。

### 3.3 为什么编译层保持静态快照视图（补丁核实）

`neoforge-21.1.195-userdev.jar` 中的 `patches/net/minecraft/world/level/storage/loot/LootTable.java.patch` 显示 NeoForge 对 loot table 的改造包括：codec 改为 `CommonHooks.lootPoolsCodec(LootPool::setName)` 与 `ConditionalOps.decodeListWithElementConditions(...)`（pool 命名 + 元素级条件），并新增 `freeze()` / `isFrozen()`，冻结后 `addPool` / `removePool` 抛异常。这意味着运行时表与 JSON 快照之间存在结构性差异，且把运行时表 codec 编码回 JSON 会带上有损或非原版的语义。Fabric 侧的加载期 `LootTableEvents.MODIFY` 同理不可见于 JSON。因此编译层明确定为**静态快照视图**，注入内容继续由模拟期动态发现与图中的合成边表达。

图中的边必须区分语义来源：`JSON_REFERENCE` 来自有效 JSON 中真实存在的 `loot_table` entry，参与静态语义链接；`RUNTIME_INJECTION` 只表达运行时注入关系，参与收录闭包、目录层级、子树哈希与子表模拟观测，**不生成 `ReferenceSite`、不把子表条目提前编译进父表，也不改变 `injected` 判定**。否则 `fishing → mud_dredging` 会从运行时注入变成静态引用，破坏当前行为。

图的邻接表可以按目标表去重，但 `CompiledLootTable` 中的 `ReferenceSite` 必须保留每个引用出现位置与稳定顺序：同一个子表在两个位置出现时，conditions / functions 可能不同，不能因图上只有一条拓扑边而合并语义路径。

### 3.4 为什么哈希必须保留编译产物摘要（源码核实）

`ArchaeologyJournalServerCatalog.computeTableHashes` 的每表哈希输入有三类：`SIMULATION_CACHE_VERSION`、表与引用闭包的全部资源文本（`updateTableResourceDigest` + `collectReferencedTables`）、以及**解析产物摘要** `updateRawDefinitionDigest`（物品签名的 storedKey、物品 id、`injected` 标记；静态解析阶段该标记通常为 false）。第三类真正需要保留的是 **tag 展开结果与签名集合**：数据包往某个 item tag 里加物品时，JSON 文本不变，只有展开结果变。若新方案的哈希"只吃资源与闭包摘要"，这条失效链会断掉，概率将静默过期；目标态的编译产物摘要因此包含 tag 展开后的 item id 与签名，不要求把模拟期 `injected` 结果反写进编译层。

目标态明确采用：引用图只从**有效 JSON**提取边；每个可达节点的哈希仍使用其**完整 resource stack 摘要**。因此低优先级 inactive 层自身文本变化会使该节点及祖先失效，但只存在于 inactive 层中的引用不会扩展有效闭包。它比现状“从每一层文本继续追引用”更贴近实际运行表，属于有意收窄的过度失效范围，需在步骤①记录一次哈希基线变化。

同时注意：合成边也参与哈希（`computeTableHashes` 遍历 `childTables`，而 `childTables` 来自含合成边的图），因此修改 `mud_dredging` 会正确让 `fishing` 重算。该行为必须保留。

至于"编译缓存失效"：编译只在数据包重载时发生一次、追踪集有限，因此编译产物**不跨重载复用**，每轮重载重建，不存在编译缓存失效问题；哈希仍吃编译产物摘要，用于驱动**存档缓存**失效。

### 3.5 为什么种子重载可用于等价性验证（补丁核实）

同一 patch 显示 NeoForge 把 Global Loot Modifier 挂在**私有** `getRandomItems(LootContext)` 上：

```java
private ObjectArrayList<ItemStack> getRandomItems(LootContext ctx) {
    ObjectArrayList<ItemStack> list = new ObjectArrayList<>();
    this.getRandomItemsRaw(ctx, createStackSplitter(ctx.getLevel(), list::add));
    list = CommonHooks.modifyLoot(this.getLootTableId(), list, ctx);
    return list;
}
```

patch 把三个返回 `void` 的重载改为 `this.getRandomItems(...).forEach(...)`，而三个返回 `ObjectArrayList` 的重载（含 `getRandomItems(LootParams, RandomSource)` 与 `getRandomItems(LootParams, long)`）在原版就已委托给它；`getRandomItemsRaw` 被标注 `@Deprecated // Use a non-'Raw' version ... so that the Forge Global Loot Modifiers will be applied`。

结论：**换用种子重载不会绕过 GLM，也不需要平台分支**，等价性验证可用它实现。注意两点：① `RandomSource` 必须每场景创建一次并复用，否则 10000 次抽取拿到同一段随机序列，概率退化为 0%/100%；② `LootProbabilitySimulationJob` 里给注入器用的 `injectionRandom` 是独立随机源，必须同步带种子，否则 Fabric 注入路径仍不可复现。

另外，模拟的随机源默认取自 `ServerLevel.getRandom()`（`LootContext.Builder.create` 的三级回退），是有状态的全服共享随机源，因此**模拟本身是非确定性的**——重构前后直接对比概率文本不是可靠判据，这正是需要种子开关的原因。

### 3.6 为什么钓鱼上下文改用声明的 `type`（数据包核实）

原版 `data/minecraft/loot_table/gameplay/fishing.json` 自带 `"type": "minecraft:fishing"`（其余为 pools / entries / `random_sequence`）。而解析器早已把该字段读进 `TableDefinition.type`。因此上下文判定应改为读声明类型，取代 `SimulationProfile:42` 的 `tableId.getPath().contains("fishing")`：任何声明了 `minecraft:fishing` 的模组表都能被正确识别，命名里带 fishing 的非钓鱼表不再被误判。

### 3.7 为什么循环引用保持「排除」语义（现状语义 + 风险判断）

今天的行为是"在追踪根表初始可达集内检测到环 → 环上所有表排除出收录闭包并告警"。改为"环内断边保留"会改变目录可见内容与哈希、触发一次全量重模拟，属产品决策而非重构细节。本规划允许图全局计算 SCC，但排除与日志策略只作用于初始可达集内的 SCC；与考古目录无关的实体、方块或第三方表循环不得新增告警。图暴露带 scope 的循环分量访问器（对外仍只输出日志，见 D7），并以此产出排除集——**注意 SCC 恒为今天回边切片集合的超集，两者只在存在环时不同，理由与量化见 §3.9 之八**。

统一的是**循环发现与排除策略**，不是删除所有局部保护：解析链接器仍保留 stack 作为断言式防御，普通图查询仍用 visited 处理共享 DAG 节点；二者不得自行决定排除集或重复输出循环日志。

### 3.8 必须遵守的边界（不变量）

| # | 不变量 | 位置 | 破坏后果 |
|---|---|---|---|
| 1 | 首跳子表归属：`sourceChildTable` 只在第一层设置，孙表条件汇总到直接子表 | `LootTableJsonParser.expandLootTableReference` 的 `previousSource == null` 分支 | 父表 UI 子表概率归属错位 |
| 2 | 条件继承顺序：`allConditions()` = inherited + entry | `LootTableCatalog.LootAcquisitionPath` | UI 假设列表顺序变化 → 目录哈希变化、客户端重同步 |
| 3 | 函数继承与降级：引用位置的 functions 作用到子表物品；无法静态求值 → `entryHasConditions` + `APPROX_ITEM_ONLY` | `LootTableJsonParser.resolveEntry` | 签名类型变化 → 玩家进度键错位 |
| 4 | 签名兼容：同一 JSON 必须算出逐位相同的 `toStoredKey()` | `LootResultSignature` + `LootFunctionHandlers` | 玩家进度 / 日志 / 缓存键错位，只能靠签名重映射补救 |
| 5 | tag 展开结果参与哈希 | `computeTableHashes` → `updateRawDefinitionDigest` | 数据包加物品后概率静默过期 |
| 6 | 合成边参与哈希 | `computeTableHashes` 遍历 `childTables` | 改 `mud_dredging` 不会让 `fishing` 重算 |
| 7 | 条件指纹的稳定性判定与两层守卫不被绕开 | `LootConditionFingerprint` / `SimulationScenarioPlanner` / `LootSimulationScope` | 场景覆盖静默失效（P1-2 形态复现） |
| 8 | 模拟走非 Raw 的 `getRandomItems`（GLM 在其中应用）；嵌套表仍走 Raw，GLM 只在根表应用一次 | `LootProbabilitySimulator` | NeoForge 端注入物在模拟中消失 |
| 9 | `RUNTIME_INJECTION` 只参与结构关系，不参与静态语义链接；动态物品仍由模拟发现并保留 `injected=true` | fishing→mud_dredging 两端注入路径 | 注入物被误当静态条目，条件、来源与缓存语义变化 |
| 10 | 图邻接可去重，`ReferenceSite` 不去重且保持出现顺序 | 同一子表在不同引用位置的 conditions / functions | 两条不同获取路径被错误合并 |
| 11 | 同一轮快照、图、编译结果与哈希带同一个 generation，完整构建后原子发布 | `ArchaeologyJournalServerCatalog.ensureLoaded` / worker 提交 | 读到半新半旧目录，旧模拟结果写入新目录 |
| 12 | 父表 `childTables` 只收录「有物品」的子表；无物品的表不进目录也不作为子表入口 | `ArchaeologyJournalCatalog.load` 的 `filter(parsed::containsKey)`（而 `parsed` 只收 `items()` 非空的表） | UI 多出空的子表入口，父表页签出现不可达层级 |
| 13 | 每表哈希的**编译产物摘要**覆盖 `descendantsInclusive(id)` 内每张表，而非只覆盖本表 | `computeTableHashes`（见 §3.9 之一） | 子表 tag 成员变化时父表缓存不失效，概率静默过期 |
| 14 | 合成边只在两端资源都存在时注入 | `ArchaeologyJournalCatalog.addRuntimeInjectedReferences` 的 `containsKey` 双端守卫 | 图出现快照中不存在的节点，编译层取不到 JSON |

### 3.9 开工前复审的修正（第二轮计划文本的缺陷）

本轮复审对照 `a6b8c24` 之后的实际代码逐条核对 §3 / §4，发现七处需要在动工前改正。前三条是会导致行为错误的实质缺陷，后四条是文本与清单的不完整。

**之一（实质）：每表哈希的编译产物摘要必须覆盖整个子树，而不是只覆盖本表。**

这是本文档最重要的修正。现状 `computeTableHashes` 的第三类输入 `updateRawDefinitionDigest(digest, entry.getValue())` 吃的是 `entry.getValue().items()`，而 `LootTableJsonParser.parseTable` 把**整棵子树的物品内联进了父表的 items**（§3.2 已论证）。因此现状下父表 P 的哈希包含子表 T 的物品签名。

实际后果：数据包往 T 引用的某个 item tag 里加物品时，T 的 JSON 文本不变。现状 P 的哈希会因 `signature` 变化而失效；而 §4.3 描述的"编译产物摘要 + 资源摘要 + 闭包摘要"如果按 §4.2 的 `CompiledLootTable`（**本表**上下文无关局部语义）取本表摘要，P 的三项输入全都不变，**P 的缓存不会失效，概率静默过期**。这条失效链正是 §3.4 明确要求保住的不变量 #5。

修正后的规则：每表哈希的第二、三类输入都按 `descendantsInclusive(id)`（含合成边）取全集——资源摘要取子树内每张表的完整 resource stack 摘要，编译产物摘要取子树内每张表的编译产物摘要（tag 展开后的 item id 与签名集合）。这与现状"每表哈希吃整子树物品签名"语义等价，也是 §4.2 `subtreeDigest(id)` 应当采用的形态：**它必须同时包含其后代的编译产物摘要，而不只是资源摘要**。

**之二（实质）：合成边的双端存在守卫必须保留。**

`addRuntimeInjectedReferences` 只在 `graph.containsKey(FISHING) && graph.containsKey(MUD_DREDGING)` 时才加边。新方案的 `RuntimeLootLinks.SYNTHETIC_EDGES` 若无条件注入，图里会出现快照中并不存在的节点；编译层对这类节点取 `effectiveJson(id)` 会得到 null，必须显式跳过而不是抛异常或产出空表。两处都要做：注入侧保留守卫，编译/投影侧对"图有节点但快照无资源"保持跳过 + 告警。

**之三（实质）：`childTables` 必须过滤掉"没有物品的表"。**

`ArchaeologyJournalCatalog.load` 里 `parsed` 只收 `items()` 非空的表，`withChildTables` 又用 `filter(parsed::containsKey)` 过滤，所以空表既不入目录、也不作为子表入口。§4.2 的图 `directChildren(id)` 是纯拓扑关系，若 `StaticTableProjection` 直接使用，UI 会多出空子表入口。投影期必须复现这条过滤，已作为不变量 #12 补入 §3.8。

**之四：`RuntimeLootLinks` 需要覆盖的标识符是三个，不是两个。**

除两张战利品表外，`SimulationScenarioPlanner:37,52` 还硬编码了**条件类型** `unsuspiciousblock:mud_dredging`（用于场景假设文案与指纹类型默认值）。它性质不同（条件类型 id 而非 loot table id），但同属"平台注入联动的标识符"，应在 `RuntimeLootLinks` 中一并声明，避免遗漏第四处硬编码。

**之五：`contextKind` 的入参需要归一化。**

§4.2 写 `contextKind(ResourceLocation declaredType)`，但 `TableDefinition.type` 是 `String` 且由 JSON 原样读出（可能是 `fishing`，也可能缺失为空串）。实现须按 `LootParseUtil.normalizeType` 的语义归一化后比较，并对缺失 `type` 的表退回非钓鱼上下文。

**之六：`computeCatalogHash` 未列入改动清单。**

它遍历 `catalog` map 的 `TableDefinition`（含 items / acquisitionPaths / 场景概率）。步骤② 改用 `SimulatedTable` 与 `CatalogTableDto` 后，这个哈希的输入源必须同步切换，否则客户端按需同步的比对会与 payload 内容脱节。§4.4 只在 `SyncArchaeologyCatalogPayload` 一行提到 payload。

**之七：`invalidate()` 需要清理快照与 session。**

`invalidate()` 目前清 `catalog` / `rawCatalog` / `tableHashes` / `catalogStructure` / `cachedCatalogHash`。§4.2 引入 `LootTableAnalysisSession` 与 `CatalogGeneration` 后，快照会常驻全表原文，`invalidate()` 必须一并释放，否则数据包重载期间旧快照留存。§4.4 未列这一处。

**之八（实质）：SCC 与今天的环排除集并不相等，SCC 恒为超集。**

§3.7 断言"图以此计算与今天相同的排除集"。实施时用随机图对照验证发现该断言不成立：

- 今天 `dfsCycles` 收的是 **DFS 回边对应的栈切片并集**；回边切片上的节点都在某个有向环上，因此旧集合恒 ⊆ SCC 集合，但反过来不成立——**旧实现会漏报环上成员**。
- 反例（`0→1, 1→0/3/4/5, 2→5, 3→1, 4→2/3/5, 5→3`）：从 0 出发 DFS 时 3 在从 1 展开后即完成，之后 5→3 看到的是 `VISITED` 而非 `VISITING`，于是 `{2,4,5}` 虽在环上却从未进入任何回边切片；旧集合 `{0,1,3}`，SCC 集合 `{0,1,2,3,4,5}`。
- 量化（各 20 万次随机图，边密度按真实引用图特征设定）：树状图 0/20 万不一致；加 1% 回边 3/20 万；加 5% 回边 24/20 万；两出边图 3176/20 万。**80 万次试验中旧集合恒为 SCC 集合的子集，零反例。**
- 影响面：模组与 原版 战利品表**当前都没有环**，排除集在两种实现下都是空集，因此本批次对现有数据零可见影响；差异只在数据包构造循环表时出现，届时 SCC 会多排除一部分环上表（即更接近 §3.7 描述的意图"环上所有表排除出收录闭包"，而旧实现会让部分环上表漏进目录、靠解析期 `expandingStack` 兜住）。

处置：保留 SCC（D7 明确要求"图全局计算 SCC"，且它忠实于今天行为被描述的意图），并把本条记为步骤① 唯一超出"行为保持"的语义变化，列入 §六 风险与 §九 偏离，供用户决定是否需要改回逐字复刻旧集合。

## 四、技术路线

### 4.1 分层设计与目标结构

```
ResourceManager
   └─(每层资源只读一次)→ LootTableSourceSnapshot
          ├─→ LootTableReferenceGraph ─→ SCC / 收录闭包 / 子树摘要 / 分型边
          └─→ CompiledLootTable ──────→ LootTableProjector → StaticTableProjection
                                            ↑                       │
                        LootTableReferenceGraph ────────────────────┘

StaticTableProjection ─→ 概率模拟 / 缓存恢复 ─→ SimulatedTable ─→ CatalogTableDto

同一 generation 的 snapshot / graph / compiled / hashes
   └─→ LootTableAnalysisSession ─→ 完整构建后由 CatalogGeneration 原子发布
```

依赖方向单向：快照无业务依赖；图只依赖快照；编译只依赖快照与 analysis 内的语义值；投影器同时依赖图与编译；模拟只依赖静态投影；网络只依赖专用 DTO。`catalog` 不反向依赖 `analysis` 的编译器实现，避免新增包级循环依赖。

### 4.2 关键类型（API 草案）

| 类型 | 关键 API |
|---|---|
| `loottable/source/LootTableSourceSnapshot` | `capture(ResourceManager)` / `tableIds()` / `effectiveJson(ResourceLocation)`（`listMatchingResources` 语义，最高优先级那份）/ `resourceStackDigest(ResourceLocation)`（`getResourceStack` 语义，全部数据包层）/ `directReferences(ResourceLocation)`；`JsonElement` 不常驻，编译期按需重解析。实现时从同一次 resource stack 捕获中取得有效原文与摘要，避免最高层被重复打开 |
| `loottable/graph/LootTableReferenceGraph` | `build(snapshot, syntheticEdges)` / `directEdges(id)` / `directChildren(id)` / `reachableFrom(roots)` / `descendantsInclusive(id)`（稳定顺序）/ `cyclicNodesIn(scope)` / `subtreeDigest(id)`（含合成边、visited 去重；**并入后代每张表的 resource stack 摘要与编译产物摘要**，见 §3.9 之一） |
| `loottable/graph/LootTableEdge` | `target` + `kind`；`JSON_REFERENCE` 参与语义链接，`RUNTIME_INJECTION` 只参与结构关系。边类型显式声明是否参与 closure / semanticLink / directory / hash |
| `loottable/graph/RuntimeLootLinks` | 注入关系与上下文判定的单一权威：两个 loot table 标识符 + 一个条件类型标识符、`SYNTHETIC_EDGES`（保留双端存在守卫）、`contextKind(String declaredType)`（内部按 `LootParseUtil.normalizeType` 语义归一化） |
| `loottable/analysis/CompiledLootTable` | 本表直接物品路径 + 按出现位置保序的 `ReferenceSite(target, inheritedConditions, entryConditions, functions)`；引用位置的继承语义显式化，不再靠可变上下文 save-restore |
| `loottable/catalog/LootTableProjector` | 消费图与编译结果，为根表构建 `StaticTableProjection`；只沿 `JSON_REFERENCE` 做语义链接，结构型合成边只进入 child / closure 视图 |
| `loottable/catalog/StaticTableProjection` | 静态展平物品路径 / `itemsByFirstHopChild` / 静态子树签名集合 / 场景适用子表；是模拟与无需概率的命令、成就和迁移查询输入，不含概率占位符或模拟期动态签名 |
| `loottable/catalog/SimulatedTable` | `StaticTableProjection` 对应的模拟结果：数值概率、场景结果、模拟次数与动态发现条目；构造时要求模拟状态完整 |
| `loottable/catalog/CatalogQueryIndex` | generation 级查询索引：消费 graph、static projections 与当前 simulated overlay，提供 `subtreeItems(root)`、`cachedSubtreeSignatures(child)`、`itemsByFirstHopChild(root)`；缓存恢复与模拟提交时按本代更新，替代散落的递归聚合 |
| `loottable/catalog/CatalogTableDto` | 只包含客户端所需字段；每表场景 assumptions 存一次，物品和子表按 `scenarioKey` 引用。网络 codec 不直接序列化内部投影或图索引 |
| `loottable/catalog/Probability` | sealed 值类型：`Unknown` / `Unreachable` / `Measured(lower, OptionalDouble upper)`；`Measured` 校验有限数值、`0≤lower≤upper≤1`。NBT 与网络各一个穷尽 codec，格式化只在 UI 边界 |
| `journal/catalog/LootTableAnalysisSession` | 不可变的 `generation` + snapshot + graph + compiled + 全部追踪表的 static projections + table hashes；同一轮全部构建完成后使用 |
| `journal/catalog/CatalogGeneration` | `LootTableAnalysisSession` + `CatalogStructure` + 本代 `SimulatedTable` overlay / `CatalogQueryIndex` / catalog hash；缓存恢复完成后由单个 volatile 引用原子发布，后续只接受 generation 相同的 worker 提交 |

`RuntimeLootLinks` 的两个要点：`MudDredgingCondition.MUD_DREDGING`（`ResourceKey<LootTable>`，被 Fabric 注入与 NeoForge GLM 引用）改为由 `RuntimeLootLinks` 派生，使标识符只有一处来源且不动平台代码；`contextKind` 按 §3.6 读声明类型。

边类型规则固定如下，后续新增运行时联动时必须显式选型：

| 边类型 | closure | semanticLink | directory | hash |
|---|---:|---:|---:|---:|
| `JSON_REFERENCE` | 是 | 是 | 是 | 是 |
| `RUNTIME_INJECTION` | 是 | 否 | 是 | 是 |

### 4.3 数据流

1. **重载**：`capture` 建快照 → `build` 建分型图 → 在追踪根的初始可达集内算 SCC 排除集 → 对有效追踪集做编译 → 为全部追踪表构建静态投影 → 由编译产物摘要 + 资源摘要 + 闭包摘要算每表哈希 → 组成带 generation 的 `LootTableAnalysisSession` → 恢复本代合法缓存 → 组成 `CatalogGeneration` 并通过单个 volatile 引用原子发布。所有步骤先在局部对象完成；构建失败继续保留上一代完整状态或进入明确的空状态，不发布半成品。
2. **缓存恢复与模拟**：SavedData 根节点新增 `format_version`；先检查版本和严格 NBT 类型，再读取当前格式缓存。旧文件没有版本、仍为 `StringTag probability`，或单个 entry 不完整时，均把对应表视为缓存未命中。命中者由 `StaticTableProjection + 缓存结果` 构造 `SimulatedTable`；恢复动态条目来源时，由 generation 级 `CatalogQueryIndex` 聚合图中后代的缓存签名，不把动态签名塞进静态投影。未命中者从静态投影取路径与场景适用子表，主线程分片抽取（含 GLM / Fabric 注入器）后构造 `SimulatedTable`。worker 工作项携带 generation，提交时与当前 session 不同则丢弃。
3. **查询与同步**：命令、成就判定、迁移等不需要概率的路径消费 `StaticTableProjection`；需要聚合静态与动态结果的目录、缓存来源恢复消费 `CatalogQueryIndex`；UI 与网络消费 `SimulatedTable` 映射出的 `CatalogTableDto`。客户端只接收 DTO，不接收服务器内部图、编译 IR 或子树签名索引。

### 4.4 文件改动清单

**新增**：`loottable/source/LootTableSourceSnapshot`、`loottable/graph/LootTableReferenceGraph`、`LootTableEdge`、`RuntimeLootLinks`（步骤①）；`loottable/analysis/CompiledLootTable` 与 `loottable/catalog/LootTableProjector`、`StaticTableProjection`、`SimulatedTable`、`CatalogQueryIndex`、`CatalogTableDto`、`journal/catalog/LootTableAnalysisSession`、`CatalogGeneration`（步骤②）；sealed `Probability`（步骤③）。

**改造**：

| 文件 | 改动 |
|---|---|
| `journal/catalog/ArchaeologyJournalCatalog.java` | 闭包、环、合成边、子树遍历改为消费图；删除 `buildReferenceGraph` / `collectDirectReferences` / `collectReachable` / `findCycleTables` / `dfsCycles` / `addRuntimeInjectedReferences` |
| `loottable/analysis/LootTableJsonParser.java` | 从快照取 JSON（含子表引用），不再持有 `ResourceManager`；环保护与图的排除集对齐 |
| `journal/catalog/ArchaeologyJournalServerCatalog.java` | `computeTableHashes` 改吃摘要并保留编译产物摘要（摘要按 `descendantsInclusive` 覆盖子树，见 §3.9 之一）；构建带 generation 的 session / catalog generation 并原子发布；worker 提交校验 generation；`computeCatalogHash` 输入源随步骤② 的投影/DTO 同步切换（见 §3.9 之六）；`invalidate()` 一并释放快照与 session（见 §3.9 之七）；删除 `updateTableResourceDigest` / `collectReferencedTables` / `isLootTableEntry` |
| `loottable/simulation/SimulationProfile.java` | 上下文类型改由 `RuntimeLootLinks.contextKind(...)` 决定 |
| `loottable/simulation/SimulationScenarioPlanner.java`、`mixin/interaction/FishingHookMixin.java:37` | 硬编码标识符改引 `RuntimeLootLinks` |
| `loottable/catalog/LootTableCatalog.java` | 步骤② 清理便捷构造器与 `collectSubtreeItems`；解析态、模拟态记录由新类型替代 |
| `world/LootProbabilityData.java` | 步骤③ 新增根 `format_version` 并改用 `Probability` codec；读取时先检查版本与 NBT tag 类型，旧 String 字段或不完整 entry 按单表缓存未命中处理，不在反序列化阶段报错或伪造 0 值 |
| `network/payload/s2c/SyncArchaeologyCatalogPayload.java` | 步骤② 改为序列化 `CatalogTableDto` 并做 P3-1，不直接暴露 `StaticTableProjection` / `SimulatedTable` 内部索引 |
| 客户端 `JournalViewModel` / `ItemGridPanel` / `JournalTooltipBuilder` | 步骤②③ 跟随投影 DTO 与值类型调整 |

**查询调用点切换**（步骤②）：`JournalViewModel:547`、`JournalCommand:107,121,309`、`JournalCompletionRewardChecker:68`、`ArchaeologyLootRuntimeTracker:97,286`、`ArchaeologyChallengeChecker:113`、`JournalDataMigrationManager:83`。`collectSubtreeItems` 的四个调用点即前三项（`JournalCommand:284,316` 是 `getRawCatalog` 的取值处，不是子树查询，见 §3.9 之六的行号订正）。

**不做**：客户端 `JournalViewModel:52,54` 的两处硬编码标识符在步骤① 不动（缩小批次风险），步骤② 随 DTO 携带合成边后消除。

### 4.5 分阶段实施

**步骤① 快照 + 引用图**，拆四个可独立编译的子批次；前三批允许新旧实现双存，第四批才完成最终切换：

1. 新增快照、分型边、图与 `RuntimeLootLinks`（只增不删，旧路径照常运行）；验证 `JSON_REFERENCE` 与 `RUNTIME_INJECTION` 的参与规则。
2. `LootTableJsonParser` 切到快照（行为保持）。
3. `computeTableHashes` 切到摘要（触发一次全量重模拟，已由 D2 接受）。
4. 闭包、SCC 与子树查询切到图；只对追踪根初始可达集应用循环排除与日志；删除被替代的重复实现，硬编码收敛到 `RuntimeLootLinks`。

**步骤② 编译 / 投影 / 状态分型**：先建 `CompiledLootTable`、`StaticTableProjection`、`SimulatedTable`、`CatalogQueryIndex` 与 `LootTableAnalysisSession` 并双跑对比；确认静态投影不包含运行时合成边的静态条目、查询索引能同时覆盖静态与缓存/模拟动态签名后，再按 §4.4 切换消费者。最后单独引入 `CatalogTableDto` 做 P3-1 payload 瘦身，内部投影不兼任网络 DTO。

**步骤③ 概率值类型**：引入 sealed `Probability`，删除反解排序，范围改为模拟完成态由场景集合推导，按 D13 留服务端产量期望访问器，bump 缓存版本。SavedData decoder 先按 tag 类型识别新格式；旧 `StringTag` 不是“字段缺失”，必须显式按缓存未命中处理，且摘要概率与场景概率使用同一兼容规则。

### 4.6 文档同步

重构完成后重写 [战利品表系统](../dev/loottable.md) 的 §2（子包结构）、§5（收录范围）、§6（解析流程）、§7.1 / §7.4（概率口径与缓存）、§10（扩展点）；不新增平行文档。本文件在步骤完成后补 §九 并移入 `docs/archive/`。

## 五、决策记录

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 目标态与落地方式 | 四层模型；分三步增量落地，每步可独立发布与回退 |
| D2 | 概率缓存演进 | 允许 bump `SIMULATION_CACHE_VERSION` 触发一次性全量重模拟；SavedData 根新增 `format_version`，加载发生在哈希比较前，因此 decoder 必须先检查版本与 tag 类型。旧文件无版本、旧 `StringTag probability`、未知版本或不完整 entry 按**单表缓存未命中**跳过，不迁移数值、不把类型不匹配解成 0 |
| D3 | 等价性验证 | 固定种子的可复现模拟；临时实现，验收后删除 |
| D4 | 每表哈希输入 | 有效 JSON 图确定闭包；闭包内每个节点吃完整 resource stack 摘要；再加编译产物摘要（tag 展开结果、item id、签名集合）与合成边摘要。inactive 层独有引用不扩展闭包，这是有意收窄过度失效范围 |
| D5 | 快照形态 | 全表常驻原文 + 摘要，`JsonElement` 按需重解析 |
| D6 | 步骤① 切换面 | 按 parser → 哈希 → 闭包/SCC 三个可编译子批次迁移，最终删除旧路径时才视为整体切换完成；不长期保留双权威 |
| D7 | 循环引用语义 | SCC 可全局计算，但只对追踪根初始可达集中的循环应用"整体排除出闭包"与一次性日志；不相关表的 SCC 不新增告警 |
| D8 | 注入边界 | 编译层确认为静态快照视图；图使用分型边。`RUNTIME_INJECTION` 参与 closure / directory / hash，不参与 semanticLink，不改变静态条目与 `injected` 语义 |
| D9 | 硬编码收敛 | fishing↔mud_dredging 与"钓鱼上下文"判定一起收进 `RuntimeLootLinks`，上下文改读声明的 `type` |
| D10 | 投影与模拟态生命周期 | 全部追踪表的 `StaticTableProjection` 在 session 发布前一次性构建并保持不可变；`SimulatedTable` 是独立完整类型，随缓存恢复或模拟提交产生；`CatalogQueryIndex` 在 generation 内聚合两者，不用占位概率伪装未模拟态，也不把动态签名污染静态投影 |
| D11 | 网络形态 | 使用专用 `CatalogTableDto`，不让内部投影兼任 DTO；顺带做 P3-1（场景 assumptions 每表一次、物品按 `scenarioKey` 引用） |
| D12 | 编译缓存 | 编译产物不跨重载复用；tag 展开留在编译期 |
| D13 | 概率值形态 | sealed `Unknown` / `Unreachable` / `Measured(lower, optionalUpper)`；构造与 codec 强制 `0≤lower≤upper≤1` 且拒绝 NaN/Infinity。不统计产量期望，仅服务端内部留访问器 |
| D14 | 重载一致性 | snapshot / graph / compiled / projection / hashes 归属同一 `LootTableAnalysisSession.generation`；局部构建完成后原子发布，worker 提交时校验 generation |
| D15 | 包归属 | source I/O 放 `loottable/source`，拓扑放 `loottable/graph`，编译 IR 留在 `loottable/analysis`，投影与对外读模型放 `loottable/catalog`，避免 `catalog ↔ analysis` 新循环 |

由决策推导的机制性结论：编译每重载重建，故哈希吃编译产物摘要只服务于存档缓存失效；tag 展开必须在编译期（签名按具体物品生成，存档缓存也按具体签名索引）；种子随机源每场景创建一次并复用，注入器随机源同步带种子；缓存版本 bump 只能决定是否复用已成功反序列化的数据，不能替代旧 NBT tag 类型守卫；结构型合成边不能被投影器当作 JSON `ReferenceSite` 展开。

### 讨论期间修正的自身错误（留档）

1. **P2-1 的收益被高估为性能收益。** 最初的架构审查把"同一子树启动期读盘 2~3 次"写成主要问题之一；实测模组自有 loot table 仅 13 张、原版表 JSON 均为 KB 级，重复读盘大概是毫秒级。已改为把 P2-1 的收益定位为"引用关系只保留一份实现"，并在 §2.2 明确不按性能项目验收。
2. **硬编码位置数错。** 审查记录原写 fishing↔mud_dredging 硬编码"三处"，实测是 **5 处 Java 位置**（多出客户端 `JournalViewModel:52,54` 与 `FishingHookMixin:37`）。已修正审查记录与本文 §1.2。
3. **计划文档的归属搞错。** 初版把本文放在 `docs/todo/`，而 `docs/dev/README.md` 早已约定 `docs/plan/` 用于"进行中的子系统改造规划"。已移入 `docs/plan/` 并把结构规范补进开发者文档约定。
4. **投影层边界初版仍然一型多角。** 初版让 `LootTableProjection` 同时充当模拟输入、模拟结果与网络 DTO，并把 `Probability` 设计成可组合出非法状态的 `state + double`。第二轮审查后拆为 `StaticTableProjection` / `SimulatedTable` / `CatalogTableDto`，概率改为 sealed 值类型，同时补上旧 NBT 类型守卫与 session generation。
5. **哈希失效链在第二轮的表述里是断的。** 第二轮把每表哈希写成"编译产物摘要 + 资源摘要 + 闭包摘要"，但 `CompiledLootTable` 被定义为**本表**上下文无关语义，于是"子表 tag 成员变化"这条失效链会断——而 §3.4 恰恰把这条链列为必须保留的不变量 #5。第三轮复审时改正为"编译产物摘要按 `descendantsInclusive` 覆盖子树"（§3.9 之一）。
6. **`RuntimeLootLinks` 漏了一个标识符。** 第二轮写"两个标识符"，实际除两张表外还有条件类型 `unsuspiciousblock:mud_dredging`（`SimulationScenarioPlanner:37,52`）。**审查记录里的"5 处"本身是对的**，是计划侧收敛范围写漏了一处。
7. **`computeCatalogHash` 与 `invalidate()` 漏在改动清单外。** 两处都会因步骤② 引入 session/投影而必须同步改动，第二轮只列了 payload。

## 六、风险与限制

| 风险 | 说明 | 处置 |
|---|---|---|
| 步骤② 语义复现不全 | 要同时复现首跳归属、条件继承顺序、函数继承与 `APPROX_ITEM_ONLY` 降级三重语义 | 拆成"先建类型、双跑对比、再切消费者"；发现不一致保留旧路径 |
| 合成边被当作静态引用展开 | `fishing→mud_dredging` 会把运行时注入物提前变成静态条目，改变 `injected`、条件与来源 | `LootTableEdge` 强制分型；投影器只对 `JSON_REFERENCE` semanticLink，验收 dump 单独核对合成边两侧条目 |
| 环排除集由回边切片改为 SCC | SCC 恒为旧集合的超集（§3.9 之八，80 万次随机图零反例），存在环时会多排除环上表 | 现有数据无环 → 零可见影响；已记录为步骤① 唯一超出"行为保持"的语义变化，若需逐字复刻旧集合须改回回边切片 |
| 子树编译产物摘要被漏进哈希 | 若每表哈希只吃本表编译产物，子表 tag 成员变化不会让父表缓存失效，概率静默过期（§3.9 之一） | 摘要按 `descendantsInclusive` 覆盖子树；验收矩阵的「item tag 成员变化、loot table JSON 不变」场景必须同时验证**父表**重模拟 |
| 空表子表入口泄漏到 UI | 图是纯拓扑，不区分"表是否有物品" | 投影期复现 `parsed::containsKey` 过滤（不变量 #12） |
| 签名不兼容会动存档 | 签名是玩家进度 / 日志 / 缓存的键 | 每步验收都把签名集合纳入结构 dump；任何签名变化单独评估并补 `JournalNbtMigrator` 步骤 |
| 升级触发全量重模拟 | 步骤① 与步骤③ 各一次 | 已由 D2 接受；若整合包规模导致时长不可接受，再评估兼容读取旧缓存 |
| 旧概率 NBT 类型不匹配 | 旧 `probability` 是 String，新 codec 若直接按结构读取，可能在哈希比较前报错或静默得到伪值 | decoder 先检查 tag 类型；旧格式按单表缓存未命中跳过，摘要与场景概率同规则；用旧世界副本验证可加载并自动重模拟 |
| 重载期间代际混用 | 多个静态 map 分步清空/填充，或旧 worker 结果写入新目录 | session 局部完整构建后原子发布；工作项携带 generation，提交前校验；reload 继续 pause + clearQueue |
| 批次过大 | 影响面 13 处构造、约 31 处概率读取、14 个引用文件 | 按 §4.5 子批次推进，每个独立编译并单独提交 |
| 固定种子的概率被误用 | 种子模式与真实运行不同源 | 开关只作临时验证用途，验收后删除（D3）；不写入玩家存档 |
| 客户端与服务端不同版本 | 步骤② 改变 DTO 字段结构 | 与模组版本一起发布；客户端不落盘目录数据，无跨版本兼容负担 |
| 未实测 | 本规划的全部结论基于源码 / 补丁 / 数据包阅读与推算 | 按 §七 验证；实机部分由用户执行 |

## 七、验证方式

**编译验证（开发执行）**：每批次 `./gradlew build`，等待时间不短于 120 秒，确认 BUILD SUCCESSFUL。

**等价性验证（开发执行，临时手段）**：

- 判据：同一 `seed` 下，重构前后的逐表逐签名概率快照逐位一致。
- 步骤① 另需**结构 dump 对齐**：解析与建图无随机性，物品签名集合、获取路径（entry / inherited 条件、`sourceChildTable`、`sourceItemTag`）、`childTables` 集合与图不变量直接比对，强于概率对比。
- 实现要点见 §3.5；验收完成后删除开关与其输出。

**最小结构验收矩阵（开发执行，临时 fixture / dump，验收后删除）**：

| 场景 | 必须证明 |
|---|---|
| 同一子表在两个位置被引用，且 conditions / functions 不同 | 图可去重拓扑边，但 `ReferenceSite` 与最终获取路径保留两份且顺序稳定 |
| 自循环、两节点循环、共享 DAG 子树 | 只排除追踪根初始可达集中的循环成员；共享节点只聚合一次；无关 SCC 不新增日志。**注意**：SCC 排除集 ⊇ 旧回边切片集合（§3.9 之八），须按"环上所有表"而非旧实现逐字复刻来验收 |
| 重复引用与缺失目标表 | 重复引用不丢语义路径；缺失目标只告警并跳过，不使整个 session 半发布 |
| item tag 成员变化、loot table JSON 不变 | 编译摘要变化并触发对应表**及祖先**缓存失效（只验子表自身不够，见 §3.9 之一） |
| 仅低优先级 inactive resource-pack 层变化 | 明确记录并验证采用的哈希语义；不得意外改变有效 JSON 或引用图 |
| JSON 引用边与 `RUNTIME_INJECTION` 合成边同时存在 | 两者都进入 closure / directory / hash，只有 JSON 边进入 semanticLink；动态注入物仍为 `injected=true` |
| 无 `format_version` 且使用旧版 `StringTag probability` 的 SavedData | 世界可正常加载；旧表按缓存未命中重模拟，不被解成 0、不影响其他合法表 |
| reload 时存在未完成 worker 任务 | 旧 generation 结果无法提交到新 session，客户端只看到完整代际 |

**实机验证（用户执行）**：

1. 创建或载入世界，确认启动无异常、`解析到 N 个考古战利品表原始目录` 与 `从缓存恢复 N 个表，N 个待模拟` 日志符合预期。
2. `/usb journal reload` 观察逐表进度与每表"有效计算 / 跨 tick 历时 / 剩余队列"日志。
3. 打开考古笔记，逐项核对：物品概率与场景范围、子表入口及其概率、条件树、排序、搜索定位。
4. 触发一次真实掉落（刷拭 / 钓鱼 / 开箱），确认解锁与日志条目正常写入，签名与目录条目对应。

**重点核对项**：同一 seed 前后概率一致；tag 成员变化会触发重模拟；修改 `mud_dredging` 会让 `fishing` 的重模拟失效；合成边不会把泥地打捞物品静态编译进 fishing；环表仍被排除且每个相关 SCC 只输出一次日志；无关 SCC 不新增日志；旧概率 NBT 能安全丢弃并重建；条件指纹守卫的两条告警在正常整合包下不刷屏。

## 八、待确认项

1. **oracle 开关的落点**：开发期系统属性 + 临时输出是最小面，但也可做成正式调试命令以便后续排查概率异常。推荐先按最小面实现，验收后按需再决定是否保留。
2. **步骤② 的提交粒度**：是否严格按"先双跑、再切消费者、最后切 DTO"三次提交。推荐是，便于分别定位语义偏差与网络形态问题。
3. **本文件的归档时机**：三步全部完成后补 §九 并移入 `docs/archive/`（该目录不入库）。若中途放弃，应在 §九 记录放弃原因与已落地的部分。

## 九、实施结果

### 2026-09-18 步骤① 完成（快照 + 引用图）

**构建结果**：`./gradlew build` BUILD SUCCESSFUL；新增/改动文件经 IDE 静态检查（warning 级）无遗留提示。

**新增文件**

| 文件 | 内容 |
|---|---|
| `loottable/source/LootTableSourceSnapshot` | 一次 `listMatchingResourceStacks` 捕获全部战利品表；栈首（index 0，最高优先级层）为有效原文，整个栈供哈希。原文与 `JsonElement` 惰性读取并缓存，`directReferences` 按 `LootParseUtil.normalizeType` 统一类型判定 |
| `loottable/graph/LootTableEdge` | `target` + `Kind`（`JSON_REFERENCE` / `RUNTIME_INJECTION`），两类边的四维参与规则写在类型注释里 |
| `loottable/graph/LootTableReferenceGraph` | `build`（含合成边双端存在守卫与目标去重）/ `directEdges` / `directChildren`（只取快照中真实存在的目标）/ `reachableFrom`（可带排除集）/ `descendantsInclusive` / `cyclicComponentsIn`（Tarjan SCC，scope 受限）/ `updateSubtreeDigest`（并入后代每张表的资源栈摘要与编译产物摘要） |
| `loottable/graph/RuntimeLootLinks` | 两个战利品表标识符 + 一个条件类型标识符 + `syntheticEdges()` + `contextKind(declaredType)` |

**改造文件**

| 文件 | 改动 |
|---|---|
| `journal/catalog/ArchaeologyJournalCatalog.java` | 改为消费图：闭包走 `reachableFrom`、环走 `cyclicComponentsIn`、子表走 `directChildren`；删除 `buildReferenceGraph` / `collectDirectReferences` / `collectReachable` / `findCycleTables` / `dfsCycles` / `addRuntimeInjectedReferences` 与本地 FISHING / MUD_DREDGING 常量；`LoadResult` 增加 `referenceGraph` 供哈希复用同一份拓扑 |
| `loottable/analysis/LootTableJsonParser.java` | `load` 接收 `LootTableSourceSnapshot`，子表展开改用 `effectiveJson`，不再持有 `ResourceManager`、不再读盘 |
| `journal/catalog/ArchaeologyJournalServerCatalog.java` | `ensureLoaded` 先捕获快照；`computeTableHashes` 改为图摘要 + 编译产物摘要；删除 `updateTableResourceDigest` / `collectReferencedTables` / `isLootTableEntry` / `updateRawDefinitionDigest` 与 `LOOT_TABLES` |
| `loottable/simulation/SimulationProfile.java` | `eligibleConditions` 改吃声明的 `type`，上下文由 `RuntimeLootLinks.contextKind` 判定 |
| `loottable/simulation/SimulationScenarioPlanner.java` | `FISHING` / `MUD_DREDGING`（条件类型）/ `MUD_DREDDING_TABLE` 改引 `RuntimeLootLinks`（顺带修掉条件类型 id 用裸字面量 `"unsuspiciousblock"` 的不一致） |
| `loottable/condition/MudDredgingCondition.java` | `MUD_DREDGING` 的 `ResourceKey` 派生自 `RuntimeLootLinks.MUD_DREDDING_TABLE` |
| `loottable/condition/ModLootConditions.java` | 条件类型 id 改引 `RuntimeLootLinks.MUD_DREDDING_CONDITION` |
| `mixin/interaction/FishingHookMixin.java` | 根表标识符改引 `RuntimeLootLinks.FISHING_TABLE` |

**与计划的偏离**

1. `LootTableEdge.Kind` 只保留枚举常量，四维参与规则写在类型注释；未暴露 `contributesToClosure` / `contributesToHash` / `contributesToSemanticLink` 访问器——前两者对所有现有类型恒为真，暴露出来是死代码；`contributesToSemanticLink` 到步骤② 由投影层消费时再加。
2. `LootTableReferenceGraph` 未提供计划草案里的 `cyclicNodesIn`（由 `cyclicComponentsIn` 取代，日志按分量输出需要分量粒度）与 `missingTargets`（当前无消费者，悬空引用与今天一样静默跳过）。
3. `computeTableHashes` 未 bump `SIMULATION_CACHE_VERSION`：`needsResimulation` 是逐表哈希比对，摘要组成变化本身即触发重模拟，版本号留给步骤③ 的 NBT 格式变更。
4. 已按 §3.9 之一把编译产物摘要做进子树：`updateSubtreeDigest` 对 `descendantsInclusive` 内每张表都写入资源栈摘要与编译产物摘要，而非只写根表。

**结构变化（会触发一次全量重模拟，已由 D2 接受）**

- 每表哈希输入从"根表内联整棵子树的物品签名 + 从每一层文本递归追引用"变为"子树内每张表的完整资源栈摘要 + 编译产物摘要"。哈希值必然变化，旧缓存按逐表哈希比对自动失效。
- 环排除集从"DFS 回边栈切片并集"改为"强连通分量成员"。**这不是等价替换**：用随机图对照验证，SCC 恒为旧集合的超集（80 万次试验零反例，见 §3.9 之八）——旧实现会漏报不在任何回边切片上的环成员。现有数据（模组 + 原版）无环，两种实现的排除集都是空集，因此对本批次零可见影响；差异只在数据包构造循环表时体现，届时 SCC 会多排除一部分环上表，即更接近 §3.7 描述的意图"环上所有表排除出收录闭包"。这是步骤① 唯一超出"行为保持"的语义变化，如需逐字复刻旧集合需改回回边切片。

**待用户实机验证**（§七 的实机清单，另加本批次特有的三项）

1. 启动/载入世界确认 `解析到 N 个考古战利品表原始目录` 与 `从缓存恢复 N 个表，N 个待模拟` 符合预期，且**本轮应观察到全部表重新模拟一次**（哈希组成变化）。
2. 确认钓鱼表页签下的泥地打捞子表入口仍在且概率正常（合成边未因守卫被误丢）。
3. 若整合包自带循环引用的战利品表，确认告警为每个环一次、且环上表确实被排除出目录。
4. 打开考古笔记核对：物品概率与场景范围、子表入口及其概率、条件树、排序、搜索定位。

> 验收结果：用户实测未发现明显 bug，本批次已提交。

### 2026-09-18 步骤② 子批次 1 完成（编译 / 投影层 + 双跑对比）

按 §4.5，本子批次只建类型并双跑对比，**不切换任何消费者**，因此目录行为与步骤① 完全一致（多一次编译 + 投影的开销，验收后随对比设施一起消失）。

**新增文件**

| 文件 | 内容 |
|---|---|
| `loottable/analysis/CompiledLootTable` | 上下文无关编译产物：按 JSON 出现顺序排列的事件流（`ItemPath` / `ReferenceSite`），每个事件携带两个**本表局部**快照（继承条件链、函数链）。既不缓存展开结果，也不含任何跨表信息 |
| `loottable/analysis/LootTableCompiler` | 单表 JSON → 编译产物。遇到 `loot_table` 引用只记 `ReferenceSite` 不跟随；item tag 在编译期展开为具体物品；遍历结构与条件/函数链累积次序与解析路径逐条对齐 |
| `loottable/catalog/LootTableProjector` | 图 + 编译产物 → `StaticTableProjection`。链接期复现首跳归属、条件继承顺序、函数继承与 `APPROX_ITEM_ONLY` 降级三条不变量；引用位置按出现顺序逐个进入，不去重 |
| `loottable/catalog/StaticTableProjection` | 静态投影读模型：展平物品条目 + 子表入口，概率保持 `"?"` 占位，不兼任网络 DTO |
| `loottable/catalog/ItemDefinitionAccumulator` | 同签名多条获取路径的合并逻辑，解析与投影两条路径共用同一实现，杜绝合并规则漂移 |
| `journal/catalog/StaticProjectionShadowCheck` | **临时**双跑对比设施：逐项比对物品签名集合、展示名与 tooltip、获取路径（条件 / 首跳来源 / tag 来源）、声明类型、子表入口；只告警不改行为 |

**改造文件**

| 文件 | 改动 |
|---|---|
| `loottable/analysis/LootParseUtil` | 迁入 `parseConditions` / `extractTypeId` / `prependFunctions` / `appendConditions`，成为条件分析与函数链拼接的唯一实现——两条路径必须产出逐位一致的条件指纹与函数顺序 |
| `loottable/analysis/LootTableJsonParser` | 改用共享工具与 `ItemDefinitionAccumulator`，删除私有副本与 `ItemDefinitionBuilder` |
| `journal/catalog/ArchaeologyJournalCatalog` | 解析之后追加一次编译 + 投影并调用对比设施（临时代码，已就地标注删除时机） |

**实施中修正的自身错误**

1. 初版把 `resolveEntry` 的函数处理抽成 `applyHandler` 辅助方法，导致 `previewStack` 的**原地更新**丢失（后续函数会作用在错误的物品栈上），且额外加了一条"近似签名即视为条件性"的判断——两者都会破坏逐位一致。已改回逐字照搬原逻辑。这是 §六 首项风险（语义复现不全）的一次实际命中，说明双跑对比这道门是必要的。
2. 编译器初版给 `recordItem` 保留了 `sourceItemTag` 形参，实际只有 tag 分支传非空值且该分支已自行发事件，形参恒为 `null`。已去掉。

**遗留的既有行为（本轮刻意保留，未修正）**

解析路径对 `group` / `alternatives` / `sequence` 条目**只并入自身条件、不并入自身函数**；未知类型条目同样不并入自身函数。这会让这些层级上声明的 `functions` 被忽略。编译路径逐字复现了该行为——因为修正它会改变签名（不变量 #4），属于独立议题，不在本轮范围。

**待用户实机验证**（本子批次特有，一次加载即可给出结论）

1. 载入世界或执行 `/usb journal reload`，确认日志出现一行
   `[投影双跑] 对齐通过：N 张表，物品签名集合、获取路径、条件与子表入口逐项一致`。
2. 若出现 `[投影双跑] 发现差异`，请把该行及其后最多 5 张表的差异明细贴回——每条差异会同时打印解析侧与投影侧的完整渲染文本，可直接定位到具体字段。
3. 注意双跑期间同一个缺失子表 / 循环引用会各告警一次（两条路径各一条），属预期。
4. 已知的假阳性：若某条件的 `toString()` 是默认身份实现，其指纹元数据含 `类名@hash`，两条路径各自解码出的实例必然不同 → 差异只体现在元数据字段上。现有条件类型均为 record（值语义文本），按现有数据不应触发；若出现且差异仅限此类元数据，可判定为假阳性而非语义偏差。

**下一步（子批次 2 的前置条件）**：只有在上述对齐通过后，才按 §4.4 切换消费者（`collectSubtreeItems` 的四个调用点、`getRawTable` 系查询、`computeCatalogHash` 输入源），否则语义偏差会被固化进投影层。

**验收结果**：用户实机执行 `/usb journal reload`，日志输出
`[投影双跑] 对齐通过：58 张表，物品签名集合、获取路径、条件与子表入口逐项一致` —— 门通过。

### 2026-09-18 步骤② 子批次 2 完成（投影层切为生产路径）

**改动**

| 文件 | 改动 |
|---|---|
| `journal/catalog/ArchaeologyJournalCatalog.java` | 解析改为"编译 → 投影"：`LootTableCompiler` 编译 `validClosure`，`LootTableProjector` 建投影，再按表 id 排序把投影转成 `TableDefinition`。展示名登记改为对 `validClosure` 全量调用一次（与原解析路径的调用集合一致），无物品的表仍不进目录，子表入口由投影层按同一规则过滤 |
| `loottable/analysis/LootTableJsonParser.java` | **删除**（连同私有 `ResolvedEntry` / `ItemDefinitionBuilder`） |
| `journal/catalog/StaticProjectionShadowCheck.java` | **删除**（D3：验收后删除临时设施） |

**行为保持的依据**：双跑对比已在真实数据上证明两条路径逐项一致；本批次只是把生产路径换成已验证的那一条，并把展示名登记、无物品表过滤、子表入口过滤三处调用集合逐一对齐。目录对外形态（`TableDefinition` / `ItemDefinition` / 网络 payload / 客户端）本批次一律未动。

**顺带消掉的重复**：编译期只在 `LootParseUtil` 里解析条件与拼接函数链，条件指纹与签名顺序对整条链路只有一个来源；`ItemDefinition` 的多路径合并同样只剩 `ItemDefinitionAccumulator` 一份实现。

**待用户实机验证**

1. 载入世界或 `/usb journal reload`，确认 `解析到 58 个考古战利品表原始目录` 数量不变，且**不再出现** `[投影双跑]` 相关日志（设施已删除）。
2. 确认没有新增告警，特别是没有 `展开 loot_table 引用 … 失败`、`检测到战利品表循环引用` 这类来自投影层的告警。
3. 打开考古笔记逐项核对：物品概率与场景范围、子表入口及其概率、条件树、排序、搜索定位——应与上一次验收（步骤① 状态）完全一致。

**下一步（子批次 3）**：`CatalogQueryIndex` 替换 `collectSubtreeItems` 的四个调用点，并引入 `LootTableAnalysisSession` / `CatalogGeneration` 承载投影集合与 generation 原子发布；随后清理 `LootTableCatalog` 的便捷构造器与 `collectSubtreeItems` 本身。

