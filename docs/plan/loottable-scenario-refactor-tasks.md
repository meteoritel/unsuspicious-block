# 战利品表条件场景重构——执行级拆点清单

> 本文是 [条件场景与按需概率模拟重构规划](loottable-scenario-refactor-plan.md) 的**执行侧清单**（该规划第 4.6 节决策 47 要求执行级拆点另起一份，不写进规划本文）。
>
> 规划记录"为什么这样改"与"裁定依据"；本文只记录"先做什么、做完没有、怎么验证"。分点粒度按可独立编译、可独立验证来切。
>
> 完成一项就在其标题后追加 `✅` 与提交号；验证方式写在该项内，不再单列。
>
> **当前状态（2026-09-22 同步）**：**P0 / PERF / P1 / P2 已全部实施并提交**进 `1.21.1` 分支（静态与编译验证已过：IDEA MCP 无报错无警告、`./gradlew build` 通过）。**P3「保真与兼容」未开工**。
> P2 起的 UI 是当时的**临时验收实现**，已由前端重构（S1–S6）取代：场景页与网格页头部现以 [客户端与 GUI](../dev/client-ui.md) 第 4.4–4.6 节为准，`client-ui.md` 与网络文档已同步，P3-4 的欠账只余 `config-integrations.md`。
> **实机验收**：P1 / P2 的实测由用户自测中（判据见 T2 与 P2 状态口径）；2026-09-22 的条件树修复另见 [战利品条件树修复计划](loottable-condition-tree-plan.md)。
> **下一步**：P3 实机验收与剩余文档同步。原列于此处的「前端重构方案探讨」已完成。

## P0 诚实化与失效链（不依赖 `SimulationInput`，不依赖新协议）

| 点 | 交付 | 状态 |
|---|---|---|
| P0-1 | 数据包重载监听：Fabric `ResourceManagerHelper`、NeoForge `AddReloadListenerEvent` 只置脏标记，重建走 tick 路径（D9） | ✅ |
| P0-2 | 哈希输入并入被引用附魔定义摘要；保证范围拆成两句写入文档（决策 35）；`SIMULATION_CACHE_VERSION` v15→v16 | ✅ |
| P0-3 | `Probability` 新增 `NeedsCondition` 第 4 态，`Unknown` 带 `UnknownReason`；`SimulatedValue` 窄类型隔开持久化与展示（决策 22/36） | ✅ |
| P0-4 | 参数填充按声明 paramSet 的 `allowed` 补填/裁剪，不兼容 paramSet 明确排除（D8） | ✅ |
| P0-5 | 战利品表不可用机制的显式识别与一次性降级诊断（决策 31） | ✅ |
| P0-6 | 网格改读基准场景（全假条件）；显示优先级链：可适用性 → 声明触发率 → 模拟值（决策 2/44）；零命中改「未命中」（决策 40） | ✅ |
| P0-7 | 静态信息性提示 `PathHint` 与「需要条件」文案；中英文案同步 | ✅ |
| P0-8 | 编译验证：IDEA MCP 检查改动文件 + `./gradlew build` | ✅ |

## PERF 慢表性能归因与修复（独立于 P1，未提交 git）

| 点 | 交付 | 状态 |
|---|---|---|
| PERF-1 | 保留原热运行证据；加入掉落栈、候选扫描、组件序列化、存储键调用计数与分段计时；统一日志解析脚本 | ✅ 19:05 第二轮热运行完整，58 张表分支计数守恒 |
| PERF-2 | 结合真实分段数据与 mc-developing-mcp 源码，确认三张慢表的具体成因 | ✅ Relics 随机属性动态签名约 1645～1796 个，候选扫描约 91万～110万次，匹配耗时 468～525ms |
| PERF-3 | 按归因实施最小修复，保留签名语义/存储键、10000 次抽取与动态发现 | ✅ 索引后 MATCH 从 468～525ms 降到 9～14ms，三表 CPU 降低 69%～77% |
| PERF-4 | IDEA 检查、双端 build；用户热运行后生成同口径逐表 CPU 对照与最终结论 | ✅ 索引版 build 53s 成功、58 表对照已生成；<100ms 未达标，残余见 PERF-5 |
| PERF-5 | 复用目录取名预览，避免重复 JSON 解码；依据新实测继续定位 GENERATE 残余热点 | 预览复用已实现、IDEA 无警告，构建及实机收益见性能记录；GENERATE 内部分摊未确认 |

操作与证据：本地观测目录 `docs/archive/loot-performance/`（不入库，随 `docs/archive/` 一并忽略）。

## P1 模拟输入模型与按需管线

| 点 | 交付 | 状态 |
|---|---|---|
| P1-1 | 编译层保留 `weight`/`quality`/`rolls`/`bonus_rolls` 与被引用附魔 | ✅ `LuckSpec` 随事件携带；`bonus_rolls` 缺省按原版 `constant 0` 处理（不是"未知"）；`group`/`sequence` 子节点不参与权重选择，其权重按缺省处理 |
| P1-2 | `LuckGateAnalysis` 逐路径最小幸运门槛（0.01 对齐 + 真实公式回验） | ✅ 门槛按路径保留（`LootAcquisitionPath.luckGate`），含"区间受限/不可达"两档降级；`PathHintAnalyzer` 据此产出可直接照填的数值提示，并在**全部**路径不可达时报 `Unreachable`（`0%`） |
| P1-3 | `SimulationInput` / `ScenarioParams` / `SimulationInputKey`（含抽样次数档位） | ✅ 三件套 + `ToolOption`；抽样次数进输入身份且只接受签发档位；幸运量化到 0.01 网格，使"门槛值"与"手填值"是同一个缓存键 |
| P1-4 | `SimulationConstraintCatalog` 只发布约束，不枚举候选 Input（决策 32） | ✅ 场景候选 / 工具基座 / 附魔等级上限 / 次数档位四份清单；单输入在请求时构造并由 `resolve` 逐项校验，越权整体拒绝 |
| P1-5 | 条件树展开预算（决策 33） | ✅ 节点数与组合集合各一个预算，超限整表按"无约束"降级并告警一次 |
| P1-6 | worker 去重键改 `(tableId, inputKey)`、限流、代次校验 | ✅ 含"同一输入的多个等待者都收到结果"；HIGH 待处理 ≤32、每玩家在途 ≤2（**只约束玩家触发**，启动批量与解锁插队不受约束） |
| P1-7 | 缓存格式 3：表级发现记录 + per-Input 测量值 + 按参数组合计数的 LRU（决策 37/46） | ✅ 见"与规划的已知偏差"5（实际落到 `format_version = 4`） |
| P1-8 | 新 C2S/S2C payload 与协议版本 | ✅ 请求/结果/拒绝三个 payload + 共享 `CatalogStreamCodec`；协议版本 P1 时服务端 `4.5`、客户端 `4.4`，**P2 已随 payload 扩充升到服务端 `4.7`、客户端 `4.6`**；`CatalogTableDto` 增加每表哈希（按需请求的版本凭据） |
| P1-9 | 启动只跑基准 Input | ✅ 缓存命中判定改为"该表**基准输入**的测量值是否存在"；每表每代只入队一个低优先级任务 |
| P1-10 | `match_tool` 移出 `SCENARIO_CONDITIONS`、工具改为真实求值（决策 8；布尔维度 8 类降 7 类） | ✅ `SimulationContextConditionMixin` 已删除并从 `unsuspiciousblock.mixins.json` 移除 |

验收方式：`./gradlew build` 通过（52 张表规模的完整构建）；改动文件经 IDEA MCP 检查后无报错、无警告。
实机验收（改 JSON 后 `/reload`、门槛数值、参数生效、双平台）**2026-09-21 起由用户自测中**（本机构建需带代理 JVM 参数，见 P2 状态口径）。

### P1 实机反馈修复（2026-09-20，用户首轮实机）

用户首轮实机截图报出三处问题，处置如下：

1. **参数根本没进抽取（真 bug，影响 P1-3 的全部语义）**：`LootProbabilitySimulationJob.prepareScenario`
   用**场景自带**的 profile 构造 `LootParams`，而条件作用域用的是合成后的 `effectiveProfile`——于是每次模拟
   实际都跑在"默认工具 + 幸运 1.0"上，玩家设的幸运/工具/附魔等级一律无效。实机证据：河流淘洗的青金石
   显示 60%，正是幸运 1.0 下池 2 的份额（3/5）。已改用 `effectiveProfile`，并**升 `SIMULATION_CACHE_VERSION`
   v17→v18** 让旧存档里按错口径算出的测量值整体失效（否则它们会被当作缓存命中继续展示）。
2. **tooltip 重复且读不通的"需要条件"行**：`PathHintAnalyzer` 无条件递归条件子节点，把 `tool_enchantment`
   的展示子行（"概率：基础 20%，每级变化 10%"）也当成一条独立门槛，于是同一条门槛列两遍、第二遍还写成
   "该路径需要工具带 概率：… 附魔"。已改为**只对组合条件（`all_of`/`any_of`/`inverted`）递归**。
   同时去掉附魔提示里硬编码的"（等级 ≥ 1）"——`min_level` 由数据包给出，写死会在 `min_level > 1` 的表上说谎。
3. **灰色文字难以辨读**：网格状态词 `PROB_COLOR_UNKNOWN` 由 `0xFF6B6B6B`（浅纸面上约 3.9:1）改为深暖灰
   `0xFF4A4038`（约 7:1）；`TooltipBuilder.HINT` 由 `DARK_GRAY` 改为 `GRAY`（前者在深色 tooltip 背景上只有
   约 1.9:1），条件树的树枝前缀与子表 tooltip 的表 id 行一并跟随。配色约束写入 `docs/dev/client-ui.md` 4.2.1。
4. **随口的一句话文案**：tooltip 的"随幸运变化（模拟幸运值：1.0）"里的固定数字已删除——幸运自 P1 起是
   输入的一维（基准值 0），印一个固定数字与玩家看到的输入不符。

**用户对子表显示语义的裁定**：泥地打捞这类"内容被可调门槛挡住"的表，**保持显示「需要条件」**，
不做"自动满足门槛"的假设（决策 2 不变）；要求 tooltip 把需要的条件与**该条目自己的**幸运门槛写清——
门槛本已按路径/条目保留，本次修好参数生效后数值门槛才会真正出现在 tooltip 里。

### 第二轮实机反馈：缓存永不命中（2026-09-20，用户报告）

用户报告"每次进入服务端都会重新跑全量模拟"。由 `neoforge/run/saves/test/data/unsuspiciousblock_loot_probability.dat`
与 `neoforge/run/logs/` 定位到两个独立问题：

1. **陈旧写入守卫造成死循环（已修，直接原因）**：`commitSimulated` 原本只要该 `(表, 输入)` 已有测量值就跳过写入，
   **不判断那条测量值属于哪个内容哈希**。于是版本升级后：读路径判"哈希不同 → 重算"，写路径却"已有测量值 → 跳过写入"，
   存档里的哈希永远停在旧值，每次启动都全量重算。现改为仅在 `!needsResimulation(...)` 且该输入的测量值存在时才跳过。
   证据：存档里河流淘洗的数值仍是**幸运 1.0 的口径**（`lapis_lazuli 0.5951` / `amethyst_shard 0.4049` 正是池 2 在幸运 1 下的
   3:2 分配，`iron_nugget 0.4279` 亦同），而键上写的是 `luck=0.00` —— 说明修好参数生效后算出的新数值从未落盘，
   **参数修复本身是对的**。
2. **条件指纹跨运行不稳定（已修，T1）**：`minecraft:gameplay/fishing`、`.../fishing/junk`、
   `unsuspiciousblock:gameplay/fishing/mud_dredging` 三张表各存了 **4 个输入键**，每次运行新增一条 —— 场景键里嵌了条件指纹，
   而 `entity_properties`/`location_check` 的指纹跨 JVM 运行不重复（`tool_enchantment` 的指纹是稳定的）。
   由于 LRU 按**参数组合**计数，这些陈旧键既不会被淘汰也不会被覆盖，会无限累积。处置见下条。

配套改动：启动日志的未命中原因分账（`哈希变化 A 个、缺少该输入的测量值 B 个`）；新增存档诊断脚本
`scripts/read_loot_probability_save.py`。

### T1：稳定场景身份（2026-09-20，已实施，未提交）

把"场景键"从"条件指纹的编码"改为**稳定身份**：基准恒为 `baseline`，其余按发射顺序编号 `scene-N`。
条件指纹仍留在 `SimulationProfile.conditionOutcomes` 里作为运行时语义（模拟时按运行时指纹回答条件成立与否），
只是不再进键。落地清单：

| 改动 | 位置 | 要点 |
|---|---|---|
| 输入记录增加场景维 | `SimulationInput` | `(scenarioKey, conditionOutcomes, params)`；`scenarioKey` 非空校验；条件赋值不进键 |
| 键只取场景键 | `SimulationInputKey` | `scenario=baseline\|luck=…`；删除 `canonicalOutcomes`（它曾是持久化键的来源） |
| 规划器给稳定序号 | `SimulationScenarioPlanner` | `BASELINE_SCENARIO_KEY = "baseline"`、`scene-N`；泥地打捞注入场景接在已发射场景之后 |
| 去掉不稳定的并列破平局 | 同上 | 覆盖度排序**只按覆盖路径数降序**，并列保持插入顺序（路径枚举顺序）。原来按指纹字符串破并列，而指纹跨运行不稳，并列候选每次启动互换序号——原版 `minecraft:gameplay/fishing` 的群系条目恰好每条覆盖一条路径，是典型的全并列 |
| 按场景键查缓存 | `SimulationConstraintCatalog.baselineInput/resolve`、`ArchaeologyJournalServerCatalog.measurementsByScenario` | 直接用 `scenario.key()`，不再从 `conditionOutcomes` 反算 |
| 版本作废孤儿条目 | `SIMULATION_CACHE_VERSION` v18→**v19** | 存档里那批指纹键整体作废，避免它们作为同一参数组合下的孤儿条目永久留着 |

诊断脚本的新判据：`存有多个输入键的表 = 0`，且每个键形如 `scenario=baseline|luck=0.00|…`。

### T4：把泥地打捞的入口门槛从子表移到注入处（2026-09-20，用户要求，已实施）

**用户要求**：泥地打捞是钓鱼表的子表。它在**钓鱼表里**显示「未命中」可以理解，但在**它自己的页面**上
不应该被"从父表带来的条件"挡住——应该显示子表自己的物品概率。

**核实到的机制事实**：那些「需要条件」并非来自父表，而是 `mud_dredging.json` 自己池上的
`tool_enchantment`（`chance` = `linear(0.2, 0.1)`）。父表那边的注入池另有一条**同名门槛**
（Fabric `FishingLootInjection` / NeoForge GLM），但它**不带** `chance`——从 tooltip 上能否出现
「基础 20%，每级变化 10%」这一子行即可区分两者（截图里出现的是子表自己那条）。
因此"只把父表条件排除"不会有任何显示变化，必须做数据层的移动。

**做法**：门槛与概率一起搬到注入处，被注入子表不再写条件。

| 位置 | 改动 |
|---|---|
| `mud_dredging.json` | 删除池上的 `tool_enchantment` 条件（只保留"进来之后产出什么"） |
| `FishingLootInjection`（Fabric） | 注入池条件补 `chance = LevelBasedValue.Linear(0.2F, 0.1F)` |
| `mud_dredging_fishing.json`（NeoForge GLM） | 条件补同样的 `chance` |
| `RuntimeLootLinks` | 新增 `injectionGateEnchantments(tableId)`：声明注入边条件引用的附魔，**记在发起注入的表上**（身份只有 `ModEnchantments.MUD_DREDDING` 一个字面量）。记父表侧而非子表侧，是因为变的是父表能产出什么，给子表挂旋钮只会多个点不动的控件 |
| `ArchaeologyJournalServerCatalog` | 每表哈希与附魔等级旋钮清单改吃 `summaryEnchantments = JSON 引用 ∪ 注入边门槛附魔` |

两个页面的语义因此分开且各自明确：**父表页答"能不能进本表"**（基准输入下如实显示未命中），
**子表页答"进了本表之后各物品的份额"**（五个直接物品显示各自占比，不再被入口门槛判成「需要条件」）。
子表里剩下的 `entity_properties`(开放水域) 与 `location_check`(沼泽) 是**条目级**门槛，照旧显示「需要条件」。

**必须一起做的连带修复**：门槛搬走之后两端 JSON 里都不再出现 `enchantment` 字段，而该附魔的 `max_level`
仍决定注入场景要带几级附魔（且它是父表的附魔等级旋钮）。不并入 `summaryEnchantments`（记在发起注入的表上），
"改 `max_level` 会失效"（决策 35）与"附魔等级旋钮可见"（决策 26）会同时断掉。

**代价**：门槛的等级/概率参数在两端的注入处各写一份（Fabric Java 条件 / NeoForge GLM 数据），
改一处必须改另一处；掷概率的位置从子表池内移到父表入口，随机数消耗点改变，**掉落分布不变**。
**本项把决策 42 的方向提前了一半**：注入边及其门槛附魔现在有了 common 侧声明，
剩下的"由注入边通用派生父表约束描述"仍按 P2-6 做。

### T5：父表页的子表入口改为「需要条件」+ 门槛单一声明（2026-09-20，用户要求，已实施）

**用户要求**：泥地打捞在父表（钓鱼）里不应显示「未命中」，应显示「需要条件」，tooltip 要给出条件，
并且后续调条件场景要能影响这一项。

**根因（不是"父表漏了一条判定"，而是三处叠加）**：

1. 父表页子表入口的**状态派生**与物品不同构：物品的展示值走 `PathHintAnalyzer.deriveDisplay`
   （零命中 + 引用了旋钮 → 「需要条件」），而子表入口直接把测量值透传 → 零命中只会显示「未命中」；
2. 子表入口的**条件树**由客户端本地推导（`JournalViewModel.childTableConditions`），
   并带一条 P0 遗留的专门分支："父表没有静态路径时回填子表自己的直接路径条件"。
   T4 把子表条件删掉后，这条回填随之失效 → 入口既没有状态词也没有条件可讲；
3. 注入边**不写在任何 JSON 里**，所以"从物品路径本地重推"这条路对注入入口必然漏项——
   这正是"解析只有一份实现"这条边界要防的情形。

**做法**：门槛单一声明 + 服务端派生 + 客户端只渲染。

| 位置 | 改动 |
|---|---|
| `RuntimeLootLinks` | 新增 `InjectionEdge`/`InjectionGate`（附魔 + 最低等级 + 概率曲线）与 `MUD_DREDGING_GATE`；`INJECTION_EDGES` 成为**唯一声明**，`syntheticEdges()` 由它派生（避免"边"与"门槛"各写一份）、`injectionGate(target)`、`injectionGateEnchantments(source)` |
| `FishingLootInjection`（Fabric） | pool 条件改为 `MUD_DREDGING_GATE.condition(registries)` |
| `FishingLootModifier`（NeoForge） | 门槛在 `doApply` 里按同一份声明判定；GLM 数据只留 `loot_table_id`（判定时机与原先作为 GLM 条件一致） |
| `SimulationConstraintCatalog` | 新增 `childEntryGates`（子表 id → 门槛条件树）与 `describeGate`（门槛 → 同一个条件对象 → 同一份分析处理器，因此 tooltip 的等级/概率不会与玩法漂移） |
| `PathHintAnalyzer` | 新增 `hintsForConditions`（无路径条件的提示）与 `deriveEntryDisplay`（子表入口的零命中 → 需要条件） |
| `ChildTableProbability` / `CatalogTableDto.ChildTableEntry` / `CatalogStreamCodec` | 入口条件树随目录下发；协议版本 4.5/4.4 → **4.6/4.5** |
| `CatalogGeneration.updateTableDigest` | 入口条件进目录哈希（它直接改变 tooltip 内容） |
| `JournalTooltipBuilder.buildChildTable` | 状态词**优先于**区间（区间含 `0%` 会与「需要条件」互相打脸） |
| `JournalViewModel` | 改用服务端下发的条件，删除本地推导与 P0 遗留的 mud_dredging 专门分支 |

并集/交集的分工是刻意的：**提示用并集**（回答"引用了哪些可调的旋钮"，有一条路径引用过就该列出来），
**条件树用交集**（回答"要拿到它必须满足什么"，并集会把"只有部分路径需要"说成"需要"）。
分场景列表仍保留原始测量值，入口那一行回答的是"当前输入下能不能进"——与物品的两层结构一致。

### P2 后端补丁：按需复用缓存与失败回执（2026-09-21，已实施）

后端链路通读时发现两个缺口，都在服务端，都会直接削弱"场景缓存存服务端"这件事的价值。

**缺口 1：按需请求从不查服务端缓存。** `ArchaeologyJournalServerCatalog.requestSimulation` 在
`resolve` 通过后直接入队，全文件没有 `getMeasurement` 查询（只有启动恢复路径读缓存）。于是同一个
`(表, 场景, 参数)` 无论被谁算过，下一个人再选它就**完整重跑** 1 万～10 万次抽取，而 `commitSimulated`
只让它"跳过写入"——CPU 白烧，日志照样打"已完成…模拟"。缓存实际上只加速了启动。

**缺口 2：模拟失败没有任何回执。** `LootProbabilitySimulationWorker.complete()` 只在
`result.successful()` 时回调 `resultHandler`，`commitSimulated` 对失败结果也直接 return。玩家请求的
输入若在 `createJob`（表为空）或抽取过程抛异常时失败，客户端既收不到结果也收不到拒绝，只能停在
`pending`，**120 秒后**显示"等待超时，可点击「计算」重试"，而重试依然不会成功。

| 改动 | 位置 | 要点 |
|---|---|---|
| 缓存命中先于排队 | `requestSimulation` | `!needsResimulation(tableId, hash) && getMeasurement(tableId, input.key()) != null` → `sendScenarioResult` 直接下发，返回 `CACHE_HIT`。**必须先过 `needsResimulation`**：`getMeasurement` 只看输入键不看哈希，内容变过而条目尚未重写时那条测量值属于上一版内容 |
| 在途额度改在真正入队时判定 | 同上 + `ScenarioSimulationHandler` | 删掉网络层入口的 `canAcceptFor` 预检，改由目录在需要入队时返回 `PLAYER_LIMIT`。理由：缓存命中不消耗 tick 预算，若在入口按额度拒掉，玩家会在答案就在眼前时收到"请求过多" |
| 失败也走结果回调 | `LootProbabilitySimulationWorker.complete` | 把 `resultHandler` 回调提到 `successful` 判断之前（结果本身带 `successful` 标志），在途额度的释放保持原位置不变 |
| 失败回执 | `commitSimulated` 失败分支 → 新增 `notifySimulationFailed` | 走与拒绝同一条通道（不携带概率），原因 `SIMULATION_FAILED`；启动批次 `requester == null` 只留日志 |
| 同步回退路径同样处理 | `requestSimulation` 的 `worker == null` 分支 | 原来无论成败都调 `sendScenarioResult`，会把一份"什么都没测到"的结果当成结果下发；现在失败返回 `SIMULATION_FAILED` 由网络层回执 |
| 协议 | `ScenarioRequestRejectedPayload.Reason` | 追加 `SIMULATION_FAILED`（**追加在末尾**：编码用序数，插进中间会改写已有取值的含义）。客户端文案 `failure.simulation_failed` 早已存在，无需新增 key |

预期可观测行为：切换到一个已算过的场景时不再出现"已完成…输入模拟"的日志行（改为 debug 的
`复用缓存的测量值`），而界面立即显示数字；模拟失败时不再等超时，界面直接显示
"模拟失败，请在数据包重载后重试"，且自动重算被抑制（手动 `[计算]` 仍可重试）。

## T2 实机验收清单（交用户执行，2026-09-20；**2026-09-21 起用户自测中，同时覆盖 P2 与后端补丁**）

代码侧已完成编译与静态检查；下面每一步都要看**日志**或**存档**给出判读，不靠"看起来对"。
`git` 未提交，改动都在工作区。

| # | 操作 | 判定依据 | 期望 |
|---|---|---|---|
| 1 | NeoForge 启动，进一次世界后正常退出；再启动第二次 | `latest.log` 的 `概率缓存命中 … 未命中原因：…` | 第一次应为 `0 命中，58 待模拟（哈希变化 58）`（v19 让旧存档整体作废）；第二次应为 `命中 58 个表的基准输入，0 个待模拟（哈希变化 0、缺少该输入的测量值 0）` |
| 2 | `python scripts/read_loot_probability_save.py` | 脚本的 `存有多个输入键的表` | 应为 `0`；`--table` dump 出的键形如 `scenario=baseline|luck=0.00|tool=…|n=10000` |
| 3 | `淘洗（河流）` 页 | 网格与 tooltip | 青金石/紫水晶显示「需要条件」，tooltip 给「该路径需要：幸运 ≥ 0.34」/「≥ 0.5」；**不再**出现 60%/40%。存档里这两条在 `luck=0.00` 下应为 `measured 0.0`（即零命中，不是旧口径的 0.5951/0.4049） |
| 4 | `钓鱼（总表）` 页的 `泥地打捞` 入口 | 网格与 tooltip | **T5 起**：显示「需要条件」（不再是「未命中」），tooltip 列出入口条件（泥底打捞 + 「概率：基础 20%，每级变化 10%」子行） |
| 5 | `钓鱼（泥地打捞）` 子表页 | 网格与 tooltip | **T4 起**：五件直接物品显示各自占比（各约 6.7%，池总权重 15）而不是「需要条件」；`泥底打捞（普通群系）`/`（加成群系）` 因开放水域/沼泽是条目级门槛，仍显示「需要条件」 |
| 6 | 灰色文字 | 目视 | 网格状态词与 tooltip 副文本在浅纸面／深底上均可辨读 |
| 7 | 改一张被追踪表的 JSON 后 `/reload` | 日志 `哈希变化 N 个` | 该表重算、`N ≥ 1`，其余表不受影响 |
| 8 | 改附魔 `max_level` 后 `/reload` | 同上 | 引用它的表哈希变化并重算（钓鱼表因注入门槛而被计入） |
| 9 | Fabric 端重复 1–5、6 | 同上 | 行为一致（两端共用 common 的规划与缓存代码；注入门槛两端同源） |

判读要点：输入键里的 `luck=` 是**该次模拟的输入幸运**；若键写 `luck=0.00` 而数值呈现幸运 1 的口径
（例如河流淘洗的青金石 0.5951），说明"算的是新参数、存的是旧数值"，即写入路径没执行。

**T3（已记录，未处理）**：

1. `LootConditionFingerprint.isStableSource` 只检查**条件对象自身**的 `toString()` 形态（是否 record /
   是否退化成 `类名@identityHash`），看不到嵌套字段。已核实的链条（源码级）：
   - 解析期分析的是 `LootTableCompiler` 从 JSON 解出的另一份实例，与运行时 `ReloadableServerRegistries`
     的对象不同，因此只有**整条** `toString` 链按值生成才能复现指纹；
   - `LootItemEntityPropertyCondition` / `EntityPredicate` / `FishingHookPredicate` / `LocationPredicate`
     全是 record ⇒ 一律判"稳定"，嵌套的非 record 对象不参与判定；
   - `location_check` 带标签时**必然**不稳：`LocationPredicate.biomes` = `Optional<HolderSet<Biome>>`，
     `HolderSet.Named.toString` 逐个打印 `Holder`，而 `Holder.Reference.toString` 打印它持有的**值对象**
     （`"Reference{key=value}"`），`Biome` 无按值 `toString` ⇒ 落回 `类名@identityHash`。
   - `entity_properties`（`fishing_hook/in_open_water`）的链路上全是 record，**其不稳的具体环节尚未定位**
     ——下一轮若要收紧判定，需要按"条件树任意层级"检查稳定性（含值对象），并顺带确认这一条。
   - 附注：告警按 `条件 id + (简单类名)` 去重，"有 N 个"是**类型数**，不能读成条件条数。
   `docs/dev/loottable.md` 第 7.2 节已按上述事实改写（原文把该告警单独归因于泥地打捞注入场景，
   并断言"不是指纹失效"，与第 3 条冲突）。
2. 同一张表的子表条目出现「未命中」与「需要条件」不对称（截图中的 `钓鱼（总表）→ 钓鱼（泥地）`），
   待 T2 结果确认是否仍存在。
3. 原版 `minecraft:gameplay/fishing` 的注入条目在 P2 决策 42 落地前只能靠专门分支场景发现，
   基准下显示未覆盖/未命中属预期。
2. 同一张表的子表条目出现「未命中」与「需要条件」不对称（截图中的 `钓鱼（总表）→ 钓鱼（泥地）`），
   待 T2 结果确认是否仍存在。
3. 原版 `minecraft:gameplay/fishing` 的注入条目在 P2 决策 42 落地前只能靠专门分支场景发现，
   基准下显示未覆盖/未命中属预期。

## P2 交互与联合见证

| 点 | 交付 | 状态 |
|---|---|---|
| P2-1 | 参数区（工具/附魔等级/幸运/抽样次数档位）与幸运输入框 + 建议档位 | ✅ 代码完成（**临时验收实现**，`client/ui/panel/ScenarioPanel.java`）：工具/附魔/次数为点击轮换，幸运为 `EditBox` + 500ms 防抖；建议档位由各路径 `luckGate.minLuck()` 收集去重后取"下一个更大档"。**实机验收中** |
| P2-2 | `RecommendationSolver` 联合见证搜索与可点击「填入推荐值」 | ✅ 代码完成：双预算（2048 次 + 15ms）、三值逻辑（未知不作结论）、逐层 `luckRequirements` 回验；客户端**仅在 `found && recommendation` 时**渲染按钮（决策 34）。已知限制：搜索顺序为 路径→场景→工具→等级，预算内可能触不到靠后的场景，表现为"没有按钮"（诚实降级） |
| P2-3 | 场景 Tab + 网格页快捷切换下拉与三态缓存标记（决策 43） | ✅ 代码完成：新增 `RightPageContainer.Tab.SCENARIO` + 第 4 个书签；同一份下拉在网格页页头也渲染；标记为三态 + 失败标记（`cached`/`pending`/`uncomputed`，失败另给 7 种原因文案）。**实机验收中** |
| P2-4 | 防抖自动请求 + `[计算]` 按钮、页头状态 | ✅ 代码完成：输入键变化后 500ms 自动请求，`[计算]` 走 manual（失败后可重试）；页头常显"场景 · 幸运 · 次数 · 状态"。**留口**：`/reload` 换代后不自动重发（输入键未变且 `sent` 守卫仍为真），需手点【计算】或切场景，见"已知偏差"12 |
| P2-5 | 一键填充当前状态与 `PlayerStateProbe`（决策 41） | ✅ 代码完成：探针读主手工具 / 附魔等级 / 幸运与可读条件（`location_check`/`weather_check`/`time_check`/`entity_properties`/`entity_scores`），读不到的逐项出清单；答复 `found` 且非推荐请求时客户端自动应用 |
| P2-6 | 注入边参与父表约束描述（决策 42） | ✅ 代码完成：`constraintTable()` 把注入子树并进父表**约束规划**，`injectionGateEnchantments` 让父表拿到门槛附魔旋钮；泥地打捞专用场景与 `keepBaseTool` 已删除，父表注入物改由参数（附魔等级）驱动发现，注入物自身仍由抽样动态发现 |
| P2-7 | 客户端偏好的文件实现（决策 29/37） | ✅ 代码完成：`SimulationPreferenceStore` + `IClientSimulationPreference` SPI + 两端实现，properties 原子写；恢复时按当前目录重新校验，不盲信旧 `inputKey` |

**P2 状态口径（2026-09-21 记录；2026-09-22 状态同步）**：上表七项与前面的「P2 后端补丁」实施当时只在**工作区，未提交 git**；后续已随 `1.21.1` 分支的提交进入版本库。下表的 UI 描述记录的是**当时的临时验收实现**，现已由前端重构（S1–S6）取代。

- **编译与静态验证已过**：IDEA MCP 检查全部改动文件 0 error / 0 warning；`./gradlew build` `BUILD SUCCESSFUL`，三模块 jar 均重新产出，`:common:test` 6 个用例 `failures=0 errors=0`。
- **实机验收：用户自测中**。判据沿用 T2 清单，另加两条本批的可观察行为——(a) 同一个"场景+参数"第二次选择时日志不再新增"已完成…输入模拟"且界面立即出数；(b) 模拟失败时界面立即报"模拟失败"而不是等 120 秒超时。
- **构建环境注意**：本机 Gradle 的 JVM 不走 `http_proxy`，Loom 配置阶段直连 `piston-meta.mojang.com` 会报 `Failed download after 3 attempts`；构建需带
  `-Dorg.gradle.jvmargs="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890"`。
- **前端说明（当时的欠账，已结清）**：P2 的 UI 是**临时验收实现**，已由前端重构（S1–S6）统一重构；`docs/dev/` 的 `client-ui.md` 与网络文档已同步，`config-integrations.md` 仍待随 P3-4 处理。
- **下一步**：P3 实机验收与剩余文档同步（`config-integrations.md`）。原列的「前端重构方案探讨」已完成。

## P3 保真与兼容

| 点 | 交付 | 状态 |
|---|---|---|
| P3-1 | 原版 `minecraft:gameplay/fishing` 群系条目全覆盖验收 | ⬜ |
| P3-2 | 整合包注入与动态条目的 Input 归属 | ⬜ |
| P3-3 | 双平台实机验收 | ⬜ |
| P3-4 | `docs/dev/` 文档同步（loottable / client-ui / network / mixin / config-integrations） | ✅ 2026-09-20 随 P0 与索引修复同步一次；**P1 再次同步**：loottable（新增 7.5/7.6/7.7/7.8 与包结构、缓存格式、worker 语义）、network（payload 清单、协议版本、新增 6.1）、mixin（`SimulationContextConditionMixin` 删除）。`client-ui.md` 与 `config-integrations.md` 待 P2 涉及 UI/配置时再同步 |

## 与规划的已知偏差（实施期决定，需回写规划）

1. **P0 提前移除泥地打捞专门分支**（规划把 `RuntimeLootLinks` 注入边的通用化放在 P2，决策 42）。理由：P0 的完成标准是"网格上不再有误导数字"，而专门分支给基准场景配的是满级附魔钓竿，会让该表五件直接物品在基准下显示"能拿到"。移除后它们按静态提示显示「需要条件：工具带泥地打捞附魔（等级 ≥ 1）」，与规划 §7.2 的验收标准一致。代价：原版 `minecraft:gameplay/fishing` 里的注入条目在 P2 完成前只显示 `?`（未覆盖），因为父表的约束描述尚未并入被注入子表的条件树。
2. **P0 的「需要条件」判定是近似**：基准场景下测量值为零且路径引用旋钮时显示 `NeedsCondition` 而非「未命中」。**P1 已取代该近似**：`PathHintAnalyzer` 现在先判"全部路径是否被逐路径幸运门槛证明不可达"（是则 `Unreachable` → `0%`），再按测量值/提示派生，规则与规划 §4.3 的两轴判定一致。
3. **`PathHint.ReferencesParameter.detail` 用 `Component` 而非规划草案的 `String`**：需要本地化文案（附魔名、工具谓词原文、幸运门槛数值），`String` 会把服务端语言固化进目录。
4. **决策 8 未随 P0 落地**：P0 期间 `match_tool` 仍在 `SCENARIO_CONDITIONS` 内，只有泥地打捞的满级工具专门分支被移除。**P1-10 已完成**，该偏差关闭。
5. **概率存档格式实际落到 `format_version = 4`（规划写"格式 3"）**：规划 §4.5 的编号早于 P0——P0 已把"窄类型 `SimulatedValue`"这一改动用掉了 3。P1 的结构改造（表级 `discovery` + per-Input `inputs` + 参数组合 LRU）因此是 4。旧格式读到即按缓存未命中处理，不做迁移。
6. **抽样次数档位与参数组合上限落为可调常量**：`ScenarioParams.SAMPLE_COUNT_TIERS`（1 万 / 5 万 / 10 万，同时是白名单与硬上限）、`MAX_PARAMETER_COMBINATIONS = 8`。规划把它们定为"实机观察后再定"的可调实现参数，P1 先取建议值。**待确认**：LRU 的"参数组合"含场景段，因此 8 个组合对 32 场景的表偏浅（逛一圈会互相淘汰，包含基准输入）；实机观察后再决定是否按"每表 N 场景 × M 参数组"分开计数。
7. **模式外的工具不被参数覆盖**：泥地打捞注入场景把"满级钓竿"写进场景定义，P1 用 `SimulationScenario.keepBaseTool` 让这类场景只接受输入的幸运、保留自带工具。规划没有这一维度——它是"注入场景本就不是玩家处境"的直接后果：若被默认工具覆盖，注入池永远抽空，注入条目再也发现不了（信息丢失，不是参数生效）。**P2 关闭该偏差**：决策 42 落地后门槛移到注入处、注入物改由参数（附魔等级）驱动发现，`keepBaseTool` 与泥地打捞专用场景一并删除——"所有场景都用玩家填的参数"成为唯一口径。
8. **`CatalogTableDto` 新增每表哈希**：规划没有这一字段，但按需请求必须携带一个"我按的是这一版内容"的凭据。用整目录哈希不行——它会被任何一张表的模拟完成改变，导致并发计算时的正常请求被频繁误判为过期。
9. **约束描述暂不下发给客户端**：`SimulationConstraintCatalog` 目前只活在服务端（构建期派生、随代次缓存），P1-4 的交付是"目录只发布约束"这一结构本身。参数区所需的约束描述下发属 P2（规划 §4.5 的投影行）。**P2 已交付**：`SimulationOptions` 随每表 DTO 下发（场景假设 + 工具基座 + 附魔等级上限 + 次数档位 + 截断/降级标记），客户端据此渲染参数区并在本地预校验，服务端 `resolve` 仍是唯一权威。
10. **客户端目前只做结果入库，不做界面切换**：`ScenarioSimulationClientState` 校验并缓存结果，`receiveRejection` 只写日志。规划把"结果与当前选择的匹配、界面切换"放在 P2；P1 刻意不先建一份无人读取的状态。**P2 已交付**：状态改为"每表当前选择 + 按输入隔离的缓存/在途/失败 + 推荐状态"，`receiveRejection` 的原因现在会显示在页头（`failure.<reason>`）并参与自动重算的抑制。
11. **`SimulationInput` 增加"场景身份"这一维（T1）**：规划 §4.2 的草案是
    `record SimulationInput(Map<String, Boolean> conditionOutcomes, ScenarioParams params)`，键由条件赋值的编码而来。
    实测该编码**跨 JVM 运行不重复**（原因见 T3 第 1 条），作为缓存键会让同一个场景每次启动换一个键，因此改为
    `record SimulationInput(String scenarioKey, Map<String, Boolean> conditionOutcomes, ScenarioParams params)`，
    键取稳定的 `scenarioKey`（`baseline` / `scene-N`）。规划未预见这一维——它把"场景"等同于"条件赋值"，
    而那个赋值里含不可复现的指纹。条件赋值仍是场景的**运行时语义**，只是不再是身份。
12. **`/reload` 后客户端不自动重算当前选择（P2 留口，未修）**：服务端换代后客户端会因代次变化清掉本地
    结果与在途状态，但 `ScenarioPanel.tick()` 只在**输入键变化**时发请求，而换代不改变输入键、`sent`
    守卫仍为真，于是界面停在"未计算"，要玩家手点【计算】或切场景才会重算。后端侧无法修（客户端不知道
    "我这份选择在新代里还没算过"），修点在客户端：`ScenarioSimulationClientState.catalog()` 检测到换代时
    置一个"需要重发"标记，由面板消费。留待前端重构一起做。
13. **P2 的后端链路经审查后补了两处缺口（2026-09-21）**：按需请求此前**不查服务端缓存**（缓存只加速
    启动）、模拟失败**无回执**（玩家只能等 120 秒超时）。两处已修，详见「P2 后端补丁」一节；同时把每玩家
    在途额度从网络层入口移到目录真正入队处（否则缓存命中的请求会被"请求过多"误拒），并修掉
    `enqueuePlayerRequest` 重复登记导致的在途计数泄漏（同一位玩家重复请求同一输入会 +2 只 -1，
    永久撞在额度上）。
