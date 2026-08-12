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
| `ClientLootTableLanguageStore` | 服务端名称落盘、资源重载和动态语言覆盖 |
| `SuspiciousReaderClientState` | 可疑解析仪客户端状态（扫描结果） |
| `ReaderScanHighlightState` | 范围扫描高亮状态（描边方块） |
| `HandOfCatClientState` | 猫之手客户端状态（缓存的 favor/lives） |
| `CatHandClientState` | 猫之手按键处理状态 |
| `SpecimenBoxScrollState` | 标本箱滚动状态 |
| `EnchantmentRevealClientState` | 附魔揭示客户端状态（完整候选列表） |
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

`client/ui/` 子包按职责分层，共约 41 个类：

```
ui/
├── ArchaeologyJournalUi          UI 注册入口（注册 opener）
├── entry/      目录条目（ArchaeologyJournalEntry / ItemEntryLike / ...）
├── layout/     布局（JournalLayout / JournalBookBackground）
├── panel/      面板（CatalogPanel / LogPanel / DetailOverlayPanel / ItemGridPanel / ...）
├── screen/     屏幕（ArchaeologyJournalScreen / LootTableManagementScreen / SpecimenBoxScreen / ...）
├── support/    支持类（ClientState / CatalogSorter / JournalSearchQuery / PaginationState / ...）
├── toast/      Toast 通知（JournalUnlockToast）
├── widget/     组件（IconButton / BookmarkToggleButton / CopyCoordinateButton / ...）
└── tooltip/    tooltip（ClientSpecimenBoxTooltip）
```

### 4.1 分层职责

- **screen/**：顶层 `Screen` 实现。`ArchaeologyJournalScreen` 是主屏幕，`LootTableManagementScreen` 是与手册 TAB 分离的追踪管理页；`JournalViewModel` 持有视图状态，`CatalogToolbar` / `LogToolbar` 是工具栏。`SpecimenBoxScreen` / `PotteryWheelScreen` 是容器屏幕。`JournalLogNoteEditScreen` 是日志备注编辑。

### 4.2 战利品表追踪管理页

考古手册左外侧的管理按钮打开独立 `LootTableManagementScreen`。页面提供名称/ResourceLocation 搜索、全部/已追踪/未追踪筛选、状态切换、语言代码及自定义名称编辑。候选项按 namespace、path 与子路径构造成可逐层展开的文件树，并通过滚轮或可拖动滚动条连续浏览；搜索时自动展开匹配分支。布局根据当前 GUI 逻辑分辨率动态计算面板与双栏，截断的 ResourceLocation 可悬停查看完整值。无权限玩家仍可浏览，但只有服务端权限等级 2 的玩家可以修改。

列表不在客户端自行枚举资源，而是显示 `LootTableManagementClientState` 接收的服务端注册表快照。名称更新后，客户端写入 `config/unsuspiciousblock/lang/<language>.json`；内容实际变化时触发资源重载，使当前界面立即使用新名称。
- **panel/**：可复用的面板组件。`CatalogPanel`（目录）、`LogPanel`（日志）、`DetailOverlayPanel`（详情浮层）、`ItemGridPanel`（物品网格）、`LogDetailPanel`（日志详情）、`PagePanel` / `PageIndicator`（分页）、`RightPageContainer`（右侧标签页容器）。
- **entry/**：目录条目数据。`ArchaeologyJournalEntry` / `ArchaeologyEntryItem` / `ArchaeologyEntryLogRef` / `ItemEntryLike`。
- **layout/**：布局计算。`JournalLayout`（书本双页布局）、`JournalBookBackground`（背景渲染）。
- **widget/**：交互组件。`IconButton`、`BookmarkToggleButton`（收藏）、`CopyCoordinateButton`（复制传送指令）、`JournalPageButton`（翻页）。
- **support/**：业务支持。`ArchaeologyJournalClientState`（状态）、`CatalogSorter`（排序）、`JournalSearchQuery`（搜索）、`JournalTooltipBuilder`（tooltip 构建）、`JournalFormatHelper`（格式化）、`LogGrouper`（日志分组）、`PaginationState`（分页状态）、`ScrollTextHelper`（滚动文本）、`JournalUiPreferencesStore`（偏好持久化）、`ArchaeologyJournalLogLocalStore`（日志本地存储）。
- **toast/**：`JournalUnlockToast` 弹出表/物品解锁与 100% 完成通知。

### 4.3 UI 打开流程

```
ArchaeologyJournalUi.registerOpener(state -> Minecraft.setScreen(new ArchaeologyJournalScreen(state)))
  ├─ 玩家右键考古笔记物品 或 按 C 键（ArchaeologyJournalKeyHandler）
  └─ 打开 ArchaeologyJournalScreen，传入 ClientState
```

`ArchaeologyJournalScreen` 从 `ArchaeologyJournalClientState` 读取目录/进度/日志数据，通过 `JournalViewModel` 组织视图，渲染 `JournalBookBackground`（书本背景）+ 各 panel。

## 5. HUD

[`CatFavorHud`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/hud/CatFavorHud.java) 在快捷栏上方渲染猫之恩惠信息：

- 羁绊值与当前阶段（`CatBondStage`，带阶段颜色）。
- 残存九命命数（>0 时显示）。
- 能力解锁状态指示。
- 数据来自 `SyncCatFavorPayload` 同步的 `HandOfCatClientState`。

平台客户端在 HUD 渲染事件中调用 `CatFavorHud.render(guiGraphics)`。

## 6. 渲染

### 6.1 实体渲染

[`ModEntityRenderers`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/renderer/ModEntityRenderers.java) 沿用清单模式注册 4 个实体渲染器：

| 渲染器 | 实体 | 说明 |
|---|---|---|
| `MessengerCatRenderer` | 猫猫信使 | 灵体猫 + 职业装饰 |
| `SwordsmanCatRenderer` | 剑士猫猫 | 嘴叼钻石剑 |
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
| `JOURNAL_OPEN` | C | 打开考古笔记 |
| `CAT_DETERRENCE_TOGGLE` | 未绑定 | 切换猫之威慑开关 |
| `CAT_LIGHT_STEP_TOGGLE` | 未绑定 | 切换轻步开关 |

Fabric 用 `KeyBindingHelper.registerKeyBinding`，NeoForge 用 `RegisterKeyMappingsEvent`。玩家可在控制设置中自由修改。威慑/轻步切换通过 C2S payload 发送到服务端处理（`CatPassiveAbilities.onDeterrenceToggle` / `onLightStepToggle`）。

## 11. 客户端 Mixin

`mixin/client/` 包含 3 个客户端 mixin（见 [mixin.md](mixin.md)）：

- `AbstractContainerScreenAccessor`：访问容器屏幕的内部字段（tooltip 渲染用）。
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
