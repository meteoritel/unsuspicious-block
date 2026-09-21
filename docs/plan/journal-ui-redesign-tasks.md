# 考古笔记前端重构——执行级拆点清单

> 本文是 [考古笔记前端重构设计](journal-ui-redesign-plan.md) 的**执行侧清单**：只记录「先做什么、做完没有、怎么验证」。决定依据与架构说明在计划文档里。
>
> 完成一项就在其状态列标 `✅`；验证方式写在该项内。
>
> **状态（2026-09-21，本轮）**：用户已确认前序实机验收完成；在用户新增树干连线、浮层层高、下拉导航和介绍页上界修复的基础上，补齐 **S2 / S3 / S4**。代码与中英文文案已完成，IDEA 无错误，双平台构建已通过（1m 50s）；本轮新增功能仍待用户实机验收，S5 未实施。
>
> **贴图决策**：用户明确确认 S4-t 使用 **24×24 九宫格、8px 四角**，同一张贴图用于页内框和放大窗口。
>
> **保留前序修复**：树的父行/连线缓存、浮层先 flush 再抬到 z=400、网格页下一组箭头、介绍页独立 `INTRO_TOP` 均保留。

---

## 依赖与前置

| 项 | 说明 |
|---|---|
| 后端 | 不需要改动。本设计未使用任何新 payload（D11）。 |
| 平台 | `common/` 内实现，不引入平台类；无 mixin。 |
| 构建验证 | 每次改动后按项目约定：IDEA MCP 查改动文件 → `./gradlew build`。本机构建需带代理 JVM 参数（`-Dorg.gradle.jvmargs="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890"`）。 |
| 素材工具 | Python 3.14.4 + Pillow 12.2.0 + numpy 2.4.4 已就绪；贴图脚本沿用 `scripts/drawer/` 的 PIL 管线。 |

---

## S0 修复实机 bug 1（独立交付）

| 项 | 内容 |
|---|---|
| 交付 | ① `RightPageContainer.renderTooltips` 的导航 tooltip 路径补上与普通物品路径相同的 `!scenarioPanel.dropdownOpen()` 守卫；② `ArchaeologyJournalScreen.mouseScrolled` 在下拉展开时不翻页；③ `mouseDragged` / `mouseReleased` 转发给 `ScenarioPanel`，以原版点击定位和选区锚点补齐幸运值框拖选 |
| 涉及 | `client/ui/panel/RightPageContainer.java`、`client/ui/screen/ArchaeologyJournalScreen.java`、`client/ui/panel/ScenarioPanel.java` |
| 状态 | ✅ 前序代码、构建与实机验收已完成（用户确认） |
| 验收 | 下拉展开时悬停 tag 聚合/子表条目不再弹出 tooltip；下拉展开时滚轮不翻页；幸运值框可正常拖选。 |

---

## S1 kit 骨架 + 集成实测

| 项 | 内容 |
|---|---|
| 交付 | `client/ui/kit/` 下的 `UiNode` / `UiIcon` / `UiAction` / `UiTarget` / `UiTransform` / `TextMeasurer` / `UiDocument`，另含 `UiRect` / `UiMetrics`；测量 / 排版 / 裁剪 / 命中 / 绘制；版本与脏标记门控；开发模式四段计时。附临时验证页（200 行 + 60 个图标），S1-t 用户验收后删除 |
| 状态 | ✅ 前序代码、构建与实机验收已完成（用户确认） |
| 验收 | 临时验证页渲染正确；拖动/缩放不触发重排；四段耗时可在开发模式读出。 |

### S1-t 集成实测（消 R2 / R3）—— ✅ 用户已确认前序实机验收

| 项 | 内容 |
|---|---|
| 结论 1（R2） | 1.21.1 的 `GuiGraphics.enableScissor` **不读当前 pose**（源码核实：`GuiGraphics.java:155-181` 的 enableScissor/applyScissor 只用矩形与窗口 GUI scale）。因此 `UiTransform.enableScissor` 取出 `pose().last().pose()` 的 `m00/m11/m30/m31` 手工换算到 GUI 屏幕坐标，并在**内容变换入栈之前**调用；整数边界向内取整防泄漏。后续新增裁剪点必须走同一入口。 |
| 结论 2（R3） | 图标在同一 pose 内绘制，随框一起缩放。**每帧开销未量化**：由验收第 14 条在双平台一并确认。 |
| 验收 | 2x 下框内内容被完整裁在框边（不多画、不少画）。用户在本轮开始时确认已完成实机验收；本轮实际场景页与新放大框仍需回归确认。 |

---

## S1-b 树干连线（实机反馈引入）—— ✅ 已完成并实机确认

| 项 | 内容 |
|---|---|
| 背景 | 演示页实机反馈「树形结构没有树枝」。**不使用 SVG**：客户端没有矢量绘制能力、也没有 SVG 解析器，GUI 只有纹理四边形与 `fill` 两类原语；原版进度界面的树连线同样是 `hLine` / `vLine`（即 `fill` 的包装）。 |
| 交付 | ① `UiNode.Row` 增加 `parentRow`（同内容列表内的父行下标，`NO_PARENT = -1`）；② `UiNode.FrameSpec` 增加 `branchColor`（连线颜色归框所有）；③ `UiDocument` 在排版阶段一次算出连线几何：折线竖段 + 横段，以及「祖先仍有后续兄弟」时穿过本行的续行竖线（逐行相接即视觉连续）；④ 线宽按缩放档取整（`ceil(1/scale)`），避免 0.5x 下亚像素线消失；⑤ 演示页改成真正的树（25 组 × (1 根 + 3 子 + 2×2 孙) = 200 行，每 10 行 3 图标 = 60 图标） |
| 状态 | ✅ 代码与构建完成，实机已由用户确认 |
| 验收 | 演示页可见折线树枝与续行竖线；0.5x 下连线仍可见；拖动与缩放时连线跟随且不触发重排（验收清单第 1、7 条） |

---

## S2 场景页只读版与面板自注册 —— ✅ 代码完成

| 项 | 内容 |
|---|---|
| S2a ✅ | 新增 `LayoutAware` / `UiStateful` / `UiPanelRegistry`；场景面板注册一次，屏幕遍历应用布局、捕获与恢复状态。旧面板未迁移接口，由现有容器补齐所有 tab 的页号快照。 |
| S2b ✅ | 保留条件树构建器与树干连线；以目录、结果、选择的 revision 门控构建。条件的每一层均可挂图标；没有正条件时用「无额外条件」行承载图标。未计算输入不再回退到目录的基准概率。 |
| S2c ✅ | 页内读数行、独立动作行、固定 152×166 框；标题与图标 tooltip 统一由命中派生。场景列表按 `options.scenes` 的服务端顺序，标题、下拉、分页共用选择。 |
| S2d ✅ | 补上 `RightPageContainer.setTable` 向新详情面板转发 tableId 的缺口；SCENARIO tab 路由到新面板。 |
| 验收 | 条件树完整、图标提示无穿透；未计算态无测量概率；resize 后各 tab 页号与每场景框视图保持。 |

---

## S3 场景页交互版 —— ✅ 代码完成

| 项 | 内容 |
|---|---|
| 浮层 ✅ | 复用 `OverlayLayer`，保留 flush + z=400；下拉、参数与放大框均先接管输入。浮层打开时屏蔽原生控件悬停与 JEI 物品获取路径。 |
| 场景下拉 ✅ | `ScenarioSelectionOverlay`：当前选择标记、每行状态图标、滚动/分组跳转、上下键选择、ESC/点外关闭；选择后页码同步，不触发计算。 |
| 参数 ✅ | 保留确认/取消语义；改为 kit 控件，布局和命中缓存；幸运值保留 EditBox 并补拖选；参数过多时可滚动，非法输入显示错误，不改变已生效选择。 |
| 四态角标 ✅ | 未计算/计算中/已缓存/失败四种纹理符号，失败原因在 tooltip。`ScenarioPresentation` 允许复用相同参数下已有的明确场景引用，禁止借用其它场景的总概率。 |
| 显式计算 ✅ | 新增场景页计算按钮；移除旧网格头部防抖自动请求，应用推荐也只改变选择，只有点计算才发起测量。 |
| 验收 | 确认才生效、取消不改、非法值有提示；下拉/分页/场景一致；浮层输入与提示不穿透。 |

---

## S4 框交互 + 居中放大窗口 + 贴图 —— ✅ 代码完成

| 项 | 内容 |
|---|---|
| 框交互 ✅ | `ScenarioFrameView` 共用绘制/命中/拖动/缩放/复位逻辑。滚轮以鼠标为锚点切换 0.5/1/2/3 档；框角控件先命中；缩放和平移不重排。 |
| 居中放大 ✅ | `ScenarioExpandedOverlay`：遮罩、放大框、关闭按钮、ESC/点外关闭；复制打开时的视图，窗口内调整不修改页内视图。 |
| 持久化 ✅ | 每个 `tableId + scenarioKey` 保存 `FrameState`，与已确认参数一起写入按存档+玩家隔离的 `journal_ui_preferences.dat`；保存覆盖非当前 tab。 |
| 布局边界 ✅ | 框内滚轮优先于翻页；新增 INTRO / LOG / SCENARIO 各自分页带常量，保留网格页常量，避免共享位置调整引发回归。 |
| 素材 ✅ | `scenario_frame.png`（24×24，8px 四角）与 `scenario_status.png`（48×12，四格）；`UiNineSlice` 固定九个四边形，脚本 `scripts/drawer/generate_scenario_ui.py` 可复现。 |
| 验收 | 页内/放大框拖动缩放和 tooltip 一致；框角不会被内容盖住；关闭放大框回到原页内视图；跨 resize、重开、重启恢复状态。 |

### S4-t 贴图规格 —— ✅ 用户已确认

采用同一张 24×24 九宫格贴图，四角各 8px。与旧版 1:1 整图惯例的偏离已由用户确认；边与中心拉伸，四角保持原生尺寸。

---

## S5 收尾

| 项 | 内容 |
|---|---|
| 交付 | ① 旧 `ScenarioPanel` 的**场景 tab 部分**下线（网格页头部仍由它绘制，含它自己的下拉与计算按钮——见计划 4.4 R7）；② i18n 新增 key 中英同步；③ `docs/dev/client-ui.md`、`docs/dev/network.md` 同步（含 2.9 的文档漂移修正）；④ 清理死资源（`player_inventory.png`、`catalog_entry_pin.png`）与脱节脚本（`generate_toolbar_icons.py`、`generate_journal_icon_atlas.py`）；⑤ 按待决项决定网格页头部下拉的去留（R7） |
| 状态 | ⬜ |

---

## 验收清单（交用户执行，最后一并验收）

> 按 D26，全部实现完成后一并交用户实机验收。下列每条都可独立勾选。
>
> **可提前验证的部分**：S1 演示页（开发客户端内 `Ctrl+F8`）已能覆盖「基础与数据」「框的交互」两类，不必等 S2 完成。
>
> **已实机确认的部分**：第 7 条「树形连线随内容跟随」已在 200 行 / 60 图标的演示页上确认（外层 pose 1x）；第 14 条的耗时读数同一次实测得到——构建 3.8ms、排版 1.2ms、单帧渲染 110µs、命中 1µs。其余条目（含 0.5x 下连线可见性、真实场景页与参数叠加层的各项）待最终验收。

**基础与数据**

1. 树完整显示：条件层级、条件行内的物品图标都在，**没有任何折行或截断**；超出框的内容靠拖动/缩放查看。
2. 未计算态：树与图标完整显示，**没有任何概率数字**；状态角标为「未计算」；条件自身的声明概率（如「随机概率 20%」）照常显示。
3. 点【计算】后概率出现（行内图标悬停显示该场景下的概率），状态角标转为「已缓存」。

**框的交互**

4. 缩放档位 {0.5, 1, 2, 3} 可切换；滚轮缩放**以鼠标位置为锚点**（放大后鼠标指向的那一块仍在鼠标附近）；0.5 档可看清整棵树的形状、文字不可读但图标可辨。
5. 框角「档位 + 复位」在树被拖动后**仍然可点**（先命中生效）；复位回到 1x 且平移归零。
6. 拖动树时内容不会画出框外。

**居中放大窗口**

7. 点展开按钮 → 出现遮罩，框放大到屏幕中央；**窗口内交互与页内完全一致**（可拖、可缩、可悬停、框角控件可用）。
8. ESC 或点击窗口外部关闭，回到页内且缩放/平移状态与放大前一致。

**参数**

9. 参数按钮为固定图标，悬停显示当前生效参数；点开是叠加层；工具/抽样次数/附魔等级用按钮，幸运值用输入框；**确认后才生效，取消不改**；非法幸运有提示。

**状态与持久化**

10. 每个场景各自记住缩放档与平移位置（切走再回来能恢复）。
11. 退出重进笔记、重启游戏后，参数偏好与 UI 偏好仍在（P2 的偏好文件）。
12. 改 GUI scale / 拉窗口 / 全屏切换后：页面不漂移、框架重新排版正确；**非当前 tab 的页面在切回后状态仍在**（修掉「只保存当前 tab 页号」那个丢失）。

**性能**

13. 200 行条件树 + 60 个图标的场景下，开发模式打印的构建/排版/单帧渲染/命中耗时在可接受范围；**拖动与缩放时不发生重排版**。

**回归**

14. 网格页行为与修复前一致，除了 bug 1 被修好（下拉展开时不再穿透、滚轮不翻页）。
15. 目录页、日志页、介绍页行为不变（S2 的面板自注册只作用于新面板）。
16. 双平台实机（NeoForge + Fabric）表现一致。

---

## i18n 新增 key（草案，S3/S4 落地时定稿）

> 命名遵循 `docs/dev/tooltip.md` 第 6 节：`screen.unsuspiciousblock.<screen>.<seg>`，lang 值一律纯文本、样式由代码控制。四态状态词与失败原因已存在，无需新增。

| key 后缀 | 用途 |
|---|---|
| `simulation.frame.zoom` | 框角档位显示（`%s%%`） |
| `simulation.frame.reset` | 复位 |
| `simulation.frame.expand` | 放大到屏幕居中 |
| `simulation.frame.overview_hint` | 0.5 档时的「概览」提示 |
| `simulation.params.title` / `.confirm` / `.cancel` | 参数叠加层标题与两个按钮 |
| `simulation.params.tool` / `.samples` / `.enchant` / `.luck` | 参数分组标签 |
| `simulation.params.invalid_luck` | 幸运值非法提示 |
| `simulation.scene.toggle` | 场景下拉入口 |
| `simulation.scene.status.*` | （已存在）已缓存 / 计算中 / 失败 / 未计算 |
| `simulation.overlay.close` | 遮罩层通用关闭提示 |

**不需新增**：条件全称、工具名、物品名、表名都来自服务端已下发的 `Component`；旋钮字母与算子（`∧` / `¬`）是字面量。

---

## 待实测项索引

| 编号 | 内容 | 在哪一步消 |
|---|---|---|
| R2 | `enableScissor` 与 pose 缩放的相互作用 | S1-t |
| R3 | `renderItem` 在缩放 pose 与被裁剪时的开销 | S1-t |
| R4 | 0.5 档位图字体的实际可读性 | S4 验收第 4 条 |
| R5 | 放大窗口与屏幕级原生控件的层级、事件分发顺序 | S3 / S4 |
| R7 | 网格页头部下拉与场景页浮层下拉并存 | S5 决定 |

## 本轮交付记录（2026-09-21，S0 + S1）

- IDEA MCP 已检查全部改动 Java 文件及中英文 lang：无错误；保留公共 API 尚无业务调用的提示、局部提取建议和既有屏幕返回值提示。
- 按上述代理参数执行 `./gradlew build`：**BUILD SUCCESSFUL in 1m 33s**，33 个任务（23 执行、10 最新），common / Fabric / NeoForge 均成功。未新增或修改测试文件；build 自带的既有测试任务正常执行。Gradle 提示现有弃用功能与 Gradle 9 不兼容。
- S1-t 入口：Fabric / NeoForge 的 Gradle `runClient` 已默认带上 `-Dunsuspiciousblock.uiKitDebug=true`；IDEA 刷新 Gradle 后启动开发客户端，打开笔记按 **Ctrl+F8**。详见 [客户端与 GUI](../dev/client-ui.md) 第 4.4 节。
- 实机重点：下拉 tooltip/滚轮不穿透；幸运值拖选；验证页拖动/缩放/复位不增加排版次数；物品与图块 tooltip 不互相穿透；内容 2x 与外层 pose 2x 下裁剪正确，记录四段耗时。
- **未宣称实测通过**：R2 仅完成源码核实与裁剪实现，R3 的显示效果/耗时仍待双平台实测。验证页暂留，不做 S2–S5 业务迁移。

## 本轮交付记录（2026-09-21，S1-b + S2b/S2c/S2d）

- **S1-b 树干连线**：`UiNode.Row` 增 `parentRow`、`UiNode.FrameSpec` 增 `branchColor`、`UiDocument` 增连线几何与绘制、演示页改成真树。IDEA 检查无编译错误；`./gradlew build` **BUILD SUCCESSFUL in 1m 28s**（33 个任务，20 执行 / 13 最新）。
- **S2b/S2c/S2d**：新增 `ScenarioPageBuilder`、`ScenarioDetailPanel`、`FrameState`；`UiTransform` 补 `zoomIndex()` 与 `restore(...)`；`RightPageContainer` 完成 tab 接管，`ArchaeologyJournalScreen` 的拖动与释放改走路由。
- **构建**：`./gradlew build` **BUILD SUCCESSFUL in 1m 21s**，`:fabric:build` 与 `:neoforge:build` 均成功。
- **IDEA 检查**：全部改动文件无错误。保留的告警：`ScenarioDetailPanel.mouseReleased` 两个形参未用（签名与旧面板对齐，S4 判定点外点击时要用）、`ScenarioPageBuilder` 一处布尔方法只被捕反使用（已改写为直接判断，仅剩提示）、`registerWidget` 返回值未用（既有）。
- **未新增 i18n key**：S2 全部复用既有 `simulation.*`。
- **未宣称实测通过**：S1-b 的实机外观与 S2 的验收项均由用户实机执行。**S2a（面板自注册）未做**，其后果与三条临时缺口见「S2 未做项与临时缺口」。

## 本轮交付记录（2026-09-21，S1-b 实机确认 + S3① + S3③）

- **S1-b 树干连线已实机确认通过**（用户复核截图：折线树枝与续行竖线均正确；开发耗时 HUD 读数：构建 3.8ms、排版 1.2ms、单帧渲染 110µs、命中 1µs）。
- **浮层注册表** `client/ui/overlay/OverlayLayer.java`：模态浮层，打开期间吞掉 click / scroll / drag / release / key / char 六类输入并画在最上层；屏幕在六个入口最先转发，`renderOverlays` 在打开时直接返回。两个正面副作用：ESC 关浮层而不是关整本书；下层任何 tooltip 都不会透出（不再依赖“每条下层路径都记得加守卫”）。
- **参数叠加层** `client/ui/overlay/ScenarioParamsOverlay.java`：工具（服务端签发清单，◀/▶ 循环）、抽样次数（按钮，当前档高亮）、附魔等级（−/＋，0..上限）、幸运（单行 `EditBox`）；确认 / 取消；越界或非数字由 `ScenarioParams` 的构造校验拦下，显示一行提示且**不改动任何已生效的选择**；点外部空白不关闭，避免误触丢掉正在编辑的内容。
- **入口**：读数行上的固定图标（`toolbar_icons.png` 的 `GEAR` 槽位，列 0 行 2，见 `docs/dev/toolbar-icon-atlas.md`），当前生效参数在它的悬停提示里。`ScenarioDetailPanel.mouseClicked` 先跑文档级命中（可点元素）再判定框内拖动，避免与平移抢同一次按下。
- **修掉一个编译错误**：1.21 起附魔是数据驱动注册表，`BuiltInRegistries.ENCHANTMENT` 不存在；改为从世界 `registryAccess()` 取 `Registries.ENCHANTMENT`，无世界（或 id 缺失）时退回 id 路径段。
- **i18n**：新增 8 个 `simulation.params.*` key，中英同步；错误提示复用既有 `simulation.luck_invalid`。
- **验证**：IDEA 检查全部改动文件**无错误**（以 `error` 级别复查为空）；`./gradlew build` **BUILD SUCCESSFUL in 1m 29s**。
- **未宣称实测通过**：参数叠加层的四条验收项待用户实机执行。

## 本轮交付记录（2026-09-21，实机反馈的两处下拉修复）

用户实机报告两条，均已修复并构建通过（`BUILD SUCCESSFUL in 1m 26s`），待实机复核：

1. **下拉框被物品图标的数量角标压住**。根因是层高：**原版 tooltip 用 z=400**（`GuiGraphics.renderTooltipInternal` 中 `pose.translate(0, 0, 400)`，背景也按 400 画），而物品的数量角标画在 z=200，旧下拉也只抬到 200——同层被角标压住。
   - 修法：旧下拉改为 `graphics.flush()` + `translate(0, 0, 400)`。
   - **同一条教训前移到浮层层**：`OverlayLayer.render` 现在也先 `flush()` 再把整层抬到 z=400（常量 `TOOLTIP_Z`），因此参数叠加层与以后所有浮层都天然在物品角标之上，不必每处再记。
2. **「下一组场景」从下拉框底部移到下拉框按钮右侧**，只保留一个右箭头（`▸`），说明文字进悬停提示：`ScenarioPanel` 新增 `NEXT_PAGE_WIDTH` / `NEXT_PAGE_LABEL` 常量，开关按钮宽度由 `width-40` 收为 `width-56`，箭头占 14px，计算按钮位置不变；下拉列表少占一行（填充高度由 `y+176` 收为 `y+156`）。命中判定移到下拉分支**之前**，因此展开状态下也能翻页。`lang` 的 `simulation.more` 文案同步改为纯说明文字（原来带 `▸`，现在是 tooltip 文本）。
3. **介绍页内容上界被推下 34px（回归，非本轮引入）**。`JournalLayout.GRID_TOP` 在 `dcde979d`（P2 场景页那次提交）由 `TOOLBAR_Y` 改成 `TOOLBAR_Y + 34`，为场景页头部腾出两行；同一次改动把日志页正确解耦为 `LOG_TOP = TOOLBAR_Y`，**但介绍页仍在读 `GRID_TOP`**，于是内容被一起推下 34px，且该行留下的注释还写着旧语义（「与左页工具栏行对齐」），是明显的漏改。
   - 修法：新增 `INTRO_TOP = TOOLBAR_Y`，`DetailOverlayPanel` 的 3 处引用改用它；`GRID_TOP` 上补注释「只有带头部的页面能用」，避免再犯。
   - 教训：右页共用同一个「内容上界」常量时，任何一页为了自己的头部抬高它，都会静默改动其它页——每页各有一个上界常量才是安全的。


## 本轮交付记录（2026-09-21，S2 / S3 / S4）

- IDEA MCP 已检查 git 工作区全部 Java、lang 和 Gradle 文件：无错误；剩余为公共 API 未使用、风格建议及既有 DSL 推断警告。
- `./gradlew build`：**BUILD SUCCESSFUL in 1m 50s**，33 个任务（23 执行、10 最新）；没有新增或修改测试文件。
- Fabric / NeoForge 主 jar 均确认包含场景页、下拉、放大窗口类以及 `scenario_frame.png` / `scenario_status.png`。
- 67 个中英 simulation key 配对、格式占位符与 `git diff --check` 检查通过。
- 新增交互交用户验证：场景下拉与页码同步；确认/取消及非法幸运；树内滚轮缩放不翻页；框角始终可点击；放大框关闭回原视图；切场景、切 tab、resize、重开/重启后状态恢复。
- 旧全局参数文件不删除、不自动导入；首次使用新偏好存储时需重新确认参数。窗口 resize 关闭临时模态，未确认草稿作取消处理。
- S5 的旧代码/死资源/临时演示页清理尚未实施。
