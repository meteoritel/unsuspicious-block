# 战利品物品提示：`set_count` 区间在多路径合并时被丢弃并退化为「近似物品」

> 状态：**D1 / D2 / D4 / D5 已实现，待完整实机验证**（2026-09-20）；D3 保留后续评估。
>
> 后续修复：D4 通过获取路径的函数不确定性分级解决，保留物品签名兼容；另按维护者确认，将明确的 `random_chance` 声明值以“触发率”放在网格，模拟掉落率放在 tooltip。目录协议更新为 4.3，模拟缓存版本更新为 v15。下述“本轮”及验证记录指首次提示修复。
>
> 数据侧后续调整：`river.json` 移除了金粒 `uniform 3~6` 的条目与另一个含金粒/青金石/紫水晶碎片的池，各金粒路径区间统一为 `1~3`；`lava.json` 的恶魂之泪由 `uniform 1~1` 改为常量 `count: 1`，两个门槛条目权重由 `0` 改为 `1`。内置资源因此不再触发 D1 与 D2，两者仍对第三方数据包成立；机制以 [战利品表系统](../dev/loottable.md) 第 6.1 节为准。
>
> 后续验证：IDEA 检查无新增错误或警告；中英 JSON 各 738 个键且集合一致；`./gradlew build` 成功（50 秒），未新增测试文件。待实机确认：河流金粒等不再因数量区间显示“条件”；绿宝石/钻石卡片为“触发率 8% / 1%”，tooltip 单列模拟掉落率；自定义 `random_chance` 区间及多个声明值保留；重启后原发现数量不变，双端更新后的目录同步正常。
>
> 本轮采用 D1 的合并提示方案：按组件结构去重，保留不同路径提示，以 `/` 分隔；D2 对等端点区间显示 `数量: N`，不改变签名；D5 移除合并器对本地化文本的比较。未调整整体架构、网络字段或模拟缓存版本。以下症状、代码片段与行号保留为修复前的调查记录，当前机制见权威文档第 6.1 节。
>
> 开发验证：IDEA 检查无错误，仅 `LootFunctionHandlers.keyOf` 原有未使用方法警告；中英语言 JSON 解析通过且各 734 个键一致；`./gradlew build` 双平台构建成功（55 秒）。未新增测试文件。待维护者验证河流金粒、铁粒、紫水晶碎片与岩浆恶魂之泪的提示，以及重载和专用服务端表现。
>
> 性质：**展示信息丢失**，不是解析失败、也不是网络传输漂移。`set_count` 的数值在检查/投影阶段已被正确算出，丢失发生在目录的"同签名多路径合并"环节。
>
> 当前机制的唯一权威描述是 [战利品表系统](../dev/loottable.md)；本文是过程记录与缺陷清单，**不构成机制权威**，机制问题一律回到该文档与代码。
>
> 行号以 2026-09-20 的工作区为准，实施后会漂移；定位时请以类名 / 方法名为准。

## 一、症状

实机表现（NeoForge 端，单人世界）：

在考古笔记的「淘洗（河流）」表页，悬停任意一件**淘洗产物**（如「金粒」），tooltip 的概率行下方出现 **`近似物品`**，而**没有** `数量: a-b` 行：

```
金粒
累计获得：3
条件概率：51%
近似物品        ← 期望这里是「数量: 1-3」（或 3-6）
```

同一张表里，所有带 `uniform` `set_count` 的物品**格子**都显示「条件 X%」而不是普通百分比（截图里 8 件中 7 件如此，只有绿宝石显示「约 7.7%」）。两者叠加，很容易读成"`set_count` 根本没被解析"。

本机证据（`run/` 在 `.gitignore` 中，不入库，仅作现场记录）：

| 证据 | 路径 | 说明 |
|---|---|---|
| 截图 | `neoforge/run/screenshots/2026-09-20_10.58.19.png` | tooltip 四行内容如上（实机确认） |
| 会话日志 | `neoforge/run/logs/latest.log`（2026-09-20 10:57 会话） | 无任何 `解析战利品函数失败` / `应用战利品函数处理器失败` 告警 |

## 二、最小验证（5 秒可判）

同一页面上把鼠标依次移到**铁粒**与**金粒**：

| 物品 | 各 pool 的 `set_count` | 提示是否一致 | 预期 tooltip 末行 |
|---|---|---|---|
| 铁粒 `minecraft:iron_nugget` | pool 1 = `2~5`，pool 4 = `2~5` | 一致 | `数量: 2-5` |
| 金粒 `minecraft:gold_nugget` | pool 1 = `1~3`，pool 2 = `3~6`，pool 3 = `1~3`，pool 4 = `1~3` | **冲突** | `近似物品` |

- 若确实"一个有一个没有"→ 解析链路正常，缺陷 100% 落在本文第四节 D1。
- 若**两者都是** `近似物品` → 说明投影期 `describeHint` 没有产出提示，需要回到第四节 D2 与第三节第 2 步重新核查（本次未能实机逐项悬停确认这一条，见第八节）。

## 三、产生原理（按工作流）

以 `common/src/main/resources/data/unsuspiciousblock/loot_table/gameplay/panning/river.json` 的**金粒**为主线（数据包核实）：

```
river.json
  │
  ├─① 快照 / 编译    LootTableSourceSnapshot → LootTableCompiler
  │                 每 pool 每 entry 一条“物品路径”事件；4 条金粒路径各自带自己的 set_count
  │
  ├─② 投影-求值      LootTableProjector.resolveItem
  │                 逐条路径解码 set_count → 成功产出「数量: 1-3」/「数量: 3-6」
  │                 同时因 apply 返回 null 而把该路径标为“带条件”
  │
  ├─③ 签名归一       4 条路径拿到同一个 key：
  │                 usb_sig|APPROX_ITEM_ONLY|minecraft:gold_nugget|<base64("function")>
  │
  ├─④ 合并 ★丢失点1  ItemDefinitionAccumulator.merge
  │                 提示文本不一致 → 放弃两个已算出的数值
  │
  ├─⑤ 兜底 ★丢失点2  LootTableCatalog.resolveMergedTooltipHint
  │                 APPROX_ITEM_ONLY 只有一个出口：「近似物品」
  │
  ├─⑥ 不确定性外溢   ItemDefinition.uncertaintyLevel() → RUNTIME
  │                 → 格子「条件 X%」、tooltip「条件概率：X%」
  │
  ├─⑦ 网络传输       CatalogTableDto → SyncArchaeologyCatalogPayload
  │                 原样透传，无损（已排除嫌疑）
  │
  └─⑧ 客户端渲染     JournalTooltipBuilder → tooltip 末行「近似物品」
```

### 第 0 步：数据本身合法

金粒在 `river.json` 的 pool 1、2、3、4 里出现（**编号约定**：下文以 JSON 中 `pools` 数组从 1 开始的序号称呼 pool，即 pool 1 = 数组首元素），`set_count` **故意不同**：

| pool | 该 pool 内金粒的写法 | 行 |
|---|---|---|
| pool 1 | `weight: 5`，`count` = `uniform 1~3`（行 52-66） | 52-66 |
| pool 2 | `weight: 0, quality: 4`，`count` = `uniform 3~6` | 149-164 |
| pool 3 | `weight: 4, quality: 2`，`count` = `uniform 1~3` | 170-185 |
| pool 4 | `rolls: 0, bonus_rolls: 1` 的奖励池，与 pool 1 条目列表相同，`count` = `uniform 1~3` | 220-330 |

"`weight: 0` + `quality: 4`"是"只有品质足够好才被替换成这一档"的写法，因此它的区间更大是设计意图，不是脏数据。四条路径按 pool 顺序进入合并，**冲突发生在第 2 条（pool 2 的 `3~6`）**。

### 第 1 步：编译只搬运，不理解

`LootTableCompiler.walkNode` 依次走 `pools → entries`，每遇到 `item` 调 `recordItem`（`LootTableCompiler.java:192`），把该 entry 自己的 `functions` 数组原样挂到事件上（`ownFunctions`，`:244`）。编译层**不认识** `set_count`，这是刻意的（上下文无关）。

事件顺序 = pool 顺序 × pool 内 entry 顺序，因此金粒的四条路径依次是：pool 1（`1~3`）→ pool 2（`3~6`）→ pool 3（`1~3`）→ pool 4（`1~3`）。**冲突发生在第 2 条**。

### 第 2 步：投影期数值被正确算出

`LootTableProjector.resolveItem`（`LootTableProjector.java:179`）对每条路径重新解码函数，交给 `SetCountHandler`（`LootFunctionHandlers.java:436`）：

| 环节 | 位置 | 行为 | 结果 |
|---|---|---|---|
| `apply` | `:437-454` | 反射读 `SetItemCountFunction.value`，**只认 `ConstantValue`** | 遇到 `UniformGenerator` 返回 `null` → 走"无法静态求值"分支，`entryHasConditions = true`（`LootTableProjector.java:245`） |
| `describeHint` | `:456-477` | 反射读 `UniformGenerator.min/max`，两端皆为 `ConstantValue` | 产出 `Component.translatable(...item_hint.set_count_range, minInt, maxInt)` |

**数值在这一步是完好的。** 原版字段名已逐一核对，与代码中的字符串逐字一致（源码核实）：`SetItemCountFunction.value` / `.add`、`UniformGenerator.min` / `.max`、`ConstantValue.value`（`net.minecraft.world.level.storage.loot.providers.number`）。

### 第 3 步：四条路径被判定为"同一个结果"

函数循环结束后（`LootTableProjector.java:271-276`），因为 `entryHasConditions == true` 且签名仍是 `PLAIN`，签名被降级：

```java
signature = LootResultSignature.approximateItemOnly(currentItemId(previewStack), "function");
```

四条路径的降级理由都是同一个字符串 `"function"`，所以 `toStoredKey()` 完全相同，`computeIfAbsent`（`:292`）让它们共用一个 `ItemDefinitionAccumulator`。

这里埋着全流程的**第一个关键假设**：

> 同一个签名 ⇒ 这是同一件掉落物 ⇒ 展示信息理应一致。

对"物品身份"它成立；对"数量区间"它不成立——同一件物品在不同 pool 里合法地拥有不同区间。

### 第 4 步：合并 —— ★丢失点 1

`ItemDefinitionAccumulator.merge`（`ItemDefinitionAccumulator.java:41`）逐条比较提示文本（`:49-54`）：

```java
String currentHint  = this.tooltipHint != null ? this.tooltipHint.getString() : null;  // "数量: 1-3"
String resolvedHint = resolvedTooltipHint != null ? resolvedTooltipHint.getString() : null; // "数量: 3-6"
if (Objects.equals(currentHint, resolvedHint)) {
    return;                                                    // 不一致，放行
}
this.tooltipHint = LootTableCatalog.resolveMergedTooltipHint(this.signature);  // ← 数值在这里被丢弃
```

设计意图（见该类 javadoc）是"先出现者胜，出现分歧时退回按签名重新解析，避免展示互相矛盾的信息"。**但"按签名重新解析"对 `APPROX_ITEM_ONLY` 签名没有任何可用信息**，于是两个真实区间一起被替换成兜底文案。

后续两条路径（`1~3`）再进入时，比较对象已经变成「近似物品」，继续不一致、继续兜底，最终稳定在「近似物品」。

### 第 5 步：兜底 —— ★丢失点 2

`LootTableCatalog.resolveMergedTooltipHint`（`LootTableCatalog.java:264-273`）：

```java
if (signature.isEnchantedVariant())                                  return 「已附魔」;
if (signature.type() == SignatureType.APPROX_ITEM_ONLY)              return 「近似物品」;   // :269-271
return null;
```

签名里**没有任何"数量"维度**，所以兜底不可能还原出 `3~6`。这就是截图末行四个字的直接来源。

### 第 6 步：不确定性外溢（放大器）

`entryHasConditions` 会继续向下传导，产出一个**与真实条件无关**的"条件"标签：

- `ItemDefinition.hasConditions()`（`LootTableCatalog.java:196-203`）把 `APPROX_ITEM_ONLY` 也算作"带条件" → 模拟零出现时概率记 `Unknown`。
- `ItemDefinition.uncertaintyLevel()`（`:215-228`）第一分支即 `APPROX_ITEM_ONLY → RUNTIME`。
- 客户端按等级取文案（`ItemGridPanel.formatProbability:423-450`）：`RUNTIME` → `probability_conditional_short` = `条件 %s`；tooltip 走 `probability_conditional_value` = `条件概率：%s`。

**注意它波及的是所有带 `uniform set_count` 的物品，包括提示其实正常的铁粒。** 所以从网格看是"整张表都不对"，从 tooltip 看却只有一部分物品真的丢了数值——这是症状被放大的原因，也是排查时最容易误判的地方。

### 第 7 步：传输无损（已排除）

| 环节 | 位置 | 结论 |
|---|---|---|
| 内部记录 → 网络形态 | `CatalogTableDto.from` / `toTableDefinition`（`CatalogTableDto.java:79` / `:104`） | 原样透传 `tooltipHint` |
| 缓存恢复 | `ArchaeologyJournalServerCatalog.restoreFromCache:358-360` | 用 `item.tooltipHint()` 重建 |
| 模拟结果重建 | `LootProbabilitySimulationJob.buildResult:260-262` | 用 `item.tooltipHint()` 重建 |
| 网络编解码 | `SyncArchaeologyCatalogPayload:74-77` / `:132-134` | `Component.Serializer` 往返 |

参数不会在序列化时变形：`TranslatableContents.ARG_CODEC` 对 `Number` / `Boolean` / `String` 参数走 `PRIMITIVE_ARG_CODEC`，是原样往返的（源码核实）。也就是说，**消息到达客户端时它已经是「近似物品」了**。

### 第 8 步：渲染并上屏

客户端只做"取字段 + 加样式"：`GridItem.tooltipHint()` → `TooltipData.hint()`（`ItemGridPanel.getTooltipData:494`）→ `JournalTooltipBuilder.build:109-116` 原样追加为深绿斜体行。客户端没有、也不应该有能力把它变回区间——这符合项目"客户端不得推断条件语义"的既定边界（[战利品表系统](../dev/loottable.md) 7.2）。

## 四、触发条件清单

| 编号 | 触发器 | 触发位置 | 命中数据 | 后果 |
|---|---|---|---|---|
| **D1** | 同一同签名条目的**多条路径提示不一致** | 合并期 `ItemDefinitionAccumulator.java:49-54` + 兜底 `LootTableCatalog.java:269-271` | `river.json`：金粒 `1-3` vs `3-6`；青金石 `2-4` vs `1-2`；紫水晶碎片 `1-2` vs（空提示） | 两个真实区间都被替换为「近似物品」 |
| **D2** | `uniform` 两端相等（`min == max`） | 求值期 `LootFunctionHandlers.java:469-470`（`describeHint` 返回 `null`）+ `apply` 不认 `uniform`（`:437-454`） | `river.json` 紫水晶碎片 pool 3 的 `1~1`；`lava.json` 恶魂之泪 | **单条路径**即降级为「近似物品」 |
| **D3** | 常量 `count`（如 `"count": 4`） | `apply` 成功但数量不是组件、签名仍是 `PLAIN`，也不产出提示 | 当前内置资源未使用此写法 | 数量在 UI 上完全不可见（潜在，未触发） |
| **D4** | 放大器：`uniform` ⇒ 近似签名 | `LootTableProjector.java:271-276` → `uncertaintyLevel()` | 全部含 `uniform set_count` 的物品 | 格子显示「条件 X%」，令 D1/D2 看起来像"全表失效" |
| **D5** | 比较依赖**已本地化文本** | `ItemDefinitionAccumulator.java:49-50` 的 `getString()` | 所有走到合并期的提示 | 单人（能解析出文本，判定不一致）与专用服务端（都解析成原始 key，判定一致、不 fallback）行为不同 |

D5 属于项目自身已经明令禁止的写法——`LootTableCatalog.java:190-195`（`ItemDefinition.hasConditions()` 的注释）写着"不依赖 tooltipHint 文本比较，避免服务端/客户端语言差异导致行为不一致"，而合并器仍在用同一手法。

## 五、影响面盘点

内置资源（数据包核实 + 本地脚本复刻合并语义扫描 `common/src/main/resources/data/**/*.json`；`fabric/` 与 `neoforge/` 源集下无 `loot_table` 资源）：

| 表 | 受影响条目 | 触发 |
|---|---|---|
| `unsuspiciousblock:gameplay/panning/river` | 金粒（`gold_nugget`）、青金石（`lapis_lazuli`）、紫水晶碎片（`amethyst_shard`） | D1（紫水晶碎片兼 D2） |
| `unsuspiciousblock:gameplay/panning/lava` | 恶魂之泪（`ghast_tear`，`uniform 1~1`） | D2 |
| 其余含 `set_count` 的表（`mud_dredging/common`、`mud_dredging/swamp`、`fossil_hunter/overworld_bone_block`、`fossil_hunter/nether_bone_block`、`cat/ghost_gift`） | 无 | — |

同表的铁粒、沙子、黏土球、煤炭、燧石、骨粉因两处区间相同而**未**受影响（提示保留为 `数量: a-b`），这也解释了为什么症状看起来"只在部分物品上出现"。

第三方数据包同样可能触发 D1/D2：任一物品在多 pool 里使用不同的 `set_count` 区间即可。

## 六、已排除的假设

| 假设 | 结论 | 依据 |
|---|---|---|
| `set_count` 函数解码失败 | 排除 | `LootItemFunctions.TYPED_CODEC` 按 `function` 字段分发；原版 `SetItemCountFunction` 字段名与反射字符串一致（源码核实）；会话日志无解码失败告警（实机确认） |
| 反射被 JPMS 挡（NeoForge 走模块路径） | 排除 | `neoforge/build/moddev/clientRunVmArgs.txt` 确实使用 `-p`，但 `securejarhandler` 的 `ModuleJarMetadata` 以 `ModuleDescriptor.newOpenModule(name)` 建模块，游戏模块是 open module，`setAccessible` 不会抛 `InaccessibleObjectException`（源码核实）；Fabric 端走 classpath，无名模块更无此问题 |
| 后端 → 前端信息漂移 | 排除 | 第四节第 7 步的全链路透传 + `TranslatableContents.ARG_CODEC` 原样往返 |
| 2026-09-18 编译-投影重构引入的新回归 | 排除 | 重构前 `919c767^` 的 `LootTableJsonParser.ItemDefinitionBuilder.merge` 已是同一套 `getString()` 比较与同一兜底，`resolveMergedTooltipHint` 的实现逐行相同（源码核实，`git show 919c767^:.../LootTableCatalog.java:310-318`） |

即：**这是一个存量设计缺口**，只是被 `panning/river.json` 这种"同一物品在多个 pool 给不同区间"的新写法第一次真实触发。它也解释了为什么此前一直没被发现。

## 七、缺陷清单（处置方案待定，由项目维护者裁决）

### D1 合并期提示冲突丢弃数值（P1，直接对应用户可见症状）

- 位置：[`ItemDefinitionAccumulator.merge`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/ItemDefinitionAccumulator.java) `:49-54`；兜底 [`LootTableCatalog.resolveMergedTooltipHint`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/LootTableCatalog.java) `:264-273`。
- 问题：把"提示分歧"等同于"提示不可信"，而回退目标对近似签名没有任何信息量。
- 可选方向（未裁决）：
  1. **合并提示**：同签名下收集全部不同提示并拼接（如 `数量: 1-3、3-6`），保留获取路径各自的区间；
  2. **按路径展示**：提示不再压成单值，随 `acquisitionPaths` 逐条渲染（改动面最大，会动 UI）；
  3. **保留首条非空提示**：最小改动，但会丢掉其它路径的区间（仍是信息损失，只是不再输出无意义的「近似物品」）。
- 注意：`ItemDefinitionAccumulator` 是"同签名多路径合并的唯一实现"，编译路径与投影路径都依赖它，改动需保证两条路径产出逐位一致（见 [战利品表系统](../dev/loottable.md) 第 6 节）。

### D2 `uniform` 两端相等的 `set_count` 两头不处理（P2）

- 位置：[`LootFunctionHandlers.SetCountHandler`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootFunctionHandlers.java) `apply:437-454`（只认 `ConstantValue`）与 `describeHint:469-470`（`minInt == maxInt` 直接 `return null`）。
- 问题：`{"type":"minecraft:uniform","min":1,"max":1}` 既不被 `apply` 应用，也不产出提示，于是单条路径就落到 `resolveItemTooltipHint(true)` 的「近似物品」。
- 可选方向：`apply` 支持"两端为常量且相等"的 `uniform`（按常量处理）；`describeHint` 在 `min == max` 时改输出 `数量: N`（复用现有键或新增键，需同步两个 lang 文件）。

### D3 常量 `count` 静默丢失（P3，当前未触发）

- 位置：同 `SetCountHandler.apply`。
- 问题：常量 `set_count` 会改预览栈数量，但数量不是组件、签名仍是 `PLAIN`、`createPreviewStack()` 又恒为 1，于是"4 个"这一事实在 UI 上不可见，且与无函数物品共用同一签名、共用同一批模拟统计。
- 可选方向：常量 `count` 也产出提示；或让数量进入签名/条目身份（影响面大，需评估存档与缓存键）。

### D4 `uniform` 即被判为"运行时不确定"（P3，放大器，属设计问题）

- 位置：`LootTableProjector.java:271-276`（`APPROX_ITEM_ONLY` 降级）→ `ItemDefinition.uncertaintyLevel():215-228`（`RUNTIME`）。
- 问题：`uniform` 的 `set_count` 只是"产量不定"，并不影响"该物品是否掉落"，却被算作"带条件"，导致整表显示「条件 X%」、零出现时概率记 `Unknown`。
- 可选方向：把"函数导致的近似"与"条件导致的可能不可达"在不确定性口径上区分开（会牵动签名语义、`SIMULATION_CACHE_VERSION` 与 `catalogHash`，需单独评估）。
- 与 [战利品表系统](../dev/loottable.md) 第 7.2 节"概率文本与颜色共用一份判定"的既有约定直接相关，改动前需一并核对。

### D5 合并比较依赖已本地化文本（P3）

- 位置：`ItemDefinitionAccumulator.java:49-50` 的 `Component#getString()`。
- 问题：同一条数据在单人 / 专用服务端上可能得到不同合并结果（语言表是否已加载），与项目"不用文案比较判断语义"的既定边界相悖。
- 可选方向：改为比较结构化值（如把提示携带的语义键与参数作为比较依据），提示类型由 `LootFunctionHandler` 提供结构化描述而非成品 `Component`。
- 注意：若改变提示的承载形态，需同步 `SyncArchaeologyCatalogPayload` 的编解码与 `CatalogGeneration` 的目录哈希输入（`updateDigest(digest, item.tooltipHint())`，见 `CatalogGeneration.java:184`）。

## 八、修复时必须遵守的边界

1. **单一实现**：提示合并只有 `ItemDefinitionAccumulator` 一份实现，编译路径与投影路径必须产出逐位一致的结果；不可为修 UI 而在客户端另起规则。
2. **客户端不推断语义**：客户端只能取字段、映射样式（[战利品表系统](../dev/loottable.md) 7.2 的前提）。
3. **lang 键与代码同批**：若新增 / 修改提示键，`en_us.json` 与 `zh_cn.json` 必须同步（项目 i18n 规范）。
4. **签名 / 哈希影响面**：改动提示的承载结构会影响目录哈希与客户端目录重取；改动签名语义会影响玩家存档缓存（`LootProbabilityData`）与 `SIMULATION_CACHE_VERSION`、`JournalNbtMigrator` 的迁移链条。D4 属此类。
5. **不新增测试文件**：按项目准则，收尾走 IDEA 静态检查 + `./gradlew build`，实机验证交由维护者（HUD / GUI 文本相关改动还需人工过一遍 tooltip）。
6. **行号会漂移**：本文所有 `文件:行` 引用以 2026-09-20 工作区为准。

## 九、验证方式

### 9.1 编译验证（开发执行）

1. 通过 IDEA MCP（`http://127.0.0.1:64342/stream`）检查改动文件的报错与警告（忽略 markdown 格式问题）。
2. 执行 `./gradlew build`；按项目准则等待不短于 120 秒，若返回运行中任务 ID 则持续等待至结束，未确认上一次构建结束前不重复启动。
3. 不新增测试文件。

### 9.2 实机核对项（维护者执行）

| # | 核对项 | 期望 |
|---|---|---|
| 1 | 淘洗（河流）悬停「金粒」 | 出现 `数量:` 行（具体形态取决于 D1 选定的方案），**不再**是「近似物品」 |
| 2 | 淘洗（河流）悬停「铁粒」 | 仍为 `数量: 2-5`（未回归） |
| 3 | 淘洗（岩浆）悬停「恶魂之泪」 | 不再因 `uniform 1~1` 落入「近似物品」（D2 修复后） |
| 4 | 同一页面所有含 `uniform set_count` 的物品格子 | 若采纳 D4，应不再一律显示「条件 X%」；若本轮不动 D4，则此项保持原状（须显式确认属预期） |
| 5 | 其余表（`mud_dredging` 父子表、`chest/buried_treasure`、原版考古表） | 无新增「近似物品」或告警；概率数值与改动前无结构性变化（抽样存在正常波动） |
| 6 | 触发一次数据包重载 | tooltip 提示随新目录生效；无 missing lang key 告警；客户端目录重取一次属预期 |
| 7 | 联机（专用服务端）与单人各验证一次 | 若未修 D5，需记录两端的差异表现，作为 D5 是否排期的输入 |

## 十、证据索引

| 证据 | 等级 | 位置 |
|---|---|---|
| `set_count` 数值在投影期被正确算出 | 源码核实 | `LootFunctionHandlers.java:456-477` |
| 原版字段名与反射字符串一致 | 源码核实 | 1.21.1 `SetItemCountFunction` / `UniformGenerator` / `ConstantValue`（`NumberProviders.CODEC` 对裸数字走 `ConstantValue.INLINE_CODEC`） |
| 游戏模块是 open module，反射可用 | 源码核实 + 本机核实 | `securejarhandler-3.0.8-sources.jar` `ModuleJarMetadata:81`；`neoforge/build/moddev/clientRunVmArgs.txt` |
| 提示组件无损过网 | 源码核实 | `SyncArchaeologyCatalogPayload.java:74-77`、`:132-134`；`TranslatableContents.ARG_CODEC` |
| 合并与兜底逻辑 | 源码核实 | `ItemDefinitionAccumulator.java:41-55`、`LootTableCatalog.java:264-273` |
| 重构前行为相同（存量缺口） | 源码核实（git） | `git show 919c767^:common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootTableJsonParser.java`（`ItemDefinitionBuilder.merge`）、`.../catalog/LootTableCatalog.java:310-318` |
| 各表 `set_count` 分布与冲突集合 | 数据包核实 + 本地脚本复刻合并语义扫描 | `common/src/main/resources/data/**/*.json` |
| UI 实际呈现为「近似物品」 | 实机确认 | `neoforge/run/screenshots/2026-09-20_10.58.19.png`（本机，不入库） |
