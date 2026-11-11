# 战利品表函数与展示规则

> 本文档说明战利品表子系统中与玩家可读规则和模拟观测相关的底层机制：展示值派生优先级、函数静态描述与策略登记、运行时执行捕获预算、降级状态定义及开发调试表开关。
> 本文件不参与主任务导航，由[战利品表系统](../subsystems/loottable.md)与[战利品表机制细节](loottable-mechanics.md)按需链接。

## 1. 展示值派生与条件渲染规则

### 1.1 概率展示派生 (`PathHintAnalyzer.deriveDisplay`)

服务端为每个条目的当前输入派生四态 `Probability`（以及同口径的子表入口展示状态 `deriveEntryDisplay`）：

| 判定条件 | 派生状态 | 网格显示 | Tooltip 行为 |
|---|---|---|---|
| 全部路径经逐路径幸运门槛证明有效权重恒为 `0` | `Unreachable` | `0%`（或触发率） | 说明静态不可达 |
| 当前适用且模拟计数 > 0 | `Measured(p)` | 测值百分比（或触发率） | 并列声明触发率与模拟结果 |
| 当前适用且模拟计数 = 0，无任何可调条件提示 | `Measured(0.0)` | 「未命中」 | 说明当前条件下零命中 |
| 当前不适用或零命中，且路径引用了可调旋钮或场景条件 | `NeedsCondition` | 「需要条件」 | 逐条列出引用的条件或旋钮（附未解析条件 ID，不承诺必掉） |
| 条目在所有代表场景下均不可用（如受 32 场景截断） | `Unknown(UNCOVERED)` | `?` | 明确说明场景未被覆盖 |

- **声明触发率与模拟掉落率分开**：`random_chance` 节点将常量或常量区间写入 `DeclaredChance`。网格固定采用优先级链：`可适用性状态（NeedsCondition / 未知） → 可展示时的声明触发率 → 模拟值`。多个 `random_chance` 节点各自独立展示，不做相乘、并集或求补运算。
- **跨场景引用严格绑定参数**：`scenarioProbabilities` 仅在同组 `ScenarioParams` 存在测量缓存时下发对应场景引用；代表场景最小/最大值范围仅供对照，不代表数学全空间极值。

### 1.2 条件保真度与函数树呈现

- **保真度三态（`analysis_fidelity`）**：服务端解析层在描述不完整时标记 `partial`，无解析器或编码失败时标记 `unreadable`（统一显示原始条件注册名作为定位入口）。客户端根据标记自动使用斜体或 `CONDITION_UNREADABLE` 语义色（见[文本格式规范](../foundation/text-format.md)），不自行推断条件语义，也绝不把动态概率伪装成 `100%`。
- **函数树在 Tooltip 中的渲染规则**：
  - 每条获取路径先列「掉落条件」与来源，再列所属「生成效果」，函数自身的条件以「生效条件」附在对应节点下；
  - 全部路径均无条件和有效效果时隐藏获取方式区；完整展开且无自身条件的 `sequence` / `reference` 省略包装层，直接展示内部效果顺序；
  - 连续完全相同的兄弟节点自动折叠为「同上 ×N」，递归深度上限 `8`；
  - 附魔与标签名称在客户端通过带注册名回退的 `Component` 本地化；
  - 数值颜色由 `ItemDefinition.uncertaintyLevel()` 统一控制，存在条件时文案注明“条件满足时至少出现一次”。

## 2. 函数静态描述与策略登记

### 2.1 预览上下文与身份保真

静态投影期间安装 `LootFunctionPreviewContext`，从当前服务器读取 `ITEM_MODIFIER` 与熔炼配方（不构建 `LootContext`、不执行随机掉落）：

- **节点与嵌套预算**：包装预览与内层描述分别限制为 `8` 层、单次内层展开 `128` 个节点；超限写入 `analysis_incomplete` / `truncated`，确保不把缺失条件误判为无条件。
- **容器内容隔离**：`set_contents` / `modify_contents` 摘要最多列出 `8` 项、`8` 层、共享 `32` 个条目节点预算；容器内物品不并入外层物品目录或完成度进度。
- **数量与身份分离**：掉落条件与纯粹修改数量的函数不降低结果签名精度；历史 `APPROX_ITEM_ONLY(function)` 进度在同物品当前签名唯一时自动迁移，存在歧义时保留原记录。

### 2.2 能力登记与线格式策略 (`semantics_v1`)

[`LootFunctionHandlers`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/analysis/LootFunctionHandlers.java) 为每类原版或自定义函数登记必需的 `LootFunctionDescription` 与可选的 `LootFunctionPreview`（返回 `Applied`、`NeedsContext` 或 `Unsupported`）：

- **类型化策略 (`FunctionSemantics`)**：描述函数的效果类别（`FunctionEffectKind`）、保真度、随机性、预览不确定性与身份影响；包装器聚合子策略。
- **空操作证明 (`displayNoOp`)**：非书类物品上的空附魔映射在证明未修改身份且无附魔效果后标记为 `displayNoOp` 并省略展示；普通书因可能转化为附魔书，不作空操作省略。
- **线格式元数据编码 (`semantics_v1`)**：写入 `CatalogStreamCodec` 时每个节点最多保留 `32` 项元数据（优先保留策略、语义不完整、裁剪与空操作标记，辅助键按字母排序，键与值分别截断在 `64` / `512` 字符内）。

## 3. 运行时执行捕获 (`FunctionTraceSession`)

`FunctionTraceSession` 在模拟期间旁路记录实际进入执行体的函数链，与静态规则树互为独立维度。

### 3.1 Mixin 捕获与对象传递

- **两个注入点**（配置清单见[Mixin 边界与接线](../foundation/mixin.md)）：
  - `LootItemConditionalFunctionMixin` 使用 `@WrapOperation` 包住 `apply` 内部对 `run` 的调用，精确捕获“外层条件通过并进入函数执行体”的时刻，不额外重掷条件或消费随机数。
  - `LootTableSplitterMixin` 包装 `createStackSplitter` 的输入/输出 `Consumer`，使原版拆栈产生的物品副本自动继承输入栈的观测链。
- **引用传递与容器隔离**：使用 `IdentityHashMap<ItemStack, TraceHandle>` 追踪产物实例而不污染物品 NBT/组件；遇到第三方复制导致的引用断裂时直接记为未关联，不做近似猜测。执行容器内容填充函数时标记 `contentExpanded = false`，防止内部函数混入外层产物链。
- **静态条件与捕获分离**：内层包装函数的条件仅在静态描述路径（`LootFunctionDescribeSupport.describeNested` 安装 `RegistryOps`）解析并原位挂载；运行时捕获路径不安装该上下文。
- **分析与展示裁剪分离**：`LootTableProjector` 保留完整函数语义供条件规划读取；仅在网络传输（`CatalogStreamCodec`）时按顶层 `64`、子节点 `64`、嵌套 `16` 层裁剪并标记 `truncated`。

### 3.2 捕获预算与越界策略

| 预算项 | 上限 | 越界行为 |
|---|---|---|
| 单条链节点数 | `64` | 停止增量构造并标记 `PARTIAL` |
| 函数嵌套深度（执行帧） | `16` | 放弃新增帧并置截断标志 |
| 单个结果子节点数 | `64` | 停止挂入子节点并置截断标志 |
| 单个签名观测链数 | `16` | 丢弃多余链并置 `truncated` + `incomplete` |
| 单次输入唯一链条目数 | `4096` | 停止新增条目 |
| 单轮栈对象关联数 | `4096` | 暂停本轮新增捕获 |
| 会话唯一函数描述缓存 | `4096` | 停止新增描述缓存 |

**核心不变量**：观测越界或记录器自身异常仅置降级标志，绝不影响原函数的实际掉落逻辑与概率计数；被包装原函数抛出的异常正常退出栈帧后原样向上传播。

### 3.3 观测缓存与四种降级状态

- **输入级缓存与白名单**：函数观测摘要随 `(表哈希, inputKey)` 一起存入 `InputMeasurement` 并随 LRU 淘汰；向客户端下发 DTO 时，仅 `exactInput()` 精确命中的结果（及公共目录的 `baseline`）携带 `observedFunctions`，其余场景 Projection 置空。
- **同签名来源合并**：常规静态来源与 `MOD_INTEGRATION` 注入来源在服务端构建期与客户端视图统一合并为单个条目（路径追加、来源集合取并集）。
- **四种独立降级状态（不可互推）**：

| 状态 | 判据 | 语义说明 |
|---|---|---|
| 未观测 | `chains` 为空 | 未记录到执行链；不代表未执行（可能无函数或引用断裂） |
| 未完整 | `incomplete = true` | 存在未归因产物或链遭截断；不代表静态规则解析失败 |
| 截断 | `truncated = true` | 超出运行时预算并被裁剪 |
| 未解析 | `FunctionFidelity.UNRESOLVED` | 静态阶段无法识别或解码该函数；与运行时是否执行无关 |

## 4. 开发模式调试表 (`LootDebugMode`)

[`LootDebugMode`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/loottable/diagnostics/LootDebugMode.java) 是进程级开关（系统属性 `usb.loot.debug` 优先于环境变量 `USB_LOOT_DEBUG`）：

- **关闭时（默认）**：`ArchaeologyJournalCatalog` 与 `JournalCategoryLoader` 跳过 `archaeology/debug/` 前缀战利品表与 `journal_categories/dev_debug.json`，普通玩家目录完全不可见。
- **开启时（`-Dusb.loot.debug=true` 或 `USB_LOOT_DEBUG=true`）**：调试表与调试分类进入目录，用于核对函数包装嵌套、天气/时间场景、>64 函数裁剪及解析深度超限等边界样本（资源位于 `data/unsuspiciousblock/{loot_table/archaeology/debug/,journal_categories/dev_debug.json,item_modifier/debug_*.json}`）。

## 5. 相关文档

- [战利品表系统](../subsystems/loottable.md)
- [战利品表机制细节](loottable-mechanics.md)
- [文本格式规范](../foundation/text-format.md)
- [Mixin 边界与接线](../foundation/mixin.md)
