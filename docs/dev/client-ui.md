# 客户端与 GUI

本文档描述 `client/` 包的架构：客户端初始化、状态管理、考古笔记 GUI 层级、HUD、渲染、铁砧/砂轮成本分解、附魔揭示与 Toast 通知。

> **本篇职责边界**：本篇是**客户端通用基础设施**（状态管理框架、按键、Toast、HUD/渲染接入）与**考古笔记 GUI** 的权威文档。各玩法子系统的客户端表现，其机制权威在对应子系统文档（猫 HUD 数据流见 [猫族关系系统](cat-favor.md)、淘盘动画与水声见 [淘洗系统](panning.md)、附魔揭示服务端机制见 [附魔系统](enchantment.md)）；本篇只保留客户端侧的接入方式与渲染细节。

## 1. 职责概述

`client/` 包含所有客户端专属代码（服务端严禁引用）：

- **考古笔记 GUI**：完整的目录/日志/进度界面，分层组织。
- **客户端状态**：缓存服务端推送的目录、进度、日志，处理增量同步与解锁通知。
- **HUD**：猫之恩惠快捷栏 HUD。
- **渲染**：实体渲染器、方块实体渲染器、范围扫描高亮、保护罩。
- **信息面板**：铁砧成本分解、砂轮预览、附魔台完整候选（猫之瞳驱动）。
- **按键绑定**：扫描等级切换、笔记打开、威慑/轻步切换。
- **Toast 通知**：表/物品解锁、100% 完成奖励。

## 2. 客户端初始化

两个平台客户端入口职责高度对称（见 [架构总览](architecture-overview.md) 第 4.2 节），都完成按键注册、渲染器/模型层注册、Screen 注册、S2C 接收器注册、Tooltip 组件注册、HUD/世界渲染注册、客户端 tick 驱动、断连重置。

关键清单模式（与注册架构一致，平台客户端自动遍历）：

| 清单 | 用途 |
|---|---|
| `ModEntityRenderers.REGISTRY_MANIFEST` | 实体渲染器（4 个灵体/宠物） |
| `ModModelLayers` | 模型层 LayerDefinition |
| `ModPayloads.Client.S2C_PAYLOADS` | S2C 接收器 |

## 3. 客户端状态管理

`client/state/` 与 `client/ui/support/` 持有各类客户端状态：

| 状态类 | 职责 |
|---|---|
| [`ArchaeologyJournalClientState`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/ArchaeologyJournalClientState.java) | 考古笔记核心状态（目录/进度/日志缓存 + 解锁通知 + UI 偏好） |
| `LootTableManagementClientState` | 服务端权威战利品表索引、权限和多语言名称快照 |
| `ClientLootTableLanguageStore` | 游戏资源来源索引、服务端名称内存快照和动态语言补充 |
| `SuspiciousReaderClientState` | 可疑解析仪按键与扫描等级切换 |
| `ReaderScanHudState` | 整批扫描汇总、地下目标聚焦与世界描边的统一生命周期 |
| `HandOfCatClientState` | 猫之手客户端状态（缓存的 favor/lives） |
| `CatHandClientState` | 猫之手按键处理状态 |
| `EnchantmentRevealClientState` | 附魔揭示客户端状态（完整候选列表） |
| `JournalHoveredItemProvider` | 可选物品查看器（JEI）向考古笔记快捷键提供悬停物品的接口；空物品栈表示输入被查看器占用 |
| `ArchaeologyJournalKeyHandler` | 考古笔记按键处理 |

### 3.1 ArchaeologyJournalClientState 核心

这是最复杂的客户端状态类，管理服务端推送的所有数据并驱动 UI：

**数据缓存**：
- `serverCatalog` / `catalogStructure`：服务端目录快照
- `journalState`：玩家进度（`ArchaeologyJournalState`）
- `cachedCatalogHash`：目录哈希，用于按需同步比对

**同步处理**：
- `receiveCatalogHash`：收到哈希后与本地比对，不一致请求全量目录（`RequestCatalogPayload`）。
- `receiveState`（全量）：首次同步不弹 Toast（避免重进世界重复通知），后续检测新解锁。
- `receiveStateIncremental`（增量）：**间隙检测**--`incomingRevision > lastNotifiedRevision + 1` 时说明中间有增量包丢失，请求全量重同步。仅对变更表做 Diff。
- `requestFullStateWithCooldown`：限流（2s）防请求风暴。

**解锁通知**：
- `tableUnlockNotifier` / `itemUnlockNotifier`：回调接口，由平台客户端注入（桥接到 `JournalUnlockToast`），解耦状态层与 UI 层。
- `detectAndNotifyUnlocks`：比较旧/新状态，检测新解锁的表和物品，仅对追踪目录中的表触发（避免未追踪表弹通知）。

**UI 偏好持久化**：
- 跨打开/关闭保留：排序方式、搜索文本、隐藏未解锁开关、收藏集合、日志分组模式、右侧标签页等。
- 通过 `JournalUiPreferencesStore` 落盘到本地文件。
- `persistenceSuppress`：加载持久化文件时抑制 `markDirty`，避免加载即触发回写。
- `resetOnDisconnect`：断线时先落盘 UI 偏好，再重置同步状态。

## 4. 考古笔记 GUI 架构

`client/ui/` 子包按职责分层：

```
ui/
├── ArchaeologyJournalUi          UI 注册入口（注册 opener）
├── JournalBookBackground         书本背景渲染（ui 根目录）
├── PotteryPreviewRenderer        陶轮预览渲染（ui 根目录）
├── entry/      目录条目（ArchaeologyJournalEntry / ItemEntryLike / ...）
├── layout/     布局（JournalLayout / JournalViewport）
├── panel/      面板（CatalogPanel / LogPanel / WelcomeStatsPanel / DetailOverlayPanel / ItemGridPanel / ...）
├── screen/     屏幕（ArchaeologyJournalScreen / LootTableManagementScreen / SpecimenBoxScreen / ...）
├── support/    支持类（ClientState / CatalogSorter / JournalSearchQuery / JournalItemDetailAppender / ...）
├── toast/      Toast 通知（JournalUnlockToast / CatBondToast）
├── widget/     组件（IconButton / BookSideTabButton / ExternalLinkButton / BookmarkToggleButton / CopyCoordinateButton / PotteryWheelModeButton / ShadowlessEditBox / ...）
└── tooltip/    tooltip（ClientSpecimenBoxTooltip）
```

### 4.1 分层职责

- **screen/**：顶层 `Screen` 实现。`ArchaeologyJournalScreen` 是主屏幕，`LootTableManagementScreen` 是与手册 TAB 分离的追踪管理页；`JournalViewModel` 持有视图状态，`CatalogToolbar` / `LogToolbar` 是工具栏。`SpecimenBoxScreen` / `PotteryWheelScreen` 是容器屏幕。`JournalLogNoteEditScreen` 与 `JournalLogRetentionScreen` 分别编辑日志备注和当前表保留策略。

### 4.2 战利品表追踪管理页

考古笔记左外侧的管理按钮打开独立 `LootTableManagementScreen`。页面提供名称/ResourceLocation 搜索、全部/已追踪/未追踪/最近遇到筛选、状态切换和自定义名称编辑。候选项按 namespace、path 与子路径构造成可逐层展开的文件树，并通过滚轮或可拖动滚动条连续浏览；搜索时自动展开匹配分支。“最近遇到”模式使用服务端下发的玩家记录，并让每级分支按最近的后代条目优先排列。布局根据当前 GUI 逻辑分辨率动态计算面板与双栏，截断的 ResourceLocation 可悬停查看完整值。无权限玩家仍可浏览，但只有服务端权限等级 2 的玩家可以修改。

列表不在客户端自行枚举战利品表，而是显示 `LootTableManagementClientState` 接收的服务端注册表与最近记录快照。语言选择器由语言代码输入框（`EditBox`，支持自定义语言代码并带校验，最长 16 字符）与旁侧按钮打开的 `LanguageSelectionScreen` 选择弹窗组成。

页面同时读取当前游戏资源栈中的语言 JSON：资源已有名称时显示 Resource Pack 来源并锁定输入，资源缺失时才允许编辑服务端补充配置，当前语言缺失时用 `en_us` 作为提示回退。手工输入按语言与表保存在页面草稿中，“应用更改”一次提交全部草稿；管理员可通过系统文件选择窗口导入本地语言 JSON，确认统计预览后批量提交。提交后的服务端过滤/写盘/广播语义以 [战利品表系统](loottable.md) 第 5.2 节为权威；服务端快照只驻留当前连接内存，断开即清除。
- **panel/**：可复用的面板组件。`CatalogPanel`（连续滚动目录）、`LogPanel`（日志）、`DetailOverlayPanel`（详情浮层）、`ItemGridPanel`（物品网格）、`LogDetailPanel`（日志详情）、`PagePanel` / `PageIndicator`（右页分页）、`RightPageContainer`（右侧标签页容器）。

`ItemGridPanel` 只展示当前表自身的获取路径。直接引用的子表以与物品 tag 分组相近的预览入口参与分页，
物品卡片与子表入口显示同一个**服务端派生的当前输入展示状态**（`Probability` 四态），客户端不参与判定、也不再跨代表场景取最大值。显示优先级链为「可适用性状态 → 可展示时的声明触发率 → 模拟值」：「需要条件」与未知只显示状态词、不显示任何数字（否则一个当前拿不到的条目会顶着最有利场景的数字出现），零命中显示「未命中」，`0%` 只留给静态不可达。tooltip 按状态类型给文案——需要条件逐条列出引用的旋钮与条件、未知按原因分述、零命中报本次抽样次数——并并列其它代表场景的最小/最大值供对照。具体口径见 [战利品表系统](loottable.md) 第 7.2 节。

**子表入口与物品同一条优先级链**：状态词优先于区间。同一条链在子表入口上曾被区间顶掉（区间里含 `0%`，读起来像"不可能"，与「需要条件」互相打脸），因此子表入口也改为先判状态；区间只在数值态出现。子表入口的**条件树由服务端下发**（`ChildTableProbability.conditions`）——通往它的路径共同成立的条件（交集）加上注入边门槛；此前该条件由客户端从物品路径本地重推、并对原版钓鱼表注入的泥底打捞入口留了一条"回填子表自己直接路径条件"的专门分支，而注入边不写在任何 JSON 里，本地重推必然漏掉它，泥底打捞入口因此显示成没有原因的「未命中」。现在客户端只渲染服务端给的那一份，专门分支与本地推导一并删除。
点击后由 `JournalViewModel` 展开目录祖先并选中目标子表。
左页目录使用鼠标滚轮或可拖动滚动条连续浏览，不再分页。目录树的每个节点独立保存展开状态；展开父表只显示其直接子表，只有显式展开子表时才显示孙表。
从全目录搜索结果选中条目时，`JournalViewModel` 同步切换到该条目所属分类并展开父级路径；关闭搜索后仍保留该条目与右页标签页状态。
子表入口优先预览自身直接物品；纯转发表没有直接物品时递归使用后代物品作为图标，并对循环引用做保护。
子表物品不会进入父表网格或父表的物品搜索匹配；父表 Intro 会按需递归映射全部后代物品，
按物品签名去重并读取父表自身的发现记录，避免为每个树节点重复缓存完整子树物品。
- **entry/**：目录条目数据。`ArchaeologyJournalEntry` / `ArchaeologyEntryItem` / `ArchaeologyEntryLogRef` / `ItemEntryLike`。
- **layout/**：布局计算。`JournalLayout`（书本双页布局）、`JournalViewport`（视口与滚动区域）。
- **widget/**：交互组件。`IconButton`、`BookmarkToggleButton`（收藏）、`CopyCoordinateButton`（复制传送指令）、`JournalPageButton`（翻页）、`PotteryWheelModeButton`（陶轮模式切换）、`ShadowlessEditBox`（无阴影输入框）。

工具栏共用的 `toolbar_icons.png` 是 `9 x 9` 单元格组成的 `9 x 3` 图集。固定槽位、UV
坐标与追加约束见 [工具栏图标图集](toolbar-icon-atlas.md)。
- **support/**：业务支持。`ArchaeologyJournalClientState`（状态）、`CatalogSorter`（排序）、`JournalSearchQuery`（搜索）、`JournalTooltipBuilder`（tooltip 构建）、`JournalFormatHelper`（格式化）、`LogGrouper`（日志分组）、`PaginationState`（分页状态）、`ScrollTextHelper`（滚动文本）、`JournalUiPreferencesStore`（偏好持久化）、`ArchaeologyJournalLogLocalStore`（日志本地存储）、`JournalItemDetailAppender`（物品详情追加）。
- **toast/**：`JournalUnlockToast` 弹出表/物品解锁与 100% 完成通知；`CatBondToast` 弹出羁绊阶段变化通知（由 `HandOfCatClientState` 触发）。

### 4.2.1 文本配色约束

两类文字各有自己的底色，选色时必须按**对比度**而不是"看起来淡一点"来决定：

| 场景 | 常量 | 底色 | 要求 |
|---|---|---|---|
| 网格/纸张上的状态词与数值 | `ItemGridPanel` 的 `*_COLOR` | 浅色纸面 | ≥ 4.5:1。状态词（「需要条件」「?」「尚未计算」）原为 `0xFF6B6B6B`（约 3.9:1，实测难以辨读），已改为深暖灰 `0xFF4A4038`（约 7:1） |
| tooltip 副文本 | `TooltipBuilder.HINT` | 近黑的深色 tooltip 背景 | ≥ 4.5:1。**不要用 `DARK_GRAY`**：它在该背景上只有约 1.9:1，几乎读不出来；而这里承载的恰恰是"为什么没有数字"这类必须读到的信息。与 `LABEL`（`GRAY`）同色是刻意的取舍——可读性优先于层级装饰 |
| tooltip 条件树的树枝前缀 | `TooltipBuilder.HINT` | 同上 | 同上；条件树正是"为什么没数字"的依据，前缀不可用 `DARK_GRAY` |

`TooltipBuilder` 的语义色表是唯一取色入口（见其"语义色表：语义 → 颜色，禁止在别处直接挑选颜色"注释）；新增语义应加别名而不是在渲染点临时挑色。

### 4.3 UI 打开流程

```
ArchaeologyJournalUi.registerOpener(state -> Minecraft.setScreen(new ArchaeologyJournalScreen(state)))
  ├─ 玩家右键考古笔记物品 或 按 C 键（ArchaeologyJournalKeyHandler）
  └─ 打开 ArchaeologyJournalScreen，传入 ClientState
```

`ArchaeologyJournalScreen` 从 `ArchaeologyJournalClientState` 读取目录/进度/日志数据，通过 `JournalViewModel` 组织视图，渲染 `JournalBookBackground`（书本背景）+ 各 panel。战利品表管理与帮助入口使用 `BookSideTabButton` 垂直附着在书本左侧，按纹理原生 `24 x 20` 像素尺寸渲染；帮助入口经原版链接确认界面打开项目 GitHub Wiki。

分类首页右页由 `WelcomeStatsPanel` 显示紧凑的玩家统计与最近发现。刷拭次数与战利品箱次数来自日志状态按 `LootSourceType.ARCHAEOLOGY` / `LOOT_CONTAINER` 持久化的累计来源计数；同一次聚合日志只在父表计数，子表镜像不重复累计，且日志淘汰或手动清理不会减少计数。当前日志、已有标注、最近记录和最近发现物品来自客户端日志快照，Panel 按日志与目录 revision 缓存汇总结果。最近记录只参与父表日志的比较，并展示最新一条日志中的全部实际物品（无实际结果时回退预期物品）。右下角的 GitHub Issues、CurseForge 与 Modrinth 按钮使用 `ExternalLinkButton`，并经原版 `ConfirmLinkScreen` 确认后打开链接。

日志详情页保留单条删除入口。列表工具栏提供保留配置、批量选择、批量执行、清空当前表和清空全部按钮；当前表没有日志时，整个日志工具栏隐藏。工具栏 widget 创建时一律保持隐藏，只由 `ArchaeologyJournalScreen.syncButtonState()` 根据统一的日志列表状态控制可见性，禁止各按钮自行判断当前表或全局日志数量。批量模式在条目与分组标题左侧显示方形选择框，点击整组会统一选择或取消，且此时不会进入详情页。所有显式删除都会先打开原版 `ConfirmScreen`。服务端成功后以权威单表历史或全量快照更新本地状态，并用 `JournalLogDeleteResultPayload` 显示结果提示。

### 4.4 声明式 UI kit（S1）

`client/ui/kit/` 提供 Java 声明式块序列 API，业务方给出内容，`UiDocument` 负责测量、排版、裁剪、命中和绘制。实现位于 common 客户端包，不依赖平台类，不发网络请求。场景详情页已接入；其它旧页面保留原有实现。

| 类型 | 契约 |
|---|---|
| `UiNode.Row` | 缩进单位为内容像素，文字后跟 `List<InlineIcon>`；文字与图标各自保存 tooltip、载荷、动作；行宽为自然宽度 |
| `UiNode.Gap` / `Divider` | 固定空白 / 跟随视口宽度的单像素分隔线 |
| `UiNode.Frame` / `FrameSpec` | 固定大小的子视口；只允许一层，禁止 Frame 内再嵌套 Frame；边框与浮动控件由宿主绘制 |
| `UiIcon.Item` / `Sprite` | 原版 16×16 物品 / 显式指定图集尺寸的原生大小贴图区域 |
| `TextMeasurer` | 注入宽度和行高；`TextMeasurer.of(font)` 提供原版适配；首版不折行、不截断 |
| `UiTarget` | `hit()` 返回的唯一目标；图标优先于行，空图标提示也不会回退到行提示；矩形属于命中文档的内容坐标系 |
| `UiTransform` | 内容原点、平移、缩放及双向坐标转换；档位 0.5/1/2/3，鼠标锚点缩放 |
| `UiMetrics` | 构建/排版/渲染/命中最近一次耗时（ns）与调用次数；生产宿主传 `false` 关闭计时 |

数据流为 `业务版本 + 构建器 → UiDocument.setContent → layout → render / hit`。`setContent(revision, supplier)` 只在版本变化时执行构建器；传入列表的重载每次都会更新。业务方应持有稳定构建器、为所有影响内容的输入维护版本，不应在逐帧路径创建节点、列表或 lambda。节点及其 Component 快照交给文档后按只读使用。

`setViewport(x, y, width, height)` 使用调用方 GUI 逻辑坐标；`hit(mouseX, mouseY)` 使用同一坐标系。文档原点随视口移动，只有内容、视口尺寸或 `invalidateLayout()` 改变时重排，平移和缩放均不重排。字体/语言/资源重载由宿主更新内容版本并失效排版。Frame 的 `UiTransform` 由宿主持有，原点由父文档排版设置；它的缩放锚点应先换算到父文档内容坐标，每个活动 Frame 使用独立变换对象。

排版缓存文字视觉顺序、矩形、图标与命中目标；稳定帧的 kit 绘制/命中不创建自有对象（游戏引擎内部除外）。纵向块数组用二分定位首个可见块，框外块与完全不可见图标跳过绘制。宿主每次只取一个 `hit()` 结果派生 tooltip；动作由宿主调用 `target.action().run()`，kit 不负责输入分发、焦点、浮层或状态持久化。

**裁剪边界**：1.21.1 `GuiGraphics.enableScissor` 不读取 pose（mc-developing-mcp 中原版源码已核实）。`UiTransform` 在内容变换入栈前，将当前 pose 的轴对齐平移/缩放应用到视口矩形，再设置 scissor，并在裁剪切换前提交绘制批次。支持书本整体缩放和 Frame 内缩放；不支持旋转、错切、透视。整数 GUI 像素裁剪向内取整，分数边界最多收进不足一个 GUI 像素。嵌套 scissor 使用原版交集栈恢复外层裁剪。实际双平台渲染效果和 `renderItem` 开销待 S1-t 实机验证。

最小接入示例（在初始化/内容变更时构建）：

```java
UiDocument document = new UiDocument(TextMeasurer.of(font), false);
document.setViewport(x, y, width, height);
document.setContent(List.of(new UiNode.Row(
        Component.translatable("screen.unsuspiciousblock.archaeology_journal.title"),
        UiTextPalette.Parchment.TITLE)));
// 绘制时调用 document.render(graphics, font)，交互时调用 document.hit(mouseX, mouseY)。
```

**S1 临时验证页**：Fabric / NeoForge 的 Gradle 客户端运行配置已默认添加 `-Dunsuspiciousblock.uiKitDebug=true`；IDEA 刷新 Gradle 后运行对应 `runClient`，打开笔记后按 `Ctrl+F8`。`UiKitDebugScreen` 包含 200 行、40 个物品图标和 20 个贴图图标，支持拖动、滚轮缩放、复位，以及 `P` 切换外层 pose 1x/2x。底部依次显示构建/排版次数、四段耗时（微秒）、内容缩放、外层缩放；每 5 秒在日志输出 ns 计时。拖动/滚轮/复位时排版次数应保持不变；`P` 和 resize 允许因视口改变重排。正式发布环境或未启用开关时无法进入。S1-t 双平台验收后删除此临时类、笔记快捷入口和 `ui_kit_debug.*` 本地化键。

**S0 旧页修复**：场景下拉展开时，普通物品与导航条目 tooltip 同样被屏蔽，屏幕消费滚轮防止翻页。幸运值输入框由屏幕转发拖动/释放；面板复用 `EditBox.onClick` 定位字符并保留选区锚点，补齐原版单行框缺失的拖选行为。参数确认和新浮层已在 S3 接入，自动计算已取消，见第 4.5 节。

### 4.5 场景详情页与模态交互（S2–S4）

`RightPageContainer.setTable` 同时向旧网格头部与 `ScenarioDetailPanel` 传递 tableId，SCENARIO tab 由新面板负责。页内布局是读数行 y=6、动作行 y=18、框 y=34（152×166），底部分页带仍由原生控件负责。各 tab 的分页带常量彼此独立，`pageIndicatorY()` 统一提供当前页指示器及按钮位置。

`ScenarioPageBuilder` 仅在内容 revision 或请求状态改变时重新构建：读取 `SimulationOptions.scenes()` 的签发顺序，以场景条件指纹匹配物品路径；每层条件均可包含多个图标，根、子行及树枝全部保持自然尺寸，不折行不截断。没有正条件的场景显示「无额外条件」与物品图标。未计算输入只显示未计算；`ScenarioPresentation` 只复用同参数、同抽样档位的明确场景引用，绝不把目录的基准总概率当作其它场景的概率。当前输入的直接结果可使用其总概率作为缺失场景引用的回退。

读数行、标题提示、四态角标、动作行和框角控件均使用 `UiControl`：配置时测量并缓存目标，绘制时复用固定矩形；标题过长时视觉裁剪，完整条件在 tooltip。树内命中仍走 `UiDocument.hit()`。`ScenarioFrameView` 统一提供页内和放大框的绘制与交互：框角档位/复位先于树命中，缩放档位 0.5/1/2/3，滚轮以鼠标位置为锚点，拖拽只改平移，二者均不重排。

`OverlayLayer` 同时只打开一个模态，六类输入入口均先分发给它，并保留先 flush 再 z=400 的层高契约。模态期间屏幕抑制下层自绘 tooltip、原生控件悬停和 JEI 悬停物品查询。三个使用者是：

- `ScenarioSelectionOverlay`：最多七行可见，使用服务端场景顺序；滚轮或分组箭头浏览，上下键改变当前场景，点击行后关闭，ESC/外部点击关闭。选中状态和屏幕页码共用 `ScenarioSimulationClientState`。
- `ScenarioParamsOverlay`：独立草稿、确认/取消；工具、抽样、附魔等级由签发清单约束，幸运值是唯一原生 EditBox，支持拖选；参数多时滚动。确认再次使用最新目录校验，不自动计算；ESC 取消，点外不关闭。
- `ScenarioExpandedOverlay`：居中遮罩窗口，复制页内视图到独立 `ScenarioFrameView`，复用相同交互；关闭时丢弃窗口临时平移/缩放，因此页内视图保持打开前状态。关闭按钮与框角控件均置于物品之上。

测量只能由显式计算按钮发起。旧网格头部的防抖自动请求已去除，应用推荐只改变选择。未新增网络包；仍复用既有请求/结果协议。

贴图由 `scripts/drawer/generate_scenario_ui.py` 生成：`scenario_frame.png` 是用户确认的 24×24 九宫格（8px 四角），`UiNineSlice` 用九个四边形拉伸边和中心；`scenario_status.png` 为 48×12 四格，空心点/沙漏/勾/叉对应未计算/计算中/已缓存/失败。角标同时使用形状区分状态，详细状态和失败原因放 tooltip。

### 4.6 面板状态与偏好持久化

新面板实现 `LayoutAware.applyLayout(BookLayout)`、`UiStateful.saveUiState/loadUiState(CompoundTag)`，在创建处向 `UiPanelRegistry` 注册一次即可。屏幕遍历注册表完成布局与状态恢复；旧面板不迁移接口，`RightPageContainer.savePages/loadPages` 只补齐各 tab 页号，修复 resize 只保留当前 tab 的缺口。

`ScenarioDetailPanel` 按 `tableId#scenarioKey` 保存框档位/平移，最多保留 512 个视图，切场景或表时先捕获旧视图。resize 快照与关闭笔记时都包含非当前 tab 的新面板状态；状态存在 `JournalUiPreferencesStore` 的 `panels` NBT 子树。窗口尺寸变化会关闭临时浮层、丢弃未确认草稿，保留已确认参数和页内视图。

参数仍在每张表内跨场景共用同一套，工具清单由该表签发。`SimulationPreferenceStore` 现在通过 `JournalUiPreferencesStore` 的 `simulationPreferences` NBT 子树读写选择，和 UI 偏好共用按存档、按玩家隔离的 `journal_ui_preferences.dat`。旧的全局 `config/unsuspiciousblock-simulation.properties` 保留但不再读取或自动导入，避免把一个存档/玩家的选择带到其它存档/玩家；首次使用新存储时由当前签发清单初始化合法参数。切换连接清空本地加载缓存，断线刷盘仍使用已加载的旧世界路径。

## 5. HUD

[`CatFavorHud`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/hud/CatFavorHud.java) 在快捷栏上方渲染猫之恩惠信息：

- 羁绊值与当前阶段（`CatBondStage`，带阶段颜色）。
- 残存九命命数（>0 时显示）。
- 能力解锁状态指示。
- 数据来自 `SyncCatFavorPayload` 同步的 `HandOfCatClientState`。

平台客户端在 HUD 渲染事件中调用 `CatFavorHud.render(guiGraphics)`。

[`SuspiciousReaderHud`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/hud/SuspiciousReaderHud.java)
采用左下角汇总与准星侧下方目标详情两个面板，独立于 Jade；不检测、隐藏或替代 Jade HUD，默认位置避开其顶部区域。Jade 被用户自定义移动后仍可能与任意 HUD 重叠。

- **汇总**：新扫描整批替换旧结果，显示仍有效的可疑方块/容器总数，按物品 ID、展示名及封存来源分组，最多展示三组物品；其余组数和空方块数显示在底部。所有目标保留用于定位，不受展示行数限制。空范围扫描同样发送原有 `SyncReaderScanResultPayload`，清除旧标记并显示“未发现目标”，不再写聊天栏。
- **目标详情**：仅主手或副手持扫描仪时显示，换下立即隐藏；沿相机视线在 32 格内与已扫描方块包围盒相交，忽略表层沙子的遮挡，优先最近目标，切换确认延迟 100ms。详情仅显示物品/数量、距离、相对玩家脚部的上下格数，封存者按需追加；容器仅显示内容未知，不解析容器战利品。长物品名最多两行，数量保留在首行；其余长文本按像素宽度省略。
- **布局**：两个面板分别按文本、图标、数量与内边距自适应宽度，最大 180 GUI 像素，并受窗口可用宽度限制；详情在准星右下方，靠边时向内收，小窗口两面板相交时优先显示详情。打开其他 GUI、F1 时不绘制；H 仅控制扫描仪两个面板及聚焦强调，不影响 Jade 或基础世界标记。
- **生命周期**：`ReaderScanHudState` 统一持有结果、图标、分组与聚焦目标，结果和世界描边共用单调时钟的 10 秒寿命，淡入 150ms、淡出 500ms；持续聚焦可延长阅读，整批最多保留 30 秒。新扫描重新计时；跨维度、断线、到期清理。每客户端 tick 仅检查已加载区块的缓存目标，移除方块实体消失或区块卸载的目标并更新汇总，不加载新区块。
- **渲染预算**：图标和分组在接收/结果变化时缓存，目标选择每 tick 执行，渲染只读取快照。基础可疑方块描边为高不透明度青色、空方块弱灰色、容器紫色，取消连续闪烁，聚焦目标增加浅色外框。透视绘制使用已有无深度测试 RenderType，线宽增至 3 像素以增强沙地背景下的辨识度。
- **原版字体边界**：alpha 字节低于 4 时跳过 HUD 绘制，避免原版字体强制恢复不透明。

## 6. 渲染

### 6.1 实体渲染

[`ModEntityRenderers`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/renderer/ModEntityRenderers.java) 沿用清单模式注册 4 个实体渲染器：

| 渲染器 | 实体 | 说明 |
|---|---|---|
| `MessengerCatRenderer` | 信使猫猫 | 灵体猫 + 职业装饰 |
| `SwordsmanCatRenderer` | 剑士猫猫 | 灵体渲染（钻石剑装饰待实现） |
| `MerchantCatRenderer` | 猫猫商人 | 职业装饰 |
| `LanternPetRenderer` | 灵魂提灯宠物 | 灵魂灯笼外形 |

`MessengerCatRenderer` 用 `GhostlyBufferSource` 包装 `MultiBufferSource`，把原版猫皮肤统一染成淡蓝青色半透明（alpha 由 `SpiritCat.getRenderAlphaProgress` 驱动，实现显现/消散渐变），并在其上叠加 `MessengerCatCollarLayer`（复刻原版项圈层）与 `MessengerCatClothesLayer`（职业装饰）。

职业装饰模型 [`MessengerCatClothesModel`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/model/MessengerCatClothesModel.java) 不自行播放动画：它的 `head` / `body` 枢轴与原版 `CatModel` 完全对齐，渲染器把**同一份烘焙根部件**交给 `CatModel` 与装饰层共享，装饰层每帧用 `ModelPart.copyFrom` 把原版骨骼姿态拷到装饰骨骼上，因此行走、坐下、躺卧等全部原版猫动画自动生效。装饰贴图为独立的 64x64 `textures/entity/cat/messenger_cat_clothes.png`，与随机抽取的原版猫皮肤分两次绘制。

`LanternPetModel` 是灯笼宠物的模型。`ModModelLayers` 注册模型层。

### 6.2 方块实体渲染

`PotteryWheelRenderer` 渲染陶轮方块实体的内容。

### 6.3 世界渲染

- [`SuspiciousReaderRangeHighlight`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/renderer/SuspiciousReaderRangeHighlight.java)：在半透明方块渲染之后绘制扫描结果透视描边与聚焦强调，数据来自 `SyncReaderScanResultPayload` 同步的 `ReaderScanHudState`。
- [`CatFavorShieldRenderer`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/renderer/CatFavorShieldRenderer.java)：九命触发时绘制保护罩。

平台客户端在 `AFTER_TRANSLUCENT` / `AFTER_TRANSLUCENT_BLOCKS` 阶段调用这两个渲染器。

## 7. 铁砧 / 砂轮成本分解

`client/anvil/` 与 `client/grindstone/` 实现猫之瞳驱动的成本分解：

| 包 | 类 | 职责 |
|---|---|---|
| `anvil/` | `AnvilBreakdown` | 铁砧分解数据 |
| | `AnvilBreakdownCalculator` | 计算等级成本、附魔、修复、重命名、不兼容惩罚、累积惩罚 |
| | `AnvilBreakdownTooltipBuilder` | 构建分解 tooltip 行 |
| | `AnvilBreakdownTooltipAppender` | 持有猫之瞳时追加到铁砧结果 tooltip |
| `grindstone/` | `GrindstoneBreakdown` / `Calculator` / `Builder` / `Appender` | 砂轮：预览将被移除的附魔、保留的诅咒、返还经验范围 |

平台客户端在 tooltip 事件中调用 `appendIfApplicable`，仅当玩家持有猫之瞳时追加分解行。

## 8. 附魔揭示客户端

客户端仅负责展示：[`EnchantmentRevealClientState`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/enchantment/EnchantmentRevealClientState.java) 持有服务端下发的完整附魔候选列表（`SyncEnchantmentRevealListPayload`），`EnchantmentScreenMixin` 在附魔台界面渲染完整候选而非原版的一条提示。揭示的触发机制、条件扩展与服务端权威计算以 [附魔系统](enchantment.md) 第 8 节为权威。

## 9. Toast 通知

[`JournalUnlockToast`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/toast/JournalUnlockToast.java) 弹出三类通知：

- 表解锁（`addTableUnlocks`）
- 物品解锁（`addItemUnlocks`）
- 100% 完成奖励（`addTableCompletion`）

由 `ArchaeologyJournalClientState` 的解锁通知回调触发，平台客户端在初始化时注册回调桥接。

## 10. 按键绑定

[`ModKeyBindings`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/keybind/ModKeyBindings.java) 定义 4 个按键：

| 按键 | 默认键 | 用途 |
|---|---|---|
| `SCAN_LEVEL_CYCLE` | V | 可疑解析仪扫描等级切换 |
| `JOURNAL_OPEN` | C | 打开考古笔记；在背包/JEI 物品上按下时按注册名搜索 |
| `CAT_DETERRENCE_TOGGLE` | 未绑定 | 切换猫之威慑开关 |
| `CAT_LIGHT_STEP_TOGGLE` | 未绑定 | 切换轻步开关 |
| `READER_HUD_TOGGLE` | H | 打开/关闭扫描仪 HUD |

Fabric 用 `KeyBindingHelper.registerKeyBinding`，NeoForge 用 `RegisterKeyMappingsEvent`。玩家可在控制设置中自由修改。威慑/轻步切换通过 C2S payload 发送到服务端处理（`CatPassiveAbilities.onDeterrenceToggle` / `onLightStepToggle`）。

`ArchaeologyJournalKeyHandler` 还处理 GUI 内的 C 键：Fabric 通过 `ScreenKeyboardEvents`，NeoForge 通过 `ScreenEvent.KeyPressed.Pre` 接入。它优先读取 JEI 悬停原料，否则读取原版容器槽位，并用 `JournalSearchQuery.forItemId` 以完整物品注册名预检搜索结果；存在结果时用初始搜索条件打开手册。

## 11. 客户端 Mixin

`mixin/client/` 包含 4 个客户端 mixin，注入点与目标的权威清单见 [Mixin 总览](mixin.md) 第 3.7 节。本篇只说明用途：`AbstractContainerScreenAccessor`（tooltip 渲染访问容器屏幕内部字段）、`EditBoxMixin`（搜索框行为）、`EnchantmentScreenMixin`（附魔揭示渲染）、`ClientLanguageMixin`（服务端补充名称动态生效，机制见 [战利品表系统](loottable.md) 第 5.2 节）。

## 12. 扩展点

- **新增实体渲染器**：在 `ModEntityRenderers.REGISTRY_MANIFEST` 加条目，在 `ModModelLayers` 加模型层。
- **新增 GUI 面板**：在 `panel/` 实现，由对应 `screen/` 组合。
- **新增 HUD 元素**：参考 `CatFavorHud`，在平台客户端 HUD 事件中调用。
- **新增 tooltip 分解**：参考 `AnvilBreakdownTooltipAppender`，在 tooltip 事件中按条件追加。
- **新增 Toast**：参考 `JournalUnlockToast`，在 `ArchaeologyJournalClientState` 注册回调。
- **新增按键**：在 `ModKeyBindings` 加 `KeyMapping`，在客户端 tick 中处理。

## 13. 相关文档

- [架构总览](architecture-overview.md) - 客户端初始化流程
- [考古笔记系统](journal.md) - 客户端状态的同步来源
- [网络与同步](network.md) - S2C payload 与接收器
- [猫族关系系统](cat-favor.md) - HUD 数据来源
- [附魔系统](enchantment.md) - 附魔揭示
- [Mixin 总览](mixin.md) - 客户端 mixin
- [淘洗系统](panning.md) - 淘盘动画、水声与贴水波光
