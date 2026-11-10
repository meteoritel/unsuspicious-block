# Issue tracker：本地 Markdown

本仓库的 issue 和 spec 保存在 `.scratch/` 中。

## 文件约定

- 每个功能使用独立目录：`.scratch/<feature-slug>/`。
- 规格文件：`.scratch/<feature-slug>/spec.md`。
- 实现任务：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 编号，每个任务独立成文件。
- 普通 issue 在文件顶部附近使用 `Status:` 记录 triage 状态，角色名称见 [triage 标签](triage-labels.md)。
- 评论与讨论追加到文件底部的 `## Comments` 小节。

## 发布与读取

技能要求“发布到 issue tracker”时，按文件约定在对应功能目录中创建 Markdown 文件。

技能要求“读取相关 ticket”时，读取指定路径。用户仅提供编号时，在对应功能的 issues 目录中定位；编号存在歧义时询问所属功能。

## Wayfinder 操作

使用 `/wayfinder` 时，探索地图和子任务使用以下约定：

- 地图：`.scratch/<effort>/map.md`，包含 Notes、Decisions-so-far 和 Fog。
- 子任务：`.scratch/<effort>/issues/<NN>-<slug>.md`，正文写明问题。
- 类型：顶部附近使用 `Type:`，取值为 `research`、`prototype`、`grilling` 或 `task`。
- 状态：开放任务尚未领取；领取后写入 `Status: claimed`，解决后写入 `Status: resolved`。
- 依赖：使用 `Blocked by: NN, NN`；列出的任务全部为 resolved 时解除阻塞。
- 领取：按编号选择开放、无阻塞且尚未领取的任务，开始工作前保存 claimed 状态。
- 解决：在 `## Answer` 下追加答案，保存 resolved 状态，并向地图的 Decisions-so-far 追加结论摘要与任务链接。

Wayfinder 子任务使用本节的生命周期状态；普通 issue 使用 triage 状态。
