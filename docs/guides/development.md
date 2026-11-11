# 开发流程

本项目使用 Java 21、Mojang 官方映射及 Fabric / NeoForge 双平台。开发前先按[开发导航](../dev/README.md)定位代码与契约，任务复杂度分级规则以仓库根目录 `AGENTS.md` 为准。

## 实现约束

- **编码与结构**：遵循[代码风格规范](style-code.md)，禁止全限定类名，严格执行 `common` 模块的 Loader 隔离与客户端/服务端物理隔离。
- **文档编写**：遵循[文档撰写风格指南](style-documentation.md)，保持单一事实源、信息阶梯与 Mermaid 图表规范。
- **端与平台边界**：`common/` 只使用跨平台契约，Loader API 留在对应平台模块，Fabric 与 NeoForge 接入互相隔离；网络与懒加载边界见[网络与同步](../dev/foundation/network.md)与[仓库工具](../../tools/README.md)。
- **事件优先于 Mixin**：已有 Event API 可表达同等或相近行为时优先使用事件；Mixin 仅作调用入口，业务逻辑放在普通 Java 类中。
- **文本与本地化**：HUD / GUI 文本统一使用本地化 key，同步更新 `en_us.json` 和 `zh_cn.json`；文本结构与语义色表见[文本格式规范](../dev/foundation/text-format.md)。
- **外部 API 查询**：不确定原版或 Loader API 时，按 [API 查询](../agents/api-lookup.md)分级策略核实。

## 验证顺序

| 变更类型 | 验证方式 |
|---|---|
| Markdown 文档 | 核对当前事实、归属与相对链接；运行 `python tools/checks/check-docs.py --include-local-rules` |
| 仅 JSON 资源 | 校验 JSON 合法性；语言配对与资源接线门禁由对应工具或构建入口执行 |
| 仓库工具 (`tools/`) | 使用临时输入或 `--check` 运行受影响 CLI，核对退出码与诊断输出 |
| Java 代码或构建接线 | 先通过 IDEA MCP 检查 Git 工作区报错与警告，再串行执行 `./gradlew.bat build` |

工具命令与检查规则见[仓库工具](../../tools/README.md)。检查 IDEA 工作区问题时忽略纯 Markdown 格式提示，集中处理本次变更引入的代码错误与警告。

执行 Gradle 构建时需耐心等待完整任务结束，上一次构建完成前保持单一构建进程。检查或构建失败时先定位原因并修复再验证；若修复需要改变跨子系统设计，先沟通确认再继续。

开发阶段选用探针时，先按根目录 `AGENTS.md` 的「探针调用授权」核对授权范围，再按[核心探针](../../tools/probes/README.md)选择用例并通过本地命令运行。探针负责 NeoForge 核心链路的重复逻辑、输入和 PNG 证据；代表截图的外观与实际操作手感由用户复核。自动化验证使用 CLI 与临时输入副本，保持仓内不新增 test 源码文件的现有约定。

## 相关文档

- [代码风格规范](style-code.md)
- [文档撰写风格指南](style-documentation.md)
- [文档体系与维护约定](../README.md)
- [架构总览](../dev/foundation/architecture.md)
- [API 查询](../agents/api-lookup.md)
