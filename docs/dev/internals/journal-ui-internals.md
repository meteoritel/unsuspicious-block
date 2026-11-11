# 笔记 GUI 内部机制

> 笔记的输入组合、业务展示、场景交互与阅读状态。客户端入口见 [客户端与 GUI](../subsystems/client-ui.md)，独立库消费契约见 [UI kit 公开 API 与兼容策略](ui-kit-api.md)。

## 1. 职责与输入拓扑

| 所有者 | 职责 |
|---|---|
| `ArchaeologyJournalScreen` | kit `UiScreen` 根，选择顶层作用域，组合书本与页面，管理导航历史和返回快照 |
| `JournalViewport` / `JournalBookBackground` | 书本逻辑坐标与缩放；使用 kit Sprite 绘制宿主书本纹理 |
| `JournalViewModel` | 分类、目录投影、展开路径和所选表 |
| `CatalogToolbar` / `LogToolbar` | 稳定搜索草稿、防抖、排序、过滤与批量操作入口 |
| `CatalogPanel` / `RightPageContainer` | 左页目录与右页 INTRO / ARCHAEOLOGY / LOG / SCENARIO 的实际内容树 |
| `JournalControls` / `UnsuspiciousUiTheme` | 宿主图集、书签与热点皮肤；羊皮纸和深色主题 |
| `JournalOverlays` | 当前场景层的所有权、准备与关闭回调 |
| `UiPanelRegistry` / `LayoutAware` / `UiStateful` | 面板布局及 NBT 阅读状态装配 |
| `support/` | 搜索、排序、格式、概率显示、持久化与网络动作适配 |

根使用唯一 kit router，绘制、鼠标位置和拖动距离都经 `JournalViewport` 换算。`UiChildren` 反映实际成员，控件、页签和行热点按稳定业务 key 复用；焦点与持续交互归当前 `UiInputScope`。目录和条件区各贡献自己的键盘停靠点，其余焦点来自真实 kit 控件。

按钮在配对释放且仍命中时执行；拖出释放取消。鼠标可设置逻辑焦点，轮廓随键盘模式显示。目录方向键只在目录持焦时导航，输入框编辑优先。搜索保留防抖；Backspace 优先交给文本和顶层浮层，再回退导航历史，无历史时关闭笔记。历史恢复阅读，不撤销已提交参数或服务端操作。

`UiPointerHit` 是 tooltip 和 JEI 悬停查询的共同来源；查询前准备当前布局。模态、文本输入或持续捕获占用输入时，底层 JEI 物品查询返回空，提示也遵循当前作用域门禁。

`RightPageContainer` 的局部 `PageDescriptor` 绑定稳定 Tab、活动内容、书签、指示器位置/可见性与进入/离开动作。容器同时持有书签和翻页控件，通过 `chromeControls()` 交给 Screen 注册；Screen 只用 `setChromeAvailable` 控制分类首页等整体可见性。新增右页时在同一描述列表接入内容与 chrome，领域导航仍由业务动作执行。

ARCHAEOLOGY 由 `ArchaeologyPage` 组合网格和场景栏，场景栏优先命中，焦点按网格再场景栏收集；普通输入继续复用现有 kit。LOG 的内容 Supplier 按 list/detail 模式选择实际面板。准备、绘制、控件、tooltip 与输入均经实际 PagePanel，分页调用面板的 `setPage/changePage`，由面板维护 dirty、页码范围与刷新；容器在同一准备阶段同步页码、指示器和翻页按钮。

## 2. 目录与追踪管理

目录分类使用 kit 网格几何，表目录使用行几何，连续滚动及滚动条由 `UiScrollView` 管理。绘制、点击和提示使用相同几何。分类按两列卡片组织，表目录保留节点独立展开；展开父表只展示直接子表。点击子表入口先展开当前父路径，再选其下的目标行；搜索结果选中后同步所属分类和父级展开。

目录对外 `getScrollOffset/setScrollOffset` 和快照仍使用条目单位，内部按当前分类或列表步长转换为像素。切换形态时重算首项和步长，窗口变化后钳制。

左侧管理入口打开独立 `LootTableManagementScreen`：

- 数据只来自 `LootTableManagementClientState` 的服务端快照；按 namespace/path 形成树，搜索时展开匹配分支。“最近遇到”按服务端记录组织分支顺序。
- kit 列表处理滚动与导航，重建后按行 key 恢复浏览选择；所选表由 ResourceLocation 标识。浏览和激活分开，点击或 Enter 激活条目。
- 详情使用 kit 滚动体，导入与返回固定在底部；Tab 聚焦字段时滚动到可见位置。
- 名称草稿以语言代码和表 ID 为键。资源栈已有名称时展示来源并锁定编辑；资源缺失时可编辑服务端补充名称，缺省提示回退 `en_us`。资源失效时重新构造投影并协调草稿。
- 语言代码使用 kit 文本输入，选择器和导入预览使用 kit 模态。系统文件选择器继续由原接口提供，读取、大小和格式失败保留独立原因。确认后才提交。
- 无权限仍可浏览；权限与保存语义以 [战利品表系统](../subsystems/loottable.md) 为准，服务端名称快照只驻留当前连接。

## 3. 网格、详情与日志

`ItemGridPanel` 先按 tag、子表、直属物品形成逻辑序列，再切出当前页的 `visibleCells` 投影。绘制、提示、控件装配和命中都读取同一投影；单元保留物品、tag、子表和返回入口的业务区分，留白、间隙和不满末行没有目标。物品、来源与预览使用 kit 局部物品层，装饰留在同一层，数量角标用前景层。卡片的颜色、纹理、概率解释和业务分页由宿主提供。

网格只展示当前表自身路径。普通物品卡片只读，不跳转场景或请求推荐。未发现物品只展示未知图标与“待解析”，tooltip 不透露物品、概率、声明触发率、区间或路径。已发现条目按“可适用状态 → 可展示的声明触发率 → 模拟值”显示：需要条件与未知只显示状态，零命中显示未命中，静态不可达才显示 `0%`。

子表入口使用同一优先级链，条件来自服务端下发的 `ChildTableProbability.conditions`，客户端不重新推导注入边。tag 取本组成员、子表取其后代物品闭包；有成员已发现后才解锁名称、预览和概率。未解锁入口不泄露 ID、成员数和概率。子表预览只取已发现成员。子表物品不进入父表网格或父表物品搜索；Intro 递归映射后代，按物品签名去重，并读取父表自身发现记录。

日志分页、分组、批选和选中 entry ID 由业务模型持有。`UiControlGroup` 复用日志行、复制和备注操作；富提示从行命中生成。网格、介绍页和日志详情在物品命中时把已解析的 `ItemStack` 放入 `UiPointerHit.Row.key`，Screen 的 JEI 查询直接消费该行，不再通过坐标二次反查。详情物品区域在输入准备时生成几何，不依赖上一渲染帧。删除单条、本表及全部日志均通过 kit 确认，确认前无请求。

`JournalLogNoteEditScreen` 和 `JournalLogRetentionScreen` 稳定持有编辑器与 scope，resize 只更新布局。备注保存按服务端的 500 UTF-16 单元上限校验，超限保留草稿并提示。自动保留值至少为 1，一次清理允许保留 0 条；数字解析和范围校验通过后才发送原 payload。保留设置正文滚动，完成按钮固定。返回笔记恢复子页打开前的阅读快照。

首页统计沿原数据版本缓存，最近记录和物品的提示在当前命中时生成。

## 4. 场景条件与文档动作

`ScenarioPanel` 提供网格页头部的场景选择与显式计算；`ScenarioDetailPanel` 提供短场景名、状态、工具与参数读数、配置、计算、条件标题和放大入口。场景详情页是单页连续阅读面，标题打开 `ScenarioSelectionOverlay`，方向键只浏览，点击或 Enter 激活后才调用选择入口；通用分页和条件区未消费的滚轮不提交场景。工具图标只读，不进入 Tab 序列。物品与概率统一留在网格。

`ScenarioPageBuilder` 只从服务端场景及条件 DTO 构造条件树；假设与子表入口分组，保留逻辑、取反、目标、partial/unreadable 与不成立项展开。`ScenarioConditionLayout` 按字体和宽度产生视觉行，续行保留样式但不重复连线、图标与动作。

条件区使用独立 kit `UiDocument` 和 `UiScrollView`。标题底纹、动作列表和导航几何直接取 `UiDocument.targets()`，与鼠标点命中共用真实排版。Tab 进入条件区，上下选择动作，PageUp/PageDown 阅读，Enter/Space 执行；鼠标动作在同目标释放时执行。资源、输入 key、目录版本、请求状态、字体或布局改变时重建相关投影。

`ScenarioSimulationClientState.sceneSource` 统一详情、列表与网格来源。跨场景结果仅在具有目标场景引用且参数、样本档位相符时复用；缺引用保持未知，当前输入直接结果优先。客户端不推断条件语义或取其他场景最有利概率。

## 5. 场景浮层与灯箱

场景覆盖面使用 `UiOverlay` 或直接使用 `UiLightbox` 的层；确认使用 `UiModalStack`。根只选顶层 scope，kit 负责遮罩、焦点恢复和迟到事件阻断。`JournalOverlays` 仅调用当前层的准备和业务清理，不维护平行输入分发器。

- `ScenarioSelectionOverlay` 用 kit 滚动列表保留服务端顺序，展示短名称、状态和摘要，完整定义在 tooltip。方向键只浏览，点击或 Enter 激活后选择并关闭，Escape 取消。
- `ScenarioParamsOverlay` 持有单份草稿：合法幸运域的 `UiNumberField`、`UiScalarSlider` 和文本输入；工具列表；附魔搜索与等级增减；固定样本读数。滑条使用 kit 数值策略，精细步长为 0.01。“仅应用”保存输入，“应用并计算”显式发请求。提交前重新验证表 hash、选项 generation 和输入合法性；失败保留草稿。条件门槛由条件树阅读，参数窗口使用标准滑条和列表，不绘制伙伴肖像、宠物动画或滑条门槛/玩家标记。
- `ScenarioRecommendationOverlay` 保留等待、失败、重试与变更预览；正文滚动，按钮可聚焦。应用只选择合法参数，仍需显式计算；关闭、切表、切输入及目录失效会取消旧辅助请求。
- `ScenarioExpandedOverlay` 是灯箱内容与单个焦点目标，使用独立 `ScenarioFrameView`。fit 按窗口选择适合内容的档位且不超过自然字号；滚轮缩放、拖动平移，resize 保留手动缩放并钳制。动作导航从文档快照派生，灯箱将键盘送到持焦内容；Escape 关闭，遮罩点击不关闭。灯箱关闭不修改页内滚动。

场景纸面与图标在 `ScenarioUi` 定义，`scenario_controls.png` 与 `scenario_panel.png` 由 `scripts/drawer/generate_scenario_ui.py` 生成；共享工具栏 UV 见 [工具栏图标图集](toolbar-icon-atlas.md)。

## 6. 阅读状态与存储

`UiStateSnapshot` 组合右页 `ReadingState`、目录工具栏、目录上下文、目录偏移和面板状态。目录工具栏以 `CatalogQueryState` 整组应用；捕获使用含防抖草稿的 `effectiveSearch()`，恢复时重新设置查询并清理旧草稿。右页通过 `captureReadingState/restoreReadingState` 统一保存/恢复 Tab、当前 tag、日志 list/detail、entry ID、批选和页号。根在 resize、子页返回和历史回退时使用同一恢复入口；重新投影会丢弃已失效的业务选择。窗口调整关闭临时场景浮层，未确认浮层草稿随之结束；独立编辑页面的稳定草稿保留。

新面板实现 `LayoutAware` / `UiStateful`，在 `UiPanelRegistry` 注册。右页内部的页号绑定保留 `intro / grid / log / logDetail` NBT 键；场景面板继续由 UiPanelRegistry 序列化。场景条件滚动按 `tableId#inputKey` 保存，并使用有界缓存；旧结果分区字段忽略。

UI 偏好和模拟选择存于按存档、玩家隔离的 `journal_ui_preferences.dat`，分别属于 `panels` 与 `simulationPreferences` 子树。`JournalClientConnection` 在 tick 建立连接 epoch、复位世界默认值并加载本地数据；尚未就绪的报文保留其原连接，ready 后按接收顺序应用，迟到的旧连接报文丢弃。Fabric 从 context.player().connection、NeoForge 从 context.listener() 取得接收者。

`JournalMirror` 独占 session/snapshot/sequence、完整性与 2 秒重同步冷却，完整快照一次提交，快照后的增量按 sequence 排序应用。时间和重同步动作由连接宿主注入，current 返回保护性副本。查询与接包均不读写文件。

日志 `journal_log_state.dat` 与偏好由 `JournalLocalPersistence` 共用原路径规则和原子替换：同目录 `.tmp` 写完后 ATOMIC_MOVE，不支持时 REPLACE_EXISTING。保存输入为冻结 NBT；失败以原路径保留待写，同路径更新为最新快照，后续 tick/显式退出重试，成功后才清对应 dirty。回到同一路径时优先加载未写快照。正常保存仍在客户端线程同步执行。断连先保存旧路径，再整体替换连接数据与世界偏好。旧全局 `config/unsuspiciousblock-simulation.properties` 不再读取或自动导入；其已无消费者的 SPI 链说明见 [平台抽象](../foundation/platform-spi.md)。

## 7. 文本、裁剪与生命周期

GUI 使用 kit 的 `UiTextScheme` 和主题，书页专属名称与严重状态色由 `JournalTextColors` 提供；tooltip 使用 `TooltipBuilder` 的语义色。文字规则见[文本格式](../foundation/text-format.md#8-gui-内文本规范)。超宽标题与说明走 kit 滚动或 tooltip；非交互行使用带省略号裁剪。进度读数按填充边界分色。

文档缓存随内容版本、字体、资源和尺寸失效；变换变化只更新几何，不逐帧重建业务树。`UiRenderLayers` 处理 pose 下的裁剪和局部模型层，模型回调只使用传入的局部绘制上下文。关屏按 scope 结束捕获，由 kit 释放模型工作区。
