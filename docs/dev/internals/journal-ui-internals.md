# 笔记 GUI 内部机制

> 考古笔记与战利品表管理界面的内部结构：组件分层、唯一输入与焦点路由、右页与工具栏组装、网格与日志展示规则、场景详情与浮层交互，以及本地阅读状态持久化。
> 本文件不参与主任务导航，由[客户端与 GUI](../subsystems/client-ui.md)按需链接；UI Kit 公开接口与兼容性规范见[UI kit 公开 API 与兼容策略](ui-kit-api.md)。

## 1. 组件职责与输入拓扑

| 组件 / 目录 | 职责 |
|---|---|
| `ArchaeologyJournalScreen` | 消费 UI Kit `UiScreen` 的根界面：持有唯一 `UiInputRouter`、选择当前顶层作用域、管理页面返回历史栈与返回快照 |
| `UiScreen.viewport()` / `JournalBookBackground` | 换算逻辑书页坐标与缩放比例，使用 UI Kit 精灵图层绘制宿主书本贴图 |
| `journal/catalog/` | 聚合 `JournalViewModel`、查询状态、工具栏、目录面板与展示投影；根 Screen 提交同批分类/行/选择，模型只计算投影 |
| `CatalogToolbar` / `LogToolbar` | 维护搜索草稿、防抖输入、排序、过滤与日志批量操作入口 |
| `CatalogPanel` / `RightPageContainer` | 组装左页目录面板与右页（`INTRO` / `ARCHAEOLOGY` / `LOG` / `SCENARIO`）内容树与侧边书签/翻页控件 |
| `JournalControls` / `UiAtlas` / `UnsuspiciousUiTheme` | 切分 `ui_atlas.png` 图标与九宫格皮肤、构造侧签/书签/翻页/行热点皮肤，维护羊皮纸与深色两套主题 |
| `JournalOverlays` | 持有当前打开的场景浮层/灯箱的准备与退出清理回调 |
| `journal/` / `RightPageContainer` | 类型化捕获与恢复阅读位置，生成持久投影；NBT 编码集中在 `JournalReadingStateCodec` |
| `support/` | 提供搜索、排序、格式转换、概率显示、本地持久化与网络请求适配 |

### 1.1 输入路由与焦点规则

- **单一根路由与逻辑坐标**：`ArchaeologyJournalScreen` 只持有一个 UI Kit 路由器，所有渲染、指针坐标与拖动增量均经 `UiScreen.viewport()` 换算；`JournalContent` 的 `UiChildren` 按绘制层序登记目录、欢迎页/活动右页与顶层 widgets；Tab 独立保留 widgets → 目录 → 右页顺序。命中、焦点、普通字符、配对释放、退出通知和布局从同一成员树派生，控件与行热点按稳定业务键复用。
- **焦点与键盘导航**：当前顶层 `UiInputScope` 拥有唯一输入与焦点权。目录列表与条件文档区各贡献自身的键盘停靠点；鼠标点击可切换逻辑焦点，轮廓随键盘模式显隐。输入框编辑与顶层浮层优先截获按键，`Backspace` 未被文本编辑或浮层消费时才回退导航历史（无历史则关闭笔记；历史回退只恢复阅读状态，不撤回已提交的网络请求）。
- **按钮激活与悬停命中**：按钮遵循“按下并同目标释放”才执行动作，拖出范围释放取消。`UiPointerHit` 是 Tooltip 与 JEI 物品查询的统一来源（网格、介绍页与日志详情在准备阶段将已解析的 `ItemStack` 写入 `UiPointerHit.Row.key`，不再二次反查坐标）；当模态框、文本框或持续拖动占用输入时，底层 JEI 悬停查询返回空栈。
- **只读布局**：根 `layoutNodes()` 沿当前输入树聚合控件与自绘目录行，提供 `table/<id>`、`log/<entryId>`、日志坐标动作及工具栏的稳定 key。目录行复用实际滚动几何并裁剪到视口，锁定条目保留隐藏语义；根统一投影到原版 GUI 坐标。运行探针与截图见[核心探针](../../../tools/probes/README.md)。
- **固定画布**：双页保留最小逻辑尺寸 `412 × 256`，根使用可容纳画布的整数物理像素倍率，GUI 设置决定放大上限。缩小窗口可降低整数倍率，实际像素不足 `1×` 时连续缩小；正文随书页倍率绘制，Tooltip 由 Kit 转入原版 GUI 空间。统一接入契约见 [UI Kit](ui-kit-api.md#31-普通页面与考古笔记)。

### 1.2 右页装配 (`RightPageContainer`)

- `RightPageContainer` 实现 `UiInputContainer`，稳定成员集合只登记当前活动面板；切页签或日志模式时结清旧交互并传播本地退出。通过局部 `PageDescriptor` 绑定右页各 Tab 的内容面板、书签皮肤、指示器位置及切换生命周期回调，并通过 `chromeControls()` 将书签与翻页按钮统一交给 Screen 注册（分类首页时 Screen 通过 `setChromeAvailable(false)` 整体隐藏）。
- **分页状态由活动面板自有**：`ARCHAEOLOGY` 由 `ArchaeologyPage` 组合 `ItemGridPanel` 与 `ScenarioPanel`（同一实际成员集合按 grid → scenario 登记，场景栏命中优先，Tab 焦点顺序为网格 → 场景栏）；`LOG` 按列表/详情模式切换面板。分页动作调用活动面板的 `setPage` / `changePage`，由面板自身维护脏状态、页码边界并在准备阶段同步更新翻页按钮状态。

## 2. 目录面板与追踪管理界面

### 2.1 左页分类与表目录

- 分类首页使用两列卡片网格，战利品表目录使用支持独立展开的树形行列表；两者均由 `UiScrollView` 驱动连续滚动，绘制、命中与 Tooltip 共用同一几何投影。
- 展开父表仅展示其直接子表；从父表点击子表入口或从全局搜索选中条目时，同步切换所属分类并自动展开对应父路径。
- 对外暴露的滚动偏移 API（`getScrollOffset` / `setScrollOffset`）保持以条目为单位，内部按当前视图步长转换为像素并在模式切换或窗口变化后重新钳制。

目录展示记录位于 `journal/catalog/CategoryEntryData` 与 `CatalogEntryData`。模型重建不接收面板，根 `syncCatalogProjection()` 统一提交分类或行并使选择可见；搜索、导航和数据刷新复用该装配点。动态文本缓存 `text/PanelTextMetrics` 由具体面板持有，按业务内容、语言资源和 Font 身份失效。

### 2.2 追踪管理页面 (`LootTableManagementScreen`)

从笔记左侧管理侧签进入，供管理员调整战利品表追踪规则与多语言名称：

- **数据来源与权限**：仅读取服务端快照 `LootTableManagementClientState` 按命名空间与路径构造目录树（“最近遇到”按服务端记录排序）；所有玩家均可只读浏览，修改与保存要求服务端权限等级 `2`（操作入口与文件校验在 `LootTableManagementActions` / `LootTableImportValidator`）。
- **浏览与激活分离**：列表支持按行键稳定恢复滚动与选择状态，点击或 `Enter` 才激活右侧详情；详情体由 `UiFormBody` 统一排版、滚动和裁剪，业务状态说明按实际宽度测量并随字段滚动；导入/返回按钮固定在底部，Tab 聚焦字段时自动滚动可见。
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
- `JournalLogNoteEditScreen` 与 `JournalLogRetentionScreen` 稳定持有编辑框与输入作用域（窗口大小变化仅重算排版）：备注严格校验服务端 `500` UTF-16 字符上限，保留设置校验自动保留 `>= 1`、一次性裁剪 `>= 0` 后才发包，返回后恢复子页打开前的阅读快照。日志保留正文使用 `UiFormBody`，数字过滤、提交范围与两种网络请求留在页面。

## 4. 场景条件页与文档动作

- **面板分工**：`ScenarioPanel` 置于网格页顶部提供当前已启用条件摘要与参数/计算/条件配置入口（点击条件区弹出 `ScenarioConditionOverlay`）；`ScenarioDetailPanel` 专注于直观展现战利品表的条件结构与交互式组合试算，**物品具体概率仍留在网格页**（且严格遵循未解锁物品 `?` 防剧透规则）。
- **场景详情交互式布局 (`ScenarioPageBuilder`)**：页面自顶向下由状态摘要横幅（无额外条件 / 有条件掉落待选或已选数量 / 当前结果状态与缓存提示）、操作栏（`[同步自身]` 触发 `PlayerStateProbe`、`[重置]` 回退基准、`[参数…]` 与 `[计算]`）、可交互条件勾选清单（原子环境条件复选框 + 当前工具/附魔/幸运生效条件即时求值状态）、以及分目标条件树（子表与物品分支及对应的「推荐参数」快捷按钮）构成；逻辑条件树交给 kit reader 按当前字体宽度折行，续行保持同一逻辑行与动作。
- **文档动作与聚焦**：`ScenarioDetailPanel` 持有 kit `UiDocumentView`，业务只提供逻辑树、Heading 背景及条件/推荐回调。reader 统一持有折行、滚动、命中、动作导航和配对激活，具体契约见 [UI kit 文档阅读](ui-kit-api.md#33-文档阅读与业务呈现)。条件阅读 offset 按表 ID 捕获进宿主阅读快照。
- **跨输入来源一致性**：`ScenarioSimulationClientState.selection` 统一详情、浮层与网格页的 `enabledConditions + params` 组合；仅当当前规范化的 `inputKey` 存在同参数/同档位缓存引用时复用测值，缺引用保持未知并保留已有结果缓存供切换对比，客户端绝不擅自挑选“最有利条件组合”充当当前显示值。

## 5. 场景浮层与灯箱 (`JournalOverlays`)

所有浮层使用 UI Kit `UiOverlay`、灯箱使用 `UiLightbox`、确认提示使用 `UiModalStack`；根屏幕只选择顶层 `UiInputScope`，由 UI Kit 统一处理遮罩拦截、焦点归还与迟到事件隔离。

| 浮层类 | 职责与交互约束 |
|---|---|
| `ScenarioConditionOverlay` | 供网格页顶部栏打开的轻量条件勾选浮层：展示可勾选原子 `ConditionOption` 清单与条件 Tooltip，提供 `[重置]`、`[同步自身]`、`[参数…]`、`[应用并计算]`、`[仅应用]` 与 `[取消]`；方向键与 `Space` 预览勾选，确认后才提交选中状态 |
| `ScenarioParamsOverlay` | 持有单份独立参数草稿：合法幸运范围的 `UiNumberField` + `UiScalarSlider`（精细步长 `0.01`）、工具选择列表、附魔搜索与等级增减按钮及固定抽样次数读数。「仅应用」保存输入草稿，「应用并计算」校验表哈希、选项代次与参数边界后发包（失败保留草稿） |
| `ScenarioRecommendationOverlay` | 展示物品/子表入口推荐求解的等待、失败重试与参数及建议启用条件变更预览；应用推荐同时填回推荐的启用条件与参数草稿，仍需显式计算。关闭、切表、切输入或目录失效时取消旧请求 |
| `ScenarioExpandedOverlay` | 为 `UiLightbox` 装配 `UiDocumentLightboxContent` 全屏放大条件树：初始按窗口尺寸适配档位（不超过自然字号），支持滚轮缩放与拖动平移，窗口 resize 保持手动缩放比并钳制；`Esc` 关闭（点击遮罩不关闭），关闭不破坏页内原滚动位置 |

所有功能图标、状态徽章、按钮九宫格与场景底纹均统一取自 `ui_atlas.png`（由 `tools/drawer/generate_ui_atlas.py` 生成），分区与 UV 见[UI 图标与控件皮肤图集](toolbar-icon-atlas.md)。

## 6. 阅读状态与本地持久化

`RightPageContainer` 是右页阅读状态的唯一捕获/恢复入口。`ReadingState` 使用具名 `PagePositions` 保存介绍、网格、日志列表和日志详情页号，同时携带 Tab、active tag、日志身份、批选与按表条件偏移。集合不可变，NBT 只存在于 codec 与存储边界。面板布局随直接构造和 `applyLayout` 完成。

`UiStateSnapshot` 聚合这份阅读状态和目录、工具栏字段，在 resize、同 Screen 子页返回与历史回退时复用。普通关屏从同一次捕获生成 `JournalReadingState.persistent()`，写入 `JournalUiPreferencesStore`；持久投影剔除批选。新建 Screen 与同世界重连可恢复最后表、Tab、各页号、tag、日志详情和条件偏移。目录展开、目录滚动和分类选择继续只在当前连接记忆；显式物品搜索使用其请求的内容上下文。

恢复先选择有效表并准备内容，再校验 tag、日志 entry 与详情模式，随后应用并钳制页号和 offset，最后恢复 Tab、过滤批选并更新 chrome。切表重置右页内容身份和页号；同表刷新先捕获后恢复。失效表或被当前投影过滤的表清除旧右页上下文，日志条目删除时回到列表并清除详情页号。

Screen 在目录与进度尚未完整提交时保留一次待恢复目标。客户端数据所有者的就绪状态和 revision 一同驱动既有刷新点，覆盖首次 revision 与初始值相同的情形；空目录也属于已就绪。恢复受连接 epoch 限定，新导航、键盘或搜索输入取消旧目标。过早关屏保存待恢复目标，避免将尚未加载的默认页覆盖到偏好。

本地文件按世界和玩家隔离：

- `journal_ui_preferences.dat` 由偏好 store 管理阅读投影、排序、搜索、收藏和模拟偏好；格式与连续迁移见[本地持久化](../foundation/persistence.md#21-客户端笔记偏好)。
- `journal_log_state.dat` 由 `JournalMirror` 管理会话、序号与本地日志镜像，沿既有冷却与整批快照协议工作。

`JournalLocalPersistence` 使用同目录临时文件和原子替换，保存失败保留待写冻结 NBT，由既有 tick/退出路径重试；同路径重连优先加载待写快照。切连接先保存旧路径，再推进 epoch 并复位。偏好格式读取失败或未来版本被拒时报告原因，当前路径保持只读以保留原文件。

## 7. 文本配色与裁剪生命周期

- 文本语义配色遵循[文本格式规范](../foundation/text-format.md)的「GUI 内文本规范」：Tooltip 使用 `TooltipBuilder`，书页自绘文本取 `UiTextScheme.PARCHMENT` 配合 `JournalTextColors`，管理/暗色页面取 `UiTextScheme.DARK`。
- 目录列表使用 `UiRenderLayers` 按当前 pose 换算裁剪，在绘制结束或异常退出时恢复父级裁剪。日志战利品的只读布局直接读取实际悬停槽位，以条目和物品签名提供稳定身份。
- 节点与文档缓存仅随内容版本、字体、资源或视口变化刷新，不在每帧重建树结构；3D 物品与模型在 `UiRenderLayers` 局部裁剪上下文内绘制，关屏时按作用域自动释放模型工作区。

## 8. 相关文档

- [客户端与 GUI](../subsystems/client-ui.md)
- [UI kit 公开 API 与兼容策略](ui-kit-api.md)
- [UI 图标与控件皮肤图集](toolbar-icon-atlas.md)
- [文本格式规范](../foundation/text-format.md)
- [战利品表系统](../subsystems/loottable.md)
