# 开发者文档

本目录存放 Unsuspicious Block 面向**项目开发者**的代码架构文档。目标是帮助新加入的开发者快速建立代码心智模型，并为后续维护与扩展提供参考。

> 如果你只是想了解"怎么玩这个 mod"，请看根目录 [README](../../README.md)。本目录面向写代码的人。

## 文档体系与职责划分

本目录文档按功能分为三类，并遵循**单一权威原则**：

- 每个横切机制、每个玩法子系统各有一个**权威文档**，完整描述其机制与设计取舍；
- 其他文档提到该机制时，只保留"一句话概括 + 链接"，**不复述细节**；
- 新增内容时先确认归属：跨子系统的技术底座进横切机制文档，玩法线内容进对应子系统文档（含该玩法的客户端表现与 mixin 需求）。

| 职责类别 | 文档 | 一句话职责 |
|---|---|---|
| 总览 | [架构总览](architecture-overview.md) | 三模块划分、初始化流程、构建系统、横切设计原则 |
| 横切机制 | [平台抽象](platform-abstraction.md) | Services SPI 机制与可选依赖反射加载（`OptionalModIntegration`）的唯一权威 |
| 横切机制 | [注册架构](registration.md) | "清单 + 回调"注册模式与新增内容标准流程的唯一权威 |
| 横切机制 | [Mixin 总览](mixin.md) | 三套 mixin 配置、全部注入点清单、典型模式与兼容策略的唯一权威 |
| 横切机制 | [网络与同步](network.md) | payload 清单（C2S/S2C）、平台注册与同步会话机制的唯一权威 |
| 横切机制 | [配置与第三方联动](config-integrations.md) | 配置参数全表、数据驱动边界、第三方联动清单 |
| 横切机制 | [文本格式规范](tooltip.md) | 全部游戏内文本（物品 tooltip / Jade / GUI 内文本提示）结构、语义色表与本地化键命名的唯一权威 |
| 玩法子系统 | [考古笔记系统](journal.md) | 笔记玩法全链路：目录 / 进度 / 追踪 / 日志 / 同步（含其持久化 `JournalLogStorage`） |
| 玩法子系统 | [战利品表系统](loottable.md) | 笔记玩法的底层：解析 / 收录 / 签名 / 概率模拟 / 注入 / 自定义条件 / 追踪管理页 |
| 玩法子系统 | [猫族关系系统](cat-favor.md) | 羁绊阶段 / 恩惠能力 / 九命 / 往礼 / 猫猫商人 |
| 玩法子系统 | [实体与 AI](entities-world.md) | 灵体猫基类与三职业 AI / 灯笼宠物 / 骨块追踪 / 猫之手结构 |
| 玩法子系统 | [淘洗系统](panning.md) | 淘盘 / 淘洗点变体（闪烁的光与幽微的光）/ 生成与账本 / 淘洗结算 / 淘洗客户端表现 |
| 玩法子系统 | [附魔系统](enchantment.md) | 附魔效果框架 / 四种附魔 / 失落书页锻造 / 附魔揭示 |
| 玩法子系统 | [方块与物品](blocks-items.md) | 可疑方块 / 陶轮 / 标本箱 / 回溯粉 / 便携容器机制 / 自定义配方 |
| 玩法子系统 | [客户端与 GUI](client-ui.md) | 客户端基础设施与考古笔记 GUI；其他子系统客户端表现只留索引 |
| 参考 | [工具栏图标图集](toolbar-icon-atlas.md) | `toolbar_icons.png` 槽位 / UV / 图标语义（资源规格，非架构文档） |

## 阅读顺序

建议按以下顺序阅读：

1. **[架构总览](architecture-overview.md)** - 先读这篇。三模块划分、初始化流程、构建系统、横切设计原则。
2. **[平台抽象](platform-abstraction.md)** - Services SPI 机制，common 如何访问平台能力。
3. **[注册架构](registration.md)** - "清单 + 回调"注册模式，新增内容的标准流程。

读完以上三篇即可上手改代码。之后按子系统兴趣选读（见上方职责矩阵）。

## 按任务导航

| 我想… | 看哪篇 |
|---|---|
| 新增物品 / 方块 / 实体 / 配方 | [注册架构](registration.md)（标准流程），细节参考 [方块与物品](blocks-items.md) / [实体与 AI](entities-world.md) |
| 新增网络包 | [网络与同步](network.md) 第 9 节 |
| 新增平台能力（SPI 接口） | [平台抽象](platform-abstraction.md) 第 5 节 |
| 调整可调参数 / 接入第三方 mod | [配置与第三方联动](config-integrations.md) |
| 修改战利品收录范围 / 概率口径 / 管理页 | [战利品表系统](loottable.md) 第 5、10 节 |
| 修改笔记追踪、日志或同步 | [考古笔记系统](journal.md) |
| 新增 mixin | [Mixin 总览](mixin.md) 第 6、7 节 |
| 调整淘洗生成 / 结算 / 表现 | [淘洗系统](panning.md)，参数见 [配置与第三方联动](config-integrations.md) 第 2 节 |
| 新增淘洗点变体 / 淘盘 | [淘洗系统](panning.md) 第 2、6 节，物品差异见 [方块与物品](blocks-items.md) 第 3.2 节 |
| 修改猫族行为 / 新增恩惠能力 | [猫族关系系统](cat-favor.md) 第 13 节 |
| 新增附魔 / 附魔效果 | [附魔系统](enchantment.md) 第 10 节 |
| 新增 GUI 面板 / HUD / 按键 | [客户端与 GUI](client-ui.md) 第 12 节 |
| 新增 / 修改物品 tooltip、Jade 文本或 GUI 内文本提示 | [文本格式规范](tooltip.md) 第 2、7、8 节 |

## 与 docs/ 及其他文档的关系

`docs/` 与项目根目录下还有几类文档，与本目录分工如下：

| 路径 | 类型 | 说明 |
|---|---|---|
| `docs/dev/`（本目录） | 开发者文档 | 代码架构，面向维护者 |
| `docs/cat-bond-design.md` | 设计文档 | 猫族羁绊玩法设计（面向策划） |
| `docs/spirit-cat-npc-design.md` | 设计文档 | 灵体猫 NPC 设计（面向策划） |
| `docs/journal-categories.md` | 设计文档 | 考古笔记目录分类规则 |
| `docs/roadmap.md` | 计划文档 | 后续内容路线图与冻结项 |
| `docs/advancement-tree-plan.md` | 实施计划 | 进度树完善规划（已随 1.5.2 实施完成） |
| `docs/plan/` | 实施计划 | 进行中的子系统改造规划；实施完成后移入 `docs/archive/` |
| `docs/archive/` | 归档 | **本地目录，不入库**（列在 `.gitignore`）。已实施完成的计划文档保留在其中，含迁移背景、技术核查与决策记录；当前机制一律以 `docs/dev/` 为准，文档引用不要指向此处 |
| `CONTEXT.md`（项目根目录） | 领域语言 | 各玩法子系统共用的统一术语权威 |
| `docs/readme/README_EN.md` | 用户文档 | 英文 README |
| `docs/image/` | 资源 | 文档用图片 |

## 约定

- 文档中的代码引用形如 `ModItems.java` 或 [`ModItems`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/ModItems.java)，后者可在支持 markdown 链接的环境直接跳转。
- 文档与代码保持同步：重构包名、新增子系统或改变架构模式时，请同步更新对应文档。
- **单一权威原则**：修改机制描述时，改在权威文档中，其他文档只同步链接与一句话概括；不得在多处复述同一机制细节。
- 子系统文档统一结构：职责概述 → 数据流 → 关键类 → 扩展点。
