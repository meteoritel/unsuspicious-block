# 开发者文档

本目录存放 Unsuspicious Block 面向**项目开发者**的代码架构文档。目标只有一个：**你要新增或修改某个机制时，能一跳找到该改的文件。**

> 只想了解"怎么玩这个 mod"请看 [玩家 Wiki](../wiki/Home.md) 或根目录 [README](../../README.md)。本目录不描述玩法，只描述代码结构。

## 目录分层

| 层 | 目录 | 放什么 | 什么时候读 |
|---|---|---|---|
| 索引 | 本文件 | 任务导航、子系统地图、写作约定 | 每次开始任务 |
| 底座 | `foundation/` | 跨子系统的契约：架构、平台抽象、注册、网络、Mixin、配置、文本规范 | 要动横切机制时 |
| 子系统 | `subsystems/` | 每个代码子系统一条技术路线：入口 → 数据流 → 关键类 → 扩展点 | 要改某个子系统时 |
| 细节 | `internals/` | 实现细节、性能口径、资源规格。**不参与任务导航**，只在被链接时读 | 被 `subsystems/` 指过来时 |

## 任务导航

| 我要… | 去哪 |
|---|---|
| 新增物品 / 方块 / 方块实体 / 实体 / 配方 / 效果 / 声音 | [注册架构](foundation/registration.md) 的「扩展点：新增注册内容」一节；子系统细节见下方地图 |
| 新增网络包 | [网络与同步](foundation/network.md) 的「扩展点：新增 payload」一节 |
| 新增平台能力（SPI 接口） | [平台抽象](foundation/platform-spi.md) 的「扩展点：新增 SPI 接口」一节 |
| 新增 / 修改 Mixin | [Mixin 总览](foundation/mixin.md) |
| 调整可调参数 / 接入第三方 mod | [配置与第三方联动](foundation/config-and-integrations.md) |
| 新增 / 修改物品 tooltip、Jade 文本、GUI 内文本 | [文本格式规范](foundation/text-format.md) |
| 改战利品收录范围 / 签名 / 概率 / 注入 | [战利品表系统](subsystems/loottable.md)；模拟与缓存细节见 [机制细节](internals/loottable-mechanics.md) |
| 改笔记目录 / 进度 / 追踪 / 日志 | [考古笔记系统](subsystems/journal.md) |
| 新增 GUI 面板 / HUD / 渲染器 / 按键 | [客户端与 GUI](subsystems/client-ui.md)；UI kit 契约见 [笔记 GUI 内部机制](internals/journal-ui-internals.md) |
| 新增 / 修改进度条目 | [进度（成就）系统](subsystems/advancement.md) |
| 新增附魔 / 附魔效果 | [附魔系统](subsystems/enchantment.md) |
| 改猫族羁绊行为 / 新增恩惠能力 | [猫族关系系统](subsystems/cat-favor.md) |
| 改灵体猫 AI / 灯笼宠物 / 骨块追踪 | [实体与 AI](subsystems/entities-world.md) |
| 改淘洗生成 / 结算 / 客户端表现 | [淘洗系统](subsystems/panning.md) |
| 改可疑方块 / 陶轮 / 标本箱 / 回溯粉 / 花火粉 / 便携容器 / 自定义配方 | [方块与物品](subsystems/blocks-items.md) |
| 查图标图集槽位与 UV | [工具栏图标图集](internals/toolbar-icon-atlas.md) |
| 新增可入库文档 / 写实施计划 | 见下方[写作约定](#写作约定)与 [计划文档规范](../plan/README.md) |

## 子系统地图

| 子系统 | 一句话职责 | 主要代码包 |
|---|---|---|
| [考古笔记](subsystems/journal.md) | 笔记目录构建、玩家进度、战利品追踪、日志 | `journal/` `network/journal/` |
| [战利品表](subsystems/loottable.md) | 表解析、签名、收录判定、概率模拟、注入 | `loottable/` |
| [进度（成就）](subsystems/advancement.md) | 手写进度 JSON 与代码授予桥 | `achievement/` |
| [附魔](subsystems/enchantment.md) | 附魔效果框架、四种附魔、失落书页锻造、揭示 | `enchantment/` |
| [猫族关系](subsystems/cat-favor.md) | 羁绊阶段、恩惠能力、九命、往礼、猫猫商人 | `cat/` |
| [实体与 AI](subsystems/entities-world.md) | 灵体猫三职业与 AI、灯笼宠物、骨块追踪、猫之手结构 | `entity/` `world/` |
| [淘洗](subsystems/panning.md) | 淘洗点变体、生成与账本、淘洗结算、客户端表现 | `pan/` `entity/`（WaterShimmer） |
| [方块与物品](subsystems/blocks-items.md) | 可疑方块、陶轮、标本箱、回溯粉、便携容器、自定义配方 | `block/` `item/` `blockentity/` `specimen/` `pottery/` `recipe/` `inventory/` |
| [客户端与 GUI](subsystems/client-ui.md) | 客户端基础设施、笔记 GUI、HUD、渲染器、按键 | `client/` |
| （底座，非子系统） | 三模块划分、初始化、构建、横切原则 | [架构总览](foundation/architecture.md) |

## 阅读顺序

1. [架构总览](foundation/architecture.md) —— 先读这篇：三模块划分、初始化流程、构建系统。
2. [平台抽象](foundation/platform-spi.md) + [注册架构](foundation/registration.md) —— common 如何访问平台能力、新增内容的标准流程。

读完这三篇即可上手改代码，之后按上表导航。

## 写作约定

### 子系统篇固定骨架

每篇 `subsystems/*.md` 使用同一骨架，**槽位与顺序固定**。新增内容时只往槽位里追加，不插入新槽位、不改已有小节标题——这是"新增功能不触发老文档重写"的前提。

```text
# <子系统名>
> 一句话职责。玩家可见入口：<...>。
> 本文件是 <X> 的唯一权威；<Y> 见 [foundation/xxx.md](...)。

## 1. 代码地图      —— 表格：入口 / 服务端 / 客户端 / 数据资源 / 网络 / Mixin / 平台差异，每格给路径
## 2. 数据流        —— 一张图：触发 → 服务端 → 持久化 → 同步 → 客户端
## 3. 关键类        —— 表格：类 → 一句话职责
## 4.~N. 机制分区    —— 该子系统自己的机制，一节一个机制；**只允许在末尾追加新节**，不改已有节标题
## N+1. 扩展点：新增 X —— 编号步骤，最多 2~4 个 X。新增功能时唯一要动的地方
## N+2. 约束与陷阱   —— 每条一行的短清单；深层结论链接到 internals/
## N+3. 相关文档     —— 先列本子系统的 internals/ 细节篇，再列相关篇
```

机制分区是允许生长的槽位（0~4 节），但它是**追加式**的：新增机制 = 末尾加一节；不做插入、不重编号、不重命名已有节。交叉引用一律用「小节名」文本（见硬规则 2）。

参考实现：[进度（成就）系统](subsystems/advancement.md)（无机制分区的短篇）、[附魔系统](subsystems/enchantment.md)（有机制分区的长篇）。

### 三条硬规则

1. **清单只存一份。** 注册项、payload、Mixin、配置键的**完整清单只存在于 `foundation/` 对应篇**。子系统篇只写"本子系统涉及哪几项"并链接，绝不复述全清单。
2. **禁止用节号做交叉引用。** 不写"见 xxx.md 第 5 节"，也不要写依赖标题文本的 `#锚点`——重排或改标题后必然失效。改用「小标题名」写成文本，如"见 [战利品表机制细节](internals/loottable-mechanics.md) 的「签名机制」一节"。
3. **篇幅上限。** `subsystems/*.md` 目标 ≤ 200 行。超了就说明有内容该进 `internals/`，或者存在重复。

### 拆层判据

一篇里若"为什么这样设计 / 性能口径 / 曾经如何"的论证叙述**显著多于**数据流本身，就把论证部分拆到 `internals/`，`subsystems/` 只留路线。

### 代码引用与链接

- 代码引用形如 `ModItems.java`，或写成可跳转链接 [`ModItems`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/ModItems.java)。
- 文档与代码保持同步：重构包名、新增子系统或改变架构模式时，同步更新对应文档。
- **单一权威原则**：改机制描述时改在权威文档，其他文档只同步链接与一句话概括。
- **不改向未入库的本地文件**：`CONTEXT.md`、`AGENTS.md`、`CLAUDE.md`、`INSPIRATION.md`、`docs/archive/` 都在 `.gitignore` 里，克隆仓库的人看不到，链接会变死链。需要引用其中内容时，把必要的那部分**以文本形式写进文档**；本地目录可以用行内代码写路径，但不做 markdown 链接。

## 与 docs/ 及其他目录的关系

| 路径 | 类型 | 说明 |
|---|---|---|
| `docs/dev/`（本目录） | 开发者文档 | 代码架构，面向维护者 |
| `docs/plan/` | 实施计划 | 进行中的子系统改造规划；实施完成后移入 `docs/archive/`。**不构成机制权威**，规范见 [计划文档规范](../plan/README.md) |
| `docs/todo/` | 审查与待办 | 代码审查结论与问题清单；条目完成后同步到本目录对应文档或删除 |
| `docs/wiki/` | 用户文档 | 玩家侧玩法说明（中英双语） |
| `docs/roadmap.md` | 计划文档 | 后续内容路线图与冻结项 |
| `docs/cat-bond-design.md`、`docs/spirit-cat-npc-design.md`、`docs/journal-categories.md` | 设计文档 | 面向策划的玩法设计 |
| `docs/readme/README_EN.md`、`docs/image/` | 用户文档 / 资源 | 英文 README、文档图片 |
| `docs/archive/` | 归档 | **本地目录，不入库**（列在 `.gitignore`）。已完成计划与历史记录，当前机制一律以本目录为准 |
