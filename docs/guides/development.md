# 开发流程

本项目使用 Java 21、Mojang 官方映射及 Fabric / NeoForge 双平台。先按[开发导航](../dev/README.md)定位机制，再按改动类型选择验证。

## 任务分级

- 简单任务：局部改动且不涉及跨子系统契约，直接执行。
- 中等任务：涉及多个文件或局部设计选择，先制订计划再执行。
- 复杂任务：跨子系统契约、平台能力新增、侵入性 Mixin 或不确定 API 需要先讨论拆分；已有明确方案且用户授权执行时，按方案推进。


## 实现约束

- `common/` 使用跨平台契约，Loader API 留在相应平台模块；Fabric 与 NeoForge 接入互相隔离。
- 服务端执行面使用公共类型。客户端入口、客户端 Mixin 和惰性绑定边界见[网络与同步](../dev/foundation/network.md)与[依赖检查](../../tools/README.md)。
- 先考虑线程、分配、扫描与缓存成本，再选择实现。复用真实共有的能力，保持业务状态的所有者清晰。
- 优先使用 Event API；Mixin 只保留必要入口，业务逻辑放普通类。已有事件可表达同等或相近行为时使用事件。
- 允许两个 Loader 采用不同接入方案实现相同玩家语义。
- 注释使用简体中文，专有名词保留英文，说明设计原因和业务边界。
- HUD / GUI 文本使用本地化 key，`en_us.json` 和 `zh_cn.json` 同步更新；文本结构见[文本格式](../dev/foundation/text-format.md)。
- API 不确定时按 [API 查询](../agents/api-lookup.md)获取依据。

## 验证顺序

| 变更 | 验证 |
|---|---|
| Markdown | 核对当前事实、归属和本地链接；按工具入口运行文档检查，Markdown 格式问题忽略 |
| 仅 JSON | 校验 JSON 合法性；语言与资源接线的完整门禁由对应工具和构建入口执行 |
| Python / PowerShell 工具 | 运行受影响 CLI 与临时成功、失败输入，核对退出码和诊断 |
| Java 或构建接线 | 先通过 IDEA MCP 检查 Git 工作区报错与警告，再串行执行 `./gradlew.bat build` |

工具用途和执行命令见[仓库工具](../../tools/README.md)。IDEA MCP 地址为 `http://127.0.0.1:64342/stream`；忽略 Markdown 格式问题，处理本次变更引入的错误与警告。

Gradle 构建等待至少 120 秒；返回运行中任务 ID 时持续等待至结束。上一次构建结束前保持单一构建进程。检查或构建失败时先定位、修复再验证；原因不明确或修复需要改变设计时，说明问题后继续讨论。

实机测试交由用户，自动化检查使用 CLI 和临时输入副本，保持现有不新增 test 文件的约定。

## 相关文档

- [文档维护](../README.md)
- [架构总览](../dev/foundation/architecture.md)
- [API 查询](../agents/api-lookup.md)
