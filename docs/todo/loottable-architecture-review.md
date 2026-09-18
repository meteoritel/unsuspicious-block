# 战利品表解析追踪架构审查记录

本文是一次针对 `loottable/` 包（解析 / 收录 / 签名 / 概率模拟 / 注入）及其上层编排（`journal/catalog`、`world/LootProbabilityData`、`network`、运行时追踪入口）的架构审查结论，作为后续排期依据。

审查基线：分支 `1.21.1`，提交 `a6b8c24`。审查方式为通读代码 + 与 [战利品表系统](../dev/loottable.md) 文档逐条比对 + 查询 1.21.1 原版源码求证关键假设；**未编译、未进游戏实测**，涉及数值的结论均为静态推演。

> P2 三项与 P3-1 / P3-4 已按四层模型（快照 → 引用图 → 编译 → 投影）实施完成，三个步骤均已实机验收；实施规划完成后已按约定移入本地归档目录 `docs/archive/`（该目录不入库），因此本文不再链接它。当前机制的权威描述见 [战利品表系统](../dev/loottable.md)。
>
> 仍未处理：P3-2（运行时签名匹配缺预览栈缓存）、P3-3（失败任务不重试）、P3-5（两套可信度口径）。

## 1. 总体评价

架构方向正确、模块边界清晰，属于同类模组中偏上的水平。

**站得住的设计**

- **双模型分工明确**：解析期静态模型（`analysis/`，产出可展示的条件树与物品清单）与执行期真实模型（`simulation/`，真正跑 `LootTable`）职责不重叠，且静态模型在无法求值时统一降级为 `APPROX_ITEM_ONLY`，不假装精确。
- **签名机制是整套系统的枢纽**：`LootResultSignature` 同时服务"模拟期匹配掉落"与"运行时解锁玩家掉落"，全程只有一套词汇表，避免了两套匹配规则漂移。稳定 key + 旧格式兼容 + 旧附魔类型折叠为近似（`LootResultSignature.java:93-104`）考虑周全。
- **主线程分片模拟的判断正确**：`LegacyRandomSource` 非线程安全这个前提成立，因此放弃后台线程、改为 `END_SERVER_TICK` 驱动可续跑任务（`LootProbabilitySimulationWorker.java:155-220`），并配套双优先级队列、tick 边界抢占且保留进度、reload 期间暂停。
- **子表概率用直接观测而非签名反推**：`NestedLootTableMixin` + `LootSimulationScope.recordChildTableDrop` 统计"本轮该子表是否产出过"，绕开了父子表产物重叠导致的误判。
- **缓存失效链路完整**：JSON 内容哈希（含引用闭包与 tag 展开结果）+ `SIMULATION_CACHE_VERSION` + 失败结果不写缓存 + 启动非阻塞渐进填充 + 客户端哈希比对按需拉取。

**主要结构债务**

- **概率用格式化字符串贯穿全链路**：`"?"` / `"0"` / `"<0.01%"` / `"12%"` / `"3%-7%"` 五种语义共用一个 String 字段，穿过 catalog 记录、SavedData NBT、网络包与 UI，再用 `parsePercentToFraction` 反解排序。P1-1 之所以能发生且无声，根因就在这里：`"0"` 同时表示"证明了不可达"和"没覆盖到"。
- **同一张表的引用关系被 4 套独立实现各遍历一遍**，且 `loot_table` 类型判定写法不一致（详见 P2-1）。
- **解析期与模拟期共用同一个记录类型**，用 `probability="?"` + `simulationCount=0` 隐式表达"未模拟"，非法状态可表示（P2-3）。
- **硬编码的平台注入关系散落三处**（fishing ↔ mud_dredging 分别写在 catalog 加载器、场景规划器与条件类中，另有按 path 猜上下文），见 P3-4。

## 2. 待办清单

> 下表状态为最新；其后的**问题明细小节（P1-1 / P1-2 / P2-1 / P2-2 / P2-3 / P3）记录的是审查基线当时的状态**，保留作为问题溯源，不代表当前实现。当前机制的权威描述一律以 [战利品表系统](../dev/loottable.md) 为准。


| 编号 | 严重度 | 问题 | 状态 |
|---|---|---|---|
| P1-1 | 高 | 代表场景被 `MAX_SCENARIOS` 截断时，条目在所有场景中都不适用，概率被写成 `"0"`（不可达）而非 `"?"`（未知） | 本次已修复 |
| P1-2 | 高 | 解析期与运行时的条件指纹依赖 `Object.toString()` 挂钩，无守卫，破坏后静默产出错误数值 | 本次已修复 |
| P2-1 | 中 | 引用图遍历重复实现 4 套，循环处理 3 套，类型判定写法不一致 | **已修复**（步骤①）：引用关系收敛为 `LootTableReferenceGraph`，四套遍历与三套环处理删除 |
| P2-2 | 中 | 概率为字符串（P1-1 的根因），无法数值聚合 | **已修复**（步骤③）：sealed `Probability` 值类型 + 存档 `format_version`，反解排序删除 |
| P2-3 | 中 | 解析态与模拟态共用记录类型，非法状态可表示 | **已修复**（步骤②）：上下文无关编译产物 + 静态投影 + 网络 DTO 分型，`collectSubtreeItems` 与 5 个便捷构造器删除 |
| P3-1 | 低 | 网络包按物品重复序列化同一场景的假设条件树 | **已修复**（步骤②）：`CatalogTableDto` 场景假设每表只发一次，物品与子表按 `scenarioKey` 引用 |
| P3-2 | 低 | 运行时签名匹配缺预览栈缓存（模拟热路径有） | 待办 |
| P3-3 | 低 | 模拟失败任务不会重试，日志措辞误导 | 待办 |
| P3-4 | 低 | fishing / mud_dredging 硬编码关系散落三处 | **已修复**（步骤①）：收敛到 `RuntimeLootLinks`（含条件类型 id），钓鱼上下文改读声明的 `type` |
| P3-5 | 低 | 两套"可信度"口径（tooltip 颜色 vs `"?"`）规则不同 | 待办 |

### P1-1 场景截断被显示为"不可达"

**现象**：`SimulationScenarioPlanner.plan` 最多产出 8 个代表场景（fishing 表 6 个），超出的候选直接 `break`（`SimulationScenarioPlanner.java:29,76-81`）。被截断的条件组合不再有任何场景覆盖，于是该条目在**每一个**已发出场景里都不在 `applicableSignatures` 中；`scenarioProbabilities` 对不适用的场景一律写 `"0"`（`LootProbabilitySimulationJob.java:292-295`），`summarize` 见全等即返回 `"0"`（同文件 `:329-338`），最终 `ProbabilityFormat.normalizePercent` 渲染为 `0%`。

**影响**：把"没算到"显示成"不可能获得"，且因 `hasConditions()` 分支未走到，连 `"?"` 兜底都失效。触发条件为单表出现超过 8 种不同条件组合——父表 item 列表会被解析器内联进整棵子树（`LootTableJsonParser.java:304-344` 复用同一 `items` map），因此"一张根表引用多个带条件子表"即可撞上。

**判定依据**：对有条件的路径，方案集来自各路径的 `requirementsFor`，且每个非空 requirement 都会成为候选并被规范化后发出（除非被截断）；故"在所有已发出场景中都不适用"等价于"该 requirement 被截断"，属于"未知"而非"不可达"。

**修复**：在 `LootProbabilitySimulationJob` 中，对"在所有场景中均不可用"的条目与子表，整体报告为未知（空场景列表 → `summarize` 得 `"?"`），不再逐个写 `"0"`。

### P1-2 条件指纹依赖 `toString()` 且无守卫

**链路**：解析期把 `hash(className + "|" + condition)` 写入 `LootConditionInfo.metadata`（`LootConditionHandlers.java:194-205`、`LootConditionFingerprint.java:54-56`）；运行时 Mixin 对**另一个对象实例**算同样哈希去查场景覆盖（`SimulationProfile.java:56` → `LootSimulationScope.overrideResult`）。

**现状是能工作的**：`SCENARIO_CONDITIONS` 白名单中的 8 个原版类型在 1.21.1 下全部是 record（`toString()` 由字段值生成），`Holder.Reference.toString()` 打印 `key=value` 且 BuiltInRegistries 实例在全进程生命周期内恒定，故解析期与运行时哈希一致。

**风险**：该不变量没有任何注释、断言或测试保护。一旦有人往白名单加入非 record 的条件类型（自研或第三方模组条件），或字段中嵌入恒等 `toString()` 的对象，指纹即静默不匹配 → 场景覆盖整体失效、退化为用假玩家的钻石镐 / AIR 方块真实求值，数值悄悄变错且不报错。

**修复**：解析期识别"身份哈希式 `toString()`"并在元数据中标记；场景规划遇到不稳定指纹时不再据此建场景（该条件按"无约束"处理，走未知而非零），同时按类型告警一次；运行时对"场景相关类型但未被场景覆盖"的条件做汇总告警，使其他成因（如跨 reload 的 Holder 身份变化）也不再静默。

### P2-1 引用图遍历重复实现

| 位置 | 用途 | `loot_table` 类型判定 |
|---|---|---|
| `ArchaeologyJournalCatalog.java:93-186` | 收录闭包 + 循环检测（原始 JSON 扫描） | 字面量 `"loot_table"` / `"minecraft:loot_table"` |
| `LootTableJsonParser.java:304-344` | 解析期展开子表（**再次读盘**，自带 `expandingStack`） | `LootParseUtil.normalizeType` |
| `ArchaeologyJournalServerCatalog.java:573-628` | 缓存哈希输入（**第三次读盘**） | 又一个 `isLootTableEntry` 字面量判断 |
| `LootTableCatalog.collectSubtreeItems` / `collectCachedSubtreeSignatures` / `SimulationScenarioPlanner.applicableChildTables` | 目录 / 缓存 / 场景各自递归子树 | — |

同一棵子树启动期至少读盘 2~3 次；循环处理有三套机制。建议收敛为单一"引用图服务"（建图 + 闭包 + 环 + 子树遍历 + 哈希输入），解析器只消费其结果。

### P2-2 概率值类型化

引入小型值类型（state: `UNKNOWN` / `UNREACHABLE` / `MEASURED` + value + 可选上界），只在 UI 边界格式化，NBT 与网络各写一个 codec。这是 P1-1 的根因治理，也是 P3-1 网络瘦身的前置条件。

### P2-3 解析态 / 模拟态分型

`ItemDefinition` 现有 6 个便捷构造器（`LootTableCatalog.java:146-191`），`TableDefinition` 3 个；解析期产出 `probability="?"` + `simulationCount=0`，模拟期产出真实值与 `10000`。建议拆 `ParsedTable` / `SimulatedTable` 两态，或至少把这两个字段合并为显式状态字段。

### P3 明细

- **P3-1**：`encodeScenarioProbabilities` 对每个 item 重新序列化该场景的 `assumptions` 条件树（`SyncArchaeologyCatalogPayload.java:144-152`），而同一场景所有物品填的是同一个 `scenario.assumptions()`。建议每表只发一次场景假设，物品侧按 `scenarioKey` 引用。
- **P3-2**：模拟热路径有 `previewCache`（`LootProbabilitySimulationJob.java:49-52`），但玩家侧 `resolveSignature` / `resolveCandidateSignatures` 走 2 参重载，每个 `COMPONENT_EXACT` 候选每次都要 base64 + JSON 解码（`ArchaeologyLootRuntimeTracker.java:204-241`）。
- **P3-3**：`complete()` 里 `enqueued.remove(tableId)` 后无重新入队，失败表要等下次 `ensureLoaded` / `/reload`，但日志写"保留待重试状态"（`LootProbabilitySimulationWorker.java:236-243`）。
- **P3-4**：`minecraft:gameplay/fishing ↔ unsuspiciousblock:gameplay/fishing/mud_dredging` 这对关系在 Java 侧实际有 **5 处**独立来源：`ArchaeologyJournalCatalog:33,35`、`SimulationScenarioPlanner:34,39`、`MudDredgingCondition:30`（ResourceKey）、客户端 `JournalViewModel:52,54`、`FishingHookMixin:37` 字面量（另加配置默认值 `ILootTableConfig:29,33` 里的前缀字符串，性质不同不计入）。此外 `SimulationProfile:42` 用 `path.contains("fishing")` 猜是否钓鱼上下文（决定默认工具与 `THIS_ENTITY` 类型），形如 `mymod:misc/fishing_rod_test` 的表会被误判——已核实原版 loot table 自带 `"type": "minecraft:fishing"` 声明，应改为按声明类型判定（见实施计划步骤① 的 D9）。
- **P3-5**：tooltip 颜色走客户端 `computeUncertaintyLevel`（条件树 + handler 分级），`"?"` 走服务端 `ItemDefinition.hasConditions()`（路径条件 + `APPROX_ITEM_ONLY`），规则与计算位置都不同，可能出现"颜色显示运行时不确定，但概率从不显示 `?`"的错配。

## 3. 已核验事项

- 通读 `loottable/` 全部 33 个类、`journal/catalog` 两个加载器、`LootProbabilityData`、`network/journal` 两个 handler、10 个 `Simulation*` Mixin、`NestedLootTableMixin`、Fabric 注入器与 NeoForge GLM。
- 用原版源码核验：白名单 8 个条件类型均为 record；`Holder.Reference.toString()` 形态；`ClientboundCustomPayloadPacket` 的 1 MiB 上限仅作用于未注册 payload 的 fallback 解码器。
- 对照 [战利品表系统](../dev/loottable.md) 核验 6 条具体声称，均与代码一致（管理页候选来自 `reloadableRegistries().getKeys(LOOT_TABLE)`、排除 `entities/` `blocks/` 前缀、Fabric 显式调用注入器而 NeoForge 为空实现、主线程约束、fingerprint metadata、`loot-analysis-v12`）。

## 4. 未覆盖 / 未验证

- 未编译、未进游戏实测；P1-1 的触发路径为静态推演，没有构造出真有 9 种条件组合的表来复现。
- 未做性能实测：启动期重复读盘与网络包体积均为按代码结构推断的量级判断。
- 客户端 UI 仅审查了 `JournalViewModel` / `JournalTooltipBuilder` 中与概率、条件树相关的部分。
- `LootFunctionHandlers`（906 行）与 `LootConditionHandlers`（896 行）只读了注册表与不确定性分级部分，未逐个核对每个 handler 的静态求值是否与运行时语义一致——这是静态模型的另一个潜在漂移面，需要时单独过一遍。

## 5. 本次改动记录

修复 P1-1 与 P1-2，`./gradlew build` 通过。

| 文件 | 改动 |
|---|---|
| `loottable/simulation/LootProbabilitySimulationJob.java` | 条目与子表在**所有**代表场景中都不适用时返回空场景列表（`summarize` 汇总为 `"?"`），不再逐个写 `"0"`；新增 `isApplicableInAnyScenario` / `isChildApplicableInAnyScenario`；跨 tick 收集未被场景覆盖的条件，表模拟完成时汇总告警 |
| `loottable/simulation/LootSimulationScope.java` | `overrideResult` 记录"场景控制类型但未命中覆盖"的条件；`Scope` 暴露 `uncoveredConditions()`，由调用方在作用域关闭前读取 |
| `loottable/simulation/SimulationScenarioPlanner.java` | 新增 `isScenarioControlled` 供运行时判定；指纹不稳定时该条件按无约束处理（不再据其建场景）并按类型告警一次 |
| `loottable/simulation/LootConditionFingerprint.java` | 新增 `STABLE_METADATA_KEY` / `isStableSource` / `isStable`，按文本形态识别 `类名@identityHash` 默认 `toString` |
| `loottable/analysis/LootConditionHandlers.java` | `analyzeAll` 统一写入指纹与稳定性两项元数据（顺带消除三处重复的 metadata 拼接） |
| `docs/dev/loottable.md` | §7.1 概率口径新增"所有场景都不适用 → `?`"一行；§7.2 补充指纹不变量与两层守卫；§10 新增"新增场景控制类型"扩展点 |

**行为影响**：白名单内 8 个原版类型与 `MudDredgingCondition` 当前均为 record，按现有表内容推断也未触及场景上限，因此本次改动对现网数值**预期无变化**，属防护性修复。只有当出现非 record 的条件类型、或某表条件组合被场景上限截断时，才会从"静默的 `0`"变为"`?` + 告警"。

**残留注意**：P1-2 的运行时守卫会对"场景控制类型但未命中覆盖"的条件告警，其中"条件来自运行时注入路径"（如其他模组 GLM 注入、被排除的循环引用表）属预期命中，看到告警时先按这条排除。`minecraft:gameplay/fishing` 上的那条 `entity_properties` 告警即属此类，成因与判定见 [战利品表系统](../dev/loottable.md) §7.2 的「已知的预期告警」。

