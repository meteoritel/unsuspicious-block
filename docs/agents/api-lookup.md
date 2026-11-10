# 原版与 Loader API 查询

当涉及原版 API 或 NeoForge/fabric API 时，必须遵循以下**分级策略**：

### Level 1（默认）  
-  优先基于**已有上下文 + 常识性 API 使用方式**完成任务：若 API 用法明确，应使用上下文与常识性判断。
---

### Level 2（不确定时）

触发条件（满足任一）：

- API 参数或返回值不确定
- 编译报错（方法调用不正确）
- 方法名/类名存在歧义

允许操作：
- 调用 mc-developing-mcp 查询 API 源码或官方文档
- 通过 IDEA MCP 反编译原版/NeoForge/Fabric 类（实测可用，mc-developing-mcp 取不到原版类时的首选替代）：
- **定位类**：`search_symbol(include_external=true, q="<类名>")` → 返回 merged jar 内的 `net/minecraft/.../<类>.class` 路径。
- 同一类通常有 3 份：vanilla、neoforge、loom-cache。优先访问 vanilla 类；仅当目标符号涉及平台补丁、平台特有行为或 vanilla 中不存在时，再访问对应平台类。
  - `common/build/moddev/artifacts/vanilla-*-minecraft-merged.jar!`
  - `neoforge/build/moddev/artifacts/neoforge-*-merged.jar!`
  - `.gradle/loom-cache/minecraftMaven/**/-merged.jar!`
- **读源码**：`read_file(file_path="<绝对路径>\\...vanilla-*-minecraft-merged.jar!\\net\\minecraft\\world\\entity\\LivingEntity.class")` → 直接返回 Fernflower 反编译后的完整 `.java` 源码，支持 `limit` / `offset` 取片段
- **跳转定义**：对**项目内源码**里的原版符号调用 `get_symbol_info`（给 file/line/column）→ 返回声明片段 + documentation，`declarationFile` 指向 merged jar
- **限制**：`get_symbol_info` 的 `filePath` 不认 jar 的 `!` 路径（报 File not found），只能对项目内源码用；`search_regex` / `search_text` 不索引 jar 内部（返回空）。要读原版整类只能走 `read_file` + jar 路径

---

### Level 3（必须用户确认）
**执行前必须说明理由并询问用户**

触发条件：
- 需要源码级理解且 Level 2 无法解决（如数据库中不存在目标 API）

允许：源码反编译查询，使用 fetch 查阅网络获取 API 用法。

---
