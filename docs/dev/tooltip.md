# Tooltip 格式规范

本文档是本 mod **物品 tooltip 与 Jade HUD 文本格式**的唯一权威：统一行序（五段式）、语义色表与本地化键命名规则。所有新增/修改 tooltip 的代码都必须遵循本文档。

> 猫之瞳的附魔揭示（附魔台候选、铁砧/砂轮分解预览）属于附魔玩法，其机制见[附魔系统](enchantment.md)；本文档只约束它的**呈现样式**。

## 1. 职责概述

- 提供一套统一的 tooltip 行序结构（五段式），让玩家在所有物品上获得一致的阅读节奏；
- 提供**语义色表**作为颜色的唯一出口，杜绝 `ChatFormatting` 随手挑选、`§` 格式码硬编码、hex 色值三种表示法并存的局面；
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

约束：

- 键只做命名，不承载样式；
- en_us 与 zh_cn 键集合必须完全一致，改键后跑一遍 JSON 语法校验（历史上出现过缺逗号导致本地化整体失效）。

## 6. 关键类

| 类 | 职责 |
|---|---|
| [`client/tooltip/TooltipBuilder`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/tooltip/TooltipBuilder.java) | 语义色常量 + 五段式构建器（`wip` / `intro` / `status` / `hint` / `section` / `expandable`）。仅依赖共享类，common 可安全引用 |
| [`item/DescribedItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/DescribedItem.java) | 只需一行 GRAY 简介的素材类物品基类（古代金币 / 失落书页 / 基页 / 花火粉） |
| [`block/SealedContentsDisplay`](../../common/src/main/java/com/meteorite/unsuspiciousblock/block/SealedContentsDisplay.java) | 封存信息行构建，物品 tooltip 与 Jade 共用 |
| `client/anvil/AnvilBreakdownTooltipBuilder`、`client/grindstone/GrindstoneBreakdownTooltipBuilder` | 铁砧 / 砂轮分解预览（猫之瞳持有者可见）。**TODO**：GUI 侧 tooltip 暂未纳入统一规划，仍直接使用 `ChatFormatting` 挑色（含语义色表外的 `DARK_GREEN`、`LIGHT_PURPLE`），待规划确定后迁移至语义色表 |

## 7. 扩展点：新增物品 tooltip 的标准流程

1. 物品有交互逻辑 → 在其 `appendHoverText` 中先调 `super`，再用 `new TooltipBuilder(tooltipLines)` 按五段式追加；
2. 物品只是一句说明（素材类）→ 直接继承 `DescribedItem`，注册时传入 `item.unsuspiciousblock.<name>.tooltip.desc`；
3. 需要展开详情 → 用 `tooltip.expandable(t -> { t.section(...); ... })`，不要自行读 Shift 状态；
4. 上色 → 只从 `TooltipBuilder` 常量取，需要新语义时先在本文档第 3 节补行；
5. 同步添加 en_us / zh_cn 两个键，并校验 JSON。
