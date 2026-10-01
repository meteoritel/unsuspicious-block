# 战利品函数解析：捕获覆盖与待办

> 状态：**实施前审查快照，已修正技术讨论中发现的错误**（2026-10-01）。仅静态核查，**未编译、未实机验证**；文档修正不代表代码问题已修复。
>
> 机制的唯一权威描述是 [战利品表系统](../dev/subsystems/loottable.md)；条件（predicate）侧的同类待办见 [战利品条件树](loottable-condition-tree.md)。
> 本文记录源码事实、缺口和取舍；详细目标、技术路线及验证矩阵见 [函数捕获实施规划](../plan/loottable-function-capture-plan.md)，不在本文维护第二份实施计划。**规划由用户执行，完成后由助手审查；当前未开始实施。**
> 修订内容：纠正附魔提示的控制流、数量附魔分类、动态等级、容器引用、注册表可用性与爆炸衰减语义，并补充运行时来源丢失及函数条件归属问题。
> 本项目代码的行号以写作时的工作区为准；**原版代码行号以 `common/build/moddev/artifacts/vanilla-1.21.1-*-minecraft-sources.jar` 为准**
> （官方 sources jar，路径 `net/minecraft/world/level/storage/loot/functions/`）。

核心类：[`LootFunctionHandlers`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootFunctionHandlers.java)（40 个原版函数名的注册映射，含共用占位处理器）、
[`LootFunctionHandler`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootFunctionHandler.java)（处理器接口）、
[`LootTableProjector`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/catalog/LootTableProjector.java)（函数链求值 / 提示汇入物品）、
[`LootParseUtil`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootParseUtil.java)（函数链拼接）。

---

## 一、已核实的实现现状

| 项 | 事实 | 依据 |
|---|---|---|
| 原版函数名覆盖 | 注册名与 1.21.1 `LootItemFunctions` 的 **40 个**注册名一致；部分共用 `NULL_RANDOM`，**注册覆盖不等于语义解析完整** | `LootItemFunctions.java:26-77`；`LootFunctionHandlers:78-130` |
| 函数链求值入口 | `LootTableProjector.resolveItem` 逐条解码对象形式的 `TYPED_CODEC`；有函数条件时只取提示，无条件时尝试 `apply`，返回 null 才取提示。失败/未知会标记不确定性，并按分支及既有签名决定是否退化 | `LootTableProjector:186`、`:201-285` |
| 提示汇入物品 | 函数提示按 `joinFunctionHints` 拼成 `tooltipHint`；附魔变体走独立的 `ENCHANTED_HINT_KEY` 短路 | `LootTableProjector:299-307`、`:56`；`LootTableCatalog:339-340` |
| 附魔提示优先级 | `isEnchantedVariant` 在最终汇总时将提示替换成通用「附魔」，**可能覆盖此前已收集的提示**；它不是阻止前面 `describeHint` 调用的控制分支 | `LootTableProjector:245-263`、`:301-302` |
| 条件侧已用结构化元数据 | 条件把 `declared_chance_min/max`、`analysis_fidelity`、指纹等写入 `LootConditionInfo.metadata()` | `LootConditionHandlers`（`RandomChanceHandler:501` 等）；`DeclaredChance` |
| 函数侧缺少独立结构化描述 | 当前接口提供预览修改、签名派生、提示与不确定性判断，没有独立的函数参数描述/metadata 通道；不能据此说预览组件和签名本身也没有机器可读信息 | `LootFunctionHandler.java:28-68` |
| 模拟没有函数执行记录 | 最终 drops 按签名匹配、计数；没有保留逐产物函数链，现有子表身份观测也不能替代函数归因 | `LootProbabilitySimulationJob.java:235-280`；`LootSimulationScope.java:73` |

**核对结论**：当前不只是处理附魔与数量。除预览修改外，已有 **9 个**专门的 `describeHint` 实现（`set_count` / `set_damage` / `apply_bonus` / `limit_count` / `fill_player_head` / `set_stew_effect` / `exploration_map` / `set_fireworks` / `set_book_cover`）。缺口既包括附魔参数、包装与其他函数说明，也包括**条件语义和最终产物的函数来源**，不能只靠补文案解决。

---

## 二、当前信息损失与语义缺陷

### 2.1 附魔参数缺失与数量函数误分类

| 编号 | 事实 | 位置 | 依据 |
|---|---|---|---|
| F1 | 三个产物附魔 handler 及被误归类的 `enchanted_count_increase` handler，在被应用时仅按需将普通书预览提升为附魔书并派生 `ENCHANTED_APPROX`，不描述关键参数；非书物品不会一律变成书 | `LootFunctionHandlers:361-431`；`LootTableProjector:301` | 见下表字段列 |

| 函数 | 原版字段（源码位置） | 现状 |
|---|---|---|
| `enchant_randomly` | `options`（缺省使用注册表候选；显式空集合没有候选）、`only_compatible`（默认 true；实际候选还受兼容性筛选影响） | handler 不描述这些参数；派生为附魔签名时最终只显示「附魔」。原版空候选会原样返回，不能承诺必有附魔 |
| `enchant_with_levels` | `levels`（NumberProvider）、`options`（可选集合） | 缺少等级输入与候选说明；`levels` 不是最终附魔等级的承诺 |
| `set_enchantments` | `enchantments`（Map<附魔,NumberProvider>）、`add`（bool） | 缺少参数；**不一定确定**，等级可随机或依赖上下文，`add` 还依赖原栈等级 |
| `enchanted_count_increase` | `enchantment`、`count`（NumberProvider）、`limit`（0=无上限） | **误分类**：原版读取攻击实体附魔来增加掉落数量，不给产物附魔；当前 handler 却派生附魔签名 |

原版依据：`EnchantRandomlyFunction.java:61-75`、`SetEnchantmentsFunction.java:34`、`:65`、`EnchantedCountIncreaseFunction.java:69-84`。这些参数缺失与错误分类都需要处理；仅放开附魔提示覆盖不能修复签名语义。

### 2.2 结构 / 元函数未展开

| 编号 | 事实 | 影响 |
|---|---|---|
| F2 | `filtered` / `reference` / `sequence` 注册为 `NULL_RANDOM`，不展开内层函数；投影器也未解析数组形式的内联序列 | 被包装的规则不可见，按现有状态产生不确定性/近似结果；不能仅因包装存在就断言所有内层效果随机 |
| F3 | `set_loot_table` / `set_contents` / `modify_contents` 注册为 `NULL_RANDOM`，缺少容器语义说明 | `set_loot_table` 只写待生成表引用，`set_contents` 生成容器内部物品，`modify_contents` 修改已有内容；**不能把这些内容按普通子表边计入当次外层掉落** |

原版依据：`SetContainerLootTable.java:56-60`、`SetContainerContents.java:53-65`、`ModifyContainerContents.run`。静态图没有展开这些内容，并不证明原版执行时没有生成容器内容；收录关系、容器内容与外层掉落统计需要分别讨论。

### 2.3 配方依赖与公式参数缺口

| 编号 | 事实 | 依据 |
|---|---|---|
| F4 | `furnace_smelt` 注册为 `NULL_RANDOM`；原版按实际输入及当前服务端熔炼配方求结果，无配方时保留原物品。**仅凭表 JSON 不能保证静态确定最终产物** | `SmeltItemFunction.java:40-55`；输入栈还可能受前序函数影响 |
| F5 | `apply_bonus` 只出「附魔名 + 公式类型名」，公式参数（`extra`/`probability`/`bonusMultiplier`）未捕获 | `LootFunctionHandlers:661`；`ApplyBonusCount.java:111,179` |

### 2.4 结果归因与函数条件缺口

| 编号 | 事实 / 推论 | 依据 |
|---|---|---|
| F6 | 不同函数链可能生成完全相同的物品；现有最终签名匹配无法唯一反推实际函数链 | 源码核实：`LootProbabilitySimulationJob.java:254` 调用 `LootResultMatcher.resolve`；非唯一性例证：固定数量 2 与随机数量 1～3 都可产出 2 个钻石 |
| F7 | 函数自己的 `conditions` 被并入条目条件；函数不执行通常不等于原物品不掉落 | `LootTableProjector.java:208-212`；原版 `LootItemConditionalFunction.java:36-37` 条件失败返回原栈。数量为零或身份变换等效果仍需分别判断 |
| F8 | 动态发现的结果被构造为 `injected=true`；动态发现也可能来自未静态解析的原版变换，不能一概认定为平台注入 | `ArchaeologyJournalServerCatalog.java:874-879`；`LootProbabilitySimulationJob.java:274` 起的动态签名分支；`LootTableCatalog.buildDiscoveredDefinition` 本身只透传调用方的 injected 参数 |
| F9 | 函数返回新栈、原版拆栈及平台后处理可能打断原对象关联；按物品/组件相同回退不能证明唯一函数来源 | 原版 `SmeltItemFunction.java:50`、`../LootTable.java:70`；项目 `LootSimulationScope.java:73` 的回退语义 |

---

## 三、已纳入规划的改进方向

展示与结构化描述不是二选一。规划选择**结构化规则作为数据源，派生展示文本，并辅以模拟执行观测**。静态规则保留未命中分支的信息；运行时只回答当前输入实际观察到了什么。具体优先级、API 和阶段以 [实施规划](../plan/loottable-function-capture-plan.md) 为准，以下仅列本审查涉及的缺口。

### 参数说明与语义修正

| 函数 | 捕获字段 | 形态 |
|---|---|---|
| 三个产物附魔函数（F1） | `options` / `levels` / `enchantments` / `only_compatible` / `add` 等规则参数 | 描述与预览独立，再移除最终提示覆盖；保留既有附魔签名折叠 |
| `enchanted_count_increase` | 攻击实体附魔 + 增量提供器 + 上限 | 先修正数量函数分类和签名，再补说明；不默认等同于模拟所选工具附魔 |
| 函数条件（F7） | 条件及其约束的具体效果 | 保留在函数节点上，不一概并入基础物品获取条件 |

### 包装函数与容器说明

| 函数 | 做法 |
|---|---|
| `sequence` | 按顺序展开，包括内联数组；是否能精确求值仍取决于内层函数和条件 |
| `filtered` | 描述 `item_filter` 与 modifier，不能把内层效果当成无条件应用 |
| `reference` | 保留引用 ID；正确 lookup 可用时有界展开，缺失/循环/不可用时标部分解析；不要求构建全量运行时对象图 |
| `set_loot_table` / `set_contents` / `modify_contents` | 分别提示延迟生成、设置内容、修改内容；本轮不将内部物品计为外层掉落，也不做内部逐物品运行时归因 |

### 其他参数与实际产物

| 函数 | 捕获字段 |
|---|---|
| `apply_bonus` | 公式参数（`extra`/`probability`/`bonusMultiplier`） |
| `set_attributes` | `modifiers` 的属性名 + 数值范围 + 槽位 + `replace` |
| `furnace_smelt` | 描述熔炼效果，通过模拟取得实际产物及执行观测；规划不另建静态配方执行器 |
| `set_potion` | 药水名 |
| `set_stew_effect` | 效果 `duration` |

### 运行时对应关系（F6 / F8 / F9）

规划采用模拟作用域内的公共函数执行捕获与局部拆栈关联，将观测链汇入最终结果。**不要求运行时函数对象精确映射到 JSON 的 table/pool/entry 位置**；无法可靠捕获的部分如实降级。确认的平台注入统一标为“模组联动”，无法确认的动态来源不能仅凭匹配失败使用该标签。

---

## 四、不展开完整细节的范围（不是禁止记录函数类型）

下表是展示/解析深度的取舍，不表示这些函数一定不影响产物，也不要求公共执行捕获入口跳过它们。可保留类型、简短说明及保真度，避免全量传输长文本和实例数据。

| 函数 | 理由 |
|---|---|
| `copy_components` / `copy_custom_data` / `copy_name` / `copy_state` | 只描述复制来源和类别，不展开实体/方块实体实例内容；复制结果仍可能影响物品组件和匹配 |
| `set_custom_data` | 数据来自函数声明，不等同于运行时复制；当前已有预览处理，但不要求将完整自定义数据变成提示文本 |
| `set_written_book_pages` / `set_writable_book_pages` | 长文本，展示噪音大 |
| `set_firework_explosion` / `set_banner_pattern` / `set_instrument` / `set_custom_model_data` | 不优先展开全部细节；这些组件仍可能区分产物变体，不能说“对获得什么无信息量” |
| `toggle_tooltips` | 主要改变提示显示相关组件，不直接增加掉落数量；不能概括为完全不改变产物 |
| `explosion_decay` | 无函数自有数值参数，但读取 `EXPLOSION_RADIUS`。当前默认 profile 半径为 0，允许该参数时会填入；参数缺省或为 0 时不减少数量，**存在值为 0 的参数时仍逐件消耗随机数，不能跳过执行** |

爆炸衰减依据：`ApplyExplosionDecay.java:34-48`；项目 `LootContextParamFiller.java:75-80`、`:155-156` 与 `SimulationProfile.java:45-58` 的 `eligibleConditions`。数量不变不等于整个随机过程的恒等操作，也不能推广到其他半径或真实游戏上下文。

---

## 五、捕获的整体一致性要求

- 新增展示形态必须同步 `en_us.json` 与 `zh_cn.json`（见项目 i18n 规范）。
- 函数提示与预览分别求取；移除 `LootTableProjector:301` 的覆盖后，需确认附魔书、附魔物品和其他函数参数能共同展示。
- 描述规则与结果签名是不同职责。补提示不必拆分 `ENCHANTED_APPROX`；数量函数误分类导致的错误签名则需要单独修正，不能批量迁移可能也被真实附魔路径使用的历史键。
- 结构化函数描述复用条件侧保真度约定，但不将函数伪装成条件；获取路径相同时仍需保留函数规则差异。
- 静态声明、当前输入的模拟观测、未知来源和确认的模组联动分别表达；不把观测片段说成完整生成过程。

---

## 六、未验证项与已明确的局限

1. **收录范围内各函数的实际出现频次未实测**：上述捕获范围依据字段的信息量和语义缺陷，不代表整合包/原版表的实测分布。若需按使用频率调整后续投入，可另行扫描收录范围统计。
2. **展示与签名解耦后的兼容性待验证**：避免签名爆炸依赖结果签名折叠，不是依赖隐藏参数文本；补描述后仍需验证聚合、缓存、同步及附魔书/附魔物品的展示。不能继续把通用提示覆盖解释为防止签名爆炸的必要机制。
3. **不同阶段的 lookup 可用性待验证**：投影器已接受 `HolderLookup.Provider` 并构造 `RegistryOps`（`LootTableProjector.java:75-82`），不能笼统说编译/投影全程只有 JSON、完全没有注册表。是否包含可展开的 `ITEM_MODIFIER` 要核对具体调用方与阶段；原版 `FunctionReference.java:61` 会在运行时通过 resolver 获取引用。`set_loot_table` 本轮只说明延迟生成引用，不需要当场展开。
4. **捕获入口与成本未验证**：公共 `run` 与局部 splitter 的双平台 remap、其他 Mixin 共存、对象关联和容量预算均需实施后验证；一个公共入口不能保证所有第三方函数、复制及后处理都被捕获。
5. **已接受限制**：不精确定位运行时 JSON 位置，不追查第三方修改器内部，不逐物品展开容器内容。未知来源不是平台注入的充分证据；确认的注入按规划显示“模组联动”。

---

## 七、相关文档

- [函数捕获实施规划](../plan/loottable-function-capture-plan.md) — 当前目标、技术路线、已接受限制及验证矩阵；由用户实施后交助手审查
- [战利品表系统](../dev/subsystems/loottable.md) — 机制权威描述
- [战利品表机制细节](../dev/internals/loottable-mechanics.md) — 签名、场景规划、缓存与展示派生
- [战利品条件树](loottable-condition-tree.md) — 条件（predicate）侧的同类待办
