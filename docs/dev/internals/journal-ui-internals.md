# 笔记 GUI 内部机制

> 考古笔记与战利品表管理界面的内部结构：组件分层、唯一输入与焦点路由、右页与工具栏组装、网格与日志展示规则、场景详情与浮层交互，以及本地阅读状态持久化。
> 本文件不参与主任务导航，由[客户端与 GUI](../subsystems/client-ui.md)按需链接；UI Kit 公开接口与兼容性规范见[UI kit 公开 API 与兼容策略](ui-kit-api.md)。

## 1. 组件职责与输入拓扑

| 组件 / 目录 | 职责 |
|---|---|
| `ArchaeologyJournalScreen` | 消费 UI Kit `UiScreen` 的根界面：持有唯一 `UiInputRouter`、选择当前顶层作用域、管理页面返回历史栈与返回快照 |
| `JournalViewport` / `JournalBookBackground` | 换算逻辑书页坐标与缩放比例，使用 UI Kit 精灵图层绘制宿主书本贴图 |
| `JournalViewModel` | 维护当前选中分类、目录投影、父子表展开路径与选中表 |
| `CatalogToolbar` / `LogToolbar` | 维护搜索草稿、防抖输入、排序、过滤与日志批量操作入口 |
| `CatalogPanel` / `RightPageContainer` | 组装左页目录面板与右页（`INTRO` / `ARCHAEOLOGY` / `LOG` / `SCENARIO`）内容树与侧边书签/翻页控件 |
| `JournalControls` / `UiAtlas` / `UnsuspiciousUiTheme` | 切分 `ui_atlas.png` 图标与九宫格皮肤、构造侧签/书签/翻页/行热点皮肤，维护羊皮纸与深色两套主题 |
| `JournalOverlays` | 持有当前打开的场景浮层/灯箱的准备与退出清理回调 |
| `UiPanelRegistry` / `LayoutAware` / `UiStateful` | 登记可持久化阅读状态的面板，协调布局刷新与 NBT 状态读写 |
| `support/` | 提供搜索、排序、格式转换、概率显示、本地持久化与网络请求适配 |

### 1.1 输入路由与焦点规则

- **单一根路由与逻辑坐标**：`ArchaeologyJournalScreen` 只持有一个 UI Kit 路由器，所有渲染、指针坐标与拖动增量均经 `JournalViewport` 换算；`UiChildren` 按真实绘制顺序维护成员树，控件与行热点按稳定业务键复用。
- **焦点与键盘导航**：当前顶层 `UiInputScope` 拥有唯一输入与焦点权。目录列表与条件文档区各贡献自身的键盘停靠点；鼠标点击可切换逻辑焦点，轮廓随键盘模式显隐。输入框编辑与顶层浮层优先截获按键，`Backspace` 未被文本编辑或浮层消费时才回退导航历史（无历史则关闭笔记；历史回退只恢复阅读状态，不撤回已提交的网络请求）。
- **按钮激活与悬停命中**：按钮遵循“按下并同目标释放”才执行动作，拖出范围释放取消。`UiPointerHit` 是 Tooltip 与 JEI 物品查询的统一来源（网格、介绍页与日志详情在准备阶段将已解析的 `ItemStack` 写入 `UiPointerHit.Row.key`，不再二次反查坐标）；当模态框、文本框或持续拖动占用输入时，底层 JEI 悬停查询返回空栈。

### 1.2 右页装配 (`RightPageContainer`)

- `RightPageContainer` 通过局部 `PageDescriptor` 绑定右页各 Tab 的内容面板、书签皮肤、指示器位置及切换生命周期回调，并通过 `chromeControls()` 将书签与翻页按钮统一交给 Screen 注册（分类首页时 Screen 通过 `setChromeAvailable(false)` 整体隐藏）。
- **分页状态由活动面板自有**：`ARCHAEOLOGY` 由 `ArchaeologyPage` 组合 `ItemGridPanel` 与 `ScenarioPanel`（场景栏命中优先，Tab 焦点顺序为网格 → 场景栏）；`LOG` 按列表/详情模式切换面板。分页动作调用活动面板的 `setPage` / `changePage`，由面板自身维护脏状态、页码边界并在准备阶段同步更新翻页按钮状态。

## 2. 目录面板与追踪管理界面

### 2.1 左页分类与表目录

- 分类首页使用两列卡片网格，战利品表目录使用支持独立展开的树形行列表；两者均由 `UiScrollView` 驱动连续滚动，绘制、命中与 Tooltip 共用同一几何投影。
- 展开父表仅展示其直接子表；从父表点击子表入口或从全局搜索选中条目时，同步切换所属分类并自动展开对应父路径。
- 对外暴露的滚动偏移 API（`getScrollOffset` / `setScrollOffset`）保持以条目为单位，内部按当前视图步长转换为像素并在模式切换或窗口变化后重新钳制。

### 2.2 追踪管理页面 (`LootTableManagementScreen`)

从笔记左侧管理侧签进入，供管理员调整战利品表追踪规则与多语言名称：

- **数据来源与权限**：仅读取服务端快照 `LootTableManagementClientState` 按命名空间与路径构造目录树（“最近遇到”按服务端记录排序）；所有玩家均可只读浏览，修改与保存要求服务端权限等级 `2`（操作入口与文件校验在 `LootTableManagementActions` / `LootTableImportValidator`）。
- **浏览与激活分离**：列表支持按行键稳定恢复滚动与选择状态，点击或 `Enter` 才激活右侧详情；详情体支持滚轮浏览并将导入/返回按钮固定在底部，Tab 聚焦字段时自动滚动可见。
- **自定义表名草稿与导入**：以 `(languageCode, tableId)` 隔离暂存未提交草稿（游戏资源中已存在的名称显示来源并锁定编辑，缺失时允许编辑服务端补充名称，并按 `en_us` 回退提示）。支持通过系统文件选择器导入标准语言 JSON，在 UI Kit 模态框内展示新增/更新/跳过/非法明细预览，确认后批量提交。

## 3. 网格、介绍与日志面板

### 3.1 网格展示与未解锁隐藏规则 (`ItemGridPanel`)

`ItemGridPanel` 将条目按「物品标签组 → 直接子表入口 → 直属物品」顺序生成逻辑序列并切片为 `visibleCells` 投影，渲染、点击、Tooltip 与 JEI 命中全部读取同一投影。

| 条目状态 | 网格与入口展示 | Tooltip 行为 |
|---|---|---|
| **未发现物品** | 统一显示未知图标与「待解析」 | **严禁剧透**物品名称、概率、声明触发率或具体路径；普通物品卡片不提供点击跳转 |
| **已发现物品** | 固定显示优先级：`不可用状态（需要条件 / 未知） → 可展示的声明触发率 → 模拟值`（零命中显示「未命中」，静态不可达才显示 `0%`） | 并列显示声明触发率与模拟值，并渲染带获取条件和折叠效果的函数树 |
| **直接子表入口** | 至少有一项后代物品已发现后才解锁名称与预览 | 条件直接读取服务端下发的 `ChildTableProbability.conditions`（含注入门槛），客户端不自行重推 |

> 物品标签组（Tag）读取本组成员、子表读取其后代物品闭包；子表物品不混入父表网格或父表搜索，介绍页（`Intro`）则递归合并全部后代按签名去重展示。

### 3.2 日志列表、详情与编辑子页

- 日志分页、时间/维度/群系/备注分组、批量选择模式与选中条目由业务模型维护；`UiControlGroup` 按业务键复用列表行、复制坐标与备注按钮。
- 单条删除、清空当前表与清空全部日志均先弹出 UI Kit 确认模态（`UiModalStack`）再发送网络请求。
- `JournalLogNoteEditScreen` 与 `JournalLogRetentionScreen` 稳定持有编辑框与输入作用域（窗口大小变化仅重算排版）：备注严格校验服务端 `500` UTF-16 字符上限，保留设置校验自动保留 `>= 1`、一次性裁剪 `>= 0` 后才发包，返回后恢复子页打开前的阅读快照。

## 4. 场景条件页与文档动作

- **面板分工**：`ScenarioPanel` 置于网格页顶部提供快捷场景切换与计算触发；`ScenarioDetailPanel` 专门用于阅读复杂条件树（展示短场景名、状态、工具/参数读数、配置/计算入口与放大按钮），**物品列表与概率统一留在网格页**。
- **场景详情阅读与选择**：条件树由 `ScenarioPageBuilder` 直接从服务端场景与条件 DTO 组装（逻辑、取反、`partial`/`unreadable` 及不成立项均按分组展开），并由 `ScenarioConditionLayout` 按当前字体宽度折行（续行不重复连接线与动作按钮）。点击详情页标题弹出场景选择列表，方向键仅预览高亮，点击或 `Enter` 激活后才切换场景（普通翻页与条件区未消费的滚轮不意外切换场景）。
- **文档动作与聚焦 (`UiDocument`)**：条件区使用独立的 UI Kit `UiDocument` 配合 `UiScrollView`，动作列表、标题底纹与键盘/鼠标命中统一读取 `UiDocument.targets()` 的实时布局。Tab 聚焦条件区后支持上下选择动作、PageUp/PageDown 翻页及 `Enter`/`Space` 触发动作；内容版本、字体、资源或视口变化时重建文档缓存。
- **跨场景来源一致性**：`ScenarioSimulationClientState.sceneSource` 统一详情、列表与网格的场景口径；仅当目标场景存在同参数/同档位缓存引用时复用测值，缺引用保持未知，客户端绝不擅自挑选“最有利场景”充当当前显示值。

## 5. 场景浮层与灯箱 (`JournalOverlays`)

所有浮层使用 UI Kit `UiOverlay`、灯箱使用 `UiLightbox`、确认提示使用 `UiModalStack`；根屏幕只选择顶层 `UiInputScope`，由 UI Kit 统一处理遮罩拦截、焦点归还与迟到事件隔离。

| 浮层类 | 职责与交互约束 |
|---|---|
| `ScenarioSelectionOverlay` | 按服务端顺序展示代表场景列表（短名、状态与摘要，完整条件见 Tooltip）；方向键预览，点击/`Enter` 激活并关闭，`Esc` 取消 |
| `ScenarioParamsOverlay` | 持有单份独立参数草稿：合法幸运范围的 `UiNumberField` + `UiScalarSlider`（精细步长 `0.01`）、工具选择列表、附魔搜索与等级增减按钮及固定抽样次数读数。「仅应用」保存输入草稿，「应用并计算」校验表哈希、选项代次与参数边界后发包（失败保留草稿） |
| `ScenarioRecommendationOverlay` | 展示物品/子表入口推荐求解的等待、失败重试与参数变更预览；应用推荐只填回参数草稿，仍需显式计算。关闭、切表、切输入或目录失效时取消旧请求 |
| `ScenarioExpandedOverlay` | 在 `UiLightbox` 中使用 `ScenarioFrameView` 全屏放大条件树：初始按窗口尺寸适配档位（不超过自然字号），支持滚轮缩放与拖动平移，窗口 resize 保持手动缩放比并钳制；`Esc` 关闭（点击遮罩不关闭），关闭不破坏页内原滚动位置 |

所有功能图标、状态徽章、按钮九宫格与场景底纹均统一取自 `ui_atlas.png`（由 `scripts/drawer/generate_ui_atlas.py` 生成），分区与 UV 见[UI 图标与控件皮肤图集](toolbar-icon-atlas.md)。

## 6. 阅读状态与本地持久化

- **统一阅读快照 (`UiStateSnapshot`)**：聚合右页 `ReadingState`（Tab、tag、日志列表/详情、`entryId`、批选与页号）、目录工具栏 `CatalogQueryState`（含防抖 `effectiveSearch()`）、目录选中与滚动偏移以及已登记的 `UiStateful` 面板状态。在窗口 resize、子页返回与历史回退时共用同一恢复入口；窗口 resize 会关闭临时场景浮层并丢弃未确认草稿，独立子页内的草稿保留。
- **按世界隔离的本地文件**：
  - `journal_ui_preferences.dat`：保存面板状态（`panels` 子树，保留旧键 `intro`/`grid`/`log`/`logDetail` 与有界的 `tableId#inputKey` 条件滚动记录）及模拟参数偏好（`simulationPreferences` 子树）。
  - `journal_log_state.dat`：由 `JournalMirror` 独占管理会话、序号与本地日志镜像（支持 2 秒重同步冷却与快照整批替换，不混入网络或 IO 回调）。
- **原子写盘与断连处理 (`JournalLocalPersistence`)**：写盘使用同目录 `.tmp` 临时文件与 `ATOMIC_MOVE`（不支持时降级 `REPLACE_EXISTING`）。保存失败保留待写冻结 NBT 并在客户端 tick 或退出时重试，同路径重连优先加载未写盘快照；切换世界或断连先同步落盘旧路径，再推进连接 `epoch` 复位世界状态。已废弃的全局旧文件 `config/unsuspiciousblock-simulation.properties` 不再读取或导入。

## 7. 文本配色与裁剪生命周期

- 文本语义配色遵循[文本格式规范](../foundation/text-format.md)的「GUI 内文本规范」：Tooltip 使用 `TooltipBuilder`，书页自绘文本取 `UiTextScheme.PARCHMENT` 配合 `JournalTextColors`，管理/暗色页面取 `UiTextScheme.DARK`。
- 节点与文档缓存仅随内容版本、字体、资源或视口变化刷新，不在每帧重建树结构；3D 物品与模型在 `UiRenderLayers` 局部裁剪上下文内绘制，关屏时按作用域自动释放模型工作区。

## 8. 相关文档

- [客户端与 GUI](../subsystems/client-ui.md)
- [UI kit 公开 API 与兼容策略](ui-kit-api.md)
- [UI 图标与控件皮肤图集](toolbar-icon-atlas.md)
- [文本格式规范](../foundation/text-format.md)
- [战利品表系统](../subsystems/loottable.md)
