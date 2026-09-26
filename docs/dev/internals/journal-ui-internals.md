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
| `UiControlStyle` | 控件**结构色** token：普通/悬停/按下/选中/禁用背景、焦点轮廓、滚动条轨道与滑块；内置 `PARCHMENT` 与 `DARK`，`background(State)` 按「禁用 > 按下 > 悬停 > 选中 > 普通」取色。文本色不在其中 |
| `UiControl` 语义状态 | `enabled` / `visible` / `selected` / `focused` / `pressed` 只影响绘制与命中、不触发重新测量；禁用与不可见清掉按压与焦点；`isFocusable()` 要求 `action != null`，纯标签可命中、有 tooltip，但不作 Tab 停靠点 |
| `UiControlGroup` | 稳定 key → 控件的 `LinkedHashMap`，迭代顺序即绘制与命中层序（后创建者在上层）；`beginUpdate` / `obtain(key)` / `endUpdate` 复用并丢弃未复用项；`controlAt` 取最上层命中、`renderTooltip` 只画该目标的提示；`mousePressed` 命中即激活并捕获按压到释放；`keyPressed` 处理 Tab/Shift+Tab 与 Enter/Space |
| `UiScrollView` | 视口矩形（宿主 GUI 坐标）+ 内容高度 + 偏移，偏移恒钳制在 `[0, maxOffset()]`；`push` / `pop` 进出内容坐标，`toContentX` / `toContentY` / `toScreenY` 做换算，`ensureVisible` 最小滚动；滚动条含点轨道跳转与拖动 |
| `UiLinearLayout` | 有界横纵布局：主轴 `FIXED` / `CONTENT` / `REMAIN` 加 min·max 钳制，交叉轴固定/内容/拉满，统一 `spacing` 与 `padding`，`Child.leading` 覆盖单个子项前间距；`REMAIN` **先按各子项的 min 预扣再平分余量**，容器确实放不下时按 min 溢出而不是把子项压到 min 以下；`bounds(int)` 只读缓存、越界返回零矩形，`usedMain()` 供宿主换算内容高度 |

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

**焦点与键盘导航**：`keyPressed` 只处理 Tab / Shift+Tab（`moveFocus(±1)`，用 `Math.floorMod` 环绕）与 Enter / Space（激活焦点控件），其余按键一律不消费——ESC、上下键等语义仍归模态宿主。进入序列的条件是 `isFocusable()`（`isHittable()` 且 `action != null`）：禁用与不可见控件既不可命中也不进序列，`setEnabled(false)` / `setVisible(false)` 会立刻清掉 `pressed` 与 `focused`；纯标签仍可命中、仍显示 tooltip，只是不再占用 Tab 停靠点。焦点或按压目标可能因内容更新、禁用、隐藏而失效，每个交互入口先经 `refreshInteraction()` 做一次廉价校验并清理。`setKeyboardNavigation(false)` 供宿主在原生输入框持焦期间关掉导航。

**按压捕获**：`mousePressed` 命中即激活并记录按压目标，`mouseReleased` 只结束捕获、不在释放时激活（拖动结束不应触发点击）；`isPressCaptured(x, y)` 让宿主区分这次拖动归控件还是归下层内容。

**与原生 `EditBox` 的接驳边界**：`ScenarioParamsOverlay` 中幸运值 `EditBox` 仍是唯一原生输入（支持拖选），其命中优先级高于控件组，聚焦期间关闭控件组键盘导航，字符输入走 `charTyped`，可见性随所在行是否落在滚动窗口内切换。控件组只自绘标签与按钮，标签 `action == null`，因此点击只读文本没有副作用。

**语义状态与样式分工**：状态解析优先级为「禁用 > 按下 > 悬停 > 选中 > 普通」，背景色从 `UiControlStyle.background(State)` 取，焦点轮廓画在矩形内侧（不侵入相邻控件、也不被控件自身裁剪吃掉）。结构色（背景、焦点轮廓、滚动条轨道/滑块）归 `UiControlStyle`，文本色仍由调用方从 `UiTextPalette` 传入，禁用态只降不透明度、保留调用方给定的色相——与「文本配色约束」的分工一致。`PARCHMENT` 的普通/悬停背景与旧硬编码值逐像素相同，按下/选中/禁用/焦点是新增态，旧界面不会触发。

**口径收窄**：前述「kit 不负责输入分发、焦点、浮层或状态持久化」现在收窄为：kit 仍不决定模态优先级、不持有业务状态，只提供组内的焦点与键盘导航；输入先给谁（原生输入框、滚动条、控件组、文档命中）由宿主维护，滚轮是否消费也仍由宿主按 `UiScrollView.contains(x, y)` 判定。

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

- `ScenarioSelectionOverlay`：最多七行可见，使用服务端场景顺序；行由 `UiScrollView` + `UiControlGroup` 承载——整表行控件按场景下标稳定复用、只建一次，翻页与滚轮只改滚动偏移，行内容仍只在「目录 revision / 秒 / dirty」变化时重配；内容溢出才显示滚动条（行宽相应让出 4 像素），底部箭头到边界时进入禁用态。滚轮或分组箭头浏览，上下键改变当前场景，Tab/Shift+Tab 在行/箭头控件间移焦、Space 激活焦点控件，点击行后关闭，ESC/Enter/外部点击关闭。它只吃「表 + 签发清单 + 当前场景 + 参数 + 一个回调」，因此**场景页的「场景」按钮与网格页头部的「切换场景」按钮共用同一实现**：前者把选择映射到页码（会保存框内视图），后者只切换选择（网格数字随之更新，不跳页）。网格页不再自绘下拉。
- `ScenarioParamsOverlay`：独立草稿、确认/取消；控件统一由 `UiControlGroup` 按稳定 key 承载，落位由三套 `UiLinearLayout`（行槽 / 行内横排 / 底部按钮行）产出，不再手算坐标；工具、抽样、附魔等级由签发清单约束，幸运值是唯一原生 `EditBox`（支持拖选，命中优先于控件组），它持焦期间控件组的 Tab/Space 导航关闭；参数多时按行滚动。确认再次使用最新目录校验，**不自动计算**；ESC 取消，点外不关闭。
- `ScenarioExpandedOverlay`：居中遮罩窗口，复制页内视图到独立 `ScenarioFrameView`，复用相同交互；关闭时丢弃窗口临时平移/缩放，因此页内视图保持打开前状态。关闭按钮与框角控件均置于物品之上。

**网格页头部**（`ScenarioPanel`）只剩读数行与「切换场景 / 计算」两个动作，文字超宽时悬停滚动。网格页调整参数需到场景页打开参数浮层。

**测量只能由显式计算按钮发起**。旧网格头部的防抖自动请求已去除，应用推荐只改变选择。未新增网络包；仍复用既有请求/结果协议。

贴图由 `scripts/drawer/generate_scenario_ui.py` 生成：`scenario_frame.png` 是 24×24 九宫格（8px 四角），`UiNineSlice` 用九个四边形拉伸边和中心；`scenario_status.png` 为 48×12 四格，空心点/沙漏/勾/叉对应未计算/计算中/已缓存/失败。角标同时使用形状区分状态，详细状态和失败原因放 tooltip。

## 6. 面板状态与偏好持久化

新面板实现 `LayoutAware.applyLayout(BookLayout)`、`UiStateful.saveUiState/loadUiState(CompoundTag)`，**在创建处向 `UiPanelRegistry` 注册一次即可**。屏幕遍历注册表完成布局与状态恢复；旧面板不迁移接口，`RightPageContainer.savePages/loadPages` 只补齐各 tab 页号，修复 resize 只保留当前 tab 的缺口。

`ScenarioDetailPanel` 按 `tableId#scenarioKey` 保存框档位/平移，最多保留 512 个视图，切场景或表时先捕获旧视图。resize 快照与关闭笔记时都包含非当前 tab 的新面板状态；状态存在 `JournalUiPreferencesStore` 的 `panels` NBT 子树。窗口尺寸变化会关闭临时浮层、丢弃未确认草稿，保留已确认参数和页内视图。

参数仍在每张表内跨场景共用同一套，工具清单由该表签发。`SimulationPreferenceStore` 现在通过 `JournalUiPreferencesStore` 的 `simulationPreferences` NBT 子树读写选择，和 UI 偏好共用按存档、按玩家隔离的 `journal_ui_preferences.dat`。**旧的全局 `config/unsuspiciousblock-simulation.properties` 保留但不再读取或自动导入**，避免把一个存档/玩家的选择带到其它存档/玩家；首次使用新存储时由当前签发清单初始化合法参数。切换连接清空本地加载缓存，断线刷盘仍使用已加载的旧世界路径。

> 该旧存储的 SPI 链（`IClientSimulationPreference` → `FileSimulationPreference` → 两端实现）已无调用点，属待清理的死代码，**不要作为新代码的参考模式**（见 [平台抽象](../foundation/platform-spi.md)）。
