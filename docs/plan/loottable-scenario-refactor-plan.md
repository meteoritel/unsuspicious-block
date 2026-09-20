# 战利品表条件场景与按需概率模拟重构规划

> 状态：**规划完成，尚未实施**，2026-09-20。全部设计决定经五轮质询、共 11 批提问与用户逐项确认，决策记录见第五节；尚未修改任何 Java、语言文件、缓存格式或网络协议，未执行构建与实机验证。
>
> 当前机制的唯一权威描述是 [战利品表系统](../dev/loottable.md)；本文记录改造背景、核实依据与决策记录，不构成机制权威。机制描述在实施完成后同步回该文档。
>
> 本文**取代并合并**了《战利品幸运门槛与分档概率改造方案》（原 `docs/plan/loottable-luck-analysis-plan.md`，2026-09-20 规划完成、未实施）：其「默认预览幸运 0」「区分幸运阻断与抽样未命中」「路径级门槛」结论予以保留，「关键幸运档位切换」改为玩家自填数值，「完整区间代数」缩减为「最小门槛判定」。该旧文档已移入本地归档目录（`docs/archive/` 不入库，故此处不提供链接）。
>
> 修订记录：首版。四轮质询共裁定 24 项，含两处用户否决了本文档作者的推荐（基准场景选全假而非"覆盖最多"，见决策 2）、一处作者漏问后补问（按需模拟的触发与限流，见决策 19）。
>
> 修订（第二轮）：处理首版第八节的全部待确认项，新增裁定 6 项（决策 26-31）。其中「工具附魔等级」经源码核实后由待确认项转为**明确支持**——它是函数/条件在运行时读取的参数，不是条件谓词，因此以参数形态支持而不进入场景维度（见 3.2）。§2.1、§2.3（现为 §2.4）、§4.1、§4.2、§4.3、§4.5、§4.6、§6、§7 与第八节已按此同步。
>
> 修订（第三轮，评审修正）：针对前两版的十项评审意见逐条核实并修正——规划成本失控、`NeedsCondition` 的存在性证明不充分、运行时注入发现无闭环、把"路径适用"等同于"已测量"、新缓存结构漏掉子表入口与动态来源、失效保证范围写得过大、`DeclaredChance` 绕过状态、零命中展示违反诚实目标、泥地打捞验收标准写错、P0/P1 交付物与 P2 依赖倒挂。新增裁定 17 项（决策 32-48）。三处结构性改动：**目录不再枚举候选 Input，只发布约束**（决策 32，原 `SimulationInputCatalog` 的枚举职责作废）；**新增可调模拟次数并进入 Input 身份**（决策 38-39）；**推翻本文件首版 §2.3 中"不做读玩家上下文"的边界，改为提供玩家主动触发的一键填充**（决策 41）。两处用户否决了本文档作者的推荐（首次触发者记录只落盘不展示，见决策 37；标签谓词取首个成员，见决策 45）。§2.1-§2.3（本轮新增 §2.3 追踪范围后，原"明确不做"下移为 §2.4）、§3.4、§3.6、§3.7、§4.1-§4.7、§5-§9 已按此同步。
>
> 修订（第四轮，范围收窄）：用户指出本文档需要先把**追踪范围**写明确——本模组的战利品表追踪明确不做**方块破坏掉落表**（`blocks/` 前缀）与**实体掉落表**（`entities/` 前缀），**陶罐（`pots/`）是唯一例外**（它是"没有 UI 的容器"）。据此新增 §2.3「追踪范围（前提约束）」、原「明确不做」下移为 §2.4；**D6、D7 作废**（它们所指的表都不在追踪范围内），参数填充缺口只剩 D8；移除**爆炸与击杀/抢夺**两个旋钮及其全部连带内容（`ScenarioParams` 的两个字段、`ParameterKind` 的两个枚举值、决策 6/9/17/23 的白名单与默认值、§7.2 的三条验收项、§6 的一条风险项、§4.5 的两处函数展示项）；§3.3 的内置表统计扣减三张方块破坏表（15 → 12 个场景、13 → 10 张表）。新增裁定 1 项（决策 49）。

> 需要留意的边界：排除是按**路径前缀**而非表声明的 `type`。被追踪的 `gameplay/panning/*` 与 `gameplay/fossil_hunter/*` 就声明了 `minecraft:block`，因此"某表声明为 block/entity"并不等于"它在范围外"；反之，前缀匹配是配置驱动的，手工添加前缀可绕过管理页的拒绝（见决策 49 的遗留边界）。

## 一、现状

> 本节记录的是**实施前**状态。Java 路径相对 `common/src/main/java/com/meteorite/unsuspiciousblock/`，资源相对 `common/src/main/resources/`；行号为规划时定位，会随实施失效。

### 1.1 现有实现

战利品表的解析与概率链路按四层组织（详见 [战利品表系统](../dev/loottable.md) 第 2 节）：快照 → 引用图 → 编译（`analysis/`）→ 投影与目录（`catalog/`），再由 `simulation/` 做概率模拟、由 `journal/catalog/` 与 `world/` 做持久化与同步。

**场景机制**：`SimulationScenarioPlanner.plan()`（`loottable/simulation/SimulationScenarioPlanner.java:67`）已经**从条件树推导场景**——它遍历 `table.items()` 每条获取路径的 `allConditions()`，经 `requirementsFor` 把条件树解析成「条件指纹 → 成立与否」的最小布尔赋值，以 `canonical()` 规范化去重，最多保留 `MAX_SCENARIOS = 8` 个（`:34`）。所以「分场景模拟」不是权宜之计，现有实现本身就是条件场景模拟。

**场景的表示能力**：载体是 `Map<String, Boolean>`（`SimulationProfile.conditionOutcomes`，`SimulationProfile.java:32`）。指纹由条件对象的 `toString()` 生成（`LootConditionFingerprint`），因此指纹包含**整个谓词**——「群系为沼泽」与「群系为丛林」天然是两个独立维度，各自成立/不成立。参数化取值（附魔等级 1..N、时间范围、分数区间）**无法表达**。只有 `SCENARIO_CONDITIONS` 里 8 类条件进入场景维度（`SimulationScenarioPlanner.java:44-52`），`match_tool` 与 `block_state_property` 通过伪造布尔满足——`SimulationContextConditionMixin`（`mixin/loottable/SimulationContextConditionMixin.java:13`）在 `MatchTool.test()` 的 HEAD 拦截并返回作用域里的布尔值。

**执行方式**：`LootProbabilitySimulationWorker` 在主线程 `END_SERVER_TICK` 消费队列，`TICK_BUDGET_NANOS = 15ms`（`LootProbabilitySimulationWorker.java:35`），高低双优先级队列、`enqueued` 按 **tableId** 去重（`:45,96`），任务可跨 tick 续跑。每个场景跑 `SIMULATION_COUNT = 10_000` 次（`LootProbabilitySimulator.java:21`），真实调用 `lootTable.getRandomItems(lootParams)`，幸运由 `LootParams.Builder.withLuck` 注入，固定值 `SimulationProfile.CATALOG_LUCK = 1.0F`（`SimulationProfile.java:35`）。

**参数填充**：`LootContextParamFiller.createForSimulation`（`LootContextParamFiller.java:36`）**只遍历 paramSet 的 required 参数**（`:72`），optional 一律不填（钓鱼表的 THIS_ENTITY 是唯一例外，`:101-108`）。

**持久化与失效**：`LootProbabilityData`（SavedData，格式 2，`FORMAT_VERSION = 2`，`world/LootProbabilityData.java:57`）按表存 `hash` + 每签名概率 + `scenario_key → probability`。表哈希由 `ArchaeologyJournalServerCatalog.computeTableHashes`（`journal/catalog/ArchaeologyJournalServerCatalog.java:487`）计算，输入为 `SIMULATION_CACHE_VERSION`（当前 `loot-analysis-v15`，`:66`）+ 模拟次数 + **整棵子树的资源栈摘要** + 编译产物摘要。`needsResimulation(tableId, hash)`（`LootProbabilityData.java:260`）据此判缓存命中。

**展示**：`ItemGridPanel` 渲染物品网格；卡片概率取 `JournalViewModel.maxScenarioProbability()`（`client/ui/screen/JournalViewModel.java:589`，调用点 `:534` 与 `:579`）——即**跨代表场景的最高值**。`Probability` 是三态 sealed 类型（`catalog/Probability.java:19`：`Unknown` / `Unreachable` / `Measured`），`ProbabilityFormat.format`（`simulation/ProbabilityFormat.java:54`）把 `Unknown` 渲染为 `?`、`Unreachable` 渲染为 `0%`。右页是硬编码三 Tab（`client/ui/panel/RightPageContainer.java:28`：`INTRO / ARCHAEOLOGY / LOG`），各自实现 `PagePanel`。

### 1.2 需要改造的耦合点

| 位置 | 现有行为 | 为何要改 |
|---|---|---|
| `simulation/SimulationScenarioPlanner.java:34,87-91` | `MAX_SCENARIOS = 8`，超出即丢弃候选 | 静默截断，玩家看不到"缺了什么" |
| 同文件 `:44-52` | `match_tool` 属场景控制类型 | 与真实工具参数冲突（见 D5） |
| `simulation/SimulationProfile.java:32,35` | 条件布尔 + 固定 `CATALOG_LUCK = 1.0F` | 需承载玩家可选的参数旋钮 |
| `simulation/LootContextParamFiller.java:72` | 只填 required 参数 | 未填充的 optional 会让条件恒真/恒假，barter 表更是直接抛异常（D8） |
| `simulation/LootProbabilitySimulationWorker.java:45,96` | `enqueued` 按 tableId 去重 | 同表两个参数组合会互相顶掉 |
| `simulation/LootProbabilitySimulationJob.java:292,327` | "该场景不适用"记 `Probability.unreachable()` | 与静态不可达折叠（D2） |
| `catalog/Probability.java:19-55` | 三态，`format` 返回 `String` | 需第 4 态、`Unknown` 需带原因枚举，且文案须本地化 |
| `client/ui/screen/JournalViewModel.java:589` | 跨场景取最大值 | 网格展示的最有利组合不是玩家处境 |
| `client/ui/panel/RightPageContainer.java:28,151-157` | Tab 枚举硬编码三项 | 需新增场景 Tab |
| `journal/catalog/ArchaeologyJournalServerCatalog.java:66,487` | 哈希含 `loot-analysis-v15` | 统计口径变化必须失效 |
| `world/LootProbabilityData.java:57,28-46` | 格式 2，flat `scenarios[]`，每签名另存 `hasDirectSource` / `sourceChildTables` | 键结构改为 `(表哈希, SimulationInputKey)`；发现记录（含上面两项）须保留并提升为表级，否则重启后动态条目的归属会丢 |
| `network/ModPayloads.java:76,105,139` | 无按需模拟相关 payload | 需新增 C2S/S2C |

### 1.3 现存缺陷

**D1 网格展示的是最有利组合的上限，不是玩家处境。** `JournalViewModel.maxScenarioProbability` 取跨场景最大值。以 `gameplay/fishing/mud_dredging.json` 为例，它的沼泽子表 entry 权重 6、普通子表权重 4（`mud_dredging.json:39,56`），于是网格给玩家看的是「你站在沼泽里」的那个数，却完全不提示这个前提。这是本轮最该修的谎。

**D2「该场景下不适用」与「静态不可达」被折叠成同一个状态。** `LootProbabilitySimulationJob.java:292,327` 两处都把"某条路径在该场景不可用"写成 `Probability.unreachable()`，`ProbabilityFormat` 一律渲染 `0%`。要显示「需要条件」就必须先把这两种语义拆开。

**D3 场景上限静默截断。** `SimulationScenarioPlanner.java:87-91` 达到 `baseScenarioLimit` 即停止收集，被丢弃的条件组合只体现为 `Probability.unknown()`（`?`），玩家无法知道是被截断还是规则未解析。原版 `minecraft:gameplay/fishing` 的 `FISHING` 保留 6+2 名额（`:35`），已满。

**D4 场景由规划器选定，玩家既不能选场景也不能改参数。** 不存在任何按需/单场景重算入口：`LootProbabilitySimulator.createJob` 每次都调用 `SimulationScenarioPlanner.plan(...)` 生成全部代表场景并顺序跑完。

**D5「工具」有两套互相矛盾的真相。** `match_tool` 被 `SimulationContextConditionMixin` 伪造为布尔（"匹配成功"只发生在作用域覆盖时），而 `apply_bonus`（`ore_drops`/`uniform_bonus_count`）与 `table_bonus` 读的是**真实工具**——`SimulationProfile.eligibleConditions` 给的是一把**无附魔的钻石镐**（`SimulationProfile.java:48`）。于是"工具匹配"与"时运等级"互不相干，基础场景的时运曲线恒为 0 级。泥地打捞表的满级工具假设是唯一例外（`SimulationScenarioPlanner.java:125-139`）。

**D6 / D7 已作废（第四轮评审修正，见决策 49）。** 原 D6（`block` 类型表的 `EXPLOSION_RADIUS` 从不填充，导致 `survives_explosion` 恒真）与原 D7（`entity` 类型表的击杀者链路缺失、抢夺恒为 0）描述的现象客观存在，但**它们所指的表都不在追踪范围内**：本模组的追踪明确不做方块破坏掉落表（`blocks/` 前缀）与实体掉落表（`entities/` 前缀），陶罐（`pots/`）是唯一例外（见 2.3）。已核实的证据：会用到这些机制的只有 `blocks/pottery_wheel.json`（唯一带 `survives_explosion` 的表）与 `entity` 类型的表，两者都在范围外；被追踪的 `gameplay/panning/*`、`gameplay/fossil_hunter/*` 虽然**声明**了 `minecraft:block`，条件只用到 `random_chance`。因此这两条从缺陷列表移除，参数填充的缺口只剩 D8。

**D8 `barter` 类型的表会直接抛异常。** `PIGLIN_BARTER` 的 allowed 集合只有 `THIS_ENTITY`，而 `LootContextParamFiller.java:39` 无条件塞入 `ORIGIN`，`LootParams.create` 会抛 `IllegalArgumentException`。以物易物表位于 `gameplay/` 下、不在被排除的两个前缀里，整合包把它纳入追踪即失败。【源码核实】

**D9 数据包重载后目录与概率缓存不会失效。** 全仓检索 `ReloadListener` / `AddReloadListenerEvent` / `onResourceManagerReload` **无任何注册**（仅 `mixin/client/ClientLanguageMixin.java:19` 是客户端语言资源，与战利品表无关）。实际触发重建的只有三条路径：服务端启动（`fabric/.../UnsuspiciousBlockFabric.java:261`、`neoforge/.../UnsuspiciousBlockNeoForge.java:387`）、配置轮询（`platform/ServerLootTableConfigManager.java:15,46`，每 20 tick 且**仅在追踪规则变化时**才调 `onDataPackReload`）、以及管理员动作（`command/JournalCommand.java:148,183` 的 `/usb journal reload`、`network/journal/LootTableManagementHandler.java:46`）。

后果是：整合包作者改了战利品表 JSON 并执行 `/reload` 后，本模组的资源快照与表哈希**不会重算**，概率缓存不会失效；而 `LootProbabilitySimulator` 取运行时表走的是 `ReloadableServerRegistries`（重载后是新的），两者会出现"静态分析按旧 JSON、运行时抽取按新 JSON"的不一致。**这一条是用户诉求「战利品表变化后缓存要清空」的前提，当前连"发现变化"这一步都不存在。**

## 二、目的

### 2.1 玩家目标

让玩家能回答三个问题：**这条物品我现在能不能拿到**、**拿不到是因为缺什么**、**如果我换成某种情况，概率是多少**。

- 网格只对**当前选中且已完成计算的 Input**给出数字；不满足条件的条目不显示数字，显示「需要条件」。
- 「需要条件」分两级：网格与 tooltip 给**静态信息性提示**（该路径引用了哪些可调整的旋钮与条件），**不承诺**调参后一定拿得到；「填入推荐值」按钮**只在服务端找到整条路径的联合见证时才渲染**，找不到就退化为不可点击的纯文本（见决策 32、34）。
- 新增场景 Tab，列出当前表由条件树构成的场景与状态；**网格页另有快捷切换下拉**，每项带缓存状态标记（已缓存／未计算／计算中／上次失败）（见 4.5、决策 43）。
- 幸运可以自填（输入框 + 静态门槛算出的建议档位）；**模拟次数可选离散档位**，上限 10 万（见决策 38-39）。
- 工具与附魔等级可作为参数选择；附魔等级按每张表实际引用到的附魔给 0..maxLevel 的取值。**不做爆炸与击杀/抢夺旋钮**——它们分别只服务于方块破坏表与实体掉落表，见 2.3。
- **一键填充玩家当前状态**：一次把可得参数与可得条件场景按玩家此刻的处境填好。它是**玩家主动触发**的动作，不是"自动选场景"（见决策 41）；读不到的项逐条列出，不伪装成"确定不成立"。
- 明确区分「需要条件」「未覆盖」「规则未解析」「尚未计算」「计算失败」「已淘汰」「静态不可达」「已测量但抽样零出现」——前六者网格一律 `?`，靠 tooltip 分述（见决策 36）。

### 2.2 架构目标

场景与参数统一为一个记录类 `SimulationInput` 作为模拟的输入身份（含模拟次数，见决策 38）；服务端在启动时每表只模拟**一个基准 Input**，其余 Input 由玩家按需触发。三条关键约束：

1. **目录只发布约束，不枚举候选 Input**（决策 32）。原计划让目录生成"场景 × 基座工具 × 附魔等级赋值"的完整候选集合，那是一个乘积——32 个场景的上限只约束其中一维，6 种附魔各 4 档就是 4⁶ × 32 = 131,072 个组合。现在改为：目录发布该表可调整的**旋钮与场景约束描述**，单个 Input 在请求时构造并校验；条件树展开与推荐值搜索各自设预算，超限返回未知。缓存 LRU 限制的是结果数量，**限制不了这部分计算**，因此必须从源头取消枚举。
2. **缓存键为 `(表哈希, SimulationInputKey)`，且按内容去重**（决策 37）：键里不出现玩家身份，任何 Input 只要规范化编码相同就复用同一份结果，无论由谁触发、由谁算出。
3. **展示状态由服务端派生，客户端只渲染**；持久化层只存原始测量值，不存派生状态；"表变了缓存自动失效"由现有哈希机制承担（其覆盖范围见 3.6）。

### 2.3 追踪范围（前提约束）

**本模组的战利品表追踪明确不做方块破坏掉落表与实体掉落表。** 默认规则只命中 `archaeology/`、`archeology/`、`pots/`、`minecraft:gameplay/fishing`、`minecraft:chests/{buried_treasure,ancient_city,ancient_city_ice_box}`，以及模组自己的 `gameplay/{fishing,fossil_hunter,panning}/`（`ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES`，`platform/services/ILootTableConfig.java:25-36`）；`blocks/` 与 `entities/` 两个前缀被管理页一律拒绝（`journal/tracking/RecentLootTableService.java:27-30`）。

**陶罐（`pots/`）是唯一例外**：它虽然是方块破坏掉落，但本质是"没有 UI 的容器"，其内容正是本模组要记录的对象，因此 `pots/` 被显式列入追踪前缀。

注意这条范围是按**路径前缀**划的，不是按表声明的 `type`——被追踪的 `gameplay/panning/*` 与 `gameplay/fossil_hunter/*` 就声明了 `minecraft:block`。因此"某张表声明为 block/entity"不等于"它在范围外"，反之亦然；实现侧仍必须让参数填充对齐声明 paramSet 的 allowed（见 D8 与 §4.5）。

### 2.4 明确不做（本轮范围外）

- **不做参数化取值维度**（群系逐个枚举、时间/分数区间展开；附魔等级是例外，见下条）。场景仍是"每个出现的条件谓词成立/不成立"的布尔赋值。
- **不做完整候选 Input 枚举**（决策 32）。目录不生成"场景 × 工具 × 附魔等级"的笛卡尔积，也不把"存在可行 Input"作为构建期的承诺。
- **不做完整区间代数**。负 quality 的幸运上限、同一物品的多段不连续区间、父子路径的交集并集只做到"最小门槛判定"，遇到区间受限即降级为`需要幸运（区间受限）`。
- **不做"自动"选场景，但做玩家主动触发的"一键填充当前状态"**（决策 41，**推翻本项在首版中的表述**）。首版把"读当前群系/手持工具/是否在水中"整体列为不做；现在改为：不自动改场景，但提供一键填充，把玩家此刻的处境映射为参数值与条件指纹的成立与否，读不到的项列出而不猜测（见决策 41）。
- **不按玩家 uuid 分区存储测量结果**（决策 37）。世界数据里只有按 Input 内容索引的全服共用缓存；玩家偏好留客户端本地。
- **不再把附魔等级压成满级单点**：等级改为参数（见 3.2 与决策 26），按每张表实际引用到的附魔给 0..maxLevel 的取值。
- **不做客户端自由 float 的无界请求**；幸运有界，模拟次数取离散档位。
- 不改变实际掉落与玩家进度；不实现全幸运连续概率曲线；不迁移历史缓存数值。

## 三、技术原理

### 3.1 原版 LootContext 参数全貌【源码核实】

1.21.1 `LootContextParams` 共 12 个字段（`net/minecraft/world/level/storage/loot/parameters/LootContextParams.java` L16-27）。**1.21.1 不存在 `ADDITIONAL_PARAMETERS`**（1.21.2+ 才有）。核查方式：mc-developing-mcp 数据库对 `LootContextParams` 与 `LootContextParamSets` 返回空，改读本机 NeoForm 中间产物源码（`C:\Users\Geng\.gradle\caches\neoformruntime\intermediate_results\` 下的 `mergeWithSources_*` 与 `sourcesAndCompiledWithNeoForge_*` 两个 jar，战利品相关类逐字节等价）。

| 参数 | 类型 | 谁读它 | 是否值得开放给玩家 |
|---|---|---|---|
| `TOOL` | ItemStack | `MatchTool`、`BonusLevelTableCondition`(table_bonus)、`ApplyBonusCount` | **值得**：附魔等级直接改变数量与资格 |
| `ATTACKING_ENTITY` | Entity | `EnchantedCountIncreaseFunction`、`LootItemRandomChanceWithEnchantedBonusCondition` | **不在追踪范围**：只被实体掉落表使用（见 2.3） |
| `EXPLOSION_RADIUS` | Float | `ExplosionCondition`、`ApplyExplosionDecay` | **不在追踪范围**：只被方块破坏表使用（见 2.3） |
| `DAMAGE_SOURCE` | DamageSource | `DamageSourceCondition`（另读 ORIGIN） | **值得**：致死方式决定分支 |
| `THIS_ENTITY` | Entity | `LootItemEntityPropertyCondition`(this)、`CopyNameFunction` | 部分：钓鱼的"是否开放水域"值得，其余状态细节不值得 |
| `ORIGIN` | Vec3 | `LocationCheck`、`ExplorationMapFunction` | 不值得：群系/维度/光照由坐标决定，玩家选的是"在哪"而非坐标 |
| `BLOCK_STATE` | BlockState | `LootItemBlockStatePropertyCondition`、`CopyBlockState` | 不值得：方块表自带语义 |
| `LAST_DAMAGE_PLAYER` | Player | `LootItemKilledByPlayerCondition`、`attacking_player` 目标 | **不在追踪范围**：实体掉落表专用 |
| `BLOCK_ENTITY` | BlockEntity | `CopyComponentsFunction`、`CopyNameFunction` | 不值得：纯数据来源 |
| `DIRECT_ATTACKING_ENTITY` | Entity | `entity_properties(direct_attacker)` | 不值得：箭还是射手的实现细节 |
| `ENCHANTMENT_LEVEL` | Integer | `EnchantmentLevelProvider`（`enchantment_level` 数字提供器） | **不适用**：只出现在附魔效果的 5 个 paramSet |
| `ENCHANTMENT_ACTIVE` | Boolean | `EnchantmentActiveCheck` | **不适用**：同上 |

两条必须记住的修正【源码核实】：

1. **幸运不是 `LootContextParam`**，它是 `LootParams` 的独立字段，只被 `LootPool.addRandomItem`/`addRandomItems` 读取（权重公式 `max(floor(weight + quality × L), 0)` 与抽取次数 `rolls + floor(bonus_rolls × L)`）。**没有任何原版 loot condition 或 function 直接读幸运**。
2. **`random_chance` 在 1.21.1 不吃幸运**，实现是 `context.getRandom().nextFloat() < chance`。旧版本记忆里的 `random_chance_with_looting`、`looting_enchant`、`copy_nbt`、`smelt` 在 1.21.1 均不存在（对应 `random_chance_with_enchanted_bonus`、`enchanted_count_increase`、`copy_custom_data`、`furnace_smelt`）。

`LootContextParamSets` 的关键 required/optional 划分（决定了参数填充与旋钮可见性）：

| paramSet | required | optional |
|---|---|---|
| `fishing` | ORIGIN, **TOOL** | THIS_ENTITY |
| `block` | BLOCK_STATE, ORIGIN, TOOL | THIS_ENTITY, **BLOCK_ENTITY**, **EXPLOSION_RADIUS** |
| `entity` | THIS_ENTITY, ORIGIN, DAMAGE_SOURCE | **ATTACKING_ENTITY**, **DIRECT_ATTACKING_ENTITY**, **LAST_DAMAGE_PLAYER** |
| `archaeology` / `chest` / `shearing` / `vault` / `command` | ORIGIN | THIS_ENTITY |
| `gift` / `selector` / `equipment` / `advancement_*` | 见源码 | — |
| `generic`（`ALL_PARAMS`） | 10 项（除两个 ENCHANTMENT_*） | — |
| `barter`（`PIGLIN_BARTER`） | THIS_ENTITY | **无**（allowed 也只有 THIS_ENTITY） |

`EntityTarget` 到参数的映射有一处反直觉：`this`→THIS_ENTITY、`attacker`→ATTACKING_ENTITY、**`attacking_player`→LAST_DAMAGE_PLAYER**（不是 ATTACKING_ENTITY）。做"抢夺"模拟必须看 ATTACKING_ENTITY。

### 3.2 附魔等级的读取路径与可传入性【源码核实】

首版曾把「附魔等级 1..N」与群系取值、时间/分数区间并列，当作"会与其它维度相乘的参数化取值"排除。本次源码核实推翻了这个归类：**等级不是条件谓词，而是函数/条件在运行时读取的输入**。因此它可以作为参数支持，且不产生任何场景组合。

**等级从哪来**：`ApplyBonusCount.run` 每次执行时实时从 `LootContextParams.TOOL` 读（`ApplyBonusCount.java:81-90`）：

```java
ItemStack itemstack = context.getParamOrNull(LootContextParams.TOOL);
if (itemstack != null) {
    int i = EnchantmentHelper.getItemEnchantmentLevel(this.enchantment, itemstack);
    int j = this.formula.calculateNewCount(context.getRandom(), stack.getCount(), i);
    stack.setCount(j);
}
```

JSON 的 `enchantment` 字段只决定"读哪个附魔"，`formula` 决定"怎么用等级算"，**JSON 里根本不存在等级数值**。

**三个公式**（1.21.1 中三者都是 `ApplyBonusCount` 的嵌套 record，没有独立的 `OreDrops.java` 等文件）：

| formula | 实现 | 等级 0 的行为 |
|---|---|---|
| `ore_drops` | `originalCount × (clamp(nextInt(level + 2) - 1, 0, ∞) + 1)` | 原样返回 |
| `uniform_bonus_count` | `originalCount + nextInt(bonusMultiplier × level + 1)` | `nextInt(1) = 0`，无加成 |
| `binomial_with_bonus_count` | 循环 `level + extra` 次，每次以 `probability` 决定 `originalCount++` | **仍有 `extra` 次机会**（唯一在等级 0 也可能加成的公式） |

**等级可被任意传入**：读取链路是 `ItemStack.getEnchantmentLevel` → `ItemEnchantments.getLevel`，即纯 map 查表原样返回（`ItemEnchantments.java:66-68`）。**全链路没有任何裁剪到附魔 `maxLevel` 的逻辑**；唯一的数值边界是 `0..255`（组件 codec `intRange(0, 255)`、构造器校验、`Mutable.set/upgrade` 的 `Math.min(level, 255)`，以及 NeoForge 事件路径 `EventHooks.getEnchantmentLevelSpecific` 在超过 255 时静默压到 255）。`Enchantment.getMaxLevel()` 只被表示层（`getFullname` 是否拼罗马数字）使用，不参与读取校验。

**两条读路径必须分开**：

| 机制 | 读的参数 | 落地方式 |
|---|---|---|
| `apply_bonus`、`table_bonus`、模组自定义 `tool_enchantment` | **TOOL** | 把等级写进工具栈的 `ENCHANTMENTS` 组件 |
| `enchanted_count_increase`、`random_chance_with_enchanted_bonus` | **ATTACKING_ENTITY 的装备槽**（`EnchantmentHelper.getEnchantmentLevel(Holder, LivingEntity)` 遍历该附魔允许的槽位取最大值） | 给假玩家**装备**一件带该附魔的物品；塞 TOOL 无效。（这两个机制只在实体掉落表里出现，**不在追踪范围**，见 2.3；保留本行是为了说明"工具附魔"为何不能替代"装备附魔"） |

**不可用的机制**：`enchantment_level` 数字提供器与 `enchantment_active_check` 读的是 `ENCHANTMENT_LEVEL` / `ENCHANTMENT_ACTIVE` 这两个独立参数，只在附魔效果的 5 个 paramSet（`enchanted_damage`、`enchanted_item`、`enchanted_location`、`enchanted_entity`、`hit_block`）里 required，**任何普通战利品表 paramSet 都不包含它们**。所以用它们的表无法通过构造工具栈影响，且会在 `LootContext.getParam` 处抛异常导致整表模拟失败（处置见决策 31）。

**项目已有先例**：`SimulationScenarioPlanner.toolWithEnchantment(enchantment, level)`（`:177-183`）已经会构造带指定等级附魔的工具栈，经 `SimulationProfile.withTool` 与 `LootContextParamFiller.java:136-137` 注入 `TOOL`。把等级换成任意值即可精确复现原版行为——这是"等级当参数"几乎零成本的原因。

### 3.3 场景枚举的真实规模【数据包核实 + 估算】

规划器枚举的是**真实路径上出现过的最小布尔赋值**，不是 2^N 全组合——`plan()` 先放一个空候选，再为每条路径产出一个需求集合，以 `canonical()` 去重，最后把所有指纹补齐（缺省 false）。所以规模 ≈ 不同需求集合的数量。

内置表的条件分布与场景数估算（第四轮已移除三张方块破坏表，见 2.3）：

| 表 | 声明类型 | 参与场景维度的条件 | 取消 8 上限后的场景数 |
|---|---|---|---|
| `chests/hand_of_cat_cache.json` | `chest` | 无 | 1 |
| `gameplay/cat/ghost_gift.json` | `gift` | 无 | 1 |
| `gameplay/fishing/mud_dredging/common.json` / `swamp.json` | `fishing` | 无 | 1 |
| `gameplay/fishing/mud_dredging.json` | `fishing` | 2×`entity_properties(in_open_water)` + 1×`location_check(#c:is_swamp)` | **3** |
| `gameplay/fossil_hunter/nether_bone_block.json` | `block` | 5×`random_chance`（非场景控制） | 1 |
| `gameplay/fossil_hunter/overworld_bone_block.json` | `block` | 4×`random_chance` | 1 |
| `gameplay/panning/lava.json` | `block` | 1×`random_chance` | 1 |
| `gameplay/panning/river.json` | `block` | 2×`random_chance` | 1 |
| `usb/random_potion.json` | `generic` | 无 | 1 |

合计 12 个场景（10 张表），最大值 3。第四轮移除的 `blocks/pottery_wheel.json`、`blocks/unsuspicious_gravel.json`、`blocks/unsuspicious_sand.json` 均为方块破坏表（见 2.3），其 3 个场景一并扣减；本表只统计条件与场景规模，**不区分这些表进入目录的具体入口**（前缀规则命中 / 运行时开箱 / 考古 / 赠礼等），因此它不是"默认追踪集合"的清单。**内置数据里没有任何一张表因 8 上限被截断**；真正受影响的只有原版 `minecraft:gameplay/fishing` 这类带多条群系分流条目的表（它的 6+2 名额已满）。这有两个结论：取消 8 上限本身**不省启动时间**（内置表都不到 8），省成本的是"不再全量列举"；而硬上界仍必须保留，因为它挡的是整合包表。

### 3.4 场景枚举会爆炸的那一维：条件谓词的取值【源码核实】

现有实现的场景值只有布尔，且**没有任何枚举/数值范围展开逻辑**（全仓无按 `IntRange` 枚举时间/分数、按群系标签成员展开、按附魔等级 1..N 展开场景的代码）。参数值的信息只以"身份"形式烘进指纹（`LootConditionFingerprint.ofRaw` = `hash(类名 + toString())`），场景侧只记该指纹成立/不成立。

因此：`location_check(#c:is_swamp)` 与 `location_check(#minecraft:is_jungle)` 是**两个不同指纹**、各自真假，能表达"沼泽条件成立"；但**无法表达"我确实在沼泽"**。附魔等级维当前被刻意压成满级单点（`SimulationScenarioPlanner.java:125-139` 的 `applyMudDredgingTool`、`:142-165` 的 `appendMudDredgingScenarios`，等级取 `definition().maxLevel()`），若展开 1..3 会让泥地打捞 3→9、钓鱼注入 2→6，并与其他维度相乘。

**展开本身也需要预算**（决策 33）。`requirementsFor`(`:232`) 对 `all_of` 走 `combineAnd`(`:289`)，而 `combineAnd` 是**左右叉乘**；`any_of` 则 `addAll` 累加。于是"`all_of` 里嵌多个 `any_of`"的条件树会在 `plan()` 的第 87-91 行截断**之前**就指数膨胀——场景上限约束的是展开的**结果数量**，约束不了展开的**过程成本**。因此：条件树展开与推荐值搜索各设一个独立预算，超预算的路径按"无约束"处理（与 `LootConditionFingerprint.isStable` 失败时的既有降级同型：数值偏保守，但不会把条目伪装成确定不可达），并在表级汇总告警一次。

### 3.5 三处结构性不一致

1. **`match_tool` 是伪造的布尔，而时运曲线读真实工具**（D5）。同一个"工具"概念两套真相，且基础场景的工具是无附魔钻石镐，使 `apply_bonus` 的时运曲线恒为 0 级。
2. **`plan()` 的 default 候选（全部条件为假）对条件门槛条目等价于"拿不到"**。`mud_dredging.json` 里全部条目受**池级** `tool_enchantment` 约束，其中两个子表入口（`:44-52`、`:61-69`）另要求 `entity_properties(in_open_water=true)`；default 场景下前者一律不成立，后者更是不成立。所以"启动只跑一个场景、就取 default"若照字面实现，会让该表整表显示 0%——**基准场景必须配一条"不显示数字而显示需要条件"的展示规则**，这正是 D2 要拆的那个状态。（注意区分：五件直接物品只缺附魔参数、不涉开放水域，验收标准见 7.2 的三段式切分。）
3. **文档与代码不一致**（留档）：`docs/plan/loottable-condition-plan.md` 第九节称泥地打捞表的场景数"由 2 降为 1"，而按 `mud_dredging.json:44-75` 与 `plan()` 实际为 3。判定为文档简化/笔误，不影响机制。

### 3.6 缓存失效链与它的保证范围

现有每表哈希把"表变没变"做成了一个 SHA-256（`ArchaeologyJournalServerCatalog.java:487-536`）。它的**实际覆盖范围**必须逐项写清，否则"表变即失效"会被读成一句比事实更大的保证（决策 35）：

| 哈希输入 | 覆盖到什么 | 覆盖不到什么 |
|---|---|---|
| `SIMULATION_CACHE_VERSION` + 模拟次数 | 统计口径 | — |
| 子树内每张表的**资源栈摘要** | 该表 JSON 的任何改动——条件、函数、`weight`、`quality`、`rolls`、`bonus_rolls` 全在原文里 | 表 JSON 之外的数据 |
| **编译产物摘要**（`updateCompiledProductDigest`，`:523`，只写物品签名与 id） | item tag 展开结果的变化（JSON 文本不变、只有展开结果变也会失效），以及物品签名/集合变化 | 同上 |
| — | — | **附魔定义**：`unsuspiciousblock:mud_dredging` 的 `max_level` 不在任何战利品表 JSON 里，却决定模拟用的满级工具与等级控件范围（决策 26） |

因此本轮把失效保证拆成两句话（决策 35）：

1. **"表 JSON 变化必然失效"**——现在成立，无需改动。
2. **"影响概率的所有数据变化必然失效"**——本轮把**被引用到的附魔定义摘要**并入哈希输入后成立。枚举来源就是子树编译产物里五个读附魔的机制的 `enchantment` 字段（`apply_bonus`、`table_bonus`、`tool_enchantment`、`enchanted_count_increase`、`random_chance_with_enchanted_bonus`）。其余外部注册表依赖列为**已知残余**，写入 `docs/dev/loottable.md`。

明确**不受影响、因此不列入残余**的两类，避免把"没进摘要"一律当成漏洞：biome tag 成员（`location_check` 由条件指纹回答，不查真实区块与 tag）与 damage type 定义（`DAMAGE_SOURCE` 由 profile 填充，不读注册表）。

修好 D9（数据包重载监听）只解决"**哈希会不会被重算**"；"重算出的哈希是否**足以**区分新旧"是上表的事。两者不可互相替代——首版把二者混成了一句"自动成立"，是本次修正的重点。

在此前提下，只要按需模拟的结果写进同一份 `LootProbabilityData`、并把键写成 `(表哈希, SimulationInputKey)`，那么"战利品表变了 → 该表所有 Input 的缓存一起失效"对**上表覆盖到的输入**是自动成立的，不需要额外的清空逻辑。

### 3.7 必须遵守的边界

- **抽样零命中给不出概率上界**：`Measured(0.0)` 的网格文案一律是**「未命中」**（简短、不被截断），tooltip 写「本次抽样 N 次命中 0 次」并给出当前抽样次数与"可提高抽样次数"的提示；**绝不把抽样分辨率写成概率上界**。`<0.01%` 的旧写法作废——它等于声称 p < 1/N，而 n=10000 时真实概率为 0.01% 仍有约 36.8% 的概率零命中。`0%` 只留给静态可证明的不可达（见决策 40）。
- **"没算到"不能显示成"不可能"**：未覆盖、超出上界、规则未解析、尚未计算、计算失败、已淘汰都保持 `?`，由 `Unknown(Reason)` 在 tooltip 分述（见决策 36）。
- **每维独立判定不得合并成可操作性承诺**：静态信息性提示（"该路径需要工具带 X 附魔"）可以无条件展示，因为它是单路径、可静态证明的陈述；但任何「填入推荐值」之类的可点击入口必须携带**整条路径联合验证过的具体 Input**，找不到见证就不渲染按钮（见决策 34）。
- **解析只有一份实现**：条件语义、场景推导、展示状态的派生都在服务端完成，客户端只渲染（沿用 `docs/dev/loottable.md` 第 7.2 节已确立的边界）。
- **模拟必须调用普通 `LootTable.getRandomItems`**：NeoForge 的 GLM 只在该入口应用，`getRandomItemsRaw` 不会。
- **模拟只能跑在主线程 `END_SERVER_TICK`**：`LootTable.getRandomItems` 与 `LootContextParamFiller` 会触碰非线程安全的 `LegacyRandomSource`。
- **`Probability` 是穷尽 switch 的载体**：新增状态会被编译器强制暴露所有需要更新的位置（存档与网络的穷尽 codec、`ProbabilityFormat`、`maxScenarioProbability` 等），这正是我们想要的。

## 四、技术路线

### 4.1 分层设计与目标结构

| 层 | 目标调整 |
|---|---|
| 编译 | `CompiledLootTable` 增加局部池/条目描述：保留 `weight`、`quality`、`rolls`、`bonus_rolls` 的原始值与"是否可静态求值"程度；事件继续关联路径；额外记录**该表/该子树引用了哪些附魔**，供哈希摘要（决策 35）与旋钮目录（决策 26）共用同一份枚举 |
| 分析 | 新增最小幸运门槛判定：每条路径给出「使权重 > 0」与「使 `bonus_rolls` 触发」所需的最小幸运，**按路径分别保留**（首版的"取多路径并集的最小值"作废，见决策 34）；门槛向上对齐 0.01 网格后用真实公式回验；条件树展开设预算，超限按无约束降级（决策 33） |
| 约束发布 | 新增 `SimulationConstraintCatalog`：由表内容派生**约束描述**而非候选 Input——场景候选（≤32）、工具基座清单、被引用到的附魔及 `0..maxLevel` 范围、旋钮可见性（幸运 / 工具 / 附魔等级 / 抽样次数）、以及"哪条路径引用了哪些旋钮/条件"的静态关系（供「需要条件」的信息性提示）。**不生成任何 Input 组合**（决策 32） |
| 场景 | `SimulationInput`（记录类）= 条件布尔赋值 + `ScenarioParams`（含模拟次数）；**单个 Input 在请求时构造并校验**（拒绝目录未签发的取值），不做批量派生 |
| 执行 | `LootProbabilitySimulationJob` 接受单个 Input；worker 去重键改为 `(tableId, inputKey)`，队列有界，新增每玩家在途计数，并记录失败原因（供 `Unknown(SIMULATION_FAILED)`） |
| 联合见证搜索 | 按需：对指定物品的指定路径，搜索一组使该路径在**幸运、工具、附魔等级、场景四个维度上同时成立**的 Input；带预算，找不到即不渲染可点击入口（决策 34） |
| 持久化 | `LootProbabilityData` 格式 3：per table `{hash, discovery, inputs}`；`discovery`（签名集合、`hasDirectSource`、来源子表、见过的子表入口）挂**表级**、LRU 不淘汰，`inputs`（`inputKey → per-signature 测量值 + 子表入口概率`）按 LRU 淘汰；只存原始测量值，展示状态不落盘；首次触发者与时间只落盘不展示（决策 37） |
| 投影 | `ItemDefinition` 的展示状态由服务端派生并随 `CatalogTableDto` 下发，分**两轴**：可适用性（`NeedsCondition` / `Unreachable` / `Unknown(UNCOVERED)`）× 计算状态（`Measured` / `Unknown(NOT_SIMULATED / SIMULATION_FAILED / EVICTED)`）；表级下发约束描述（沿用"每表只发一次"的既有做法） |
| 客户端 | 网格只读当前 Input，**显示优先级：可适用性状态 → （可展示时）声明触发率 → 否则模拟值**（决策 44）；新增第 4 个 Tab「场景」与**网格页快捷切换下拉**（含缓存标记）；tooltip 给静态信息性提示与（有联合见证时的）可点击推荐；新增一键填充入口与模拟次数档位；偏好存本地 |

### 4.2 关键类型（API 草案）

```java
// 模拟输入身份：条件布尔赋值 + 参数旋钮 + 抽样次数。规范编码后作为缓存键第二段。
// 不变量：conditionOutcomes 按指纹排序、拒绝 null；luck 有限且在有界范围内；拒绝 NaN/Infinity；
// sampleCount 只能取服务端签发的离散档位（决策 39）。
record SimulationInput(Map<String, Boolean> conditionOutcomes, ScenarioParams params) {}

record ScenarioParams(
        float luck,                                   // -5.00 .. 10.00
        ToolOption tool,                              // 基座物品（默认工具 / 条件树引用的物品）
        Map<EnchantmentId, Integer> toolEnchantments, // 读 TOOL 的机制；只放非零项、按附魔 id 排序；0..maxLevel
        int sampleCount) {}                           // 离散档位（建议 1 万 / 5 万 / 10 万），上限 10 万
// 参数只覆盖"从 TOOL 读取"的机制（apply_bonus / table_bonus / tool_enchantment）。
// 读 ATTACKING_ENTITY 装备槽的 enchanted_count_increase / random_chance_with_enchanted_bonus 不在本轮范围：
// 它们只出现在实体掉落表里，而实体掉落表不追踪（见 2.3），因此没有"击杀/抢夺"旋钮；
// 爆炸同理（只对带 survives_explosion 的方块破坏表有意义）。
// sampleCount 是统计口径而不是玩家处境：它进 Input 身份，但每表持久化的 LRU 在"参数组合"这一层
// 竞争（决策 46），否则同一参数的不同精度会把参数本身挤出缓存。

// 由服务端目录签发的预置工具基座。不穷举 ItemPredicate，只列该表条件树与函数树实际引用到的基座物品，
// 附魔等级另由 toolEnchantments 承担。谓词引用的是标签时取该标签的首个成员（决策 45），
// 因此下拉不是"谓词允许的全部物品"——提示里必须同时给出谓词原文，避免玩家以为列表就是全集。
record ToolOption(String id, Component displayName, Component predicateText, /* 构造 ItemStack 的描述 */ ToolSpec spec) {}

// 目录签发的约束描述——只说"能调什么、引用在哪条路径上"，不生成任何 Input 组合（决策 32）。
record SimulationConstraintCatalog(
        List<SimulationScenario> scenarios,          // ≤32，按覆盖路径数降序截断，记录被截断数量
        List<ToolOption> tools,                      // 默认工具 + match_tool 谓词引用的基座物品
        Map<EnchantmentId, IntRange> enchantLevels,  // 该表条件/函数树实际引用到的附魔及 0..maxLevel
        List<ParameterKind> availableKnobs,          // 恒含 LUCK / TOOL / SAMPLE_COUNT（见 2.3 的范围约束）
        List<Integer> sampleCounts) {}               // 离散档位（建议 1 万 / 5 万 / 10 万）

// 静态信息性提示：只说"这条路径引用了什么"，不承诺可达成（决策 34）。
sealed interface PathHint {
    record ReferencesScenario(List<LootConditionInfo> conditions) implements PathHint {}
    // detail 指明引用目标：ENCHANT_LEVEL 时为附魔 id，TOOL 时为谓词描述，其余为 null
    record ReferencesParameter(ParameterKind kind, @Nullable String detail) implements PathHint {}
}
enum ParameterKind { LUCK, TOOL, ENCHANT_LEVEL, SAMPLE_COUNT }

// 联合见证：只在服务端对某条路径搜到"四维同时成立"的 Input 时才存在。
// 任何可点击的「填入推荐值」必须携带它；搜不到就没有这个对象，界面只能渲染静态提示（决策 34）。
record Recommendation(SimulationInput input, double luckLowerBound, boolean luckRangeLimited) {}

// 展示层的概率值。新增第 4 态，并给 Unknown 带上原因——它现在背着"未覆盖 / 规则未解析 / 尚未计算 /
// 计算失败 / 已淘汰"五种来源，只有原因枚举能把它们分开（决策 36）。
// 注意 ProbabilityFormat.format 当前返回 String，新状态需要本地化文案，因此必须新增返回 Component
// 的格式化入口（或让网格自行分派），不能把可翻译文本塞进 String 常量。
sealed interface Probability {
    record Unknown(UnknownReason reason) implements Probability {}
    record Unreachable() implements Probability {}
    record Measured(double lower, OptionalDouble upper) implements Probability {}
    record NeedsCondition(List<PathHint> hints, @Nullable Recommendation recommendation) implements Probability {}
}
enum UnknownReason { UNCOVERED, UNPARSED, NOT_SIMULATED, SIMULATION_FAILED, EVICTED }
```

持久化层需要一个只表达"算没算、算出多少"的窄类型，避免把由静态结构派生的 `NeedsCondition`/`Unreachable` 写进存档：`SimulatedValue` = `Unknown | Measured`。读取时该表按需重建展示状态。**这是本轮唯一一处需要新增类型以隔开两个语义层的改动**，理由是 D2 的根源正是"把不同来源的结论塞进同一个值"。

旋钮可见性规则：幸运、工具与抽样次数恒可见。**附魔等级旋钮按"该表条件树与函数树实际引用到的附魔"逐个生成**（`apply_bonus` / `table_bonus` / `tool_enchantment` 的 `enchantment` 字段），不会出现"没被引用却给控件"的情形；`enchanted_count_increase` / `random_chance_with_enchanted_bonus` 的附魔字段**不参与生成**——它们只出现在实体掉落表里（见 2.3）。**爆炸与击杀/抢夺旋钮不做**：前者只对带 `survives_explosion` 的方块破坏表有意义、后者只对实体掉落表有意义，两类都在追踪范围外。

**但这套"只读表自身条件/函数树"的枚举不够**：`RuntimeLootLinks` 声明的运行时注入边会让**父表**实际产出被注入子表的物品，而原表 JSON 里看不到这条引用。已核实 `SimulationScenarioPlanner` 对 `mud_dredging` 的专门分支同时供给两样东西——`applyMudDredgingTool`(`:125`) 供**参数**（满级钓竿）、`appendMudDredgingScenarios`(`:142`) 给原版 fishing 补**两个场景**。改成"无附魔基准 + 只读表自身条件树"后两样一起丢，注入物连入口都不存在。因此：**凡 `RuntimeLootLinks` 声明的注入边，被注入子表的条件树与函数树一并作为父表约束描述的输入**（决策 42），生成父表的场景候选与旋钮目录；注入物自身仍由模拟期动态发现并保持 `injected=true`，这条既有边界不变。这也是 `docs/dev/loottable.md` 第 10 节把 `RuntimeLootLinks` 定为"新增运行时联动边"扩展点的用法。

### 4.3 物品展示状态的判定规则

在服务端派生。**两个轴，不能合成一个**（决策 36）：先判**可适用性**（静态可证），再判**计算状态**（该 Input 的测量结果是否存在），只有**成功完成**的结果才能生成 `Measured`。首版把"存在一条适用路径"直接等同于 `Measured`，在按需模型下会把"没请求过 / 排队中 / 计算失败 / 被淘汰"全部写成数字。

| 可适用性（静态） | 计算状态 | 状态 | 网格 | tooltip |
|---|---|---|---|---|
| 当前 Input 下有适用路径 | 已完成 | `Measured` | 该 Input 的模拟值 | 「在幸运 L、下列条件满足时，一次抽取至少出现一次」 |
| 当前 Input 下有适用路径，但抽样零命中 | 已完成 | `Measured(0.0)` | **未命中** | 「本次抽样 N 次命中 0 次」+ 当前抽样次数 + 提高次数的提示（决策 40） |
| 当前 Input 下无适用路径，但该路径引用了可调整的旋钮/条件 | 任意 | `NeedsCondition` | 「需要条件」 | 静态信息性提示，逐条列出引用了哪些旋钮/条件；**仅在存在联合见证时**才附可点击的「填入推荐值」（决策 34） |
| 全部路径都不适用，且无法证明其不可产出 | 任意 | `Unknown(UNCOVERED)` | `?` | 「未覆盖：可能需要预置列表之外的输入」 |
| 任意 | 尚未请求 | `Unknown(NOT_SIMULATED)` | `?` | 「尚未计算，点击计算」 |
| 任意 | 排队中或计算中 | `Unknown(NOT_SIMULATED)` | `?` | 「正在计算…」 |
| 任意 | 计算失败 | `Unknown(SIMULATION_FAILED)` | `?` | 「计算失败，将在下次数据包重载后重试」 |
| 任意 | 结果已被 LRU 淘汰 | `Unknown(EVICTED)` | `?` | 「结果已淘汰，点击重新计算」 |
| 规则无法解析（含 paramSet 不允许的参数引用） | 任意 | `Unknown(UNPARSED)` | `?` | 「规则未解析：<条件 id>」或不可用诊断（决策 31） |
| 静态可证明在任何可表示 Input 下都不可产出 | 任意 | `Unreachable` | `0%` | 说明阻断原因 |

（当前可静态判定的不可达：有效权重恒为 0；条件树在布尔层面自相矛盾。）

**「当前 Input 下无适用路径，但该路径引用了可调整的旋钮/条件」是纯静态判定，不需要搜索**：它只问"这条路径的条件树里出现过场景控制类型的条件、`match_tool`，或读附魔/幸运的机制吗"，答案就在编译产物里。**它不承诺可达成**——首版把这句写成"存在某个候选 Input 使其适用"，而那需要"场景 × 基座工具 × 附魔等级赋值"上的可满足性判定：三个维度各自有解**并不等于**同一条路径存在共同解（父路径要求 L ≥ 3、子路径要求 L ≤ 1 时，"幸运维度有解"与"场景维度有解"可以同时为真却没有任何可用的联合解）。因此改为：**静态提示无条件给，可点击的「填入推荐值」必须由联合见证搜索产出**，搜不到就不渲染按钮（决策 34）。

**静态门槛可以无条件展示**，因为它是单路径、可静态证明的陈述：「该路径需要幸运 ≥ 0.34」。算法是先用代数求出 `quality × L > -weight`（使有效权重为正）与 `bonus_rolls` 触发两处的门槛，**向上对齐到 0.01 网格后再用真实公式回验**（`max(floor(weight + quality × L), 0)` 与 `rolls + floor(bonus_rolls × L)`），回验不通过就按 0.01 步进直到通过或触及预算；存在上限或多段不连续区间时追加「（该路径有上限，区间受限）」且不给具体数值（决策 28）。**多条路径的门槛按路径分别保留**，不再像首版那样"取多路径并集的最小值"——合并后的最小值无法指回具体是哪条路径需要它（决策 34）。

### 4.4 数据流

```text
资源快照 ─┬→ 编译（保留 pool/entry 局部语义 + 逐路径最小幸运门槛 + 被引用附魔）
          └→ 引用图（含 RuntimeLootLinks 的运行时注入边）
                ↓
        场景规划（条件布尔候选，≤32，按覆盖路径数降序截断；展开自身有预算，超限按无约束降级）
                ↓
        约束描述（场景候选 + 工具基座 + 被引用附魔及等级范围 + 旋钮可见性 + 逐路径静态引用关系）
          ↑ 注入边把被注入子表的条件/函数树并入此处，父表才有扫描与参数入口（决策 42）
                ↓  ← 到此为止，不生成任何 Input 组合（决策 32）
        基准 Input = 全假条件 + 默认旋钮 + 幸运 0 + 基准档次数 ──→ 启动每表只模拟这一个
                ↓
        玩家切场景/改旋钮/一键填充 ──→ 防抖 400-600ms ──→ C2S 请求（代次, 表哈希, inputKey）
                                                            （页头始终显示当前 Input 与状态）
                ↓
        服务端校验：该 Input 是否由当前目录签发 + 在途/全局限额 ──→ 高优先级队列 ──→ 按需模拟
                ↓
        LootProbabilityData: per table {
            hash,
            discovery { 签名, hasDirectSource, 来源子表, 见过的子表入口 },   ← 表级，LRU 不淘汰
            inputs { inputKey → per-signature 测量值 + 子表入口概率 } }      ← per-Input，LRU 淘汰
                ↓
        服务端派生展示状态（可适用性 × 计算状态）与静态信息性提示
        按需：联合见证搜索 → 有见证才产出可点击推荐（决策 34）
                ↓
        CatalogTableDto（约束描述与发现记录，表级只发一次）→ 客户端网格 + 场景 Tab + 快捷切换下拉
                ↓
        S2C 增量结果（带代次、表哈希与 inputKey）→ 客户端校验后代次不符即丢弃
                                                      → 通过才把网格切到该 Input
```

### 4.5 文件改动清单

**新增**

| 文件 | 职责 |
|---|---|
| `loottable/analysis/LuckGateAnalysis.java` | 从 `weight`/`quality`/`rolls`/`bonus_rolls` 推导**逐路径**最小幸运门槛与已知程度；门槛向上对齐 0.01 网格后用真实公式回验（决策 34） |
| `loottable/simulation/SimulationInput.java`、`ScenarioParams.java`、`ToolOption.java` | 模拟输入身份与参数（含模拟次数档位） |
| `loottable/simulation/SimulationInputKey.java` | `SimulationInput` ↔ 稳定字符串（排序指纹、float 规范化、拒绝 NaN/Infinity、次数只能是签发档位） |
| `loottable/simulation/SimulationConstraintCatalog.java` | **替代原计划的 `SimulationInputCatalog`**：由表内容派生约束描述（场景候选 / 工具基座 / 被引用附魔及等级范围 / 旋钮可见性 / 逐路径静态引用关系），**不生成 Input 组合**（决策 32） |
| `loottable/simulation/PathHintAnalyzer.java` | 从路径的条件树与函数树派生静态信息性提示 `PathHint`（决策 34） |
| `loottable/simulation/RecommendationSolver.java` | 联合见证搜索：在幸运、工具、附魔等级、场景四维上找同时成立的单个 Input；带预算，超限或找不到即返回空（决策 34） |
| `loottable/simulation/PlayerStateProbe.java` | 一键填充的读取端：从玩家实体与所在世界读出可用的参数与条件成立情况，并产出"读不到"清单（决策 41） |
| `loottable/catalog/SimulatedValue.java` | 持久化层的窄概率类型（`Unknown` / `Measured`） |
| `loottable/catalog/Recommendation.java` | 联合见证（含 `SimulationInput` 与门槛信息） |
| `network/payload/c2s/RequestScenarioSimulationPayload.java` | 玩家请求按需模拟某个 Input（携带目录代次、表哈希、inputKey） |
| `network/payload/s2c/SyncScenarioResultPayload.java` | 下发某表某 Input 的增量结果与派生状态（带代次与 inputKey，客户端校验后不合即丢弃） |
| `network/journal/ScenarioSimulationHandler.java` | 服务端处理请求：校验 Input 由当前目录签发、检查在途/全局限额、入队、回包 |
| `network/journal/FillCurrentStateHandler.java` | 服务端处理一键填充请求，回传填好的参数、置为成立的条件指纹，以及未能填充的项 |
| `client/ui/panel/ScenarioPanel.java` | 场景 Tab 的实现，`implements PagePanel` |
| `client/ui/panel/ScenarioQuickSwitch.java` | 网格页的快捷切换下拉：三态缓存标记（已缓存／未计算／计算中）+ 仅失败时出现的标记（决策 43），以及一键填充入口 |
| `loottable/analysis/LootMechanismSupport.java` | 识别战利品表 paramSet 不允许的参数引用（`enchantment_level` 提供器、`enchantment_active_check` 等），产出该表/该路径的不可用诊断 |
| `platform/services/IClientSimulationPreference.java` | 客户端偏好接口（每表选中的 `inputKey`）+ 内存实现。文件实现见决策 29 与第八节——注意 §7.2 的"重进笔记后偏好仍在"要求跨会话保留，因此 P2 必须把它落地，不能只停在内存实现 |

**修改**

| 文件 | 改动 |
|---|---|
| `loottable/analysis/CompiledLootTable.java`、`LootTableCompiler.java` | 保留 pool/entry 的 `weight`/`quality`/`rolls`/`bonus_rolls` 与可静态求值程度；记录本表子树**被引用到的附魔**（供哈希摘要与旋钮目录共用） |
| `loottable/simulation/SimulationScenarioPlanner.java` | `MAX_SCENARIOS` 8→32；`match_tool` 移出 `SCENARIO_CONDITIONS`；截断改为"按覆盖路径数排序后截断 + 记录被截断数量"；**移除泥地打捞的专门分支**（`applyMudDredgingTool`、`appendMudDredgingScenarios`），改由 `RuntimeLootLinks` 的注入边通用派生（决策 42）；展开加预算（决策 33） |
| `mixin/loottable/SimulationContextConditionMixin.java` | 不再拦截 `MatchTool`（工具改由真实参数求值）；`block_state_property` 的拦截保留或同样评估 |
| `loottable/simulation/SimulationProfile.java` | `CATALOG_LUCK` 退化为基准默认值；承载 `ScenarioParams`（含模拟次数） |
| `loottable/simulation/LootContextParamFiller.java` | 按声明 paramSet 的 `allowed` 补填 optional（避免条件恒真/恒假）；与现有填充模型不兼容的 paramSet 明确排除并告警（barter，见 D8） |
| `loottable/simulation/LootProbabilitySimulationJob.java` | 接受单个 Input（而非 Input 子集）；"不适用"与"静态不可达"分离；产出 `Unknown` 的原因；抽样次数取自 Input |
| `loottable/simulation/LootProbabilitySimulator.java` | `SIMULATION_COUNT` 常量退化为基准档位（且不再进表哈希）；新增"按单个 Input 建 job"的入口 |
| `loottable/simulation/LootProbabilitySimulationWorker.java` | 去重键改 `(tableId, inputKey)`；待处理组合有界；每玩家在途计数；记录失败 Input 及其原因 |
| `loottable/catalog/Probability.java` | 新增 `NeedsCondition` 第 4 态；`Unknown` 带 `UnknownReason`（会触发全仓穷尽 switch 的编译错误，逐处处理是预期流程） |
| `loottable/catalog/LootTableCatalog.java`、`CatalogTableDto.java` | 物品带两轴展示状态与信息性提示；表带约束描述（沿用 `ScenarioAssumptions` 的表级单次下发形态） |
| `loottable/simulation/ProbabilityFormat.java` | 新增返回 `Component` 的入口以承载本地化文案；`Measured(0.0)` 改为「未命中」；`NeedsCondition` 不再走 `String` 路径 |
| `loottable/analysis/LootFunctionHandlers.java` | `apply_bonus` 由附魔等级推导数量分布（替换"只显示附魔名与 formula 名"的现状）。`enchanted_count_increase` **不在范围**——实体掉落表专用（见 2.3） |
| `loottable/analysis/LootConditionHandlers.java` | `table_bonus` 补概率表与附魔名（现在在注释里自认参数未展示，不补则「需要条件」无法指名附魔）。`random_chance_with_enchanted_bonus` **不在范围**——实体掉落表专用 |
| `journal/catalog/ArchaeologyJournalServerCatalog.java` | `SIMULATION_CACHE_VERSION` v15→v16；哈希输入**去掉模拟次数**（改由 Input 身份承担）并**加入被引用附魔定义摘要**（决策 35）；启动只跑基准 Input；消费目录构建后的脏标记 |
| `world/LootProbabilityData.java` | `FORMAT_VERSION` 2→3；结构改为 per table `{hash, discovery, inputs}`；发现记录表级、LRU 只淘汰 `inputs`；LRU 按**参数组合**计数（决策 46）；首次触发者与时间只落盘不展示 |
| `network/ModPayloads.java` | 注册新 C2S/S2C（两端自动遍历，无需改平台注册代码）；NeoForge 协议版本 `"4.3"` 需同步升级 |
| `platform/ServerLootTableConfigManager.java` | 消费"数据包重载"脏标记（D9 的落地处） |
| fabric / neoforge 平台入口 | **新增数据包重载监听**：Fabric 用 `ResourceManagerHelper`（SERVER_DATA），NeoForge 用 `AddReloadListenerEvent`；监听器只置脏标记，重建仍走现有 tick 路径，避免在重载回调里同步跑全量构建 |
| `client/ui/panel/RightPageContainer.java` | `Tab` 新增 `SCENARIOS`，同步导航按钮与页数逻辑 |
| `client/ui/screen/JournalViewModel.java` | 移除 `maxScenarioProbability`（`:589`）作为网格默认的用法，改读当前 Input；`declaredChances()` 的用法改为条件性（决策 44） |
| `client/ui/panel/ItemGridPanel.java`、`client/ui/support/JournalTooltipBuilder.java` | 落实显示优先级链（可适用性状态 → 声明触发率 → 模拟值，决策 44）；渲染「未命中」、`Unknown` 原因、两类信息性提示与（有见证时的）可点击推荐；挂上快捷切换下拉与一键填充入口 |
| `assets/unsuspiciousblock/lang/en_us.json`、`zh_cn.json` | 新增全部文案 key（场景 Tab 名、状态词与 `Unknown` 各原因的文案、信息性提示、旋钮标签、抽样次数档位、缓存标记、一键填充结果与未填充项、校验提示） |

`pan/PanningLootService` 与淘洗结算不受影响。

### 4.6 分阶段实施

| 阶段 | 可独立验收的交付 |
|---|---|
| P0：诚实化与失效链（**不依赖 `SimulationInput`，不依赖新协议**） | 数据包重载监听（D9）；哈希输入加入被引用附魔定义摘要并区分两种保证范围（决策 35）；"不适用"与"静态不可达"拆分（D2），`Probability` 加 `NeedsCondition` 第 4 态与 `Unknown(Reason)`（决策 36）；「需要条件」文案与**静态信息性提示**（无按钮、无搜索，决策 32/34）；显示优先级链与 `DeclaredChance` 条件性保留（决策 44）；零命中改为「未命中」（决策 40；P0 里次数仍是基准档位，tooltip 只报"本次抽样 N 次命中 0 次"，可调档位在 P2 才出现）；网格不再显示跨场景最大值（D1）；参数填充对齐声明 paramSet 的 `allowed`，并排除会抛异常的 barter（D8）；战利品表不可用机制的显式识别与降级诊断（决策 31）；缓存与协议版本升级。**此阶段末尾：网格上不再有任何一个会误导玩家为"已获得"或"不可能"的数字，不再有一张表因机制不可用而整体静默失败，改 JSON 后 `/reload` 会真正触发重算。** |
| P1：模拟输入模型与按需管线（**第一次落地协议**） | 编译层保留 `weight`/`quality`/`rolls`/`bonus_rolls` 与被引用附魔；`LuckGateAnalysis` 逐路径门槛（0.01 对齐与真实公式回验、区间受限两段式，决策 28/34）；`SimulationInput`/`ScenarioParams`（含**模拟次数档位**，决策 38/39）与 `SimulationInputKey`；`SimulationConstraintCatalog` 只发布约束（决策 32）；条件树展开预算（决策 33）；worker 去重键 `(tableId, inputKey)`、限流、代次/表哈希/inputKey 校验；缓存格式 3（表级发现记录 + per-Input 测量值 + 按参数组合计数的 LRU，决策 37/46）；新 C2S/S2C；启动只跑基准 Input |
| P2：交互与联合见证 | 参数区（工具/附魔等级/幸运/**抽样次数档位**）；幸运输入框 + 建议档位；`RecommendationSolver` 联合见证搜索与可点击「填入推荐值」（决策 34）；场景 Tab + **网格页快捷切换下拉与三态缓存标记**（决策 43）；防抖自动请求 + `[计算]` 按钮兜底、页头始终显示当前 Input 与状态；**一键填充当前状态**（含读不到清单，决策 41）与 `PlayerStateProbe`；**注入边参与父表约束描述**（决策 42，泥地打捞五件直接物品在此才获得参数入口）；客户端偏好的文件实现（决策 29 的尾巴，见第八节）；硬上界 32 与降级提示 |
| P3：保真与兼容 | 原版 `minecraft:gameplay/fishing` 的群系条目全覆盖验收；整合包注入（Fabric `MODIFY` / NeoForge GLM）与动态条目的 Input 归属；双平台实机验收；`docs/dev/` 文档同步 |

P0 至 P2 是解决本次问题的最小完整交付。**调度上有两条硬约束**：P0 必须先落地（它把"哪些数字在骗人"清零，且独立于 Input 模型，可单独验收）；P2 的一切交互都要走 P1 的协议，因此 P1 的交付物不能推到 P2 之后——首版把可点击 hint 放 P0、把幸运输入框放 P1，却把 `SimulationInput`、请求协议与按需调度放 P2，是本次修正的第四类问题（决策 48）。

开始代码实施前依项目规则与用户确认是否分点执行；**执行级的拆点清单在开工时另起一份，不写进本文**（决策 47）。

### 4.7 文档同步

实施完成后更新 `docs/dev/loottable.md`（场景机制、参数填充、缓存格式、**失效保证的实际覆盖范围与已知残余**、`SIMULATION_CACHE_VERSION`、`RuntimeLootLinks` 注入边参与约束派生、`Unknown` 的原因枚举、`Measured(0.0)` 的展示口径）、`docs/dev/client-ui.md`（第 4 个 Tab、快捷切换下拉与缓存标记、显示优先级链、一键填充）、`docs/dev/network.md`（新 payload、代次/表哈希/inputKey 校验、协议版本；并核对 `:171` 关于 `/reload` 广播新快照的描述——它在 P0 修好 D9 后才成立）、`docs/dev/mixin.md`（`SimulationContextConditionMixin` 的拦截范围变化）；`docs/dev/config-integrations.md` 若新增配置项同步；[文本格式规范](../dev/tooltip.md) 若参数展示改动其约定则同步；淘洗的相关解释按需同步 `docs/dev/panning.md`。全部新增 GUI 文案同步 `en_us.json` 与 `zh_cn.json`。本文最后补第九节并按项目规则归档。

## 五、决策记录

| # | 决策点 | 结论与理由 |
|---|---|---|
| 1 | 本轮首要目标 | **诚实与表达力优先**（用户裁定）。取消 8 上限本身不省启动时间（内置表都不到 8 个场景），省成本的是"不再全量列举"；而"网格显示最有利组合的最大值"是正在误导玩家的缺陷，修它必然引入"哪个场景是基准"的概念，每表只跑基准随之自然成立。否决"启动成本优先"与"两者并重"。 |
| 2 | 基准场景的选择 | **条件全部不成立的场景**（用户裁定，否决了作者推荐的"覆盖路径最多的最小布尔赋值"）。配一条展示规则：不满足条件的条目不显示概率、显示「需要条件」并在 tooltip 标注所需条件。理由：不去猜"哪个场景最有代表性"，而是规定"数字只在基准条件下有意义"，条件门槛一律用文字表达。 |
| 3 | 场景的表示能力 | **条件维度只做布尔赋值**。条件谓词按指纹独立成维，取值只有成立/不成立；群系、时间、分数这类"取值型"维度不展开，改在场景卡片上显式写出假设。否决"按条件谓词取值展开场景"（会真正爆炸）。注意：附魔等级**不受**这条限制——它不是条件谓词而是运行时输入，见决策 26。 |
| 4 | 场景列表页的落点 | **右页新增第 4 个 Tab「场景」**。理由：卡片能同时承载条件描述、计算状态与概率摘要，横向切换条做不到解释"这个场景为什么值得看"。代价是 `Tab` 枚举硬编码三处需同步改。 |
| 5 | 幸运与场景的轴关系 | **一个记录类、界面两处呈现**。`SimulationInput` 统一承载条件布尔与 `ScenarioParams(luck, tool, toolEnchantments, sampleCount)`，缓存键是整体；界面上一部分用场景卡片选条件、一部分用参数区填值。（参数集合在第四轮随旋钮收窄，见决策 49。） |
| 6 | 可自填参数白名单 | **幸运 + 工具 + 爆炸 + 击杀（含抢夺等级）**（用户裁定）。四者都是玩家真实能选、且直接改变掉落结果的旋钮；每个都是有界枚举或有界数值。否决"只开放幸运"与"再放开伤害源与实体状态"（后者与布尔场景维度重复）。**第四轮收窄（见决策 49）：爆炸与击杀/抢夺移除**——它们分别只服务于方块破坏表与实体掉落表，两类都不在追踪范围内；现白名单为 **幸运 + 工具 + 附魔等级（决策 26）+ 抽样次数（决策 38）**。 |
| 7 | 幸运的控件形态 | **输入框 + 建议档位按钮**。建议档位由静态门槛算出，点即填入。否决"只给固定档位"（问不到"我的 1.7 幸运是多少"）与"只给自由输入框"（玩家猜不到 0.34 这个数有意义）。 |
| 8 | 工具与 `match_tool` 的关系 | **工具真实求值，`match_tool` 移出 `SCENARIO_CONDITIONS`**（用户裁定）。理由：现状是"布尔说匹配成功、真实工具却不匹配"的持续矛盾，且时运曲线与布尔无关。布尔维度从 8 类降到 7 类。代价是 `ItemPredicate` 无法穷举，只能用预置工具列表，其余谓词归 `Unknown`。 |
| 9 | 参数填充的三个缺口 | **本轮一起修**（用户多选全选）：按 `allowed` 补填 optional、假玩家配主手武器、修 barter 抛异常。**第四轮收窄（见决策 49）：** 前两个缺口所属的 `block`/`entity` 表都不在追踪范围，已随 D6/D7 一并作废；本轮只修 **barter 抛异常（D8）**，并把"按 `allowed` 补填 optional"作为通用正确性规则保留（理由不再是那两类表，而是"路径排除 ≠ 类型排除"）。 |
| 10 | barter 类 paramSet 的修法 | 作者自决：**明确排除出收录并输出一次告警**，不造假猪灵。理由：`PIGLIN_BARTER` 语义要求 THIS_ENTITY 是猪灵，用假玩家填充仍然失真，诚实地不追踪优于给出错误概率。 |
| 11 | 玩家偏好保存位置 | **客户端本地偏好**（用户裁定）。只影响该玩家界面，不写存档、不同步他人；服务端只保存"已算过的 Input 组合"这份全服共享缓存。 |
| 12 | 缓存与失效模型 | 键为 `(表哈希, SimulationInput)`，按需结果持久化；每表最多持久化 8 个 Input，超出按 LRU 淘汰。关键洞察：哈希已含整棵子树的资源栈摘要与编译产物摘要，所以"表变即失效"是**自动**的。否决"只留内存"（重启即失）与"子表级细粒度失效"（现有哈希是整表级的，要做到那粒度必须重做哈希模型）。**本条的后半句（"自动失效"的范围）已被决策 35 收窄，第一条（8 个 Input）已被决策 46 修正——以那两条为准。** |
| 13 | 网格「需要条件」的判定 | **新增独立状态**（用户裁定），不按 `hasConditions()` 客户端判定（那会让"切到满足条件的场景后也看不到数字"，与需求冲突），也不只改展示文案（会把真正的静态不可达也说成"需要条件"）。 |
| 14 | 硬上界 | **32**（用户裁定）。超出按"可适用获取路径数"降序截断，被截断的场景不进列表，其覆盖的条目显示 `?` 并在场景页顶部提示"N 个场景因超出上界未列出"。 |
| 15 | 按需模拟的触发与限流 | **任何玩家可触发 + 有界**（用户裁定）：结果全服共享，每玩家在途请求 ≤2，全局待处理组合 ≤32，服务端只接受当前目录签发的 `SimulationInput`（拒绝任意自造参数）。理由：查概率是正常信息需求，但模拟跑在主线程 tick 上，连点刷爆预算是真实攻击面。 |
| 16 | 选中场景后的行为 | **跳回物品网格**（用户裁定），页头显示当前组合与 `[切换]` 入口；场景卡片只显示条件描述与计算状态，未算过的显示「点击计算」。否决"卡片内联展开"（重复一份列表渲染）与"两者都要"（两份渲染路径都要维护）。 |
| 17 | 基准 Input 的旋钮默认值 | **沿用现有默认**（用户裁定）：钓鱼表=无附魔钓竿，其余=无附魔钻石镐；幸运=0；抽样次数=基准档位（1 万）。与现有行为连续，且无附魔镐让时运曲线从 0 级开始，与「幸运 0 基准」自洽。代价是需要剪刀/斧头类 `match_tool` 谓词的条目在基准下显示「需要条件」，等玩家自己选对工具。（原列的"爆炸=关闭、击杀=非玩家击杀"已在第四轮随旋钮移除，见决策 49。） |
| 18 | 旧计划文档的处理 | **合并为新文档**（用户裁定）：旧的移入本地归档并标注已被取代。理由：两份文档都声称对 `SimulationProfile`/场景键/缓存格式有权威会让后来人按错的那份改。 |
| 19 | 按需模拟的触发与限流（补问留档） | 作者在首轮质询的提问清单中遗漏了这一项，改写成工具提问时只问了四项，直到第三轮才发现并补问，答案见决策 15。留档以提醒后续审阅者：**不是所有遗漏都会被发现**。 |
| 20 | 静态幸运门槛的范围 | **只做最小门槛判定**，不做完整区间代数。否决"完整区间代数"（工作量最大且内置数据用不到）与"不做门槛"（要玩家盲试数值，且与已批准的旧计划结论相左）。 |
| 21 | 展示状态由谁派生 | 作者自决：**服务端派生并随 DTO 下发**。理由：沿用 `docs/dev/loottable.md` 第 7.2 节确立的"解析只有一份实现、客户端不推断语义"边界。 |
| 22 | 持久化与展示的概率是否同一类型 | 作者自决：**拆开**。持久化用窄类型 `SimulatedValue`（`Unknown`/`Measured`），展示层 `Probability` 增加 `NeedsCondition`。理由：D2 的根源正是把不同来源的结论塞进同一个值。 |
| 23 | 旋钮可见性 | 作者自决：**由表声明的 paramSet 的 allowed 集合决定**，避免给出对当前表毫无作用的旋钮。**第四轮收窄（见决策 49）**：applicable 的旋钮只剩 幸运 / 工具 / 附魔等级 / 抽样次数，其中前三个的可用性按上句判定，附魔等级另按"该表实际引用到的附魔"逐个生成。 |
| 24 | 已核实的原版事实（留档纠偏） | `random_chance` 不吃幸运；`random_chance_with_looting`/`looting_enchant`/`copy_nbt`/`smelt` 在 1.21.1 不存在；`attacking_player` 映射到 `LAST_DAMAGE_PLAYER` 而非 `ATTACKING_ENTITY`；`ENCHANTMENT_LEVEL`/`ENCHANTMENT_ACTIVE` 不用于战利品表上下文。这些纠正了旧计划与技术记忆中的多处偏差。 |
| 25 | 原有布尔 `luckAffected` 标记 | 保留其"同池也受影响"的含义，但改由最小门槛分析派生，不再作为"是否可能受幸运影响"的唯一门面。 |
| 26 | 工具附魔等级的控件形态 | **按每张表实际引用到的附魔给 0..maxLevel**（用户裁定）。前置事实经源码核实：等级是函数/条件运行时从 `TOOL` 或 `ATTACKING_ENTITY` 装备槽读的输入，vanilla 全链路不裁剪到 `maxLevel`（唯一边界 0..255），项目也已有 `SimulationScenarioPlanner.toolWithEnchantment` 先例。因此它是**参数而非维度**——首版沿用旧计划的"压成满级单点以避免笛卡尔积"的顾虑不成立（那只在把它当条件维度时才成立）。这是本轮对首版的一处自我修正。 |
| 27 | 幸运的合法范围 | **-5.00 ~ 10.00，精度 0.01**（用户裁定）。依据：原版幸运效果每级 +1，海之眷顾与时运由附魔等级推高，实际玩家的有效幸运罕见超过 10；下界 -5 兼顾整合包减益与负 quality 路径。非法值（NaN / Infinity / 超界）在客户端与服务端双重拒绝并提示。否决"0~5"（挡住负幸运，而负 quality 条目本来就有上限，玩家反而问不到）与"不设上界"（缓存键取值无界）。 |
| 28 | 负 quality 上限与多段区间的降级展示 | **门槛 + 受限说明两段式**（用户裁定）：主行给可确定的一侧「需要幸运 ≥ x」，若存在上限或多段不连续区间则追一行「（该路径有上限，区间受限）」且不给具体数值。否决"完整区间代数"（工作量最大且内置数据用不到）与"只写需要幸运"（要玩家盲试）。 |
| 29 | 客户端偏好的落地方式 | **先定接口 + 内存实现**（用户裁定）。经核实项目没有客户端配置基础设施（现有配置全是服务端的 `ModConfigSpec` / `serverconfig`），而 `common/` 不得引用平台类，文件存储必须走 Services SPI。本轮先定 `platform/services/` 的接口并给内存实现，fabric/neoforge 的文件实现推迟到实机验收确认需要时再补——接口定下后补实现不影响调用方。 |
| 30 | 附魔等级相关的参数展示 | **本轮一起补**（用户多选全选）：`apply_bonus` 由等级推导数量分布、`table_bonus` 补概率表与附魔名。理由：不补的话「需要条件：工具需带 XX 附魔」无法指名附魔，且工具/附魔等级调完之后玩家看不出数量为何变化。**第四轮收窄（见决策 49）**：`enchanted_count_increase` 与 `random_chance_with_enchanted_bonus` 移除——它们读的是 `ATTACKING_ENTITY` 的装备槽，只出现在实体掉落表里，不在追踪范围。 |
| 31 | 战利品表不可用的机制 | **显式识别并降级**（用户裁定）：解析层识别 `ENCHANTMENT_LEVEL` / `ENCHANTMENT_ACTIVE` 这类"表 paramSet 不允许"的参数引用，标为不可用并输出一次性诊断，而不是让整表在 `getRandomItems` 里抛 `IllegalArgumentException` 后静默失败（失败表不自动重试，玩家只会看到满屏 `?`）。否决"强行补填这两个参数"——它们在真实战利品抽取时并不存在，补填等于造出游戏里不可能发生的精确概率。 |
| 32 | 候选 Input 的生成方式 | **目录只发布约束，不枚举候选 Input**（用户裁定，采纳评审意见）。原计划的"场景 × 基座工具 × 附魔等级赋值"是乘积：32 的上限只约束其中一维，6 附魔 × 4 档就是 4⁶ × 32 = 131,072 个组合，而缓存 LRU 限制的是结果数量、**限制不了这部分计算**。改为：目录发布约束描述，单个 Input 在请求时构造并校验。原 `SimulationInputCatalog` 的枚举职责作废，改为 `SimulationConstraintCatalog`。 |
| 33 | 条件树展开与推荐搜索的预算 | **各设独立预算，超限返回未知**（用户裁定，采纳评审意见）。`requirementsFor` 对 `all_of` 走 `combineAnd` 的叉乘、`any_of` 累加，因此"`all_of` 嵌多个 `any_of`"的条件树会在 32 的截断**之前**就指数膨胀——场景上限约束的是展开的**结果数量**，不是展开的**过程成本**。超预算的路径按"无约束"降级（与 `LootConditionFingerprint.isStable` 失败时的既有降级同型），并汇总告警一次。 |
| 34 | 可达成性的证明强度 | **静态提示无条件给，可点击推荐必须携带整条路径的联合见证**（用户裁定，采纳评审意见）。首版让场景、工具、幸运三个维度独立判定后合并，而**分别有解不等于同一条路径存在共同解**（父路径要求 L ≥ 3、子路径要求 L ≤ 1 时，两个维度各自有解却无可用的联合解）。改为：`NeedsCondition` 只陈述"该路径引用了哪些可调旋钮/条件"，可点击的「填入推荐值」由 `RecommendationSolver` 产出、搜不到就不渲染按钮。幸运门槛**按路径分别保留**（首版"取多路径并集的最小值"作废——合并后的最小值无法指回是哪条路径需要它），求出的门槛**向上对齐 0.01 网格后用真实公式回验**（1/3 → 0.34，回验不通过则步进到通过或触预算）。 |
| 35 | 哈希失效的保证范围 | **并入被引用附魔的定义摘要，并把保证拆成两句话**（用户裁定，采纳评审意见）。问题不在"修不修 D9"，而在于修 D9 只解决"哈希**会不会被重算**"，不解决"重算出的哈希**是否足以区分新旧**"。实际覆盖范围是表 JSON 原文 + item tag 展开 + 物品签名集合，**覆盖不到附魔定义**：`unsuspiciousblock:mud_dredging` 的 `max_level` 不在任何战利品表 JSON 里，却决定模拟用的满级工具与等级控件范围（决策 26）。本轮把子树编译产物里五个读附魔的机制的 `enchantment` 字段所指附魔的定义并入摘要；其余外部注册表依赖列为已知残余写入 `docs/dev/loottable.md`。biome tag 成员与 damage type 定义经核实不受影响，不列入残余。 |
| 36 | 「未计算」「未覆盖」等状态的表示 | **`Unknown` 带原因枚举**（用户裁定，采纳评审意见）。首版把"存在一条适用路径"直接等同于 `Measured`，而在按需模型下该 Input 可能尚未请求、正在排队、计算失败或已被淘汰。改为**两轴判定**：先判可适用性（静态可证），再判计算状态，只有**成功完成**的结果生成 `Measured`；`Unknown(Reason)` 的 `Reason ∈ {UNCOVERED, UNPARSED, NOT_SIMULATED, SIMULATION_FAILED, EVICTED}`，网格一律 `?`、tooltip 分述。否决"新增第 5 态"（网格要给不同字形，而"点计算"的入口本来就在参数区/场景卡片上）与"平行的 `SimulationStatus` 字段"（两轴可能互相矛盾，需额外维持一致性）。请求与回包另须携带**目录代次 + 表哈希 + inputKey**，代次不符即丢弃，防止切参数或 `/reload` 后旧结果覆盖当前界面。 |
| 37 | 跨玩家共用与存储分层 | **按内容去重；世界数据不按 uuid 分区；偏好留本地**（用户裁定；"不分区"否决了作者先前提问中保留的选项）。缓存键里不出现玩家身份，任何 Input 只要 `SimulationInputKey` 相同就复用同一份结果：模拟输入相同则统计口径相同（**不承诺逐位相同**——模拟用服务端共享 `RandomSource`，缓存的语义本就是"一份抽样估计"）。用户原本想要的"标记玩家名"因此失去隔离用途，改为**首次触发者与时间的落盘记录、不展示**（用户二次裁定，否决了作者推荐的"tooltip 显示"）。玩家偏好留客户端本地（沿用决策 11），而 §7.2 要求"重进笔记后偏好仍在"，因此决策 29 的内存实现不够，P2 必须补文件实现。 |
| 38 | 模拟次数是否进 Input 身份 | **进**（用户裁定）。它现在既是被写进表哈希的常量（`computeTableHashes` 里的 `getSimulationCount()`）又是统计口径，一旦可调必须从表哈希里搬出来，成为 Input 的一维。否决"只作重算精度"（同一参数在高低次数间来回切会看到不同数字却无从解释）与"仅基准可调次数"（"先调到幸运 5、再想提高精度"做不了）。 |
| 39 | 模拟次数的档位与上限 | **离散档位，上限 10 万**（用户裁定）。建议档位 1 万 / 5 万 / 10 万（1 万约 50ms 实测，10 万约 0.5s，跨 tick 续跑可接受）。作者曾建议上限 100 万，用户改为 10 万以减轻服务端压力；档位与上限都是可调实现参数。不做自由输入，避免每表 LRU 被同一参数的不同精度稀释。 |
| 40 | 抽样零命中的展示 | **网格「未命中」，tooltip 报抽样次数并提示提高次数**（用户裁定，措辞经用户修正）。`<0.01%` 的旧写法作废——它等于声称 p < 1/N，而 n=10000 时真实概率为 0.01% 仍有约 **36.8%** 概率零命中，与"没算到不能显示成不可能"是同一类错的缩小版。作者曾建议 tooltip 附 95% 置信上界（约 0.03%），用户选择只报抽样事实，并要求网格文案简短以避免截断。 |
| 41 | 一键填充玩家当前状态 | **做，且填参数与场景两层**（用户裁定，**推翻本文档首版 §2.3 的"不做"，即现行的 §2.4**）。首版把"读当前群系/手持工具/是否在水中"整体列为不做；现在改为不做**自动**选场景，但提供玩家主动触发的一键填充：读手持物品 → 工具与附魔等级；读位置与所在群系标签 → 命中的 `location_check` 指纹；读世界时间/天气、是否在水中 → 对应指纹。**读不到的项填得动就填、逐条列出未填**：按"不成立"填会把"读不到"伪装成"确定不成立"（否决），一律清空会把玩家刚填的参数一起丢掉（否决）。已知读不到的三处：`in_open_water` 属于鱼获浮标而非玩家（需 `player.fishing`）、`attacker` 对尚未死亡的怪无意义、`damage_source_properties` 与玩家打算怎么杀未必一致。 |
| 42 | 运行时注入的参数与场景发现 | **通用化 `RuntimeLootLinks` 的注入边**（用户裁定，采纳评审意见，落 P2 而非 P3）。已核实规划器的泥地打捞专门分支**同时供给两样东西**：参数（`applyMudDredgingTool` 换满级钓竿）与场景（`appendMudDredgingScenarios` 给原版 fishing 补两个场景）。改成"无附魔基准 + 只读表自身条件树"后两样一起丢，注入物连入口都不存在。改为：凡注入边声明的被注入子表，其条件树与函数树一并作为父表约束描述的输入；注入物自身仍由模拟期动态发现并保持 `injected=true`。这也是 `docs/dev/loottable.md` 第 10 节指定的扩展点用法。否决"保留专门分支"——那只是把耦合挪个位置。 |
| 43 | 请求触发方式与缓存标记 | **防抖自动 + 显式按钮兜底；下拉三态 + 失败标记**（用户裁定）。参数或场景改动后停手 400-600ms 自动请求，同时始终提供 `[计算]` 按钮；**页头始终显示当前展示的 Input 与状态**（按需模拟可跨 tick 续跑，不显示状态就是"点了没反应"）。网格页快捷切换下拉每项标 已缓存／未计算／计算中，另加只在失败时出现的"上次失败"标记（失败表不自动重试，玩家需要知道点了也白点、得等重载）。"已淘汰"不单列标记——它对玩家没有可操作差异（都是点一下），做成 tooltip 说明。 |
| 44 | `DeclaredChance` 与模拟值的显示优先级 | **条件性保留声明触发率**（用户裁定）。优先级链固定为：**可适用性状态 →（可展示时）声明触发率 → 否则模拟值**。即当前 Input 的模拟状态可展示（`Measured` / `Unreachable` / `Measured(0.0)`）时，网格继续显示声明触发率——保留 `docs/dev/loottable.md:379` 当初把它放网格的理由（`random_chance` 类条目的模拟值常是 `?` 或未命中，声明值反而是唯一有信息量的数字）；状态是 `NeedsCondition` / `Unknown(NOT_SIMULATED)` 时网格不显示数字、只显示状态词，声明值进 tooltip。tooltip 里两者始终并列并注明各自口径。 |
| 45 | 工具基座清单对标签谓词的处理 | **取标签首个成员**（用户裁定，否决了作者推荐的"展开全部成员"）。代价已被明确接受："首个成员"是取决于数据包成员顺序的实现偶然，可能造成"下拉里有金斧但没有铁斧"这类难以解释的缺失。因此补一条要求：`ToolOption` 必须同时携带**谓词原文**，提示里写清下拉不等于谓词允许的全集；按决策 34，找不到联合见证的条目只渲染静态提示、不渲染按钮——在"取首个成员"下未列入下拉的成员必然找不到见证，于是行为是诚实的降级而不是点了不动的死按钮。 |
| 46 | 每表可持久化的 Input 上限 | **按参数组合计数**（用户裁定，修正决策 12 的"8 个 Input"）。LRU 在**参数组合**这一层竞争：每个参数组合（不含抽样次数）最多保留全部次数档位，参数组合数上限 8，实际条目 ≤ 8 × 档位数（≈24）。理由：换参数是换**问题**，换次数是换**答案的精度**，高精度答案不该把问题本身挤出缓存。存储代价可控（24 条 per-signature 比例值）；8 与档位本身仍是可调实现参数。 |
| 47 | 本规划的粒度与终点 | **本文只补决策与阶段归属，执行级拆点待开工另起清单**（用户裁定）。四块新功能（可调次数、一键填充、网格页快捷切换、缓存状态标记）与两项架构变更（次数进 Input 身份、按内容去重 + 偏好本地）并入现有阶段的归属，不展开实施细节。否决"本文写到执行级"（决策与实施混住正是上一版被指出的问题）与"现在就拆文档"（实施前的细纲会持续与代码漂移）。 |
| 48 | 阶段划分的重排 | **四阶段重切**（用户裁定，采纳评审意见）。首版的 P0 要交付可点击 hint、P1 要交付幸运输入框，而它们依赖的 `SimulationInput`、请求协议与按需调度都在 P2——"可独立验收"不成立。重切为：P0 诚实化与失效链（不依赖 Input 模型，可单独验收）、P1 输入模型与按需管线（第一次落地协议）、P2 交互与联合见证、P3 保真与兼容。用户另要求"计划内容继续向后规划、执行时可拆更细的点"，执行侧按决策 47 处理。 |
| 49 | 追踪范围的明确与旋钮收窄 | **明确不做方块破坏表与实体掉落表；陶罐（`pots/`）是唯一例外；爆炸与击杀/抢夺旋钮随之移除**（用户裁定，第四轮）。范围由**路径前缀**划定：默认追踪规则（`ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES`，`platform/services/ILootTableConfig.java:25-36`）不含 `blocks/`、`entities/`，且管理页对这两个前缀一律拒绝（`journal/tracking/RecentLootTableService.java:27-30`）；`pots/` 被显式列入，因为陶罐虽是方块破坏掉落，本质却是"没有 UI 的容器"，其内容正是本模组要记录的对象。据此删除 D6/D7——它们指的证据表（`blocks/pottery_wheel.json` 是内置表里唯一带 `survives_explosion` 的）都在范围外，而被追踪的 `gameplay/panning/*`、`gameplay/fossil_hunter/*` 虽声明 `minecraft:block`，条件只用到 `random_chance`。决策 6 的参数白名单收窄为 **幸运 + 工具 + 附魔等级 + 抽样次数**。否决"保留旋钮以备将来"——没有消费者的旋钮就是给玩家看的噪声。**遗留边界（留待实施时决定）**：排除目前是"默认值不含 + 管理页拒绝"的组合，用户手工在配置文件里添加 `blocks/`/`entities/` 前缀仍可让它们进入目录（`loottable/catalog/LootTableNames.patterns()` 只按配置前缀匹配、不额外校验）；是否在匹配层加一道硬排除（例如与 `RecentLootTableService.isSupported` 共用同一判定）需在实施时定。 |

## 六、风险与限制

| 风险 | 说明 | 处置 |
|---|---|---|
| 网格可看性下降 | 基准取全假场景后，凡条件门槛在默认值下不成立的条目都不再显示数字。内置表里受影响最明显的是泥地打捞表——**它的全部条目都受池级 `tool_enchantment` 约束，五件直接物品因此也需要「工具带泥地打捞附魔」，只有两个子表入口额外需要开放水域** | 这是刻意取舍：靠场景 Tab、静态信息性提示，以及**找到联合见证时**出现的可点击推荐补偿（决策 34）。实机验收须确认玩家能在两步内看到想要的数字 |
| 缓存版本升级后数字可能与上一版不同 | 统计口径变化（场景 / 参数策略、参数填充对齐 `allowed`）会让部分表的数值变化。**注意本项已不含原 D6/D7 所指的陶轮表与实体表**——它们不在追踪范围（见 2.3），其数值从不参与展示 | 在 P0 明确记录并要求实机核对；缓存版本 v15→v16 一次性失效，不做数值迁移 |
| 工具预置列表无法穷举 `match_tool` 谓词 | 谓词可含任意物品、标签、附魔、组件、数量；且决策 45 让标签只取首个成员，下拉**必然不是**谓词允许的全集 | 用真实 `ItemPredicate.test` 对预置工具求值；`ToolOption` 同时携带谓词原文，提示里写清下拉不等于全集；按决策 34，找不到联合见证的条目只渲染静态提示、不渲染按钮——"下拉里没有"因此是诚实的降级，而不是挂了按钮却点不动 |
| 参数化取值的限制对玩家可能反直觉 | 玩家无法表达"我在丛林"，只能选"丛林条件成立"这个场景 | 场景卡片显式写出假设文本；**一键填充**（决策 41）把玩家此刻的处境直接映射为指纹成立情况，是这个限制的主要缓解手段；无法映射的条件保持不填并在结果里列出 |
| 低概率始终抽不到 | 1 万次抽样对极小概率仍然未命中；真实概率为 0.01% 时仍有约 36.8% 概率零命中 | 网格显示「未命中」、tooltip 报当前抽样次数并提示提高次数（决策 40）；不自动追加抽样、不伪造上界。提高次数由玩家在离散档位里选（上限 10 万），因此改善是**可见且可控**的 |
| 玩家自填数值导致缓存与队列增长 | 自由 float、抽样次数档位，以及参数组合 | 幸运有界（-5..10、精度 0.01）、抽样次数只取离散档位且上限 10 万、每玩家在途 ≤2、全局待处理 ≤32、每表**按参数组合**计数上限 8（实际条目 ≤ 8 × 档位数，决策 46） |
| 场景 Tab 在多数表上信息量低 | 内置 13 张表里 11 张只有 1 个场景 | 该 Tab 定位为「模拟条件页」，参数区对每张表都有意义，不会出现空页面 |
| 主线程 tick 占用 | 单场景约 50ms（用户实测），超过 15ms 软预算，一个场景至少跨 4 个 tick | 沿用软预算与跨 tick 续跑；基准每表只跑一个，按需部分由限流兜底 |
| 静态分析与运行时表不一致 | 改 JSON 后未重载，或整合包运行时改表 | D9 修好后哈希会重算；仍存在"静态按 JSON、运行时按注入后的表"的固有差异，靠"未识别注入不承诺穷尽发现"的既有边界诚实标注 |
| 双平台重载监听的行为差异 | Fabric 与 NeoForge 的重载事件时序不同 | 监听器只置脏标记、重建走 tick 路径，把差异压到最小；P3 双平台实机验收 |
| 附魔等级与抽样次数使缓存键取值增长 | 每多一个被引用的附魔就多一维取值，再乘上次数档位 | 决策 32 已取消候选枚举，因此这个增长只影响**缓存键的取值空间**、不影响构建成本；只对"表实际引用到"的附魔生成控件；每表按参数组合计数（决策 46）兜底 |
| 参数展示补齐会拉长 tooltip | 数量范围、概率表、每级增加量都会进入物品提示 | 沿用 [文本格式规范](../dev/tooltip.md) 的分行与折叠约定，必要时把长内容放到第二级；实机核对中英文案不溢出 |
| 等级超出 `maxLevel` 的情形 | vanilla 不裁剪等级（0..255），但游戏内可获得的等级受附魔数据限制 | 控件范围取 0..maxLevel，因此该类情形只可能出现在"数据包抬高了 maxLevel"时，控件会自动跟随数据（且该数据已在哈希摘要内，见决策 35） |
| 静态提示不承诺可达成 | 「需要条件」只说明该路径引用了哪些旋钮/条件，玩家照着调仍可能拿不到（最典型的是决策 45 下"谓词要求的是标签里没列出的成员"） | 这是决策 34 的刻意取舍：宁可给一句真话也不给假按钮。有联合见证时按钮会出现并直接给出数字，没有时是纯文本。实机验收须确认玩家不会把它误读成"照着调就行" |
| 一键填充读不到部分上下文 | `in_open_water` 属于鱼获浮标（需 `player.fishing`）、`attacker` 对尚未死亡的怪无意义、`damage_source_properties` 与玩家打算怎么杀未必一致 | 按决策 41 逐条列出未填项与原因，不按"不成立"填；面板明确区分"已按当前状态填入"与"无法读取，保持原值" |
| 按内容去重后无法回答"这份数字是谁算的" | 首次触发者只落盘不展示（决策 37） | 隐私与信息量之间的取舍，由用户裁定。整合包作者排障需要时从日志或存档读取；若将来确有需要，再加一个只回答"是否有人算过"的布尔即可，不影响缓存结构 |
| 防抖自动请求在连续微调下会打出多个请求 | 拖动幸运输入框、连续切换次数档位 | 防抖窗口 400-600ms，且与在途去重共用同一套去重键；页头显示排队/计算中状态让等待可见；限流（每玩家在途 ≤2、全局待处理 ≤32）兜底 |
| 抽样次数进 Input 身份后每表缓存条目变多 | 参数组合数 × 次数档位数 | 决策 46 把上限压到 ≈24 条/表，且存的是 per-signature 比例值，存储量很小；档位与上限本身是可调实现参数 |

## 七、验证方式

### 7.1 编译验证（实施时由开发执行）

优先通过 IDEA MCP 检查最终改动的非 Markdown 文件，然后运行 `./gradlew build`。构建等待至少 120 秒，返回运行中 ID 后持续等待结束，不重复启动并发构建；不新增 test 文件。注意 `Probability` 新增状态会触发全仓穷尽 switch 的编译错误，逐处处理是预期流程而非意外。

### 7.2 实机验证（用户执行）

| 场景 | 验收标准 |
|---|---|
| `river.json` / `lava.json` 幸运 0 与门槛 | 青金石/紫水晶碎片在幸运 0 下显示「需要条件」并给出「该路径需要幸运 ≥ 0.34」——门槛已**向上对齐到 0.01 并用真实公式回验**，因此可直接填入；有联合见证时可点按钮填入并给出数字，不再写"约 1/3"这种不能直接填的值 |
| `mud_dredging.json` 基准（**三段式，验收时不可混为一谈**） | **① 五件直接物品**（黏土球/泥巴/玻璃瓶/骨粉/睡莲）只受池级 `tool_enchantment` 约束，**不要求开放水域或沼泽**：显示「需要条件」+「该路径需要：工具带泥地打捞附魔（等级 ≥ 1）」。**② `common` 子表条目**：额外需要开放水域。**③ `swamp` 子表条目**：额外需要开放水域 + `#c:is_swamp` 群系。三段都不得显示 `0%` |
| 原版 `minecraft:gameplay/fishing` | 原先被 8 上限截断的群系条目（睡莲/竹子/可可豆等）在场景 Tab 里可见；若超出 32 上界则有明确提示 |
| 场景 Tab 交互与快捷切换 | 点场景卡片触发计算并跳回网格；**网格页快捷切换下拉**每项标出 已缓存／未计算／计算中，失败时另标"上次失败"；页头始终显示当前 Input 与状态；切场景保留已填的幸运值与次数档位；重进笔记后偏好仍在（**要求 P2 落地文件实现，不能停在内存实现**） |
| 请求触发（决策 43） | 拖动幸运输入框停手后自动触发一次请求（防抖），快速连续微调不会打出等量的计算；`[计算]` 按钮可手动兜底；等待期间页头可见排队/计算中状态 |
| 参数旋钮 | 参数区只出现 工具 / 附魔等级 / 幸运 / 抽样次数 四类，**不出现爆炸与击杀/抢夺**（见 2.3）；附魔等级旋钮只在该表确实引用了该附魔时出现；抽样次数只能选签发档位；幸运超出 -5..10 或输入非有限值被拒绝并有提示 |
| 显示优先级（决策 44） | 状态为 `NeedsCondition` 的条目网格**不显示任何数字**（含声明触发率）；状态可展示的条目仍显示声明触发率且 tooltip 并列给出模拟值；切换到未计算的 Input 后网格全部变 `?` 且 tooltip 写「尚未计算」 |
| 零命中（决策 40） | 适用但零命中的条目网格显示「未命中」，**全界面不出现 `<0.01%`**；tooltip 报当前抽样次数；把次数切到更高档位后若命中则给出数字 |
| 各种 `?` 可区分 | 「需要条件」「未覆盖」「规则未解析」「尚未计算」「计算失败」「已淘汰」「静态不可达」「已测量但零命中」互不混淆，tooltip 文案各自独立，均不伪造精确概率 |
| 数据包重载（D9） | 修改一张战利品表的 JSON 后执行 `/reload`，无需管理员命令即触发目录重建；该表全部 Input 的缓存失效并重算 |
| 附魔定义的失效（决策 35） | 改 `mud_dredging` 附魔的 `max_level` 后 `/reload`：该表哈希变化、缓存失效、等级控件范围与模拟用的满级工具随之改变 |
| 缓存、LRU 与失效 | 改表后旧 Input 结果不残留；重启存档后已算过的 Input 不必重算；每表持久化的**参数组合**数不超过 8，同一参数的不同次数档位不挤占彼此；LRU 淘汰某个 Input 后动态条目仍留在网格里（发现记录挂表级） |
| 跨玩家复用（决策 37） | 玩家 B 选中与玩家 A 完全相同的参数时直接命中，服务端不重复计算；存档里不出现玩家 uuid 分区 |
| 一键填充（决策 41） | 手持带时运的镐点一键填充：工具与附魔等级被填入、页头显示该 Input 并触发计算；抛竿中打开笔记时"开放水域"被填入，未抛竿时该行显示"未能填充"而**不是**"不成立" |
| 限流与越权 | 连点场景不刷爆 tick 预算；两玩家请求同一 Input 不重复计算；越权自造的 `SimulationInput`（含未签发的次数档位、超范围幸运）被拒绝 |
| 双平台 | Fabric `LootTableEvents.MODIFY` 注入与 NeoForge GLM 的动态条目在基准与按需 Input 下都能被发现并标注；**泥地打捞五件直接物品在 fishing 表里能获得参数入口**（决策 42 的闭环） |
| 隐藏条目与 i18n | 未发现条目仍按现有规则隐藏，场景卡片不提前泄露隐藏物品名；中英文案同步、不溢出；「未命中」等短文案在网格宽度内不被截断 |
| 附魔等级参数 | 泥地打捞表把等级从 1 调到 2、3 时概率随之变化，并与 `linear(base 0.2, per_level 0.1)` 的公式值一致；等级设为 0 时该条目显示「需要条件」 |
| 条件/函数参数展示 | `apply_bonus` 的 tooltip 给出由等级推导的数量范围；`table_bonus` 能指名附魔 |
| 不可用机制（决策 31） | 造一张用 `enchantment_level` 提供器的表并纳入追踪：该表被标为不可用并输出一次诊断，其余表不受影响，不再只有满屏 `?` |
| 区间受限降级（决策 28） | 造一条 weight=-2、quality=1 的条目：显示「需要幸运 ≥ 3」；再造一条负 quality 的：追加「（该路径有上限，区间受限）」且不给具体数值 |
| 标签谓词降级（决策 45） | 造一条 `match_tool` 要求 `#minecraft:axes` 的表：下拉只出现标签首个成员与默认工具，且提示里给出谓词原文；要求非首个成员的条目只显示静态提示，**不出现点不动的按钮** |
| 成本上界（决策 32/33） | 造一张"`all_of` 嵌多层 `any_of`"的表并纳入追踪：目录构建不被拖住，超预算的路径按无约束降级并输出一次告警，不出现启动卡死 |

## 八、待确认项

第三轮裁定后，首版的六项待确认项只剩四项余尾；另有三项是本轮新产生、明确留给实施期定的：

| 事项 | 现状与推荐处置 |
|---|---|
| 每表可持久化的参数组合上限与抽样次数档位 | 决策 46 定为**参数组合** 8、实际条目 ≤ 8 × 档位数；档位本身建议 1 万 / 5 万 / 10 万（决策 39）。三者在实机观察命中率后再定，都是可调实现参数，不影响任何语义。 |
| 客户端偏好的文件实现 | **已从"待确认"变为"必须在 P2 落地"**：§7.2 要求"重进笔记后偏好仍在"，而决策 29 只给了接口 + 内存实现。P2 按 `platform/services/` 的 SPI 补 fabric / neoforge 的文件实现。 |
| 场景卡片的截断长度与 tooltip 行数 | 方向已定：单列可变行高 + 最大长度截断，截断处由 tooltip 给出完整条件树。具体截断像素与 tooltip 行数在实现时按中英文案实测后定稿——「未命中」的简短措辞（决策 40）正是为不被截断而定。 |
| `docs/dev/network.md:171` 关于 `/reload` 广播新快照的描述 | 与现状不符（未注册重载监听）。P0 修好 D9 后该描述才成立，需在 P3 的文档同步阶段一并核对。 |
| 内置表以外的追踪表 | 场景数估算只覆盖内置 13 张表与原版 fishing；整合包表的场景数、参数组合数、以及"`all_of` 嵌多层 `any_of`"这类条件树的实际出现频率，需在 P3 实机验收中抽样确认。 |
| 联合见证搜索的预算取值 | 决策 34 定了"带预算、找不到就不渲染按钮"的形态，但预算的具体量级没有实测依据（取决于整合包表的 `match_tool` 谓词数量与附魔维度数）。实施时先取保守值，P3 用整合包表实测后再调，不因调大预算而改变语义。 |
| 一键填充的场景指纹映射 | 决策 41 定了"填参数 + 场景"两层，但"把玩家位置与世界状态映射到该表的哪些条件指纹"需要逐类条件的映射实现（`location_check` 用 `LocationPredicate.matches`，`entity_properties` 用玩家实体与当前浮标）。`damage_source_properties` 一类没有自然映射的条件明确**不填**并在结果里列出，具体清单在实施时定稿。 |
| 静态信息性提示的文案长度 | 一条路径引用多个旋钮/条件时 tooltip 会变长。沿用 [文本格式规范](../dev/tooltip.md) 的分行与折叠约定，具体在实现时按中英文案实测。 |

## 九、实施结果

2026-09-20：完成项目源码、内置资源、原版 1.21.1 `LootContextParams`/`LootContextParamSets` 与条件/函数消费者的核查（原版部分经本机 NeoForm 中间产物源码，因 mc-developing-mcp 数据库对这两个类返回空），形成本规划；尚未修改 Java、语言文件、缓存格式或网络协议，未进行构建及游戏实测。

2026-09-20（第二轮）：处理第八节的全部待确认项，新增裁定 6 项（决策 26-31）。为回答"附魔等级能否作为参数"，追加核查了 1.21.1 附魔等级的读取链路与三个 `apply_bonus` 公式（同样经本机 NeoForm 中间产物源码；mc-developing-mcp 对本例的 `ApplyBonusCount` 只返回类骨架、没有方法体，不足为据）。结论：等级是运行时输入、vanilla 不裁剪到 `maxLevel`，项目已有构造带附魔工具栈的先例，因此以参数形态支持。本轮仍未修改任何代码、资源、缓存格式或网络协议。

2026-09-20（第三轮，评审修正）：对前两版收到的十项评审意见逐条在代码与数据中核实后修正，新增裁定 17 项（决策 32-48）。本轮为核实意见而读的证据：`SimulationScenarioPlanner`（`SCENARIO_CONDITIONS`、`plan`、`applyMudDredgingTool`、`appendMudDredgingScenarios`、`combineAnd` 的叉乘）、`LootProbabilityData`（格式 2 的 `hasDirectSource` / `sourceChildTables` / 子表入口键）、`ArchaeologyJournalServerCatalog`（`computeTableHashes`、`updateCompiledProductDigest`、`:379-390` 的缓存恢复）、`ItemGridPanel:388` 的声明触发率优先级、`ProbabilityFormat` 的 `<0.01%` 渲染、`RuntimeLootLinks`、`mud_dredging.json`、`SyncCatalogHashPayload` 的整体重同步链路。

核实结论：十项意见里九项完全成立；第六项（失效范围）经核实后把表述收窄为"表 JSON 变化必然失效"与"影响概率的所有数据变化必然失效"两句（决策 35）；第八项（零命中）按用户裁定改为只报抽样事实、不给置信上界（决策 40）。另有两处用户否决了作者的推荐：首次触发者记录只落盘不展示（决策 37）、标签谓词取首个成员（决策 45）。

用户在本轮另行提出四项功能与一项架构方向并获裁定：可调模拟次数及其进入 Input 身份（决策 38-39）、网格页快捷切换下拉与缓存状态标记（决策 43）、一键填充玩家当前状态（决策 41，推翻首版 §2.3 的"不做"）、缓存按内容去重而非按来源隔离（决策 37）、本规划只补决策、执行级拆点待开工另起清单（决策 47）。阶段划分据此重切为四段（决策 48）。

本轮仍未修改任何代码、资源、缓存格式或网络协议。

2026-09-20（第四轮，范围收窄）：用户指出追踪范围需要在文档里写明确——不做方块破坏表与实体掉落表，陶罐（`pots/`）例外。为核实这一点读了 `platform/services/ILootTableConfig.java`（默认追踪前缀，`:25-36`）、`journal/tracking/RecentLootTableService.java`（`:27-30` 的 `entities/`、`blocks/` 排除）、`loottable/catalog/LootTableNames.java`（`:57-71` 只按配置前缀匹配、无额外校验）、全仓 13 张内置战利品表的顶层 `type` 与条件/函数清单、`blocks/pottery_wheel.json` 全文，以及 `blocks/unsuspicious_gravel.json` 与 `usb/random_potion.json` 的内容。

核实结论：范围由**路径前缀**划定（`pots/` 在默认前缀内、`blocks/` 与 `entities/` 不在且被管理页拒绝）；被追踪的表里没有一张用到 `survives_explosion`、`ExplosionCondition`、`EnchantedCountIncreaseFunction` 或 `LootItemKilledByPlayerCondition`——`panning` 与 `fossil_hunter` 虽声明 `minecraft:block`，条件只用到 `random_chance`。因此 D6/D7 从缺陷列表移除，爆炸与击杀/抢夺旋钮一并删除（新增裁定 1 项：决策 49）。本轮同时发现一处留待实施时决定的遗留边界：手工在配置里添加 `blocks/`/`entities/` 前缀仍可让这两类表进入目录，因为匹配层不校验。本轮仍未修改任何代码、资源、缓存格式或网络协议。
