# UI kit 公开 API 与兼容策略

> 面向第三方宿主的公开契约：公开入口清单、依赖边界与可执行检查、接入步骤与最小示例、行为约定、兼容策略，以及未来拆包的逐条门槛结论。
> **机制与实现口径**（排版缓存、裁剪边界、命中与 tooltip 的唯一来源、焦点失效与变化通知的时序、灯箱 fit 与 resize 的区别、平移钳制公式等）的权威是 [笔记 GUI 内部机制](journal-ui-internals.md)；本页只声明**对外契约**，不复制那些推导过程。
> 本文件属于 `internals/` 层，不参与任务导航，只被 [开发者文档](../README.md)、[笔记 GUI 内部机制](journal-ui-internals.md) 与 [客户端与 GUI](../subsystems/client-ui.md) 链接。

## 1. 定位与权威分工

| 主题 | 权威 | 说明 |
|---|---|---|
| 机制与实现口径 | [笔记 GUI 内部机制](journal-ui-internals.md) | 为什么这样做、实际怎么算、性能与坐标口径 |
| 公开入口、接入步骤、行为约定与兼容策略 | 本页 | 第三方宿主可以依赖什么、怎么接、什么会变 |
| 公开边界的**代码声明** | `common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/kit/package-info.java` | 清单以它为准；本页「公开入口清单」与它必须一致 |
| 依赖方向的**可执行检查** | `scripts/check-ui-kit-boundaries.ps1` | 五条边界规则，退出码 0/1 |
| 跨子系统契约（注册、网络、平台抽象、文本规范） | `docs/dev/foundation/` 对应篇 | 见 [开发者文档](../README.md) 的任务导航 |

三条声明：

- 本页只描述**已落地**的代码；尚未落地的能力一律写在「拆包就绪门槛」的结论里，不当作现状。
- 「公开」= **接口稳定性承诺**，不等于物理拆包：kit 目前仍在 mod 的 `common` 客户端包里（见「本轮范围声明」）。
- 三处描述冲突时：机制细节以 [笔记 GUI 内部机制](journal-ui-internals.md) 为准，接口与兼容承诺以本页为准，清单以 `package-info.java` 为准。

## 2. 公开入口清单

第三方宿主可直接依赖的入口，按能力分组（与 `package-info.java` 的分类一致）：

| 分组 | 入口 |
|---|---|
| 内容排版 | `UiDocument`、`UiNode`（`Row` / `Gap` / `Divider` / `Frame` / `FrameSpec` / `InlineIcon`）、`UiIcon`（`Item` / `Sprite`）、`UiTransform`、`UiMetrics`、`TextMeasurer`、`TextScroll` |
| 控件与交互 | `UiControl`、`UiControlStyle`、`UiControlGroup`、`UiScrollView`、`UiLinearLayout` |
| 焦点 | `UiFocusTarget`、`UiFocusManager` |
| 灯箱 | `UiLightbox`（含 `Content` / `Gallery` / `Labels`）、`UiImageView`、`LightboxImage` |
| 命中与几何 | `UiRect`、`UiTarget`、`UiAction`、`UiNineSlice` |

**内部实现**（同为 `public`，但**不承诺兼容**，宿主不应依赖）：kit 里没有独立的内部类型，内部口径都落在各类的非公开成员上——`UiDocument` 的排版缓存与私有几何公式、`UiMetrics` 的计时细节、`UiTransform` 的裁剪工具等。它们可以随实现改动而不进变更记录。

**不属于 API**：`client/ui/overlay/`（`OverlayLayer`、`LightboxOverlay` 等宿主适配层）与 `client/ui/sample/`（`UiKitDebugScreen`、`UiKitSampleScreen` 示例层）都允许依赖项目常量与平台服务，是**宿主层代码**，不是可发布的库入口。

同步要求：新增/删除公开入口时，同时改 `package-info.java` 与本页「公开入口清单」；只改一处会导致第三方照着过期的清单接入。

## 3. 依赖方向与可执行检查

kit 允许依赖：Minecraft 客户端通用类型（`Component` / `ResourceLocation` / `GuiGraphics` / `Font` 等）、Java 标准库、JOML、LWJGL（`org.lwjgl.glfw.GLFW` 按键常量）与 JetBrains annotations。kit 不得依赖：任何项目包、两端 loader/平台 API、项目资源常量。

| 规则 | 内容 |
|---|---|
| 1 | kit 内不得 `import` 任何项目包（`com.meteorite.unsuspiciousblock.*`） |
| 2 | kit 内不得出现两端 loader / 平台 API（`net.neoforged` / `net.fabricmc` / `net.minecraftforge` / `cpw.mods`） |
| 3 | kit 内不得出现 `Constants.MOD_ID` 或 `unsuspiciousblock:` 命名空间字面量（忽略注释行，避免 javadoc 里的说明被误判） |
| 4 | `client/` 以外的代码不得 `import` kit（服务端加载路径不得链接客户端 kit 类） |
| 5 | kit 的每条 `import` 必须落在 `java.*` / `net.minecraft.*` / `org.jetbrains.*` / `org.joml.*` / `org.lwjgl.*` 之内（**白名单**；规则 1–3 是黑名单，拦不住新增的任意第三方依赖） |

检查命令与退出码：

```powershell
pwsh -File scripts/check-ui-kit-boundaries.ps1
# 可选：-Root <仓库根>；默认取脚本上一级目录
# 退出码 0 = 全部通过；1 = 有违规（逐条打印 文件:行）；2 = 找不到 kit 目录
```

当前结论：**五条规则全部通过（exit 0）**。规则 5 已用「临时插入一条白名单外 `import`」的探针验证会被拦下并报 `文件:行`。这条检查是「边界靠可执行脚本守住」而不是靠人工记忆——改 kit 时先跑它。

## 4. 接入指南

分步接入一个非笔记界面（每步给出用到的公开入口；完整的可运行版本见「最小示例」）：

1. **声明内容**：`TextMeasurer.of(font)` → `new UiDocument(measurer, profiling)`；需要独立变换或分支色时用 `new UiDocument(measurer, transform, branchColor, profiling)`。内容用 `setContent(revision, supplier)`（版本不变就不重建构建器）或 `setContent(List)`。
2. **视口与坐标**：`setViewport(x, y, width, height)`、`render(graphics, font)`、`hit(mouseX, mouseY)` 全部使用 Screen 逻辑 GUI 坐标；只有内容、视口尺寸或 `invalidateLayout()` 变化才会重排。需要读数时用 `metrics()`、`viewport()`、`contentWidth()` / `contentHeight()`。
3. **滚动**：`UiScrollView.setViewport` + `setContentHeight` + `setStep`（`SCROLLBAR_WIDTH` 常量供让出内容宽度）；绘制时 `push(graphics)` / `pop(graphics)` 进出内容坐标，命中先用 `toContentX` / `toContentY` 换算，滚轮先用 `contains(x, y)` 判断是否属于本视口；滚动条由 `renderScrollbar(graphics, style)` 画，拖动走 `hitScrollbar` / `mousePressed` / `mouseDragged` / `mouseReleased` / `isDragging`。
4. **控件组**：`beginUpdate()` → `obtain(stableKey)` → `configure(font, label, color, icon, tooltip, action)` + `setBounds(...)` → `endUpdate()`；命中用 `targetAt` / `controlAt`，按压与释放走 `mousePressed` / `mouseReleased`，同一位置只画一个 tooltip（`renderTooltip`）。`action == null` 是只读标签：可命中、有提示，但不进 Tab 序列。
5. **焦点**：宿主按**视觉顺序** `focus.beginUpdate()` → `add(target)` → `focus.endUpdate()`；`UiControl` 本身就是 `UiFocusTarget`，原生 `EditBox` 用宿主侧适配器实现 `UiFocusTarget` 夹在中间。`keyPressed` 只处理 Tab / Shift+Tab 与可配置的 Enter / Space；`setListener` 里做宿主策略（典型是 `scroll.ensureVisible(target.bounds())`）。
6. **灯箱**：`new UiLightbox(labels, content, onClose)`；图片内容用 `LightboxImage.whole(id, texture, width, height, title, description)` + `new UiImageView(image, missingText)`（允许小图放大时用三参重载）。`setText` / `setGallery` / `setMaskClickCloses` 由宿主按需设置；外壳文案全部来自 `UiLightbox.Labels`。
7. **模态接入**：`overlays.setBounds(width, height)`，`overlays.open(new LightboxOverlay(overlays, lightbox))`；自定义模态实现 `OverlayLayer.Overlay` 并同样用 `open(overlay, opener)` 登记返回焦点。六类输入（click / drag / release / scroll / key / char）先交给模态层，打开期间它一律消费（下层点不到也不会弹提示）。
8. **本地化**：kit 不持有任何文案键。宿主自己维护键（灯箱通用键的命名与用法见「最小示例」）；命名与取色规范见 [文本格式规范](../foundation/text-format.md)。

**最小示例**：`common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/sample/UiKitSampleScreen.java`。它是第三方视角的最小 Screen：只 import 13 个 kit 公开入口 + `OverlayLayer` / `LightboxOverlay` + Minecraft/GLFW/JDK，不引用宿主状态类、`support` 包与资源常量，贴图与语义色自带；覆盖按钮（`UiControlGroup` + `UiControlStyle`，含禁用按钮）、滚动（`UiScrollView` 视口内的控件组）、灯箱（`UiLightbox` + `UiImageView`）与焦点（`UiFocusManager` + `UiLinearLayout` 摆放）。进入方式：运行客户端（开发环境带 `-Dunsuspiciousblock.uiKitDebug=true`）→ 打开笔记按 `Ctrl+F8` → 演示区「最小示例页」按钮。

## 5. 公开 API 的行为约定

- **线程**：只在客户端主线程调用（`init` / `render` / 输入回调所在线程）；kit 不自建线程、不做异步解码。
- **坐标**：一律 Screen 逻辑 GUI 坐标。滚动内容用 `UiScrollView` 的内容坐标（`toContentX` / `toContentY` 换算）；灯箱内容必须把绘制严格裁剪在外壳写入的视口内。
- **尺寸与资源通知**：宿主在可用区变化时调用 `setBounds` / `setViewport`，资源重载时调用 `UiLightbox.invalidateResources()`（内容实现按契约丢弃派生缓存）；`Content.fit()` 由外壳在首次布局与换内容时求一次，内容尺寸尚未就绪时会推迟到能报告非零尺寸的那一帧，resize 只重新钳制、**保留用户缩放**。
- **所有权与生命周期**：组件的生命周期由宿主持有；`UiLightbox` 的关闭动作是构造时传入的 `onClose`，外壳在 `Overlay.closed()` 回调里执行 `onClosed()`（释放按压捕获与焦点）。模态被替换时，旧实例同样会收到一次 `closed()`。
- **事件消费**：所有输入入口返回 `boolean` 表示是否消费；宿主按「模态 → 滚动条 → 内容」的优先级分发。模态打开期间 `OverlayLayer` 对六类输入一律返回消费，被接管的下层不会收到事件。
- **空值与非法输入**：`LightboxImage` 在构造期拒绝非正尺寸与越界区域（`IllegalArgumentException`）；未知/缺失贴图不抛异常，由 `placeholder()` 返回占位文案，外壳把它居中画在视口里。`action == null` 表示纯标签；`UiControlGroup.obtain` / `UiFocusManager.add` 传 `null` 抛 NPE。
- **负尺寸一律钳制**：`UiControl.setBounds` 与 `UiDocument.setViewport` 把负宽高钳到 `0`（此前抛 `IllegalArgumentException`），`UiScrollView.setViewport` 同口径，`UiLightbox.setBounds` 钳到 `1`（外壳至少要一像素才排得出面板）。只有 `UiRect` 自身在构造期拒绝负尺寸——那是矩形的不变式，kit 的入口不会把负尺寸透传给它。宿主不需要在调用前自行钳制。
- **灯箱默认样式（本轮变更）**：`new UiLightbox(...)` 的控制栏默认用 `UiControlStyle.DARK`；文本默认色不再固定为近白 `0xFFE0E0E0`，而是按结构底色的相对亮度在近白/近黑两候选中取对比度更高者。`setStyle(style)` 后文本色随之重算（标脏，下一帧重配控件标签时生效），先调用 `setTextColor(int)` 则固定不再跟随。此前默认是 `UiControlGroup` 的浅底 `PARCHMENT`，与近白字对比度约 1.06:1。
- **Frame 内子文档参与悬停**：`UiDocument.render(graphics, font, mouseX, mouseY)` 把鼠标坐标换算到内容坐标后**透传**给 Frame 内子文档，子文档的悬停滚动与 `hit` 与根文档同口径；无鼠标重载 `render(graphics, font)` 仍等价于整篇（含 Frame 内）都不悬停。Frame 的悬停坐标由父文档排版决定，子文档不需要额外接线。
- **滚动文本的宽度口径**：`TextScroll` 的 `String` 门面保留原签名（内部测一次宽度），并新增带 `textWidth` 的重载；传已测宽度的一方负责口径与失效（字体、语言、资源重载后重测）——kit 不为 `String` 建全局宽度缓存。
- **灯箱读数格式**：`UiLightbox.Labels` 新增 default 方法 `zoomReadout(int percent)`，默认输出既有画面 `"100%"`；既有 `Labels` 实现无需改动，需要本地化读数的宿主覆盖它。
- **超宽文本的两个口径**：需要交互时用 `TextScroll.draw(...)`（悬停带内滚动）；列表行这类不接交互的位置用新增的 `TextScroll.trimToWidth(Font font, String value, int maxWidth)`——宽度足够原样返回，连 `"..."` 都放不下时退化为纯宽度截断，否则截到 `maxWidth - font.width("...")` 再补 ASCII 三点省略号（`maxWidth <= 0` 返回空串）。它返回新字符串、不绘制，宽度按 `Font.width(String)` 逐次测量；省略号口径以它为准，宿主不要再自写一份。
- **回调内容**：`UiAction` 只表达语义动作（`void run()`），不接收也不持有 `Minecraft`、玩家状态或平台事件对象；需要业务数据时由宿主在闭包里捕获，kit 不反向回调宿主状态。
- **焦点与鼠标**：鼠标点击**不夺取焦点**，而且按下时会收掉键盘焦点（避免轮廓留在被点过的控件上）；焦点只在 Tab 导航与宿主显式 `focusOn` 时改变。模态入口只有在**当前确实持有焦点**时才会被登记为返回焦点，因此键盘路径（Tab → Space/Enter 打开）关闭后恢复焦点，鼠标打开则不留下轮廓。**两个焦点层要一起清**：`OverlayLayer.clearRestoredFocus()` 只清「已经还给入口的焦点」，宿主若同时用 `UiFocusManager`，必须在同一次点击里也调用 `focus.clearFocus()`，否则管理器记录的当前焦点会与控件上的标志失配（最小示例页与本模组笔记页就是这两种情形）。

## 6. 兼容策略草案（0.x）

- **稳定入口与实验性入口**：本页「公开入口清单」是稳定入口（0.x 内尽量只增不改）；「内部实现」即使 `public` 也不承诺兼容；`client/ui/sample/` 与 `client/ui/overlay/` 属宿主/示例层，接口随宿主需要变化；标注为开发用/调试用的屏（`Ctrl+F8` 与最小示例页）随时可能移除。
- **支持范围**：这是 **Minecraft 1.21.1 客户端库**，不是脱离游戏的纯 Java UI 库——它使用 `Component`、`ResourceLocation`、`GuiGraphics`、`Font` 等客户端类型。当前构建目标是 NeoForge 21.1.195 与 Fabric 0.116.0+1.21.1（见 `gradle.properties`）；换 Minecraft 版本需要重新验证，不在 0.x 的兼容承诺内。
- **升级规则**：新增 API 属向后兼容；删除、改签名、改语义（包括默认行为）属破坏性变更。破坏性变更必须同时记入 `CHANGELOG.md` 与本页的一节，说明「改了什么 / 怎么迁移」；只有内部实现变动不进变更记录。
- **版本号与变更记录草案**：kit 目前没有独立版本号，随模组版本（`gradle.properties` 的 `version`，当前 `1.5.2`）走；未来独立发布时再引入自己的 0.x 语义化版本。变更记录草案如下表，本页新增行时按时间倒序追加：

| 日期 | 类型 | 变更 | 迁移 |
|---|---|---|---|
| 本轮 | 默认行为变更 | `UiLightbox` 控制栏默认样式改为 `UiControlStyle.DARK`，文本默认色按结构底色反推（见「公开 API 的行为约定」） | 需要浅底画面的宿主显式 `setStyle(UiControlStyle.PARCHMENT)` 并自行 `setTextColor` |
| 本轮 | 行为变更 | 几何入口统一钳制负尺寸：`UiControl.setBounds` / `UiDocument.setViewport` 由抛 `IllegalArgumentException` 改为钳到 `0` | 依赖该异常的宿主需自行校验；现有调用点都已自行钳制，画面不变 |
| 本轮 | 新增 API | `UiLightbox.Labels.zoomReadout(int)`（default 方法，默认 `"100%"`）；`TextScroll.draw(String, …, int textWidth, …)` 与 `ScrollTextHelper` 的对应重载 | 向后兼容；现有 `Labels` 实现与调用点可不变 |
| 2026-09-30 | 新增 API | `UiLightbox.setSurface(UiNineSlice)`、`setIcons(Map<String, UiIcon>)`、`setBarHint(Component)`；图标键沿用 `close` / `fit` / `zoom_in` / `zoom_out` / `previous` / `next` | opt-in 纸面、图标与底栏提示；默认暗色外壳、字符按钮不变，资源与文案仍由宿主提供 |
| 本轮 | 新增 API | `TextScroll.trimToWidth(Font, String, int)`：超宽文本截断为带 ASCII 省略号的返回串（非交互场景的统一口径），语义取宿主既有的管理页 / 语言选择列表私有实现 | 向后兼容；宿主可删掉各自的私有 `trimToWidth`，返回值与边界口径一致 |
| 本轮 | 行为修正 | `UiDocument` 把鼠标透传给 Frame 内子文档（此前恒不悬停）；控件只在内容越界时设裁剪（不再每控件两次 `flush()`） | 无 API 变更；Frame 内超宽文本现在可悬停滚动 |
| 本轮 | 检查加强 | 边界脚本新增规则 5（import 白名单）；`package-info` 与本文补上 LWJGL | 无 API 变更 |
| 本轮 | 边界冻结 | 声明公开入口清单、加入依赖方向检查脚本、示例层迁出 kit | 无 API 签名变更；宿主无需迁移 |

## 7. 拆包就绪门槛（逐条结论）

门槛取自「考古笔记 UI kit 渐进扩展计划」的「对外 API 与未来拆包约束」一节（五条），逐条核对当前状态（该计划于 2026-09-27 阶段 A–E 实施完毕后移入本地归档目录 `docs/archive/`，不入库，故不提供链接）：

| 门槛 | 结论 | 证据 |
|---|---|---|
| ① kit 不 import 项目业务包、资源常量及两端 loader API | **已满足** | `scripts/check-ui-kit-boundaries.ps1` 规则 1–3 与 5（import 白名单），实测 exit 0 |
| ② 开发调试示例移到示例/宿主适配层，资源由调用方提供 | **已满足** | `client/ui/sample/UiKitDebugScreen.java`（包名 `client.ui.sample`，自带贴图路径）；kit 内不再有开发用资源常量 |
| ③ 最小第三方 Screen 示例只依赖公开包完成按钮、滚动与灯箱 | **已满足** | `client/ui/sample/UiKitSampleScreen.java`（13 个 kit 导入 + `OverlayLayer` / `LightboxOverlay`） |
| ④ 明确版本号、变更记录和兼容策略，区分实验性与稳定入口 | **已满足** | 本页「兼容策略草案」与「公开入口清单」；模组版本见 `gradle.properties` |
| ⑤ 分离构建验证两端消费同一库产物，且专用服务端不加载客户端类 | **未满足** | 见下方「剩余阻碍」，两部分都还没有验证手段 |

**剩余阻碍**（本轮明确留下、不属于本计划交付）：

- kit **不拆分 `api/` 与 `internal/` 子包**（已评估，2026-09-27 决定不采纳）：kit 内部实测只有 2 组单向代码依赖（`UiDocument` → `TextScroll`、`UiControl` → `TextScroll`）与 1 组实现关系（`UiImageView implements UiLightbox.Content`）；`UiControlGroup` ↔ `UiScrollView`、`UiLightbox` ↔ `UiImageView` 仅有 javadoc 提及、没有代码引用（2026-09-28 复核），任何按功能域的分包都会把它们从「包内耦合」升级成「跨包循环」；且 `UiTransform` 的 3 个裁剪工具与 `UiMetrics` 的 5 个计时钩子是 package-private，拆包须提权为 `public`，等于用扩大公开面换目录美观；22 个类型约 2.6k 行也未到需要分包的规模。因此「公开 / 内部」继续由本页清单与 `package-info.java` 约定，子包拆分留到独立 Gradle 模块时一次到位。
- 没有**独立 Gradle 模块与发布脚本**：kit 不是独立产物，无法被外部工程以依赖坐标消费。
- 没有**「两端消费同一库产物」的构建验证**：NeoForge 与 Fabric 目前各自编译同一份源码，尚未验证同一份发布产物被两端同时消费。
- 没有**「专用服务端不加载客户端类」的运行时验证**：规则 4 只做了源码层的 `import` 检查，缺少专用服务端启动加载路径的实测证据。

## 8. 本轮范围声明

本轮**只冻结边界与检查方式**：写下公开入口清单、加入可执行的多条依赖边界检查、把开发/最小示例迁到 `client/ui/sample/`、记录兼容策略与门槛结论。**不拆包、不发布**——不新建 Gradle 模块、不写发布脚本、不改包结构、不声明对外版本号。以上「剩余阻碍」全部完成并有实测证据后，才进入独立的拆包立项。
