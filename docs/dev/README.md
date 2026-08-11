# 开发者文档

本目录存放 Unsuspicious Block 面向**项目开发者**的代码架构文档。目标是帮助新加入的开发者快速建立代码心智模型，并为后续维护与扩展提供参考。

> 如果你只是想了解"怎么玩这个 mod"，请看根目录 [README](../../README.md)。本目录面向写代码的人。

## 阅读顺序

建议按以下顺序阅读：

1. **[架构总览](architecture-overview.md)** - 先读这篇。三模块划分、初始化流程、构建系统、横切设计原则。
2. **[平台抽象](platform-abstraction.md)** - Services SPI 机制，common 如何访问平台能力。
3. **[注册架构](registration.md)** - "清单 + 回调"注册模式，新增内容的标准流程。

读完以上三篇即可上手改代码。之后按子系统兴趣选读：

## 子系统文档

| 文档 | 覆盖内容 | 关键包 |
|---|---|---|
| [考古笔记系统](journal.md) | 目录 / 状态 / 同步 / 战利品追踪数据流 | `journal/` |
| [战利品表系统](loottable.md) | 解析 / 收录 / 注入 / 签名 / 概率模拟 | `loottable/` |
| [猫族关系系统](cat-favor.md) | 羁绊阶段 / 恩惠 / 灵体猫 / 猫猫商人 | `cat/` `entity/` |
| [附魔系统](enchantment.md) | 附魔框架 / 揭示 / 四种附魔 | `enchantment/` |
| [实体与世界生成](entities-world.md) | 灵体猫 AI / 灯笼宠物 / 骨块追踪 / 结构 | `entity/` `world/` |
| [方块与物品](blocks-items.md) | 可疑方块 / 陶轮 / 标本箱 / 制陶 / 配方 | `block/` `item/` `specimen/` `pottery/` |
| [客户端与 GUI](client-ui.md) | 考古笔记 GUI 层级 / HUD / 渲染 / 客户端状态 | `client/` |

## 横切关注点文档

| 文档 | 覆盖内容 |
|---|---|
| [Mixin 总览](mixin.md) | common / fabric / lootr 三套配置，注入点分类与兼容风险 |
| [网络与同步](network.md) | payload 分类、C2S/S2C 通信、客户端状态同步会话 |
| [配置与第三方联动](config-integrations.md) | 配置系统、数据驱动 vs 硬编码边界、Jade/JEI/Lootr/Trinkets/Curios/Artifacts 联动 |

## 与 docs/ 其他内容的关系

`docs/` 目录下还有几类文档，与本目录分工如下：

| 路径 | 类型 | 说明 |
|---|---|---|
| `docs/dev/`（本目录） | 开发者文档 | 代码架构，面向维护者 |
| `docs/adr/` | 架构决策记录 | 猫族关系系统的关键设计决策与理由 |
| `docs/cat-bond-design.md` | 设计文档 | 猫族羁绊玩法设计（面向策划） |
| `docs/spirit-cat-npc-design.md` | 设计文档 | 灵体猫 NPC 设计（面向策划） |
| `docs/journal-categories.md` | 设计文档 | 考古笔记目录分类规则 |
| `docs/readme/README_EN.md` | 用户文档 | 英文 README |
| `docs/release-test-checklist-*.md` | 测试清单 | 发布前测试要点 |
| `docs/image/` | 资源 | 文档用图片 |

## 约定

- 文档中的代码引用形如 `ModItems.java` 或 [`ModItems`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/ModItems.java)，后者可在支持 markdown 链接的环境直接跳转。
- 文档与代码保持同步：重构包名、新增子系统或改变架构模式时，请同步更新对应文档。
- 子系统文档统一结构：职责概述 → 数据流 → 关键类 → 扩展点。
