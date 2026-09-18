# 文本格式规范

本文档是本 mod **全部游戏内文本格式**的唯一权威：物品 tooltip 与 Jade HUD 的行序结构（五段式 / 两段式）、GUI 内文本提示、语义色表与本地化键命名规则。所有新增/修改游戏内文本呈现的代码都必须遵循本文档。

> 猫之瞳的附魔揭示（附魔台候选、铁砧/砂轮分解预览）属于附魔玩法，其机制见[附魔系统](enchantment.md)；本文档只约束它的**呈现样式**。

## 1. 职责概述

- 提供一套统一的 tooltip 行序结构（五段式），让玩家在所有物品上获得一致的阅读节奏；
- 提供**语义色表**作为颜色的唯一出口，杜绝 `ChatFormatting` 随手挑选、`§` 格式码硬编码、hex 色值三种表示法并存的局面；
- 约定 GUI 内文本提示（hover tooltip 与自绘文本）的语义对色与分段规则（第 8 节）；
- 约定本地化键命名规则，保证 en_us / zh_cn 键集合一致。

## 2. 物品 tooltip：五段式行序

物品 `appendHoverText` 统一按以下顺序组装（先调用 `super.appendHoverText`，再追加自定义内容；`super` 产生的内容通常是空，不影响视觉）：

| 段 | 内容 | 样式 | 说明 |
|---|---|---|---|
| 1 | WIP 横幅 | RED + ITALIC | 仅开发中物品；使用通用键，永远置顶 |
| 2 | 简介行 | GRAY | 这是什么 / 怎么用，一句话 |
| 3 | 动态状态行 | 整行 GRAY，值可按语义单独上色 | 能量、羁绊、纹饰等运行时数据 |
| 4 | 操作提示行 | DARK_GRAY | 按键 / 右键等交互提示；按键名用 ACCENT 高亮 |
| 5 | Shift 展开详情 | 分区标题 GOLD + 明细 | 见下方展开规则 |

**Shift 展开规则**：详情 ≥ 4 行或属"能力清单"类内容时使用展开模式；未按 Shift 时只显示通用提示行（`tooltip.unsuspiciousblock.expand_hint`）。展开/收起逻辑统一走 `TooltipBuilder#expandable`，不要在物品里各自判断 `Screen.hasShiftDown()`。

## 3. 语义色表

颜色的唯一出口是 [`TooltipBuilder`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/tooltip/TooltipBuilder.java) 的常量。**禁止**在新代码中直接使用 `ChatFormatting.XXX` 挑色、在 lang 值中写 `§` 格式码、或使用 `withColor(0xXXXXXX)`。

| 常量 | 颜色 | 语义 |
|---|---|---|
| `TITLE` | GOLD | 分区标题、关键数值（总计、羁绊值） |
| `BODY` | WHITE | 正文行：Jade 数量、封存信息等（物品简介行自 2026-09-14 起改用 GRAY，与 `LABEL` 同色） |
| `LABEL` | GRAY | 状态行 / 次要信息 / 标签 |
| `HINT` | DARK_GRAY | 操作提示、未激活内容 |
| `POSITIVE` | GREEN | 正面、已解锁、增益 |
| `NEGATIVE` | RED | 负面、警告、损失 |
| `SEVERE` | DARK_RED | 严重负面（诅咒等） |
| `ACCENT` | AQUA | 特殊强调（按键名、残存命数） |
| `NAME` | YELLOW | 归属者、物品名 |

灰阶只有两档：`LABEL`（#AAAAAA）与 `HINT`（#555555）。Jade 侧历史上使用的 `0xAAAAAA` 与 `LABEL` 同值，`0xFFE040` 已归并为 `TITLE`。

> **语义层与实现层分离**：第 3 节语义色表是唯一语义层，定义"语义 → ChatFormatting"的实现（原版暗底 tooltip / Jade）。GUI 自绘文本（羊皮纸 / 暗色底）沿用同一批语义槽、各自提供 int 色实现，见第 8 节。

### 3.1 条件树映射（考古笔记 tooltip）

条件树（[`JournalTooltipBuilder#appendConditionTree`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/JournalTooltipBuilder.java)）的每个节点用两个**正交**维度表达信息，两者都必须从 `TooltipBuilder` 取语义槽：

| 维度 | 取值 | 样式 | 回答的问题 |
|---|---|---|---|
| 颜色 | `CONDITION_STATIC` | 绿 | 这个条件的概率是怎么来的 |
| | `CONDITION_PROBABILISTIC` | 金 | |
| | `CONDITION_RUNTIME` | 黄 | |
| | `CONDITION_UNREADABLE` | 灰 | |
| 字重 | 常规 | — | 这句话读全了吗 |
| | 斜体（`ITALIC`） | 斜体 | |

四个条件语义别名只复用既有色值（`CONDITION_STATIC`=`POSITIVE`、`CONDITION_PROBABILISTIC`=`TITLE`、`CONDITION_RUNTIME`=`NAME`、`CONDITION_UNREADABLE`=`LABEL`），**不新增颜色**。颜色已被"概率来源"占用且进入玩家阅读习惯，字重此前未被使用，故用它承载保真度。

保真度由**服务端解析层**以 `LootConditionInfo.metadata()` 的 `analysis_fidelity` 给出：缺失该键表示描述完整（常规），`partial` 表示"有保留"、`unreadable` 表示"未读到"（两者都用斜体）。客户端只做样式映射，**不推断条件语义**；键与取值常量取自 [`LootConditionHandlers`](../../common/src/main/java/com/meteorite/unsuspiciousblock/loottable/analysis/LootConditionHandlers.java)，禁止在客户端写字面量。

## 4. Jade HUD 规则

Jade 注入行采用独立的**两段式**：`标签(LABEL)：值`。值直接复用第 3 节语义色表（同一批常量），不另搞一套颜色。物品名 + 数量的复合行中，数量用 `BODY`。

Jade 键命名：

- Jade 专属键：`jade.unsuspiciousblock.<subject>.<seg>`（如 `jade.unsuspiciousblock.shimmer.pan_remaining`、`jade.unsuspiciousblock.suspicious_reader.prefix`）；
- 物品 tooltip 与 Jade **真正共用**的封存信息键走中立前缀：`unsuspiciousblock.sealed.*`（由 [`SealedContentsDisplay`](../../common/src/main/java/com/meteorite/unsuspiciousblock/block/SealedContentsDisplay.java) 统一构建，两侧共用同一组方法）。

lang 值一律为纯文本，样式由代码 `withStyle` 控制。

## 5. 本地化键命名规则

| 场景 | 键形 |
|---|---|
| 物品 tooltip | `item.unsuspiciousblock.<name>.tooltip.<seg>` |
| 模组级通用提示 | `tooltip.unsuspiciousblock.<seg>`（现有 `wip`、`expand_hint` 两个） |
| 容器分解（铁砧/砂轮/附魔台） | `unsuspiciousblock.container.*`（保持既有前缀不变） |
| Jade 专属 | `jade.unsuspiciousblock.<subject>.<seg>` |
| 封存信息（物品 + Jade 共用） | `unsuspiciousblock.sealed.<seg>` |
| GUI 内文本（自绘文本 + hover tooltip） | `screen.unsuspiciousblock.<screen>.<seg>`（追认现状；GUI 内 hover tooltip 同属所在屏幕，不另设前缀） |

约束：

- 键只做命名，不承载样式；
- en_us 与 zh_cn 键集合必须完全一致，改键后跑一遍 JSON 语法校验（历史上出现过缺逗号导致本地化整体失效）。

## 6. 关键类

| 类 | 职责 |
|---|---|
| [`client/tooltip/TooltipBuilder`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/tooltip/TooltipBuilder.java) | 语义色常量 + 五段式构建器（`wip` / `intro` / `status` / `hint` / `section` / `expandable`）。仅依赖共享类，common 可安全引用 |
| [`item/DescribedItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/DescribedItem.java) | 只需一行 GRAY 简介的素材类物品基类（古代金币 / 失落书页 / 基页 / 花火粉） |
| [`block/SealedContentsDisplay`](../../common/src/main/java/com/meteorite/unsuspiciousblock/block/SealedContentsDisplay.java) | 封存信息行构建，物品 tooltip 与 Jade 共用 |
| [`client/ui/support/UiTextPalette`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/UiTextPalette.java) | GUI 自绘文本语义色表（羊皮纸 / 暗色两套 int 主题实现，见第 8 节） |
| [`client/ui/support/JournalTooltipBuilder`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/JournalTooltipBuilder.java) | 考古笔记 tooltip 行构建；条件树按 3.1 的"颜色 + 字重"双维度渲染 |
| [`client/anvil/AnvilBreakdownTooltipBuilder`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/anvil/AnvilBreakdownTooltipBuilder.java)、[`client/grindstone/GrindstoneBreakdownTooltipBuilder`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/grindstone/GrindstoneBreakdownTooltipBuilder.java) | 铁砧 / 砂轮分解预览（猫之瞳持有者可见）。2026-09-15 起已迁移至 `TooltipBuilder` 语义色常量 |

## 7. 扩展点：新增物品 tooltip 的标准流程

1. 物品有交互逻辑 → 在其 `appendHoverText` 中先调 `super`，再用 `new TooltipBuilder(tooltipLines)` 按五段式追加；
2. 物品只是一句说明（素材类）→ 直接继承 `DescribedItem`，注册时传入 `item.unsuspiciousblock.<name>.tooltip.desc`；
3. 需要展开详情 → 用 `tooltip.expandable(t -> { t.section(...); ... })`，不要自行读 Shift 状态；
4. 上色 → 只从 `TooltipBuilder` 常量取，需要新语义时先在本文档第 3 节补行；
5. 同步添加 en_us / zh_cn 两个键，并校验 JSON。

GUI 内文本提示的新增 / 修改流程见第 8 节。

## 8. GUI 内文本规范

GUI 覆盖范围 = 屏幕内的 **hover tooltip**（按钮 / 条目 / 帮助等提示，一律走原版 `renderTooltip` 渲染，不自绘）与**自绘文本**（面板标题、状态行、提示、空状态文案等）。Toast 与 HUD 文本暂不在本节范围内，需要时再扩展。

### 8.1 结构规则（弱化五段式）

物品侧五段式不强制搬进 GUI：**短提示只要求"语义对色"**（见 8.2），**长内容才要求分段**——详情 ≥ 4 行或需分区呈现时，按"标题（TITLE）→ 正文（BODY/LABEL）→ 状态 → 提示（HINT）"组织行序。GUI 内长 tooltip 允许复用物品侧展开机制：`Screen.hasShiftDown()` + `TooltipBuilder#expandable`，通用提示键共用 `tooltip.unsuspiciousblock.expand_hint`，不要在 GUI 代码里各自判断 Shift 状态。

### 8.2 一语义三实现

语义层只有一份（第 3 节 9 个语义槽），不同渲染介质各提供一套实现：

| 实现层 | 介质 | 颜色出口 |
|---|---|---|
| 1 | 原版暗底 tooltip（物品 tooltip / GUI 内 hover tooltip / Jade） | [`TooltipBuilder`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/tooltip/TooltipBuilder.java) 的 ChatFormatting 常量 |
| 2 | 羊皮纸 GUI（考古笔记书页类界面） | [`UiTextPalette.Parchment`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/UiTextPalette.java) |
| 3 | 暗色 GUI（战利品表管理等） | [`UiTextPalette.Dark`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/UiTextPalette.java) |

GUI 侧 int 语义槽目标值（2026-09-15 定案；存量面板按此表逐步迁移，迁移前各组件旧常量继续可用）：

| 语义槽 | Parchment | Dark |
|---|---|---|
| `TITLE` | `0xFF3A2818` | `0xFFFFFFFF` |
| `BODY` | `0xFF5A422C` | `0xFFE0E0E0` |
| `LABEL` | `0xFF7A6247` | `0xFFB8B8B8` |
| `HINT` | `0xFF9A8A70` | `0xFF999999` |
| `POSITIVE` | `0xFF3A8C3A` | `0xFF78D66A` |
| `NEGATIVE` | `0xFFC06040` | `0xFFFF5555` |
| `ACCENT` | `0xFFC8A014` | `0xFFFFAA00` |
| `NAME` | `0xFF3A2A1A` | 预留 |
| `SEVERE` | 预留 | 预留 |

### 8.3 归并与边界规则

- **违规色归并**（历史遗留色向 9 槽归并的既定结论）：`DARK_GREEN` 中"无损失标注"→ `POSITIVE`、"提示文本"→ `HINT`；`LIGHT_PURPLE` 转换标注 → `ACCENT`；概率值 YELLOW（运行时条件）→ `ACCENT`；
- **控件结构色不属文本语义**：边框、背景、进度条、选中态色条、滚动条、子表分类标识等保留为组件本地常量，不强行塞进语义槽；
- hover tooltip 一律走原版 `renderTooltip`，禁止自绘悬浮层；
- lang 值一律纯文本、样式由代码控制（同第 5 节约束）。

### 8.4 新增 GUI 文本的标准流程

1. 自绘文本上色 → 只从 `UiTextPalette.Parchment` / `UiTextPalette.Dark` 取语义槽常量，控件结构色除外；
2. GUI 内 hover tooltip → 用 `TooltipBuilder` 语义常量构建 `List<Component>`，交原版 `renderTooltip` 渲染；
3. 长内容按 8.1 分段；需要展开详情 → 复用 `TooltipBuilder#expandable`；
4. 键命名用 `screen.unsuspiciousblock.<screen>.<seg>`，同步添加 en_us / zh_cn 两个键，并校验 JSON。
