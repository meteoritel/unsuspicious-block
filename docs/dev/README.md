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
| 修改本地存档格式 / 添加数据迁移 | [本地持久化与数据迁移](foundation/persistence.md) |
| 新增 / 修改 Mixin | [Mixin 总览](foundation/mixin.md) |
| 调整可调参数 / 接入第三方 mod | [配置与第三方联动](foundation/config-and-integrations.md) |
| 新增 / 修改物品 tooltip、Jade 文本、GUI 内文本 | [文本格式规范](foundation/text-format.md) |
| 改战利品收录范围 / 签名 / 概率 / 注入 | [战利品表系统](subsystems/loottable.md)；模拟与缓存细节见 [机制细节](internals/loottable-mechanics.md) |
| 改笔记目录 / 进度 / 追踪 / 日志 | [考古笔记系统](subsystems/journal.md) |
| 新增 GUI 面板 / HUD / 渲染器 / 按键 | [客户端与 GUI](subsystems/client-ui.md)；UI kit 契约见 [笔记 GUI 内部机制](internals/journal-ui-internals.md) |
| 接入 UI kit / 查公开 API 与兼容策略 | [UI kit 公开 API 与兼容策略](internals/ui-kit-api.md) 的「公开入口清单」与「接入指南」 |
| 新增 / 修改进度条目 | [进度（成就）系统](subsystems/advancement.md) |
| 新增附魔 / 附魔效果 | [附魔系统](subsystems/enchantment.md) |
| 改猫族羁绊行为 / 新增恩惠能力 | [猫族关系系统](subsystems/cat-favor.md) |
| 改灵体猫 AI / 灯笼宠物 / 骨块追踪 | [实体与 AI](subsystems/entities-world.md) |
| 改淘洗生成 / 结算 / 客户端表现 | [淘洗系统](subsystems/panning.md) |
| 改可疑方块 / 陶轮 / 标本箱 / 回溯粉 / 花火粉 / 便携容器 / 自定义配方 | [方块与物品](subsystems/blocks-items.md) |
| 查图标图集槽位与 UV | [工具栏图标图集](internals/toolbar-icon-atlas.md) |
| 新增可入库文档 / 写临时计划 | [文档维护](../README.md)与[Issue 追踪器](../agents/issue-tracker.md) |

## 子系统地图

| 子系统 | 一句话职责 | 主要代码包 |
|---|---|---|
| [考古笔记](subsystems/journal.md) | 笔记目录构建、玩家进度、战利品追踪、日志 | `core/journal/` `core/network/journal/` |
| [战利品表](subsystems/loottable.md) | 表解析、签名、收录判定、概率模拟、注入 | `core/loottable/` |
| [进度（成就）](subsystems/advancement.md) | 手写进度 JSON 与代码授予桥 | `core/achievement/` |
| [附魔](subsystems/enchantment.md) | 附魔效果框架、四种附魔、失落书页锻造、揭示 | `core/enchantment/` |
| [猫族关系](subsystems/cat-favor.md) | 羁绊阶段、恩惠能力、九命、往礼、猫猫商人 | `core/cat/` |
| [实体与 AI](subsystems/entities-world.md) | 灵体猫三职业与 AI、灯笼宠物、骨块追踪、猫之手结构 | `core/entity/` `core/world/` |
| [淘洗](subsystems/panning.md) | 淘洗点变体、生成与账本、淘洗结算、客户端表现 | `core/pan/` `core/entity/`（WaterShimmer） |
| [方块与物品](subsystems/blocks-items.md) | 可疑方块、陶轮、标本箱、回溯粉、便携容器、自定义配方 | `core/block/` `core/item/` `core/blockentity/` `core/specimen/` `core/pottery/` `core/recipe/` `core/inventory/` |
| [客户端与 GUI](subsystems/client-ui.md) | 客户端基础设施、笔记 GUI、HUD、渲染器、按键 | `client/` |
| （底座，非子系统） | 三模块划分、初始化、构建、横切原则 | [架构总览](foundation/architecture.md) |

## 阅读顺序

1. [架构总览](foundation/architecture.md) —— 先读这篇：三模块划分、初始化流程、构建系统。
2. [平台抽象](foundation/platform-spi.md) + [注册架构](foundation/registration.md) —— common 如何访问平台能力、新增内容的标准流程。

读完这三篇即可上手改代码，之后按上表导航。

## 维护入口

文档职责、事实归属和写作规则见[文档维护](../README.md)。临时计划按[Issue 追踪器](../agents/issue-tracker.md)登记和归档；工具选择见[仓库工具](../../tools/README.md)。
