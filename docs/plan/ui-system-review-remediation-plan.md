# UI 系统审查问题修复计划

> 状态：**规划完成，尚未实施代码修复**；建立于 2026-09-28。
> 本计划依据 [UI 系统审查报告](../todo/ui-system-review.md) 的**复核后版本**。当前机制的唯一权威仍是 [客户端与 GUI](../dev/subsystems/client-ui.md)、[笔记 GUI 内部机制](../dev/internals/journal-ui-internals.md) 与 [UI kit 公开 API](../dev/internals/ui-kit-api.md)；本文只记录改造背景、依据、取舍和执行顺序，不构成机制权威。
> 修订记录：2026-09-28 首版。纳入用户实机复现的搜索框焦点 bug；排除报告中已撤销的 O-5；原报告的审查者统计是历史值，不作为本计划的任务数或验收数。
> 修订记录：2026-09-28 第二版。**本轮排除灵体猫（SpiritCat）全部客户端表现条目**：H-5/O-2、R-3 及 S0-3 中"连接衣服层检查"的部分不在本次实施范围，理由与替代口径见 [2.4](#24-本轮排除灵体猫待重设计)；受影响的编号在 4.5 标注"本轮不做"。
> 路径约定：下文 `client/...`、`mixin/...`、`item/...` 相对 `common/src/main/java/com/meteorite/unsuspiciousblock/`；平台入口分别在 `fabric/src/main/java/`、`neoforge/src/main/java/`。行号是规划时定位，实施时需重新核对。

## 一、现状

> 本节记录的是**实施前状态**，不表示这些问题已经修复。

### 1.1 现有实现与相邻计划

| 范围 | 实施前状态 | 依据等级 |
|---|---|---|
| 笔记屏幕与搜索 | `ArchaeologyJournalScreen` 组合书页和输入事件，`CatalogToolbar` 创建原生 `EditBox`；点击放大镜的回调会重建控件。 | 源码核实：`client/ui/screen/ArchaeologyJournalScreen.java:199-230,523-532,631-654`、`CatalogToolbar.java:110-125,292-321` |
| UI kit 与模态 | `UiControlGroup` 默认 `PARCHMENT`；`UiLightbox` 未覆盖该默认值，控制栏在浅底上使用近白字。 | 源码核实：`client/ui/kit/UiControlGroup.java:33`、`UiLightbox.java:107,114,136-143,283-316`；对比度为公式推算 |
| 客户端状态与渲染 | `LootTableManagementClientState` 没有断连重置；`CatFavorHud` 的着色器颜色仅在封顶分支恢复；三个猫渲染器固定返回灵体 `RenderType`。 | 源码核实：`client/ui/support/LootTableManagementClientState.java:12-29`、`client/hud/CatFavorHud.java:73-87`、`client/renderer/MessengerCatRenderer.java:57-60` 等 |
| 逐帧工作 | 日志详情卡在渲染中间接调用 `getCatalog()`，后者每次重新执行目录 overlay；控件裁剪、文字宽度、日志图标等路径也有重复工作。 | 源码核实：`client/ui/panel/LogDetailPanel.java:258-259`、`client/ui/support/JournalFormatHelper.java:41-51`、`ArchaeologyJournalClientState.java:277-279`、`client/ui/kit/UiControl.java:144-158` |
| 既有改造 | [考古笔记 GUI 翻新计划](journal-gui-refresh-plan.md) 的 P0–P2 已落地，后续体验核验仍待完成。本计划处理本次审查发现的具体缺陷，并与该计划的逐页验收共享结果，不重复改写已完成的场景页结构。 | 文档核实：`docs/plan/journal-gui-refresh-plan.md:3,131-145,192-208` |

### 1.2 已确认缺陷与耦合点

1. **搜索焦点是已观察到的 bug**：用户实机确认，点放大镜展开后不能直接输入，必须再点一次输入框。`CatalogToolbar.java:312-316` 只设置新输入框自身的焦点；原版 `ContainerEventHandler.mouseClicked` 会在按钮回调结束后把宿主焦点设给被点击的旧按钮。证据等级：**用户实机确认 + 项目/原版源码核实**（H-1/O-3）。
2. **浅色控制栏与低对比度文字**：`UiLightbox.java:107,114` 的默认组合算得约 1.06:1；另外六处文字颜色在报告使用的底色上算得低于 4.5:1。证据等级：**源码核实 + 公式推算**；真实贴图、混合后的像素还需截图复核（H-2/H-3、I-1/I-2）。
3. **渲染状态与实体语义**：`CatFavorHud.java:73-87` 存在未成对恢复的颜色状态（H-4/O-1，本轮修复）；三个猫渲染器忽略 `bodyVisible/translucent/glowing` 入参（H-5/O-2，**本轮不做**，见 2.4）。证据等级：**项目与原版源码核实**；实际污染范围与灵体隐身设计意图尚未实机裁定。
4. **性能问题不能只凭调用次数定优先级**：`ScenarioSimulationClientState.overlay` 的重建链成立，但 P-2～P-14 的绝对帧耗时、玩家常见背包布局和平台饰品栏成本仍缺样本。证据等级：**源码核实 + 性能影响推断**（H-6/P-1～P-14）。
5. **跨层改动会互相影响**：管理页同时涉及列表几何、文件导入、语言来源和输入快照（A-4/R-2/I-5/I-6/O-6/O-13）；UI kit 的裁剪、焦点和公开 API 有兼容约束（R-7/P-2/O-8/O-9/O-14/O-16）。需要按功能边界分批改动。

### 1.3 报告结论的使用边界

- O-5 已撤销：`ScenarioParams.java:60-71` 在构造点拒绝非有限幸运值，本计划**没有**“修复 NaN 持久化”任务。
- I-3（未发现物品的 tooltip 是否披露概率）、H-5（灵体是否特意无视隐身）、I-7（九命为零的表达）属于设计判断，先确认目标体验，再定代码改法。
- R-4 的五处 `containsMouse` 不是逐字相同：`ScenarioDetailPanel` 的右/下边界用 `<`，另外四处用 `<=`。抽取前须先确定统一边界语义。
- 任何“改善性能”“避免卡顿”的验收，必须有实施前后相同场景的测量；静态减少分配只能证明机制改善。

## 二、目的

### 2.1 可验收的结果

- 点击放大镜后立即可键入；再点击输入框、切页、重建和 C 键带物品打开也遵循明确的焦点规则。
- 灯箱按钮与纸面文字在实际渲染背景下可辨，静态颜色比值满足项目规定的最低对比度；`en_us`、`zh_cn` 同步。
- HUD 绘制后恢复借用的渲染状态；灵体猫对隐身、旁观、发光的表现符合确定的设计；断连后管理页不显示上一连接的内容或编辑权限。
- 目录、搜索、日志和控件渲染减少已测得的重复工作，同时保留目录版本、语言切换、tooltip、裁剪、输入与日志落盘语义。
- 报告内每个有效编号都有“已修复 / 实测无需改 / 设计决定保留 / 后续任务”的去向；`docs/dev/` 与最终代码一致。

### 2.2 架构目标

- `client/ui/kit` 只承担可复用的布局、控件、焦点、裁剪与绘制；宿主处理业务状态、文案、输入优先级和平台事件。
- `common/` 不 import Fabric/NeoForge 平台类；服务端可达路径不直接加载客户端类。先隔离真实加载边界，再决定是否搬迁公共文本构建器。
- 缓存使用可说明的版本键和失效点；资源重载、语言切换、断连、目录增量和模拟选择变化均有对应处理。
- 通过短小、可独立编译和验收的改动推进，不把 62 个表格编号视为一次性重写任务。

### 2.3 明确不做（本轮范围外）

- 不改战利品表概率算法、网络 payload 格式、玩家存档格式或 UI kit 的独立发布/拆包。
- 不把已撤销的 O-5 重新列为缺陷；不因主观代码风格评价而重写正常运行的页面。
- 不预设每个低严重度性能项都值得改；P-7 等先测量，低于噪声或引入较大失效风险时记录“暂不改”及原因。
- 不把现有 [考古笔记 GUI 翻新计划](journal-gui-refresh-plan.md) 已完成的 P0–P2 再实施一次。
- **不做灵体猫（SpiritCat）相关的客户端表现改动**，详见 2.4；本轮不裁定额外可见性规则，也不重构其渲染实现。

### 2.4 本轮排除：灵体猫（待重设计）

> 本节的排除由需求方在 2026-09-28 明确：**灵体猫部分未来要重新设计，本轮不做**。此处只登记范围与仍然成立的静态结论，不构成对现有实现的裁定。

**排除范围（本轮不新增/不重构）**

| 编号 | 原计划动作 | 涉及实现 | 本轮处置 |
|---|---|---|---|
| H-5 / O-2 | 先裁定灵体隐身语义，再恢复 `null` / `outline` 分支 | `client/renderer/MessengerCatRenderer.java`、`SwordsmanCatRenderer.java`、`MerchantCatRenderer.java` 的 `getRenderType` | **不做**；待重设计时一并确定可见性/发光契约 |
| R-3 | 抽公共 `TintedBufferSource`，收敛三份灵体 tint 包装 | 同上三个渲染器的 tint `MultiBufferSource`/`VertexConsumer` 内部类 | **不做**；重复实现随重设计一并消失，提前抽取会与重设计冲突 |
| S0-3 的"衣服层"复核 | 检查衣服层与主体可见性一致 | `client/renderer/layer/MessengerCatClothesLayer.java`、`client/model/MessengerCatClothesModel.java` | **不做**（该层的自发光类型选择同属重设计范围） |
| R-10 的灵体部分 | mod id 字面量统一为 `Constants.MOD_ID` | 上述衣服层/模型文件中的 `"unsuspiciousblock"` 字面量 | **不做**；其余文件（`PotteryWheelModeButton`、`CatBondToast`、`JournalUnlockToast`、`LanternPetModel`、`LanternPetRenderer`）照常处理 |

**仍然成立、但本轮只登记不执行的静态结论**（保留给重设计任务）：

- 三个渲染器一律 `return RenderType.entityTranslucentEmissive(getTextureLocation(entity))`，忽略 `bodyVisible`/`translucent`/`glowing`；原版 `LivingEntityRenderer.getRenderType` 在 `!bodyVisible` 时返回 `glowing ? outline(loc) : null`，调用点对 `null` 不绘制。因此**隐身/旁观实体仍被绘制、发光描边分支永不生效**（报告 H-5 原文）。证据等级：项目/原版源码核实，未实机验证。
- 三份灵体 tint 包装近似同构（约 130 行），任意一处单独修改会扩大三份实现的分叉，故不做增量修补。

**对 4.5 的影响**：H-5/O-2 与 S3-1 中的 R-3 条目标记为"本轮不做（灵体猫待重设计）"，不计入本轮完成条件；其余编号不受影响。

## 三、技术原理

| 路线选择与取舍 | 核实依据、等级 | 实施含义 |
|---|---|---|
| 搜索焦点在**本次鼠标事件结束后**交给新输入框 | 用户实机确认；原版 1.21.1 `ContainerEventHandler.mouseClicked` 在按钮回调返回后执行 `setFocused(clickedChild)`；`CatalogToolbar.java:312-316` 在回调期间建新框。**实机 + 源码核实** | 仅在 `createWidgets()` 中调用 `screen.setFocused(newField)` 仍可能被旧按钮覆盖。宿主可记录一次性焦点请求，在 `super.mouseClicked` 返回后完成交接；重建时只恢复原本属于搜索框的焦点。 |
| 灯箱默认改用暗底，纸面颜色另做集中校正 | `UiControlGroup.java:33` 默认为 `PARCHMENT`；`UiLightbox.java:114` 为近白字；报告的公式值和宿主调用点已核对。**源码 + 推算** | 灯箱构造时明确设置 `DARK`，仍保留调用方 `setStyle` 覆盖能力；纸面文字按每个实际背景取色，不能把一种色值套到所有六处。 |
| 渲染状态成对恢复，并保持图标着色时序 | `CatFavorHud.java:73-87` 的设置/恢复不对称；`GuiGraphics` 可延后提交绘制。**源码核实；实际批次为待验证** | 恢复颜色应放在可靠的收尾路径；先确认 tint 发生在期望的 flush 之前，避免修了状态泄漏却改变图标颜色。 |
| 猫渲染器保留原版可见性分支 | 三个 `getRenderType` 固定返回 emissive；原版 `LivingEntityRenderer` 依 `translucent/bodyVisible/glowing` 返回半透明、正常、outline 或 null。**项目/原版源码核实** | 先明确灵体隐身设计；至少恢复对 outline/null 的处理，连同衣服层检查，不只改主体渲染器。 |
| 先阻断单点 O(目录) 重建，再扩大缓存 | `LogDetailPanel → JournalFormatHelper → getCatalog → overlay` 无门控；`catalogRevision` 已存在。**源码核实** | 优先评估日志结构名直接读取原始目录的窄修复；若仍有广泛重复读取，再给 overlay 建以全部输入变化为边界的只读缓存。 |
| 逐帧优化以测量和视觉回归为门槛 | P-2 的无条件 scissor/flush 是代码事实；P-7 有提前返回；R-7 的两种裁剪公式与 flush 时序不同。**源码核实；收益推断** | 先量化热路径，再分别优化；无裁剪快路径须证明文字与图标都在控件边界内。裁剪实现不可未经同一 GUI scale 场景核对就直接互换。 |
| 日志写盘合并必须保留退出时持久性 | `ArchaeologyJournalLogLocalStore.java:157-168,229-233` 单条路径立即保存；偏好存储已有 tick 合并范式。**源码核实** | 可合并同 tick 的增量，但切世界、断连、关闭客户端前必须 flush；失败不能丢 dirty 状态。 |

**必须遵守的边界**：优先使用已有 Event API；Mixin 只做入口；服务端不依赖客户端类；新 HUD/GUI 文案同时写 `en_us.json`、`zh_cn.json`；kit 公共行为变更遵守 [UI kit 公开 API](../dev/internals/ui-kit-api.md) 的兼容策略；两端入口和断连清单保持对称。涉及原版或 loader API 的不确定用法，先按项目的 API 分级规则核对。

## 四、技术路线

### 4.1 分层设计与数据流

```text
平台客户端事件 / 原生 Screen 输入
  → 宿主焦点、模态与生命周期协调
  → 客户端状态快照 / 目录 revision / 语言与资源版本
  → 面板数据投影与文案
  → UI kit 布局、裁剪、绘制
```

搜索框由宿主记录“此次展开后要聚焦的输入框”，在原版鼠标分发返回后兑现。目录投影按版本复用，不让面板 `render` 隐式生成全目录副本。HUD 和世界渲染各自只借用并恢复本次绘制改变的状态。断连先清客户端快照，再允许管理页按新连接请求填充。

### 4.2 关键类型与状态约定（实施草案）

| 职责 | 草案 | 失效/边界 |
|---|---|---|
| 搜索焦点交接 | `CatalogToolbar` 暴露本次新建的 `searchField` 与“需要聚焦”请求；`ArchaeologyJournalScreen` 在输入事件尾部调用宿主 `setFocused`。 | 收起、切页、resize、模态打开时清请求；只有原搜索框曾持焦点时才自动恢复。 |
| 目录投影 | `ArchaeologyJournalClientState` 的只读投影缓存，或 `JournalFormatHelper` 的原始目录定点查询。 | 先列齐 `serverCatalog`、模拟结果/选择、断连的变更点；缓存键和版本一致才复用，不返回可变内部集合。 |
| 网格/搜索派生数据 | 目录索引、关系索引及小写名称按目录 revision 和语言版本维护；网格按表 ID、展示状态、模拟版本取视图。 | 搜索文本只影响过滤结果；排序、收藏、展开与当前选择分别声明失效范围。 |
| 管理页快照 | `LootTableManagementClientState.reset()` 清条目、最近记录、翻译、`canEdit` 并推进 revision；两端断连入口调用。 | 与 `ClientLootTableLanguageStore` 的连接级翻译清理一起核对，不让旧文案残留。 |
| 复用组件 | 仅在两处以上证实同构时抽 `BreakdownPreview`、列表几何、tint BufferSource、面板几何。 | 保留 anvil/grindstone 各自的计算器和数据 record；`containsMouse` 先裁定 `<`/`<=`。 |

### 4.3 分阶段实施与完成条件

每个编号是一批可独立审查的改动；同阶段内可按编号顺序推进。阶段结束时记录结果、构建状态、用户需实测的步骤，以及审查报告条目的去向。

| 批次 | 工作与关联编号 | 完成条件 |
|---|---|---|
| S0-1 搜索与状态 | 修 H-1/O-3；补 O-4 的断连重置；复核 O-6 三个子屏的未提交输入快照。 | 点放大镜后直接输入可用，再点框仍可用；断连到另一服务器时无旧条目/编辑权限；resize 不丢草稿。 |
| S0-2 可读性 | 修 H-2/I-1、H-3/I-2；同步灯箱默认样式契约与中英文案。 | 六处逐项按真实背景复算并截图核验；灯箱全部控制按钮可读，hover/pressed/disabled 态也可辨。 |
| S0-3 渲染正确性 | 修 H-4/O-1、O-7。**H-5/O-2 与衣服层复核本轮不做**（见 2.4）。 | 非封顶/封顶 HUD 后 shader 状态恢复；透视描边不意外遮挡后续绘制。灵体可见性/发光不列入本轮验收。 |
| S1-1 服务端边界 | 处理 A-1 的服务端可达 `client` 引用；把 A-6 的附魔 tooltip 业务移出 Mixin。 | 专用服务端可启动并执行相关物品路径；common 无平台 import；Mixin 仅保留入口与少量参数传递。 |
| S1-2 目录与日志性能 | 处理 H-6/P-1、P-3/P-4、P-5；复核性能前后同目录数据。 | 日志详情渲染不再每帧 overlay 全目录；相同 revision 下重复搜索/网格导航不重建索引；日志合并写盘且退出/断连不丢增量。 |
| S1-3 绘制热路径 | 先量化 P-2、P-6～P-14；按收益实施 P-2/P-6/P-8/P-9/P-10/P-11/P-12，低收益的 P-7/P-13/P-14 记录测量后决定。 | 有前后同场景帧时间/分配记录；GUI scale 变化、滚动、tooltip、图标 tint 和裁剪无回归。 |
| S2-1 管理页与输入体验 | 合并 A-4/R-2/I-5/I-6/O-13 的管理页工作；修 I-4、I-8～I-14 中确认的用户可见问题。 | 空结果、资源包来源、导入错误有准确本地化反馈；列表滚动/搜索/文件导入保持可用；toast 与计数文案不静默截断。 |
| S2-2 披露与 HUD 设计 | 对 I-3、I-7 先作设计裁定；修确认需要的内容；检查 I-10 禁用态与 I-12 淡出阈值。 | 未发现物品的格子与 tooltip 披露口径一致；阶段/九命状态不只靠颜色理解；禁用态不暗示可操作。 |
| S3-1 复用与职责 | 处理 A-2/A-3/A-5/A-7、R-1/R-3～R-6/R-8～R-10；R-4 先统一边界。 | 每次抽取保留原页面行为与两端调用；状态/视图依赖方向可说明；颜色表和 tooltip 语义只在相应权威处登记。 |
| S3-2 kit 契约与可靠性 | 核验并处理 R-7、O-8/O-9/O-11/O-12/O-14/O-16；只在两端确认后提高 Mixin `require`。 | Frame 子文档交互、裁剪、资源重载和输入框阴影在两端一致；边界脚本与 `package-info` 声明一致；公开 API 变更有兼容记录。 |
| S4 文档收尾 | 处理 O-10 的 12 处不一致、O-15 死键、O-17/O-18 颜色与注释；逐条关闭审查报告并同步 `docs/dev/`。 | 相关机制只在权威文档留一份当前答案；中英键差集为空；报告每个编号有明确处置状态。 |

S0–S1 的正确性任务不依赖大规模重构，可优先提交。S1-3 的性能项先采样再确定改动范围；S2-2 的设计项在用户决定披露和 HUD 语义后实施。S3 的职责拆分应在相关 bug 与热路径稳定后进行，避免把问题一起搬入新类。

### 4.4 主要文件改动清单（预期）

| 范围 | 主要文件 | 预期工作 |
|---|---|---|
| 搜索与笔记视图 | `client/ui/screen/{CatalogToolbar,ArchaeologyJournalScreen,JournalViewModel}.java`、`client/ui/panel/{CatalogPanel,ItemGridPanel,LogDetailPanel}.java` | 焦点事件尾部交接、目录/网格索引、日志详情定点查询与页面输入恢复。 |
| 状态与本地存储 | `client/ui/support/{ArchaeologyJournalClientState,LootTableManagementClientState,ArchaeologyJournalLogLocalStore,JournalFormatHelper}.java`、`client/state/ScenarioSimulationClientState.java` | 连接生命周期、revision 缓存、日志写盘和投影数据的失效点。 |
| kit 与模态 | `client/ui/kit/{UiLightbox,UiControl,UiDocument,TextScroll,UiTransform,UiImageView}.java`、`client/ui/overlay/ScenarioParamsOverlay.java` | 样式、文本、裁剪、鼠标传递与 API 契约。 |
| HUD 与渲染 | `client/hud/{CatFavorHud,SuspiciousReaderHud}.java`、`client/renderer/{SuspiciousReaderRangeHighlight,CatFavorShieldRenderer}.java`、`client/pan/PanningMediumIndex.java` | 渲染状态、描边、性能项及呈现语义。**三个灵体猫渲染器本轮不改写**（见 2.4）。 |
| 管理页、tooltip、通知 | `client/ui/screen/LootTableManagementScreen.java`、`client/ui/widget/{IconButton,BookmarkToggleButton}.java`、`client/ui/toast/`、`client/anvil/`、`client/grindstone/`、`mixin/client/EnchantmentScreenMixin.java` | 复用与职责抽取、错误反馈、禁用态和通知可读性。 |
| 两端入口与资源 | `fabric/src/main/java/.../UnsuspiciousBlockFabricClient.java`、`neoforge/src/main/java/.../UnsuspiciousBlockNeoForgeClient.java`、`common/src/main/resources/assets/unsuspiciousblock/lang/{en_us,zh_cn}.json` | 对称的断连重置、平台接线和同步本地化。 |
| 规则与文档 | `scripts/check-ui-kit-boundaries.ps1`、`client/ui/kit/package-info.java`、`docs/dev/subsystems/client-ui.md`、`docs/dev/internals/{journal-ui-internals,ui-kit-api}.md`，以及受影响的 foundation/子系统文档 | 边界检查、公开契约、机制同步与审查条目关闭。 |

### 4.5 报告编号覆盖与处置

| 编号 | 默认处置 | 批次或裁定门槛 |
|---|---|---|
| H-1/O-3、H-2/I-1、H-3/I-2、H-4/O-1、O-4 | 已确认代码问题；分别修复并做运行验证 | S0-1～S0-3；H-1 已有用户复现，其余运行时范围仍需确认 |
| H-5/O-2 | **本轮不做**：灵体猫待重新设计，可见性/发光契约随之确定 | 移出本轮范围（见 2.4）；重设计任务启动时一并裁定 |
| H-6/P-1、P-2～P-6、P-8～P-12 | 源码热路径成立；在相同场景采样后优化 | S1-2～S1-3 |
| P-7、P-13、P-14 | 小项或平台成本未知，先测量；收益不足时记录不改 | S1-3 |
| A-1、A-6 | 先解决服务端边界和 Mixin 职责 | S1-1 |
| A-2、A-3、A-5、A-7 | 在行为稳定后拆职责或修类型边界 | S3-1 |
| A-4、R-2 | 与管理页体验合批，避免同文件反复改动 | S2-1 |
| R-1、R-3～R-6、R-8～R-10 | 按实际重复点逐项抽取；保留不同算法和边界语义。**其中 R-3 的本轮部分为"不做"**（灵体 tint 包装，见 2.4）；R-1 及 R-4～R-6、R-8～R-10 照常 | S3-1（R-3 移出） |
| R-7 | 裁剪与 flush 时序先验证，再决定统一实现 | S3-2 |
| I-3、I-7 | 设计先裁定披露/状态文案，再决定修复 | S2-2 |
| I-4～I-6、I-8～I-14 | 可读性与反馈逐项修复或记录设计保留 | S2-1～S2-2 |
| O-6、O-7 | 生命周期和渲染行为复核后修复 | S0-1、S0-3 |
| O-8、O-9、O-11、O-12、O-14、O-16 | kit 契约与两端运行验证后处理 | S3-2 |
| O-10、O-15、O-17、O-18 | 文档、死键和注释清理；颜色改动须查实际用途 | S4 |
| O-13 | 与管理页导入错误反馈合批 | S2-1 |
| O-5 | **撤销，不实施** | `ScenarioParams` 已在构造点拒绝非法值 |

### 4.6 文档同步与完成后的去向

每批实施后只把**已生效的机制**写回对应 `docs/dev/` 权威文档；本计划保留理由和决策。改 kit 公开 API 时同步 `package-info.java`、`ui-kit-api.md` 与必要的兼容记录；改客户端连接状态和两端入口时同步架构与平台文档。审查报告中的条目在实测和文档同步后标记关闭；本计划全部完成后补「九、实施结果」，再按 `docs/plan/README.md` 的约定移入本地归档。

## 五、决策记录

| 编号 | 决策点 | 结论、备选与理由 |
|---|---|---|
| D1 | 焦点修复位置 | 在宿主鼠标事件结束后完成焦点交接；仅在 `CatalogToolbar.createWidgets` 内调用 `setFocused` 可能被原版点击分发覆盖。最终代码应保留“再点输入框”和 Tab 的正常行为。 |
| D2 | UI kit 主题 | `UiLightbox` 明确采用暗底默认样式并保留 `setStyle` 覆盖能力；不把暗色文字硬编码到所有宿主页面。 |
| D3 | 性能排序 | H-6/P-1 的 O(目录) 调用链先改；P-7/P-13/P-14 先测量。报告的调用次数足以定位路径，但不足以证明真实帧耗时。 |
| D4 | 缓存口径 | 每个缓存写清输入、版本与失效事件；不使用无界的全局字符串宽度缓存或只依赖 `tableId` 的网格缓存。 |
| D5 | 日志写盘 | 优先同 tick 合并并在退出/断连 flush；后台线程方案只有主线程方案确实不能满足帧预算时再考虑。 |
| D6 | UI 复用 | 抽取计算编排，不合并 anvil/grindstone 的不同算法；R-4 的边界口径先统一，R-7 的裁剪实现先实机验证。 |
| D7 | 设计开放项 | I-3、I-7、H-5 隐身语义保留待确认；在用户未裁定前，不以静态审查建议直接改变玩家可见规则。 |
| D8 | 已修正的审查错误 | O-5 已被撤销；R-4、I-10、P-7、O-10 等按复核版本理解。本计划不把原始审查者统计当成完成百分比。 |
| D9 | 与现有计划的关系 | 已完成的考古笔记 GUI 翻新 P0–P2 不重做；仍待的逐页实机核验可同时验证本计划相关页面，结果分别记录。 |
| D10 | 灵体猫范围 | 需求方 2026-09-28 裁定「灵体猫未来要重新设计，本轮不做」。H-5/O-2（可见性/发光分支）、R-3（三份 tint 包装）与衣服层复核移出本轮（见 2.4），避免与重设计冲突；本轮其余编号不受影响。 |

## 六、风险与限制

| 风险 | 说明 | 处置 |
|---|---|---|
| 焦点交接时序 | 旧按钮在回调后可能重新获得宿主焦点；resize 或逐字符重建又可能夺走用户焦点。 | 在事件尾部交接；覆盖鼠标、Tab、ESC、搜索收起、resize、C 键路径。 |
| 渲染状态与批次 | 直接在 `blit` 后恢复颜色可能改变延迟提交的 tint；深度遮罩也依赖 setup/clear 顺序。 | 核对两端渲染阶段与 flush 时机；用同 GUI scale 截图比对，并检查后续 HUD/世界绘制。 |
| 版本缓存漏失效 | 模拟选择、结果、目录、语言或断连变化后可能显示旧概率/旧名称。 | 实施前列输入与失效矩阵；变化路径逐一触发刷新，结果只读。 |
| 日志延迟落盘 | 合并写盘若漏掉断连或客户端关闭，可能丢最近记录。 | 明确 flush 边界、失败重试与 dirty 保留；对照存档重进。 |
| API 与两端差异 | kit 公开语义改变、Mixin 目标差异或平台饰品栏实现不同，单端通过不代表双端通过。 | 跑边界脚本、两端构建；用户分别实机核对关键路径。 |
| 主观建议变成强制重写 | I-3、I-7、某些低危复用/性能项可能是有意设计或没有可感收益。 | 先给证据和设计决定，再修；接受有理由的“保留现状”。 |
| 审查报告仍是未跟踪文件 | 规划时 `docs/todo/ui-system-review.md` 尚未进入 Git；若只提交本计划，来源链接会失效。 | 实施/提交文档时把报告与本计划一并纳入版本控制；本任务不代替用户创建提交。 |

## 七、验证方式

**编译与静态验证（开发执行）**：每批修改后优先用 IDEA MCP 检查 Git 工作区改动的 Java 文件错误/警告（Markdown 格式问题忽略），再运行一次 `./gradlew build` 并等待结束；涉及 kit 边界时运行 `scripts/check-ui-kit-boundaries.ps1`；涉及文案时严格解析两份 JSON 并核对 key 双向差集；涉及服务端可达类时做专用服务端启动与物品路径烟测。不要为这批可逆改动新增镜像实现的 test 文件。

**性能验证（开发执行）**：记录相同目录规模、相同 GUI scale、相同页面与语言下的实施前后帧时间/分配或 profiler 采样；至少覆盖日志详情、长目录逐字符搜索、结果网格、参数浮层、解析仪 HUD、猫之恩惠 HUD。对未达到可辨收益的 P-7/P-13/P-14，写明测量和不改理由。

**实机验证（用户执行）**：

1. NeoForge 与 Fabric 各打开考古笔记：点放大镜后直接输入，再点框输入、退格、收起/展开、Tab 切换、调整窗口；另用 C 键带物品打开搜索，确认首字不丢失。
2. 打开场景条件灯箱并逐个观察关闭、切图、适应、放大/缩小按钮的普通/悬停/禁用态；在中英语言与小窗口分别核对六处纸面文字。
3. 在非封顶与封顶恩惠状态观察 HUD 和随后绘制的其它元素；给三类猫分别施加隐身/发光条件，按裁定检查主体与衣服层；检查解析仪透视描边。
4. 连接服务器 A 打开管理页，再断连进入服务器 B；在 B 的首个管理页响应到来前后确认无 A 的条目、翻译或编辑权限。调整窗口时备注、保留上限、管理页搜索草稿保持合理。
5. 使用较大目录重复搜索、切表和查看日志详情；检查结果、tooltip、目录关系及颜色/裁剪无回归。制造多条日志增量后退出重进，确认本地记录完整。

## 八、待确认项

| 项 | 推荐处置 |
|---|---|
| I-3：未发现物品 tooltip 是否允许概率/声明触发率 | 先由玩法设计确定披露规则；当前代码是显式传递，不能仅凭格子隐藏数字认定泄露 bug。 |
| H-5：灵体猫是否有意无视隐身 | **本轮不做**（见 2.4），并入灵体猫重设计一起裁定。 |
| I-7：阶段名与九命零值怎么显示 | 给出玩家可见规则后实施，避免修原版分支时改变预期玩法。 |
| O-11/O-12/R-7：Mixin 命中、资源语言加载线程、裁剪 flush 时序 | 两端各取一份可重复运行证据；证实风险后确定最小修复。 |
| P-7 与第三方饰品栏流的成本、P-3/P-4 的实际服务器目录规模 | 用真实 Mod 组合和目录规模测量；以结果决定缓存深度。 |
| 现有 GUI 翻新计划的 P3/P4 验收与本计划批次重合 | 共享截图和操作记录，按两个计划各自的完成条件关项，避免重复实施。 |

## 九、实施结果

> 实施日期：2026-09-28。执行方式：Agent Teams 按文件作用域分四批并行（笔记屏幕 / UI kit / 面板与浮层 / 状态与渲染），Lead 承担服务端边界（A-1）、Mixin 职责（A-6）、i18n 合并、文档收尾与最终构建验证。**H-5/O-2 与 R-3 按 2.4 本轮不做。**

### 9.1 验证记录（开发执行）

| 项 | 命令 / 手段 | 结果 |
|---|---|---|
| IDEA 检查 | IDEA MCP `lint_files`（error 级），覆盖 git 工作区全部 82 个改动 Java 文件 | **0 error**；warning 级仅剩既有风格项（`Math.clamp` 建议、未使用的公开槽位、形参值恒定等），已修掉本轮引入的 2 条 |
| 边界脚本 | `scripts/check-ui-kit-boundaries.ps1` | **exit 0**：kit 无项目依赖 / 无平台 API / 无资源常量 / import 白名单通过；扫描 22 个 kit + 422 个非 client 文件 |
| 编译构建 | `./gradlew build`（单次、等待至结束） | **BUILD SUCCESSFUL in 55s**；common / fabric / neoforge 均通过，javadoc 任务通过 |
| i18n | 两份 JSON 严格解析 + 双向差集 | 各 **898** 键（原 909，删 21 个死键、增 10 个新键）、**双向差集为空** |
| 灵体猫文件 | 全流程未修改 | `MessengerCatRenderer` / `SwordsmanCatRenderer` / `MerchantCatRenderer` / `MessengerCatClothesLayer` / `MessengerCatClothesModel` 无改动 |

### 9.2 完成的批次

- **S0-1 搜索与状态**：H-1/O-3 宿主焦点交接（鼠标事件尾部兑现 + `setInitialFocus` 覆盖 C 键路径 + overlay 打开清焦点）、O-4 `LootTableManagementClientState.reset()` 与两端断连清单、O-6 三个子屏 resize 输入快照、P-3 搜索 200ms 防抖与关系索引/小写名称按 revision 缓存、P-4 网格与子表闭包按版本缓存。
- **S0-2 可读性**：H-2/I-1 灯箱默认暗底 + 文本色按底色反推；H-3/I-2 六处全部按真实底色复算达标（并追加 7 处同底色不达标项）；`UiTextPalette.Parchment` 全 9 槽 ≥4.5:1（最低 NEGATIVE 4.68:1）；三处 `JournalLayout` 常量改引语义色。
- **S0-3 渲染正确性**：H-4/O-1 `CatFavorHud` try/finally 成对恢复（tint 在 `blit` 同步上传后复位，不影响图标颜色）、O-7 透视描边补 `depthMask(false/true)`。**灵体可见性本轮不做。**
- **S1-1 服务端边界**：A-1 `TooltipBuilder` 迁至 `text/` 包、新增 `text/ClientTooltipBridge` + `client/tooltip/ClientTooltipHooks`，`ArchaeologyJournalItem` / `HandOfCatItem` 改为走桥接，15 处引用统一切换；A-6 `EnchantmentScreenMixin` 70 行业务逻辑外移到 `client/enchantment/EnchantmentRevealTooltipBuilder`。
- **S1-2 目录与日志性能**：H-6/P-1 `getCatalog()` 按 `catalogRevision` 缓存（含断连推进版本这一新增失效点）、P-5 日志脏标记 + tick 合并落盘 + 三处强制 flush。
- **S1-3 绘制热路径**：P-2 控件按需裁剪、P-6 解析仪 HUD 派生量缓存、P-8 淘洗索引按快照缓存、P-9 护盾球体静态几何、P-10 `TextScroll` 已测宽度重载 + 6 个面板调用点、P-11 日志图标槽改持条目/去 flush 循环、P-12 面板文案与布局按版本缓存、P-14 灯箱重复测量。
- **S2-1 管理页与输入体验**：A-4 抽出 `LootTableManagementActions` / `LootTableImportValidator`、R-2 列表几何共用 `ListScrollState`、I-5 空态、I-6 资源包来源（`%s`）、O-13 导入失败四类分流 + 日志。
- **S2-2 中性项**：I-10 禁用态不提示/不展开、I-12 alpha 阈值统一为 `alpha*255 >= 4`。**I-3、I-7 待设计裁定，本轮不改。**
- **S3 部分**：R-1 `BreakdownPreview`（保留两套 Calculator 与 record，grindstone 不再 import anvil）、R-4 边界口径统一为半开区间、R-5 分页/几何单一来源、R-7 语义核对后仅合并换算核心（保留两套取整/flush 策略）、R-8 取色统一到语义色表、R-9/R-10 部分、O-8/O-9/O-11/O-12/O-14/O-16/O-17/O-18。
- **S4 文档收尾**：O-10 的 12 处不一致全部修订；O-15 删除 21 个死键；`client-ui.md` 压缩到 **199 行**（≤200 约定）；`text-format.md` / `journal-ui-internals.md` / `ui-kit-api.md` / `config-and-integrations.md` / `cat-favor.md` 同步；CHANGELOG 增「未发布」段。

### 9.3 与计划的偏离（有意收窄，附理由）

| 项 | 计划口径 | 实际处理与理由 |
|---|---|---|
| P-11 | 「`flush()` 移出循环」 | 幽灵虚影依赖着色器颜色在提交时生效，改成「幽灵分支：先 flush 不透明内容→着色→渲染→立即 flush→复位」，全获得场景 0 次 flush；字面建议被有意收窄，理由写在代码注释 |
| R-4 | 抽取公共 `containsMouse` | 先统一为半开区间 `[left,right)×[top,bottom)`（另四处原为 `<=`，会让边界像素双命中），四个面板边界行为变化恰 1px；tooltip 入口收敛留待后续 |
| R-7 | 删 `TextScroll` 自带裁剪、统一 `UiTransform.enableScissor` | 复核 1.21.1 原版语义后判定两套策略必要（文本走 `drawString` 会自行 flush、`blit` 不会），只合并 pose→像素换算核心 |
| P-12 | 全部逐帧文本缓存 | 2 处 hover-only（导航 tooltip、场景悬停 tooltip）记录不改：悬停才发生，缓存成本高于收益 |
| P-7 / P-13 | 先测量再决定 | 本轮无法启动客户端采样，按「记录不改 + 说明测量条件」处理；需用户侧 f3/Spark 采样 |
| P-9 | 评估关 `sortOnUpload` | 保留 `true`：去 cull 后自重叠混合顺序依赖排序，收益小、可见回归风险实在 |
| A-2 | 完全收敛双向依赖 | 剩余两条反向边（`SimulationPreferenceStore → JournalUiPreferencesStore`、`RightPageContainer/ArchaeologyEntry`）不在本轮作用域，第三条回调已补（state 不再依赖 `ui.toast`），其余记为后续 |
| O-11 | 至少一端 `require = 1` | 同包双端共用一个 mixin，对任一端提级会让另一端整包加载失败；改为共享包装方法 + 启动期自检告警，要求拆分平台 mixin 才能提级 |
| R-2 | 管理页直接用 `UiScrollView` | 管理页是按行 `drawString` 的列表，套 `UiScrollView` 会改变滚动条视觉与命中口径；改为抽共用 `ListScrollState` 并统一 `TextScroll.trimToWidth` |
| DetailOverlayPanel 进度读数 | H-3 建议"白字改画在条外" | 按用户实机反馈修正：读数移回**条内**居中，改为按填充边界分色（填充侧 `0xFF2E2114`：暖棕 6.40:1 / 完成绿 5.04:1；未填充侧白字：条底 7.46:1），位置与 ≥4.5:1 同时满足 |
| 灵体猫 | H-5/O-2、R-3、衣服层复核 | 需求方裁定本轮不做（2.4），随重设计处理 |

### 9.4 编号去向

| 编号 | 去向 |
|---|---|
| H-1/O-3 | 已修复（待实机复测两条打开路径） |
| H-2/I-1、H-3/I-2、H-4/O-1、O-4、O-6、O-7 | 已修复 |
| H-5/O-2、R-3 | **本轮不做**（灵体猫待重设计，2.4） |
| H-6/P-1、P-2~P-6、P-8~P-12 | 已实施（P-12 两处 hover-only 记录不改） |
| P-7、P-13 | 记录不改（缺真实采样），待用户 Profiler 数据 |
| P-14 | 已实施（kit 内） |
| A-1、A-6 | 已修复 |
| A-2 | 主体已修复（第三条回调 + 依赖方向声明），剩余两条反向边记后续 |
| A-3、A-5 | 未实施（职责拆分列入 S3 后续；避免与热路径改动同批） |
| A-4、R-2 | 已修复（管理页动作/校验抽出、列表几何共用） |
| A-7、R-3 之外的 R-1、R-5、R-6、R-9、R-10 | R-1/R-5 已实施；R-6（模态 chrome）部分沿用；R-9/R-10 已处理本轮触及的部分，其余记后续 |
| R-4、R-7、R-8 | R-4 边界统一 + 抽取；R-7 仅合并换算核心；R-8 已统一取色 |
| I-1、I-2、I-4~I-6、I-8~I-14 | 已实施，除 I-3 / I-7 待设计裁定 |
| I-3、I-7 | **待设计裁定**（披露口径、阶段名与九命为零的表达），本轮不改 |
| O-1、O-3~O-4、O-6~O-9、O-11~O-18 | 已实施（O-18 的 `hudEnabled` 判定为会话级有意设计并写明注释） |
| O-5 | 撤销，不实施 |
| O-10 | 12 处全部修订 |

### 9.5 仍需用户实机验证

1. NeoForge 与 Fabric：点放大镜后直接输入；再点输入框、退格、收起/展开、Tab、调整窗口；另用 C 键带物品打开确认首字不丢失；打开浮层时字符不落入搜索框。
2. 场景条件灯箱逐个按钮的普通/悬停/按下/禁用态；中英与小窗口下核对纸面文字；GUI Scale=2 下悬停超宽文本确认不越出文本带（R-7 的两套裁剪策略）。
3. 非封顶与封顶恩惠下观察 HUD 及随后绘制的其它元素；解析仪透视描边不再遮挡后续世界绘制。
4. 连接服务器 A 打开管理页，再断连进入服务器 B，确认无 A 的条目/翻译/编辑权限；空结果提示、资源包来源 id、导入失败三类文案；resize 后备注/保留上限/搜索草稿保留。
5. 较大目录下重复搜索、切表、查看日志详情；制造多条日志增量后退出重进确认记录完整；核对日志详情最后一页不空页（分页口径已与渲染统一）。
6. 未裁定项：I-3（未发现物品 tooltip 是否披露概率）、I-7（阶段名与九命为零的表达）需先给玩法口径。

