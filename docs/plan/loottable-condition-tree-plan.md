# 战利品条件树修复计划

> 状态：**开发侧已实施，待用户实机验证**（2026-09-22）。
> 当前机制的唯一权威描述是 [战利品表系统](../dev/loottable.md)；本文记录修复顺序、设计选择和验证口径，不作为现行机制说明。
> 本计划依据 [条件树审查报告](../todo/loottable-condition-tree.md) 与工作区静态复核制定；原版 API 细节和游戏内行为仍待实施阶段验证。

## 一、现状

### 1.1 现有实现

本节记录的是**实施前状态**。`LootParseUtil` 从 JSON 解码条件，`LootConditionHandlers` 生成带保真度标记的条件树，`PathHintAnalyzer` 根据路径条件派生网格状态。条件树经目录网络协议下发，客户端用 `JournalTooltipBuilder` 展示。完整链路与已覆盖范围见审查报告第一节。

### 1.2 需要改造的耦合点

| 耦合点 | 现状依据 | 计划动作 |
|---|---|---|
| 解码失败回退 | `LootParseUtil.java:76` | 回退节点标记 `unreadable` |
| 无注册 id 的条件对象 | `LootConditionHandlers.java:197-220` | 保留一个可定位的回退节点 |
| 条件说明与保真度 | `LootConditionHandlers.java:349-360,761-810` | 修正区间文本，保留实体类型与子谓词 |
| 零命中派生 | `PathHintAnalyzer.java:60-85,119-135,231-255` | 让未解析条件参与展示判定，但不声称它一定阻止掉落 |
| 提示数据模型及渲染 | `PathHint.java:18-39`、`ProbabilityFormat.java:118-165` | 增加专门的未解析条件提示，检查对应网络 codec |
| 场景名称 | `ScenarioLabel.java:37,139-149` | 保持父节点 i18n key 与折叠约定 |

### 1.3 现存缺陷

审查报告将 D1、D2、D3 归为信息丢失，D4 归为误报，D5、D6 归为静默与扩展性问题，D7 归为展示问题。当前 `LootConditionHandlers.java` 有未提交的工作区改动；实施时须在该版本上继续，不覆盖这些改动。

## 二、目的

### 2.1 本轮目标

1. 条件解码或识别失败时，树中仍有节点、保真度正确、服务端能定位表和类型。
2. 未解析条件存在且抽样零命中时，网格不把结果简单归结为“未命中”，也不推断该条件必然是原因。
3. 同时存在的实体类型和 `type_specific` 都能展示；已知状态区间不再显示原始 JSON。
4. 根据实际收录表中的字段分布，优先补最有收益的谓词描述。

### 2.2 架构目标

保留 `LootConditionInfo.source()` 供服务端机器逻辑使用；文本捕获只用于描述和场景名。新增展示状态沿用现有数据模型与目录同步流程，不另建一套解析器。

### 2.3 本轮范围外

不展开 `reference` 的注册表内容，不递归展示 NBT、组件、深层实体谓词，不修改场景规划对第三方条件的保守处理。第三方通用扩展点先形成 API 提案，只有明确调用方或数据包需求时再实施。

## 三、技术原理

| 依据与证据等级 | 对路线的约束 |
|---|---|
| **工作区源码核实**：`LootParseUtil.java:76` 的失败分支直接调用 `fallbackInfo`；`LootConditionHandlers.java:244` 已提供 `unreadable` | 先复用现有保真度语义修 D1 |
| **工作区源码核实**：`analyzeAll` 只在 condition id 非空时回退；`PathHintAnalyzer.collect` 只收三类已知条件 | D2、D4 必须分别在解析层和展示派生层修，不能只改 tooltip |
| **工作区源码核实**：`EntityPropertiesHandler` 在类型分支提前返回；状态属性编码后将非 primitive 值原样输出 | D3、D7 可局部修复 |
| **审查报告的源码核对，实施前需复验**：部分谓词内部成员可能不可访问 | 优先使用 public 访问器；确实不可访问时才按现有模式通过 CODEC 回读。若仍不确定原版 API，按项目分级准则查询 MCP；不得自行反编译 |
| **推断，待验证**：未知条件即使存在，也不一定是本次零命中的原因 | 展示“条件未解析”比“需要满足该条件”更准确，同时保留原始测量值供 tooltip 说明 |

必须遵守的边界：`common/` 不引用平台类；服务端不引用客户端类；新的 HUD/GUI 文案同步 `en_us.json` 和 `zh_cn.json`；条件树新增子项不改变组合条件的递归含义。

## 四、技术路线

### 4.1 第一阶段：止损与可诊断性

1. 修 D1：JSON 解码失败节点加 `unreadable`。修 D2：类型 id 为 null 时也生成回退节点，并保留 `source`、指纹与稳定性元数据；无法获取真实 id 时用明确的占位 id。
2. 修 D5：在目录构建过程中按“表 id + 条件类型”去重记录告警，给出可操作的定位信息；重载后允许重新报告，避免进程全局集合无限增长。解码失败已有日志，不重复刷屏。
3. 修 D4：给 `PathHint` 增加“路径含未解析条件”的信息性提示，检查 `CatalogStreamCodec` 的穷尽编码；仅当结果为零命中或未覆盖时影响展示，正数测量及静态不可达仍优先。网格和 tooltip 用独立本地化文本表达“条件未解析”，不把它解释为已证实门槛。已知参数提示仍按原顺序保留。

### 4.2 第二阶段：修正已有描述

1. 修 D3：`EntityPropertiesHandler` 累积 `entityType` 与 `subPredicate` 子行；若还有未展示字段，父节点继续标 `partial`，不把“显示了两项”误作完整解析。
2. 修 D7：`describeStateProperties` 把编码后的 `min`/`max` 形态写成可读区间，单端与双端边界均覆盖；异常形态保留安全回退。
3. 检查 `ScenarioLabel` 对父节点 key 的折叠，以及两种语言中的多子行顺序、斜体和条件类型颜色。

### 4.3 第三阶段：按使用量补谓词捕获

1. 先统计本项目及当前可取得的数据包内，收录表实际出现的条件类型和谓词字段；统计范围、资源来源和数量随结果记录，不能把本地数据当作所有第三方模组的分布。
2. 优先补审查报告 §3.2 的四项低成本字段：`enchantment_active_check.active`、`value_check.range/provider`、`table_bonus.enchantment/values`、`random_chance_with_enchanted_bonus` 的附魔侧参数。每项检查运行时语义与 `partial` 标记。
3. 再按统计结果选择 `location_check` 的 light/position/block/fluid、`match_tool.count`、`type_specific` variant。B 类访问性先作最小编译核实，再决定直接解构还是 CODEC 回读；不要一次展开全部 18 种子谓词。
4. 每批新增描述同步两份 lang；保留父节点的 `condition.*` key，避免场景名折叠行为暗中变化。

### 4.4 第四阶段：扩展点决策

梳理内部 `register` 的生命周期与调用方；若有明确第三方接入需求，再设计“按条件 id 注册描述器”的最小公共 API。数据包配置式描述器涉及类型校验、翻译、安全回退和同步边界，另列任务，不与缺陷修复混做。

### 4.5 文件改动清单

预计涉及 `LootParseUtil.java`、`LootConditionHandlers.java`、`PathHintAnalyzer.java`、`PathHint.java`、`ProbabilityFormat.java`、`CatalogStreamCodec.java`、`en_us.json`、`zh_cn.json`，必要时调整目录构建阶段的诊断入口与 `ScenarioLabel`。若改变机制行为，同步更新 `docs/dev/loottable.md`，客户端或网络协议变化分别核对 `docs/dev/client-ui.md`、`docs/dev/network.md`；完成后处理审查报告状态。

## 五、决策记录

| 编号 | 决策 | 原因及未采用方案 |
|---|---|---|
| C1 | 未解析条件单独提示，零命中时显示“条件未解析” | 直接当成“需要条件”会暗示它一定阻止掉落；继续显示“未命中”会隐藏解析缺口 |
| C2 | 描述字段按实际分布扩充 | 全量展开实体和 NBT 递归谓词成本高，且可能让 tooltip 失去可读性 |
| C3 | 保真度在解析层标记，展示层消费 | 只改颜色或斜体会遗漏机器消费路径及组合父节点传播 |
| C4 | 通用第三方扩展点暂不实施 | 尚无明确外部调用方与稳定配置格式，先完成可诊断的降级 |

## 六、风险与限制

| 风险 | 处置 |
|---|---|
| `PathHint` 增型影响网络 codec、缓存和穷尽 `switch` | 实施时全局搜索读写点，确认编码与解码对称，必要时更新协议版本 |
| 文案增加导致场景名称变化 | 保持父节点 i18n key；检查折叠与多子项输出 |
| 第三方条件在本地样本中缺席 | 使用审查报告的静态缺陷作为修复依据，实机由用户用带第三方条件的数据包验证 |
| `partial` 被过早移除 | 逐字段核查所有仍未展示的约束，无法证实完整时继续标记 |
| 未提交的源码改动被覆盖 | 在当前工作区增量修改，不重置或覆盖现有 diff |

## 七、验证方式

**开发执行**：每阶段检查受影响文件的 IDE 诊断，再执行一次 `./gradlew build`；构建等待至任务结束，避免并发构建。不新增 test 文件。静态核对 D1/D2 的回退节点与 metadata、D3 双字段、D7 单端和双端区间、未解析提示的网络编码及两份翻译键。

**用户实机验证**：用含未知条件的第三方表确认零命中网格与 tooltip；用带 `type` + `type_specific` 的表确认两行均显示；用区间状态谓词确认文案；在 Fabric 和 NeoForge 分别查看场景名、条件树和目录重载后的提示。

## 八、待确认项

- 第三阶段的排序以实际分布为准；仅凭审查报告无法断言哪些谓词在第三方整合包里最常见。
- B 类访问性和部分原版条件的运行时语义须在实施时按项目 API 分级准则核实。
- 扩展点是否需要数据包配置格式，取决于真实接入需求，暂不承诺实现。

## 九、实施结果（2026-09-22）

- 已修复 D1、D2、D3、D4、D5、D7；未解析条件加入独立 `PathHint` 类型，零命中时网格显示“条件未解析”，并同步网络编码、目录摘要及双语文案。
- 已补第一批四类条件参数描述。`random_chance_with_enchanted_bonus` 的附魔侧值仍用 CODEC JSON 展示并标 `partial`，尚未把所有 `LevelBasedValue` 子类型翻译为自然语言。
- 扫描本项目 `common/src/main/resources/data/unsuspiciousblock/loot_table` 下 13 张 JSON 表：`random_chance` 12 处、`entity_properties` 2 处、`location_check` 1 处、`survives_explosion` 1 处。本地没有第二批复杂谓词的实例，因此本轮未扩大到那批字段；此统计不代表第三方模组。
- D6 的数据包/配置级第三方扩展点仍需真实接入方和格式需求，本轮保留为独立待办；现有 Java `register` 不变。
- IDE 首轮检查发现并修复一处局部变量重名，最终检查无错误或警告；`./gradlew build` 在协议版本调整前后各通过一次，Fabric 与 NeoForge 均构建成功。客户端 S2C 协议版本由 `4.6` 升至 `4.7`。
- 用户实机仍需验证未知条件、实体双字段、状态区间文案、目录重载及 NeoForge/Fabric 两端显示。
