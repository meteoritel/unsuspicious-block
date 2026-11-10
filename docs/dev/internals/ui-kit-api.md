# UI kit 公开 API 与兼容策略

本模组的自有 GUI 消费独立 Meteo’s UI Kit 的 `com.meteorite.meteosuikit.api`。宿主维护业务投影、权限、网络请求、配置、阅读状态和资源；库维护通用输入、焦点、控件、布局、主题与绘制。

## 公开入口清单

公开 API 按职责分包，完整签名以固定依赖版本的 sources JAR、JavaDoc 和库工程的接口基线为准。库的接入说明位于其 `docs/integration.md`，宿主不复制接口清单或库实现。

| API 包 | 宿主用途 |
|---|---|
| `control` | `UiScreen` / `UiThemedScreen`、文本编辑器、列表、滚动视图、控件组、模态和灯箱 |
| `input` | 实际成员树 `UiChildren`、`UiInputScope`、唯一 `UiInputRouter`、容器桥 `UiInputBridge`、焦点与持续交互 |
| `layout` / `geometry` | 文档及其目标快照、行和网格几何、变换及矩形 |
| `style` | 主题上下文、控件角色、宿主皮肤和表面 |
| `number` | 幸运值草稿的精确输入、合法数值域和滑条 |
| `render` / `text` | 贴图与物品图标、局部模型深度层、文本测量、裁剪与提示 |

`internal` 属于库实现。宿主禁止导入或用全限定名访问它，也禁止恢复仓内旧 kit 副本。

## 依赖与版本

版本由 `gradle.properties` 的 `meteos_ui_kit_version` 固定。本地 Maven 位置由用户级 `meteos_ui_kit_maven_dir` 提供，仓库过滤只开放库的 group。具体构建声明见 [架构总览](../foundation/architecture.md) 的「Meteo's UI Kit 依赖环境」。

- common 仅编译依赖 `meteos-ui-kit-common-1.21.1`。
- Fabric 使用并内嵌 `meteos-ui-kit-fabric-1.21.1`。
- NeoForge 使用并通过 Jar-in-Jar 内嵌 `meteos-ui-kit-neoforge-1.21.1`。

宿主加载器元数据声明 `meteos_ui_kit` 的最低能力版本和下一 minor 上限，NeoForge 的 Jar-in-Jar 区间与之保持一致。

升级库时先完成库自身检查、接口基线和发布内容验证，发布新的不可覆盖版本，再升级宿主并核对最终嵌套 JAR 与加载器元数据。平台库中的开发样例不进入发布物。

## 接入指南

### 普通页面与笔记

普通页继承 `UiThemedScreen`，笔记继承 `UiScreen`。控件和作用域由页面稳定持有；准备阶段同步业务状态，布局阶段更新几何，绘制阶段读取同一命中结果。输入树采用实际绘制成员，避免额外维护平行焦点列表。

`UiControl` 显式启用焦点，动作在配对释放且仍命中时执行。`UiControlGroup` 按业务 key 复用控件；`UiTextInput`、`UiTextArea` 的实例承载草稿、选择区和局部撤销。保存前的业务校验由宿主执行。

笔记通过 `JournalViewport` 统一绘制、指针和拖动增量的逻辑坐标；`JournalControls` 只提供图集、书签和热点皮肤，输入与捕获由 kit 承担。

### 模态与持续交互

每个根只选出当前顶层作用域。确认使用 `UiModalStack`，场景浮层使用 `UiOverlay`，条件放大使用 `UiLightbox`。笔记的 `JournalOverlays` 只管理当前层及其业务准备/关闭回调。根 router 负责作用域切换、焦点恢复和迟到事件阻断。

拖动、按压和拖选通过所属 scope 的 `UiInteractionSession` 持有实际目标；隐藏、禁用、切域及卸载由该所有权结清。控件不再保留第二套全局捕获。

### 文档动作

`UiDocument.targets()` 导出真实排版的 `UiDocumentTarget`：所属叶子文档、出现路径、缓存的命中对象、标签、查询文档内容坐标与屏幕坐标。条件区据此生成键盘动作导航和标题底纹，点命中也来自同一文档。

出现路径只描述当前内容结构，业务跨版本身份使用 payload。内容、字体、资源或尺寸失效后重取目标；缩放平移后重取屏幕几何。目标快照包含视口外条目，实际激活仍遵循当前视口和输入作用域。

### 容器与只读呈现

`PotteryWheelScreen` 保留 `AbstractContainerScreen`、Menu 与 Slot，自己的模式控件和预览使用唯一 router / bridge。全部配对事件先交 bridge，未消费再交容器父类。预览旋转是 scope 内的业务交互目标。

`SpecimenBoxScreen` 没有自有交互控件，直接用 kit 图标与文本绘制，槽位继续由容器父类管理。HUD、Toast、富 tooltip 和 JEI 自有内容同样保留原生命周期，使用 kit 表面、文本、图标或模型层。

物品和模型在 `UiRenderLayers.item/model` 中绘制，回调只使用传入的局部 `GuiGraphics`。物品装饰与对应图标放在同一局部层；文字角标放在前景层。模型工作区由 kit 管理释放。

原版/第三方拥有的槽位、文件选择器、普通 tooltip 文本和 Jade 数据出口继续遵守其接口。

## 边界与验证

运行 `pwsh -NoProfile -File scripts/check-ui-kit-boundaries.ps1` 检查：旧目录及包名退出、只消费公开 API、kit 引用限于客户端所有者、common 无 loader import、自有原生控件路径退出。脚本扫描 Java 源集；实际 GUI 接线、业务状态与渲染效果仍按对应实现核对。

代码验证先执行 IDEA MCP 工作区问题检查，再串行执行 `./gradlew.bat build --no-daemon`。最终读取 Fabric / NeoForge 发布 JAR 的内嵌库与元数据，确认宿主没有展开库 class、旧 kit class 或 common 编译库。

笔记业务与状态机制见 [笔记 GUI 内部机制](journal-ui-internals.md)，配置存储与平台可写性见 [配置与第三方联动](../foundation/config-and-integrations.md)。
