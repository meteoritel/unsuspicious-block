# 文档入口与维护约定

Unsuspicious Block 的文档按读者和职责组织。修改机制时，从开发导航找到状态所有者与源码声明；写作时更新对应事实源和必要链接。

## 按任务阅读

| 任务 | 入口 |
|---|---|
| 安装、配置和游玩 | [玩家 Wiki](wiki/Home.md)、[项目 README](../README.md)、[英文 README](readme/README_EN.md) |
| 判断代码改动落点 | [开发导航](dev/README.md)、[架构总览](dev/foundation/architecture.md) |
| 修改跨子系统机制 | 开发导航中的 foundation 文档 |
| 修改某个玩法或客户端模块 | 开发导航中的子系统篇，再按链接读取 internals |
| 编写笔记分类数据包 | [笔记分类格式](api/journal-categories.md) |
| 查当前玩法规格 | [猫族关系](design/cat-bond.md)、[猫国灵体](design/spirit-cat-npc.md) |
| 开发、查询 API 或选择验证 | [开发流程](guides/development.md)、[API 查询](agents/api-lookup.md) |
| 运行或修改校验、生成、诊断工具 | [仓库工具](../tools/README.md) |
| 读写任务或讨论领域概念 | [issue 约定](agents/issue-tracker.md)、[triage 标签](agents/triage-labels.md)、[领域资料](agents/domain.md) |
| 查实施目标与验收条件 | [实施计划](plan/README.md)、[产品规划](roadmap.md) |
| 准备发布说明 | [下次发布草稿](todo/next-version-change.md) |

## 文档职责

| 位置 | 内容 |
|---|---|
| `dev/foundation/` | 初始化、SPI、注册、网络、Mixin、配置及文本的跨子系统契约 |
| `dev/subsystems/` | 子系统入口、数据流、状态所有者、扩展点与约束 |
| `dev/internals/` | 被子系统篇引用的算法、缓存、存储、线程与资源细节 |
| `api/` | 数据包作者与集成方使用的格式和匹配语义 |
| `design/` | 已实现玩法的可观察规则；实现结构由开发文档描述 |
| `guides/`、`agents/` | 开发操作与 Agent 按任务读取的工作约定 |
| `wiki/`、`readme/` | 玩家使用说明；Wiki 页面按语言前缀平铺发布 |
| `plan/`、`roadmap.md` | 目标、候选方案、未确认事项和验收条件 |

本地任务放在 `.scratch/`，完成材料放在 `docs/archive/`；二者沿用 Git 忽略策略。公开资料的链接指向入库文档，生命周期说明使用行内代码记录本地去向。

## 事实归属

| 事实 | 事实源 | Markdown 的职责 |
|---|---|---|
| 注册项和注册依赖 | `bootstrap/ModContent`、各 `ModXxx` 声明 | [注册架构](dev/foundation/registration.md)解释模式与扩展步骤 |
| payload、codec、处理绑定及协议版本 | `network/ModPayloads` 与实际 codec | [网络与同步](dev/foundation/network.md)解释端边界与线格式约束 |
| Mixin 条目与目标 | mixin JSON、Mixin 类和构建条件 | [Mixin](dev/foundation/mixin.md)解释用途、侵入边界和平台差异 |
| 配置键、默认值与范围 | 平台配置声明及 common 配置接口 | [配置与联动](dev/foundation/config-and-integrations.md)解释作用域、生命周期与接入 |
| 分类格式与匹配顺序 | [笔记分类格式](api/journal-categories.md)、`JournalCategoryLoader` | 子系统篇链接契约，实现篇解释装配 |
| 状态归属、提交和恢复 | 对应子系统及存储细节篇 | 网络和客户端篇链接状态所有者 |
| UI Kit 公开 API | 固定版本的 sources/JavaDoc 与库工程 | [宿主接入](dev/internals/ui-kit-api.md)解释业务归属与生命周期 |
| 类与方法的局部契约 | 源码注释 | 文档列关键入口、职责与设计原因 |
| 校验判定和 CLI 参数 | 工具实现与 `--help` | [工具入口](../tools/README.md)解释用途、运行时和输入输出 |

同一规则只维护一处。枚举项查声明，其他文档保留当前任务需要的概括和链接。玩家文档按使用场景说明操作；技术文档提供维护依据。

## 写作规则

- 首句说明职责或结论，正文围绕边界、数据流、扩展点和有效限制组织。
- 正式文档只描述源码和项目现状已确认的行为；临时状态、候选方案与待验收事项写入计划。
- 保留协议限额、配置范围和性能硬约束，并紧贴相应机制。源码文件数、LOC、字节大小与符号计数等探索快照留在工具上下文。
- 技术选择说明当前机制和必要原因；修改流水、AI 工作记录、修订自述和防御性冗词从正文删除。
- 链接使用相对文件路径；交叉引用写文件链接与小节名称。源码位置优先用类型、方法、资源 ID。
- 子系统篇按“代码地图、数据流、关键入口、机制、扩展点、约束、相关文档”组织。按职责分叉和重复内容拆篇，细节由 internals 承接。
- 数据格式按契约列字段与语义；方法签名、完整源码清单和配置表优先链接实际声明。
- 文风与证据强度按 `direct-prose-guard` 审校；机械检查核对链接、导航和结构事实。

## 计划生命周期

计划按[计划规范](plan/README.md)登记、实施和验收。实施完成后，将当前机制同步到权威文档，再归档计划并从活动索引移除。用户实机验收属于完成条件时，计划保留到该验收完成。

## 相关文档

- [开发导航](dev/README.md)
- [开发流程](guides/development.md)
- [仓库工具](../tools/README.md)
