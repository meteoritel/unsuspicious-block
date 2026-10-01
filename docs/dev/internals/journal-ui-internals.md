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
├── layout/     布局（JournalLayout 书本双页布局 / JournalViewport 视口与滚动区域 / LayoutAware 接口）
├── panel/      可复用面板
├── screen/     顶层 Screen
├── support/    业务支持类
├── toast/      Toast 通知
├── widget/     交互组件
├── tooltip/    tooltip 基础与分解预览共用件
├── overlay/    模态层（OverlayLayer 与各浮层）
└── sample/     开发调试示例页（UiKitDebugScreen 等）
```

**screen/**：`ArchaeologyJournalScreen` 是主屏幕；`LootTableManagementScreen` 是与手册 TAB 分离的追踪管理页；`JournalViewModel` 持有视图状态，`CatalogToolbar` / `LogToolbar` 是工具栏；`SpecimenBoxScreen` / `PotteryWheelScreen` 是容器屏幕；`JournalLogNoteEditScreen` 与 `JournalLogRetentionScreen` 分别编辑日志备注和当前表保留策略。

**panel/**：`CatalogPanel`（连续滚动目录）、`LogPanel`（日志）、`DetailOverlayPanel`（详情浮层）、`ItemGridPanel`（物品网格）、`LogDetailPanel`（日志详情）、`PagePanel` / `PageIndicator`（右页分页）、`RightPageContainer`（右侧标签页容器）、`WelcomeStatsPanel`（首页统计）、`ScenarioPanel` / `ScenarioDetailPanel` / `ScenarioPageBuilder` / `ScenarioConditionView`（场景页与网格页头部）、`ScenarioSelectionOverlay` / `ScenarioParamsOverlay` / `ScenarioExpandedOverlay`（三类模态）；`ScenarioFrameView` 与 `FrameState` 由条件灯箱路径使用。

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

单件物品先按发现状态门控：未发现时卡片只绘制未知图标与「待解析」，tooltip 只显示「未发现」，不携带真实物品、概率、声明触发率、场景区间或获取路径。普通物品卡片不响应点击，不跳转场景页或请求推荐。已发现物品的显示优先级链为「可适用性状态 → 可展示时的声明触发率 → 模拟值」：

- 「需要条件」与未知**只显示状态词、不显示任何数字**（否则一个当前拿不到的条目会顶着最有利场景的数字出现）；
- 零命中显示「未命中」；`0%` 只留给静态不可达；
- tooltip 按状态类型给文案——需要条件逐条列出引用的旋钮与条件、未知按原因分述、零命中报本次抽样次数——并并列其它代表场景的最小/最大值供对照。

**子表入口与物品同一条优先级链**：状态词优先于区间。同一条链在子表入口上曾被区间顶掉（区间里含 `0%`，读起来像"不可能"，与「需要条件」互相打脸），因此子表入口也改为先判状态；区间只在数值态出现。子表入口的**条件树由服务端下发**（`ChildTableProbability.conditions`）——通往它的路径共同成立的条件（交集）加上注入边门槛。此前该条件由客户端从物品路径本地重推、并对原版钓鱼表注入的泥底打捞入口留了一条"回填子表自己直接路径条件"的专门分支，而注入边不写在任何 JSON 里，本地重推必然漏掉它，泥底打捞入口因此显示成没有原因的「未命中」。**现在客户端只渲染服务端给的那一份**，专门分支与本地推导一并删除。

物品 tag 与子表这两种**合集入口**以成员发现状态控制解锁：tag 取本组成员，子表取该表及后代的物品闭包；任何一件已发现才显示名称、预览和当前概率。未解锁时只画未知图标与「未解锁」，tooltip 不泄露 ID、成员数或概率；tag 入口不能展开。子表预览从整个闭包选至多三件已发现物品，纯转发表也能显示真正发现的成员。

点击子表入口后由 `JournalViewModel.prepareNavigationToChild` 展开**当前父表行及其祖先**，再由 `findNavigationRow` 选中该父表下面的目标子表行；目标恰好也作为分类根表出现时，不能跳到并列根表。左页目录使用鼠标滚轮或可拖动滚动条连续浏览，不再分页；目录树每个节点独立保存展开状态，**展开父表只显示其直接子表**，只有显式展开子表时才显示孙表。从全目录搜索结果选中条目时，`JournalViewModel` 同步切换到该条目所属分类并展开父级路径；关闭搜索后仍保留该条目与右页标签页状态。子表物品**不会**进入父表网格或父表的物品搜索匹配；父表 Intro 会按需递归映射全部后代物品，按物品签名去重并读取父表自身的发现记录，避免为每个树节点重复缓存完整子树物品。

**左页目录的滚动实现**：`CatalogPanel` 的滚动偏移与滚动条已由 `UiScrollView` 持有（像素口径），本类只把「条目数」口径的公开 API 换算成像素——分类模式一行 2 项、表格模式一行 1 项，步长分别取卡片高度 + 纵向间距、行高 + 行间距。对外 API（`getScrollOffset` / `setScrollOffset` / `ensureIndexVisible` / `scrollByRows`）与屏幕快照里的 `catalogScrollOffset` 仍是条目数，**持久化单位不变**；切换分类/表格时先把旧模式的可见首项取出、再按新模式步长换算，避免像素偏移直接沿用而落到不同行。同一次迁移验证了同一视口的两种内容形态：两列卡片网格与单列树列表（行步长与可见行数各不相同）。滚动条轨道矩形与旧手算值逐像素重合，**但滑块几何是迁移的固有结果、不是等价实现**：新实现按「视口 / 内容」像素比算滑块高度并保底 8 像素，旧实现按「可见条目数 / 总条目数」换算并保底 12 像素，因此相同内容下滑块长度与拖动手感可能与旧版有细微差异。

## 3. 文本配色约束

两类文字各有自己的底色，选色时必须按**对比度**而不是"看起来淡一点"来决定：

| 场景 | 常量 | 底色 | 要求 |
|---|---|---|---|
| 网格/纸张上的状态词与数值 | `ItemGridPanel` 的 `*_COLOR` | 浅色纸面 | ≥ 4.5:1。状态词（「需要条件」「?」「尚未计算」）原为 `0xFF6B6B6B`（约 3.9:1，实测难以辨读），已改为深暖灰 `0xFF4A4038`（约 7:1） |
| tooltip 副文本 | `TooltipBuilder.HINT` | 近黑的深色 tooltip 背景 | ≥ 4.5:1。**不要用 `DARK_GRAY`**：它在该背景上只有约 1.9:1，几乎读不出来；而这里承载的恰恰是"为什么没有数字"这类必须读到的信息。与 `LABEL`（`GRAY`）同色是刻意的取舍——可读性优先于层级装饰 |
| tooltip 条件树的树枝前缀 | `TooltipBuilder.HINT` | 同上 | 同上；条件树正是"为什么没数字"的依据，前缀不可用 `DARK_GRAY` |
| 进度条上的读数（解析进度、日志总进度） | `DetailOverlayPanel.READOUT_*_COLOR` | 条内底色（深灰）/填充色（暖棕、完成绿） | ≥ 4.5:1。读数画在**条内**，并按填充边界分色：填充侧用深色 `0xFF2E2114`（暖棕 6.40:1 / 完成绿 5.04:1），未填充侧用白色（深灰底 7.46:1） |

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
| `UiControlStyle` | 控件**结构色** token：普通/悬停/按下/选中/禁用背景、焦点轮廓、滚动条轨道与滑块；内置 `PARCHMENT` 与 `DARK`，`background(State)` 按「禁用 > 按下 > 悬停 > 选中 > 普通」取色。文本色不在其中 |
| `UiControl` 语义状态 | `enabled` / `visible` / `selected` / `focused` / `pressed` 只影响绘制与命中、不触发重新测量；禁用与不可见清掉按压与焦点；`isFocusable()` 要求 `action != null`，纯标签可命中、有 tooltip，但不作 Tab 停靠点 |
| `UiControlGroup` | 稳定 key → 控件的 `LinkedHashMap`，迭代顺序即绘制与命中层序（后创建者在上层）；`beginUpdate` / `obtain(key)` / `endUpdate` 复用并丢弃未复用项；`controlAt` 取最上层命中、`renderTooltip` 只画该目标的提示；`mousePressed` 命中即激活并捕获按压到释放（**不夺取焦点**）；`collectFocusTargets` 按视觉顺序把可聚焦控件交给 `UiFocusManager`，自身不再持有焦点；渲染跳过不可见控件，控件只在标签/图标确实越界时才设置裁剪（不再每控件两次 `flush()`） |
| `UiScrollView` | 视口矩形（宿主 GUI 坐标）+ 内容高度 + 偏移，偏移恒钳制在 `[0, maxOffset()]`；`push` / `pop` 进出内容坐标，`toContentX` / `toContentY` / `toScreenY` 做换算，`ensureVisible` 最小滚动；滚动条含点轨道跳转与拖动 |
| `UiLinearLayout` | 有界横纵布局：主轴 `FIXED` / `CONTENT` / `REMAIN` 加 min·max 钳制，交叉轴固定/内容/拉满，统一 `spacing` 与 `padding`，`Child.leading` 覆盖单个子项前间距；`REMAIN` **先按各子项的 min 预扣再平分余量**，容器确实放不下时按 min 溢出而不是把子项压到 min 以下；`bounds(int)` 只读缓存、越界返回零矩形，`usedMain()` 供宿主换算内容高度 |
| `UiFocusTarget` | 可聚焦目标适配器：`canFocus()` / `setFocused(boolean)` / `activate()` / `bounds()` / `accessibleName()`。kit 的焦点系统只经它读可聚焦性与边界、写焦点、请求激活，因此原生 `EditBox` 这类非 kit 控件也能按宿主给定的视觉顺序参与 Tab 导航；`setFocused` 只改绘制状态，不重建内容、不重排 |
| `UiFocusManager` | 焦点管理器：按宿主给出的**视觉顺序**登记一组目标（`beginUpdate` / `add` / `endUpdate`，同一目标一次更新内只保留首次位置），唯一决定当前焦点，提供 Tab / Shift+Tab 移动、可配置的 Enter / Space 激活、焦点失效清理与焦点变化通知（`Listener#focusChanged`） |
| `UiLightbox` | 可复用模态查看器**外壳**：遮罩、内容视口、标题与描述、底部控制栏（图集导航与页码、缩放读数、缩放与适应窗口按钮）、控件组与焦点、关闭语义；文案由宿主的 `Labels` 注入（kit 不持有文案键）。**只做外壳与输入分派，不做内容几何** |
| `UiLightbox.Content` | 内容契约：`contentWidth/Height`、`setViewport`、`render`、`zoom` / `zoomBy` / `fit`、`panBy`、`mousePressed` / `mouseReleased`、`zoomPercent`、`hit`、`placeholder`、`invalidateResources`；实现方必须把绘制**严格裁剪在视口内** |
| `UiLightbox.Gallery` | 图集：宿主报 `index` / `total`，并在 `navigate(delta)` 里换内容；外壳只在 `total > 1` 时创建导航控件与页码 |
| `LightboxImage` | 图片描述 record：稳定 `id`、贴图与区域（uv + 原始宽高 + 贴图总尺寸）、可本地化标题与描述；区域必须落在贴图内，非法尺寸在**构造期**就被拒绝；只接受客户端已可用的贴图来源 |
| `UiImageView` | `UiLightbox.Content` 的图片实现：contain 适配（默认不放大）、1.25 有限步进缩放、指针锚点缩放、平移钳制、严格裁剪绘制与缺图占位；尺寸与缩放状态只在它自己这里，图集/文案/按钮都在外壳 |

**行文本的宽度口径**：排版按自然尺寸不折行，但行文本的可用宽度以视口宽度（1 倍档参考）为上限——超出的部分不参与排版宽度，因此不会再被静默裁掉、也不会把后续图标挤出视口；它在**悬停时于带内滚动**（`render(graphics, font, mouseX, mouseY)` 逐行判悬停并自持滚动计时，无鼠标重载等价于整篇不悬停）。Frame 子文档与父文档同坐标系，鼠标会透传到 Frame 内，因此 Frame 里的超宽文本也能悬停滚动。`UiControl` 的标签同一口径：宽度足够时行为与过去逐像素一致，只有确实超宽才滚动。

数据流为 `业务版本 + 构建器 → UiDocument.setContent → layout → render / hit`。`setContent(revision, supplier)` **只在版本变化时执行构建器**；传入列表的重载每次都会更新。业务方应持有稳定构建器、为所有影响内容的输入维护版本，**不应在逐帧路径创建节点、列表或 lambda**。节点及其 Component 快照交给文档后按只读使用。

`setViewport(x, y, width, height)` 使用调用方 GUI 逻辑坐标；`hit(mouseX, mouseY)` 使用同一坐标系。几何入口对负尺寸统一**钳制**（`UiControl.setBounds` / `UiDocument.setViewport` 钳到 0，`UiScrollView` 同口径，`UiLightbox.setBounds` 钳到 1）；只有 `UiRect` 仍在构造期拒绝负尺寸（矩形自身不变式）。文档原点随视口移动，只有内容、视口尺寸或 `invalidateLayout()` 改变时重排，平移和缩放均不重排。字体/语言/资源重载由宿主更新内容版本并失效排版。Frame 的 `UiTransform` 由宿主持有，原点由父文档排版设置；它的缩放锚点应先换算到父文档内容坐标，每个活动 Frame 使用独立变换对象。

排版缓存文字视觉顺序、矩形、图标与命中目标；稳定帧的 kit 绘制/命中不创建自有对象（游戏引擎内部除外）。纵向块数组用二分定位首个可见块，框外块与完全不可见图标跳过绘制。宿主每次只取一个 `hit()` 结果派生 tooltip；动作由宿主调用 `target.action().run()`，**kit 不负责输入分发、焦点、浮层或状态持久化**。

**裁剪边界**：1.21.1 `GuiGraphics.enableScissor` 不读取 pose（已核实原版源码）。`UiTransform` 在内容变换入栈前，将当前 pose 的轴对齐平移/缩放应用到视口矩形，再设置 scissor，并在裁剪切换前提交绘制批次。支持书本整体缩放和 Frame 内缩放；**不支持旋转、错切、透视**。整数 GUI 像素裁剪向内取整，分数边界最多收进不足一个 GUI 像素。嵌套 scissor 使用原版交集栈恢复外层裁剪。**两套裁剪入口的取整与 flush 策略是有意区分的**：`TextScroll` 的文本带裁剪向外取整且不主动 flush（文本走 `drawString`，`GuiGraphics` 会在无托管批次时自行 flush），`UiTransform` 的图片裁剪向内取整并在切换前 flush（`blit` 不会自行 flush）；两者共用同一套 pose→GUI 像素换算，策略差异写在各自入口的注释里。

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

**滚动视口的坐标与裁剪口径**：`UiScrollView.setViewport` 使用宿主 GUI 逻辑坐标，与 `UiControl` 矩形同坐标系；`push(graphics)` 先 `flush`、再按当前 pose 设置视口 scissor，最后 `translate(viewport.x(), viewport.y() - offset)` 进入内容坐标，`pop(graphics)` 先弹回宿主 pose 再恢复上一层裁剪（原版 scissor 交集栈）。内容坐标系的原点是「内容顶部、与视口左边界对齐」；命中用 `toContentX` / `toContentY` 把屏幕坐标换算成内容坐标后再交给 `UiControlGroup`，`toScreenY` 用于反向定位。指针不在视口内时宿主传视口外的占位坐标（`ScenarioSelectionOverlay` 的 `NO_HOVER = -1000`）或直接跳过命中，避免视口外内容被命中。偏移在视口尺寸与内容高度变化后重新钳制；内容不超过一屏时 `maxOffset()` 为 0、滚动条不显示。`SCROLLBAR_WIDTH = 4`，滚动条始终贴视口右边界，溢出时宿主让出这 4 像素。

**布局只产出落位**：`UiLinearLayout` 不绘制、不持有控件，只产出逻辑矩形：`bounds(int)` 只读缓存、越界返回零矩形，`usedMain()` 供宿主换算内容高度。预期用法是「纵向容器里每行一个横向容器」——一层嵌套即可表达标签列 + 控件列的表格行，子布局各自 `setBounds` 到父布局给出的矩形。一行里宽度规则不同时用 per-child 的 `withLeading` 给间距，不再手算绝对坐标；`ScenarioParamsOverlay` 的三套布局分别是行槽（每行固定 20 高）、行内横排（逐行重配尺寸规则）与底部按钮行。

**稳定 key 复用与「稳定帧不重建」**：`UiControlGroup` 以稳定 key 持有控件，只在内容变化时走一遍 `beginUpdate` / `obtain(key)` / `endUpdate`：同一 key 永远返回同一个 `UiControl`，本次未复用的 key 连同落在其上的按压与焦点引用一起丢弃；滚动、悬停等逐帧操作只改状态。`UiControl.configure` 在标签文案未变时不重置滚动计时，因此「浮层按秒重配控件」不会让超宽文本每秒跳回起点；`setBounds` 在矩形未变时直接返回。`ScenarioSelectionOverlay` 据此把整表行控件只建一次（key 为场景下标），翻页与滚轮只改滚动偏移，行内容仍只在「目录 revision / 秒 / dirty」变化时重配。

**命中与 tooltip 的唯一来源**：组内命中只走 `controlAt`（自上层向下取第一个命中者），`targetAt` / `renderTooltip` 派生同一目标，同一位置只画一个 tooltip。组与组之间的区域划分由宿主负责：场景选择浮层在视口内取行组目标、视口外才交给底部箭头组，二者互斥；滚动条命中排在行命中之前，滑块压在行右侧也不会穿透。

**焦点与键盘导航**：Tab / Shift+Tab 与可配置的 Enter / Space 激活已从控件组移到 `UiFocusManager`（见「焦点所有权与视觉顺序」）；其余按键一律不消费——ESC、上下键等语义仍归模态宿主。进入序列的条件仍是 `isFocusable()`（`isHittable()` 且 `action != null`）：禁用与不可见控件既不可命中也不进序列，`setEnabled(false)` / `setVisible(false)` 会立刻清掉 `pressed` 与 `focused`；纯标签仍可命中、仍显示 tooltip，只是不再占用 Tab 停靠点。

**按压捕获**：`mousePressed` 命中即激活并记录按压目标，`mouseReleased` 只结束捕获、不在释放时激活（拖动结束不应触发点击）；`isPressCaptured(x, y)` 让宿主区分这次拖动归控件还是归下层内容。

**与原生 `EditBox` 的接驳边界**：`ScenarioParamsOverlay` 的幸运值和附魔搜索使用原生 `EditBox`（支持拖选），命中优先级高于控件组；它们经宿主侧适配器（`InputFocus`）接进焦点序列——`setFocused` 直接转给原版控件，`activate()` 返回 `false`（Enter / Space 的编辑语义属于原版），`bounds()` 取输入框矩形供焦点调试与坐标换算使用。文字输入、剪贴板与输入法都由原版控件处理，kit 不接管；控件组只自绘标签与按钮，标签 `action == null`，因此点击只读文本没有副作用。

**参数工具与吉祥物**：工具按钮只在服务端签发 `toolSelectionAllowed` 时出现。`SimulationToolDropdown` 锚定按钮下方，宽 32、最多六行，仅显示工具图标（空手用横线占位），名称与谓词保留在悬停提示中，不替换附魔列表；打开时优先接管输入，外部点击只关闭下拉，Escape 先关闭下拉，再次按下才关闭参数窗口，方向键与 Tab 切换候选、Enter/Space 选中。窗口左侧由 `SimulationCatMascot` 绘制原版坐姿猫模型与三花纹理：虚拟实体和模型仅创建一次，不加入世界、不 tick；头部与身体随鼠标调整方向，所选工具附着于头部骨骼，空手不绘制物品。附魔列表行数按可用高度调整，猫模型使用 52 的缩放尺寸并为底部提示留白。

**语义状态与样式分工**：状态解析优先级为「禁用 > 按下 > 悬停 > 选中 > 普通」，背景色从 `UiControlStyle.background(State)` 取，焦点轮廓画在矩形内侧（不侵入相邻控件、也不被控件自身裁剪吃掉）。结构色（背景、焦点轮廓、滚动条轨道/滑块）归 `UiControlStyle`，文本色仍由调用方从 `UiTextPalette` 传入，禁用态只降不透明度、保留调用方给定的色相——与「文本配色约束」的分工一致。`PARCHMENT` 的普通/悬停背景与旧硬编码值逐像素相同，按下/选中/禁用/焦点是新增态，旧界面不会触发。

**口径收窄**：前述「kit 不负责输入分发、焦点、浮层或状态持久化」现在收窄为：kit 仍不决定模态优先级、不持有业务状态；焦点与键盘导航由 `UiFocusManager` 统一提供（`UiControlGroup` 不再自行管理焦点，只把可聚焦控件交给它）；输入先给谁（原生输入框、滚动条、控件组、文档命中）由宿主维护，滚轮是否消费也仍由宿主按 `UiScrollView.contains(x, y)` 判定。

**焦点所有权与「鼠标点击不夺取焦点」**：焦点由 `UiFocusManager` 唯一持有，改变焦点只有两条路径——键盘导航（`keyPressed` 处理 Tab / Shift+Tab，用 `Math.floorMod` 环绕）与宿主显式 `focusOn`。`UiControlGroup.mousePressed` 命中后只激活动作并记录按压捕获、**不夺取焦点**，所以焦点轮廓只在键盘使用时出现，鼠标用户的画面与迁移前一致。`UiControl` 通过实现 `UiFocusTarget` 参与序列（`canFocus()` 即 `isFocusable()`），控件组不再持有任何焦点状态。唯一的例外是原生输入框：点击它时焦点由原版控件接管，宿主再把这件事同步给焦点管理器。

**视觉顺序与原生输入框夹层**：Tab 顺序不由控件组内部顺序决定——宿主按**视觉顺序**把目标登记进 `UiFocusManager`（`beginUpdate` / `add` / `endUpdate`，一次更新内重复登记同一目标只保留首次位置），因此原生 `EditBox` 的适配器能夹在两段控件之间：参数浮层的顺序是「工具行 ◀/▶ → 幸运值输入框 → 抽样格 → 附魔 −/+ → 取消 → 确认」，验证页的顺序是「控件组的可聚焦控件 → 原生输入框」。`UiControlGroup.collectFocusTargets(out)` 只是「按绘制层序追加当前可聚焦控件」的便捷入口，插在序列的哪个位置仍由宿主决定——这正是焦点从控件组拆出来的原因。

**Enter / Space 的可配置仲裁**：`setEnterActivates` / `setSpaceActivates` 由宿主按模态契约设置。两个浮层都关掉 Enter（`setEnterActivates(false)`）以保留「Enter 选择/确认并关闭」的既有语义，只让 Space 激活焦点控件；焦点管理器不消费的按键（ESC、上下键、搜索与编辑键）原样交回宿主或原生输入控件。

**焦点失效清理与焦点变化通知**：目标可能因内容更新、禁用、隐藏或控件被丢弃而失效。`endUpdate()` 移除本帧未登记的目标、并在焦点目标失效时清除焦点；`refresh()` 在每个交互入口再做一次廉价校验（不在列表里或 `canFocus()` 为假即清除）。焦点变化经 `Listener#focusChanged(previous, current)` 通知宿主，且焦点目标未变时不会重复通知——场景选择浮层与验证页用它实现「滚动到焦点」；参数浮层是分页窗口模型（目标永远在窗口内），不需要该通知。

**焦点进视口**：场景选择浮层把「焦点行整行可见」挂在这条通知上，用 `UiScrollView.ensureVisible` 实现，视口外的行由 `setVisible(false)` 出列、因此不会成为 Tab 停靠点。参数浮层是**分页窗口**模型：只创建并登记当前窗口那一屏的行，所以「焦点滚出视口」不会发生，Tab 永远只在窗口内环绕；窗口的移动改由 `PageUp` / `PageDown` 负责，焦点策略与滚轮翻页一致（不显式清除，目标被移出窗口时由 `endUpdate` 丢弃并自然清空），窗口之外的附魔行由此获得键盘通路。

**accessibleName 的用途与边界**：`UiFocusTarget.accessibleName()` 返回可读名称：`UiControl` 优先返回调用方显式 `setAccessibleName` 的名称，其次标签，标签为空（图标按钮）时回退到首行提示；原生输入框适配器返回输入框的提示名。契约上供「焦点调试与旁白」使用；**当前落地只有调试叠加层消费它**（在控件矩形上画名称、给当前焦点目标套强调边框并输出名称）。kit 不产生任何系统级旁白或语音输出，也没有屏幕阅读器集成——`accessible` 只是命名约定，不要当作完整的无障碍支持承诺。

**验证页的阶段 A/B/C 演示区**：`UiKitDebugScreen` 现在左右分栏。右列仍是既有的 200 行 `UiDocument` 演示（25 组 ×（1 根 + 3 子 + 2×2 孙）= 200 行、每 10 行 3 个图标 = 60 个图标）；左列是阶段 A/B/C 演示区——固定/内容/剩余与带 leading 的三种行、禁用控件、原生输入框适配器、`PARCHMENT` / `DARK` 的两组状态样例（普通/悬停/按下/选中/禁用/焦点）以及一段把内容撑高的纯标签列表，用来验证滚动偏移与滚动条。`F` 切换调试叠加层（默认关闭，关闭时不绘制任何额外内容）：打开后画面板与视口矩形、每个布局子项矩形、控件矩形（按启用/禁用着色）与 `accessibleName`、当前焦点目标的强调边框，并输出滚动几何与激活计数。底部读数每 20 tick 刷新，含焦点下标/目标数、滚动偏移/最大偏移/内容高与控件数。演示区与验证页新增的本地化键同时写入 `en_us.json` 与 `zh_cn.json`。

**灯箱的分工：外壳不做内容几何**：`UiLightbox` 只负责遮罩、内容视口、标题与描述、底部控制栏、控件组与焦点、输入分派；内容的尺寸、缩放、平移与裁剪全在 `Content` 实现里。图片场景用 `UiImageView`（由 `LightboxImage` 描述一张图），条件树这类自绘内容由宿主适配器实现——因此同一个外壳既能看图，也能放大整棵树。`Labels` 与 `Gallery` 都由宿主注入，kit 不持有任何文案 key；外壳默认用暗底样式（`UiControlStyle.DARK`），文本默认色按结构底色的 WCAG 相对亮度在近白/近黑之间反推，`setTextColor` 可覆盖并固定。

**为什么不依赖 `OverlayLayer`**：kit 里没有任何对浮层层的引用，宿主用 `LightboxOverlay` 这个模态适配器把外壳接进 `OverlayLayer`——渲染前写入 `layer.width/height`、转发六类输入、在 `Overlay.closed()` 里调外壳的 `onClosed()` 释放按压与焦点。依赖方向因此保持「宿主层 → kit」单向：外壳可以独立复用与验证，也不会被浮层契约牵着走；宿主打开适配器即获得遮罩、独占输入与关闭语义。

**视口与裁剪口径**：外壳按宿主可用区留 12 像素外边距，顶部让给标题与描述，底部固定 22 像素控制栏（内容视口在它上方，控制栏不会被内容盖住），剩下的矩形通过 `Content.setViewport` 写入内容；内容必须把绘制严格裁剪在该视口内（`UiImageView` 用 `UiTransform.enableScissor`，条件树沿用文档自己的裁剪）。坐标一律是宿主 GUI 逻辑坐标，缩放读数画在按钮组左侧以免压住按钮。

**fit 与 resize 的区别**：外壳只在「首次布局」与「换内容（`setContent`）」时调用一次 `Content.fit()`（`needFit` 标记）——尺寸尚未就绪时延后，resize 只重新 `setViewport` 并保留手动缩放。`UiImageView.fit` 是 contain：默认不放大，钳进 [0.05, 8.0] 后归零居中。条件树只在不超过 100% 的档位中选能放下内容的最大档，放不下用 50%，起点始终是左上角；手动仍可放到 200%/300%。

**缩放：有限步进与上下限**：滚轮与按钮走同一个「一档」步进（`UiImageView.STEP = 1.25`），比例钳制在 [0.05, 8.0]；滚轮以指针为锚点（缩放前后指针下的同一内容点保持不动），按钮则以视口中心为锚点（`zoomBy`）。到顶 / 到底或结果无变化时返回 `false`，不产生空消费；指针不在内容视口内时外壳不做缩放（输入仍由模态层吞掉，不会漏到下层）。

**平移钳制**：`UiImageView` 的偏移是「图片中心相对视口中心的屏幕像素偏移」，小内容居中，超过时钳制在 ±(目标尺寸 − 视口尺寸)/2。条件树按内容坐标钳制（可见范围 X = [−pan, vw/s − pan]，内容占 [0, cw]），小内容左上锁定，超出轴才允许平移；只有在视口内按下后才拖动。页内条件区使用纵向滚动，不提供自由平移。

**缺图占位与资源重载**：`UiImageView` 只在构造、内容变更与 `invalidateResources()` 时检查贴图可用性，绘制路径不做 IO；缺图返回占位文案，只在「不可用 → 可用」时重新 fit。条件树正文是原生文本，边框/图标经资源管理器绘制；字体、语言和宽度变化由宿主触发布局失效，不通过贴图尺寸决定树的缩放。

**图集 API**：`Gallery` 由宿主实现（报当前下标与总数、在 `navigate(delta)` 里换内容），外壳只请求切换并刷新布局。导航按钮、页码与左右方向键都只在 `total > 1` 时出现；宿主可在切图时一并换掉标题与描述（`setText`）。`setContent` 会把 fit 标记置真，因此每张图进入时都是「适应窗口」的初始状态。

**焦点、激活与关闭语义**：焦点由 `UiFocusManager` 按**视觉顺序**登记——关闭（右上角）→ 上一张 / 下一张（控制栏左侧）→ 缩小 → 放大 → 适应窗口；外壳里 Enter 没有其它语义，因此与 Space 一起用于激活焦点控件。ESC 与 × 都请求关闭（真正的关闭动作是宿主传入的 `onClose`）；点遮罩**默认不关闭**（`setMaskClickCloses` 默认 false，避免拖图时误触退出）。外壳的 `onClosed()` 由模态适配器在 `Overlay.closed()` 里调用，释放按压捕获与焦点。

**灯箱的 tooltip 唯一来源**：同一位置只出一个 tooltip——先问控件组（`controls.targetAt`），命中就只画控件的提示；没有控件命中才问 `content.hit`（例如条件树里的行），为空则不画。内容实现因此**不要自己画 tooltip**，只把命中契约暴露给外壳。

**验证页的阶段 D 演示**：`UiKitDebugScreen` 的演示区新增一个「灯箱」按钮，打开的是**复用生产模态路径**的图片灯箱——页面自己持有一个 `OverlayLayer` + `LightboxOverlay`，内容视图是 `UiImageView`，页内图集放两张图（256×256 的陶轮界面整图与 152×76 的目录条目整图，用来对照「大图 contain 后仍可放大」与「小图 fit 保持 100%」）。灯箱按**未缩放的真实屏幕坐标**画在所有内容之上并独占输入，ESC 由灯箱消费（关灯箱而不是关调试页）；页面在打开时收掉下层焦点与正在进行的拖动，模态期间不再画自己的 tooltip。底部读数在灯箱打开时追加「图序号 / 总数 | 缩放百分比」。通用文案键 `screen.unsuspiciousblock.lightbox.*`（8 个：close / zoom_in / zoom_out / fit / previous / next / position / missing）与调试页新增的 7 个键同时写入 `en_us.json` 与 `zh_cn.json`。

**机制与公开契约的分工**：本节的职责是**机制与实现口径**的权威——排版缓存、裁剪边界、命中与 tooltip 的唯一来源、焦点失效清理、fit 与 resize 的区别、平移钳制等「为什么这样做、实际怎么算」。面向第三方宿主的**公开入口清单、依赖边界检查、接入步骤、行为约定与兼容策略**另见 [UI kit 公开 API 与兼容策略](ui-kit-api.md)：公开边界以 `client/ui/kit/package-info.java` 为准，依赖方向由 `scripts/check-ui-kit-boundaries.ps1` 检查，开发与最小示例已迁到 `client/ui/sample/`。两处描述冲突时，机制细节以本节为准，接口与兼容承诺以那篇为准。

## 5. 场景详情页与模态交互

`RightPageContainer.setTable` 向网格页与 `ScenarioDetailPanel` 传递 tableId；书本底部场景页码不变。152 像素正文内依次是可点的短场景名与状态、单行参数栏、计算按钮、条件树标题与放大入口、条件树正文。参数栏最左侧只绘制代表工具的物品图标，悬停显示名称，无点击动作、按钮底色或键盘停靠点；中间是幸运/抽样读数，最右侧是配置按钮。数值标签放不下时移入 tooltip，数字保留。场景页不再有结果分栏，页内滚轮只做纵向阅读。

**条件树阅读**：`ScenarioPageBuilder.buildConditions` 只生成条件树，物品和概率交由网格页展示。场景假设与子表入口用加粗分组标题、浅色底纹和段间留白区分，各子表标题使用更浅底纹，子节点以缩进和父子连线展示逻辑关系。可执行节点使用深色下划线与悬停底色；分组几何只在内容或宽度变化时重建。条件区以自然字号纵向阅读，复杂树可展开灯箱。`ScenarioConditionLayout` 按字体与宽度拆成保留样式的视觉行，将父节点映射到首行，续行不重复连线、图标或动作；不改变通用 `UiNode.Row` 契约。

- **条件定义**：区分「场景假设」和按子表分组的「入口要求」。基准展示全部不成立假设，其他场景可展开/收起不成立项；组合逻辑、取反、实体目标、partial/unreadable 均保留。入口要求来自服务端静态 DTO，未计算也可阅读；入口提供推荐动作，手动参数统一通过页头配置按钮修改，不把不同子表门槛合成全表 AND。已知 `#c:is_swamp` 用显式本地化名显示，tooltip 保留原描述与 id，未知 tag 不猜名称。
- **结果口径**：`ScenarioSimulationClientState.sceneSource` 统一详情页、选择列表与网格页的缓存来源。同参数、同抽样档位的其他缓存只有带目标场景明确引用时才能复用；`overlay` 将物品与子表的目标场景引用投影到网格当前概率，缺引用的条目仍为未知，不借用来源场景的总概率。当前输入的直接结果仍优先。条件行不附概率，同一物品不会因为多个条件节点而重复显示概率。
- **输入门控**：详情面板以输入 key、目录 revision、请求状态、语言、字体、布局变化决定重建。标题、状态、参数、条件与提示在同次重建中取同一选择；切表取消辅助请求并收起其他假设。条件树滚动偏移按 `tableId#inputKey` 保存，读取旧偏好时忽略已移除的结果分栏及其偏移字段。

`ScenarioLabel.shortLabel` 给页头和列表提供短场景名，`detailLabel` 给列表第二行提供条件摘要，`definition` 保留完整定义。详情页的文字与操作用 `UiControl`，并通过 `UiFocusManager` 将可操作控件按视觉顺序登记；`RightPageContainer.handleKey` 与 `ArchaeologyJournalScreen.keyPressed` 将 Tab、Shift+Tab、Enter、Space 送到当前页。超宽说明可悬停滚动或通过 tooltip 阅读。

`OverlayLayer` 同时只打开一个模态；模态期间屏幕把视口外的占位鼠标坐标传给下层目录、右页与原生控件，下层不会随真实鼠标绘制悬停高亮；浮层自身仍收到真实坐标。屏幕同时抑制下层 tooltip 与 JEI 悬停物品查询。场景界面的使用者：

- `ScenarioSelectionOverlay`：纸面标题栏含关闭图标；条目为短名/状态与最多两行条件摘要，完整定义和失败原因在 tooltip。最多五项，实际按窗口高度收敛；当前项有左侧色条，仅溢出时显示导航和可见项范围。行按服务端顺序稳定复用。Tab/Shift+Tab 移焦、Space 激活；上下键改变当前场景，Enter/ESC 关闭，点击行选择并关闭。场景页与网格页共用此浮层。
- `ScenarioParamsOverlay`：羊皮纸角色卡使用独立草稿。左侧为带红色项圈的成年坐姿三花猫，工具以嘴部咬点附着于头部；点击投影后的头部区域播放约 1.25 秒闭眼摆头动画，并仅在当前客户端播放随机原版猫叫。肖像标题为「你的伙伴」，工具横向咬持。右侧以浅底细框与分隔行列出幸运、工具、附魔与固定样本量，工具图标位于右侧装备槽；幸运滑块范围沿用 `LuckGateAnalysis`，支持拖动、方向键按 0.01 微调、Shift 按 1 调节、Home/End 到端点、Space 归零，并保留精确输入框。工具使用局部下拉层，附魔在下方搜索与增减。Tab 按「滑块 → 幸运输入 → 工具 → 附魔入口 → 搜索 → 附魔增减 → 应用并计算 → 仅应用 → 取消」移动，跳过不可用项；Enter 应用并计算，ESC 取消，下拉打开时先由下拉消费输入。拖动只更新草稿；「仅应用」保存输入，「应用并计算」显式请求计算。确认前用最新目录校验；关闭清理拖动与焦点。

幸运滑块上方以赭金色点汇总当前表各分支的已知获取门槛与额外抽取门槛，同值同类型去重；未知或区间受限且无数值的门槛不推测标注。仅悬停「幸运」标题时显示用途、调节方法、玩家幸运与门槛数值；滑轨、标记及拖动期间不弹出提示。门槛列表只在不可变目录 DTO 引用变化时重建。下方蓝色标记读取当前客户端玩家的 `getLuck()` 属性缓存，每帧一次，无新增网络请求或布局重建；属性同步后下一帧更新，无玩家时隐藏，超出滑块范围时贴端点，标题提示保留实际数值。标记不改变模拟草稿。

- `ScenarioExpandedOverlay`：独立 `ScenarioFrameView` + `UiLightbox`，宿主注入纸面、图标与短标题。初次和适应窗口最大 100%，短树左上对齐；手动仍支持 50/100/200/300%。滚轮缩放、拖动平移、resize 保留手动档位并钳制；关闭不改页内滚动，点击遮罩不关闭。默认通用灯箱的暗色外壳和字符控件保持兼容。
- `ScenarioRecommendationOverlay`：已解锁子表卡片右上图标或条件页入口打开；普通物品卡片不提供推荐入口。等待/失败/超时有明确状态，可重试。预览列出当前→推荐的场景、工具、幸运、抽样及附魔（包括移除为 0 的项），随后显示完整场景定义。应用只选择合法输入，必须再点击计算；关闭、切表、切输入或目录变更后，旧请求无权覆盖选择。正文可滚动，按钮可键盘操作，网络目标约定见 [网络与同步](../foundation/network.md)。

网格页头部（`ScenarioPanel`）在原有约 28 像素预留区内居中排列一行场景选择与计算按钮，不再提供参数入口或独立状态提示；参数调整由场景 Tab 承担，不改变 2×3 物品布局。状态统一取 `ScenarioPresentation.resolve`，已计算时按钮显示「已计算」并禁用，计算期间同样禁用，状态与失败原因合并到按钮 tooltip；使用相同控件与焦点体系，**测量只由显式计算发起**。正常点击子表卡片仍是导航，小推荐图标不劫持整个卡片。条件页通过 Tab 进入节点动作，方向键定位动作，PageUp/PageDown 阅读，Enter/Space 激活。

**场景专属资源**：`ScenarioUi` 集中纸面、结构色与图标，不改全局主题。`scenario_controls.png` 为 81×9，九个 9×9 槽依次是关闭、展开、缩小、放大、适应、下拉、参数、计算、推荐；`scenario_panel.png` 为 12×12、角宽 3 的轻边框。`scripts/drawer/generate_scenario_ui.py` 可确定性重建；旧 `toolbar_icons.png` UV 不变。

**焦点交接**：`OverlayLayer.open(overlay, opener)` 只在 opener 当前持有焦点时记录返回目标，打开时清掉下层轮廓，关闭时若目标仍可聚焦则恢复；鼠标打开不登记。替换已打开的浮层可继承上一层返回目标，`Overlay.closed()` 用于释放输入捕获。页面自身现在也有 `UiFocusManager`；屏幕处理未被子层消费的鼠标点击时清理模态恢复的焦点。焦点管理器每次更新用本轮目标顺序替换旧列表，避免重复登记造成 Tab 序列膨胀。

## 6. 面板状态与偏好持久化

新面板实现 `LayoutAware.applyLayout(BookLayout)`、`UiStateful.saveUiState/loadUiState(CompoundTag)`，**在创建处向 `UiPanelRegistry` 注册一次即可**。屏幕遍历注册表完成布局与状态恢复；旧面板不迁移接口，`RightPageContainer.savePages/loadPages` 只补齐各 tab 页号，修复 resize 只保留当前 tab 的缺口。

`ScenarioDetailPanel` 按 `tableId#inputKey` 分别保存结果和条件的纵向滚动偏移，最多各保留 512 个输入视图，并保存当前「结果 / 条件」分区；切场景或表前先捕获旧偏移。resize 快照与关闭笔记时都包含非当前 tab 的新面板状态；状态存在 `JournalUiPreferencesStore` 的 `panels` NBT 子树。窗口尺寸变化会关闭临时浮层、丢弃未确认草稿，保留已确认参数与页内滚动位置。

参数仍在每张表内跨场景共用同一套，工具清单由该表签发。`SimulationPreferenceStore` 现在通过 `JournalUiPreferencesStore` 的 `simulationPreferences` NBT 子树读写选择，和 UI 偏好共用按存档、按玩家隔离的 `journal_ui_preferences.dat`。**旧的全局 `config/unsuspiciousblock-simulation.properties` 保留但不再读取或自动导入**，避免把一个存档/玩家的选择带到其它存档/玩家；首次使用新存储时由当前签发清单初始化合法参数。切换连接清空本地加载缓存，断线刷盘仍使用已加载的旧世界路径。

> 该旧存储的 SPI 链（`IClientSimulationPreference` → `FileSimulationPreference` → 两端实现）已无调用点，属待清理的死代码，**不要作为新代码的参考模式**（见 [平台抽象](../foundation/platform-spi.md)）。
### 场景分支与导航

场景页将主条件、工具/附魔/幸运/随机等次要要求分开显示，并以语义色区分正文、逻辑符号、条件值、警告与不确定信息；条件成立假设下的候选产物可以定位到当前物品网格，但不代表必得。表与 tag 保持聚合，未知条件保留为假设分支。场景页固定展示 10,000 次抽样，网格页只负责场景选择、查看结果和显式计算。

本模组 GUI 的 Backspace 按“文本输入优先 → 浮层关闭 → 导航历史回退 → 无历史时关闭当前界面”处理，不撤销已提交参数或服务端操作。
