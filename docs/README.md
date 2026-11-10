# 文档体系与维护约定

Unsuspicious Block 采用五层文档架构，严格区分面向读者、单一事实源（Single Source of Truth）与语言策略。各层文档职责清晰、单向引用，杜绝交叉冗余。

## 1. 文档架构分层

| 层级 | 覆盖目录 | 核心职责 | 主要读者 | 语言规范 |
|---|---|---|---|---|
| **玩家层** | 根目录 `README.md`、`README_EN.md`、`docs/wiki/` | 安装、配置、玩法指南与常见问题 | 玩家、整合包作者 | 中英双语并行维护 |
| **外部扩展层** | `docs/api/` | 数据包格式、外部集成协议与扩展点规范 | 数据包作者、联动模组作者 | 中文说明 + 英文字段/契约 |
| **设计与规格层** | `docs/design/` | 纯玩法规则、机制设计与数值体验规格 | 策划、玩法设计者、开发者 | 简体中文（专有名词保留英文） |
| **代码与架构层** | `docs/dev/` | 多平台底座、子系统代码流、内部实现细节 | 模组核心开发者、代码贡献者 | 简体中文 + 英文代码标识符 |
| **工程与治理层** | `docs/guides/`、`docs/agents/`、`tools/` | 开发流程、编码规范、写作规范、Agent 协作与工具 | 协作者、代码审查者、Agent | 简体中文 + 规范英文状态/引导词 |

---

## 2. 全局语言策略 (Language Policy)

1. **玩家可见内容中英对齐**：
   - 根目录提供 [`README.md`](../README.md)（中文）与 [`README_EN.md`](../README_EN.md)（英文），顶部提供相互切换链接。
   - [`玩家 Wiki`](wiki/Home.md) 遵循 GitHub Wiki 平铺机制，页面使用 `zh-*` 与 `en-*` 命名，由 [`Home.md`](wiki/Home.md) 与 [`_Sidebar.md`](wiki/_Sidebar.md) 集中索引。
2. **技术与架构文档以中文为事实源**：
   - 开发者文档（`docs/dev/`）、设计规格（`docs/design/`）与工程指南（`docs/guides/`）统一使用简体中文编写，代码类型名、方法名、资源路径保留原版英文标识符。
3. **领域术语单一源头**：
   - 所有玩法专有名词以根目录 [`GLOSSARY.md`](../GLOSSARY.md) 为唯一事实源，文档与本地化键严格遵循术语定义。

---

## 3. 按任务导航

| 任务 | 目标入口 | 说明 |
|---|---|---|
| 玩家安装、配置与玩法 | [中文 README](../README.md) · [English README](../README_EN.md) · [玩家 Wiki](wiki/Home.md) | 面向最终用户 |
| 查看或修改代码架构 | [开发导航](dev/README.md) | 子系统地图、代码包拓扑与代码修改入口 |
| 查阅玩法设计与规则规格 | [猫族关系规格](design/cat-bond.md) · [猫国灵体规格](design/spirit-cat-npc.md) | 纯玩法机制与体验规则 |
| 编写自定义数据包 | [笔记分类格式](api/journal-categories.md) | 笔记外部扩展契约 |
| 编写与审校文档风格 | [文档撰写风格指南](guides/style-documentation.md) | 信息阶梯、上下文指针与单一事实源 |
| 编写与修改 Java 源码风格 | [代码风格规范](guides/style-code.md) | Java 21、多平台隔离、禁止全限定名与契约 |
| 开发流程与构建验证 | [开发流程](guides/development.md) | 工作流、IDEA 检查与构建验证 |
| 运行或修改校验与构建工具 | [仓库工具](../tools/README.md) | Python/PowerShell 检查与生成入口 |
| 规划、任务与 Issue 流转 | [Issue 追踪器](agents/issue-tracker.md) · [Triage 标签](agents/triage-labels.md) | 本地 Markdown 任务规范 |
| 查询原版/Loader API 或领域建模 | [API 查询依据](agents/api-lookup.md) · [领域文档布局](agents/domain.md) | 外部查询分级与 ADR 决策 |
| 查阅版本历史 | [变更记录](../CHANGELOG.md) | 各版本特性与修复清单 |

---

## 4. 事实归属与维护准则

| 事实 | 事实源 | 文档职责 |
|---|---|---|
| 注册项与依赖关系 | `bootstrap/ModContent` 及各 `ModXxx` 声明 | [注册架构](dev/foundation/registration.md) 说明注册模式与扩展步骤 |
| 网络 Payload 与 Codec | `network/ModPayloads` 与实际 Codec | [网络与同步](dev/foundation/network.md) 说明端边界与线格式约束 |
| Mixin 注入点与平台差异 | Mixin 配置文件与 Mixin 类 | [Mixin](dev/foundation/mixin.md) 说明注入意图与平台隔离 |
| 配置项与默认值 | 平台配置声明与通用配置接口 | [配置与联动](dev/foundation/config-and-integrations.md) 说明配置项作用域与生命周期 |
| 数据包分类格式 | `JournalCategoryLoader` | [笔记分类格式](api/journal-categories.md) 说明数据格式与加载语义 |
| 运行时状态归属 | 对应子系统 Manager/State 类 | 开发子系统文档说明所有者与同步机制 |
| 校验规则与命令行参数 | 各脚本实现与 `--help` | [仓库工具](../tools/README.md) 解释用途、运行时与诊断代码 |

同一事实只维护一处。代码中可直接读出的信息不作冗余镜像，文档重点解释**设计意图、架构约束与扩展方法**。

---

## 5. 计划生命周期

临时计划、规格设计与任务 Issue 统一保存在 `.scratch/<feature-slug>/` 目录，执行完成后归档至 `.local/archive/<feature-slug>/`，均不纳入 Git 版本控制。
具体操作标准遵循 [Issue 追踪器](agents/issue-tracker.md)。

---

## 6. 相关文档

- [开发导航](dev/README.md)
- [开发流程](guides/development.md)
- [仓库工具](../tools/README.md)
