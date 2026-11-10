# Issue 追踪器：本地 Markdown

本仓库以本地 Markdown Issue 追踪器统一管理临时计划、spec 与执行任务。活动材料保存在 `.scratch/`，完成后归档到 `.local/archive/`；两者均不纳入 Git 管理。

## 文件约定

- 每个功能使用独立目录：`.scratch/<feature-slug>/`。
- 规格文件：`.scratch/<feature-slug>/spec.md`。
- 实现任务：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 编号，每个任务独立成文件。
- 普通 issue 在文件顶部附近使用 `Status:` 记录 triage 状态，状态值见 [triage 标签](triage-labels.md)。
- 评论与讨论追加到文件底部的 `## Comments` 小节。

## 规划与验收

- `spec.md` 承载临时计划，说明背景、目标与边界、当前事实源、技术依据、方案和整体验收条件。
- 每个 Issue 写明问题、改动落点、依赖、实施步骤和完成条件；执行进度与验收结果以对应 Issue 为准。
- 风险分别标注“已知限制”和“假设”，并写明处置或验证方式。
- 已落地机制同步到对应权威文档，候选方案与待验收事项保留在本地追踪器中。

## 完成与归档

1. 在 Issue 中登记实施与验收结果；需要用户实机验收的，保留活动材料到验收完成。
2. 将已确认的当前机制同步到对应权威文档，核对入口和链接。
3. 将 `.scratch/<feature-slug>/` 整体移至 `.local/archive/<feature-slug>/`，保留内部结构和文件名，并修正指向源码与正式文档的相对链接。目标已存在时保留其内容，新归档目录追加完成日期后缀。
4. 更新本地 `.local/archive/README.md`，登记归档目录、结论摘要和入口链接；索引不存在时创建。

## 创建与读取

技能要求“发布到 issue tracker”时，按上述约定在对应功能目录中创建 Markdown 文件。

技能要求“读取相关 ticket”时，读取指定路径。用户仅提供编号时，在对应功能的 `issues/` 目录中定位；编号存在歧义时询问所属功能。

## Wayfinder 操作

使用 `/wayfinder` 时，探索地图和子任务使用以下约定：

- 地图：`.scratch/<effort>/map.md`，包含 `Notes`、`Decisions-so-far` 和 `Fog`。
- 子任务：`.scratch/<effort>/issues/<NN>-<slug>.md`，正文写明问题。
- 类型：顶部附近使用 `Type:`，取值为 `research`、`prototype`、`grilling` 或 `task`。
- 状态：开放任务尚未领取；领取后写入 `Status: claimed`，解决后写入 `Status: resolved`。
- 依赖：使用 `Blocked by: NN, NN`；列出的任务全部为 `resolved` 时解除阻塞。
- 领取：按编号选择开放、无阻塞且尚未领取的任务，开始工作前保存 claimed 状态。
- 解决：在 `## Answer` 下追加答案，保存 resolved 状态，并向地图的 Decisions-so-far 追加结论摘要与任务链接。

Wayfinder 子任务使用本节的生命周期状态；普通 issue 使用 triage 状态。
