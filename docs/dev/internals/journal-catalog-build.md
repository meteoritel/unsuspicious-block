# 笔记目录构建细节

> 服务端目录构建的实现细则：整代构建流程、原子发布与线程模型、目录哈希与按需同步、调试命令。
> 本文件不参与任务导航，只被 [考古笔记系统](../subsystems/journal.md) 链接。**新增追踪前缀或分类只需要读那篇**；只有改动目录构建流程、缓存哈希或调试命令时才读本文件。

## 1. 整代构建流程

入口是 [`ArchaeologyJournalServerCatalog`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/journal/catalog/ArchaeologyJournalServerCatalog.java)，在 `SERVER_STARTED` 事件中由平台入口调用 `ensureLoaded(server)`。

```
ensureLoaded(server)
  ├─ buildGeneration(server) 在局部构建完整一代
  │   ├─ 捕获资源、编译和静态投影
  │   ├─ 捕获当代注入声明与来源名 → DeclaredInjectionExpansion.build
  │   ├─ 机制诊断 → 可模拟表约束规划 → 汇总场景预算诊断
  │   ├─ 一次标记最终不可用状态 → 计算每表 hash
  │   ├─ 构造 CatalogGeneration → 恢复可用基准缓存和不可用展示 overlay
  │   └─ 返回 BuildResult（整代、未缓存队列、不可用表）
  ├─ 单个 volatile 引用发布完整 BuildResult.generation
  └─ 未缓存基准输入入队；提交与排空广播均校验 generation
```

`DeclaredInjectionExpansion` 在构建时将物品声明与运行时子表边转为共同贡献，保留来源、模式、完整函数路径与第一跳。`ExpandedTable` 固定原生、展示和规划视图；物品声明进入静态展示，运行时子树进入规划，子树展示条目由抽样发现。路径栈阻止循环，多父路径分别保留门槛；去重使用条件携带的语义或出处身份。

`CatalogDisplayDerivation` 消费静态表、约束、输入与测量/发现只读快照，返回展示模型。缓存读取、哈希准入与 LRU 触达留在调用方。`CatalogTableDto.assemble` 从完整模型一次装配场景假设、物品、子表条件、branches 和 options；分支直接消费模型及展开贡献。无约束时 options 为空，branches 为空列表。

`staticTables()` / `staticTable()` 提供完整静态资格，`tracked()` 与 `queryIndex()` 不依赖模拟进度；`displayTable()` 集中 overlay 优先与静态回退。`simulatedTables()` 供需要确认完整模拟进度的命令使用。推荐和玩家探测读取同代固定 `SimulationContext`。

工作器汇总等待者并对去重工作交付一次 `CatalogSimulationSubmission`。目录一次消费凭据，校验代次、表哈希、签发输入及基准发布资格后写入测量；只有基准贡献发布共享 overlay。提交结果与同步缓存结果交由 `CatalogSimulationDelivery` 构造回执，离线等待者跳过发送。当前代次排空后才广播 hash。

概率缓存仅在最终准入状态确认后恢复；数据包重载经过暂停、清队列、失效、重建和恢复。构建失败发布明确空状态。每表 hash 覆盖子树资源、编译产物、声明注入和相关附魔定义，外部间接依赖按现行重载盐失效。

## 2. 冻结目录与按需同步

`CatalogGeneration.readSnapshot()` 是完整目录读视图的 Seam。快照包含 generation、overlay revision、冻结的目录 payload 和 SHA-256 摘要。overlay 提交与快照捕获使用同一锁；提交同时递增 revision 并失效投影/摘要缓存。`JournalCatalogHandler` 的 hash 与全量下发消费同一缓存快照，主线程外入口先调度到服务器线程。

快照通过实际 `SyncArchaeologyCatalogPayload.STREAM_CODEC` 编码再解码，剥离活谓词与内部约束，冻结函数树及元数据的线路裁剪结果；摘要来自冻结 payload 的实际编码字节。该线路编码用枚举/状态字段和长度前缀区分类型与值，Map 按键稳定排序、来源集合用位图，Component JSON 的对象键排序，列表保留语义顺序。表、概率、路径、函数、条件、观测摘要、子表、branches、options（含真实 generation 凭据）及分类结构全部经同一 codec 进入摘要。

每表概率缓存 hash 独立于完整目录摘要：前者决定测量缓存准入与按需表内容凭据，后者决定客户端是否刷新完整目录。代内内容改变后快照失效；新代 options 凭据进入摘要并触发同步。协议字段形状与顺序保持 `4.11`。

## 3. 调试命令与目录稳定性

- `/usb journal reload` 清空 `LootProbabilityData`、worker 队列和服务端目录后重新解析、模拟；**不清除玩家的笔记进度与日志**。进度监听器在任务计数完成时先解除，因此控制台执行或执行玩家中途离线也不会留下旧监听器。
- `/usb journal unlock table [table_id]` 使用启动即完整的解析态视图（`staticTables()`），不依赖模拟进度。
- `/usb journal unlock item [table_id]` 需要动态物品也已进入稳定目录，因此 worker 忙碌、或模拟 overlay（`simulatedTables()`）尚未覆盖解析态视图的全部表时会**拒绝执行**，避免把半成品目录写入玩家状态。
- 父表 Intro、`unlock item` 和 100% 完成奖励统一使用"当前表及全部后代表，按 `LootResultSignature` 去重"的物品闭包（`CatalogQueryIndex.subtreeItems`）。共享子表和循环引用只遍历一次。
