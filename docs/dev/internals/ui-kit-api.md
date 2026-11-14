# UI kit 公开 API 与兼容策略

本模组的自有 GUI 消费独立库 **Meteo's UI Kit** 的公开包 `com.meteorite.meteosuikit.api`。**职责划分**：宿主持有业务投影、权限校验、网络通信、配置落盘、阅读状态与贴图资源；UI Kit 负责通用输入路由、焦点管理、基础控件、排版裁剪、主题系统与底层绘制。

## 1. 公开入口包清单

完整方法签名以当前固定依赖版本的 sources JAR、Javadoc 及 UI Kit 仓库的 `docs/integration.md` 为准，宿主文档不镜像复制库实现或接口列表。

| API 包 | 宿主消费场景 |
|---|---|
| `control` | `UiScreen` / `UiThemedScreen`、单多行文本编辑、列表、表单正文与滚动视图/容器、分页状态、控件组、文档阅读控件、模态框与灯箱 |
| `input` | 成员树 `UiChildren`、输入作用域 `UiInputScope`、唯一路由器 `UiInputRouter`、容器桥 `UiInputBridge`、页面回退、焦点与持续交互 |
| `layout` / `geometry` | 排版文档 `UiDocument` 及其目标快照、行/网格几何计算、坐标变换与矩形 |
| `style` | 主题上下文、语义文字配色（`UiTextScheme`）、控件与滑块外观、宿主皮肤与表面样式 |
| `number` | 精确数值输入框、合法数值域与滑条（如幸运值参数草稿） |
| `render` / `text` | 贴图切片与物品图标层、局部模型深度层、文本换行/省略裁剪与 Tooltip 辅助 |

`internal` 包属于库内部实现。宿主禁止直接导入、使用全限定名访问或恢复仓内旧 UI Kit 源码副本。

## 2. 版本与兼容策略

Gradle 构建声明、本地仓库属性（`meteos_ui_kit_maven_dir`）与双端内嵌配置见[架构总览](../foundation/architecture.md)的「Meteo's UI Kit 依赖环境」一节。

- 当前开发消费 `dev` 版本坐标（在 `gradle.properties` 的 `meteos_ui_kit_version` 固定）：Fabric 元数据采用精确版本字符串，NeoForge 元数据与 Jar-in-Jar 采用 `[版本]` 精确区间并配置 `prefer`，锁定编译、内嵌与运行期完全一致。
- `dev` 版本允许破坏性接口演进，升级时须先完成 UI Kit 自身源码、行为、公开面、JavaDoc 与独立样例校验，发布后核对 publication 和各端 Maven 消费，再同步升级宿主并核对最终打包 JAR 的嵌套依赖与元数据（库中的开发样例不得打入宿主发布包）。

本地供应使用专用 Maven；空仓库从对应源码恢复，隔离缓存核对消费来源。完整流程和不可覆盖规则由 kit 的 `tools/README.md` 维护。

## 3. 接入规范

### 3.1 普通页面与考古笔记

- **继承与生命周期**：独立界面继承 `UiThemedScreen`，考古笔记继承 `UiScreen`。控件实例与 `UiInputScope` 由页面稳定持有；准备阶段同步业务投影，布局阶段刷新几何，绘制阶段读取同一命中快照。
- **主题注入**：主题化根页面传入 `Supplier<? extends UiStyleSource>`（动态上下文使用 `context::current`，固定主题传入固定供应商），主题发布版本号变化时自动刷新控件排版与缓存，并保持现有焦点和编辑草稿。
- **语义状态入口**：普通页面直接使用 `UiStatefulWidget` 的 bounds、可见性、可用性和布局身份操作。具体控件同步原版模型并在隐藏/禁用时结清子会话；配置行通过 editor 的 `setEnabled` 应用权限。自定义控件私有组合 `UiWidgetShell`，包装器与核心共享真实焦点和状态。
- **主题覆盖**：换根主题保留显式结构、完整 skin 与文字覆盖；组 `setStyle` / `clearStyleOverride` 广播到当前成员并更新后续默认值，组 `style()` 查询后续成员默认。单控件清结构与清完整 skin 使用各自明确入口。
- **控件与输入**：`UiControl` 显式启用可聚焦能力并在“配对释放且仍命中”时执行动作；`UiControlGroup` 按稳定业务键复用列表项；`UiTextInput` / `UiTextArea` 自身持有草稿、选区与局部撤销，提交前由宿主执行业务校验。
- **单行呈现**：`UiTextInput` 保留原版编辑模型，私有视图统一无阴影正文、占位文字、光标、选区与鼠标寻址；颜色读取当前主题，水平窗口随编辑、resize 和语言资源更新。搜索及数值字段直接消费同一控件。
- **笔记视口与工具**：宿主声明最小逻辑画布，`UiScreen.viewport()` 根据实际 framebuffer 与原版 GUI 倍率选择整数物理像素倍率，统一 GUI 与书页逻辑坐标的正逆换算；绘制、鼠标位置和拖动增量共用同一比例。像素不足 `1×` 时连续缩小以保留访问能力。`UnsuspiciousUiTheme.PARCHMENT` 通过 `UiThemes.builder()` 与 `UiSkinImages` + `UiNineSlice` 注入图集按钮皮肤，`UiAtlas` 托管统一 `ui_atlas.png` 精灵切片，`JournalControls` 组装图标、侧签/书签与翻页皮肤，点击与持续交互由 UI Kit 接管。
- **Tooltip 与裁剪**：普通与物品提示统一经过 `UiPointerHit.renderTooltip` 转入原版 GUI 空间，提示字号和屏幕限位沿用 GUI 设置，物品提示继续调用原版渲染及 Loader Hook。自绘内容裁剪使用 `UiRenderLayers.enableScissor/disableScissor` 在 `finally` 中配对；在裁剪恢复后呈现提示。
- **分页、滚动与表单**：分页复用 `UiPaginationState`，通用滚动容器使用 `UiScrollContainer`，配置、日志保留和管理详情使用 `UiFormBody` 装配真实字段、标签、动作和自绘说明。正文拥有排版、滚动与裁剪，宿主将 `reveal(current)` 接入已有焦点 listener；底部固定操作栏留在正文之外。短正文固定预留轨道带宽，内容、自然高度或字体资源改变后调用 `invalidateLayout()`。
- **阻尼与成员帧**：`UiScrollView` 唯一持有实际偏移与阻尼目标。滚轮累积目标，恢复位置及焦点滚入立即定位，键盘平滑导航调用 `ensureVisibleSmoothly`。`UiScreen` 在准备、布局和选域后推进活动内容的 `advanceFrame(long)`，再解析命中与绘制；容器沿实际成员树转发，私有持有滚动视图的包装器转发帧和退出通知。表单推进后更新真实字段坐标，隐藏、禁用或切域结清旧目标。目录只提供内容和条目索引快照。

### 3.2 模态框与持续交互

- 每个根屏幕同一时刻只激活当前顶层 `UiInputScope`：二次确认使用 `UiModalStack`，场景选择与参数浮层使用 `UiOverlay`，条件放大使用 `UiLightbox`；笔记由 `JournalOverlays` 统一管理层切换和业务清理回调，底层路由器自动负责遮罩拦截、焦点归还与迟到事件阻断。
- 页面通过活动 scope 的 `setNavigationBack` 注入返回动作，kit 处理第四个鼠标键及内容未消费的 Backspace；文本局部撤销保持独立。回退前取消持续交互，侧键的配对释放只消费，不重复导航。输入回调卸载 `UiScreen` 后，事件结束刷新保持旧根关闭。
- 拖动、长按与框选等持续操作通过所属作用域的 `UiInteractionSession` 绑定实际交互目标，在目标隐藏、禁用、切域或销毁时自动结清，禁止在控件内部自建第二套全局指针捕获。

### 3.3 文档阅读与业务呈现

场景页由 `ScenarioPageBuilder` 构造逻辑 `UiNode` 与业务动作，`ScenarioDetailPanel` 组合 `UiDocumentView`，通过背景回调识别 Heading payload、绘制分区底纹与键盘选择提示。宿主绘制外边框并保存 offset；折行、滚动条、动作导航、裁剪与鼠标/键盘配对激活归 kit。`ScenarioExpandedOverlay` 只装配业务节点与背景，灯箱持有独立的 `UiDocumentLightboxContent`。

`UiDocument` 默认单行悬停滚动，reader 显式使用带样式折行。视觉续行共用逻辑父行和动作身份，leading/trailing 图标只出现一次；命中使用实际片段，Frame 使用自身裁剪和变换。`UiDocument.targets()` 提供实际叶子文档、出现路径、payload 与内容/屏幕矩形；`contentClip()` 给出祖先 Frame 裁剪，根外层由 reader 裁剪。出现路径服务当前内容树，跨内容版本的业务身份放入 payload。

内容版本、宽度、字体或语言失效触发排版；滚动、平移与缩放复用布局。按压期间内容或排版变化会取消旧目标。语言变化由宿主调用布局失效入口，资源重载通过已有灯箱生命周期失效。灯箱 fit 不放大小内容，手动缩放后的 resize 保留档位并钳制；关闭灯箱保留页内 offset。

### 3.4 容器屏幕与只读呈现

- **`PotteryWheelScreen`**：保留原版 `AbstractContainerScreen`、`Menu` 与 `Slot` 机制，自有的控制模式按钮与右侧预览旋转通过唯一 `UiInputRouter` / `UiInputBridge` 接入（配对输入事件先交桥接器，未消费再交容器父类）。
- **`SpecimenBoxScreen`**：无自有交互控件，仅用 UI Kit 图标与文本能力绘制表面，槽位交互完全交由容器父类。
- **HUD、Toast 与局部模型层**：HUD、Toast、富 Tooltip 与 JEI 自有界面保持各自原有生命周期，按需调用 UI Kit 绘制表面、文本或图标；3D 物品与模型在 `UiRenderLayers.item` / `model` 局部深度上下文内渲染，装饰层与主图标处于同一局部层，数量角标绘制在前景层，模型工作区由 UI Kit 管理释放。

## 4. 边界检查与构建校验

### 4.1 布局与帧采集

`UiScreen.layoutNodes()` 从当前活动输入树提供原版 GUI 坐标的只读快照，包含稳定 key、原始矩形和有效裁剪。`UiOverlay` 转发当前内容的布局，模态字段与底层页面沿相同入口查询。`UiListView` 直接贡献列表根与可见行，管理树通过 `TreeRow.key()`、语言列表通过语言码提供行身份；行 target 仍为实际列表。宿主为自绘目录、日志及固定控件提供业务身份；未解锁条目遵守原有隐藏规则。实际绘制后读取快照，点击前使用 `isLayoutTarget` 校验真实 hitTest；查询不触发业务准备或业务动作。

`UiFrameCapture.capture(Path)` 接收客户端主线程请求，在后续完成帧采集并异步写入原尺寸 PNG。请求绑定当前世界、Screen 与窗口尺寸；采样前上下文变化、重复请求、目标已存在或停止会明确结清。库拥有帧生命周期与图像资源，AutoJS 调度、业务 readiness、断言和报告归[核心探针](../../../tools/probes/README.md)。

### 4.2 门禁

1. **静态边界检查**：运行 `python tools/checks/check-ui-kit-boundaries.py`，核验：① 无仓内旧 kit 目录或残留包引用；② 仅消费 UI Kit `api` 包（禁止引用 `internal`）；③ UI Kit 仅由 `client` 所有者引用；④ `common` 无 Loader 导入。
2. **构建与打包检查**：按[开发流程](../../guides/development.md)检查工作区报错后串行执行 `./gradlew.bat build --no-daemon`，并通过 `checkUiKitPackaging` 核对双端产出 JAR 中内嵌库与元数据的精确一致性（确认未误展开库 class 或混入开发样例）。

## 5. 相关文档

- [笔记 GUI 内部机制](journal-ui-internals.md)
- [客户端与 GUI](../subsystems/client-ui.md)
- [架构总览](../foundation/architecture.md)
- [配置与第三方联动](../foundation/config-and-integrations.md)
- [仓库工具](../../../tools/README.md)
