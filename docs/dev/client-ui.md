# 客户端与 GUI

本文档描述 `client/` 包的架构：客户端初始化、状态管理、考古笔记 GUI 层级、HUD、渲染、铁砧/砂轮成本分解、附魔揭示与 Toast 通知。

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
| `ReaderScanHighlightState` | 范围扫描高亮状态（描边方块） |
| `ReaderScanHudState` | 最近一次结构化扫描结果与左下角 HUD 倒计时 |
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

`client/ui/` 子包按职责分层，共约 51 个类：

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

页面同时读取当前游戏资源栈中的语言 JSON。资源已有名称时显示 Resource Pack 来源并锁定输入；资源缺失时才允许编辑服务端补充配置，当前语言缺失时用 `en_us` 作为提示回退。手工输入按语言与表保存在页面草稿中，“应用更改”一次提交全部草稿。管理员可通过系统文件选择窗口导入本地语言 JSON，确认统计预览后批量提交；资源已有项、未匹配当前服务端战利品表的 key 与非法值不会上传。服务端快照只存客户端内存，断开服务器即清除。
- **panel/**：可复用的面板组件。`CatalogPanel`（连续滚动目录）、`LogPanel`（日志）、`DetailOverlayPanel`（详情浮层）、`ItemGridPanel`（物品网格）、`LogDetailPanel`（日志详情）、`PagePanel` / `PageIndicator`（右页分页）、`RightPageContainer`（右侧标签页容器）。

`ItemGridPanel` 只展示当前表自身的获取路径。直接引用的子表以与物品 tag 分组相近的预览入口参与分页，
物品与子表入口卡片统一显示代表场景中的最高概率，tooltip 汇总显示最小值与最大值，不再逐场景展开条件树。子表 tooltip 还会从父表展开后的获取路径中提取各产出共有的触发条件；原版钓鱼表运行时注入的泥底打捞入口没有静态父表路径，因此仅对这条已知引用从泥底打捞 JSON 定义的直接路径回填公共条件。
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

### 4.3 UI 打开流程

```
ArchaeologyJournalUi.registerOpener(state -> Minecraft.setScreen(new ArchaeologyJournalScreen(state)))
  ├─ 玩家右键考古笔记物品 或 按 C 键（ArchaeologyJournalKeyHandler）
  └─ 打开 ArchaeologyJournalScreen，传入 ClientState
```

`ArchaeologyJournalScreen` 从 `ArchaeologyJournalClientState` 读取目录/进度/日志数据，通过 `JournalViewModel` 组织视图，渲染 `JournalBookBackground`（书本背景）+ 各 panel。战利品表管理与帮助入口使用 `BookSideTabButton` 垂直附着在书本左侧，按纹理原生 `24 x 20` 像素尺寸渲染；帮助入口经原版链接确认界面打开项目 GitHub Wiki。

分类首页右页由 `WelcomeStatsPanel` 显示紧凑的玩家统计与最近发现。刷拭次数与战利品箱次数来自日志状态按 `LootSourceType.ARCHAEOLOGY` / `LOOT_CONTAINER` 持久化的累计来源计数；同一次聚合日志只在父表计数，子表镜像不重复累计，且日志淘汰或手动清理不会减少计数。当前日志、已有标注、最近记录和最近发现物品来自客户端日志快照，Panel 按日志与目录 revision 缓存汇总结果。最近记录只参与父表日志的比较，并展示最新一条日志中的全部实际物品（无实际结果时回退预期物品）。右下角的 GitHub Issues、CurseForge 与 Modrinth 按钮使用 `ExternalLinkButton`，并经原版 `ConfirmLinkScreen` 确认后打开链接。

日志详情页保留单条删除入口。列表工具栏提供保留配置、批量选择、批量执行、清空当前表和清空全部按钮；当前表没有日志时，整个日志工具栏隐藏。工具栏 widget 创建时一律保持隐藏，只由 `ArchaeologyJournalScreen.syncButtonState()` 根据统一的日志列表状态控制可见性，禁止各按钮自行判断当前表或全局日志数量。批量模式在条目与分组标题左侧显示方形选择框，点击整组会统一选择或取消，且此时不会进入详情页。所有显式删除都会先打开原版 `ConfirmScreen`。服务端成功后以权威单表历史或全量快照更新本地状态，并用 `JournalLogDeleteResultPayload` 显示结果提示。

## 5. HUD

[`CatFavorHud`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/hud/CatFavorHud.java) 在快捷栏上方渲染猫之恩惠信息：

- 羁绊值与当前阶段（`CatBondStage`，带阶段颜色）。
- 残存九命命数（>0 时显示）。
- 能力解锁状态指示。
- 数据来自 `SyncCatFavorPayload` 同步的 `HandOfCatClientState`。

平台客户端在 HUD 渲染事件中调用 `CatFavorHud.render(guiGraphics)`。

[`SuspiciousReaderHud`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/hud/SuspiciousReaderHud.java)
在左下角显示扫描结果容器：最多保留 5 条独立信息，每条存活 8 秒；透明度曲线全程连续——淡入 200ms、
保持、最后 3 秒淡出，同坐标再次扫描时从当前透明度平滑回升而非瞬间跳回不透明。
后续扫描会追加到尚未消失的容器中。面板透明度锚定最新一条条目（`Snapshot.anchor()`，即列表首位）
的淡出曲线（不含淡入），渲染线程通过单次加锁快照读取，保证面板淡出与最新信息同帧同步，
且新条目到达时面板保持可见；过期条目由 tick 逐条清理，不做整体清空。
最后一条真实扫描信息消失后，容器提示的剩余寿命被压缩为一次完整淡出以平滑关闭 HUD。
**原版渲染边界**：`Font.adjustColor` 会把 alpha 字节小于 4 的文字颜色强制改为完全不透明，
因此任何文字 alpha 字节低于 `MIN_TEXT_ALPHA`（4）时必须跳过绘制（面板与条目行均遵守），
否则淡出末尾与淡入起始会闪现全不透明文字。
面板不显示坐标，背景保持低不透明度，最大宽度 148 GUI 像素。
结果由 `SyncReaderScanResultPayload` 以紧凑条目同步，避免范围扫描逐条写入聊天栏。

## 6. 渲染

### 6.1 实体渲染

[`ModEntityRenderers`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/renderer/ModEntityRenderers.java) 沿用清单模式注册 4 个实体渲染器：

| 渲染器 | 实体 | 说明 |
|---|---|---|
| `MessengerCatRenderer` | 猫猫信使 | 灵体猫 + 职业装饰 |
| `SwordsmanCatRenderer` | 剑士猫猫 | 灵体渲染（钻石剑装饰待实现） |
| `MerchantCatRenderer` | 猫猫商人 | 职业装饰 |
| `LanternPetRenderer` | 灵魂提灯宠物 | 灵魂灯笼外形 |

`GhostCatRenderer` / `GhostCatCollarLayer` 处理灵体猫的通用渲染（半透明、项圈层）。`LanternPetModel` 是灯笼宠物的模型。`ModModelLayers` 注册模型层。渲染器的 alpha 由 `SpiritCat.getRenderAlphaProgress` 驱动（显现/消散渐变）。

### 6.2 方块实体渲染

`PotteryWheelRenderer` 渲染陶轮方块实体的内容。

### 6.3 世界渲染

- [`SuspiciousReaderRangeHighlight`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/renderer/SuspiciousReaderRangeHighlight.java)：在半透明方块渲染之后绘制范围扫描高亮（可疑方块与战利品容器描边），实现透视效果。数据来自 `SyncReaderScanResultPayload` 同步的 `ReaderScanHighlightState`。
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

[`EnchantmentRevealClientState`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/enchantment/EnchantmentRevealClientState.java) 持有服务端下发的完整附魔候选列表（`SyncEnchantmentRevealListPayload`）。`EnchantmentScreenMixin` 在附魔台界面渲染完整候选，而非原版的一条提示。详见 [附魔系统](enchantment.md) 第 8 节。

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

`mixin/client/` 包含 4 个客户端 mixin（见 [mixin.md](mixin.md)）：

- `AbstractContainerScreenAccessor`：访问容器屏幕的内部字段（tooltip 渲染用）。
- `ClientLanguageMixin`：对自动生成的战利品表 key 动态补充当前服务端名称；仅在真实资源重载时清理资源来源索引。
- `EditBoxMixin`：输入框行为调整（搜索框）。
- `EnchantmentScreenMixin`：附魔台界面渲染完整候选列表。

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


## 淘盘动画与水声

`client/pan/PanningAnimation` 提供第一人称横向托平、绕圈摇洗与小幅抖动，并调整第三人称持盘手臂，支持左右手和副手。`CopperPanItem` 使用 `UseAnim.NONE`，不再触发原版刷子动画。第一人称由 NeoForge `RenderHandEvent` 与 Fabric `ItemInHandPanningMixin` 分别接入；第三人称由 common 客户端 `HumanoidPanningMixin` 在原版姿势完成后转交动画类。

`PanningSoundController` 每五刻检查玩家附近工作中的淘洗点，每个点最多一个 `PanningSound`。声音复用原版 `block.water.ambient`，随摇洗周期调整音调与音量；停止工作、实体消散、离开范围或切换世界时停止。声音控制器通过两端客户端 tick 与断连事件管理，不从服务端类引用客户端类。


贴水波光由 `ShimmerSurfaceRenderer` 在两端半透明方块渲染之后绘制：依据实际流体高度，在水面上方绘制金白色细线反光，保留深度测试并关闭深度写入。仅查询相机周围 32 格，远端淡出。剩余 3 次及以上、2 次、1 次分别绘制 48、28、12 道反光；增加线宽并保留基础亮度，避免闪烁低谷时难以辨认。白色粒子对应约 15、6.7、2 个每秒，利用明显的密度差异提示剩余次数；淘洗时仍保留水花与涟漪。

第一人称工作动画使用 `ItemDisplayContext.NONE` 渲染原始物品盘面，由动画独立设置缩放与绕 X 轴的倾角，避免叠加 generated 模型自带第一人称旋转而变成侧立。普通持物仍使用原版显示变换。
