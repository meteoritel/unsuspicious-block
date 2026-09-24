# 笔记 GUI 内部机制

> 考古笔记 GUI 的实现细则：分层职责、追踪管理页、声明式 UI kit 契约、场景详情页与模态交互、面板状态与偏好持久化、文本配色约束。
> 本文件不参与任务导航，只被 [客户端与 GUI](../subsystems/client-ui.md) 链接。**新增面板/按键/HUD 只需要读那篇**；只有改动 GUI 分层、UI kit 或面板状态时读本文件。

## 1. 笔记 GUI 分层职责

`client/ui/` 按职责分层：

```
ui/
├── ArchaeologyJournalUi          UI 注册入口（注册 opener）
├── JournalBookBackground         书本背景渲染
├── PotteryPreviewRenderer        陶轮预览渲染
├── entry/      目录条目（ArchaeologyJournalEntry / ItemEntryLike / ArchaeologyEntryItem / ArchaeologyEntryLogRef）
├── layout/     布局（JournalLayout 书本双页布局 / JournalViewport 视口与滚动区域 / LayoutAware / LayoutAware 接口）
├── panel/      可复用面板
├── screen/     顶层 Screen
├── support/    业务支持类
├── toast/      Toast 通知
├── widget/     交互组件
└── tooltip/    tooltip
```

**screen/**：`ArchaeologyJournalScreen` 是主屏幕；`LootTableManagementScreen` 是与手册 TAB 分离的追踪管理页；`JournalViewModel` 持有视图状态，`CatalogToolbar` / `LogToolbar` 是工具栏；`SpecimenBoxScreen` / `PotteryWheelScreen` 是容器屏幕；`JournalLogNoteEditScreen` 与 `JournalLogRetentionScreen` 分别编辑日志备注和当前表保留策略。

**panel/**：`CatalogPanel`（连续滚动目录）、`LogPanel`（日志）、`DetailOverlayPanel`（详情浮层）、`ItemGridPanel`（物品网格）、`LogDetailPanel`（日志详情）、`PagePanel` / `PageIndicator`（右页分页）、`RightPageContainer`（右侧标签页容器）、`WelcomeStatsPanel`（首页统计）、`ScenarioPanel` / `ScenarioDetailPanel` / `ScenarioPageBuilder` / `ScenarioFrameView`（场景页与网格页头部）、`ScenarioSelectionOverlay` / `ScenarioParamsOverlay` / `ScenarioExpandedOverlay`（三类模态）、`FrameState`。

**widget/**：`IconButton`、`BookmarkToggleButton`（收藏）、`CopyCoordinateButton`（复制传送指令）、`JournalPageButton`（翻页）、`PotteryWheelModeButton`（陶轮模式切换）、`ShadowlessEditBox`（无阴影输入框）、`ExternalLinkButton`、`BookSideTabButton`。

**support/**：`ArchaeologyJournalClientState`（状态）、`CatalogSorter`、`JournalSearchQuery`、`JournalTooltipBuilder`、`JournalFormatHelper`、`LogGrouper`、`PaginationState`、`ScrollTextHelper`、`JournalUiPreferencesStore`、`ArchaeologyJournalLogLocalStore`、`JournalItemDetailAppender`、`UiPanelRegistry`、`UiStateful`、`UiTextPalette`、`ScenarioLabel`、`ScenarioPresentation`、`ClientLootTableLanguageStore`、`LootTableManagementClientState`。

**toast/**：`JournalUnlockToast` 弹出表/物品解锁与 100% 完成通知；`CatBondToast` 弹出羁绊阶段变化通知（由 `HandOfCatClientState` 触发）。

工具栏共用的 `toolbar_icons.png` 是 `9 x 9` 单元格组成的 `9 x 3` 图集，固定槽位与 UV 见 [工具栏图标图集](toolbar-icon-atlas.md)。

## 2. 追踪管理页

考古笔记左外侧的管理按钮打开独立 `LootTableManagementScreen`。页面提供名称/ResourceLocation 搜索、全部/已追踪/未追踪/最近遇到筛选、状态切换和自定义名称编辑。

- 候选项按 namespace、path 与子路径构造成**可逐层展开的文件树**，通过滚轮或可拖动滚动条连续浏览；搜索时自动展开匹配分支。"最近遇到"模式使用服务端下发的玩家记录，并让每级分支按最近的后代条目优先排列。
- 布局根据当前 GUI 逻辑分辨率动态计算面板与双栏，截断的 ResourceLocation 可悬停查看完整值。**无权限玩家仍可浏览**，但只有服务端权限等级 2 的玩家可以修改。
- **列表不在客户端自行枚举战利品表**，而是显示 `LootTableManagementClientState` 接收的服务端注册表与最近记录快照。
- 语言选择器由语言代码输入框（`EditBox`，支持自定义语言代码并带校验，最长 16 字符）与旁侧按钮打开的 `LanguageSelectionScreen` 选择弹窗组成。
- 页面同时读取当前游戏资源栈中的语言 JSON：**资源已有名称时显示 Resource Pack 来源并锁定输入**，资源缺失时才允许编辑服务端补充配置，当前语言缺失时用 `en_us` 作为提示回退。手工输入按语言与表保存在页面草稿中，"应用更改"一次提交全部草稿；管理员可通过系统文件选择窗口导入本地语言 JSON，确认统计预览后批量提交。提交后的服务端过滤/写盘/广播语义以 [战利品表系统](../subsystems/loottable.md) 为权威；服务端快照只驻留当前连接内存，断开即清除。

### 2.1 网格页的展示优先级链

`ItemGridPanel` 只展示**当前表自身**的获取路径。直接引用的子表以与物品 tag 分组相近的预览入口参与分页，物品卡片与子表入口显示同一个**服务端派生的当前输入展示状态**（`Probability` 四态），客户端不参与判定、也不跨代表场景取最大值。

显示优先级链为「可适用性状态 → 可展示时的声明触发率 → 模拟值」：

- 「需要条件」与未知**只显示状态词、不显示任何数字**（否则一个当前拿不到的条目会顶着最有利场景的数字出现）；
- 零命中显示「未命中」；`0%` 只留给静态不可达；
- tooltip 按状态类型给文案——需要条件逐条列出引用的旋钮与条件、未知按原因分述、零命中报本次抽样次数——并并列其它代表场景的最小/最大值供对照。

**子表入口与物品同一条优先级链**：状态词优先于区间。同一条链在子表入口上曾被区间顶掉（区间里含 `0%`，读起来像"不可能"，与「需要条件」互相打脸），因此子表入口也改为先判状态；区间只在数值态出现。子表入口的**条件树由服务端下发**（`ChildTableProbability.conditions`）——通往它的路径共同成立的条件（交集）加上注入边门槛。此前该条件由客户端从物品路径本地重推、并对原版钓鱼表注入的泥底打捞入口留了一条"回填子表自己直接路径条件"的专门分支，而注入边不写在任何 JSON 里，本地重推必然漏掉它，泥底打捞入口因此显示成没有原因的「未命中」。**现在客户端只渲染服务端给的那一份**，专门分支与本地推导一并删除。

点击子表入口后由 `JournalViewModel` 展开目录祖先并选中目标子表。左页目录使用鼠标滚轮或可拖动滚动条连续浏览，不再分页；目录树每个节点独立保存展开状态，**展开父表只显示其直接子表**，只有显式展开子表时才显示孙表。从全目录搜索结果选中条目时，`JournalViewModel` 同步切换到该条目所属分类并展开父级路径；关闭搜索后仍保留该条目与右页标签页状态。子表入口优先预览自身直接物品；纯转发表没有直接物品时递归使用后代物品作为图标，并对循环引用做保护。子表物品**不会**进入父表网格或父表的物品搜索匹配；父表 Intro 会按需递归映射全部后代物品，按物品签名去重并读取父表自身的发现记录，避免为每个树节点重复缓存完整子树物品。

## 3. 文本配色约束

两类文字各有自己的底色，选色时必须按**对比度**而不是"看起来淡一点"来决定：

| 场景 | 常量 | 底色 | 要求 |
|---|---|---|---|
| 网格/纸张上的状态词与数值 | `ItemGridPanel` 的 `*_COLOR` | 浅色纸面 | ≥ 4.5:1。状态词（「需要条件」「?」「尚未计算」）原为 `0xFF6B6B6B`（约 3.9:1，实测难以辨读），已改为深暖灰 `0xFF4A4038`（约 7:1） |
| tooltip 副文本 | `TooltipBuilder.HINT` | 近黑的深色 tooltip 背景 | ≥ 4.5:1。**不要用 `DARK_GRAY`**：它在该背景上只有约 1.9:1，几乎读不出来；而这里承载的恰恰是"为什么没有数字"这类必须读到的信息。与 `LABEL`（`GRAY`）同色是刻意的取舍——可读性优先于层级装饰 |
| tooltip 条件树的树枝前缀 | `TooltipBuilder.HINT` | 同上 | 同上；条件树正是"为什么没数字"的依据，前缀不可用 `DARK_GRAY` |

`TooltipBuilder` 的语义色表是唯一取色入口（规范见 [文本格式规范](../foundation/text-format.md)）；新增语义应加别名而不是在渲染点临时挑色。

## 4. 声明式 UI kit

`client/ui/kit/` 提供 Java 声明式块序列 API，业务方给出内容，`UiDocument` 负责测量、排版、裁剪、命中和绘制。实现位于 common 客户端包，**不依赖平台类，不发网络请求**。场景详情页已接入；其它旧页面保留原有实现。

| 类型 | 契约 |
|---|---|
| `UiNode.Row` | 缩进单位为内容像素；可选**行首图标**（`leading`，物品清单每行「图标 + 名称」用它），文字后跟 `List<InlineIcon>`；文字与图标各自保存 tooltip、载荷、动作 |
| `UiNode.Gap` / `Divider` | 固定空白 / 跟随视口宽度的单像素分隔线 |
| `UiNode.Frame` / `FrameSpec` | 固定大小的子视口；**只允许一层，禁止 Frame 内再嵌套 Frame**；边框与浮动控件由宿主绘制 |
| `UiIcon.Item` / `Sprite` | 原版 16×16 物品 / 显式指定图集尺寸的原生大小贴图区域 |
| `TextScroll` | **超宽文本的唯一实现**：宽度够则照常画，超宽且悬停时按「起点停顿—连续位移—终点停顿—往返」滚动；`support/ScrollTextHelper` 是它的 `String` 门面 |
| `TextMeasurer` | 注入宽度和行高；`TextMeasurer.of(font)` 提供原版适配；不折行 |
| `UiTarget` | `hit()` 返回的唯一目标；图标优先于行，空图标提示也不会回退到行提示；矩形属于命中文档的内容坐标系 |
| `UiTransform` | 内容原点、平移、缩放及双向坐标转换；档位 0.5/1/2/3，鼠标锚点缩放 |
| `UiMetrics` | 构建/排版/渲染/命中最近一次耗时（ns）与调用次数；生产宿主传 `false` 关闭计时 |

**行文本的宽度口径**：排版按自然尺寸不折行，但行文本的可用宽度以视口宽度（1 倍档参考）为上限——超出的部分不参与排版宽度，因此不会再被静默裁掉、也不会把后续图标挤出视口；它在**悬停时于带内滚动**（`render(graphics, font, mouseX, mouseY)` 逐行判悬停并自持滚动计时，旧的无鼠标重载等价于整篇不悬停）。`UiControl` 的标签同一口径：宽度足够时行为与过去逐像素一致，只有确实超宽才滚动。

数据流为 `业务版本 + 构建器 → UiDocument.setContent → layout → render / hit`。`setContent(revision, supplier)` **只在版本变化时执行构建器**；传入列表的重载每次都会更新。业务方应持有稳定构建器、为所有影响内容的输入维护版本，**不应在逐帧路径创建节点、列表或 lambda**。节点及其 Component 快照交给文档后按只读使用。

`setViewport(x, y, width, height)` 使用调用方 GUI 逻辑坐标；`hit(mouseX, mouseY)` 使用同一坐标系。文档原点随视口移动，只有内容、视口尺寸或 `invalidateLayout()` 改变时重排，平移和缩放均不重排。字体/语言/资源重载由宿主更新内容版本并失效排版。Frame 的 `UiTransform` 由宿主持有，原点由父文档排版设置；它的缩放锚点应先换算到父文档内容坐标，每个活动 Frame 使用独立变换对象。

排版缓存文字视觉顺序、矩形、图标与命中目标；稳定帧的 kit 绘制/命中不创建自有对象（游戏引擎内部除外）。纵向块数组用二分定位首个可见块，框外块与完全不可见图标跳过绘制。宿主每次只取一个 `hit()` 结果派生 tooltip；动作由宿主调用 `target.action().run()`，**kit 不负责输入分发、焦点、浮层或状态持久化**。

**裁剪边界**：1.21.1 `GuiGraphics.enableScissor` 不读取 pose（已核实原版源码）。`UiTransform` 在内容变换入栈前，将当前 pose 的轴对齐平移/缩放应用到视口矩形，再设置 scissor，并在裁剪切换前提交绘制批次。支持书本整体缩放和 Frame 内缩放；**不支持旋转、错切、透视**。整数 GUI 像素裁剪向内取整，分数边界最多收进不足一个 GUI 像素。嵌套 scissor 使用原版交集栈恢复外层裁剪。

最小接入示例（在初始化/内容变更时构建）：

```java
UiDocument document = new UiDocument(TextMeasurer.of(font), false);
document.setViewport(x, y, width, height);
document.setContent(List.of(new UiNode.Row(
        Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"),
        UiTextPalette.Parchment.TITLE)));
// 绘制时调用 document.render(graphics, font)，交互时调用 document.hit(mouseX, mouseY)。
```

**S1 临时验证页**：Fabric / NeoForge 的 Gradle 客户端运行配置默认带 `-Dunsuspiciousblock.uiKitDebug=true`；IDEA 刷新 Gradle 后运行 `runClient`，打开笔记后按 `Ctrl+F8`。`UiKitDebugScreen` 包含 200 行、40 个物品图标和 20 个贴图图标，支持拖动、滚轮缩放、复位，以及 `P` 切换外层 pose 1x/2x。底部依次显示构建/排版次数、四段耗时（微秒）、内容缩放、外层缩放；每 5 秒在日志输出 ns 计时。拖动/滚轮/复位时排版次数应保持不变；`P` 和 resize 允许因视口改变重排。正式发布环境或未启用开关时无法进入。

## 5. 场景详情页与模态交互

`RightPageContainer.setTable` 同时向网格页头部与 `ScenarioDetailPanel` 传递 tableId，SCENARIO tab 由新面板负责。页内布局是读数行 y=6、动作行 y=18、框 y=34（152×166），底部分页带仍由原生控件负责。各 tab 的分页带常量彼此独立，`pageIndicatorY()` 统一提供当前页指示器及按钮位置。

**框内两段**：`ScenarioPageBuilder` 只在内容 revision 或请求状态改变时重建，产出「条件区 + 可达条目区」两段块序列。

- **条件区**：只列场景相对基准**成立**的条件（为假的合成 `inverted` 包装是基准本身，列出来只会多出整屏恒否的行）。区标题三态——表本身没有可调条件用 `no_assumptions`；基准用 `baseline_all_false`（带数量，tooltip 逐个列出被置假的条件，这是「基准」唯一的可读定义）；其余场景用 `scene_conditions`。
- **折叠**：`ScenarioLabel` 把「父行只描述条件类别、且恰好一个子行」的节点折叠为其子行（如 `location_check{biomes}` → 「群系: X」），取值因此与条件同行。判定按本地化键后缀（`location_check` / `weather_check` / `damage_source_properties` / `all_of` / `any_of`），**不含**自带取值的 `block_state_property`、`time_check` 区间行与自带语义的 `inverted`；多子行保留分组。
- **可达条目区**：列出本场景**确实能产出**的条目（`isMeasured()` 且非零命中，按概率降序，上限 12，其余提示见网格页），每行是「行首物品图标 + 名称 + 概率」。不可达、未命中与未知的条目留给网格页的四态展示。未算出时头部显示状态词与「点计算」提示。
- **数字不再挂在条件行上**：同一物品在多条条件下会重复出现，挂数字极易被读成「每行各一份」，因此条件行只负责说清场景定义。

`ScenarioPresentation` 只复用同参数、同抽样档位的明确场景引用，**绝不把目录的基准总概率当作其它场景的概率**；当前输入的直接结果可用其总概率作为缺失场景引用的回退。

**场景可读名**：`ScenarioLabel.label` 由叶子条件的本地化全文拼出（「场景 N · 开阔水域 + 群系: #cis_swamp」），页标题与两处场景列表共用同一份文案；`definition` 提供完整定义（含取反叶子的「非:」前缀）供 tooltip 使用。此前作为标题的字母助记公式已删除——它既需要一份额外的键后缀契约，又只能靠 tooltip 解码。

读数行、标题提示、四态角标、动作行和框角控件均使用 `UiControl`：配置时测量并缓存目标，绘制时复用固定矩形；**超宽文本在悬停时滚动**，不再静默截断。树内命中仍走 `UiDocument.hit()`。`ScenarioFrameView` 统一提供页内和放大框的绘制与交互：框角档位/复位先于树命中，缩放档位 0.5/1/2/3，滚轮以鼠标位置为锚点，拖拽只改平移，二者均不重排。

`OverlayLayer` **同时只打开一个模态**，六类输入入口均先分发给它，并保留先 flush 再 z=400 的层高契约。模态期间屏幕抑制下层自绘 tooltip、原生控件悬停和 JEI 悬停物品查询。三个使用者是：

- `ScenarioSelectionOverlay`：最多七行可见，使用服务端场景顺序；滚轮或分组箭头浏览，上下键改变当前场景，点击行后关闭，ESC/外部点击关闭。它只吃「表 + 签发清单 + 当前场景 + 参数 + 一个回调」，因此**场景页的「场景」按钮与网格页头部的「切换场景」按钮共用同一实现**：前者把选择映射到页码（会保存框内视图），后者只切换选择（网格数字随之更新，不跳页）。网格页不再自绘下拉。
- `ScenarioParamsOverlay`：独立草稿、确认/取消；工具、抽样、附魔等级由签发清单约束，幸运值是唯一原生 EditBox，支持拖选；参数多时滚动。确认再次使用最新目录校验，**不自动计算**；ESC 取消，点外不关闭。
- `ScenarioExpandedOverlay`：居中遮罩窗口，复制页内视图到独立 `ScenarioFrameView`，复用相同交互；关闭时丢弃窗口临时平移/缩放，因此页内视图保持打开前状态。关闭按钮与框角控件均置于物品之上。

**网格页头部**（`ScenarioPanel`）只剩读数行与「切换场景 / 计算」两个动作，文字超宽时悬停滚动。网格页调整参数需到场景页打开参数浮层。

**测量只能由显式计算按钮发起**。旧网格头部的防抖自动请求已去除，应用推荐只改变选择。未新增网络包；仍复用既有请求/结果协议。

贴图由 `scripts/drawer/generate_scenario_ui.py` 生成：`scenario_frame.png` 是 24×24 九宫格（8px 四角），`UiNineSlice` 用九个四边形拉伸边和中心；`scenario_status.png` 为 48×12 四格，空心点/沙漏/勾/叉对应未计算/计算中/已缓存/失败。角标同时使用形状区分状态，详细状态和失败原因放 tooltip。

## 6. 面板状态与偏好持久化

新面板实现 `LayoutAware.applyLayout(BookLayout)`、`UiStateful.saveUiState/loadUiState(CompoundTag)`，**在创建处向 `UiPanelRegistry` 注册一次即可**。屏幕遍历注册表完成布局与状态恢复；旧面板不迁移接口，`RightPageContainer.savePages/loadPages` 只补齐各 tab 页号，修复 resize 只保留当前 tab 的缺口。

`ScenarioDetailPanel` 按 `tableId#scenarioKey` 保存框档位/平移，最多保留 512 个视图，切场景或表时先捕获旧视图。resize 快照与关闭笔记时都包含非当前 tab 的新面板状态；状态存在 `JournalUiPreferencesStore` 的 `panels` NBT 子树。窗口尺寸变化会关闭临时浮层、丢弃未确认草稿，保留已确认参数和页内视图。

参数仍在每张表内跨场景共用同一套，工具清单由该表签发。`SimulationPreferenceStore` 现在通过 `JournalUiPreferencesStore` 的 `simulationPreferences` NBT 子树读写选择，和 UI 偏好共用按存档、按玩家隔离的 `journal_ui_preferences.dat`。**旧的全局 `config/unsuspiciousblock-simulation.properties` 保留但不再读取或自动导入**，避免把一个存档/玩家的选择带到其它存档/玩家；首次使用新存储时由当前签发清单初始化合法参数。切换连接清空本地加载缓存，断线刷盘仍使用已加载的旧世界路径。

> 该旧存储的 SPI 链（`IClientSimulationPreference` → `FileSimulationPreference` → 两端实现）已无调用点，属待清理的死代码，**不要作为新代码的参考模式**（见 [平台抽象](../foundation/platform-spi.md)）。
