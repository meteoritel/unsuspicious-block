# 原版与 Loader API 查询

当涉及 Minecraft 原版 API 或 NeoForge / Fabric API 时，必须遵循以下**分级策略**。

## Level 1（默认）

- 优先基于**已有上下文与常识性 API 用法**完成任务：若 API 用法明确，直接按上下文与常识性判断编写。

## Level 2（不确定时）

**触发条件**（满足任一即可）：
- API 参数、泛型或返回值不确定
- 编译报错（方法签名或调用不正确）
- 类名或方法名存在歧义

**允许操作**：
- 调用 `mc-developing-mcp` 查询 API 源码或官方文档。
- 通过 IDEA MCP 反编译原版 / NeoForge / Fabric 类（`mc-developing-mcp` 取不到原版类时的首选替代）：
  - **定位类**：调用 `search_symbol(include_external=true, q="<类名>")` 获取 merged jar 内的 `.class` 路径。
    - 同一类通常存在 `vanilla`、`neoforge`、`loom-cache` 三份映射产物；优先读取 `vanilla`，仅当符号涉及平台补丁、平台特有行为或 vanilla 不存在时，再访问对应平台 jar：
      - `common/build/moddev/artifacts/vanilla-*-minecraft-merged.jar!`
      - `neoforge/build/moddev/artifacts/neoforge-*-merged.jar!`
      - `.gradle/loom-cache/minecraftMaven/**/-merged.jar!`
  - **读源码**：调用 `read_file` 传入 jar 内 `!` 路径（如 `...vanilla-*-minecraft-merged.jar!\net\minecraft\world\entity\LivingEntity.class`），直接返回 Fernflower 反编译后的完整 `.java` 源码，支持 `limit` / `offset` 分段读取。
  - **跳转定义**：对**项目内源码**中的原版符号调用 `get_symbol_info`（提供 `file` / `line` / `column`），返回声明片段与文档，`declarationFile` 指向对应 merged jar。
  - **查询限制**：`get_symbol_info` 的 `filePath` 不支持带 `!` 的 jar 路径，`search_regex` / `search_text` 不索引 jar 内部；读取原版整类需使用 `read_file` 配合 jar 路径。

## Level 3（必须用户确认）

**执行前必须向用户说明理由并获得确认**。

**触发条件**：
- 需要深入源码级理解，且 Level 2 无法解决（例如本地反编译或索引中不存在目标 API）。

**允许操作**：
- 执行更深层的源码反编译查询，或使用网络检索获取 API 用法。
