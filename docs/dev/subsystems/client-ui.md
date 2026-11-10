# 客户端与 GUI

> `client/` 包的架构：客户端初始化、状态管理、考古笔记 GUI 层级、HUD、渲染、铁砧/砂轮成本分解、附魔揭示展示与 Toast 通知。
> 本文件是**客户端通用基础设施**（状态框架、按键、Toast、HUD/渲染接入）与**考古笔记 GUI** 的唯一权威；GUI 内部分层、UI kit 契约、场景页与模态、面板状态持久化见 [笔记 GUI 内部机制](../internals/journal-ui-internals.md)。各玩法子系统的客户端表现，其**机制**权威在对应子系统文档（猫 HUD 数据流见 [猫族关系系统](cat-favor.md)、淘盘动画与水声见 [淘洗系统](panning.md)、附魔揭示服务端机制见 [附魔系统](enchantment.md)），本篇只保留客户端侧的接入方式与渲染细节。

## 1. 代码地图

| 层 | 位置 |
|---|---|
| 入口 | `UnsuspiciousBlockFabricClient` / `UnsuspiciousBlockNeoForgeClient`（两端职责对称） |
| UI 根与分层 | `client/ui/ArchaeologyJournalUi`（注册 opener）、`JournalBookBackground`、`PotteryPreviewRenderer`；分层目录 `client/ui/{entry,layout,panel,screen,support,toast,widget,tooltip,overlay,theme}/`（清单见 [笔记 GUI 内部机制](../internals/journal-ui-internals.md)） |
| 独立 UI kit | Maven 消费 `com.meteorite.meteosuikit.api`；`client/ui/theme/` 提供宿主主题与控件皮肤 |
| 客户端状态 | `client/state/`：`ArchaeologyJournalKeyHandler`、`HandOfCatClientState`、`CatHandClientState`、`ReaderScanHudState`、`SuspiciousReaderClientState`、`ScenarioSimulationClientState`、`SimulationPreferenceStore`、`JournalHoveredItemProvider` |
| UI 侧状态 | `client/ui/support/`：`ArchaeologyJournalClientState`、`LootTableManagementClientState`、`ClientLootTableLanguageStore`、`JournalUiPreferencesStore`、`UiPanelRegistry`、`UiTextPalette`… |
| HUD 与模型 | `client/hud/`：`CatFavorHud`、`SuspiciousReaderHud`；`client/model/MessengerCatClothesModel`、`client/renderer/layer/`（项圈与职业装饰层） |
| 渲染 | `client/renderer/`：`ModEntityRenderers`、`ModModelLayers`、各实体渲染器、`SuspiciousReaderRangeHighlight`、`CatFavorShieldRenderer`、`ShimmerSurfaceRenderer`（淘洗，见 [淘洗系统](panning.md)） |
| 分解预览 | `client/anvil/`、`client/grindstone/`（各含 `Breakdown` / `Calculator` / 行构建器 / `TooltipAppender`）；两者共用的编排层为 `client/tooltip/BreakdownPreview` |
| tooltip 基础 | `text/TooltipBuilder`（语义色表唯一出口，规范见 [文本格式规范](../foundation/text-format.md)）；`client/tooltip/` 另有 `BreakdownPreview` 与客户端桥实现 `ClientTooltipHooks` |
| 按键与网络 | `client/keybind/ModKeyBindings`；S2C 接收器遍历 `ModPayloads.Client.S2C_PAYLOADS`（清单见 [网络与同步](../foundation/network.md)） |
| Mixin | `mixin/client/`：`AbstractContainerScreenAccessor`、`EnchantmentScreenMixin`、`ClientLanguageMixin`、`HumanoidPanningMixin`；Fabric 另有 `ItemInHandPanningMixin`。全清单见 [Mixin](../foundation/mixin.md) |
| 平台差异 | 按键注册：`KeyBindingHelper` vs `RegisterKeyMappingsEvent`；GUI 内按键：`ScreenKeyboardEvents` vs `ScreenEvent.KeyPressed.Pre`；第一人称淘盘动画：事件 vs mixin；世界/HUD 渲染阶段事件名不同 |

共享安装与连接生命周期由 `client/ModClientLifecycle` 负责：`install` 安装 tooltip 桥、语言存储、淘洗表现、笔记打开器和通知；`tick` 驱动客户端状态；`disconnect` 统一复位。Fabric 客户端初始化与 NeoForge client setup 各绑定一次；按键、渲染、菜单和 tooltip 工厂仍遵守平台事件相位。

## 2. 数据流

**UI 打开**

```text
ArchaeologyJournalUi.registerOpener(state -> Minecraft.setScreen(new ArchaeologyJournalScreen(state)))
  ├─ 玩家右键考古笔记物品 或 按 C 键（ArchaeologyJournalKeyHandler）
  └─ 打开 ArchaeologyJournalScreen，传入 ClientState
```

`ArchaeologyJournalScreen` 从 `ArchaeologyJournalClientState` 读目录/进度/日志数据，通过 `JournalViewModel` 组织视图，渲染 `JournalBookBackground` + 各 panel。管理与帮助入口由 kit `UiControl` 配合 `JournalControls` 的侧签皮肤提供；外链通过 kit 模态展示完整地址、复制和确认打开。

**状态同步**

```text
S2C payload ──► ArchaeologyJournalClientState / *ClientState
                  ├─ 目录：哈希不一致 → 请求全量；进度：增量包 + 间隙检测（丢包 → 全量重同步）
                  ├─ 日志：分片快照会话
                  └─ 表解锁 / 物品解锁 / 100% 完成 → 回调桥接 → JournalUnlockToast
客户端 tick ──► 按键处理、扫描高亮、客户端状态清理
断连 ──► 落盘 UI 偏好 → resetOnDisconnect（含各客户端状态 reset）
```

## 3. 关键类

| 类 | 职责 |
|---|---|
| `client/ui/support/ArchaeologyJournalClientState` | 考古笔记核心状态（目录/进度/日志缓存 + 解锁通知 + UI 偏好） |
| `client/ui/screen/ArchaeologyJournalScreen` / `JournalViewModel` | 主屏幕：组合书本背景与各面板；视图状态：分类切换、展开路径、右页标签页 |
| 独立库 `UiScreen` / `UiDocument` | 根输入生命周期与声明式文档排版、裁剪和命中 |
| `client/ui/overlay/JournalOverlays` | 当前场景 kit 层的所有权及准备/关闭业务 |
| `client/ui/panel/ScenarioDetailPanel` / `ScenarioConditionLayout` | 短场景名、只读工具图标与参数读数、分组条件树；局部折行并保留逻辑树 |
| `client/ui/overlay/ScenarioRecommendationOverlay` | 物品/子表入口推荐的变更预览，应用参数后仍需显式计算 |
| `client/ui/overlay/ScenarioParamsOverlay` | 幸运值数值编辑、工具与附魔列表、独立草稿与显式提交 |
| `client/ui/support/UiPanelRegistry` / `UiStateful` / `LayoutAware` | 面板的布局/状态恢复注册表与接口 |
| `client/ui/support/JournalUiPreferencesStore` | UI 偏好与面板状态持久化（按存档、按玩家隔离） |
| `client/hud/CatFavorHud` / `SuspiciousReaderHud` | 猫之恩惠 HUD；解析仪扫描汇总与目标详情 HUD |
| `client/state/ReaderScanHudState` | 扫描结果、图标分组、聚焦目标与描边的统一生命周期 |
| `client/keybind/ModKeyBindings` | 客户端按键定义与注册清单 |
| `text/TooltipBuilder` | 语义色常量 + 五段式构建器；客户端能力（Shift 检测、打开界面、猫族缓存）经 `text/ClientTooltipBridge` 桥接 |

## 4. 客户端初始化

两个平台客户端入口职责高度对称（见 [架构总览](../foundation/architecture.md) 的客户端初始化），都完成：按键注册、渲染器/模型层注册、Screen 注册、S2C 接收器注册、Tooltip 组件注册、HUD/世界渲染注册、客户端 tick 驱动、断连重置，以及 `ClientTooltipBridge.install(...)` 与三条解锁/完成通知回调的注册。关键清单模式（与 [注册架构](../foundation/registration.md) 一致，平台客户端自动遍历）：`ModEntityRenderers.REGISTRY_MANIFEST`（实体渲染器）、`ModModelLayers`（模型层 `LayerDefinition`）、`ModPayloads.Client.S2C_PAYLOADS`（S2C 接收器）。

## 5. 客户端状态管理

| 状态类 | 职责 |
|---|---|
| `ArchaeologyJournalClientState` | 考古笔记核心状态（目录/进度/日志缓存 + 解锁通知 + UI 偏好） |
| `LootTableManagementClientState` | 服务端权威战利品表索引、权限和多语言名称快照；断连时 `reset()` 清条目/翻译与编辑权限 |
| `ClientLootTableLanguageStore` | 游戏资源来源索引、服务端名称内存快照和动态语言补充 |
| `SuspiciousReaderClientState` / `ReaderScanHudState` | 解析仪按键与扫描等级切换；整批扫描汇总、目标聚焦与世界描边生命周期 |
| `HandOfCatClientState` / `CatHandClientState` | 猫之手缓存（favor/lives）与按键处理状态 |
| `EnchantmentRevealClientState` | 附魔揭示客户端状态（完整候选列表） |
| `JournalHoveredItemProvider` / `ArchaeologyJournalKeyHandler` | 可选物品查看器（JEI）向笔记快捷键提供悬停物品（空栈表示输入被占用）；笔记按键处理 |

### 5.1 ArchaeologyJournalClientState 核心

这是最复杂的客户端状态类，管理服务端推送的所有数据并驱动 UI：

- **连接数据**：`ConnectionState` 持有目录、结构、进度、哈希与同步标记；连接切换整体替换。`JournalClientConnection` 持有原连接、epoch 和 JournalMirror，tick 加载偏好后应用同连接报文；平台回调只入队。
- **同步处理**：`receiveCatalogHash` 与本地比对，不一致则请求全量目录；`receiveState`（全量）**首次同步不弹 Toast**（避免重进世界重复通知），后续检测新解锁；`receiveStateIncremental`（增量）做**间隙检测**——`incomingRevision > lastNotifiedRevision + 1` 说明中间丢包，请求全量重同步，仅对变更表做 Diff；`requestFullStateWithCooldown` 限流 2s 防请求风暴。
- **解锁通知**：`tableUnlockNotifier` / `itemUnlockNotifier` / `tableCompletionNotifier` 是回调接口，由平台客户端注入（桥接到 `JournalUnlockToast`），解耦状态层与 UI 层；`detectAndNotifyUnlocks` 比较新旧状态，**仅对追踪目录中的表**触发。
- **右页组合**：`RightPageContainer` 的页描述统一内容、书签和 chrome；`ArchaeologyPage` 组合网格与场景栏，LOG 按业务模式解析内容。Screen 通过统一书签入口和 `ReadingState` 保存/恢复接线；分页失效仍由面板处理，扩展路线见 [笔记 GUI](../internals/journal-ui-internals.md)。
- **世界偏好**：`WorldPreferences` 持有排序、搜索、收藏、分组、右页与运行期目录选择。切换先冻结旧路径待写，再替换默认值并加载新路径；无文件时保留默认值。应用级 notifier 与失效计数保留；`persistenceSuppress` 只用于加载。
- **读取与保存**：getter 读取内存或保护性副本；正常加载、协议推进、超时与落盘仅在客户端 tick，退出/关闭时显式 flush。目录投影版本由目录与场景 revision 单向合成；ViewModel 和 WelcomeStatsPanel 同时识别连接 epoch，按版本复制日志。

## 6. 笔记 GUI

`client/ui/` 按职责分层（`entry/` `layout/` `panel/` `screen/` `support/` `toast/` `widget/` `tooltip/` `overlay/` `theme/`）。**分层职责、追踪管理页、网格页展示优先级链、文本配色约束、声明式 UI kit 契约、场景详情页与模态交互、面板状态与偏好持久化见 [笔记 GUI 内部机制](../internals/journal-ui-internals.md)。**

场景详情页只展示条件树，按场景假设与子表入口分组，在独立滚动视图中阅读，复杂内容可进入灯箱；物品和概率统一由网格页展示。未发现物品只显示占位状态，tooltip 不泄露概率或路径，普通物品卡片不提供点击跳转。场景列表显示短名、状态与摘要，浏览后显式激活选择；参数提供“仅应用”和“应用并计算”。机制细节以内部文档为准。

新增面板在 `panel/` 实现，由 `screen/` 的实际 `UiChildren` 装配；布局与持久化按需接 `LayoutAware` / `UiStateful`。控件、滚动、焦点、模态与灯箱直接消费独立 kit API，`JournalControls` 只提供宿主资源和皮肤。`ArchaeologyJournalScreen` 的唯一 router 选择当前顶层 scope，tooltip 与 JEI 共用命中结果。管理页动作与文件校验仍在 `LootTableManagementActions` / `LootTableImportValidator`。接入契约见 [UI kit 公开 API 与兼容策略](../internals/ui-kit-api.md)，业务与状态口径见 [笔记 GUI 内部机制](../internals/journal-ui-internals.md)。

## 7. HUD

[`CatFavorHud`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/hud/CatFavorHud.java) 在快捷栏**左侧**渲染猫之恩惠信息：

- 羁绊值与当前阶段（`CatBondStage`，带阶段颜色）、残存九命命数（> 0 时显示）；数据来自 `SyncCatFavorPayload` 同步的 `HandOfCatClientState`。
- 未封顶时图标按呼吸节奏改变 tint alpha；tint 与混合状态在本次绘制内成对恢复，不污染后续 HUD。

[`SuspiciousReaderHud`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/hud/SuspiciousReaderHud.java) 采用左下角汇总与准星侧下方目标详情两个面板，**独立于 Jade**；不检测、隐藏或替代 Jade HUD，默认位置避开其顶部区域。

- **汇总**：新扫描整批替换旧结果，显示仍有效的可疑方块/容器总数，按物品 ID、展示名及封存来源分组，最多展示三组物品；其余组数和空方块数显示在底部。所有目标保留用于定位，不受展示行数限制。空范围扫描同样发送 `SyncReaderScanResultPayload`，清除旧标记并显示"未发现目标"，不再写聊天栏。
- **目标详情**：仅主手或副手持扫描仪时显示，换下立即隐藏；沿相机视线在 32 格内与已扫描方块包围盒相交，忽略表层沙子的遮挡，优先最近目标，切换确认延迟 100ms。详情仅显示物品/数量、距离、相对玩家脚部的上下格数，封存者按需追加；容器仅显示内容未知，不解析容器战利品。长物品名最多两行，数量保留在首行；其余长文本按像素宽度省略。
- **布局与生命周期**：两个面板按文本、图标、数量与内边距自适应宽度（最大 180 GUI 像素，受窗口可用宽度限制）；详情在准星右下方，靠边时向内收，小窗口两面板相交时优先显示详情。打开其他 GUI、F1 时不绘制；`H` 仅控制扫描仪两个面板及聚焦强调，不影响 Jade 或基础世界标记。`ReaderScanHudState` 统一持有结果、图标、分组与聚焦目标，结果和世界描边共用单调时钟的 10 秒寿命（淡入 150ms、淡出 500ms），持续聚焦可延长阅读，整批最多保留 30 秒；新扫描重新计时，跨维度、断线、到期清理。每客户端 tick 仅检查已加载区块的缓存目标，移除方块实体消失或区块卸载的目标并更新汇总，**不加载新区块**。
- **渲染预算与原版字体边界**：图标和分组在接收/结果变化时缓存，目标选择每 tick 执行，渲染只读取快照；派生文本、测量宽度与面板几何随快照缓存，键为语言实例与窗口尺寸。基础可疑方块描边为高不透明度青色、空方块弱灰色、容器紫色，取消连续闪烁，聚焦目标增加浅色外框；透视绘制使用已有无深度测试 `RenderType`（不写深度），线宽增至 3 像素。alpha 字节低于 4 时跳过 HUD 绘制，避免原版字体强制恢复不透明。

平台客户端在 HUD 渲染事件中调用这两个 HUD 的 `render(guiGraphics)`。图标、扫描面板表面与文字裁剪使用 kit；显示门禁、快照、锚点和淡出仍由 HUD 业务持有。

## 8. 渲染

### 8.1 实体渲染

`ModEntityRenderers` 沿用清单模式注册实体渲染器：

| 渲染器 | 实体 | 说明 |
|---|---|---|
| `MessengerCatRenderer` | 信使猫猫 | 灵体渲染 + 项圈层 + 职业装饰层 |
| `SwordsmanCatRenderer` | 剑士猫猫 | 灵体渲染（钻石剑装饰待实现） |
| `MerchantCatRenderer` | 猫猫商人 | 职业装饰 |
| `LanternPetRenderer` | 灵魂提灯宠物 | 灵魂灯笼外形 |

**灵体半透明与职业装饰**：`MessengerCatRenderer` 用自定义 `MultiBufferSource` 包装原版猫皮肤，统一染成淡蓝青色半透明（alpha 由 `SpiritCat.getRenderAlphaProgress` 驱动，实现显现/消散渐变），并在其上叠加 `MessengerCatCollarLayer`（复刻原版项圈层）与 `MessengerCatClothesLayer`（职业装饰）。`MessengerCatClothesModel` 不自行播放动画——它的 `head` / `body` 枢轴与原版 `CatModel` 完全对齐，渲染器把**同一份烘焙根部件**交给两者共享，装饰层每帧用 `ModelPart.copyFrom` 把原版骨骼姿态拷到装饰骨骼上，因此行走、坐下、躺卧等全部原版猫动画自动生效；装饰贴图为独立的 `textures/entity/cat/messenger_cat_clothes.png`（64x64），与随机抽取的原版猫皮肤分两次绘制。

### 8.2 方块实体与世界渲染

`PotteryWheelRenderer` 渲染陶轮方块实体的内容。

`SuspiciousReaderRangeHighlight` 在半透明方块渲染之后绘制扫描结果透视描边与聚焦强调（数据来自 `SyncReaderScanResultPayload` 同步的 `ReaderScanHudState`）；`CatFavorShieldRenderer` 在九命触发时绘制保护罩（球面顶点静态初始化，仅逐帧写 alpha）。平台客户端在 `AFTER_TRANSLUCENT` / `AFTER_TRANSLUCENT_BLOCKS` 阶段调用这两个渲染器。

## 9. 铁砧 / 砂轮成本分解

`client/anvil/` 与 `client/grindstone/` 实现猫之瞳驱动的成本分解：

| 包 | 类 | 职责 |
|---|---|---|
| `anvil/` | `AnvilBreakdown` / `AnvilBreakdownCalculator` / `AnvilBreakdownTooltipBuilder` / `AnvilBreakdownTooltipAppender` | 计算等级成本、附魔、修复、重命名、不兼容惩罚、累积惩罚，并构建分解 tooltip 行 |
| `grindstone/` | `GrindstoneBreakdown` / `Calculator` / `Builder` / `Appender` | 预览将被移除的附魔、保留的诅咒、返还经验范围 |

两套 Calculator 与 Breakdown record 保持独立（算法不同），共用的编排（`appendIfApplicable` 通用形态、`hasRevealItem`、`buildInputPenalty`）在 `client/tooltip/BreakdownPreview`。平台客户端在 tooltip 事件中调用 `appendIfApplicable`，**仅当玩家持有猫之瞳时**追加分解行。

## 10. 附魔揭示客户端

客户端仅负责展示：`EnchantmentRevealClientState` 持有服务端下发的完整附魔候选列表（`SyncEnchantmentRevealListPayload`），`EnchantmentScreenMixin` 只反推悬停槽位、取缓存并委托 `client/enchantment/EnchantmentRevealTooltipBuilder` 重建 tooltip 行。揭示的触发机制、条件扩展与服务端权威计算以 [附魔系统](enchantment.md) 为权威。

## 11. Toast 通知

`JournalUnlockToast` 弹出三类通知：表解锁（`addTableUnlocks`）、物品解锁（`addItemUnlocks`）、100% 完成奖励（`addTableCompletion`）。由 `ArchaeologyJournalClientState` 的三个通知回调触发，平台客户端在初始化时注册回调桥接。`CatBondToast` 由 `HandOfCatClientState` 触发（羁绊阶段变化）。Toast 使用 kit 面板、文本裁剪和图标，保留原 token、队列合并、轮换与展示时长。标本箱富 tooltip 使用 kit Sprite 和局部物品层，继续遵守 `ClientTooltipComponent` 的尺寸与生命周期。

## 12. 按键绑定

[`ModKeyBindings`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/keybind/ModKeyBindings.java) 定义按键（**默认键以代码为准**）：

| 用途 | 默认键 |
|---|---|
| 可疑解析仪扫描等级切换 | `V` |
| 打开考古笔记；在背包/JEI 物品上按下时按注册名搜索 | `C` |
| 切换猫之威慑开关 | 未绑定 |
| 切换轻步开关 | 未绑定 |
| 打开/关闭扫描仪 HUD | `H` |

Fabric 用 `KeyBindingHelper.registerKeyBinding`，NeoForge 用 `RegisterKeyMappingsEvent`；玩家可在控制设置中自由修改。威慑/轻步切换通过 C2S payload 发到服务端处理（`CatPassiveAbilities.onDeterrenceToggle` / `onLightStepToggle`）。`ArchaeologyJournalKeyHandler` 还处理 GUI 内的 `C` 键：Fabric 通过 `ScreenKeyboardEvents`、NeoForge 通过 `ScreenEvent.KeyPressed.Pre` 接入；它**优先读取 JEI 悬停原料，否则读取原版容器槽位**，并用 `JournalSearchQuery.forItemId` 以完整物品注册名预检搜索结果，存在结果时用初始搜索条件打开手册。

## 13. 客户端 Mixin

`AbstractContainerScreenAccessor` 提供 tooltip 所需容器字段；`EnchantmentScreenMixin` 接入附魔揭示；`ClientLanguageMixin` 动态补充服务端名称并失效资源索引；`HumanoidPanningMixin` 接入第三人称淘盘动画。Fabric 另有 `ItemInHandPanningMixin`。注入点与完整清单见 [Mixin](../foundation/mixin.md)。

## 14. 扩展点：新增渲染器 / 面板 / HUD / 分解 / Toast / 按键

- **新增实体渲染器**：在 `ModEntityRenderers.REGISTRY_MANIFEST` 加条目，在 `ModModelLayers` 加模型层。
- **新增 GUI 面板**：在 `panel/` 实现，由对应 `screen/` 组合；实现 `LayoutAware` / `UiStateful` 并在创建处向 `UiPanelRegistry` 注册一次。
- **新增 HUD 元素 / 按键**：参考 `CatFavorHud`，在平台客户端 HUD 事件中调用；按键在 `ModKeyBindings` 注册并在客户端 tick 处理，**默认键不要写进文档当断言**。
- **新增 tooltip 分解**：参考 `AnvilBreakdownTooltipAppender`，在 tooltip 事件中按条件追加；取色只从 `TooltipBuilder` 取（见 [文本格式规范](../foundation/text-format.md)）。
- **新增 Toast**：参考 `JournalUnlockToast`，在 `ArchaeologyJournalClientState` 注册回调。
- **新增模态**：使用 `UiModalStack`、`UiOverlay` 或 `UiLightbox`；将实际作用域交给根 router，业务清理放在层关闭回调。
- **新增可滚动列表 / 控件组**：使用 `UiListView` 或 `UiScrollView` + `UiControlGroup`，按业务 key 复用；焦点由实际成员树贡献。
- **新增容器自有 UI**：保留 Menu/Slot，使用唯一 `UiInputBridge`，未消费再回退容器；只读装饰使用 kit 绘制能力。

## 15. 约束与陷阱

- **服务端严禁引用 `client/`**：客户端类只在客户端入口或被环境守卫的路径引用；两端可达的物品/方块类需要客户端能力（Shift 检测、打开笔记界面、猫族缓存）时，一律走 `text/ClientTooltipBridge`，实现由客户端入口安装。
- **超宽文本**：交互区使用 kit 滚动或完整 tooltip；非交互行使用带省略号裁剪。
- **缓存失效**：业务树按目录/请求版本构建，字体、语言和资源变化时刷新文本与几何；不逐帧重建节点。
- **唯一输入所有权**：当前 scope 持有焦点和持续交互；隐藏、禁用、切域和关屏结束会话。按钮在配对释放时激活。
- **坐标与裁剪**：书本绘制和命中共用逻辑坐标；带 pose 的裁剪使用 kit 层，模型回调只使用传入的局部 `GuiGraphics`。
- **端与平台隔离**：kit 仅客户端消费，common 不导入 loader，宿主仅导入库 `api`。边界脚本见 [UI kit 公开 API 与兼容策略](../internals/ui-kit-api.md)。
- **取色**：tooltip/Jade 取 `TooltipBuilder`，GUI 取主题和 `UiTextPalette`。
- **业务权威**：条件语义由服务端 DTO 提供；目录、概率、网络和持久化规则留在各自业务所有者。
- **可选联动**：`C` 优先读查看器物品，外部槽位与普通 tooltip 文本保留原协议；服务端名称只驻留当前连接。

## 16. 相关文档

- [笔记 GUI 内部机制](../internals/journal-ui-internals.md) · [UI kit 公开 API 与兼容策略](../internals/ui-kit-api.md) · [文本格式规范](../foundation/text-format.md)
- [架构总览](../foundation/architecture.md) · [网络与同步](../foundation/network.md) · [Mixin](../foundation/mixin.md) · [注册架构](../foundation/registration.md)
- [考古笔记系统](journal.md) · [战利品表系统](loottable.md) · [猫族关系系统](cat-favor.md) · [附魔系统](enchantment.md) · [淘洗系统](panning.md) · [工具栏图标图集](../internals/toolbar-icon-atlas.md)
