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

**左页目录的滚动实现**：`CatalogPanel` 的滚动偏移与滚动条已由 `UiScrollView` 持有（像素口径），本类只把「条目数」口径的公开 API 换算成像素——分类模式一行 2 项、表格模式一行 1 项，步长分别取卡片高度 + 纵向间距、行高 + 行间距。对外 API（`getScrollOffset` / `setScrollOffset` / `ensureIndexVisible` / `scrollByRows`）与屏幕快照里的 `catalogScrollOffset` 仍是条目数，**持久化单位不变**；切换分类/表格时先把旧模式的可见首项取出、再按新模式步长换算，避免像素偏移直接沿用而落到不同行。同一次迁移验证了同一视口的两种内容形态：两列卡片网格与单列树列表（行步长与可见行数各不相同）。滚动条轨道矩形与旧手算值逐像素重合，**但滑块几何是迁移的固有结果、不是等价实现**：新实现按「视口 / 内容」像素比算滑块高度并保底 8 像素，旧实现按「可见条目数 / 总条目数」换算并保底 12 像素，因此相同内容下滑块长度与拖动手感可能与旧版有细微差异。

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
| `UiControlStyle` | 控件**结构色** token：普通/悬停/按下/选中/禁用背景、焦点轮廓、滚动条轨道与滑块；内置 `PARCHMENT` 与 `DARK`，`background(State)` 按「禁用 > 按下 > 悬停 > 选中 > 普通」取色。文本色不在其中 |
| `UiControl` 语义状态 | `enabled` / `visible` / `selected` / `focused` / `pressed` 只影响绘制与命中、不触发重新测量；禁用与不可见清掉按压与焦点；`isFocusable()` 要求 `action != null`，纯标签可命中、有 tooltip，但不作 Tab 停靠点 |
| `UiControlGroup` | 稳定 key → 控件的 `LinkedHashMap`，迭代顺序即绘制与命中层序（后创建者在上层）；`beginUpdate` / `obtain(key)` / `endUpdate` 复用并丢弃未复用项；`controlAt` 取最上层命中、`renderTooltip` 只画该目标的提示；`mousePressed` 命中即激活并捕获按压到释放（**不夺取焦点**）；`collectFocusTargets` 按视觉顺序把可聚焦控件交给 `UiFocusManager`，自身不再持有焦点 |
| `UiScrollView` | 视口矩形（宿主 GUI 坐标）+ 内容高度 + 偏移，偏移恒钳制在 `[0, maxOffset()]`；`push` / `pop` 进出内容坐标，`toContentX` / `toContentY` / `toScreenY` 做换算，`ensureVisible` 最小滚动；滚动条含点轨道跳转与拖动 |
| `UiLinearLayout` | 有界横纵布局：主轴 `FIXED` / `CONTENT` / `REMAIN` 加 min·max 钳制，交叉轴固定/内容/拉满，统一 `spacing` 与 `padding`，`Child.leading` 覆盖单个子项前间距；`REMAIN` **先按各子项的 min 预扣再平分余量**，容器确实放不下时按 min 溢出而不是把子项压到 min 以下；`bounds(int)` 只读缓存、越界返回零矩形，`usedMain()` 供宿主换算内容高度 |
| `UiFocusTarget` | 可聚焦目标适配器：`canFocus()` / `setFocused(boolean)` / `activate()` / `bounds()` / `accessibleName()`。kit 的焦点系统只经它读可聚焦性与边界、写焦点、请求激活，因此原生 `EditBox` 这类非 kit 控件也能按宿主给定的视觉顺序参与 Tab 导航；`setFocused` 只改绘制状态，不重建内容、不重排 |
| `UiFocusManager` | 焦点管理器：按宿主给出的**视觉顺序**登记一组目标（`beginUpdate` / `add` / `endUpdate`，同一目标一次更新内只保留首次位置），唯一决定当前焦点，提供 Tab / Shift+Tab 移动、可配置的 Enter / Space 激活、焦点失效清理与焦点变化通知（`Listener#focusChanged`） |
| `UiLightbox` | 可复用模态查看器**外壳**：遮罩、内容视口、标题与描述、底部控制栏（图集导航与页码、缩放读数、缩放与适应窗口按钮）、控件组与焦点、关闭语义；文案由宿主的 `Labels` 注入（kit 不持有文案键）。**只做外壳与输入分派，不做内容几何** |
| `UiLightbox.Content` | 内容契约：`contentWidth/Height`、`setViewport`、`render`、`zoom` / `zoomBy` / `fit`、`panBy`、`mousePressed` / `mouseReleased`、`zoomPercent`、`hit`、`placeholder`、`invalidateResources`；实现方必须把绘制**严格裁剪在视口内** |
| `UiLightbox.Gallery` | 图集：宿主报 `index` / `total`，并在 `navigate(delta)` 里换内容；外壳只在 `total > 1` 时创建导航控件与页码 |
| `LightboxImage` | 图片描述 record：稳定 `id`、贴图与区域（uv + 原始宽高 + 贴图总尺寸）、可本地化标题与描述；区域必须落在贴图内，非法尺寸在**构造期**就被拒绝；只接受客户端已可用的贴图来源 |
| `UiImageView` | `UiLightbox.Content` 的图片实现：contain 适配（默认不放大）、1.25 有限步进缩放、指针锚点缩放、平移钳制、严格裁剪绘制与缺图占位；尺寸与缩放状态只在它自己这里，图集/文案/按钮都在外壳 |

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

**滚动视口的坐标与裁剪口径**：`UiScrollView.setViewport` 使用宿主 GUI 逻辑坐标，与 `UiControl` 矩形同坐标系；`push(graphics)` 先 `flush`、再按当前 pose 设置视口 scissor，最后 `translate(viewport.x(), viewport.y() - offset)` 进入内容坐标，`pop(graphics)` 先弹回宿主 pose 再恢复上一层裁剪（原版 scissor 交集栈）。内容坐标系的原点是「内容顶部、与视口左边界对齐」；命中用 `toContentX` / `toContentY` 把屏幕坐标换算成内容坐标后再交给 `UiControlGroup`，`toScreenY` 用于反向定位。指针不在视口内时宿主传视口外的占位坐标（`ScenarioSelectionOverlay` 的 `NO_HOVER = -1000`）或直接跳过命中，避免视口外内容被命中。偏移在视口尺寸与内容高度变化后重新钳制；内容不超过一屏时 `maxOffset()` 为 0、滚动条不显示。`SCROLLBAR_WIDTH = 4`，滚动条始终贴视口右边界，溢出时宿主让出这 4 像素。

**布局只产出落位**：`UiLinearLayout` 不绘制、不持有控件，只产出逻辑矩形：`bounds(int)` 只读缓存、越界返回零矩形，`usedMain()` 供宿主换算内容高度。预期用法是「纵向容器里每行一个横向容器」——一层嵌套即可表达标签列 + 控件列的表格行，子布局各自 `setBounds` 到父布局给出的矩形。一行里宽度规则不同时用 per-child 的 `withLeading` 给间距，不再手算绝对坐标；`ScenarioParamsOverlay` 的三套布局分别是行槽（每行固定 20 高）、行内横排（逐行重配尺寸规则）与底部按钮行。

**稳定 key 复用与「稳定帧不重建」**：`UiControlGroup` 以稳定 key 持有控件，只在内容变化时走一遍 `beginUpdate` / `obtain(key)` / `endUpdate`：同一 key 永远返回同一个 `UiControl`，本次未复用的 key 连同落在其上的按压与焦点引用一起丢弃；滚动、悬停等逐帧操作只改状态。`UiControl.configure` 在标签文案未变时不重置滚动计时，因此「浮层按秒重配控件」不会让超宽文本每秒跳回起点；`setBounds` 在矩形未变时直接返回。`ScenarioSelectionOverlay` 据此把整表行控件只建一次（key 为场景下标），翻页与滚轮只改滚动偏移，行内容仍只在「目录 revision / 秒 / dirty」变化时重配。

**命中与 tooltip 的唯一来源**：组内命中只走 `controlAt`（自上层向下取第一个命中者），`targetAt` / `renderTooltip` 派生同一目标，同一位置只画一个 tooltip。组与组之间的区域划分由宿主负责：场景选择浮层在视口内取行组目标、视口外才交给底部箭头组，二者互斥；滚动条命中排在行命中之前，滑块压在行右侧也不会穿透。

**焦点与键盘导航**：Tab / Shift+Tab 与可配置的 Enter / Space 激活已从控件组移到 `UiFocusManager`（见「焦点所有权与视觉顺序」）；其余按键一律不消费——ESC、上下键等语义仍归模态宿主。进入序列的条件仍是 `isFocusable()`（`isHittable()` 且 `action != null`）：禁用与不可见控件既不可命中也不进序列，`setEnabled(false)` / `setVisible(false)` 会立刻清掉 `pressed` 与 `focused`；纯标签仍可命中、仍显示 tooltip，只是不再占用 Tab 停靠点。

**按压捕获**：`mousePressed` 命中即激活并记录按压目标，`mouseReleased` 只结束捕获、不在释放时激活（拖动结束不应触发点击）；`isPressCaptured(x, y)` 让宿主区分这次拖动归控件还是归下层内容。

**与原生 `EditBox` 的接驳边界**：`ScenarioParamsOverlay` 中幸运值 `EditBox` 仍是唯一原生输入（支持拖选），命中优先级高于控件组；它经宿主侧适配器（`LuckFocusTarget`）接进焦点序列——`setFocused` 直接转给原版控件，`activate()` 返回 `false`（Enter / Space 的编辑语义属于原版），`bounds()` 取输入框矩形供焦点调试与坐标换算使用。文字输入、剪贴板与输入法都由原版控件处理，kit 不接管；控件组只自绘标签与按钮，标签 `action == null`，因此点击只读文本没有副作用。

**语义状态与样式分工**：状态解析优先级为「禁用 > 按下 > 悬停 > 选中 > 普通」，背景色从 `UiControlStyle.background(State)` 取，焦点轮廓画在矩形内侧（不侵入相邻控件、也不被控件自身裁剪吃掉）。结构色（背景、焦点轮廓、滚动条轨道/滑块）归 `UiControlStyle`，文本色仍由调用方从 `UiTextPalette` 传入，禁用态只降不透明度、保留调用方给定的色相——与「文本配色约束」的分工一致。`PARCHMENT` 的普通/悬停背景与旧硬编码值逐像素相同，按下/选中/禁用/焦点是新增态，旧界面不会触发。

**口径收窄**：前述「kit 不负责输入分发、焦点、浮层或状态持久化」现在收窄为：kit 仍不决定模态优先级、不持有业务状态；焦点与键盘导航由 `UiFocusManager` 统一提供（`UiControlGroup` 不再自行管理焦点，只把可聚焦控件交给它）；输入先给谁（原生输入框、滚动条、控件组、文档命中）由宿主维护，滚轮是否消费也仍由宿主按 `UiScrollView.contains(x, y)` 判定。

**焦点所有权与「鼠标点击不夺取焦点」**：焦点由 `UiFocusManager` 唯一持有，改变焦点只有两条路径——键盘导航（`keyPressed` 处理 Tab / Shift+Tab，用 `Math.floorMod` 环绕）与宿主显式 `focusOn`。`UiControlGroup.mousePressed` 命中后只激活动作并记录按压捕获、**不夺取焦点**，所以焦点轮廓只在键盘使用时出现，鼠标用户的画面与迁移前一致。`UiControl` 通过实现 `UiFocusTarget` 参与序列（`canFocus()` 即 `isFocusable()`），控件组不再持有任何焦点状态。唯一的例外是原生输入框：点击它时焦点由原版控件接管，宿主再把这件事同步给焦点管理器。

**视觉顺序与原生输入框夹层**：Tab 顺序不由控件组内部顺序决定——宿主按**视觉顺序**把目标登记进 `UiFocusManager`（`beginUpdate` / `add` / `endUpdate`，一次更新内重复登记同一目标只保留首次位置），因此原生 `EditBox` 的适配器能夹在两段控件之间：参数浮层的顺序是「工具行 ◀/▶ → 幸运值输入框 → 抽样格 → 附魔 −/+ → 取消 → 确认」，验证页的顺序是「控件组的可聚焦控件 → 原生输入框」。`UiControlGroup.collectFocusTargets(out)` 只是「按绘制层序追加当前可聚焦控件」的便捷入口，插在序列的哪个位置仍由宿主决定——这正是焦点从控件组拆出来的原因。

**Enter / Space 的可配置仲裁**：`setEnterActivates` / `setSpaceActivates` 由宿主按模态契约设置。两个浮层都关掉 Enter（`setEnterActivates(false)`）以保留「Enter 选择/确认并关闭」的既有语义，只让 Space 激活焦点控件；焦点管理器不消费的按键（ESC、上下键、搜索与编辑键）原样交回宿主或原生输入控件。

**焦点失效清理与焦点变化通知**：目标可能因内容更新、禁用、隐藏或控件被丢弃而失效。`endUpdate()` 移除本帧未登记的目标、并在焦点目标失效时清除焦点；`refresh()` 在每个交互入口再做一次廉价校验（不在列表里或 `canFocus()` 为假即清除）。焦点变化经 `Listener#focusChanged(previous, current)` 通知宿主，且焦点目标未变时不会重复通知——场景选择浮层与验证页用它实现「滚动到焦点」；参数浮层是分页窗口模型（目标永远在窗口内），不需要该通知。

**焦点进视口**：场景选择浮层把「焦点行整行可见」挂在这条通知上，用 `UiScrollView.ensureVisible` 实现，视口外的行由 `setVisible(false)` 出列、因此不会成为 Tab 停靠点。参数浮层是**分页窗口**模型：只创建并登记当前窗口那一屏的行，所以「焦点滚出视口」不会发生，Tab 永远只在窗口内环绕；窗口的移动改由 `PageUp` / `PageDown` 负责，焦点策略与滚轮翻页一致（不显式清除，目标被移出窗口时由 `endUpdate` 丢弃并自然清空），窗口之外的附魔行由此获得键盘通路。

**accessibleName 的用途与边界**：`UiFocusTarget.accessibleName()` 返回可读名称：`UiControl` 优先返回调用方显式 `setAccessibleName` 的名称，其次标签，标签为空（图标按钮）时回退到首行提示；原生输入框适配器返回输入框的提示名。契约上供「焦点调试与旁白」使用；**当前落地只有调试叠加层消费它**（在控件矩形上画名称、给当前焦点目标套强调边框并输出名称）。kit 不产生任何系统级旁白或语音输出，也没有屏幕阅读器集成——`accessible` 只是命名约定，不要当作完整的无障碍支持承诺。

**验证页的阶段 A/B/C 演示区**：`UiKitDebugScreen` 现在左右分栏。右列仍是既有的 200 行 `UiDocument` 演示（25 组 ×（1 根 + 3 子 + 2×2 孙）= 200 行、每 10 行 3 个图标 = 60 个图标）；左列是阶段 A/B/C 演示区——固定/内容/剩余与带 leading 的三种行、禁用控件、原生输入框适配器、`PARCHMENT` / `DARK` 的两组状态样例（普通/悬停/按下/选中/禁用/焦点）以及一段把内容撑高的纯标签列表，用来验证滚动偏移与滚动条。`F` 切换调试叠加层（默认关闭，关闭时不绘制任何额外内容）：打开后画面板与视口矩形、每个布局子项矩形、控件矩形（按启用/禁用着色）与 `accessibleName`、当前焦点目标的强调边框，并输出滚动几何与激活计数。底部读数每 20 tick 刷新，含焦点下标/目标数、滚动偏移/最大偏移/内容高与控件数。演示区与验证页新增的本地化键同时写入 `en_us.json` 与 `zh_cn.json`。

**灯箱的分工：外壳不做内容几何**：`UiLightbox` 只负责遮罩、内容视口、标题与描述、底部控制栏、控件组与焦点、输入分派；内容的尺寸、缩放、平移与裁剪全在 `Content` 实现里。图片场景用 `UiImageView`（由 `LightboxImage` 描述一张图），条件树这类自绘内容由宿主适配器实现——因此同一个外壳既能看图，也能放大整棵树。`Labels` 与 `Gallery` 都由宿主注入，kit 不持有任何文案 key；外壳默认用暗底样式（`UiControlStyle.DARK`）。

**为什么不依赖 `OverlayLayer`**：kit 里没有任何对浮层层的引用，宿主用 `LightboxOverlay` 这个模态适配器把外壳接进 `OverlayLayer`——渲染前写入 `layer.width/height`、转发六类输入、在 `Overlay.closed()` 里调外壳的 `onClosed()` 释放按压与焦点。依赖方向因此保持「宿主层 → kit」单向：外壳可以独立复用与验证，也不会被浮层契约牵着走；宿主打开适配器即获得遮罩、独占输入与关闭语义。

**视口与裁剪口径**：外壳按宿主可用区留 12 像素外边距，顶部让给标题与描述，底部固定 22 像素控制栏（内容视口在它上方，控制栏不会被内容盖住），剩下的矩形通过 `Content.setViewport` 写入内容；内容必须把绘制严格裁剪在该视口内（`UiImageView` 用 `UiTransform.enableScissor`，条件树沿用文档自己的裁剪）。坐标一律是宿主 GUI 逻辑坐标，缩放读数画在按钮组左侧以免压住按钮。

**fit 与 resize 的区别**：外壳只在「首次布局」与「换内容（`setContent`）」时调用一次 `Content.fit()`（`needFit` 标记）——内容尺寸尚未就绪时这次 fit 会推迟到能报告非零尺寸的那一帧，避免空文档被误判为「放得下」而选中最大档位；resize 只重新 `setViewport`，由内容自己重新钳制平移并**保留用户缩放**。`UiImageView.fit` 是 contain：按视口与图片尺寸取较小比例，默认不放大（`allowUpscale` 为 false 时上限 1.0），再钳进 [0.05, 8.0] 并把偏移归零居中。条件树适配器的 `fit` 不同：它从最大档位往小试，取第一个能把整幅内容放进可见区的档位，都不行就用最小档位再居中。

**缩放：有限步进与上下限**：滚轮与按钮走同一个「一档」步进（`UiImageView.STEP = 1.25`），比例钳制在 [0.05, 8.0]；滚轮以指针为锚点（缩放前后指针下的同一内容点保持不动），按钮则以视口中心为锚点（`zoomBy`）。到顶 / 到底或结果无变化时返回 `false`，不产生空消费；指针不在内容视口内时外壳不做缩放（输入仍由模态层吞掉，不会漏到下层）。

**平移钳制**：`UiImageView` 的偏移是「图片中心相对视口中心的屏幕像素偏移」——任一轴目标尺寸不超过视口时该轴偏移锁死为 0（居中），超过时钳制在 ±(目标尺寸 − 视口尺寸)/2，因此图片永远不会被拖出视口留下空白；只有从视口内按下的拖动才平移。条件树适配器按内容坐标钳制（可见内容范围 X = [−pan, vw/s − pan]，内容占 [0, cw]），灯箱模式下列出整幅内容、只有超出轴才允许平移；页内框不走这套钳制，保持自由平移。

**缺图占位与资源重载**：`UiImageView` 只在构造、内容变更与 `invalidateResources()` 时用资源管理器判定一次贴图可用性（绘制路径不做 IO）；不可用时 `placeholder()` 返回占位文案，外壳把它居中画在视口里。资源重载时外壳调 `content.invalidateResources()` 并重新求 fit，`UiImageView` 只在「不可用 → 可用」时重新 fit（尺寸这时才真正可用），其余情况保留用户缩放；条件树不依赖贴图，沿用默认 no-op。

**图集 API**：`Gallery` 由宿主实现（报当前下标与总数、在 `navigate(delta)` 里换内容），外壳只请求切换并刷新布局。导航按钮、页码与左右方向键都只在 `total > 1` 时出现；宿主可在切图时一并换掉标题与描述（`setText`）。`setContent` 会把 fit 标记置真，因此每张图进入时都是「适应窗口」的初始状态。

**焦点、激活与关闭语义**：焦点由 `UiFocusManager` 按**视觉顺序**登记——关闭（右上角）→ 上一张 / 下一张（控制栏左侧）→ 缩小 → 放大 → 适应窗口；外壳里 Enter 没有其它语义，因此与 Space 一起用于激活焦点控件。ESC 与 × 都请求关闭（真正的关闭动作是宿主传入的 `onClose`）；点遮罩**默认不关闭**（`setMaskClickCloses` 默认 false，避免拖图时误触退出）。外壳的 `onClosed()` 由模态适配器在 `Overlay.closed()` 里调用，释放按压捕获与焦点。

**灯箱的 tooltip 唯一来源**：同一位置只出一个 tooltip——先问控件组（`controls.targetAt`），命中就只画控件的提示；没有控件命中才问 `content.hit`（例如条件树里的行），为空则不画。内容实现因此**不要自己画 tooltip**，只把命中契约暴露给外壳。

**验证页的阶段 D 演示**：`UiKitDebugScreen` 的演示区新增一个「灯箱」按钮，打开的是**复用生产模态路径**的图片灯箱——页面自己持有一个 `OverlayLayer` + `LightboxOverlay`，内容视图是 `UiImageView`，页内图集放两张图（256×256 的陶轮界面整图与 152×76 的目录条目整图，用来对照「大图 contain 后仍可放大」与「小图 fit 保持 100%」）。灯箱按**未缩放的真实屏幕坐标**画在所有内容之上并独占输入，ESC 由灯箱消费（关灯箱而不是关调试页）；页面在打开时收掉下层焦点与正在进行的拖动，模态期间不再画自己的 tooltip。底部读数在灯箱打开时追加「图序号 / 总数 | 缩放百分比」。通用文案键 `screen.unsuspiciousblock.lightbox.*`（8 个：close / zoom_in / zoom_out / fit / previous / next / position / missing）与调试页新增的 7 个键同时写入 `en_us.json` 与 `zh_cn.json`。

**机制与公开契约的分工**：本节的职责是**机制与实现口径**的权威——排版缓存、裁剪边界、命中与 tooltip 的唯一来源、焦点失效清理、fit 与 resize 的区别、平移钳制等「为什么这样做、实际怎么算」。面向第三方宿主的**公开入口清单、依赖边界检查、接入步骤、行为约定与兼容策略**另见 [UI kit 公开 API 与兼容策略](ui-kit-api.md)：公开边界以 `client/ui/kit/package-info.java` 为准，依赖方向由 `scripts/check-ui-kit-boundaries.ps1` 检查，开发与最小示例已迁到 `client/ui/sample/`。两处描述冲突时，机制细节以本节为准，接口与兼容承诺以那篇为准。

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

- `ScenarioSelectionOverlay`：最多七行可见，使用服务端场景顺序；行由 `UiScrollView` + `UiControlGroup` 承载——整表行控件按场景下标稳定复用、只建一次，翻页与滚轮只改滚动偏移，行内容仍只在「目录 revision / 秒 / dirty」变化时重配；内容溢出才显示滚动条（行宽相应让出 4 像素），底部箭头到边界时进入禁用态。键盘焦点由 `UiFocusManager` 按「场景行 → ◀ → ▶」登记：Tab/Shift+Tab 在其间移动、**焦点行自动滚进视口**、Space 激活焦点控件，而 Enter 保留「选择并关闭」的既有契约；当前场景行常亮语义选中态（原有加粗保留）。滚轮或分组箭头浏览，上下键改变当前场景，点击行后关闭，ESC/Enter/外部点击关闭。它只吃「表 + 签发清单 + 当前场景 + 参数 + 一个回调」，因此**场景页的「场景」按钮与网格页头部的「切换场景」按钮共用同一实现**：前者把选择映射到页码（会保存框内视图），后者只切换选择（网格数字随之更新，不跳页）。网格页不再自绘下拉。
- `ScenarioParamsOverlay`：独立草稿、确认/取消；控件统一由 `UiControlGroup` 按稳定 key 承载，落位由三套 `UiLinearLayout`（行槽 / 行内横排 / 底部按钮行）产出，不再手算坐标；工具、抽样、附魔等级由签发清单约束，幸运值是唯一原生 `EditBox`（支持拖选，命中优先于控件组），由宿主侧适配器接进焦点序列、文字输入仍归原版控件。Tab 顺序是「工具行 ◀/▶ → 幸运值输入框 → 抽样格 → 附魔 −/+ → 取消 → 确认」；Enter 保持「确认」契约、Space 激活焦点控件，窗口外的附魔行用 `PageUp` / `PageDown` 翻窗口到达（Tab 只在窗口内环绕），当前抽样格常亮语义选中态（原有加粗保留）；参数多时按行滚动。确认再次使用最新目录校验，**不自动计算**；ESC 取消，点外不关闭。
- `ScenarioExpandedOverlay`：**放大页已改为灯箱模式**——它不再是浮层，而是 `UiLightbox.Content` 适配器：内部持有一个独立的 `ScenarioFrameView`，只把视口 / 绘制 / 缩放 / 平移 / 命中按契约暴露出来，遮罩、控制栏、适应窗口、焦点与关闭语义全部交给 `UiLightbox` + `LightboxOverlay`。构造时调用 `frame.hideCornerControls()` 关掉框自带的档位与复位角控件（灯箱自己提供缩放与适应窗口，避免两套控件与两处命中）；打开即适应窗口（首次布局触发一次 `fit()`），平移按灯箱规则钳制（内容小于视口时该轴居中锁定，超出轴才可拖动），点遮罩不关闭。视图是独立实例，**关闭灯箱不会改动页内框的平移 / 缩放**；`ScenarioDetailPanel` 用 `LightboxOverlay` 打开，并把 `expandButton` 作为 opener 传入（仅当它当前持有焦点时才登记为返回焦点）。为此 `ScenarioFrameView` 增加了包内只读访问器 `transform()` / `contentViewport()` / `contentWidth()` / `contentHeight()`，以及只关闭绘制与命中的 `hideCornerControls()`（内部 `controlsVisible` 开关，没有重新打开的 setter，页内路径保持默认显示）。

**网格页头部**（`ScenarioPanel`）只剩读数行与「切换场景 / 计算」两个动作，文字超宽时悬停滚动。网格页调整参数需到场景页打开参数浮层。

**测量只能由显式计算按钮发起**。旧网格头部的防抖自动请求已去除，应用推荐只改变选择。未新增网络包；仍复用既有请求/结果协议。

贴图由 `scripts/drawer/generate_scenario_ui.py` 生成：`scenario_frame.png` 是 24×24 九宫格（8px 四角），`UiNineSlice` 用九个四边形拉伸边和中心；`scenario_status.png` 为 48×12 四格，空心点/沙漏/勾/叉对应未计算/计算中/已缓存/失败。角标同时使用形状区分状态，详细状态和失败原因放 tooltip。

**浮层的焦点交接**：`OverlayLayer.open(overlay, opener)` 只在 opener **当前确实持有焦点**（键盘到达，见 `UiFocusTarget.isFocused()`）时把它登记为返回焦点——鼠标点击不夺取焦点，所以鼠标打开的浮层不会被登记、关闭后也不会留下轮廓；登记后打开时先把该目标的焦点清掉（避免下层残留轮廓），`close()` 时若它仍 `canFocus()` 就把焦点还回去；`ScenarioDetailPanel` 的三个入口（场景选择、放大框、参数面板）都把按下的 `UiControl` 作为 opener 传入。替换已打开的浮层（切模态）时，未显式传 opener 的一方**继承上一层记录的返回焦点**，因此模态链全部关闭后仍能回到最初的入口。浮层被关闭或被替换时都会收到一次 `Overlay.closed()` 回调，用于释放输入捕获与引用；该回调当前是默认空实现，三个浮层都还没有覆写。**恢复的焦点需要回收**：页面侧目前没有焦点管理器（只有两个浮层与验证页有），所以 `OverlayLayer` 记下最后一次还给 opener 的目标，宿主在「未被子层消费的鼠标操作」里调用 `clearRestoredFocus()` 把它收掉（`ArchaeologyJournalScreen` 的 `mouseClicked` 就是唯一调用点），再次打开浮层也会先清除；否则该控件会一直带着焦点轮廓。注意它只清「已还给 opener 的焦点」，不动 `returnFocus`，也不碰任何 `UiFocusManager` 的内部焦点。

## 6. 面板状态与偏好持久化

新面板实现 `LayoutAware.applyLayout(BookLayout)`、`UiStateful.saveUiState/loadUiState(CompoundTag)`，**在创建处向 `UiPanelRegistry` 注册一次即可**。屏幕遍历注册表完成布局与状态恢复；旧面板不迁移接口，`RightPageContainer.savePages/loadPages` 只补齐各 tab 页号，修复 resize 只保留当前 tab 的缺口。

`ScenarioDetailPanel` 按 `tableId#scenarioKey` 保存框档位/平移，最多保留 512 个视图，切场景或表时先捕获旧视图。resize 快照与关闭笔记时都包含非当前 tab 的新面板状态；状态存在 `JournalUiPreferencesStore` 的 `panels` NBT 子树。窗口尺寸变化会关闭临时浮层、丢弃未确认草稿，保留已确认参数和页内视图。

参数仍在每张表内跨场景共用同一套，工具清单由该表签发。`SimulationPreferenceStore` 现在通过 `JournalUiPreferencesStore` 的 `simulationPreferences` NBT 子树读写选择，和 UI 偏好共用按存档、按玩家隔离的 `journal_ui_preferences.dat`。**旧的全局 `config/unsuspiciousblock-simulation.properties` 保留但不再读取或自动导入**，避免把一个存档/玩家的选择带到其它存档/玩家；首次使用新存储时由当前签发清单初始化合法参数。切换连接清空本地加载缓存，断线刷盘仍使用已加载的旧世界路径。

> 该旧存储的 SPI 链（`IClientSimulationPreference` → `FileSimulationPreference` → 两端实现）已无调用点，属待清理的死代码，**不要作为新代码的参考模式**（见 [平台抽象](../foundation/platform-spi.md)）。
