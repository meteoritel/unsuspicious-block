# 文本格式规范

本文档是本 mod **全部游戏内文本格式**的唯一权威：物品 tooltip 与 Jade HUD 的行序结构（五段式 / 两段式）、GUI 内文本提示、语义色表与本地化键命名规则。所有新增/修改游戏内文本呈现的代码都必须遵循本文档。

> 猫之瞳的附魔揭示（附魔台候选、铁砧/砂轮分解预览）属于附魔玩法，其机制见[附魔系统](../subsystems/enchantment.md)；本文档只约束它的**呈现样式**。

## 1. 职责概述

- 提供一套统一的 tooltip 行序结构（五段式），让玩家在所有物品上获得一致的阅读节奏；
- 提供**语义色表**作为颜色的唯一出口，杜绝 `ChatFormatting` 随手挑选、`§` 格式码硬编码、hex 色值三种表示法并存的局面；
- 约定 GUI 内文本提示（hover tooltip 与自绘文本）的语义对色与分段规则（见「GUI 内文本规范」一节）；
- 约定本地化键命名规则，保证 en_us / zh_cn 键集合一致。

## 2. 物品 tooltip：五段式行序

物品 `appendHoverText` 统一按以下顺序组装（先调用 `super.appendHoverText`，再追加自定义内容；`super` 产生的内容通常是空，不影响视觉）：

| 段 | 内容 | 样式 | 说明 |
|---|---|---|---|
| 1 | WIP 横幅 | `NEGATIVE + ITALIC` | 仅开发中物品；使用通用键，永远置顶 |
| 2 | 简介行 | `LABEL`（自动折行） | 这是什么 / 核心用途，一句短语精炼表达 |
| 3 | 动态状态行 | 标签 `LABEL`，值按语义单独上色 | 使用 `labelValue` 分离标签与数值/状态色 |
| 4 | 操作提示 / 风险警告行 | 提示 `HINT`（按键/动作 `ACCENT`）；警告 `NEGATIVE` | 交互提示突出按键或动作；破坏性后果走 `warning()`（`⚠ ` 前缀） |
| 5 | Shift 展开详情 | 分区标题 `TITLE` + 明细 | 见下方展开与折行规则 |

**Shift 展开与折行规则**：详情 ≥ 4 行或属"能力清单"类内容时使用展开模式；未按 Shift 时只显示通用提示行（`tooltip.unsuspiciousblock.expand_hint`，自动将高亮后的 `Shift` 传入占位符）。展开/收起统一走 `TooltipBuilder#expandable`，长文本经 `TooltipBuilder#wrapped` 按 `DEFAULT_MAX_LINE_WIDTH`（`200px`）自动折行；按键检测与文本拆分通过 `core/text/ClientTooltipBridge` 由客户端入口注入，专用服务端取安全缺省值、不加载客户端类。

## 3. 语义色表

颜色的唯一出口是 [`core/text/TooltipBuilder`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/text/TooltipBuilder.java) 的常量。**禁止**在新代码中直接使用 `ChatFormatting.XXX` 挑色、在 lang 值中写 `§` 格式码、或使用 `withColor(0xXXXXXX)`。

| 常量 | 颜色 | 语义 |
|---|---|---|
| `TITLE` | GOLD | 分区标题、关键数值（总计、羁绊值） |
| `BODY` | WHITE | 突出正文与关键状态值（Jade 数量、模式名、纹饰名等） |
| `LABEL` | GRAY | 物品简介、状态标签、次要描述 |
| `HINT` | GRAY | 操作提示、未激活内容（与 `LABEL` 同取 `#AAAAAA` 保证暗底可读性，交互焦点通过 `ACCENT` 区分） |
| `POSITIVE` | GREEN | 正面、已解锁、增益、高电量 |
| `NEGATIVE` | RED | 负面、风险警告、损失、低电量 |
| `SEVERE` | DARK_RED | 严重负面（诅咒等） |
| `ACCENT` | AQUA | 特殊强调（按键名、操作动作词、残存命数、特殊适用条件） |
| `NAME` | YELLOW | 归属者、物品名、中电量 |
| `SPECIAL` | LIGHT_PURPLE | 满级或稀有巅峰状态（如「猫国挚友」） |

暗底 Tooltip 上不再使用 `DARK_GRAY`（`#555555` 对比度仅约 1.9:1）。Jade 侧历史上使用的 `0xAAAAAA` 与 `LABEL` 同值，`0xFFE040` 已归并为 `TITLE`。

> **语义层与实现层分离**：「语义色表」一节是唯一语义层，定义"语义 → ChatFormatting"的实现（原版暗底 tooltip / Jade）。GUI 自绘文本（羊皮纸 / 暗色底）沿用同一批语义槽、各自提供 int 色实现，见「GUI 内文本规范」。

### 3.1 条件树映射（考古笔记 tooltip）

条件树（[`JournalTooltipBuilder#appendConditionTree`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/JournalTooltipBuilder.java)）的每个节点用两个**正交**维度表达信息，两者都必须从 `TooltipBuilder` 取语义槽：

| 维度 | 取值 | 样式 | 回答的问题 |
|---|---|---|---|
| 颜色 | `CONDITION_STATIC` | 绿 | 这个条件的概率是怎么来的 |
| | `CONDITION_PROBABILISTIC` | 金 | |
| | `CONDITION_RUNTIME` | 黄 | |
| | `CONDITION_UNREADABLE` | 灰 | |
| 字重 | 常规 | — | 这句话读全了吗 |
| | 斜体（`ITALIC`） | 斜体 | |

四个条件语义别名只复用既有色值（`CONDITION_STATIC`=`POSITIVE`、`CONDITION_PROBABILISTIC`=`TITLE`、`CONDITION_RUNTIME`=`NAME`、`CONDITION_UNREADABLE`=`LABEL`），**不新增颜色**。颜色已被"概率来源"占用且进入玩家阅读习惯，字重此前未被使用，故用它承载保真度。

保真度由**服务端解析层**以 `LootConditionInfo.metadata()` 的 `analysis_fidelity` 给出：缺失该键表示描述完整（常规），`partial` 表示"有保留"、`unreadable` 表示"未读到"（两者都用斜体）。客户端只做样式映射，**不推断条件语义**；键与取值常量取自 [`LootConditionHandlers`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/analysis/LootConditionHandlers.java)，禁止在客户端写字面量。

## 4. Jade HUD 规则

Jade 注入行采用独立的**两段式**：`标签(LABEL)：值`。值直接复用「语义色表」的同一批常量，不另搞一套颜色。物品名 + 数量的复合行中，数量用 `BODY`。

Jade 键命名：

- Jade 专属键：`jade.unsuspiciousblock.<subject>.<seg>`（如 `jade.unsuspiciousblock.shimmer.pan_remaining`、`jade.unsuspiciousblock.suspicious_reader.prefix`）；
- 物品 tooltip 与 Jade **真正共用**的封存信息键走中立前缀：`unsuspiciousblock.sealed.*`（由 [`SealedContentsDisplay`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/block/SealedContentsDisplay.java) 统一构建，两侧共用同一组方法）。

lang 值一律为纯文本，样式由代码 `withStyle` 控制。

## 5. 本地化键命名规则

| 场景 | 键形 |
|---|---|
| 物品 tooltip | `item.unsuspiciousblock.<name>.tooltip.<seg>` |
| 模组级通用提示 | `tooltip.unsuspiciousblock.<seg>`（现有 `wip`、`expand_hint` 两个） |
| 容器分解（铁砧/砂轮/附魔台） | `unsuspiciousblock.container.*`（保持既有前缀不变） |
| Jade 专属 | `jade.unsuspiciousblock.<subject>.<seg>` |
| 封存信息（物品 + Jade 共用） | `unsuspiciousblock.sealed.<seg>` |
| GUI 内文本（自绘文本 + hover tooltip） | `screen.unsuspiciousblock.<screen>.<seg>`（GUI 内 hover tooltip 同属所在屏幕，不另设前缀） |

约束：

- 键只做命名，不承载样式；
- en_us 与 zh_cn 键集合必须完全一致，改键后跑一遍 JSON 语法校验（历史上出现过缺逗号导致本地化整体失效）。

## 6. 关键类

| 类 | 职责 |
|---|---|
| [`core/text/TooltipBuilder`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/text/TooltipBuilder.java) | 语义色常量 + 五段式构建器（`wip` / `intro` / `status` / `labelValue` / `hint` / `warning` / `bullet` / `section` / `blank` / `wrapped` / `expandable`）。仅依赖共享类，common 与专用服务端都可安全引用；客户端能力（Shift 检测、按像素折行）经 `core/text/ClientTooltipBridge` 桥接 |
| [`core/item/DescribedItem`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/item/DescribedItem.java) | 只需简介说明的素材类物品基类（古代金币 / 失落书页 / 基页 / 花火粉） |
| [`core/block/SealedContentsDisplay`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/block/SealedContentsDisplay.java) | 封存信息行构建，物品 tooltip 与 Jade 共用 |
| kit `style.UiTextScheme`、[`client/ui/theme/JournalTextColors`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/JournalTextColors.java) | GUI 共同文字语义色与书页专属色（`NAME`、`SEVERE`、`SELECTED`、`COLLECTION`、`PROB_*`），见「GUI 内文本规范」 |
| [`client/ui/theme/JournalPalette`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/JournalPalette.java) | 羊皮纸书页边框、卡片底纹、遮罩与统一进度条（`drawProgressBar`），全部显式 8 位 ARGB |
| [`client/ui/support/JournalTooltipBuilder`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/JournalTooltipBuilder.java) | 考古笔记物品 / 子表 / 标签 Tooltip 规格构建；条件树按「条件树映射」的颜色 + 字重双维度渲染，并剥离冗余通用条件容器 |
| [`client/ui/tooltip/JournalTooltipSpec`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/tooltip/JournalTooltipSpec.java)、[`JournalRichTooltip`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/tooltip/JournalRichTooltip.java) | 考古笔记富文本视觉 Tooltip 模型与无阴影呈现器（基于 kit `UiTooltipPanel` 与 `UiTooltipSkin` 绘制图标、状态胶囊、指标条与像素树连线） |
| [`client/anvil/AnvilBreakdownTooltipBuilder`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/anvil/AnvilBreakdownTooltipBuilder.java)、[`client/grindstone/GrindstoneBreakdownTooltipBuilder`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/grindstone/GrindstoneBreakdownTooltipBuilder.java)、[`client/enchantment/EnchantmentRevealTooltipBuilder`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/enchantment/EnchantmentRevealTooltipBuilder.java) | 附魔台候选揭示与铁砧 / 砂轮分解预览（猫之瞳携带者可见），统一使用 `TooltipBuilder` 语义色常量 |

## 7. 扩展点：新增物品 tooltip 的标准流程

1. 物品有交互逻辑 → 在其 `appendHoverText` 中先调 `super`，再用 `new TooltipBuilder(tooltipLines)` 按五段式追加（状态行用 `labelValue`，警告用 `warning`，长句用 `wrapped`）；方块物品可直接覆写 `Block#appendHoverText`；
2. 物品只是一句说明（素材类）→ 直接继承 `DescribedItem`，注册时传入 `item.unsuspiciousblock.<name>.tooltip.desc`；
3. 需要展开详情 → 用 `tooltip.expandable(t -> { t.section(...); ... })`，不要自行读 Shift 状态；
4. 上色 → 只从 `TooltipBuilder` 常量取，需要新语义时先在本文档「语义色表」一节补行；
5. 同步添加 en_us / zh_cn 两个键（中文全角冒号 `：`、英文 `: `），并跑 `check-i18n.py` 与 Tooltip 探针模板（`tools/probes/inspect_tooltips.py`）校验行宽与双语表现。

GUI 内文本提示的新增 / 修改流程见「GUI 内文本规范」的「新增 GUI 文本的标准流程」。

## 8. GUI 内文本规范

GUI 覆盖范围 = 屏幕内的 **hover tooltip**（按钮 / 条目 / 帮助等提示，一律走原版 `renderTooltip`、kit `withTextTooltip` 或基于 `UiTooltipPanel` + `UiTooltipSkin` 的 [`JournalRichTooltip`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/tooltip/JournalRichTooltip.java) 渲染）与**自绘文本**（面板标题、状态行、提示、空状态文案等）。Toast 与 HUD 文本暂不在本节范围内，需要时再扩展。

### 8.1 结构规则（弱化五段式）

物品侧五段式不强制搬进 GUI：**短提示只要求"语义对色"**（见 8.2），**长内容才要求分段**——详情 ≥ 4 行或需分区呈现时，按"标题（TITLE）→ 正文（BODY/LABEL）→ 状态 → 提示（HINT）"组织行序。GUI 内长 tooltip 允许复用物品侧展开机制：`Screen.hasShiftDown()` + `TooltipBuilder#expandable`，通用提示键共用 `tooltip.unsuspiciousblock.expand_hint`，不要在 GUI 代码里各自判断 Shift 状态。

### 8.2 按介质选择语义色

共同文字语义由各渲染介质选择可读配色，书页专属名称与严重状态色留在宿主配方：

| 实现层 | 介质 | 颜色出口 |
|---|---|---|
| 1 | 原版暗底 tooltip（物品 tooltip / GUI 内 hover tooltip / Jade） | [`core/text/TooltipBuilder`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/text/TooltipBuilder.java) 的 ChatFormatting 常量 |
| 2 | 羊皮纸 GUI（考古笔记书页类界面） | kit `UiTextScheme.PARCHMENT`；专属语义取 [`JournalTextColors`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/JournalTextColors.java) |
| 3 | 暗色 GUI（战利品表管理等） | kit `UiTextScheme.DARK` |

`UiTextScheme` 提供 `title`、`body`、`label`、`hint`、`positive`、`negative` 与 `accent` 槽位（具体色值以 UI Kit 源码为准）；[`JournalTextColors`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/JournalTextColors.java) 补充书页专属文本语义（`NAME`、`SEVERE`、`SELECTED`、`COLLECTION`、`COMPLETED` 与概率四色 `PROB_*`）；`UiSurfaceStyle.withText(scheme)` 将 `body`、`label`、`hint` 映射为表面常态、次要与禁用文字色，其余表面槽位由主题维护。

> **介质隔离：暗底 Tooltip 只用 `TooltipBuilder` 亮色常量**：原版 `renderTooltip` 与 `UiPointerHit.withTextTooltip` 渲染在黑色/深色 Tooltip 背景上，文本色统一取 `TooltipBuilder` 亮色常量（`TITLE`、`BODY`、`LABEL`、`ACCENT`、`POSITIVE` 等）或无样式白字；羊皮纸文字色（`UiTextScheme.PARCHMENT`、`JournalTextColors`、任何棕色值）只能用于浅色书页表面，不可传入暗底 Tooltip 构建路径。当同一概念既服务于书页文档又服务于黑底 Tooltip 时（如 [`ScenarioLabel`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/ScenarioLabel.java)），必须分离两条着色方法。

浅色羊皮纸（约 `#E8DCBC`）无阴影文字要求至少达到 WCAG AA（≥ 4.5:1）：次要提示与辅助标签统一取 `UiTextScheme.PARCHMENT.label()`（5.03:1），概率四色与合集绿色已按浅底亮度校准，新增自绘文本需按实际底色核对对比度。

> **概率文字的分工**：数值沿用不确定度等级的色相（`JournalTextColors.PROB_*`）；**状态词**——`?`、「需要条件」、「未命中」——走深暖灰/灰色槽位（网格 `JournalTextColors.PROB_UNKNOWN`，tooltip 里的 `LABEL` 语义），因为此时没有数值得着色相可表达。状态由服务端派生的 `Probability` 决定，客户端只做这一层映射：不要因为等级为 `NONE` 就把状态词按数值色画，也不要为「需要条件」补一个数字。

### 8.3 归并与边界规则

- **按语义取色**：无损失标注使用 `positive`，提示使用 `hint`，转换标注使用 `accent`；书签 Tab 选中态与悬停态随 `UiTextScheme.PARCHMENT`（`title` / `body`），不使用与羊皮纸反差过大的高饱和黄色；
- **书页结构色与显式 ARGB**：羊皮纸边框、卡片底纹、分隔线、暖色遮罩及三处进度条（目录、介绍页、日志详情）统一由 [`JournalPalette`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/JournalPalette.java) 维护并共用 `JournalPalette.drawProgressBar`；所有传给 `GuiGraphics.fill` 或文本渲染的整数色值统一写成显式 8 位 ARGB（`0xAARRGGBB`），防止漏写 `0xFF` Alpha 导致填充透明失效；
- **暗底界面收拢到 `UiTextScheme.DARK`**：战利品管理等暗色界面统一取 `UiTextScheme.DARK` 语义槽位；
- hover tooltip 一律走原版 `renderTooltip`、kit `withTextTooltip` 或基于 `UiTooltipPanel` 的 [`JournalRichTooltip`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/tooltip/JournalRichTooltip.java) 渲染，只允许使用 `TooltipBuilder` 亮色语义常量或白字，不夹带 `UiTextScheme.PARCHMENT` / 棕色字；底层技术 ID（如条件注册名、子表或标签完整 ID）默认隐藏，仅在原版高级提示（`F3+H`）开启时展示；
- lang 值一律纯文本、样式由代码控制（同「本地化键命名规则」的约束）。

### 8.4 新增 GUI 文本的标准流程

1. 羊皮纸书页自绘文本 → 从 `UiTextScheme.PARCHMENT` 取共同语义，专属语义取 [`JournalTextColors`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/JournalTextColors.java)；暗色界面取 `UiTextScheme.DARK`；非文本结构色、遮罩与进度条取 [`JournalPalette`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/theme/JournalPalette.java)；
2. GUI 内 hover tooltip → 仅用 `TooltipBuilder` 语义常量构建 `List<Component>`，交原版 `renderTooltip` 或 `UiPointerHit.withTextTooltip` 渲染；
3. 长内容按 8.1 分段；需要展开详情 → 复用 `TooltipBuilder#expandable`；
4. 键命名用 `screen.unsuspiciousblock.<screen>.<seg>`，同步添加 en_us / zh_cn 两个键，并校验 JSON。
