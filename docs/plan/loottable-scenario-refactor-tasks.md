# 战利品表条件场景重构——执行级拆点清单

> 本文是 [条件场景与按需概率模拟重构规划](loottable-scenario-refactor-plan.md) 的**执行侧清单**（该规划第 4.6 节决策 47 要求执行级拆点另起一份，不写进规划本文）。
>
> 规划记录"为什么这样改"与"裁定依据"；本文只记录"先做什么、做完没有、怎么验证"。分点粒度按可独立编译、可独立验证来切。
>
> 完成一项就在其标题后追加 `✅` 与提交号；验证方式写在该项内，不再单列。

## P0 诚实化与失效链（不依赖 `SimulationInput`，不依赖新协议）

| 点 | 交付 | 状态 |
|---|---|---|
| P0-1 | 数据包重载监听：Fabric `ResourceManagerHelper`、NeoForge `AddReloadListenerEvent` 只置脏标记，重建走 tick 路径（D9） | ✅ |
| P0-2 | 哈希输入并入被引用附魔定义摘要；保证范围拆成两句写入文档（决策 35）；`SIMULATION_CACHE_VERSION` v15→v16 | ✅ |
| P0-3 | `Probability` 新增 `NeedsCondition` 第 4 态，`Unknown` 带 `UnknownReason`；`SimulatedValue` 窄类型隔开持久化与展示（决策 22/36） | ✅ |
| P0-4 | 参数填充按声明 paramSet 的 `allowed` 补填/裁剪，不兼容 paramSet 明确排除（D8） | ✅ |
| P0-5 | 战利品表不可用机制的显式识别与一次性降级诊断（决策 31） | ✅ |
| P0-6 | 网格改读基准场景（全假条件）；显示优先级链：可适用性 → 声明触发率 → 模拟值（决策 2/44）；零命中改「未命中」（决策 40） | ✅ |
| P0-7 | 静态信息性提示 `PathHint` 与「需要条件」文案；中英文案同步 | ✅ |
| P0-8 | 编译验证：IDEA MCP 检查改动文件 + `./gradlew build` | ✅ |

## PERF 慢表性能归因与修复（独立于 P1，未提交 git）

| 点 | 交付 | 状态 |
|---|---|---|
| PERF-1 | 保留原热运行证据；加入掉落栈、候选扫描、组件序列化、存储键调用计数与分段计时；统一日志解析脚本 | ✅ 19:05 第二轮热运行完整，58 张表分支计数守恒 |
| PERF-2 | 结合真实分段数据与 mc-developing-mcp 源码，确认三张慢表的具体成因 | ✅ Relics 随机属性动态签名约 1645～1796 个，候选扫描约 91万～110万次，匹配耗时 468～525ms |
| PERF-3 | 按归因实施最小修复，保留签名语义/存储键、10000 次抽取与动态发现 | ✅ 索引后 MATCH 从 468～525ms 降到 9～14ms，三表 CPU 降低 69%～77% |
| PERF-4 | IDEA 检查、双端 build；用户热运行后生成同口径逐表 CPU 对照与最终结论 | ✅ 索引版 build 53s 成功、58 表对照已生成；<100ms 未达标，残余见 PERF-5 |
| PERF-5 | 复用目录取名预览，避免重复 JSON 解码；依据新实测继续定位 GENERATE 残余热点 | 预览复用已实现、IDEA 无警告，构建及实机收益见性能记录；GENERATE 内部分摊未确认 |

操作与证据：本地观测目录 `docs/plan/loot-performance/`（不入库，见 `.gitignore`）。

## P1 模拟输入模型与按需管线

| 点 | 交付 | 状态 |
|---|---|---|
| P1-1 | 编译层保留 `weight`/`quality`/`rolls`/`bonus_rolls` 与被引用附魔 | ⬜ |
| P1-2 | `LuckGateAnalysis` 逐路径最小幸运门槛（0.01 对齐 + 真实公式回验） | ⬜ |
| P1-3 | `SimulationInput` / `ScenarioParams` / `SimulationInputKey`（含抽样次数档位） | ⬜ |
| P1-4 | `SimulationConstraintCatalog` 只发布约束，不枚举候选 Input（决策 32） | ⬜ |
| P1-5 | 条件树展开预算（决策 33） | ⬜ |
| P1-6 | worker 去重键改 `(tableId, inputKey)`、限流、代次校验 | ⬜ |
| P1-7 | 缓存格式 3：表级发现记录 + per-Input 测量值 + 按参数组合计数的 LRU（决策 37/46） | ⬜ |
| P1-8 | 新 C2S/S2C payload 与协议版本 | ⬜ |
| P1-9 | 启动只跑基准 Input | ⬜ |
| P1-10 | `match_tool` 移出 `SCENARIO_CONDITIONS`、工具改为真实求值（决策 8；布尔维度 8 类降 7 类） | ⬜ |

## P2 交互与联合见证

| 点 | 交付 | 状态 |
|---|---|---|
| P2-1 | 参数区（工具/附魔等级/幸运/抽样次数档位）与幸运输入框 + 建议档位 | ⬜ |
| P2-2 | `RecommendationSolver` 联合见证搜索与可点击「填入推荐值」 | ⬜ |
| P2-3 | 场景 Tab + 网格页快捷切换下拉与三态缓存标记（决策 43） | ⬜ |
| P2-4 | 防抖自动请求 + `[计算]` 按钮、页头状态 | ⬜ |
| P2-5 | 一键填充当前状态与 `PlayerStateProbe`（决策 41） | ⬜ |
| P2-6 | 注入边参与父表约束描述（决策 42） | ⬜ |
| P2-7 | 客户端偏好的文件实现（决策 29/37） | ⬜ |

## P3 保真与兼容

| 点 | 交付 | 状态 |
|---|---|---|
| P3-1 | 原版 `minecraft:gameplay/fishing` 群系条目全覆盖验收 | ⬜ |
| P3-2 | 整合包注入与动态条目的 Input 归属 | ⬜ |
| P3-3 | 双平台实机验收 | ⬜ |
| P3-4 | `docs/dev/` 文档同步（loottable / client-ui / network / mixin / config-integrations） | ✅ 2026-09-20 随 P0 与索引修复同步：loottable、client-ui、network、config-integrations 已改；mixin 核对无需改。P1/P2 落地后需再同步 |

## 与规划的已知偏差（实施期决定，需回写规划）

1. **P0 提前移除泥地打捞专门分支**（规划把 `RuntimeLootLinks` 注入边的通用化放在 P2，决策 42）。理由：P0 的完成标准是"网格上不再有误导数字"，而专门分支给基准场景配的是满级附魔钓竿，会让该表五件直接物品在基准下显示"能拿到"。移除后它们按静态提示显示「需要条件：工具带泥地打捞附魔（等级 ≥ 1）」，与规划 §7.2 的验收标准一致。代价：原版 `minecraft:gameplay/fishing` 里的注入条目在 P2 完成前只显示 `?`（未覆盖），因为父表的约束描述尚未并入被注入子表的条件树。
2. **P0 的「需要条件」判定是近似**：基准场景下测量值为零且路径引用旋钮时显示 `NeedsCondition` 而非「未命中」。P1 的 `LuckGateAnalysis` 与两轴展示状态落地后，该规则由"逐路径最小幸运门槛 + 参数化可适用性"取代。
3. **`PathHint.ReferencesParameter.detail` 用 `Component` 而非规划草案的 `String`**：需要本地化文案（附魔名、工具谓词原文），`String` 会把服务端语言固化进目录。
4. **决策 8 未随 P0 落地**：`match_tool` 仍在 `SCENARIO_CONDITIONS` 内，场景仍为它伪造布尔（基准场景取 `false`），只有泥地打捞的满级工具专门分支被移除。P0 期间 `docs/dev/loottable.md` 曾把它写成"已移出场景控制类型 / 工具真实求值"，与代码不符，已改回事实描述；落地见 P1-10。
