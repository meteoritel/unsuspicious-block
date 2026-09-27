# 客户端与 GUI

> `client/` 包的架构：客户端初始化、状态管理、考古笔记 GUI 层级、HUD、渲染、铁砧/砂轮成本分解、附魔揭示展示与 Toast 通知。
> 本文件是**客户端通用基础设施**（状态框架、按键、Toast、HUD/渲染接入）与**考古笔记 GUI** 的唯一权威。GUI 内部分层、UI kit 契约、场景页与模态、面板状态持久化见 [笔记 GUI 内部机制](../internals/journal-ui-internals.md)。
> 各玩法子系统的客户端表现，其**机制**权威在对应子系统文档（猫 HUD 数据流见 [猫族关系系统](cat-favor.md)、淘盘动画与水声见 [淘洗系统](panning.md)、附魔揭示服务端机制见 [附魔系统](enchantment.md)）；本篇只保留客户端侧的接入方式与渲染细节。

## 1. 代码地图

| 层 | 位置 |
|---|---|
| 入口 | `UnsuspiciousBlockFabricClient` / `UnsuspiciousBlockNeoForgeClient`（两端职责对称） |
| UI 根 | `client/ui/ArchaeologyJournalUi`（注册 opener）、`JournalBookBackground`、`PotteryPreviewRenderer` |
| UI 分层 | `client/ui/{entry,layout,panel,screen,support,toast,widget,tooltip}/`（清单见 [笔记 GUI 内部机制](../internals/journal-ui-internals.md)） |
| 声明式 UI kit | `client/ui/kit/`（`UiDocument` / `UiNode` / `TextScroll` / `TextMeasurer` / `UiTransform` / `UiControl` / `UiMetrics`…） |
| 客户端状态 | `client/state/`：`ArchaeologyJournalKeyHandler`、`HandOfCatClientState`、`CatHandClientState`、`ReaderScanHudState`、`SuspiciousReaderClientState`、`ScenarioSimulationClientState`、`SimulationPreferenceStore`、`JournalHoveredItemProvider` |
| UI 侧状态 | `client/ui/support/`：`ArchaeologyJournalClientState`、`LootTableManagementClientState`、`ClientLootTableLanguageStore`、`JournalUiPreferencesStore`、`UiPanelRegistry`、`UiTextPalette`… |
| HUD | `client/hud/`：`CatFavorHud`、`SuspiciousReaderHud` |
| 渲染 | `client/renderer/`：`ModEntityRenderers`、`ModModelLayers`、各实体渲染器、`SuspiciousReaderRangeHighlight`、`CatFavorShieldRenderer`、`ShimmerSurfaceRenderer`（淘洗，见 [淘洗系统](panning.md)） |
| 模型 | `client/model/MessengerCatClothesModel`、`client/renderer/layer/`（项圈与职业装饰层） |
| 分解预览 | `client/anvil/`、`client/grindstone/`（各含 `Breakdown` / `Calculator` / `TooltipBuilder` / `TooltipAppender`） |
| 附魔揭示 | `client/enchantment/EnchantmentRevealClientState` |
| tooltip 基础 | `client/tooltip/TooltipBuilder`（语义色表唯一出口，规范见 [文本格式规范](../foundation/text-format.md)） |
| 按键 | `client/keybind/ModKeyBindings` |
| 网络 | S2C 接收器遍历 `ModPayloads.Client.S2C_PAYLOADS`（清单见 [网络与同步](../foundation/network.md)） |
| Mixin | `mixin/client/`：`AbstractContainerScreenAccessor`、`EditBoxMixin`、`EnchantmentScreenMixin`、`ClientLanguageMixin`、`HumanoidPanningMixin`；Fabric 另有 `ItemInHandPanningMixin`。全清单见 [Mixin](../foundation/mixin.md) |
| 平台差异 | 按键注册：`KeyBindingHelper` vs `RegisterKeyMappingsEvent`；GUI 内按键：`ScreenKeyboardEvents` vs `ScreenEvent.KeyPressed.Pre`；第一人称淘盘动画：事件 vs mixin；世界/HUD 渲染阶段事件名不同 |

## 2. 数据流

**UI 打开**

```
ArchaeologyJournalUi.registerOpener(state -> Minecraft.setScreen(new ArchaeologyJournalScreen(state)))
  ├─ 玩家右键考古笔记物品 或 按 C 键（ArchaeologyJournalKeyHandler）
  └─ 打开 ArchaeologyJournalScreen，传入 ClientState
```

`ArchaeologyJournalScreen` 从 `ArchaeologyJournalClientState` 读目录/进度/日志数据，通过 `JournalViewModel` 组织视图，渲染 `JournalBookBackground` + 各 panel。战利品表管理与帮助入口使用 `BookSideTabButton` 垂直附着在书本左侧，按纹理原生 `24 x 20` 像素尺寸渲染；帮助入口经原版 `ConfirmLinkScreen` 打开项目 GitHub Wiki。

**状态同步**

```
S2C payload ──► ArchaeologyJournalClientState / *ClientState
                  ├─ 目录：哈希不一致 → 请求全量
                  ├─ 进度：增量包 + 间隙检测（丢包 → 请求全量重同步）
                  ├─ 日志：分片快照会话
                  └─ 解锁通知 → 回调桥接 → JournalUnlockToast
客户端 tick ──► 按键处理、扫描高亮、客户端状态清理
断连 ──► 落盘 UI 偏好 → resetOnDisconnect
```

## 3. 关键类

| 类 | 职责 |
|---|---|
| `client/ui/support/ArchaeologyJournalClientState` | 考古笔记核心状态（目录/进度/日志缓存 + 解锁通知 + UI 偏好） |
| `client/ui/screen/ArchaeologyJournalScreen` | 主屏幕，组合书本背景与各面板 |
| `client/ui/screen/JournalViewModel` | 视图状态：分类切换、展开路径、右页标签页 |
| `client/ui/kit/UiDocument` | 声明式 UI 的测量/排版/裁剪/命中/绘制中心 |
| `client/ui/overlay/OverlayLayer` | 模态层（同时只开一个），统一输入分发与层高契约 |
| `client/ui/support/UiPanelRegistry` / `UiStateful` / `LayoutAware` | 面板的布局/状态恢复注册表与接口 |
| `client/ui/support/JournalUiPreferencesStore` | UI 偏好与面板状态持久化（按存档、按玩家隔离） |
| `client/hud/CatFavorHud` | 猫之恩惠 HUD |
| `client/hud/SuspiciousReaderHud` | 解析仪扫描汇总与目标详情 HUD |
| `client/state/ReaderScanHudState` | 扫描结果、图标分组、聚焦目标与描边的统一生命周期 |
| `client/keybind/ModKeyBindings` | 4 个按键绑定 |
| `client/tooltip/TooltipBuilder` | 语义色常量 + 五段式构建器 |

## 4. 客户端初始化

两个平台客户端入口职责高度对称（见 [架构总览](../foundation/architecture.md) 的客户端初始化），都完成：按键注册、渲染器/模型层注册、Screen 注册、S2C 接收器注册、Tooltip 组件注册、HUD/世界渲染注册、客户端 tick 驱动、断连重置。

关键清单模式（与 [注册架构](../foundation/registration.md) 一致，平台客户端自动遍历）：

| 清单 | 用途 |
|---|---|
| `ModEntityRenderers.REGISTRY_MANIFEST` | 实体渲染器（4 个灵体/宠物） |
| `ModModelLayers` | 模型层 `LayerDefinition` |
| `ModPayloads.Client.S2C_PAYLOADS` | S2C 接收器 |

## 5. 客户端状态管理

| 状态类 | 职责 |
|---|---|
| `ArchaeologyJournalClientState` | 考古笔记核心状态（目录/进度/日志缓存 + 解锁通知 + UI 偏好） |
| `LootTableManagementClientState` | 服务端权威战利品表索引、权限和多语言名称快照 |
| `ClientLootTableLanguageStore` | 游戏资源来源索引、服务端名称内存快照和动态语言补充 |
| `SuspiciousReaderClientState` | 可疑解析仪按键与扫描等级切换 |
| `ReaderScanHudState` | 整批扫描汇总、地下目标聚焦与世界描边的统一生命周期 |
| `HandOfCatClientState` | 猫之手客户端状态（缓存的 favor/lives） |
| `CatHandClientState` | 猫之手按键处理状态 |
| `EnchantmentRevealClientState` | 附魔揭示客户端状态（完整候选列表） |
| `JournalHoveredItemProvider` | 可选物品查看器（JEI）向考古笔记快捷键提供悬停物品；空物品栈表示输入被查看器占用 |
| `ArchaeologyJournalKeyHandler` | 考古笔记按键处理 |

### 5.1 ArchaeologyJournalClientState 核心

这是最复杂的客户端状态类，管理服务端推送的所有数据并驱动 UI：

- **数据缓存**：`serverCatalog` / `catalogStructure`（服务端目录快照）、`journalState`（玩家进度）、`cachedCatalogHash`（按需同步比对）。
- **同步处理**：`receiveCatalogHash` 与本地比对，不一致则请求全量目录；`receiveState`（全量）**首次同步不弹 Toast**（避免重进世界重复通知），后续检测新解锁；`receiveStateIncremental`（增量）做**间隙检测**——`incomingRevision > lastNotifiedRevision + 1` 说明中间丢包，请求全量重同步，仅对变更表做 Diff；`requestFullStateWithCooldown` 限流 2s 防请求风暴。
- **解锁通知**：`tableUnlockNotifier` / `itemUnlockNotifier` 是回调接口，由平台客户端注入（桥接到 `JournalUnlockToast`），解耦状态层与 UI 层；`detectAndNotifyUnlocks` 比较新旧状态，**仅对追踪目录中的表**触发（避免未追踪表弹通知）。
- **UI 偏好持久化**：跨打开/关闭保留排序方式、搜索文本、隐藏未解锁开关、收藏集合、日志分组模式、右侧标签页等，经 `JournalUiPreferencesStore` 落盘；`persistenceSuppress` 在加载持久化文件时抑制 `markDirty`（避免加载即回写）；`resetOnDisconnect` 断线时先落盘 UI 偏好再重置同步状态。

## 6. 笔记 GUI

`client/ui/` 按职责分层（`entry/` `layout/` `panel/` `screen/` `support/` `toast/` `widget/` `tooltip/`）。**分层职责、追踪管理页、网格页展示优先级链、文本配色约束、声明式 UI kit 契约、场景详情页与模态交互、面板状态与偏好持久化见 [笔记 GUI 内部机制](../internals/journal-ui-internals.md)。**

场景详情页以结果为默认视图，固定概率列；条件树在独立滚动视图中阅读，复杂内容可进入灯箱。居中场景列表用双行展示短名称、状态与条件摘要；确认参数后仍需显式点击「计算」。当前迁移按 [考古笔记 GUI 翻新计划](../../plan/journal-gui-refresh-plan.md) 分阶段进行，机制细节以内部文档为准。

新增面板的标准路径：在 `panel/` 实现 → 由对应 `screen/` 组合 → 实现 `LayoutAware` / `UiStateful` 并在创建处向 `UiPanelRegistry` 注册一次。面板内优先复用 `client/ui/kit/` 的声明式组件而不是手算坐标：静态内容走 `UiDocument` 块序列，滚动区走 `UiScrollView`，成组的可点击控件走 `UiControlGroup`，控件与原生输入框的落位走 `UiLinearLayout`；控件结构色取 `UiControlStyle`，文本色取 `UiTextPalette`。需要键盘导航的面板把可聚焦目标按**视觉顺序**登记进 `UiFocusManager`（原生输入框用宿主侧 `UiFocusTarget` 适配器夹在中间），打开模态时用 `OverlayLayer.open(overlay, opener)` 传入打开它的目标以交接焦点；图片 / 内容查看器走 `UiLightbox` + `LightboxOverlay`（自绘内容实现 `UiLightbox.Content`，图片用 `LightboxImage` + `UiImageView`），外壳不直接依赖 `OverlayLayer`。接入步骤与公开入口清单见 [UI kit 公开 API 与兼容策略](../internals/ui-kit-api.md)，机制口径见 [笔记 GUI 内部机制](../internals/journal-ui-internals.md)。

## 7. HUD

[`CatFavorHud`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/hud/CatFavorHud.java) 在快捷栏上方渲染猫之恩惠信息：

- 羁绊值与当前阶段（`CatBondStage`，带阶段颜色）。
- 残存九命命数（> 0 时显示）。
- 能力解锁状态指示。
- 数据来自 `SyncCatFavorPayload` 同步的 `HandOfCatClientState`。

[`SuspiciousReaderHud`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/hud/SuspiciousReaderHud.java) 采用左下角汇总与准星侧下方目标详情两个面板，**独立于 Jade**；不检测、隐藏或替代 Jade HUD，默认位置避开其顶部区域。

- **汇总**：新扫描整批替换旧结果，显示仍有效的可疑方块/容器总数，按物品 ID、展示名及封存来源分组，最多展示三组物品；其余组数和空方块数显示在底部。所有目标保留用于定位，不受展示行数限制。空范围扫描同样发送 `SyncReaderScanResultPayload`，清除旧标记并显示"未发现目标"，不再写聊天栏。
- **目标详情**：仅主手或副手持扫描仪时显示，换下立即隐藏；沿相机视线在 32 格内与已扫描方块包围盒相交，忽略表层沙子的遮挡，优先最近目标，切换确认延迟 100ms。详情仅显示物品/数量、距离、相对玩家脚部的上下格数，封存者按需追加；容器仅显示内容未知，不解析容器战利品。长物品名最多两行，数量保留在首行；其余长文本按像素宽度省略。
- **布局**：两个面板分别按文本、图标、数量与内边距自适应宽度，最大 180 GUI 像素，并受窗口可用宽度限制；详情在准星右下方，靠边时向内收，小窗口两面板相交时优先显示详情。打开其他 GUI、F1 时不绘制；`H` 仅控制扫描仪两个面板及聚焦强调，不影响 Jade 或基础世界标记。
- **生命周期**：`ReaderScanHudState` 统一持有结果、图标、分组与聚焦目标，结果和世界描边共用单调时钟的 10 秒寿命，淡入 150ms、淡出 500ms；持续聚焦可延长阅读，整批最多保留 30 秒。新扫描重新计时；跨维度、断线、到期清理。每客户端 tick 仅检查已加载区块的缓存目标，移除方块实体消失或区块卸载的目标并更新汇总，**不加载新区块**。
- **渲染预算**：图标和分组在接收/结果变化时缓存，目标选择每 tick 执行，渲染只读取快照。基础可疑方块描边为高不透明度青色、空方块弱灰色、容器紫色，取消连续闪烁，聚焦目标增加浅色外框。透视绘制使用已有无深度测试 `RenderType`，线宽增至 3 像素。
- **原版字体边界**：alpha 字节低于 4 时跳过 HUD 绘制，避免原版字体强制恢复不透明。

平台客户端在 HUD 渲染事件中调用这两个 HUD 的 `render(guiGraphics)`。

## 8. 渲染

### 8.1 实体渲染

`ModEntityRenderers` 沿用清单模式注册实体渲染器：

| 渲染器 | 实体 | 说明 |
|---|---|---|
| `MessengerCatRenderer` | 信使猫猫 | 灵体渲染 + 项圈层 + 职业装饰层 |
| `SwordsmanCatRenderer` | 剑士猫猫 | 灵体渲染（钻石剑装饰待实现） |
| `MerchantCatRenderer` | 猫猫商人 | 职业装饰 |
| `LanternPetRenderer` | 灵魂提灯宠物 | 灵魂灯笼外形 |

**灵体半透明**：`MessengerCatRenderer` 用自定义 `MultiBufferSource` 包装原版猫皮肤，统一染成淡蓝青色半透明（alpha 由 `SpiritCat.getRenderAlphaProgress` 驱动，实现显现/消散渐变），并在其上叠加 `MessengerCatCollarLayer`（复刻原版项圈层）与 `MessengerCatClothesLayer`（职业装饰）。

**职业装饰跟随原版动画**：`MessengerCatClothesModel` 不自行播放动画——它的 `head` / `body` 枢轴与原版 `CatModel` 完全对齐，渲染器把**同一份烘焙根部件**交给 `CatModel` 与装饰层共享，装饰层每帧用 `ModelPart.copyFrom` 把原版骨骼姿态拷到装饰骨骼上，因此行走、坐下、躺卧等全部原版猫动画自动生效。装饰贴图为独立的 `textures/entity/cat/messenger_cat_clothes.png`（64x64），与随机抽取的原版猫皮肤分两次绘制。

### 8.2 方块实体渲染

`PotteryWheelRenderer` 渲染陶轮方块实体的内容。

### 8.3 世界渲染

- `SuspiciousReaderRangeHighlight`：在半透明方块渲染之后绘制扫描结果透视描边与聚焦强调，数据来自 `SyncReaderScanResultPayload` 同步的 `ReaderScanHudState`。
- `CatFavorShieldRenderer`：九命触发时绘制保护罩。

平台客户端在 `AFTER_TRANSLUCENT` / `AFTER_TRANSLUCENT_BLOCKS` 阶段调用这两个渲染器。

## 9. 铁砧 / 砂轮成本分解

`client/anvil/` 与 `client/grindstone/` 实现猫之瞳驱动的成本分解：

| 包 | 类 | 职责 |
|---|---|---|
| `anvil/` | `AnvilBreakdown` / `AnvilBreakdownCalculator` / `AnvilBreakdownTooltipBuilder` / `AnvilBreakdownTooltipAppender` | 计算等级成本、附魔、修复、重命名、不兼容惩罚、累积惩罚，并构建分解 tooltip 行 |
| `grindstone/` | `GrindstoneBreakdown` / `Calculator` / `Builder` / `Appender` | 预览将被移除的附魔、保留的诅咒、返还经验范围 |

平台客户端在 tooltip 事件中调用 `appendIfApplicable`，**仅当玩家持有猫之瞳时**追加分解行。

## 10. 附魔揭示客户端

客户端仅负责展示：`EnchantmentRevealClientState` 持有服务端下发的完整附魔候选列表（`SyncEnchantmentRevealListPayload`），`EnchantmentScreenMixin` 在附魔台界面渲染完整候选而非原版的一条提示。揭示的触发机制、条件扩展与服务端权威计算以 [附魔系统](enchantment.md) 为权威。

## 11. Toast 通知

`JournalUnlockToast` 弹出三类通知：表解锁（`addTableUnlocks`）、物品解锁（`addItemUnlocks`）、100% 完成奖励（`addTableCompletion`）。由 `ArchaeologyJournalClientState` 的解锁通知回调触发，平台客户端在初始化时注册回调桥接。`CatBondToast` 由 `HandOfCatClientState` 触发（羁绊阶段变化）。

## 12. 按键绑定

[`ModKeyBindings`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/keybind/ModKeyBindings.java) 定义按键（**默认键以代码为准**）：

| 用途 | 默认键 |
|---|---|
| 可疑解析仪扫描等级切换 | `V` |
| 打开考古笔记；在背包/JEI 物品上按下时按注册名搜索 | `C` |
| 切换猫之威慑开关 | 未绑定 |
| 切换轻步开关 | 未绑定 |
| 打开/关闭扫描仪 HUD | `H` |

Fabric 用 `KeyBindingHelper.registerKeyBinding`，NeoForge 用 `RegisterKeyMappingsEvent`；玩家可在控制设置中自由修改。威慑/轻步切换通过 C2S payload 发到服务端处理（`CatPassiveAbilities.onDeterrenceToggle` / `onLightStepToggle`）。

`ArchaeologyJournalKeyHandler` 还处理 GUI 内的 `C` 键：Fabric 通过 `ScreenKeyboardEvents`、NeoForge 通过 `ScreenEvent.KeyPressed.Pre` 接入。它**优先读取 JEI 悬停原料，否则读取原版容器槽位**，并用 `JournalSearchQuery.forItemId` 以完整物品注册名预检搜索结果；存在结果时用初始搜索条件打开手册。

## 13. 客户端 Mixin

`mixin/client/` 的 5 个 common mixin（注入点清单见 [Mixin](../foundation/mixin.md)）用途：`AbstractContainerScreenAccessor`（tooltip 渲染访问容器屏幕内部字段）、`EditBoxMixin`（搜索框行为）、`EnchantmentScreenMixin`（附魔揭示渲染）、`ClientLanguageMixin`（服务端补充名称动态生效，机制见 [战利品表系统](loottable.md)）、`HumanoidPanningMixin`（第三人称淘盘动画）。Fabric 另有 `ItemInHandPanningMixin`。

## 14. 扩展点：新增渲染器 / 面板 / HUD / 分解 / Toast / 按键

- **新增实体渲染器**：在 `ModEntityRenderers.REGISTRY_MANIFEST` 加条目，在 `ModModelLayers` 加模型层。
- **新增 GUI 面板**：在 `panel/` 实现，由对应 `screen/` 组合；实现 `LayoutAware` / `UiStateful` 并在创建处向 `UiPanelRegistry` 注册一次。
- **新增 HUD 元素**：参考 `CatFavorHud`，在平台客户端 HUD 事件中调用。
- **新增 tooltip 分解**：参考 `AnvilBreakdownTooltipAppender`，在 tooltip 事件中按条件追加；取色只从 `TooltipBuilder` 取（见 [文本格式规范](../foundation/text-format.md)）。
- **新增 Toast**：参考 `JournalUnlockToast`，在 `ArchaeologyJournalClientState` 注册回调。
- **新增按键**：在 `ModKeyBindings` 加 `KeyMapping`，在客户端 tick 中处理；**默认键不要写进文档当断言**，以代码为准。
- **新增模态**：必须走 `OverlayLayer`（它同时只开一个），并在六类输入入口先分发给它；打开时用 `open(overlay, opener)` 交接焦点：**只有 opener 当前确实持有焦点**（键盘到达）才登记为返回焦点，关闭后还给它；鼠标点击不夺取焦点，因此鼠标打开的模态关闭后不会留下轮廓；大图 / 内容查看器优先用 `UiLightbox` + `LightboxOverlay`，自绘内容实现 `UiLightbox.Content`，图片直接用 `LightboxImage` + `UiImageView`。
- **新增可滚动列表 / 控件组**：滚动内容用 `UiScrollView` + `UiControlGroup`（稳定 key 复用，不要逐帧新建控件），落位用 `UiLinearLayout`；结构色取 `UiControlStyle`；需要键盘导航时把可聚焦目标按视觉顺序交给 `UiFocusManager`，原生输入框走宿主侧 `UiFocusTarget` 适配器。

## 15. 约束与陷阱

- **服务端严禁引用 `client/`**：客户端类只在客户端入口或被环境守卫的路径引用。
- **超宽文本一律走悬停滚动**（`TextScroll` / `ScrollTextHelper` / `UiControl`），**不要静默截断**——截断会把"读不到"伪装成"没内容"。
- **不要在逐帧路径创建节点、列表或 lambda**：`setContent(revision, supplier)` 只在版本变化时执行构建器，业务方要持有稳定构建器并为所有影响内容的输入维护版本。
- **Frame 只允许一层**，禁止 Frame 内再嵌套 Frame。
- **`GuiGraphics.enableScissor` 不读取 pose**，因此 kit 在设置 scissor 前手动把 pose 的轴对齐平移/缩放应用到视口矩形；**不支持旋转、错切、透视**。`UiLightbox.Content` 的实现同样必须把绘制严格裁剪在给它的视口内（外壳只负责把视口写进去）。
- **kit 不负责输入分发优先级、浮层或状态持久化**，这些属于 `OverlayLayer` 与宿主；焦点与键盘导航由 `UiFocusManager` 提供（`UiControlGroup` 只把可聚焦控件交给它；纯标签 `action == null` 不进 Tab 序列）。kit 的灯箱外壳也不依赖 `OverlayLayer`，要接进模态层必须经 `LightboxOverlay` 适配器，保持「宿主层 → kit」单向依赖。
- **取色只从语义色表取**：`TooltipBuilder`（tooltip/Jade）或 `UiTextPalette`（GUI 自绘），不允许在渲染点直接挑色。
- **客户端不推断条件语义**：条件树与状态词均由服务端派生下发（见 [战利品表系统](loottable.md)）。
- **GUI 内 `C` 键要优先读查看器输入的物品**，否则 JEI 打开时会退化成读容器槽位。
- **服务端补充名称只驻留当前连接内存**，断开即清除，不写客户端全局配置。
- **滚动视口的两种坐标成对使用**：`UiScrollView` 的 `push` / `pop` 与 `toContentX` / `toContentY` 必须成对；指针不在视口内时不要拿宿主坐标直接命中控件。
- **控件组只在宿主决定的优先级内消费输入**：滚动条先于内容、原生输入框先于控件组；`mouseReleased` 只结束按压捕获，不在释放时激活；鼠标点击**不夺取焦点**，焦点只在键盘导航与宿主显式请求时改变。

## 16. 相关文档

- [笔记 GUI 内部机制](../internals/journal-ui-internals.md) —— 分层职责、UI kit、场景页与模态、面板状态
- [文本格式规范](../foundation/text-format.md) —— 语义色表、五段式、本地化键命名
- [架构总览](../foundation/architecture.md) —— 客户端初始化流程
- [考古笔记系统](journal.md) —— 客户端状态的同步来源
- [战利品表系统](loottable.md) —— 目录网格与追踪管理页的数据口径
- [网络与同步](../foundation/network.md) —— S2C payload 与接收器
- [猫族关系系统](cat-favor.md) —— HUD 数据来源
- [附魔系统](enchantment.md) —— 附魔揭示
- [淘洗系统](panning.md) —— 淘盘动画、水声与贴水波光
- [Mixin](../foundation/mixin.md) —— 客户端 mixin
- [工具栏图标图集](../internals/toolbar-icon-atlas.md) —— 图标槽位与 UV
