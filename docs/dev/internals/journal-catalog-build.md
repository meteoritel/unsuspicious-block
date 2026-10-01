# 笔记目录构建细节

> 服务端目录构建的实现细则：整代构建流程、原子发布与线程模型、目录哈希与按需同步、调试命令。
> 本文件不参与任务导航，只被 [考古笔记系统](../subsystems/journal.md) 链接。**新增追踪前缀或分类只需要读那篇**；只有改动目录构建流程、缓存哈希或调试命令时才读本文件。

## 1. 整代构建流程

入口是 [`ArchaeologyJournalServerCatalog`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/catalog/ArchaeologyJournalServerCatalog.java)，在 `SERVER_STARTED` 事件中由平台入口调用 `ensureLoaded(server)`。

```
ensureLoaded(server)
  ├─ buildGeneration(server)                  在局部对象上构建整代状态，全部成功后才发布
  │   ├─ 1. LootTableSourceSnapshot.capture()  一次列举拿到全表"有效原文 + 完整资源栈"
  │   ├─ 2. ArchaeologyJournalCatalog.load()   建图 → 编译 → 投影 → 静态读模型（概率为 "?" 占位）
  │   │                                        产出 LootTableAnalysisSession 与 CatalogStructure
  │   ├─ 3. 不可用机制判定                      引用本模组无法模拟的机制的表标记为 Unknown(UNPARSED)，
  │   │                                        不入队模拟但仍发布（否则整表在客户端消失）
  │   ├─ 4. computeTableHashes()               每表子树内资源栈摘要 + 编译产物摘要 + 被引用附魔定义摘要
  │   └─ 5. new CatalogGeneration(...)         整代静态部分成型
  ├─ currentGeneration = ...                  单个 volatile 引用发布；构建抛异常则改为 empty()
  ├─ 从 SavedData（LootProbabilityData）恢复命中缓存的表 -> 写入当代 overlay
  │     needsResimulation(tableId, hash) 判断哈希是否变化
  └─ 未缓存表入队 LootProbabilitySimulationWorker 分 tick 模拟
        worker 主线程分片消费 -> commitSimulatedTable(generationId, ...) 校验代次后写 overlay + SavedData
        整批结束 -> broadcastCatalogHash(generationId, ...) 广播目录哈希
```

**关键设计**

- **原子发布**：整代静态部分（快照 / 引用图 / 编译产物 / 静态投影 / 每表哈希 / 分类结构）在局部对象上构建完成后经单个 volatile 引用整体替换；构建抛异常时进入 `CatalogGeneration.empty()`，不留半成品。
- **非阻塞渐进填充**：`ensureLoaded` 立即返回，模拟结果作为当代 overlay 渐进增长；不受模拟进度影响的**解析态视图**由 `getRawCatalog()` 提供，启动即完整。
- **模拟结果持久化**：`LootProbabilityData`（SavedData，附加在 overworld）缓存每表概率，哈希变化时才重新模拟。
- **哈希覆盖间接依赖**：表哈希的输入是子树内每表的资源栈摘要、编译产物摘要与被引用附魔定义摘要（附魔 id 与 `max_level`），覆盖 item tag 成员与附魔等级上限变化；已知残余见 [战利品表系统](../subsystems/loottable.md) 的「模拟结果缓存」。
- **重载与重建**：数据包重载由 `DataPackReloadListener` 只置脏标记，真正的重建在服务端 tick 路径消费（`ServerLootTableConfigManager.tick`），见 [配置与第三方联动](../foundation/config-and-integrations.md)。
- **代次校验**：模拟提交与队列排空广播都携带构建时的 `generation`，旧代结果被直接丢弃。
- **线程安全**：静态部分不可变并整体发布；只有模拟 overlay 是 `ConcurrentHashMap`，读路径无锁，写路径仅在主线程。
- **worker 回退**：模拟工作线程未启动时回退到主线程同步模拟，避免功能缺失。

## 2. 目录哈希与按需同步

服务端计算整个目录的 SHA-256 哈希（`computeCatalogHash()` → `CatalogGeneration.catalogHash()`），模拟完成后广播给所有在线玩家。哈希输入取自网络形态 `CatalogTableDto`——与实际上线的内容一一对应（场景假设条件树在表级只计一次，物品与子表侧只计 key 与概率），因此"要发的内容变了"必然改变哈希。结果在当代内缓存，任一表提交新模拟结果即失效。客户端比对本地哈希，不一致时主动请求全量目录，避免每次登录都全量下发。

## 3. 调试命令与目录稳定性

- `/usb journal reload` 清空 `LootProbabilityData`、worker 队列和服务端目录后重新解析、模拟；**不清除玩家的笔记进度与日志**。进度监听器在任务计数完成时先解除，因此控制台执行或执行玩家中途离线也不会留下旧监听器。
- `/usb journal unlock table [table_id]` 使用启动即完整的解析态视图（`getRawCatalog()`），不依赖模拟进度。
- `/usb journal unlock item [table_id]` 需要动态物品也已进入稳定目录，因此 worker 忙碌、或模拟 overlay（`getCatalog()`）尚未覆盖解析态视图的全部表时会**拒绝执行**，避免把半成品目录写入玩家状态。
- 父表 Intro、`unlock item` 和 100% 完成奖励统一使用"当前表及全部后代表，按 `LootResultSignature` 去重"的物品闭包（`CatalogQueryIndex.subtreeItems`）。共享子表和循环引用只遍历一次。
