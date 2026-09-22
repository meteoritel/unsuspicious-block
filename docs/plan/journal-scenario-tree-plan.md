# 场景页条件树优化（S6）——决定与实施

> 前置：[journal-ui-redesign-plan.md](journal-ui-redesign-plan.md)（S2–S4 已落地）。
> 本轮起因：用户实机截图指出条件树可读性不足；用户在本次会话中确认了取舍（见第 2 节）。

## 0. 起因：现状的 8 个问题

对 S2–S4 落地的场景页做通读，条件树的口径本身是对的（`SimulationScenario.assumptions` 是指纹→bool
的完整赋值，取真值即「相对基准的差异」），但落到玩家眼里的呈现有 1 个硬 bug 与 7 个可读性问题：

| # | 问题 | 证据 |
|---|---|---|
| P1 | **基准页声称自己没有条件**：基准的赋值全为 false，被 `positiveAssumptions` 过滤成空 → 框内显示「无场景条件」，tooltip 的「全称」同样为空 | `ScenarioPageBuilder.java:53-57`、`:69-93` |
| P2 | **条件的取值与条件本身分处两行**：`location_check` 的指纹在父节点、取值在子节点，于是必然出现「需要特定位置/生物群系」+「└ 群系: #cis_swamp」 | `LootConditionHandlers.java:597-656` |
| P3 | **数字只能在 tooltip 里逐个悬停**；且同一物品挂多条条件时，每行显示的都是该物品在本场景的**总概率**，读起来像「每行各一份」 | `ScenarioPageBuilder.java:110-134` |
| P4 | **场景没有可读名**：页标题是 `#1 O ∧ B` 字母助记（认不出即退化为 A/B/C），列表行只有「场景 3 · 未计算」 | `ScenarioPageBuilder.java:64-67`、`:216-235`（R9） |
| P5 | **长文本被静默裁掉**：kit 契约 I5 是「自然尺寸、不折行不截断」+ scissor 裁剪，无省略号、无滚动提示 | `UiDocument.java:186-207` |
| P6 | **未计算态与已缓存态在框内无法区分**（只有框外读数行的角标） | `ScenarioDetailPanel.java:187-191` |
| P7 | **网格页仍用旧头部与旧下拉**：同一下拉有「旧原生式」与「新浮层式」两份，文案来源也不一致 | `RightPageContainer.java:178-179`、`:410-414`（R7） |
| P8 | 场景列表的**排序/来源不统一**：`SimulationOptions.from` 用 planner 顺序，`CatalogTableDto.from` 用 key 字典序 | 前置规划 §2.5 问题 1 |

## 1. 客户端拿得到什么（不新增协议）

- `options().scenes()` = planner 顺序的 `ScenarioAssumptions(scenarioKey, assumptions)`；`assumptions` 恒为完整
  赋值：为真放原条件，为假放合成的 `minecraft:inverted` 包装（内层才是真条件）。
- 每条 `LootConditionInfo` 带已本地化 `description`、`children`（递归）、`metadata`
  （`simulation_fingerprint` / `declared_chance_*` / `analysis_fidelity`）。
- 测量结果 `CatalogTableDto`：物品带 `probability` 与该输入的 `scenarioProbabilities`（`ScenarioRef(key, 概率)`）。
- 参数与选择：`ScenarioSimulationClientState.selection(table)`；每表一套，场景页与网格页共用同一选择。

**本轮不新增任何网络包，不改服务端。**

## 2. 已定决策（用户确认）

| # | 问题 | 结论 |
|---|---|---|
| D1 | 条目与数字怎么呈现 | **条件树 + 独立产出区**：条件行只讲场景定义；框内下方按概率降序列出「本场景可达条目（图标 + 名称 + 概率）」。数字不再挂在条件行上，因此不存在「同一物品在多行各显示一次总数」 |
| D2 | 场景可读名 | **做**。页标题与两处场景列表都改用从条件生成的短标签（如「开阔水域 + 群系 cis_swamp」），字母公式退到 tooltip |
| D3 | 长文本 | **不做截断，做超宽悬停滚动**，复用既有 `ScrollTextHelper` 的口径（暂停—滚动—往返），并统一到 kit 供两处使用 |
| D4 | 网格页旧下拉 | **换成场景页同一套浮层**，收掉 R7 |
| D5 | 未计算提示行 | 不做独立提示行（随 D1 的产出区自带空态文案即可） |

## 3. 设计

### 3.1 框内结构（新）

```
[条件区]  本场景成立的条件                     ← 区标题（TITLE），tooltip = 全称 + 「其余可调条件不成立」
            群系: #cis_swamp                 ← 条件行（BODY，indent 12）
            开阔水域
            └ 任一满足 …                      ← 多子节点时保留分组行并画连线
[产出区]  ────────────────（Divider）
          可达条目 4 项                       ← 区标题（TITLE）
            🐟 生鳕鱼 12.5%                   ← 行首物品图标 + 名称 + 概率（BODY）
            🥩 河豚 3.2%
```

- **条件区标题三态**：表本身无旋钮条件 → 沿用 `no_assumptions`；基准（有旋钮条件但全为假）→ 新键
  `baseline_all_false`（带数量，tooltip 列出全部被置假的条件）；其余 → 新键 `scene_conditions`。
- **可达条目区**：只列 `Probability.isMeasured() && !isZeroHit()` 的条目（即本场景确实能刷到的），
  按 `lowerBound()` 降序，最多 12 条，其余用新键 `outcome_more` 提示「见网格页」。
  空态：未计算 → 新键 `outcome_uncomputed`；已计算但无可达条目 → 新键 `outcome_empty`。

### 3.2 折叠规则（改 P2）

递归地：**节点的 `metadata` 无 `simulation_fingerprint`、类型不是 `minecraft:inverted`、且恰好只有一个子节点**
→ 以子节点替换自身。`location_check{biomes}` 因此折叠为一行「群系: X」。多子节点保留分组行（它是真 AND/OR）。

### 3.3 场景短标签（改 P4）

`ScenarioLabel`（新，`client/ui/support/`）：对每个为真的假设递归收集**叶子**的 `description` 全文
（折叠规则同上，父节点不贡献文本），用 `" + "` 连接；过长由渲染侧滚动；超长全称放 tooltip。
基准 → 键 `simulation.baseline`；无正条件且非基准（当前不可达）→ 退回首字母公式。

### 3.4 kit 扩展（改 P3/P5）

| 扩展 | 内容 |
|---|---|
| `TextScroll`（新，kit） | `draw(graphics, font, Component, x, y, maxWidth, color, hovered, ticks)`：不超宽照常画；超宽且悬停时按「起点停顿 → 连续位移 → 终点停顿 → 往返」滚动，裁剪用 pose 感知的 scissor。`support/ScrollTextHelper` 改为委托它（保持既有调用点不变） |
| `UiDocument` | `render` 增加 `(mouseX, mouseY)` 重载：逐行判悬停，超宽行文本用 `TextScroll`；每行自持 tick 计数（悬停才前进）。旧 `render(graphics, font)` 保留（等价于无悬停） |
| `UiControl` | 标签超宽且悬停时同样滚动（页标题、场景列表行、既有按钮一并受益） |
| `UiNode.Row` | 增加 `@Nullable InlineIcon leading`（行首图标，画在缩进之后、文字之前，带自己的 tooltip/payload/action 与命中矩形）；旧签名保留为便捷构造 |
| 契约 | **修订 I5**：「框内按自然尺寸排版，不折行；超出视口宽度的行文本在悬停时滚动，tooltip 恒有全文」 |

### 3.5 场景下拉统一（改 P7）

`ScenarioSelectionOverlay` 从「持有 `ScenarioDetailPanel`」改为**数据 + 回调**构造：

```java
ScenarioSelectionOverlay(OverlayLayer layer, int anchorX, int anchorY,
        ResourceLocation table, SimulationOptions options, int currentIndex,
        ScenarioParams params, IntConsumer onSelect)
```

- 场景页按钮：`onSelect = this::setPage`（保持既有的保存视图语义）。
- 网格页头部「切换场景」按钮：`onSelect = index -> ScenarioSimulationClientState.select(table, key, params)`
  （停留网格页，选择变化即让网格数字切到该场景）。
- `ScenarioPanel` 删除 dropdown / dropdownPage / renderOverlay 的下拉分支与 rows 列表渲染（该渲染自 S2 起已
  无调用点），只保留网格页头部（读数行 + 切换按钮 + 计算按钮）。

## 4. 任务清单

| # | 任务 | 文件 | 验收点 |
|---|---|---|---|
| T1 | kit：`TextScroll` + `ScrollTextHelper` 委托 | `kit/TextScroll.java`（新）、`support/ScrollTextHelper.java` | 既有 12 处调用行为不变；超宽文本悬停滚动 |
| T2 | kit：`UiNode.Row.leading` | `kit/UiNode.java` | 旧构造点零改动编译通过 |
| T3 | kit：`UiDocument` 悬停滚动 + 行首图标 | `kit/UiDocument.java`、`panel/ScenarioFrameView.java` | 行首图标有独立 tooltip/命中；文本不重排（I3） |
| T4 | kit：`UiControl` 标签悬停滚动 | `kit/UiControl.java` | 页标题/列表行超宽可滚 |
| T5 | support：`ScenarioLabel` | `support/ScenarioLabel.java`（新） | 折叠 + 短标签 + 全称 tooltip；基准文案不再是「无场景条件」 |
| T6 | panel：框内重构为条件区 + 可达条目区 | `panel/ScenarioPageBuilder.java`、`panel/ScenarioDetailPanel.java` | 见 §3.1；产出区按概率降序、只列可达、上限 12 |
| T7 | panel：下拉统一 | `panel/ScenarioSelectionOverlay.java`、`panel/ScenarioPanel.java`、`panel/RightPageContainer.java` | 网格页切换按钮打开同一浮层；旧下拉删除 |
| T8 | i18n | `lang/en_us.json`、`lang/zh_cn.json` | 新键成对、占位符一致 |
| T9 | 文档 | `docs/dev/client-ui.md` 4.5、本文件 | 契约 I5 修订、下拉统一、产出区口径 |
| T10 | 验证 | IDEA MCP 检查 → `./gradlew build` | 无新增错误；实机交由用户 |

### 4.1 新增 i18n 键（`...archaeology_journal.simulation.`）

| key | zh_cn | en_us |
|---|---|---|
| `baseline_all_false` | 基准：可调条件（%s 个）全部不成立 | Baseline: all %s adjustable conditions are false |
| `scene_conditions` | 本场景成立的条件 | Conditions holding in this scene |
| `conditions_others_false` | 其余可调条件不成立 | All other adjustable conditions are false |
| `outcome_header` | 可达条目 %s 项 | %s reachable entries |
| `outcome_uncomputed` | 尚未计算：点「计算」获取本场景的条目概率 | Not computed yet: press Calculate |
| `outcome_empty` | 本场景下没有可产出的条目 | No reachable entries in this scene |
| `outcome_more` | 另有 %s 项，见网格页 | %s more entries, see the grid page |
| `label_separator` |  +  |  +  |

## 5. 风险

| # | 风险 | 处置 |
|---|---|---|
| R1 | 产出区与网格页的条目数字**同源重复**（两页共用同一选择） | 产出区只列可达项且上限 12，定位为「不离开场景页就能看清这个场景能刷到什么」；若实机觉得冗余，后续可改为「相对基准的差异」（需同时取基准测量，见 §6） |
| R2 | 悬停滚动与既有连线绘制共用 scissor | 滚动用 pose 感知 scissor，且裁剪切换前 flush；与 `UiTransform.enableScissor` 的入向取整保持一致 |
| R3 | `UiControl` 全量加滚动会改变既有按钮观感 | 只在文本确实超宽时生效，宽度足够时行为与现在逐像素一致 |
| R4 | 短标签可能仍偏长（多条件场景） | 渲染侧滚动 + tooltip 全称；不引入新的截断策略 |
| R5 | 折叠规则误伤分组语义 | 仅折叠「无指纹 + 非 inverted + 恰好一个子节点」，多子分组与 inverted 一律保留 |
| R6 | 删除 `ScenarioPanel` 下拉会动到网格页事件分发 | 网格页点击路径改为「切换按钮 → 打开浮层」；`dropdownOpen()` 相关的守卫同步移除，避免留下永不成立的判断 |

## 6. 暂不做（留给后续）

- 产出区的「相对基准差异」视图（需要基准测量与场景测量同时在手）。
- 条件行与条目行之间的「哪些条目受这条条件影响」映射（D1 明确放弃）。
- 跨场景并排对比。
- 网格页头部补「参数」按钮：参数浮层由场景页入口打开，网格页目前只能看不能调。

## 7. 实施结果（2026-09-22）

T1–T10 全部落地：

- **kit**：新增 `TextScroll`（`support/ScrollTextHelper` 改为委托，既有 12 处调用行为不变）；`UiNode.Row` 增加 `leading`；`UiDocument` 支持行首图标与「行文本带 + 悬停滚动」，文本带以视口宽度（1 倍档参考）为上限，因此不再把图标挤出视口；`UiControl` 标签同样悬停滚动。
- **support**：新增 `ScenarioLabel`（纯分组父行折叠 + 可读名 + 完整定义）；字母助记公式连同它的键后缀契约一并**删除**（可读名取代，R9 随之关闭）。
- **panel**：`ScenarioPageBuilder` 重写为「条件区 + 可达条目区」；`ScenarioDetailPanel` 标题改用可读名；`ScenarioSelectionOverlay` 改为「数据 + 回调」构造；`ScenarioPanel` 收缩为网格页头部（旧参数行、幸运值框、自绘下拉删除），下拉与场景页共享同一浮层。
- **i18n**：新增 7 个键（中英成对，占位符一致），JSON 校验通过。
- **验证**：IDEA MCP 无错误（余留 kit 既有的 `contentWidth/contentHeight` 未使用告警）；`./gradlew build` **BUILD SUCCESSFUL in 1m 59s**，33 任务。实机表现待用户验收。

修改过的旧契约：kit 的 I5 由「自然尺寸、不折行不截断」改为「不折行；超宽行文本悬停滚动，tooltip 恒有全文」；`UiDocument.render` 增加带鼠标位置的重载（旧重载保留）。
