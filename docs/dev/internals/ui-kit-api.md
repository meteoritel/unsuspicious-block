# UI kit 公开 API 与兼容策略

本模组的自有 GUI 消费独立库 **Meteo's UI Kit** 的公开包 `com.meteorite.meteosuikit.api`。**职责划分**：宿主持有业务投影、权限校验、网络通信、配置落盘、阅读状态与贴图资源；UI Kit 负责通用输入路由、焦点管理、基础控件、排版裁剪、主题系统与底层绘制。

## 1. 公开入口包清单

完整方法签名以当前固定依赖版本的 sources JAR、Javadoc 及 UI Kit 仓库的 `docs/integration.md` 为准，宿主文档不镜像复制库实现或接口列表。

| API 包 | 宿主消费场景 |
|---|---|
| `control` | `UiScreen` / `UiThemedScreen`、单多行文本编辑、列表与滚动视图/容器、分页状态、控件组、模态框与灯箱 |
| `input` | 成员树 `UiChildren`、输入作用域 `UiInputScope`、唯一路由器 `UiInputRouter`、容器桥 `UiInputBridge`、焦点与持续交互 |
| `layout` / `geometry` | 排版文档 `UiDocument` 及其目标快照、行/网格几何计算、坐标变换与矩形 |
| `style` | 主题上下文、语义文字配色（`UiTextScheme`）、控件与滑块外观、宿主皮肤与表面样式 |
| `number` | 精确数值输入框、合法数值域与滑条（如幸运值参数草稿） |
| `render` / `text` | 贴图切片与物品图标层、局部模型深度层、文本换行/省略裁剪与 Tooltip 辅助 |

`internal` 包属于库内部实现。宿主禁止直接导入、使用全限定名访问或恢复仓内旧 UI Kit 源码副本。

## 2. 版本与兼容策略

Gradle 构建声明、本地仓库属性（`meteos_ui_kit_maven_dir`）与双端内嵌配置见[架构总览](../foundation/architecture.md)的「Meteo's UI Kit 依赖环境」一节。

- 当前开发消费 `dev` 版本坐标（在 `gradle.properties` 的 `meteos_ui_kit_version` 固定）：Fabric 元数据采用精确版本字符串，NeoForge 元数据与 Jar-in-Jar 采用 `[版本]` 精确区间并配置 `prefer`，锁定编译、内嵌与运行期完全一致。
- `dev` 版本允许破坏性接口演进，升级时须先完成 UI Kit 自身构建与基线校验并发布新版本，再同步升级宿主并核对最终打包 JAR 的嵌套依赖与元数据（库中的开发样例不得打入宿主发布包）。

## 3. 接入规范

### 3.1 普通页面与考古笔记

- **继承与生命周期**：独立界面继承 `UiThemedScreen`，考古笔记继承 `UiScreen`。控件实例与 `UiInputScope` 由页面稳定持有；准备阶段同步业务投影，布局阶段刷新几何，绘制阶段读取同一命中快照。
- **主题注入**：主题化根页面传入 `Supplier<? extends UiStyleSource>`（动态上下文使用 `context::current`，固定主题传入固定供应商），主题发布版本号变化时自动刷新控件排版与缓存，并保持现有焦点和编辑草稿。
- **控件与输入**：`UiControl` 显式启用可聚焦能力并在“配对释放且仍命中”时执行动作；`UiControlGroup` 按稳定业务键复用列表项；`UiTextInput` / `UiTextArea` 自身持有草稿、选区与局部撤销，提交前由宿主执行业务校验。
- **笔记视口与工具**：`JournalViewport` 负责将物理窗口坐标统一换算到书页逻辑坐标；`UnsuspiciousUiTheme.PARCHMENT` 通过 `UiThemes.builder()` 与 `UiSkinImages` + `UiNineSlice` 注入图集按钮皮肤，`UiAtlas` 托管统一 `ui_atlas.png` 精灵切片，`JournalControls` 组装图标、侧签/书签与翻页皮肤，点击与持续交互由 UI Kit 接管。
- **分页、滚动与表单**：分页复用 `UiPaginationState`，通用滚动容器使用 `UiScrollContainer`，配置表单由 `UiFormLayout` 驱动排版与焦点自动滚动（底部固定操作栏留在滚动视口之外）。

### 3.2 模态框与持续交互

- 每个根屏幕同一时刻只激活当前顶层 `UiInputScope`：二次确认使用 `UiModalStack`，场景选择与参数浮层使用 `UiOverlay`，条件放大使用 `UiLightbox`；笔记由 `JournalOverlays` 统一管理层切换和业务清理回调，底层路由器自动负责遮罩拦截、焦点归还与迟到事件阻断。
- 拖动、长按与框选等持续操作通过所属作用域的 `UiInteractionSession` 绑定实际交互目标，在目标隐藏、禁用、切域或销毁时自动结清，禁止在控件内部自建第二套全局指针捕获。

### 3.3 文档排版动作 (`UiDocument.targets()`)

- `UiDocument.targets()` 输出当前排版后的真实交互目标 `UiDocumentTarget`（所属叶子文档、出现路径、业务负载、标签与文档/屏幕坐标矩形），条件区的标题底纹、鼠标命中与键盘动作导航均直接消费该快照。
- 出现路径仅描述当前内容树拓扑，跨内容更新的身份标识必须写入业务负载。内容、字体、资源或尺寸变化后需重新获取快照；视口平移或缩放后需刷新屏幕坐标几何。

### 3.4 容器屏幕与只读呈现

- **`PotteryWheelScreen`**：保留原版 `AbstractContainerScreen`、`Menu` 与 `Slot` 机制，自有的控制模式按钮与右侧预览旋转通过唯一 `UiInputRouter` / `UiInputBridge` 接入（配对输入事件先交桥接器，未消费再交容器父类）。
- **`SpecimenBoxScreen`**：无自有交互控件，仅用 UI Kit 图标与文本能力绘制表面，槽位交互完全交由容器父类。
- **HUD、Toast 与局部模型层**：HUD、Toast、富 Tooltip 与 JEI 自有界面保持各自原有生命周期，按需调用 UI Kit 绘制表面、文本或图标；3D 物品与模型在 `UiRenderLayers.item` / `model` 局部深度上下文内渲染，装饰层与主图标处于同一局部层，数量角标绘制在前景层，模型工作区由 UI Kit 管理释放。

## 4. 边界检查与构建校验

1. **静态边界检查**：运行 `python tools/checks/check-ui-kit-boundaries.py`，核验：① 无仓内旧 kit 目录或残留包引用；② 仅消费 UI Kit `api` 包（禁止引用 `internal`）；③ UI Kit 仅由 `client` 所有者引用；④ `common` 无 Loader 导入。
2. **构建与打包检查**：按[开发流程](../../guides/development.md)检查工作区报错后串行执行 `./gradlew.bat build --no-daemon`，并通过 `checkUiKitPackaging` 核对双端产出 JAR 中内嵌库与元数据的精确一致性（确认未误展开库 class 或混入开发样例）。

## 5. 相关文档

- [笔记 GUI 内部机制](journal-ui-internals.md)
- [客户端与 GUI](../subsystems/client-ui.md)
- [架构总览](../foundation/architecture.md)
- [配置与第三方联动](../foundation/config-and-integrations.md)
- [仓库工具](../../../tools/README.md)
