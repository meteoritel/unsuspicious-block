# Triage 标签

技能中的规范角色按下表映射为本地 Issue 的 `Status:` 值。状态名保留英文，Agent 说明默认使用简体中文。

| 技能角色 | 本仓库状态值 | 含义 |
|---|---|---|
| `needs-triage` | `needs-triage` | 等待维护者评估 |
| `needs-info` | `needs-info` | 等待报告者补充信息 |
| `ready-for-agent` | `ready-for-agent` | 规格明确，可交由 agent 实现 |
| `ready-for-human` | `ready-for-human` | 需要人工实现 |
| `wontfix` | `wontfix` | 不予处理 |

技能要求应用某个 triage 角色时，更新 issue 顶部附近的 `Status:` 行。调整命名时，修改本表的“本仓库状态值”列。
