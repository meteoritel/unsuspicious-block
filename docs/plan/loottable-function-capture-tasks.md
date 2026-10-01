# 战利品函数捕获——分点任务书

> 本文件是 [战利品函数信息补全与模拟执行捕获规划](loottable-function-capture-plan.md) 的**执行拆分**，
> 建立于 2026-10-01。规划文档仍是设计权威；本文件只回答"谁在哪个文件里做什么、做到什么算完"。
>
> 状态标记：`[P0]` 契约冻结 · `[T1]` 静态描述 · `[T2]` 投影与目录 · `[T3]` 运行时捕获 ·
> `[T4]` 缓存与来源 · `[T5]` 展示与 i18n · `[T6]` 验证与文档。

## 零、写范围总表（**冲突即停止并上报 Lead**）

| 任务 | 独占写范围 | 依赖 |
|---|---|---|
| P0 契约 | `loottable/analysis/FunctionFidelity.java` `FunctionEffectKind.java` `LootFunctionInfo.java` `LootFunctionDescriptions.java` `LootFunctionHandler.java`；`loottable/catalog/LootOriginKind.java` | 无 |
| T1 静态描述 | `loottable/analysis/LootFunctionHandlers.java`（其余 analysis 文件可增补新类） | P0 |
| T2 投影与目录 | `loottable/catalog/*`（除 LootOriginKind）；`network/payload/s2c/CatalogStreamCodec.java`。**实际执行**：LootTableProjector 由 projection 完成后 Lead 复审并补修；CatalogTableDto / CatalogStreamCodec 因 owner 反复在大改动上中断，由 Lead 接管 | P0 |
| T3 运行时捕获 | `loottable/simulation/*`；`mixin/loottable/*`；`loottable/diagnostics/LootSimulationMetrics.java`；`common/src/main/resources/unsuspiciousblock.mixins.json` | P0 |
| T4 缓存与来源 | `world/LootProbabilityData.java`；`journal/catalog/ArchaeologyJournalServerCatalog.java` `CatalogGeneration.java` | T2 |
| T5 展示与 i18n | `client/ui/support/JournalTooltipBuilder.java` `client/ui/screen/JournalViewModel.java` `client/ui/entry/*` `client/state/ScenarioSimulationClientState.java`；两个 lang JSON | T2/T4 |
| T5a i18n 填充 | 两个 lang JSON（由 `static-desc` 执行，只新增） | T1 |
| T6 验证与文档 | `docs/dev/**` `docs/todo/**`（`docs-sync`）；`docs/plan/*`（Lead） | 全部 |

**边界铁律**：`common/` 不得 import 平台类；服务端不得引用客户端类；不新增 `test` 文件；
任何新 HUD/GUI 文本必须同步 `en_us.json` 与 `zh_cn.json`。

---

## P0：契约与入口确认

**目标**：冻结静态描述与来源表达的数据契约，使 T1/T2/T3 可以并行。

**产物**
- `FunctionFidelity`（FULL / PARTIAL / UNRESOLVED）——描述保真度，与运行时捕获完整度是**两个轴**。
- `FunctionEffectKind`（COUNT / ITEM_TRANSFORM / COMPONENT / WRAPPER / CONTAINER / UNKNOWN）。
- `LootFunctionInfo`（functionType, description, fidelity, effect, conditions, children, metadata）。
  不变量：不含执行次数；不改签名；`conditions` 是函数自身条件；`metadata` 是**有界稳定键值**。
- `LootFunctionDescriptions`——"取 handler → 描述 → 兼容回退"的唯一入口。
- `LootFunctionHandler.describe(LootItemFunction, JsonObject)` 默认返回 `null`（未迁移标记）。
  旧 `describeHint` 降级为**兼容摘要**，不再是独立信息来源。
- `LootOriginKind`（STATIC / MOD_INTEGRATION / UNKNOWN_RUNTIME）——集合语义，取代互斥布尔。

**完成门槛**
- 两个候选捕获点可维护；不可覆盖项有降级口径；不以无限扩展 Mixin 作为失败兜底。
- splitter 的准确字节码目标与两平台 remap 已核对（或明确记录"不可维护 + 选定替代方案"）。

---

## T1：静态函数描述与语义修复（对应规划 P1 的"静态说明"一半）

**目标**：不依赖任何运行时捕获，也能解释"这条路径声明了什么"。

**必须修掉的缺陷**（规划 §1.3）

| 编号 | 要求 |
|---|---|
| F01 | 每个已注册函数给出结构化 `LootFunctionInfo`，含类型化参数与保真度 |
| F03 | `enchanted_count_increase` 修正为 **COUNT** 效果：不再提升书预览、不再派生附魔签名 |
| F05 | `sequence` / `filtered` / `reference` 展开为 WRAPPER + `children`；数组形式按 `ROOT_CODEC` 序列语义解释 |
| F08 | `set_enchantments` 的等级是 `NumberProvider`，须区分常量与动态；`add=true` 依赖输入已有等级 |
| F09 | `set_loot_table` / `set_contents` / `modify_contents` 归为 **CONTAINER** |

**高价值字段**（规划 §4.4）：`enchant_randomly`（可选附魔、`only_compatible`、**缺省 ≠ 显式空集合**）、
`enchant_with_levels`（levels 提供器 + 可选附魔集合）、`apply_bonus`（附魔 + 公式 + 该公式的参数）、
`set_count`/`limit_count`/`set_damage`、`set_attributes`（属性/提供器/槽位/替换方式）、
`set_stew_effect`、`copy_*`（复制目标/来源类别）、`set_item`/`set_components`/`set_potion`、`furnace_smelt`。

**验收**
- 每个内置函数 `describe` 返回非 null 且 `fidelity != UNRESOLVED`；未知 mod 函数标 UNRESOLVED。
- 动态数值不得被猜成固定值；实测范围不得冒充声明范围。
- 新增 i18n key 写得下但不落文案（文案归 T5）——**key 命名必须先冻结并在此登记**。

**i18n key 冻结**（T1 只能新增以下格式的 key，T5 负责填两个 JSON）
```text
screen.unsuspiciousblock.archaeology_journal.function.<name>
screen.unsuspiciousblock.archaeology_journal.function.<name>.<variant>
screen.unsuspiciousblock.archaeology_journal.function.param.<name>
```

---

## T2：投影、路径级函数与网络形态

**目标**：让"每条获取路径声明了哪些函数"进入目录派生与展示链路，并修掉条件归属错误。

**工作**
1. `LootAcquisitionPath` 增加 `List<LootFunctionInfo> functions`（保留既有构造重载，默认 `List.of()`）。
   纳入路径相等性与去重——条件相同、函数不同的两条路径不得被合并丢失。
2. `LootTableProjector`：**F04** —— 函数自身条件不再并入 `resolvedConditions`，原位保存到对应函数节点；
   预览只在能证明执行条件与效果时静态应用，否则保留条件效果说明与不确定性。
3. `tooltipHint` 改为由结构化描述派生；附魔通用摘要可以保留，但**不得覆盖参数**（F02/D15）。
4. `ItemDefinition` 增加 `Set<LootOriginKind> origins`；`injected` 保留为**兼容派生**，不新增第二份权威。
5. `ScenarioBranchCatalog`：新增函数条件不得再进入它的主门槛；静态函数可随分支展示。
6. `CatalogTableDto` + `CatalogStreamCodec`：传输有界结构化函数摘要（镜像条件树的编解码形态），
   不传 `LootItemFunction`/`LootContext`/`ItemStack`/完整原始 JSON。

**验收**
- 未模拟时也能解释规则；不把函数条件当基础掉落条件。
- 编解码字节顺序自洽（写与读严格对称）；上游消费端同步更新。

---

## T3：最小运行时闭环（对应规划 P2）

**目标**：把捕获到的函数执行关联到最终产物。

**工作**
1. `FunctionTraceSession`（普通类，业务逻辑不写进 Mixin）：
   `IdentityHashMap<ItemStack, TraceHandle>` 旁路关联 + 有界字典 + 截断标志。
   **禁止**写追踪组件/NBT；**禁止**用"相同物品+相同组件"回退作为函数链归因。
2. 复用 `LootSimulationScope` 生命周期挂接 session；每轮清空栈关联；任务聚合数据留在 job 中。
3. 两个窄 Mixin（**只转交参数与返回值**）：
   - `LootItemConditionalFunction#run` 的 `apply` 内调用包装——语义严格为"外层条件通过并进入函数执行体"，
     **禁止**重新执行 `conditions.test`（会改变随机序列）。
   - `LootTable.createStackSplitter` 内的 `copyWithCount` 包装——`ItemStack` 副本继承链。
     **准确目标方法名、描述符与两平台 remap 必须实机核对**；不可维护时改用"保留原 splitter、
     包装其输入/输出 Consumer"的替代方案，二者**只选一种**；禁止全局注入 `ItemStack.copy`。
4. 容器隔离：`set_contents`/`modify_contents` 进入执行帧时暂停子内容采集，退出恢复。
5. job 侧在**全部计入结果的路径**上统一聚合（含早退的 `continue` 分支），不遗漏动态条目。
6. `SimulationMeasurement` 增加输入级观测摘要；`LootSimulationMetrics` 增加捕获耗时/唯一链数/截断数。

**预算**（实施起始值，不静默截断）：每条链 ≤64 节点、嵌套深度 ≤16、每结果 ≤16 条链、
每输入 ≤4096 唯一描述/链条目、每轮 ≤4096 栈对象关联、单输入编码总量 ≤256 KiB。

**验收**
- 同结果不同链、函数换物品、拆栈都保留正确片段；作用域外无记录；不额外消费随机数。
- 记录器自有异常降级为"观测不可用"；原函数异常按原有模拟失败规则传播，两类异常代码结构分开。

---

## T4：缓存、来源分型与失效

**目标**：新算与缓存恢复展示一致；第三方不可解释时不猜。

**工作**
- 观测摘要进入输入级测量缓存（随 LRU 淘汰）；表级 `discovery` 继续保留动态物品存在性。
- 读取最新 `FORMAT_VERSION` / `SIMULATION_CACHE_VERSION` 并递增；旧测量按**缓存未命中**处理，不迁移。
- 失效口径纳入：函数描述规则版本、静态函数结构、被展开的 item modifier、相关 tag/附魔定义。
  熔炼等运行时外部依赖无法可靠摘要时采用重载代次/会话盐。
- 平台注入复用 `DeclaredLootInjection` / `describeLootInjections` / `RuntimeLootLinks`，
  **不新造注入规则库**；未确认来源保留 UNKNOWN_RUNTIME。

**验收**
- 重启/换输入/重载后说明一致；不串场景；不用别的输入的记录证明当前输入执行过。
- 截断只裁观测详情，不删物品条目、不改概率。

---

## T5：展示与 i18n

- 每条静态路径展示"生成规则"；当前输入有观测时展示"本次模拟观测到的函数"。
- 观测中多个链使用**备选分组**，不能用逗号拼成一个因果链。
- "进入执行体"不写成"必然生效"；无观测 / 部分捕获 / 未解析 / 截断各有简短提示。
- `en_us.json` 与 `zh_cn.json` 同步；`injected_loot` 已有 key 直接复用。

---

## T6：验证与文档

1. 先 IDEA MCP 检查 git 工作区改动文件的报错/警告（忽略 markdown 格式问题）。
2. 执行一次 `./gradlew build` 并等待到结束；总等待预算 ≥120 秒，分段等待同一任务，不并发启动第二次构建。
3. 核对 Fabric remap、NeoForge/common 编译与打包、Mixin 配置、端隔离、DTO 构造与双语 key 同步。
4. 同步 `docs/dev/subsystems/loottable.md`、`docs/dev/internals/loottable-mechanics.md`、
   `docs/dev/foundation/mixin.md`、`docs/dev/foundation/network.md`、`docs/todo/loottable-function-capture.md`。
5. 在规划文档"实施结果"登记验证与偏离，附实机验证矩阵。

**明确不做**（规划 §2.3 全量保留）：不保证精确定位运行时 table/pool/entry 索引；不逐函数算概率；
不逐个适配第三方 `LootItemFunction`；不重写原版执行器或 `compose`；不迁移玩家笔记进度。

---

## 十、执行结果（2026-10-01）

本轮按 P0→P4 推进，**代码变更全部落在 common 模块**，未新增 test 文件。实际归属与偏差：

| 任务 | 状态 | 执行者 | 偏差说明 |
|---|---|---|---|
| P0 契约 | 完成 | Lead | 无 |
| T1 静态描述 | 完成 | static-desc | 40/40 内置函数实现 `describe`；新增 `LootFunctionDescribeSupport` |
| T2 投影与目录 | 完成 | projection + Lead | Projector 由 projection 完成、Lead 复审补修提示派生；DTO/Codec 因 owner 中断改由 Lead 接管 |
| T3 运行时捕获 | 完成 | capture | splitter 采用任务书允许的 **Consumer 包装降级方案**（窄注入证据见其汇报） |
| T4 缓存与来源 | 完成 | Lead | 观测进入输入级测量缓存；`FORMAT_VERSION` 4→5；动态条目来源修正为 `UNKNOWN_RUNTIME` |
| T5 展示与 i18n | 完成 | Lead + static-desc | 规则/观测分区渲染；105 个新 key 双语落盘，两侧各 1104 key 且集合一致 |
| T6 验证与文档 | 完成 | Lead + docs-sync | 三模块 `gradlew build` 通过；开发者文档 4 篇 + 待办快照已同步 |

**新增文件（13 个 Java）**：`FunctionFidelity`、`FunctionEffectKind`、`LootFunctionInfo`、`LootFunctionDescriptions`、
`LootFunctionDescribeSupport`、`LootOriginKind`、`FunctionTraceSession`、`TraceNode`、`TraceHandle`、
`ObservedFunctionChain`、`FunctionObservationSummary`、`LootItemConditionalFunctionMixin`、`LootTableSplitterMixin`。

**验证记录**：`common` / `fabric`（含 remapJar）/ `neoforge` 三模块一次完整 `gradlew build` 通过；
34 个改动 Java 文件 IDEA `lint_files(min_severity=error)` 无错误；两个 lang JSON 各 1104 key 且集合一致。

**已知限制（与规划 §六 一致，未因实施放宽）**：
- 单输入**编码总量 256 KiB** 硬上限未实现：只落了结构上限（每链 ≤64 节点、每结果 ≤16 链、每输入 ≤4096 条目）。
- 观测只承载函数注册名与捕获状态，不含参数级文本；参数级信息仍在静态侧。
- splitter 走 Consumer 包装降级方案，未做 lambda 窄注入。
- 完整运行时位置映射、第三方修改器内部追踪、容器内容逐物品归因仍为范围外。

---

## 十一、复核修复轮（2026-10-01 第二轮）

用户在首轮交付后逐条复核，提出 8 项缺陷与 2 项未闭环的验收项。**全部先核对源码/原版字节码确认存在，再修复。**

### 11.1 逐条核对结论（实施记录，含后续修正）

| 编号 | 问题 | 核对证据 | 修复 |
|---|---|---|---|
| R1 | 合法函数序列可能导致目录解码失败 | `CatalogStreamCodec.writeFunction` 写 `children.size()` 无界；`readFunction` 用 `SimulationInputCodec.count(buf, 64)`，越界抛「非法集合长度」；`count()` 实现确认会抛异常 | 上限仍为 `LootFunctionInfo.MAX_CHILDREN_PER_NODE=64`；第三轮改为仅写包时裁剪并标记，服务端完整列表供语义分析；读端使用同一常量 |
| R2 | 切换输入后把别的输入的观测显示成「本次模拟观测」 | `ScenarioSimulationClientState` 的 `mergeStructure`、`overlay` else 分支、`projectScene` 三处都在传非当前输入的 `observedFunctions` | 三处一律置 null；口径写进注释：只有精确命中当前 input key 的 DTO 才携带观测（baseline 公共目录是例外） |
| R3 | 函数条件与掉落条件分离后遗漏场景规划接入 | `SimulationScenarioPlanner.collectPathRequirements` 只用 `path.allConditions()`；函数条件已迁到 `LootFunctionInfo.conditions` | 新增 `collectFunctionConditionGroups` 递归收集函数条件，仅用于**场景候选枚举**；`satisfiedBy`/物品可达性仍只看生成条件 |
| R4 | 包装函数说明两处信息丢失 | `describeNested` 原样返回内层描述、不解析内层 conditions；`JournalTooltipBuilder.appendFunctionRules` 不递归 children | 新增 `LootFunctionDescriptions.withConditionOps` 条件解析上下文，`describeNested` 原位补挂内层条件；tooltip 递归渲染，深度上限 8 |
| R5 | apply_bonus 按错误 JSON 结构读公式参数 | 原版 `ApplyBonusCount`：`ExtraCodecs.dispatchOptionalValue("formula","parameters",FORMULA_TYPE_CODEC,...)`，`FORMULA_TYPE_CODEC=ResourceLocation.CODEC` | 按字符串读 formula 并规范化 namespace；参数优先读同级 `parameters`，回退内嵌对象 |
| R6 | set_enchantments 空映射被描述为「清空附魔」 | 原版 `SetEnchantmentsFunction.run` 只有 `enchantments.forEach(...)`，**没有清空逻辑** | 分支与双语文案改为「空操作」；`apply()` 的书→附魔书转换保留（原版确有） |
| R7 | 64 节点上限没有限制实际计算量 | `Frame.addChild` 无界；`TraceHandle.of` 先 `addAll(prefix.chain)` 再全展平，最后才裁剪 → 前缀反复复制，O(n²) | `addChild` 有界并 `markTruncated`；`of` 改增量构造、`flattenBounded` 达界即停；预算耗尽仍继续执行原函数 |
| R8a | 外部依赖变化不会让观测缓存失效 | `computeTableHashes` 只摘资源栈/编译产物/注入声明/附魔定义，无 item modifier、无熔炼配方、无重载盐 | 原直接摘要存在闭包与配方输入遗漏；第三轮替换为敏感表及其父表按代加盐失效；`SIMULATION_CACHE_VERSION` → `loot-analysis-v23` |
| R8b | 同签名的普通来源与「模组联动」没有合并 | `buildGeneration` 与 `clientTable` 在同一签名时直接 `continue` | 两处改为	extbf{合并}：注入路径并入既有条目、来源集合取并集含 `MOD_INTEGRATION` |

### 11.2 新增：开发模式调试表

- `loottable/diagnostics/LootDebugMode`：系统属性 `usb.loot.debug` 或环境变量 `USB_LOOT_DEBUG`，进程内只读一次。
- 关闭时：调试表不进收录闭包（`ArchaeologyJournalCatalog` 门禁）、调试分类资源被跳过（`JournalCategoryLoader` 门禁）。
- 资源：`journal_categories/dev_debug.json`（分类，order=-100）、`loot_table/archaeology/debug/function_capture.json`、
  `loot_table/archaeology/debug/child_echo.json`、`loot_table/archaeology/debug/oversized_sequence.json`（70 个子函数，验证 R1）、
  `item_modifier/debug_bonus.json`（供 reference 使用，验证 R8a）。
- 覆盖规划 §7.3 的核对场景：同物品两条路径、条件 set_count、enchanted_count_increase、set_enchantments 三种写法、
  sequence/filtered/reference、身份变换与熔炼后再改组件、超单栈上限拆栈、同轮兄弟物品不同链、容器内容、子表引用。

### 11.3 第二轮实施时的验证记录（不覆盖后续修改）

- IDEA `lint_files(min_severity=error)`：38 个改动 Java 文件 → 0 错误。
- 一次完整 `gradlew build`：`common` / `fabric`（含 remapJar）/ `neoforge` 全部通过，仅 `FishingLootModifier` 既有 deprecation 提示。
- i18n：`en_us.json` 与 `zh_cn.json` 各 1109 key，集合一致。
- 所有新增 JSON 资源通过解析校验。

### 11.4 仍未闭环 / 已知限制

- 多函数条件组合采用 one-hot 近似（受 `MAX_SCENARIOS` 截断），联合成立场景可能不被规划出来；截断后条件仍被钉成 false，不会退回真实求值。
- 单输入编码总量 256 KiB 硬上限仍未实现（只有结构上限）。
- 观测链只承载函数注册名与捕获状态，不含参数级文本。
- 全部改动**仍未实机验证**：Mixin 应用、启动期注入点命中、调试表在游戏内的实际展示都需用户执行。

## 十二、第三轮修复与实机验收（2026-10-01）

### 12.1 已实施修复

| 问题 | 现在的行为 | 取舍 |
|---|---|---|
| 熔炼缓存漏掉输入、tag、输出组件 | 删除不完整配方摘要；含 furnace_smelt 的表及父表每代加盐 | 相关表跨重载重新计算，同代仍复用输入缓存 |
| modifier A→B 与数组包装的依赖遗漏 | 扫描原始函数 JSON；任何 reference 均触发相关表和父表按代失效 | 不维护递归外部依赖图 |
| 展示裁剪丢失第 65 个以后的条件 | 服务端保留完整函数列表；只在写包时裁剪 | 详情展示有上限，并显示省略提示 |
| 数组 modifier 描述丢失 | normalizeSource 统一将 ROOT_CODEC 数组包装为 sequence | 顶层和嵌套走相同路径，不修改原始 JSON |
| 内层条件遗漏外层门槛 | 生成候选时继承祖先包装函数条件 | 仍不作为物品生成条件，仍非完整笛卡尔积 |
| 分析超限后回退真实环境 | 深度/预算超限标记不完整；保留静态表、停用数值和旧测量 | 概率为未知/未解析，不假装稳定结果 |
| 静态截断没有提示 | 顶层/子列表/深度截断写 metadata，双语 tooltip 提示 | 字段形状未变，协议仍为 4.10 |
| 空附魔映射样例不足 | 皮革靴先加保护 II，再执行空 set_enchantments | 可验证已有附魔被保留 |

未新增 Mixin 或 Java 测试文件。运行时对象参数反推、第三方内部归因仍为接受的限制；平台注入仍标为「模组联动」。reference 内部条件不做静态展开，不可据此推断其内部天气/时间已被场景控制。

### 12.2 本轮验证状态

- IDEA MCP 先按 warning 级别检查 53 个 Java/JSON 文件，再复查末次修改的 3 个 Java 文件（合计覆盖 54 个不同文件）：无 Java ERROR；本轮未使用 import 已修复。末次仅保留场景规划器既有的布尔方法调用方向提示。
- IDEA 的 Mixin 插件仍报告无法识别 JAVA_21；git HEAD 原文件同样使用 JAVA_21，属既有检查项，本轮没有降低项目 Java 21 配置。工作区其余未使用成员、方法引用建议等已有提示未作无关清理。
- 本轮 Gradle 命令**没有执行**：申请外部 Gradle 缓存写入时，自动审批服务返回 HTTP 404，错误为 codex-auto-review 无可用账号。这不是构建失败或构建通过，不能以旧轮次结果代替。
- 15 个变更 JSON 均通过解析和重复键校验；中英文各 1111 个 key，集合一致。核对了两个 70 函数样例的末尾天气条件、数组 modifier 的时间条件、重载盐及未裁剪投影；git diff --check 通过，仅有 Git 的既有 CRLF 转 LF 提示。Minecraft / Mixin 启动、双端同步与性能仍待用户实机验证。

### 12.3 用户实机步骤

先在项目根目录执行 `.\gradlew.bat build`，确认 BUILD SUCCESSFUL，再分别用 Fabric、NeoForge 启动独立测试存档；客户端与服务端使用本轮同版本构建。为**游戏 JVM** 设置 `-Dusb.loot.debug=true`，或启动前设置环境变量 `USB_LOOT_DEBUG=true`。开关仅进程启动读取，修改后需重启；进入笔记「开发者调试」分类。

| 表/操作 | 核对方法 | 预期 |
|---|---|---|
| function_capture：皮革靴 | 检查规则、预览和实际 /loot 产物 | 先保护 II，再空映射；已有保护 II 保留，不描述为清空 |
| array_conditions | 检查 filtered → sequence → set_count；分别选基准、仅下雨、下雨且时间条件成立 | 紫水晶碎片数量分别为 1、2、0；时间条件继承外层下雨门槛 |
| oversized_sequence | 70 个子函数，最后一个是下雨时 set_count(0) | 详情最多发送前 64 个子函数并提示省略；仍有下雨场景；基准 1 个石英，下雨 0 |
| oversized_path | 70 个顶层函数，最后一个是下雨时 set_count(0) | 顶层同样裁剪并提示；基准 1 个海晶碎片，下雨 0 |
| 改变真实天气/时间 | 固定上述表的笔记场景；改变真实环境后，用未缓存的抽样次数档位重算 | 所选场景的确定性数量保持一致；只看命中缓存不能证明控制有效 |
| analysis_depth_limit | 打开 10 层 sequence 的燧石表，尝试申请模拟 | 条目可见、规则未解析/省略，概率未知；不恢复旧数值或排队模拟，日志说明分析不完整 |
| reload_dependency 与 reload_parent | 记录铜锭数量 3；测试数据包仅覆盖 item_modifier/debug_chain_b.json，将 count 改为 7，再 /reload | 两表重算并显示 7，表 JSON 和 A 的 reference 声明不改 |
| 熔炼输入变化 | 测试包覆盖 minecraft:iron_ingot_from_smelting_raw_iron，只把 ingredient 的 raw_iron 改为 raw_gold，保持 id/result/数量，再 /reload | reload_dependency 与父表的 raw_iron 不再按该配方熔炼，不复用旧铁锭测量；前提是测试包无另一条匹配 raw_iron 的 SMELTING 配方 |
| 熔炼 tag 变化 | 将配方 ingredient 改为测试 item tag；先含 raw_iron，再仅从 tag 移除 raw_iron，/reload | 配方和表 JSON 不再修改，相关表仍重算并反映新产物 |
| 输出组件变化 | 保持配方 id/ingredient/result id/数量，只给 result 添加有效自定义名称组件，/reload | 新组件与对应签名生效，旧测量不复用；历史 discovery 条目可继续存在，不将其存在误判为缓存命中 |
| 缓存对照 | 同代重复请求同一输入，再重载；对照不含 reference/furnace_smelt 的普通表 | 同代可命中；敏感表跨代重算；无关表依赖不变时仍可持久复用 |
| 关闭调试开关 | 重启游戏，再打开笔记 | 调试分类及调试根表不被收录，正常世界战利品不受样例影响 |

原版对照命令示例：`/loot give @s loot unsuspiciousblock:archaeology/debug/array_conditions`。该命令处于模拟作用域外，使用真实天气/时间；不要与笔记选择的虚拟场景混淆。修改资源请放在测试存档数据包中，验证后移除并 /reload。

### 12.4 验收记录

每个平台分别记录启动/重载日志、各表基准与条件场景截图、截断/未解析提示、A→B 与配方变更前后的数量/签名、关闭调试后的目录。出现启动失败、Mixin 未命中、固定输入随真实环境漂移或旧观测复用时，保留第一条异常和对应表 JSON，停止该项验收后回传。
