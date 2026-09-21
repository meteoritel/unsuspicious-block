# 考古笔记前端重构——决定与架构

> 本文是 2026-09-21 前端重构讨论的**决定与架构文档**：记录**已定决策（最终口径）**、**事实勘察结论（附证据）**、**实现架构与不变式**、**风险与待实测项**。
>
> 与 [战利品表条件场景重构规划](loottable-scenario-refactor-plan.md) 的分工：那份规划管"后端能提供什么"，本文管"前端如何呈现与交互"。本文若有条目与前者的决策冲突，会在该条目上显式标注「收窄 / 替代」。
>
> 执行拆点、验收清单与 i18n 草案见 [journal-ui-redesign-tasks.md](journal-ui-redesign-tasks.md)。
>
> 2026-09-21 修订：用户确认前序实机验收后，本轮完成 S2 / S3 / S4 代码与双平台构建。第 2 节保留实施前勘察；当前机制以 [客户端与 GUI](../dev/client-ui.md) 第 4.4–4.6 节为准。后端不变；本轮新增交互待用户验收，S5 未实施。
>
> 源码路径均省略前缀 `common/src/main/java/com/meteorite/unsuspiciousblock/`。

---

## 0. 触发原因

实机验收 P2 时发现两类问题，并提出了三条新要求：

| 来源 | 内容 |
|---|---|
| 实机 bug 1 | 场景下拉列表对 tag 合集/子表条目存在**鼠标穿透**（底层物品格格子的 tooltip 穿透浮层显示），对普通物品没有 |
| 实机 bug 2 | **每次切换幸运值都会自动计算并缓存**，留下大量无关缓存 |
| 新要求 1 | 用 Python 绘制新的 GUI 材质资源，以保证 UI 设计 |
| 新要求 2 | 参数改为「点击展开 + 确认按钮」，确认后才生效；只有「选中场景 + 点计算」才进入计算阶段 |
| 新要求 3 | 新增 UI 组件没有 resize 持久化，要求审查并给出「不必每次新增组件都手动注册持久化」的方案 |

---

## 1. 已定决策（最终口径）

> 每条都经过至少一次提问与确认。**标「曾定」者表示中途有过相反决定，一律以本表为准。**

| # | 决策 | 备注 |
|---|---|---|
| D1 | **参数区**：点图标按钮 → 展开**叠加层**；能枚举的用按钮（工具、抽样次数、附魔等级），**只有幸运值用一个单行 `EditBox`**；确认后才生效 | 曾定「页内内联展开」，后改为叠加层 |
| D2 | **参数作用域**：全局一套，切场景不重置（工具与幸运值是玩家处境，不是场景属性） | |
| D3 | **参数按钮**：固定图标，当前参数在它的 tooltip 里 | |
| D4 | **计算触发**：只有「选中场景 + 点计算」才发起；**取消防抖自动请求** | 收窄规划决策 43 |
| D5 | **场景切换**：场景页一页一场景 + 页内场景下拉（跳转）；**相邻切换复用屏幕级原生分页按钮**，头部不放箭头 | 曾定「网格页步进器 + 下拉」，网格页出范围后收敛 |
| D6 | **场景标题**：**简短序号 + 数学符号公式**（`∧` / `¬`），**不含取值**；全称在 tooltip | 替代 `simulation.scene`；字母映射风险见 4.4 |
| D7 | **场景页形态**：每页一个场景，原列表条目成为页面标题；**条件树放在一个固定大小的框内**（可拖动、可缩放、内容不超出框，原版进度界面式） | 替代规划决策 4 的卡片列表 |
| D8 | **物品只挂在条件树里**：条件行内、文本后跟一排物品图标；**不碰网格页** | |
| D9 | **布局与状态**：**面板自注册**（`applyLayout(BookLayout)` + `saveUiState/loadUiState(CompoundTag)`），由屏幕遍历面板而非手写清单 | |
| D10 | **贴图**：框底/边框 + 状态角标四态；其余代码 `fill` + 复用 `toolbar_icons.png` | S4 用户确认：框使用 24×24 九宫格、8px 四角，同图服务两种尺寸 |
| D11 | **协议**：允许扩，只在新数据确实无法从已有 payload 推导时才加；**本设计不需要扩协议** | |
| D12 | **自研 kit，形态为「块序列 + 缩进行」**：`Row` / `Gap` / `Divider`，另允许一层专用 `Frame` 视口；禁止 Frame 嵌套，不做通用容器 | S1 消除原稿「无嵌套」与 Frame 子内容的矛盾 |
| D13 | kit 是**纯排版层**：只管测量/排版/裁剪/命中/绘制；**输入框与屏幕级分页控件保持原生**，其余（标题、状态、下拉、参数按钮、框角控件）全部走 kit | 曾定「只有 EditBox 原生」，后放宽 |
| D14 | **另设一个与 kit 分离的浮层注册表**，使用者三处：场景下拉、参数叠加层、居中放大窗口 | |
| D15 | **缩放档位 {0.5, 1, 2, 3}**（整数/半档，避免非整数倍把位图字形拉得宽窄不一）；滚轮缩放**以鼠标位置为锚点**；**不做紧凑模式** | 曾定「整数倍 + 紧凑模式」，后以「居中放大窗口」替代紧凑模式 |
| D16 | **框角悬浮控件 = 档位显示 + 复位**；框角控件**先于框内内容参与命中** | |
| D17 | 框内排版：**完全自然尺寸，不折行不截断**；超出框一律裁剪，其余靠拖动/缩放看 | |
| D18 | **预览 = 点展开按钮 → 整个框放大到屏幕居中**，带遮罩层，**窗口内交互与页内完全一致** | 新概念；不是单件物品预览 |
| D19 | 树内交互：**只读 + 悬停 tooltip**；可点的只有框角控件、展开按钮与动作行的入口 | |
| D20 | **未计算态**：树完整显示，只空概率；条件自身的声明概率（目录数据）照常显示 | |
| D21 | **缩放档与平移位置按场景各记**（切走再回来恢复） | |
| D22 | **命中即 tooltip 唯一来源**；`hit()` 返回带载荷的目标对象 | bug 1 类别的结构性解法 |
| D23 | 块序列**客户端构建**，按内容版本号门控重建（目录 revision + 结果 revision + 选中项） | |
| D24 | **性能**：四条结构护栏写进契约 + `TextMeasurer` 注入 + 开发模式计时出口 | |
| D25 | **验收边界：只做场景详情页**；网格页只修 bug | 曾定含网格页，后收敛 |
| D26 | **交付方式：先修 bug（第 0 步）→ 分步实现 → 最后一并验收** | |
| D27 | **树的连线用 1px `fill` 折线绘制**（与 4.8 同做法），**不使用 SVG**——客户端没有矢量绘制能力、没有 SVG 解析器，GUI 只有纹理四边形与 `fill` 两类原语；原版进度界面的树连线同样是 `hLine`/`vLine`（`fill` 的包装）。实现上 `Row` 携带父行索引、`FrameSpec` 携带连线颜色，连线几何在排版阶段一次算出，因此连线随框一起缩放平移 | 由实机演示页反馈引入，已实机确认 |
| D28 | **浮层与 tooltip 的层高契约**：任何浮层类绘制都必须先 `guiGraphics.flush()` 再把整层抬到 **z=400**（与 `GuiGraphics.renderTooltipInternal` 相同），因为物品的数量角标画在 z=200 | 由实机反馈引入，见 4.3 的 I11 |
| D29 | **右页每个 tab 各用自己的内容上界常量**，不共用同一个值 | 由实机回归引入（`GRID_TOP` 为场景头部抬高 34px，把介绍页一起推下），见 4.4 的 R8 |

---

## 2. 事实勘察结论

### 2.1 bug 1（鼠标穿透）root cause

**确证：不是画错一层，是少了一层。**

`ui/panel/RightPageContainer.java` 的两条 tooltip 路径只有一条带守卫：

```java
// :340 —— 有守卫（普通物品路径）
if (this.activeTab == Tab.ARCHAEOLOGY && !scenarioPanel.dropdownOpen()) {
    return this.gridPanel.getTooltipData(mouseX, mouseY);
}
// :352-355 —— 没有守卫（导航路径）
scenarioPanel.renderOverlay(guiGraphics, font, mouseX, mouseY);
...
this.gridPanel.renderNavigationTooltip(guiGraphics, font, mouseX, mouseY);
```

- 两条路径扫描**互不相交**的条目段：`ItemGridPanel.hoveredItem` 只扫 `i >= nonItemCount`（普通物品），`ItemGridPanel.navigationTooltip` 只扫 `i < navigationCount`（tag 聚合 + 子表）。
- 守卫是 2026-09-21 `dcde979d` 新增的，只加在普通物品那条；导航 tooltip 那两行是 2026-07-27 `e4543db5` 的旧代码，修复时漏改。
- 因此「只有 tag 合集/子表穿透」不是 tag 特有问题，而是**恰好只有它们走了那条没守卫的函数**。截图泄漏的文案「点击跳转到子表目录」= lang key `child_table_open`，正是 `navigationTooltip` 返回、经 `JournalTooltipBuilder.buildChildTable` 渲染的。

**同源缺口（一并记录）**：下拉展开时**滚轮未被拦截**（`ui/screen/ArchaeologyJournalScreen.java` 的 `mouseScrolled` 仍会把下层网格翻页）；`mouseDragged` / `mouseReleased` 从不转发给 `ScenarioPanel`，幸运值输入框的拖选无法正常结束。

**结构事实**：那个「下拉」不是浮层。它只是 `ui/panel/ScenarioPanel.java:21` 的 `private boolean dropdown` 加手算命中矩形（`:219`、`:223`、`:228`），浮层绘制靠 `pose().translate(0, 0, 200)`（`:255-256`）。全项目**没有 z-order / 浮层 / 焦点层**，也不存在统一的 tooltip 归属判定。

### 2.2 bug 2（自动计算污染缓存）机制

**请求是渲染驱动的，不是事件驱动的。**

- `ui/panel/ScenarioPanel.java:70-72`：`renderHeader` 每帧第一件事就是调 `tick()`。
- `ScenarioPanel.tick()`（`:57-68`）是唯一的自动发送点：输入键与 `observedInput` 不同则重置计时并清 `sent`，静默 **500 ms** 后 `request(table, false)`。
- **【计算】按钮不管理"要不要自动算"**：`request(table, manual)` 里 `manual` 只影响 `failed` 状态是否允许重试（`client/state/ScenarioSimulationClientState.java:78-79`）。自动路径与按钮路径是同一个 `request`。
- **打开一张表的场景页就会自动算一次**：`setTable` 把 `observedInput` 清成空串（`ScenarioPanel.java:43-44`），下一帧 `tick` 必然判定"键变了"。
- `ScenarioPanel.dropdown` / `dropdownPage`（`:20`）**不在 resize 快照里**。

**服务端缓存那笔账**（参数组合 = 规范串去掉末尾的 `n=` 段，见 `loottable/simulation/SimulationInputKey.java:63-70`）：

| 事实 | 证据 |
|---|---|
| 组键含 `scenario=` 与 `luck=`，不含 `n=` | `SimulationInputKey.parameterGroup` `:63-70` |
| 每表参数组合上限 **8** | `world/LootProbabilityData.java:77` |
| 按组整组淘汰，依据 access-order LRU | `LootProbabilityData.java:446`、`:400`、`:412-425` |
| 同表**所有场景共用这 8 个名额** | 组数计数在 `TableProbabilityEntry` 内，per-`tableId` |
| 幸运值按 0.01 网格量化，**每个值各占一组** | `ScenarioParams.java:75`、`LuckGateAnalysis.java:32` |

推论：沿幸运值拖 9 次，第 9 组写入时被挤掉的「最久未访问组」**很可能是启动时算好的 baseline 组**。用户观察到的「留下大量无关缓存」实际比观感更严重——它同时挤掉了有用的那几组。别的表互不影响。

### 2.3 布局 / resize / 状态持久化

- `resize()` **没有** override；GUI scale 变化、窗口拉伸、全屏切换三条路径**全部**汇入 `ui/screen/ArchaeologyJournalScreen.java:291 repositionElements()`。`init()`（`:101`）与它**抄了同一段重建代码**。
- 书页尺寸**恒定** `160×224`（`ui/layout/JournalLayout.java:7-14`），只有书原点平移 + 整体缩放 ≤1。所以「响应式」实际只是缩放，**不需要**比例/锚点布局表。
- 所谓「resize 持久化」缺的不是布局重算，而是**状态收纳**：现在是一份**字段写死的** `record UiStateSnapshot`（`ArchaeologyJournalScreen.java:1018-1034`）+ 手写恢复块（`:299-326`）。新增一个带状态的组件要改 **7 处**：`rebuildWidgets()`（`:578-697`，约 20 个手算坐标的 `addRenderableWidget`）、`syncButtonState()`（`:806-839`）、`UiStateSnapshot` 加字段、`captureUiState()`（`:983-1001`）、`repositionElements()` 恢复块、`removed()`（`:155-176`）/ `restorePersistedUiState()`（`:131-152`）、偏好 Store 的两处序列化。
- **已确证的状态丢失**：① 只保存**当前激活 tab** 的页号（`captureUiState` 用 `this.rightPage.getPage()`，委托 `activePanel().getPage()`），所以在介绍页 resize 会让网格页/日志页/场景页页号**全部归零**；② `ScenarioPanel.dropdown`/`dropdownPage` 完全没存。
- `ScenarioPanel` 构造时把坐标**快照**成 `final int x,y,width`（`:36-37`），`PageIndicator` 同理（`:16-19`）——重建后最容易漂的就是它们。
- **没有**任何统一布局基类/接口；`PagePanel` 只有 `render/containsMouse/pageCount/getPage/setPage/changePage`。
- **没有**九宫格拉伸 / 贴图平铺 / 共享边框工具；边框全是各处私有 `fill` 硬编码（`CatalogPanel.drawBorder`、`LogDetailPanel.drawCardBackground`、`IconButton.renderWidget`）。

### 2.4 贴图资源与绘制惯例

| 资源 | 尺寸 | 绘制方式 |
|---|---|---|
| `textures/gui/archaeology_journal_book.png` | 384×256 | 整图 `blit`，左右纸面已烘焙在图内（`ui/JournalBookBackground.java:32`） |
| `textures/gui/bookmark_tab.png` | 40×66（3 态 × 22） | 改 UV 的 v + 裁剪宽度（`ui/widget/BookmarkToggleButton.java:81`） |
| `textures/gui/catalog_entry.png` | 152×76（4 态 × 19） | 改 UV 的 v（`ui/panel/CatalogPanel.java:186-190`） |
| `textures/gui/log_entry.png` | 148×78（3 态 × 26） | 改 UV 的 v（`ui/panel/LogPanel.java:512-518`） |
| `textures/gui/toolbar_icons.png` | 81×27（9×9 格 atlas，27 槽） | 按 `col*9, row*9` 取块（`ui/widget/IconButton.java:166-169`）；规格见 `docs/dev/toolbar-icon-atlas.md` |
| `textures/gui/book_side_tabs/*.png` | 各 24×20 | 单图 + 悬停白色叠色 |

- 惯例：自有贴图**一律 1:1 `blit`**，无 `blitNineSliced` / `blitRepeating`；`blitSprite` 只用于原版 sprite（`JournalPageButton`）。**能靠 tint 解决的就不出贴图**（`IconButton`、`BookSideTabButton`、`ScenarioPanel`、`DetailOverlayPanel` 全是代码叠色）——这是项目主流惯例。
- 生成脚本（PIL）：`scripts/drawer/generate_book_texture.py`、`generate_bookmark_texture.py`、`generate_log_entry_texture.py`、`generate_pin_icon.py`、`tools/draw_book_side_tabs.py`。**Python 出图是本项目既有做法**，环境已就绪（Python 3.14.4 / Pillow 12.2.0 / numpy 2.4.4）。
- **死资源 / 脱节脚本**：`textures/gui/player_inventory.png`、`textures/gui/catalog_entry_pin.png` 全仓库无引用；`scripts/drawer/generate_toolbar_icons.py`、`scripts/generate_journal_icon_atlas.py` 与其产物已脱节。
- 既有轻微缩放当引以为戒：`catalog_entry` 152→146 压扁、`log_entry` 26→28 拉伸。新贴图应**按实际绘制尺寸出图**。

### 2.5 条件数据：客户端到底能拿到什么

**过网的字段**（`network/payload/s2c/CatalogStreamCodec.java:361-394`）：`conditionType`（ResourceLocation）、`description`（已本地化 Component，走 `Component.Serializer` JSON，**translatable key 与 args 完整保留**）、`probability`、`children`（递归）、`metadata`（Map<String,String>）。

`loottable/analysis/LootConditionInfo.java:19-26` 的 `source`（`LootItemCondition` 谓词对象）**明确不过网**（`:44-48` 注释），`readCondition` 用 4 参构造使其恒为 `null`。

**metadata 里只有四类键**：`simulation_fingerprint`（SHA-256 前 12 字节）、`simulation_fingerprint_stable`、`analysis_fidelity`（`partial`/`unreadable`）、`declared_chance_min/max`（**仅 `random_chance`**）。

> **结论：客户端无法可靠拿到条件的取值。** biome 的 ResourceLocation、数值门槛、谓词原文都不在过网数据里。只有一部分能从 `description` 的 translatable key/args 反推（biome / dimension / weather / time / chance / entity_scores / block_state_property），而那是把「服务端恰好这么拼 Component」当成隐式协议；另一部分（`location_check` 的 position/light/block/fluid、`entity_properties_target`、`damage_source` 的直接/间接实体、`value_check`、`table_bonus`、`enchantment_active_check`）描述里**根本没有值**，只写「需要特定位置」一类文案并标 `partial`。

**场景的组合方式**（`loottable/simulation/SimulationScenarioPlanner.java`）：

- `SimulationScenario.assumptions` 是 `List<LootConditionInfo>`，**恒为合取**：每个指纹恰好被赋一个 boolean，为真的放原条件，为假的放一个**合成的 `minecraft:inverted` 包装**（`describe()` `:398-416`，描述 "NOT: %s" / "非: %s"，child 才是真条件，**包装自身 metadata 为空**）。
- **归一化时把所有指纹都写进赋值表**（`:136-140`），所以每个场景的 assumptions 条数相等，等于该表参与规划的条件实例总数（**其中绝大多数是 false**）。→ 场景标题的公式只能取「为真的那一批」。
- 展开不是 one-at-a-time 也不是全叉乘：路径内 `combineAnd` 累加、`all_of` 真叉乘、`any_of` 并集产生多个候选（`:268-328`）。
- 预算：`EXPANSION_NODE_BUDGET = 4096`、`EXPANSION_COMBINATION_BUDGET = 512`（`:63-66`），超限即降级为「无约束」且不可恢复；`MAX_SCENARIOS = 32`（`:49`）只在结果阶段裁剪。
- `scenarioKey`：基准恒为 `baseline`（`:57`），其余 `scene-N` 从 1 起（`:59`、`:163`、`:170`），顺序 = 按覆盖度降序的稳定排序。
- **`keepBaseTool` 在当前代码里已不存在**（P2 随泥地打捞专用注入场景删除，仅存于文档）。

**已发现的两处排序/一致性问题**：

1. `SimulationOptions.from` 用 planner 顺序（`loottable/catalog/SimulationOptions.java:20-21`），而全量目录 `CatalogTableDto.from` 用 `TreeMap` 按 key 字典序（`loottable/catalog/CatalogTableDto.java:110-113`）→ **`scene-10` 会排在 `scene-2` 之前**。卡片列表、下拉、步进器若读不同源，顺序会互相矛盾。**处理方向：统一到 planner 顺序。**
2. 场景行的状态标记用 `new SimulationInput(scene.scenarioKey(), Map.of(), p)` 作为键（`ui/panel/ScenarioPanel.java:167`、`:261`），判定的是「有没有**专门为它**发过一次请求」；而一次结果包里其实带了分场景数值 → 会出现「数字在手、标记却说未计算」。**处理方向：状态判定改为「我手里有没有这份数据」。**

**规模事实**：零条件的表只产出 **1 个场景**（原版 6 张 archaeology 表与 buried_treasure/ancient_city 系列即是）；**带 tag 的条件指纹不稳定，永远不进场景**（`SCENARIO_CONDITIONS` 之外或被判 unstable 即返回无约束）。

### 2.6 请求与缓存链路（服务端受理顺序）

`journal/catalog/ArchaeologyJournalServerCatalog.java:429-492`，实际顺序：

1. `generation`/`isTracked` 校验 → `UNKNOWN_TABLE`
2. `raw`/`constraint` 为空 → `UNKNOWN_TABLE`
3. 表哈希比对 → `STALE_HASH`
4. `constraint.resolve(scene, params)` 空 → `REJECTED_INPUT`
5. `scenario` 为空 → `REJECTED_INPUT`
6. **缓存命中**（`!needsResimulation && getMeasurement != null`）→ `CACHE_HIT`，不写缓存、立即下发
7. `worker == null` 内联模拟：失败 → `SIMULATION_FAILED`；成功 → 写缓存 + 下发 → `SIMULATED_INLINE`
8. **限流**：`!worker.canAcceptFor(uuid)` → `PLAYER_LIMIT`
9. 入队 → `QUEUED`，结果稍后由 worker 回调 `commitSimulated` 写缓存并只回请求者

要点：**缓存命中在限流之前**；`needsResimulation` 必须在读缓存前过（`getMeasurement` 只看输入键不看哈希）。唯一写缓存的点是 `commitSimulated`（`:571-632`）；`requester == null`（启动批次）写进共享目录，`requester != null` 只回给请求者。

客户端侧：`RESULTS` / `FAILURES` 上限 64 且是**插入序而非 LRU**（`client/state/ScenarioSimulationClientState.java:112`、`:121`）；`PENDING` 超时 120 s 转 `failed`（`:90-91`）；代次变化清空全部（`:32-38`）、单表哈希变化按前缀清（`:42-48`）。**没有任何「服务端缓存了哪些组合」的下发通道。**

### 2.7 后端契约（可依赖的冻结面）

- **5 个专用 payload**：C2S `RequestScenarioSimulationPayload`、`RequestSimulationAssistPayload`；S2C `SyncScenarioResultPayload`、`ScenarioRequestRejectedPayload`、`SyncSimulationAssistPayload`。目录元数据走既有 `SyncArchaeologyCatalogPayload`（内含每表 `SimulationOptions`）。
- 客户端状态机 **4 态**：`cached / pending / failed / uncomputed`（`ScenarioSimulationClientState.java:85-94`）；**7 种失败原因**：`timeout` + 6 个服务端枚举小写（`:120`）。
- 协议版本**只存在于 NeoForge**（`registrar.versioned`）：服务端 `4.7`、客户端 `4.6`；Fabric 无版本机制。枚举一律 `writeEnum/readEnum` = **VarInt 序数**，只能追加不能插入。
- `SimulationConstraintCatalog` **不下发**；下发的是其派生的 `SimulationOptions`（场景 key + 条件假设、工具基座 + 谓词原文、附魔上限、抽样档位、`truncated`/`budgetExhausted`）。
- 场景显示名由客户端按 key 拼 i18n（`simulation.baseline` / `simulation.scene`），**场景没有图标字段**；条件描述、工具名、物品名、表名都是服务端 Component。
- `SyncSimulationAssistPayload.notes` 是**拍平的 `List<Component>`**，未填充项无结构化字段。

### 2.8 持久化现状（两套不一致的方案）

| | 参数偏好 `SimulationPreferenceStore` | UI 偏好 `JournalUiPreferencesStore` |
|---|---|---|
| 内容 | 每表的 `{scene, luck, tool, samples, enchantments}`，LRU 1024 | `lastSelectedTable`、排序/隐藏未解锁/搜索文本、`lastRightPageTab`、`favorites` 等 |
| 路径 | `<game>/config/unsuspiciousblock-simulation.properties` | `<世界>/unsuspiciousblock_journal_logs/<uuid>/journal_ui_preferences.dat` |
| 隔离 | **跨存档共享、不分玩家** | **按存档 + 按玩家** |
| 格式 | Java `Properties` 文本 | NBT（gzip） |
| flush | 请求/选择/断线时显式 flush | 每客户端 tick 检查 dirty |

参数偏好**既不分存档也不分玩家**，与「不同玩家的参数喜好存在客户端」这一目标语义不符（恢复时会用 `SimulationOptions.rejects(...)` 重新校验，非法即退基准，所以不会崩，但语义不对）。**处理方向：两套统一为「按存档 + 按玩家」。**

### 2.9 与文档不一致之处（漂移清单）

1. `docs/dev/network.md:139` 写「服务端 4.6、客户端 4.5」，代码实际是 `4.7` / `4.6`；同段示例还写着 `versioned("4.4")`。
2. 文档中多处仍描述 `keepBaseTool`（`docs/dev/loottable.md:462/556`、本文引用的任务文档 `:293`），代码里已删除。
3. `scripts/drawer/generate_toolbar_icons.py` 输出的规格（14×14、56×56、14 个图标）与现行 `toolbar_icons.png`（9×9、81×27、27 槽）不符。
4. `scripts/generate_journal_icon_atlas.py` 的产物 `journal_icon_atlas.png` 在资源目录中不存在。

---

## 3. 曾提出的待决问题与其最终结论

| # | 问题 | 最终结论 |
|---|---|---|
| Q1 | 缩放在 MC GUI 里能做到什么程度 | 档位 **{0.5, 1, 2, 3}**。0.5 档定位为「看结构不看字」（图标缩到 8px 仍可辨、文字不可读），可读性由 1x 及以上与「居中放大窗口」补；**不做紧凑模式**。 |
| Q2 | 场景页与现有翻页机制如何衔接 | **复用屏幕级原生分页**（四个 tab 共用的那套），页数 = 场景数；头部不放箭头。kit 必须给底部那条分页带预留区域。 |
| Q3 | 场景标题行写什么 | **序号 + 旋钮公式，不含取值**（零协议改动）。字母映射的实现风险见 4.4；全称恒在 tooltip。 |
| Q4 | 详情页内容取舍 | 条件树（只列为真者）+ **条件行内的物品图标**；无独立物品区。 |
| Q5 | 参数偏好是否与 UI 偏好统一为「按存档 + 按玩家」 | 统一（见 2.8）。 |
| Q6 | 是否清理死资源与脱节脚本 | 清理（见 2.4、2.9）。 |

**另有两项无需决策、直接按方向处理**：见 2.5 末尾的排序统一（统一到 planner 顺序）与状态判定口径（改为「我手里有没有这份数据」）。

---

## 4. 实现架构

### 4.1 包与类清单

新增包 `client/ui/kit/`（**纯渲染工具，不含业务、不碰网络**）：

| 类 | 职责 |
|---|---|
| `UiNode`（sealed） | `Row(indent, parentRow, text, color, List<InlineIcon>, tooltip, payload, action)`、`Gap(height)`、`Divider(color)`、`Frame(FrameSpec, List<UiNode>)`；`FrameSpec` 含宽高、连线颜色与独立 `UiTransform`；`parentRow` 是连线所需的最小父子信息（详见 4.8） |
| `UiIcon`（sealed） | `Item(ItemStack)`（走原版 `renderItem`）、`Sprite(texture, u, v, width, height, textureWidth, textureHeight)`；交互载荷由 InlineIcon 提供 |
| `UiAction` | 命中后要执行的动作（可空；由面板注入，kit 不解释其含义） |
| `UiTarget` | **命中结果**：`kind` / `index` / `payload` / `rect` / `tooltip` / `action`。二维内容（框内）与一维行都靠它统一表达 |
| `UiTransform` | 原点 + 缩放档 + 平移，提供 `toLocalX/Y` 与 `toScreenX/Y` 双向换算 |
| `TextMeasurer` | 接口：文本宽度与行高。由面板注入 `Font` 实现；首版不折行，与 D17 一致 |
| `UiDocument` | 内容 + 视口尺寸 + 脏标记；测量 / 排版 / 裁剪 / 命中 / 绘制；开发模式计时出口 |
| `OverlayLayer` | **与 kit 分离**的浮层注册表：打开/关闭、吞掉全部输入、抑制下层 tooltip；`render` 统一做 `flush` + 抬到 z=400（I11）。实际落点 `client/ui/overlay/OverlayLayer.java` | ✅ 已落地 |
| `ScenarioParamsOverlay` | 参数叠加层（工具/抽样/附魔用按钮，幸运用单行 `EditBox`，确认/取消，越界提示） | ✅ 已落地 |

业务侧（**落点改为 `client/ui/panel/`，与 `PagePanel` 同包**——它是包私有接口，跨包无法实现，移动它等于改既有 API）：

| 类 | 职责 | 状态 |
|---|---|---|
| `ScenarioPageBuilder` | 目录结构 + 该输入的测量结果 + 选中状态 → `List<UiNode>`；按内容版本号门控重建 | ✅ 已落地 |
| `ScenarioDetailPanel` | 实现 `PagePanel` / `LayoutAware` / `UiStateful`，组合读数、动作与 `ScenarioFrameView` | ✅ S2–S4 已落地 |
| `FrameState` | 每个 `tableId#scenarioKey` 的缩放档与平移位置，写入 UI 偏好 NBT | ✅ 已落盘 |
| `LayoutAware` / `UiStateful` / `UiPanelRegistry` | 新面板注册一次，屏幕统一遍历布局与状态 | ✅ 已落地 |

### 4.2 页面骨架（精确像素）

右页可绘制区 = `(bookX + 208, bookY + 16, 160 × 224)`，内容左右各内缩 4 → 净宽 **152**。

| 区域 | y（相对右页顶） | 高 | 内容 | 状态 |
|---|---|---|---|---|
| 读数行 | 6 | 12 | 序号 + 公式 + 四态角标；完整条件与状态原因放 tooltip | ✅ |
| 动作行 | 18 | 14 | 场景下拉、放大、参数、计算 | ✅ |
| **框** | 34 | 166 | 九宫格框与条件树；拖动、锚点缩放、框角档位与复位 | ✅ |
| 分页带 | 208 | 16 | **屏幕级原生分页**（场景页独立 `SCENARIO_PAGE_INDICATOR_Y = 208`），kit 必须避开 | ✅ |

尺寸常量与当前实现契约统一见客户端文档第 4.5 节；框的九宫格内侧留 8px，内容与边框分别绘制。

### 4.3 契约不变式

| # | 不变式 |
|---|---|
| I1 | **命中唯一入口**：tooltip 只由 `hit()` 派生；不存在第二条 tooltip 路径 |
| I2 | **每帧零自有堆分配**：行的矩形、缩进宽度、截断结果都在构建/排版阶段算好（引擎内部不可避免的分配不计） |
| I3 | 内容 / 视口尺寸 / 字体度量不变则**完全不重排版**；平移与缩放均只改变换 |
| I4 | **框角控件先于框内内容命中**，拖动树不会盖住它 |
| I5 | 框内按**自然尺寸**排版，不折行不截断；超出框一律裁剪 |
| I6 | 缩放为 {0.5, 1, 2, 3}，**以鼠标位置为锚点**；缩放与平移均不触发重排 |
| I7 | kit 不碰网络；数据只来自客户端已有的 overlay 目录与结果库 |
| I8 | 只有 `EditBox` 与屏幕级分页控件是原生；kit 必须给分页带预留位置 |
| I9 | 缩放**只作用于框内内容**；框外文本与控件不缩放 |
| I10 | `FrameState` 按 `scenarioKey` 记，随面板状态落盘 |
| I11 | **浮层类绘制必须先 `flush()` 再抬到 z=400**（与原生 tooltip 同层高）。物品数量角标画在 z=200，抬到 200 会被它压住——旧的场景下拉就是这么漏的。该约定由 `OverlayLayer.render` 统一实施，新增浮层不必自己记得 |
| I12 | **右页每个 tab 各用自己的内容上界常量**：任何一页为自己的头部抬高公共常量，都会静默改动其它页（见 R8） |

### 4.4 风险与待实测项

| # | 风险 | 处置 |
|---|---|---|
| R1 | **旋钮字母的映射是隐式契约**：biome 与 dimension 子行的 `conditionType` **都是 `minecraft:location_check`**，只能靠 description 的 translatable key（`...condition.location_check_biomes` 等）区分 | 把「key 后缀 → 字母」固化在一张客户端表里，**未知 key 回退为按位置分配的字母**（不崩、不错位）；字母只是助记，全称恒在 tooltip。若实机发现覆盖不足，再考虑扩协议加结构化字段 |
| R2 | **源码核实**：mc-developing-mcp 中 1.21.1 `GuiGraphics.java:155-181` 的 enableScissor/applyScissor 只用矩形与窗口 GUI scale，不应用 pose | S1 已集中到 UiTransform 换算当前 pose；整数 GUI 边界向内取整；前序实机验收已由用户确认，本轮新窗口仍需回归 |
| R3 | `renderItem` 在缩放 pose 与裁剪下的表现与开销未实测 | 同 R2，一并在第 1 步实测 |
| R4 | 位图字体在 0.5 档的实际可读性只有推断 | 实机看一眼再定 0.5 档呈现（例如框角是否提示「概览」） |
| R5 | 居中放大窗口画在书本之上，且需盖住屏幕级分页按钮与 `EditBox`；事件分发顺序要改成「浮层先接管」 | 与 `focused()` 转发机制一起设计，见 S3/S4 |
| R6 | 状态角标四态配色需与既有语义色表协调（`TooltipBuilder` 是唯一取色入口） | 取色时遵循该表，不新造颜色 |
| R7 | **网格页头部仍由旧 `ScenarioPanel` 绘制**（含它自己的下拉与计算按钮），于是同一下拉会有「旧原生式」与「新浮层式」两份 | 已登记为待决项：S5 收尾时决定是并存还是把网格页头部也换成 kit 浮层；在决定前，S0 的守卫补丁保持有效 |
| R8 | **右页布局常量被多页共用，改一处会静默改动其它页** | **已发生一次**：`GRID_TOP` 在 `dcde979d` 为场景头部由 `TOOLBAR_Y` 抬到 `TOOLBAR_Y + 34`，日志页当时被正确解耦（`LOG_TOP = TOOLBAR_Y`）但**介绍页漏改**，内容被一起推下 34px。已修：新增 `INTRO_TOP = TOOLBAR_Y` 并让 `DetailOverlayPanel` 的 3 处引用改用它，`GRID_TOP` 上补了「只有带头部的页面能用」的注释。本轮 S4 已将分页带拆成每页独立常量，页码与按钮从当前 tab 的统一位置读取（见 I12） |
| R9 | **旋钮字母的映射覆盖度仍未实机确认**（R1 的下游） | 公式在任一叶子认不出时会整条退回位置字母（A/B/C…），因此可能不如预期有助记性。需用户在实机看几张有群系/维度条件的表后反馈，再决定补映射表还是改走结构化字段 |

### 4.5 与既有规划的偏差（需回写规划）

1. **收窄决策 43**：取消防抖自动请求；「快捷切换下拉」改为「场景页内下拉 + 屏幕级分页」。
2. **替代决策 4**：卡片列表 → 一页一场景 + 固定大小的框（保留其「必须有地方承载条件描述」的实质要求）。
3. **替代决策 16**：点场景卡跳回网格 → 场景页内直接看树（网格页出范围）。
4. **决策 44 的显示优先级链**在场景页落到「未计算时只空概率，目录自带的声明概率照常显示」。

### 4.6 执行清单

拆点、涉及文件、验收方式与验收清单见 [journal-ui-redesign-tasks.md](journal-ui-redesign-tasks.md)。

### 4.7 S0 / S1 实施修订（2026-09-21）

- 按验收清单第 13 条修正 I3/I6：自然尺寸布局无需因缩放重排，旧 I6 的要求与验收矛盾。
- Row 从单图标改为多个 InlineIcon；行和图标分别缓存目标，满足条件行内多个物品及唯一 tooltip 来源。
- Frame 是专用单层视口，不扩展到通用嵌套布局；浮层、框角控件、场景业务和持久化留给 S2–S4。
- `TextMeasurer` 首版只测宽度/行高；删除与自然尺寸契约冲突的折行职责。
- S0 源码核实发现原版 EditBox 只有点击定位、没有拖选实现，仅转发事件不足以实现验收。因此面板复用点击定位并显式保存选区锚点，未新增 Mixin。
- S1 临时验证页保留至用户完成 S1-t；不能在尚未实机验证时删除入口或登记为实测通过。入口与验收操作见客户端文档第 4.4 节。

### 4.8 S1-b 树干连线（实机反馈，2026-09-21）

- 演示页实机显示「树形结构没有树枝」。**不使用 SVG**：客户端没有矢量绘制能力、也没有 SVG 解析器，GUI 只有纹理四边形与 `fill`；原版进度界面的树连线同样是 `hLine` / `vLine`（`fill` 的包装）。
- 数据侧：`Row` 增加 `parentRow`（同内容列表内的父行下标，`NO_PARENT = -1`），`FrameSpec` 增加 `branchColor`。连线几何**在排版阶段一次算出**，因此连线随框一起缩放平移，且不进入每帧路径。
- 几何：折线竖段从父行文字下方（或上一个同父兄弟的中心）接到本行垂直中心，横段从柱位接到本行左边界；祖先若仍有后续兄弟，则在同一列画一条穿过本行的续行竖线——逐行相接即视觉连续。
- 线宽按缩放档取整（`ceil(1/scale)`）：0.5 档下 1 内容像素只有 0.5 屏幕像素，不取整会直接消失。
- 演示页同步改成真正的树（原先是 `indent = i % 5 * 10` 的阶梯，不是合法树）：25 组 × (1 根 + 3 子 + 2×2 孙) = 200 行，每 10 行带 3 个图标 = 60 图标。


### 4.9 S2–S4 实施结果（2026-09-21）

- 延续用户已验收的连线、层高、下拉导航与介绍页上界修复，补齐面板注册、状态恢复、下拉、参数草稿、四态角标、显式计算、框视图、放大窗口及九宫格素材。
- 修复新详情面板漏收 tableId、未计算图标借用基准概率、标题入口 tooltip 被框内守卫挡住三处接入问题。
- 参数存储统一到按存档+玩家隔离的 UI 偏好 NBT；旧全局文件保留但不自动导入。框视图按表与场景保存，resize 保存所有 tab 页号，临时模态在 resize 时关闭。
- 放大窗口选择独立视图副本，关闭丢弃窗口内的调整，满足「回到页内时与放大前一致」的验收约定。
- IDEA MCP 检查无错误，保留公共 API 未使用、风格建议及原有 Gradle DSL 推断警告。
- `./gradlew build`：**BUILD SUCCESSFUL in 1m 50s**，33 任务（23 执行、10 最新）；Fabric / NeoForge 产物均包含新场景类与两张贴图。没有新增或修改测试文件。
- 中英 simulation 命名空间 67 个 key 配对及占位符检查通过。新增交互尚未实机验证；S5 清理仍未实施。
