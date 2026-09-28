# UI 系统审查报告：架构、代码复用、性能与信息展示

> 状态：**静态审查快照**（2026-09-28）。由 5 名审查者按包切片并行通读 +`client/**` 共 136 个文件，另交叉核对 6 篇开发者文档、1 个边界检查脚本与 MC 1.21.1 原版源码。
> 原始静态审查**未编译、未实机验证**；此后用户已实机复现 H-1/O-3 的搜索框焦点问题。其余需要实机才能定性的前提列在第八节。
> 机制的唯一权威仍是 [客户端与 GUI](../dev/subsystems/client-ui.md) 与 [笔记 GUI 内部机制](../dev/internals/journal-ui-internals.md)；本文只记录经静态阅读核实的事实、缺陷与可动项。
>
> **路径约定**：除特别注明外，路径省略前缀 `common/src/main/java/com/meteorite/unsuspiciousblock/`，以 `client/...` 起写。
> 行号以写作时的工作区为准，会随改动失效。**原版代码行号以 MCP 索引的 1.21.1 反编译源为准**，与工作区行号无关。

> **复核更正（2026-09-28）**：本报告不是全部属实。O-5 是明确误报，已撤销：`ScenarioParams` 构造器拒绝非有限幸运值，浮层捕获该异常。H-1/O-3 原本把搜索框写成普遍无法输入，范围过大；用户现已实机确认：**点击放大镜后不能直接键入，必须再点一次输入框**。R-4 的「五处逐字相同」、I-10 对禁用书签的描述均有误；O-10 的数量与第六节不一致，P-7 的扫描次数表述过满。相关条目已在下文更正。原审查者计数是历史记录，**未按本次复核重算，不能用作当前缺陷数量**。其余运行时后果和性能绝对量级仍受第八节限制。

---

## 一、审查范围与方法

| 切片 | 覆盖范围 | 文件数 |
|---|---|---|
| A. UI kit 与宿主适配层 | `client/ui/kit/**`、`client/ui/overlay/**`、`client/ui/sample/**`、`scripts/check-ui-kit-boundaries.ps1` | 26 |
| B. Screen 与视图状态 | `client/ui/screen/**`、`layout/**`、`entry/**`、`client/ui/` 根 3 类 | 21 |
| C. 面板 / 控件 / 提示 / 通知 | `client/ui/panel/**`、`widget/**`、`tooltip/**`、`toast/**` + 全局 i18n 核对 | 29 |
| D. HUD 与世界渲染 | `client/hud/**`、`renderer/**`、`model/**`、`pan/**` | 24 |
| E. 客户端状态与分解预览 | `client/ui/support/**`、`client/state/**`、`anvil/**`、`grindstone/**`、`enchantment/`、`keybind/`、`tooltip/` + 两端入口对称性 | 40 |
| F. 跨切面（Lead） | `mixin/client/**` 6 个、包边界扫描（common 引平台类 / 服务端引客户端类）、文档与代码一致性 | 20+ |

方法：逐文件静态阅读；用 `mc-developing-mcp` 核对原版语义（`Screen` 焦点分发、`RenderType` 各 shard 的 setup/clear、`ModelPart.copyFrom`、`Font.width` 无缓存等）；用 IDEA MCP 的调用层级核对可达性；`scripts/check-ui-kit-boundaries.ps1` 实跑一次（**exit 0**）。

**原审查片段统计（历史记录，未按复核重算）**

| 严重度 | A | B | C | D | E | F | 合计 |
|---|---|---|---|---|---|---|---|
| 高 | 1 | 1 | 1 | 2 | 1 | 0 | **6** |
| 中 | 6 | 9 | 8 | 7 | 9 | 3 | **42** |
| 低 | 6 | 7 | 11 | 5 | 6 | 4 | **39** |

---

## 二、总体结论

1. **框架层是健康的，业务层是欠债的。** `client/ui/kit/` 是本次审查中质量最高的部分：四条依赖边界由脚本守住且实测通过；公开入口清单与 `package-info.java`、`ui-kit-api.md` 三处逐条一致；排版缓存粒度、命中二分、`UiMetrics` 生产全关、`setFocused` 只改绘制状态等自定契约都被真正落实（见第五节）。问题集中在**接入 kit 的业务页面**与**逐帧渲染路径**。
2. **值得优先处理的问题**包括已实机复现的搜索框焦点缺陷（O-3：点击放大镜后需再点一次输入框才能键入）、灯箱控制栏文字对比度 1.06:1（I-1）、HUD 着色器颜色未恢复（O-1）、猫渲染器忽略可见性/发光入参（O-2）。后三项的具体运行时后果仍需按第八节验证。
3. **逐帧分配是系统性的，而非个别疏忽。** 合计 **14 条性能项**（P-1~P-14，另有若干性能性低危项散落于其他维度），其中「每帧重建整份目录投影」（P-1）与「每控件两次 `graphics.flush()`」（P-2）量级最大；其余多为「派生文本/宽度/集合本可缓存却每帧重算」。共性是：**项目已有正确的缓存范式**（`JournalViewModel.reloadCatalog` 的 revision 门控、`ScenarioPanel.sceneLabel` 的按 key 缓存、`ReaderScanHudState.makeSnapshot`），但没有被系统性贯彻。
4. **代码复用最大的两块债**是 **anvil / grindstone 两套同名实现**（R-1）与 **LootTableManagementScreen 内部复制整套列表几何**（R-2）——两者都有明确的、低风险的抽取路径。
5. **文本色存在第二、第三个事实来源**（R-8），增加了对比度回归（I-2）的风险：文档要求"取色只从语义色表取"，但三个 Screen、`JournalTooltipBuilder`、`ItemGridPanel` 各自就地挑色。仅凭并存关系不能证明它是六处低对比度的唯一根因。
6. **第六节列出 12 处文档与代码不一致**（O-10）；其中「按键 4 个 vs 实际 5 个」「HUD 在快捷栏上方 vs 实际左侧」「kit 内部 4 组双向依赖 vs 实测 2 组单向」会直接误导按文档扩展的人。
7. **i18n 完整性本身是达标的**（这是正面结论，不是问题）：`en_us` 与 `zh_cn` 各 909 键、双向差集为空、`client/**` 无 CJK 硬编码自然语言、四态概率文案齐全。附带发现 21 个「定义了但从不使用」的死键。

---

## 三、高严重度问题（6 条，其中 H-1 已实机复现）

### H-1 点击放大镜后无法直接键入，必须再点一次搜索输入框（已实机复现）
- 维度：其他（功能缺陷）＋架构
- 位置：`client/ui/screen/CatalogToolbar.java:292-321`（尤其 312-315）、`screen/ArchaeologyJournalScreen.java:122-127,199-230,631-750`
- 实机复现（用户反馈）：打开笔记并点击放大镜展开搜索框后，不再点击输入框就直接键入，文本不会进入搜索框；再点击一次输入框后可以正常键入。预期是点击放大镜展开后即可直接键入。
- 静态原因：展开时只调用 `searchField.setFocused(true)`，没有把新输入框交给宿主 `screen.setFocused(searchField)`；`ArchaeologyJournalScreen.charTyped` 交给 `super`。点击放大镜的回调会重建控件，而原版 `ContainerEventHandler.mouseClicked` 在按钮回调结束后才把宿主焦点设为被点击的旧按钮。
- 旁证（同仓库，强）：`JournalLogNoteEditScreen.java:61` 是同样的 `editBox.setFocused(true)`，正因如此它在 `:106-150` 手写了 `keyPressed/charTyped/mouseClicked/mouseDragged/mouseScrolled` 五个转发——作者已知「控件级 setFocused 不足以收到键盘」。
- 影响：点击放大镜后需多点一次输入框才能搜索；C 键带物品搜索打开的路径尚未实测，不能由本次复现直接推断。
- 建议：展开时把新输入框设为宿主焦点，并检查 `rebuildWidgets()` 后的焦点恢复；修复后分别复测放大镜与 C 键打开路径。

### H-2 灯箱控制栏实际用 PARCHMENT 浅底 + 近白字，对比度约 1.06:1
- 维度：信息展示（兼公开契约一致性）
- 位置：`client/ui/kit/UiLightbox.java:107,114,136-143,169-172,283-295`、`kit/UiControlGroup.java:33`、`kit/UiControl.java:32`
- 事实：`UiLightbox:107` 直接 `new UiControlGroup()`（默认样式 `UiControlStyle.PARCHMENT`），构造函数**从未调用 `controls.setStyle(...)`**（`setStyle` 的唯一实现点在 `:170`）；全仓库 `setStyle` 的 10 处调用无一处针对 UiLightbox。`textColor` 保持 `:114` 的 `0xFFE0E0E0`。于是 6 个控制栏按钮（×/◀/▶/↺/+/−）在 `0xFFF2E5C6` 浅底上画近白字。
- 与文档冲突：`UiLightbox.java:169` javadoc 与 `docs/dev/internals/journal-ui-internals.md:160` 都称"默认用暗底样式（UiControlStyle.DARK）"。
- 影响：按 WCAG 相对亮度公式约 **1.06:1**（要求 ≥4.5:1），按钮字形几乎不可读；三个 `new UiLightbox` 站点（`panel/ScenarioDetailPanel.java:317` 生产 + 2 个示例页）均未修正，任何「按文档接入」的第三方宿主会得到同样画面。
- 建议：构造函数内 `controls.setStyle(UiControlStyle.DARK)`，并让 `textColor` 默认值从 DARK 结构色反推。

### H-3 文档硬性要求的「≥4.5:1」文本对比度有 6 处不达标
- 维度：信息展示
- 位置与实算值（底色 `#E8DCBC` / 模态面板 `0xFFF2E5C6`）：

| 位置 | 用途 | 对比度 |
|---|---|---|
| `panel/ItemGridPanel.java:44` `PROB_COLOR` | 模拟值（UncertaintyLevel.NONE） | **4.28:1** |
| `panel/ItemGridPanel.java:51` `PENDING_COLOR` | 「待解析」「未解锁」 | **4.20:1** |
| `panel/CatalogPanel.java:32` `CHILD_COLOR` | 子表行名称与 ∈ 标记 | **3.31:1** |
| `panel/WelcomeStatsPanel.java:28` `LABEL_COLOR` | 问候语/分区标签/统计标签 | **3.29:1** |
| `panel/DetailOverlayPanel.java:112` | 白字画在进度条填充色上 | **2.44:1** |
| `overlay/ScenarioParamsOverlay.java:225` | 参数错误文案 | **3.37:1** |

- 事实：`docs/dev/internals/journal-ui-internals.md:65-73` 把这条写成硬性要求，并记录过一次同类回归的修复（状态词 `0xFF6B6B6B` 3.9:1 → `0xFF4A4038` 7:1）。上述文本全部**无阴影**绘制（走 `TextScroll` 的 `drawString(...,false)` 与 `UiControl.render:154`）。
- 影响：「待解析/未解锁」状态词、子表行名、统计标签、进度读数与参数错误提示在浅底上可读性不足；进度读数与错误提示恰是玩家必须读到的两类。
- 建议：`PENDING_COLOR` 直接复用 `PROB_COLOR_UNKNOWN`（7.40:1）；`CHILD_COLOR`/`LABEL_COLOR` 压暗或改用 `UiTextPalette.Parchment.{LABEL,BODY}`；`DetailOverlayPanel` 进度读数改画在条外或加阴影；错误色改用压暗负面色。
- 未验证前提：对比度为公式计算，实机还受半透明填充、贴图纹理与 GUI 缩放影响，建议截图取色复核。

### H-4 `CatFavorHud` 设置着色器颜色后在非封顶分支不恢复，污染后续 HUD 绘制
- 维度：其他（渲染状态配对）
- 位置：`client/hud/CatFavorHud.java:73`（配对恢复只在 `:86`）
- 事实：`:73` **无条件** `RenderSystem.setShaderColor(阶段色)` 后 blit 图标，但 `setShaderColor(1,1,1,1)` 只写在 `favor >= MAX_FAVOR` 分支内。`favor < MAX_FAVOR` 是常态路径，此时 `ColorModulator` 一直是阶段色直到别处覆盖；`RenderType.gui`/`rendertype_text` 的片元着色器都会乘它。同文件 81-87 行的呼吸分支做了 `enableBlend → disableBlend` 配对，恰好反证 `:73` 漏了配对。对照：`SuspiciousReaderHud.java:151-162` 已用 try/finally 正确复位。
- 影响：后续同一 render pass 内被 flush 的 GUI 内容被叠加上异常色偏；封顶/未封顶两种状态下副作用不对称。
- 建议：把 `:73-74` 包进 `try { ... } finally { RenderSystem.setShaderColor(1,1,1,1); }`。
- 未验证前提：具体被染色的元素取决于平台 HUD 注入点与 `GuiGraphics` 的 flush 时序，需实机确认。

### H-5 三个猫渲染器的 `getRenderType` 忽略全部入参：隐身实体仍被绘制、发光描边失效
- 维度：其他（渲染行为与原版契约不一致）
- 位置：`client/renderer/MessengerCatRenderer.java:57-60`、`SwordsmanCatRenderer.java:33-36`、`MerchantCatRenderer.java:33-36`
- 事实：三者一律 `return RenderType.entityTranslucentEmissive(getTextureLocation(entity))`，忽略 `bodyVisible`/`translucent`/`glowing`。原版 `LivingEntityRenderer.getRenderType` 的语义是：`translucent → itemEntityTranslucentCull`；`bodyVisible → model.renderType`；**否则 `glowing ? RenderType.outline(loc) : null`**，而调用点 `if (renderType != null) {...}` —— 返回 null 就不画。
- 影响：① 实体对本地玩家完全不可见时（隐身/旁观）本应不绘制，实际仍以灵体色绘制；② `glowing=true` 时本应走 `RenderType.outline` 描边，实际该分支永不生效，**发光描边对三类实体失效**。
- 建议：保留自定义灵体类型用于 `bodyVisible`/`translucent` 两个分支，但不要吞掉 `null` 与 `outline`。
- 未验证前提：灵体是否**有意**无视隐身（代码与文档均无说明）；发光场景需实机确认。

### H-6 日志详情卡每帧调用 `getCatalog()`，逐帧重建整份目录投影
- 维度：性能
- 位置：`client/ui/panel/LogDetailPanel.java:157,176,240,258-259` → `ui/support/JournalFormatHelper.java:41-51` → `ui/support/ArchaeologyJournalClientState.java:277-279` → `client/state/ScenarioSimulationClientState.java:167-190`
- 事实：渲染路径上无任何缓存/版本门控。`formatStructureOrFeature` 只做一次 `getCatalog().get(tableId)`，而 `getCatalog()` 每次调用都执行 `ScenarioSimulationClientState.overlay(serverCatalog)`：`new LinkedHashMap<>(base)`（O(目录)），再对 `SELECTIONS` 的每个表调 `sceneSource`（`:143-158`，遍历该表全部场景），命中缓存时还会走 `projectScene`（`:193-221`，建 2 个 HashMap + 用 stream 重建 `TableDefinition` 与全部 `ItemDefinition`/`ChildTableProbability` 列表）。`SELECTIONS` 在收到目录时对每张有 options 的表都写入一项，规模≈整目录。**Lead 已回读源码复核，调用链与"无 memo"两点属实。**
- 影响：只要日志详情卡可见（structureId 为空且 tableId 在目录中），**每帧**一次 O(目录 + 场景×表) 的 map/列表重建与对象分配，目录越大越明显。
- 建议：`getCatalog()` 按 `catalogRevision` 缓存（`overlay` 的三个输入变更时都会 `incrementAndGet()`：`ArchaeologyJournalClientState.java:120,275`、`ScenarioSimulationClientState.java:85,110,115,126,243`），命中即返回不可变缓存；`formatStructureOrFeature` 也可改为只读 `serverCatalog`。

---

## 四、按维度归并的问题清单

### 4.1 架构

| 编号 | 严重度 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| A-1 | 中 | **common 的服务端可达类直接 import `client` 包**（违反 AGENTS.md「服务端严格禁止引用客户端类」）。`TooltipBuilder` 被 **15 个**类引用（12 个 `item/`、`block/SealedContentsDisplay`、`plugin/jade/JadePlugin`），`item/ArchaeologyJournalItem.java:3,20` 直接 `import client.ui.ArchaeologyJournalUi`，`item/HandOfCatItem.java:5,87-100` 直接读 `client.state.HandOfCatClientState` | 全量 import 扫描；`TooltipBuilder.java:4` 自身 import `net.minecraft.client.gui.screens.Screen` | 把语义色表与文本构建器移到 common 的非 `client` 包（如 `text/`）；把 `expandable` 的 Shift 检测与 `ArchaeologyJournalUi.open()` 通过平台/回调桥接。文档 `ui-kit-api.md` 门槛⑤已自认「专用服务端不加载客户端类」缺运行时验证，本条是同一缺口的实例 |
| A-2 | 中 | `client/state` ⇄ `client/ui/support` **双向依赖**；解锁通知的「回调解耦」不完整 | `state/ScenarioSimulationClientState.java:3`、`SimulationPreferenceStore.java:4` → ui.support；`ui/support/ScenarioLabel.java:3`、`ScenarioPresentation.java:4` → client.state；`ui/support/ArchaeologyJournalClientState.java:4,5,24,262-268` 仍 import `ui.panel.RightPageContainer`、`ui.entry.ArchaeologyJournalEntry`，并直接调 `ui.toast.JournalUnlockToast.addTableCompletion`（**未走回调**） | 二选一收敛（把 Scenario* 状态迁入 ui/support 或改为快照接口）；100% 完成奖励改走第三条回调；同步修文档 |
| A-3 | 中 | `JournalViewModel` 职责集中：当前文件 **644 行**，承担 revision 检测、目录树建模、关系索引全树 DFS、搜索排序、选中导航、网格数据装配、i18n+注册表解析、一致性校验，并有从 getter 写持久化的副作用 | `client/ui/screen/JournalViewModel.java:48-644`（`:108-112`） | 拆 `JournalCatalogIndex` / `JournalTableRows` / `JournalGridAssembler` / `JournalCategoryPresenter` + 瘦 ViewModel；诊断逻辑移 support |
| A-4 | 中 | `Screen` 越界：管理页在 Screen 内做**协议组包 + 原生文件对话框 + 2 MiB JSON 解析校验**（渲染线程阻塞 IO） | `screen/LootTableManagementScreen.java:165,426-440,658-753`；`screen/ArchaeologyJournalScreen.java:941-953,962-1036` | 抽 `LootTableManagementActions`（client controller）与纯数据导入校验类；Screen 只转调 |
| A-5 | 中 | `ReaderScanHudState` 职责过重：S2C 解析 + 快照构建 + 生命周期/过期 + 键位消费与 HUD 开关 + 射线选靶 + 描边数据源 + 手持判定。名字只声明 HUD | `client/state/ReaderScanHudState.java`（`:47-84,86-107,113-125,142-161,171-173`）；被 `renderer/SuspiciousReaderRangeHighlight.java:121-137` 直接消费 | 拆「会话数据+快照」与「目标选择/HUD 开关（输入层）」；`hudEnabled` 与按键消费移到 keybind/handler |
| A-6 | 中 | `EnchantmentScreenMixin` 在 Mixin 内写了 **~70 行完整业务逻辑**（hover 槽位反推、注册表查询、候选列表重建、行拼装），违反 AGENTS.md「不在 Mixin 中写大量业务逻辑，mixin 仅作为入口」 | `mixin/client/EnchantmentScreenMixin.java:53-122` | 抽出客户端 helper（如 `EnchantmentRevealTooltipBuilder`），Mixin 只做「取缓存 → 委托 → 回填」 |
| A-7 | 低 | `UiPanelRegistry` 因字段类型是 `Object` 而做补偿式强转 `((LayoutAware) panel)` / `((UiStateful) panel)`，类型信息在 API 上丢失 | `ui/support/UiPanelRegistry.java:22,28,34` | 用交叉类型包装（`Map<String, T>` + `T extends LayoutAware & UiStateful` 已在泛型上成立，避免落 `Object`） |

### 4.2 代码复用

| 编号 | 严重度 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| R-1 | 中 | **anvil / grindstone 两套同名目录**：`appendIfApplicable` 结构完全同构、`hasEyeOfCat` 逐字相同；且 `grindstone` **反向 import** `anvil` 的 `buildInputPenalty` | `client/anvil/AnvilBreakdownTooltipAppender.java:45-80`、`client/grindstone/GrindstoneBreakdownTooltipAppender.java:3,46-84` | 抽 `client/tooltip/BreakdownPreview`：`BreakdownCalculator<M,B>` + `BreakdownLines<B>` + 通用 `appendIfApplicable` + `hasRevealItem`；`buildInputPenalty` 上移共用层。**必须保留**两个 Calculator 与两个 Breakdown record（算法本质不同，合并会造出错误抽象）。预估净减约 60 行并消除 1 处跨包依赖，风险中偏低 |
| R-2 | 中 | 管理页**复制了一整套列表几何/滚动条/截断**：外层类与内部 `LanguageSelectionScreen` 的 `isOverList/listTop/maxScrollRow/clampScroll/renderScrollbar/visibleRowCapacity` 与静态 `trimToWidth` 各一份 | `screen/LootTableManagementScreen.java:235-244,755-825,834-841`、`:879-1025`（`:964-1015,1017-1024`） | 抽 `ScrollableListPanel` 或直接用 `UiScrollView + UiControlGroup`；`trimToWidth` 收进 `TextScroll` 门面一份。文档明确要求「面板内优先复用 kit」，这一页是反例 |
| R-3 | 中 | 三份重复的 tint `MultiBufferSource`/`VertexConsumer` 包装（灵体染色），约 130 行近同实现 | `renderer/MessengerCatRenderer.java:120-170`、`MerchantCatRenderer.java:66-107`、`SwordsmanCatRenderer.java:67-108` | 抽公共 `TintedBufferSource`；顺带把 tint 分量随包装对象构造（现每顶点重复解包 4 分量） |
| R-4 | 中 | 5 个面板都重复右页的 `containsMouse` 边界判断，但**不是逐字相同**：前 4 个右/下边界用 `<=`，`ScenarioDetailPanel` 用 `<`；接口无默认实现。tooltip 挂载另有 **4 种重载口径** | `panel/ItemGridPanel.java:146-149`、`DetailOverlayPanel.java:78-81`、`LogPanel.java:348-351`、`LogDetailPanel.java:183-186`、`ScenarioDetailPanel.java:151-154`；`PagePanel.java:11` | 若抽公共判断，先统一右/下边界口径；tooltip 再考虑收敛到一个入口 |
| R-5 | 中 | 同一分页/几何算法在面板内**各写两份**，输入口径不同，改一处即失配 | `LogDetailPanel.java:384-401` vs `:553-580`；`LogPanel.java:64-90` vs `:641-657`；`LogPanel.java:450-461` vs `:576-586` | 抽出单一几何来源（`GridGeometry` / `noteBadgeBounds`），render 与 `computePageCount` 共用 |
| R-6 | 中 | 参数浮层与选择浮层各写一套**模态 chrome**（遮罩 + 1px 边框 + 羊皮纸底 + 各自居中/尺寸计算） | `overlay/ScenarioSelectionOverlay.java:235-237`、`overlay/ScenarioParamsOverlay.java:261-263` | 抽 `OverlayChrome.panel(...)` 或用 `UiNineSlice` 收口。**注**：不复用 `UiLightbox` 是正确的判断（它按内容查看器+图集设计），不算造轮子 |
| R-7 | 中 | 同包内**两份 pose 感知裁剪**：`TextScroll` 自带一份（不 flush、floor/ceil 向外扩张），`UiTransform.enableScissor` 另一份（flush、向内取整）；`ScrollTextHelper` 是零逻辑转发 | `kit/TextScroll.java:44,51,86-97` vs `kit/UiTransform.java:60-72,74-77`；`ui/support/ScrollTextHelper.java:13-17` | 删 `TextScroll.enablePoseAwareScissor`，统一调 `UiTransform.enableScissor`；`ScrollTextHelper` 要么补默认值重载使其有价值，要么删 |
| R-8 | 中 | **文本色存在第二/第三个事实来源**（文档要求"取色只从语义色表取"）：`JournalTooltipBuilder` 20+ 处直接 `ChatFormatting`（含色表中不存在的 `DARK_GREEN:86`）；三个 Screen 内联 **22 处**语义色字面量（与 `UiTextPalette.Dark` 逐值相同却不 import）；`ItemGridPanel.PENDING_COLOR`/`NAME_COLOR` 与 `UiTextPalette.Parchment.LABEL/NAME` 逐值重复 | `ui/support/JournalTooltipBuilder.java:52,56,63,86,94,109,115,121,124,127,137,142,173,184,190-192,267,272,278,289,295,310,322`；`screen/LootTableManagementScreen.java` 等 3 文件 22 处；`panel/ItemGridPanel.java:43,51` vs `ui/support/UiTextPalette.java:23,33` | 改引 `TooltipBuilder.*` / `UiTextPalette.*`；确需新语义就在色表登记别名。多处本地配色增加回归风险，但不能单凭静态并存断言是 I-2 的唯一根因 |
| R-9 | 低 | 其它重复：子屏「返回父屏 + ESC + isPauseScreen + 居中标题」样板 4 处；目录列 X 公式 4 份拷贝、翻页按钮 Y 公式 2 份；`UiLightbox.Labels` 映射 3 份；两处死代码 | `JournalLogNoteEditScreen.java:30-43,84-95,106-118,152-155` 等 4 处；`CatalogToolbar.java:230-232` 与 `ArchaeologyJournalScreen.java:409-411,427-429,657-660`；`CatalogToolbar.java:100-102,185-188` | 抽 `ParentedScreen` 基类（顺带解决 O-6 的输入快照）；加 `JournalLayout.catalogColumnX/pageButtonY` 派生方法；抽 `LightboxLabels`；删死代码 |
| R-10 | 低 | 逐帧 `new ItemStack` 三处（可静态化）；mod id 字面量 `"unsuspiciousblock"` 硬编码 4 处，未复用 `Constants.MOD_ID` | `widget/PotteryWheelModeButton.java:54`、`toast/CatBondToast.java:48-50`、`toast/JournalUnlockToast.java:93`；`renderer/LanternPetModel.java:29`、`model/MessengerCatClothesModel.java:30`、`renderer/layer/MessengerCatClothesLayer.java:28`、`renderer/LanternPetRenderer.java:29` | 图标栈提为静态常量；命名空间统一走 `Constants.MOD_ID` |

### 4.3 性能

> 共性：项目已有正确范式（revision 门控、按 key 缓存、快照缓存），但未被贯彻。以下按量级排序。

| 编号 | 严重度 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| P-1 | 高 | 日志详情卡每帧重建整份目录投影（见 H-6） | `panel/LogDetailPanel.java:258-259` → `ui/support/JournalFormatHelper.java:47` → `ui/support/ArchaeologyJournalClientState.java:277-279` → `state/ScenarioSimulationClientState.java:167-190` | 按 `catalogRevision` 缓存 `overlay` 结果 |
| P-2 | 中 | `UiControl.render` 对**每个控件无条件 2 次 `graphics.flush()`**（即使文本不超宽、根本不需要裁剪），且 `UiControlGroup.render` 不做视口裁剪 | `kit/UiControl.java:144,158` → `kit/UiTransform.java:69,75`；`UiControlGroup.java:107-110` | 仅当标签确实超宽时才 `enableScissor`；`UiControlGroup` 按 `visible` 跳过。示例页 20 控件≈40 次 flush/帧，参数浮层随可见行线性放大 |
| P-3 | 中 | 搜索框**逐字符**全量重建：为全部表重建条目、关系索引全树 DFS、逐物品 `getString().toLowerCase()`；排序/展开/方向键同样走这条路径。`rebuildWidgets` 还先更新一个随即被丢弃的 panel | `screen/CatalogToolbar.java:110-114,310` → `screen/ArchaeologyJournalScreen.java:100-106,798-810` → `screen/JournalViewModel.java:287-325,374-389,449-490`；`:635` vs `:698` | 加 150~250ms 防抖或失焦才生效；关系索引与「表→物品视图」按 `catalogRevision` 缓存；预建小写名称缓存 |
| P-4 | 中 | 每次选择/方向键**无条件**重建整个网格，且对选中表 + 每个直接子表各做一次 `subtreeItems` DFS（k 个子表 → k+1 次全树遍历） | `screen/ArchaeologyJournalScreen.java:837-848`、`JournalViewModel.java:519-561,564-579`；`loottable/catalog/CatalogQueryIndex.java:80-95` | 按 `(tableId, revision…)` 缓存网格与子表闭包；index 未变化时直接 return |
| P-5 | 中 | 日志**每来一条增量**就整份 `toTag()` + `NbtIo.writeCompressed`（gzip）+ 临时文件 + 原子 rename；单条路径无合并（批量路径有）。对比 `JournalUiPreferencesStore.tick()` 的 dirty+每 tick 一次 | `ui/support/ArchaeologyJournalLogLocalStore.java:157-168,229-233,340-372` | 只置 `logDirty`，把 `save()` 移入既有 `tick()`；或专门 IO 线程 + 单写者队列 |
| P-6 | 中 | `SuspiciousReaderHud` 每帧重做约 **12 次 i18n 解析、20-25 次无缓存 `font.width`、`String.format` 与 `Vec3` 分配**；`:77`/`:93` 与 `:77`/`:95` 同一值算两遍。数据虽已缓存，**派生量未缓存** | `client/hud/SuspiciousReaderHud.java:30-102`（`:47,63-69,74-78,93-97,143-149`） | 把「翻译字符串 + 每行文本 + 测量宽度 + 分组行布局」一起缓存进 `ReaderScanHudState.Snapshot`；`more`/`message` 改按分支惰性计算 |
| P-7 | 中 | `CatFavorHud` 每帧调用 `containsMatching`；该方法逐格扫描背包，**找到匹配项会提前返回**，全部未命中时才走饰品栏流，还会检查便携容器第一层。扫描和分配的实际量取决于物品位置与平台实现 | `client/hud/CatFavorHud.java:54,119-126`；`inventory/InventoryPresenceRegistry.java:72-81,100-109` | 先测量常见携带布局下的成本；若明显，再考虑按物品/背包变化缓存或降低检查频率 |
| P-8 | 中 | `PanningMediumIndex` 每帧对每个 32 格内淘洗点做 `split` + `UUID.fromString` + `HashMap.put` 重建索引 | `client/pan/PanningMediumIndex.java:31-39,48-61`；调用点 `renderer/ShimmerSurfaceRenderer.java:53-57` | 解析结果缓存到 `ShimmerEntity`（数据变化才重解析），`refresh` 只 `putAll` |
| P-9 | 中 | `CatFavorShieldRenderer` 每帧重建**完全静态**的球体几何：2304 顶点 + ~1600 次 `sin/cos`，且自定义 `SHIELD_TYPE` 带 `sortOnUpload=true` | `renderer/CatFavorShieldRenderer.java:43-63,94-97,103-133` | 单位球顶点静态初始化一次，每帧只改 alpha；评估关掉排序 |
| P-10 | 中 | `TextScroll` 的 **String 门面每次调用都 `font.width(text)`**（原版无缓存，会 new `MutableFloat` 并逐码点测量），而逐帧调用点密集 | `kit/TextScroll.java:60-71`；调用点 `panel/DetailOverlayPanel.java:92,111,122,130,177`、`ItemGridPanel.java:254,288,292,344,407,415`、`CatalogPanel.java:177,182,216`、`LogPanel.java:563,571` 等 | 增加可传「已测宽度」的重载（`FormattedCharSequence` 重载已如此），或对 String 做缓存 |
| P-11 | 中 | `LogDetailPanel` 渲染循环：**每图标** `g.flush()`（打断批次）+ **2 次 `ItemStack.copy()`**（复制组件补丁，而栈只在悬停时用）+ 角标 **9 次 `drawString`** | `panel/LogDetailPanel.java:423-464,474-491`（`:431,449,463`） | `IconSlot` 改持 `LootDisplayEntry`，tooltip 时再取栈；`flush()` 移出循环；描边改用原版阴影或只画 4 向 |
| P-12 | 中 | 面板 render 路径**逐帧重建 Component/字符串/集合**并重复测量：每可见格子 `displayName().getString()` + `formatProbability(...).getString()`；`List.copyOf(tagGroups.values())`；每组每帧 3 条 stream；tooltip 列表逐帧重建；`ScenarioResultView` 每行每帧 `new UiRect`×3 | `panel/ItemGridPanel.java:188,298,407-417,602-606,712-722`、`CatalogPanel.java:175-183,213-215`、`ScenarioPanel.java:68,77-95`、`ScenarioResultView.java:123-125,138-157`、`LogPanel.java:558-572` | 按 `(probability, declaredChances, uncertaintyLevel)` 或目录 revision 缓存格子文案；列布局只在宽度变化时重算。正例：`ScenarioPanel.sceneLabel:118-124` |
| P-13 | 低 | 其它逐帧小分配：进度条每帧构建 Component+String；悬浮提示每帧建集合；`PotteryPreviewRenderer` 每帧包装 record 与 `new ItemStack`；`LanternPetModel.setupAnim` 每帧全树 `getAllParts().forEach(resetPose)`（本模型无其它动画）；`ShimmerSurfaceRenderer` 循环不变量重复取值、冻结分支仍查流体 | `screen/ArchaeologyJournalScreen.java:440-445,486-487,493-501`；`PotteryPreviewRenderer.java:42-43,49-54`；`LanternPetModel.java:135-152`；`ShimmerSurfaceRenderer.java:75,100-122` | 按变化门控；提前取循环不变量 |
| P-14 | 低 | `UiLightbox.render` 每帧两次 `controls.targetAt`（同一坐标线性扫描两遍）；`UiLightbox.layout` 对同一读数测两次宽度 | `kit/UiLightbox.java:230,236`；`:309-310` | 命中结果存局部变量；删冗余测量 |

### 4.4 信息展示

| 编号 | 严重度 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| I-1 | 高 | 灯箱控制栏对比度 1.06:1（见 H-2） | `kit/UiLightbox.java:107,114,169-172,283-295` | 构造期注入 `UiControlStyle.DARK` |
| I-2 | 高 | 6 处对比度 < 4.5:1（见 H-3） | 见 H-3 表 | 见 H-3 |
| I-3 | 中 | **同一物品两种披露口径**：未发现物品在格子刻意隐藏数字（画未知图标 + 「待解析」后提前 return），但悬停 tooltip 仍无条件输出精确概率与声明触发率；合集入口却严格不泄露 | `panel/ItemGridPanel.java:368-379` vs `:534-539`；`ui/support/JournalTooltipBuilder.java:51-64,68-74,155-177`；`ItemGridPanel.java:641-654` | 若为有意，tooltip 里加状态口径前缀；若无意，给概率段加 `data.discovered()` 门控 |
| I-4 | 中 | `CatBondToast` 详情**静默截断为 3 行**：无省略号、无「更多」提示；跨阶段时把最多 6 项能力名串成一行 | `ui/toast/CatBondToast.java:44-47,57-64,97-117` | 超长改逐阶段 toast（`JournalUnlockToast` 已有轮换实现），或末行加省略号并在聊天栏补全 |
| I-5 | 中 | 管理页**无空态**：筛选/搜索无匹配时列表区完全空白且无文案，用户无法区分"没有匹配"与"界面坏了" | `screen/LootTableManagementScreen.java:171-196,246-293,371-398`；32 个该页 i18n 键中无 empty/no-result 文案 | 新增 `...empty` / `...empty_search` 键并居中绘制（两语言同步） |
| I-6 | 中 | `source.resource` 文案**无 `%s` 占位符**（值与「只读」同文案），`sourcePackId()` 被静默丢弃 → 资源包来源永远显示不出来，且与权限短语混淆 | `screen/LootTableManagementScreen.java:631-637`；lang 两文件对应键 | 改为「来源：资源包 %s」 |
| I-7 | 中 | `CatFavorHud` **只有裸数字**：`CatBondStage.translationKey()`（两语言均已存在）在整个 HUD 里从未被调用，阶段只能靠颜色区分；`lives == 0` 时不画角标，玩家无法区分"未解锁"与"已耗尽" | `client/hud/CatFavorHud.java:90-99`；`cat/CatBondStage.java:47-49` | 补阶段名文本/悬停提示；`lives==0` 用灰化 `0` 或独立文案区分 |
| I-8 | 低 | 网格页头部读数用未解释的 `L=` / `N=` 缩写，tooltip 也不解释；详情页同类信息却写成「工具 %s · 幸运 %s · 抽样 %s」 | `panel/ScenarioPanel.java:77`；lang `simulation.summary` 中英值完全相同 | 改带语义标签的短格式，或 tooltip 首行补图例 |
| I-9 | 低 | `WelcomeStatsPanel` 用 `plainSubstrByWidth` **静默截断**，无省略号、无 tooltip（同仓 `LootTableManagementScreen:840,1023` 已有更完整写法） | `panel/WelcomeStatsPanel.java:210,221,228` | 统一加省略号；短句可复用 `TextScroll` 悬停滚动 |
| I-10 | 低 | `IconButton` 绘制仅用 `active` 抑制悬停色，禁用时仍可能显示 tooltip；`BookmarkToggleButton` **已用 `active` 选择普通纹理**，但展开宽度仍取决于 `isHovered()`/`toggled`，禁用时可能展开。原报告称两者「完全忽略 active、外观完全相同」不属实 | `widget/IconButton.java:151-160,186`；`widget/BookmarkToggleButton.java:75-89` | 若需要明确禁用态，分别检查两控件的纹理、展开与 tooltip 行为，再统一视觉口径 |
| I-11 | 低 | 4 处用户可见文案未走 i18n key（纯数字/符号拼接），且同一坐标两种写法；进度读数每帧构造字符串 | `panel/DetailOverlayPanel.java:59,107`、`LogPanel.java:569`、`JournalUnlockToast.java:98` | 新增格式串键；坐标统一到一处格式化 |
| I-12 | 低 | `alpha` 跳过绘制的口径在 HUD 与描边间不统一（`< 4` vs `<= 0`），淡出尾部仍构建不可见顶点 | `client/hud/SuspiciousReaderHud.java:34-36` vs `renderer/SuspiciousReaderRangeHighlight.java:122-123` | 抽共享判定 `alpha * 255 < 4` |
| I-13 | 低 | `JournalUnlockToast` 轮换指示器注释说 `"x/n"`，实现只画剩余条数（`"x" + entries.size()`），无总数；物品解锁复用了表解锁标题常量 | `ui/toast/JournalUnlockToast.java:96-100,144` | 走 i18n 的 remaining 键，或按注释补总数 |
| I-14 | 低 | `ItemGridPanel` 数量缩写未固定 `Locale.ROOT`（同仓其它格式化都已固定），逗号小数点 locale 下会画成 `1,2K` | `panel/ItemGridPanel.java:426-441` | `String.format(Locale.ROOT, "%.1f", v)` |

### 4.5 其他（功能缺陷、生命周期、边界、渲染状态、i18n、文档）

| 编号 | 严重度 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| O-1 | 高 | `CatFavorHud` 着色器颜色未配对恢复（见 H-4） | `client/hud/CatFavorHud.java:73,86` | try/finally 恢复 |
| O-2 | 高 | 三个猫渲染器吞掉原版可见性/发光语义（见 H-5） | `renderer/MessengerCatRenderer.java:57` 等 3 处 | 保留 `null` 与 `outline` 分支 |
| O-3 | 高，已实机复现 | 点放大镜展开后直接键入无效；再点一次输入框后可以输入（见 H-1） | 用户实机反馈；`screen/CatalogToolbar.java:312-315`、`screen/ArchaeologyJournalScreen.java:227-230` | 展开时交给宿主焦点，重建后恢复，并复测两条打开路径 |
| O-4 | 中 | **`LootTableManagementClientState` 没有任何 `reset()`**，两端断连清理清单都漏了它 → 切服后首次打开管理页的一个往返窗口内展示上一服务器的条目，且 `canEdit` 可能仍为 true | `ui/support/LootTableManagementClientState.java:12-29`；`fabric/.../UnsuspiciousBlockFabricClient.java:99-106`；`neoforge/.../UnsuspiciousBlockNeoForgeClient.java:175-182` | 补 `reset()`（清 4 集合 + `canEdit=false` + `revision++`）并加入两端清单 |
| O-5 | **已撤销：误报** | `Float.parseFloat` 虽接受 `NaN`/`Infinity`，但紧接着的 `new ScenarioParams(...)` 在构造器中拒绝非有限值和越界值，`ScenarioParamsOverlay.confirm()` 捕获 `IllegalArgumentException` 并显示 `luck_invalid`；这些值不会经该路径确认或持久化 | `overlay/ScenarioParamsOverlay.java:319-336`；`loottable/simulation/ScenarioParams.java:60-71` | 无需按原建议修复；保留编号便于追溯 |
| O-6 | 中 | **窗口 resize 静默丢失未提交输入**：三个子屏未覆盖 `repositionElements()`，原版链路会 `rebuildWidgets → init()` 重置输入框（备注/保留上限/搜索词），而作者已为 `ArchaeologyJournalScreen` 专门做了捕获恢复 | `screen/JournalLogNoteEditScreen.java:46-81`、`JournalLogRetentionScreen.java:42-48`、`LootTableManagementScreen.java:100-168`；对照 `ArchaeologyJournalScreen.java:310-352` | 把输入现值纳入各屏状态快照，或用 `ParentedScreen` 基类统一 capture/restore |
| O-7 | 中 | 透视描边 `NO_DEPTH_LINES` **关深度测试却保留深度写入**：复用 `RenderType.lines()` 的 `COLOR_DEPTH_WRITE`，而原版同类穿透渲染（`TEXT_SEE_THROUGH`）用 `COLOR_WRITE` 不写深度 | `renderer/SuspiciousReaderRangeHighlight.java:49-63`；原版 `RenderType.java:469,482-483,594-609`、`RenderStateShard.java:626-641` | setup 补 `depthMask(false)`、clear 补 `depthMask(true)`（自定义 setup/clear 已存在，改动最小） |
| O-8 | 中 | 同类几何入口对「容器过小/负尺寸」**防御策略不一致**：`UiScrollView` 钳到 ≥0、`UiLightbox` 钳到 ≥1、`UiDocument.setViewport`/`UiControl.setBounds` **直接抛 IAE** | `kit/UiScrollView.java:38-41`、`UiLightbox.java:184-191`、`UiDocument.java:80-86`、`UiControl.java:58-62`、`UiRect.java:5-7` | 统一为钳制，或在 javadoc 显式声明「负尺寸抛 IAE，调用方负责钳制」（现存调用点均已自行钳制，当前不可达） |
| O-9 | 中 | `UiDocument` 的 **Frame 子文档永远收不到鼠标坐标**：遍历 Frame 块时调用两参 `render`（鼠标被置 `NaN`），子文档悬停恒为 false → Frame 内超宽文本永不可悬停滚动 | `kit/UiDocument.java:237-240,246,262-267,291`；示例页 `sample/UiKitDebugScreen.java:503` 对根文档也只传两参 | 把鼠标透传给子文档，或显式在 javadoc 声明「Frame 内不参与悬停」。当前生产代码只构造 `UiNode.Row`，影响面限于契约与示例页 |
| O-10 | 中 | **第六节列出 12 处文档与代码不一致** | 见第六节 | 以代码为准修订文档，或补实现 |
| O-11 | 低 | `EditBoxMixin` 的 **6 个 `@WrapOperation` 全为 `require = 0`**：任一平台签名不匹配时静默不生效（输入框重新出现文字阴影），无启动期报错；且 6 个方法近乎重复 | `mixin/client/EditBoxMixin.java:21-102`；`unsuspiciousblock.mixins.json` | 至少对两端之一把 `require` 提为 1，或加启动期自检；6 份包装可抽公共 lambda |
| O-12 | 低 | `ClientLanguageMixin` 把 hook 挂在 **`ClientLanguage.getOrDefault`（全局文本解析热路径）** 与 `has` 上：每次翻译解析都多一次 mixin 回调 + `startsWith(KEY_PREFIX)`。当前代价低（短路是字符串前缀比较），但把第三方代码放进了「每个字符串都要走」的路径；且首次命中生成 key 时会在**调用线程（可能是渲染线程）**同步遍历资源栈、GSON 解析全部同语言 JSON | `mixin/client/ClientLanguageMixin.java:18-40`；`ui/support/ClientLootTableLanguageStore.java:54-57,80-112` | 保持 guard 廉价（勿在其中加 map 查询/正则）；把语言索引构建移到资源重载路径预热，避免首帧卡顿 |
| O-13 | 低 | 导入失败一律折叠成「JSON 格式无效」，异常被丢弃且无日志（真实原因可能是文件过大/IO 错误/条目超限） | `screen/LootTableManagementScreen.java:668-675` | 至少 `Constants.LOG.warn`；对"过大/IO"给独立文案键 |
| O-14 | 低 | UI kit 边界脚本只有 **3 条黑名单规则**，「只允许 java/MC/JOML/LWJGL/annotations」这条**白名单无法被验证**（新增任意第三方 import 不会被拦）；`package-info` 的依赖清单还漏了 LWJGL（虽然 `UiFocusManager:4`、`UiLightbox:400,405,409` 都在用） | `scripts/check-ui-kit-boundaries.ps1:44-64`；`kit/package-info.java:34-36`；`docs/dev/internals/ui-kit-api.md:43` | 加一条白名单规则，或把 javadoc 措辞收窄为"不得 import 项目包/平台 API/资源常量（脚本检查这三条）" |
| O-15 | 低 | **21 个 i18n 死键**（定义了但代码从不使用）：`simulation.{luck_help,tool_help,suggestion,probe,apply,find,find_help,assumptions,outcome_more,degraded,degraded_help,notes}` 12 个 + `archaeology_journal.{progress,too_small,first_unlock_time,unknown_item,child_trigger_probability,catalog}` 6 个 + `item_hint.enchanted_random` + `hand_of_cat.ability.cat_companion{,.desc}` | lang 两文件；判定时已排除全部动态拼接族与 data/config 驱动键，并逐条做全仓子串复核 | 两文件同步删除；`cat_companion` 与现存 `deterrence` 语义重叠，勿复活旧键 |
| O-16 | 低 | `UiLightbox` 把 6 个字形按钮标签与 `"%"` 缩放读数格式硬编码在 kit 内（`Labels` 无读数格式入口）；`UiImageView` 的 `RECHECK_INTERVAL = 20` 注释写「约 1 秒」，实际按帧计数（60fps 下约 0.33 秒） | `kit/UiLightbox.java:283-295,365`；`kit/UiImageView.java:31-32,218-223` | `Labels` 补 `zoomReadout(int)`；注释改为按帧口径或改毫秒计时 |
| O-17 | 低 | 存在散落的颜色字面量（`CatFavorHud` 角标 `-12525360`、`BookmarkToggleButton` 标签 `0x3D2B1F`）；`LogPanel` 的 `"✎"` 是语言中立图形符号，**不能据此判为漏本地化** | `client/hud/CatFavorHud.java:114`；`panel/LogPanel.java:450-461,576-586`；`widget/BookmarkToggleButton.java:89` | 颜色可走常量/语义色表；符号是否替换属于视觉设计选择 |
| O-18 | 低 | `ReaderScanHudState.hudEnabled` 不持久化且断连时恢复 `true`；`CatFavorHud.BADGE_SCALE = 1.0F` 与类注释「半尺寸」矛盾（且每帧有一次 `pushPose/scale(1,1,1)/popPose`）；**`BookmarkToggleButton`** 注释写 30px、常量是 38px（原报告误写为 `IconButton`） | `client/state/ReaderScanHudState.java:33,190-193`；`CatFavorHud.java:23,37,111-115`；`widget/BookmarkToggleButton.java:18,34` | 按设计意图统一注释与常量 |

---

## 五、已核实无问题的正向结论（节选）

以下为**逐项核对后确认正确**的设计与实现，是后续重构可以依赖的不变式：

**UI kit（A 切片）**
- kit 的 26 个源文件 import 只有 `java.*`/`net.minecraft.*`/`org.jetbrains.*`/`org.joml.*`/`org.lwjgl.*`，**无项目包、无平台 API**；`scripts/check-ui-kit-boundaries.ps1` 实跑 **exit 0**（22 kit + 420 非 client 文件）。
- 公开入口清单三处一致：`package-info.java:6-26`（5 组 21 项）、`ui-kit-api.md:29-33`、kit 内无包私有顶层类型。
- `UiMetrics` 生产全关（`ScenarioConditionView:30` 传 `false`；`ScenarioFrameView:34-35` 受开发开关门控），禁用时不读时钟。
- 排版缓存失效粒度正确：`dirty` 只在 `setContent`/视口**尺寸**变化/`invalidateLayout` 置位；`UiTransform` 的平移缩放**不触发重排**。
- 命中与可见区定位是二分（`UiDocument.firstBlockAt:343-352`），render 从首个可见块开始并提前 break。
- 逐帧绘制/命中**不创建 kit 自有对象**（`FormattedCharSequence` 与矩形在排版期缓存）。
- 滚动条与偏移几何自洽（`offset` 恒钳 `[0,maxOffset()]`；`ensureVisible` 不触发重排）。
- NPE 与 `action == null` 语义在全部调用点一致；`setFocused` 只改绘制状态；`UiFocusTarget` 契约在 `UiControl` 上自洽。
- 灯箱：`Labels` 7 个方法全部被消费；无图集时不建导航控件；`fit` 推迟到尺寸就绪不会死循环；tooltip 唯一来源（先 `controls.targetAt` 再 `content.hit`）；`UiLightbox` 与 `OverlayLayer` 单向依赖。

**页面与状态（B/E 切片）**
- 两端客户端**注册项逐项对称**：按键 5 个、实体渲染器清单、模型层清单、BlockEntity 渲染器、MenuScreens×2、tooltip 组件、S2C 遍历清单、世界渲染阶段、HUD、tick 驱动、断连重置（唯一遗漏见 O-4）。
- 2s 重同步冷却**真实存在**且冷却期内直接 return 不排队；增量包间隙检测与文档一致。
- **UI 偏好写盘已做 dirty + 每 tick 至多一次合并**，不是每次交互写盘；搜索文本未逐键持久化。
- 面板状态保存/恢复成对；`LayoutAware` 注册成对。
- 跨维度/到期清理完整（`ReaderScanHudState.tick` 按 level 判定清理）；`Math.clamp` 的前置不等式恒成立。
- 六类模态入口全覆盖；`ExternalLinkButton` 的 1.21.1 三参重载签名已核对存在。

**面板与信息展示（C 切片）**
- **展示优先级链实现正确**：`formatProbability` 严格按「不可用状态词（不给任何数字） → 声明触发率 → 模拟值」；子表入口走同一条链，文档记录的历史回归已修。
- 四态文案与文档一致，`0%` 只留给静态不可达，零命中附抽样次数且不给置信上界。
- 未解锁**合集入口**不泄露身份/成员数/概率；tooltip 全部走原版渲染，无自绘浮层；条件树前缀未用近黑的 `DARK_GRAY`。
- 焦点/Tab 顺序与文档一致；稳定 key 复用与「不逐帧重建控件」已落实（选择浮层按场景下标 `obtain`，结果视图仅在 `(first,end,width)` 变化时 `configure`）。
- Scenario 7 个类**无功能重叠**（逐个核对职责）；放大浮层确实复用 kit 灯箱而非自造模态。
- 面板不反向依赖 `screen` 包（26 个文件无一 import）。

**HUD 与渲染（D 切片）**
- `SURFACE_TYPE`/`SHIELD_TYPE` 的 depthMask/cull/blend 配对完整；`NO_DEPTH_LINES` 的 lineWidth 与深度**测试**配对正确（漏的只是深度**写入**，见 O-7）。
- `H` 键只影响两个面板与聚焦强调、不动基础描边，与文档一致。
- 两个 HUD 的 16 个 i18n key 中英齐全；阶段配色与 `cat-favor.md` 表格逐值一致。
- `MerchantCatRenderer`/`SwordsmanCatRenderer` 覆写 `scale` 未调 `super` 无害（原版是空方法）；`ModelPart.copyFrom` 确实不含 `visible`，故 `MessengerCatClothesModel.syncPose` 的显式复制不可删。
- 每帧 `RenderType.entityTranslucentEmissive(loc)` **不构成分配**（原版是 `Util.memoize` 缓存函数）。
- 第一人称淘盘两端对称（Fabric mixin vs NeoForge `RenderHandEvent`）。

**i18n（C 切片全局核对）**
- `en_us`/`zh_cn` **各 909 键，双向差集为空**，两份 JSON 均可严格解析；无缺失键；`client/**` 无 CJK 硬编码自然语言（只有符号与分隔符）；20 个中英同值键均为语言中立项（非漏译）。

---

## 六、文档与代码不一致汇总

| # | 文档位置 | 文档说法 | 代码事实 |
|---|---|---|---|
| 1 | `docs/dev/subsystems/client-ui.md:66` | `ModKeyBindings` 有 **4 个**按键绑定 | 实际 **5 个**（`keybind/ModKeyBindings.java:16,24,32,40,48`；两端注册数也都是 5） |
| 2 | `client-ui.md:115` | `CatFavorHud` 在**快捷栏上方**渲染 | 实际在快捷栏**左侧**（`client/hud/CatFavorHud.java:61-64`，类注释亦为"左侧"） |
| 3 | `client-ui.md:119` | `CatFavorHud` 含"**能力解锁状态指示**" | 全类只有图标、恩惠数值、呼吸 tint、九命角标，无能力指示 |
| 4 | `docs/dev/foundation/config-and-integrations.md:158`、`docs/dev/subsystems/cat-favor.md:269` | 客户端提供猫之恩惠 **HUD 显示开关** | 仓库内不存在该开关（grep 只命中解析仪 HUD 的 `hudEnabled`） |
| 5 | `client-ui.md` §1 代码地图「UI 分层」行 | `ui/{entry,layout,panel,screen,support,toast,widget,tooltip}` | 漏了 `ui/overlay/`（3 文件）与 `ui/sample/`（2 文件），而 §3 又引用了 `OverlayLayer`；`journal-ui-internals.md` §1 的目录树同样漏 |
| 6 | `docs/dev/internals/ui-kit-api.md:113`、`journal-ui-internals.md:182` | kit 内部存在 **4 组双向依赖**（含 `UiControlGroup ↔ UiScrollView`） | 实测只有 **2 组单向代码依赖**（`UiDocument→TextScroll`、`UiControl→TextScroll`）+ 1 组实现关系（`UiImageView implements UiLightbox.Content`）；`UiControlGroup ↔ UiScrollView` **两个方向都没有代码引用**，只有 javadoc。拆包论据需重新论证 |
| 7 | `kit/UiLightbox.java:169` javadoc、`journal-ui-internals.md:160` | 灯箱"默认用暗底样式（`UiControlStyle.DARK`）" | 实际默认 `PARCHMENT`，`setStyle` 零调用者（见 H-2） |
| 8 | `journal-ui-internals.md:45` | 「资源已有名称时显示 **Resource Pack 来源**」 | `source.resource` 文案无 `%s`，来源永远显示不出（见 I-6） |
| 9 | `journal-ui-internals.md:107` | 「`render(graphics, font, mouseX, mouseY)` 逐行判悬停并自持滚动计时」 | Frame 子文档走两参重载，鼠标恒 `NaN`，永不悬停滚动（见 O-9） |
| 10 | `client-ui.md:111`、`:203`、`journal-ui-internals.md` 多处 | 「面板内优先复用 kit，而不是手算坐标」「Screen 只做组合 + 输入分发 + 生命周期」 | `LootTableManagementScreen` 整页手算坐标 + 复制列表几何 + 在 Screen 内做网络组包/文件 IO（见 A-4、R-2） |
| 11 | `kit/package-info.java:34-36` | 「只依赖 Minecraft 客户端类型、Java 标准库与 JOML/annotations」 | 漏了 LWJGL（`UiFocusManager.java:4`、`UiLightbox.java:400,405,409`）；同句在 `ui-kit-api.md:43` 里是对的 |
| 12 | `client-ui.md` 自身篇幅 | 写作约定要求 `subsystems/*.md` **≤200 行** | 该文 238 行，超限 |

---

## 七、整改路线（建议）

### P0 — 先修"会误导用户 / 静默失效"的缺陷（小改动，高收益）
1. **O-3 / H-1（已实机复现）** 修复点击放大镜后的宿主焦点交接与 `rebuildWidgets()` 后的恢复；复测直接键入、再次点击输入框及 C 键打开路径。
2. **H-2 / I-1** `UiLightbox` 构造函数注入 `UiControlStyle.DARK`（1 行）+ 校正两处文档。
3. **H-4 / O-1** `CatFavorHud` 的 `setShaderColor` 改 try/finally 配对。
4. **H-5 / O-2** 三个猫渲染器的 `getRenderType` 恢复 `null` 与 `outline` 分支。
5. **H-3 / I-2** 6 处对比度不达标的色值压暗（沿用文档已记录的修复口径）。
6. **O-4** `LootTableManagementClientState` 补 `reset()` 并加入两端断连清单。

### P1 — 消除结构性复用债与逐帧浪费（收益最大的一批）
7. **H-6 / P-1** `getCatalog()` 按 `catalogRevision` 缓存 —— 单点消除一个逐帧 O(目录) 重建。
8. **P-2** `UiControl.render` 按需裁剪（去掉绝大多数 `flush()`）。
9. **R-1** 抽 `BreakdownPreview` 统一 anvil/grindstone 的编排层（保留两个 Calculator）。
10. **R-2 + A-4** `LootTableManagementScreen` 抽出列表组件与 actions 层，复用 kit。
11. **P-3 / P-4** 搜索防抖 + 网格/关系索引按 revision 缓存。
12. **R-8** 文本色统一到 `TooltipBuilder`/`UiTextPalette`，降低对比度回归风险。
13. **P-5** 日志落盘改为 dirty + tick 合并。
14. **A-6** `EnchantmentScreenMixin` 的业务逻辑外移（同时满足项目 mixin 准则）。

### P2 — 架构收敛、文档对齐、体验补全
15. **A-1** 把 `TooltipBuilder`/`UiTextPalette` 移出 `client` 包，切断服务端可达类对客户端包的引用；`ArchaeologyJournalUi.open()` 与 Shift 检测走桥接。
16. **A-2** 收敛 `client/state` ⇄ `client/ui/support` 双向依赖；**A-3** 拆 `JournalViewModel`；**A-5** 拆 `ReaderScanHudState`。
17. **R-3 ~ R-7** 其余复用抽取（tint BufferSource、`containsMouse`、分页几何、模态 chrome、裁剪实现）。
18. **I-4 ~ I-7, O-6** 信息展示补全：toast 截断、管理页空态、资源包来源、HUD 阶段名/九命=0、resize 输入快照。
19. **O-9, O-11, O-12, O-14** kit 契约与守卫补强（Frame 鼠标透传 / `require` 提级 / guard 保持廉价 + 预热 / 白名单边界规则）。
20. **第六节全部 12 条** 文档修订；**O-15** 删除 21 个死键（两语言同步）。

---

## 八、需实机/进一步确认的前提

除 H-1/O-3 的放大镜打开路径外，以下结论**未被实机验证**，实施前建议用运行或 Profiler 收口：

1. **H-1/O-3 的剩余验证范围**：用户已确认点击放大镜后直接键入失败、再点一次输入框后可输入。尚需验证 C 键带物品搜索打开后能否直接键入，以及修复后放大镜与 C 键路径是否都能立即输入。
2. **H-2 的观感**：1.06:1 为公式计算值；若宿主在打开灯箱前后改了 `textColor`（当前无调用者）需重算。
3. **H-4 的染色范围**：取决于平台 HUD 事件注入点之后同一 render pass 内还有哪些批次未 flush。
4. **H-5 的设计意图**：灵体是否**有意**无视隐身；发光场景的实际触发条件。
5. **O-7 的可见后果**：透视描边写入的深度是否在真实场景造成可见遮挡。
6. **R-7/M-2（kit 裁剪）**：判定依赖"1.21.1 屏幕渲染期间 `GuiGraphics.managed == false`"这一推断链；原版 `AbstractSelectionList.renderWidget` 同样不 flush 却能正确裁剪，说明推断可能缺一环——需在 Scale=2 下悬停超宽文本观察是否越出文本带。
7. **性能绝对量级**：P-1~P-14 给出的是可静态复现的**调用次数与分配次数**，实际帧耗时与是否影响 60fps 需 Profiler。
8. **P-3/P-4 的规模前提**：仓库只有 14 个自带战利品表 JSON，但目录由**服务端下发**（含其它模组与全部原版表），规模上限未知——目录越大，这两条越接近高严重度。
9. **I-3 是否应隐藏概率**：`getTooltipData` 显式传了概率，看起来有意为之；文档只约束合集入口，需设计确认。
10. **R-1 的槽位泛型**：`AnvilMenu`/`GrindstoneMenu` 的 INPUT/ADDITIONAL/RESULT 槽常量能否直接统一未查原版源码（若不能，用 `record SlotRoles(...)` 传参即可，方案不受影响）。
11. **O-6 的触发路径**：需确认原版 `Minecraft.resizeDisplay` 确实走 `screen.resize → repositionElements`（`ui-kit-api.md` 与本条均按此推断）。
12. **P-7 的饰品栏缓存**：Curios/Trinkets 内部是否已缓存流（库在仓库外）。
13. **O-12 的线程归属**：`resourceLanguage` 首次构建可能落在渲染线程，需确认两端 `ClientLanguage` 重载时序。
14. **O-11 的两端命中**：`EditBoxMixin` 6 个 `require = 0` 包装在 NeoForge/Fabric 上是否都实际生效。
15. **`JournalTooltipBuilder.buildChildTable:114` 无 null 检查**：当前调用链不产生 null（下游用 `Probability.unknown` 兜底），但同字段在 `ItemGridPanel.formatProbability:456` 却显式判 null，两处契约不一致，建议补防御。
16. **`JournalTooltipBuilder:83-84` 用"译文相等"识别 approximate 提示**：任一语言改措辞即静默失效，建议改读 `TranslatableContents.getKey()`。
17. **两处组件边界脚本无法覆盖的部分**：kit 白名单（O-14）与"专用服务端不加载客户端类"的运行时验证（`ui-kit-api.md` 门槛⑤自认未满足）——与 A-1 是同一问题。

---

## 九、审查片段与后续

五份分工片段保留在 `build/ui-review/`（`build/` 不入库，可随时删除）：

| 片段 | 覆盖 | 发现 |
|---|---|---|
| `build/ui-review/ui-kit-reviewer.md` | kit / overlay / sample / 边界脚本 | 高 1 / 中 6 / 低 6 |
| `build/ui-review/screens-reviewer.md` | screen / layout / entry / UI 根类 | 高 1 / 中 9 / 低 7 |
| `build/ui-review/panels-reviewer.md` | panel / widget / tooltip / toast + 全局 i18n | 高 1 / 中 8 / 低 11 |
| `build/ui-review/hud-render-reviewer.md` | hud / renderer / model / pan | 高 2 / 中 7 / 低 5 |
| `build/ui-review/state-support-reviewer.md` | ui/support / state / anvil / grindstone / 两端入口 | 高 1 / 中 9 / 低 6 |

片段含本文未完全展开的逐条证据、原版源码行号引用与「已核实无问题」全表；需要定位某一项时优先查对应片段。

**原始审查未修改任何源码或资源文件，未执行 Gradle 构建，未启动游戏；后续复核已执行 Gradle 构建，且用户实机复现 H-1/O-3。** 待办条目完成后，请同步到 [客户端与 GUI](../dev/subsystems/client-ui.md) 或本目录对应文档并删除本文条目。
