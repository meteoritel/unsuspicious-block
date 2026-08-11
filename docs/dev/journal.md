# 考古笔记系统

本文档描述 `journal/` 包的架构：服务端如何构建战利品表目录、玩家进度如何持久化、战利品发现如何被追踪并解锁目录条目、以及状态如何同步到客户端。

## 1. 职责概述

考古笔记是模组的核心玩法载体，承担四件事：

1. **目录**：解析服务端所有战利品表，按分类组织成可浏览的目录，每张表列出可能产出的物品及估算概率。
2. **进度**：记录玩家解锁了哪些表、哪些物品、获取数量，并通过 mixin 持久化到玩家 NBT。
3. **追踪**：在玩家刷拭可疑方块、开箱、钓鱼、触发附魔战利品时，捕获实际产出的物品，解锁对应目录条目并记录日志。
4. **日志**：记录每次发现的物品、坐标、维度、群系、结构、时间，支持分组、备注、复制传送指令。

## 2. 子包结构

```
journal/
├── catalog/      目录构建（服务端解析 + 客户端快照）
│   ├── ArchaeologyJournalCatalog         原始目录解析（load）
│   ├── ArchaeologyJournalServerCatalog   服务端目录（含概率模拟调度 + SavedData 缓存）
│   └── JournalCategoryLoader             分类规则加载（data/journal_categories/）
├── state/        玩家进度状态
│   ├── ArchaeologyJournalState           进度状态（表/物品解锁 + 增量同步 revision）
│   ├── ArchaeologyJournalStateHolder     mixin 接口，附加到 ServerPlayer
│   ├── ArchaeologyJournalLogState        日志状态（条目列表）
│   ├── ExcavationLogEntry                日志条目 record
│   ├── LootSourceType                    战利品来源类型枚举
│   ├── NbtDataMigrator / NbtDataVersion  旧存档数据迁移
│   └── ArchaeologyJournalLogLegacyAccess 旧日志格式兼容访问
├── tracking/     战利品追踪
│   ├── LootTrackingContext / Holder      追踪上下文（ThreadLocal）
│   ├── LootSession                       一次 loot roll 的会话
│   ├── ArchaeologyLootRuntimeTracker     运行时解锁/记录入口
│   ├── ContainerTrackingService          容器开箱追踪
│   ├── DecoratedPotTrackingService       陶罐追踪
│   ├── DirectLootTrackingService         直接获取追踪（钓鱼等）
│   ├── MenuTrackingSnapshot(Service)     菜单快照（追踪开箱前后差异）
│   ├── JournalLogRecorder                日志记录器
│   ├── ArchaeologyChallengeChecker       考古挑战成就检查
│   ├── JournalCompletionRewardChecker    100% 完成奖励检查
│   ├── JournalProgressSignatureMigrator  签名迁移（旧进度对齐新目录）
│   ├── event/LootTrackingEvents          战利品发现事件总线
│   ├── event/LootDiscoveredEvent         发现事件
│   ├── event/LootTrackingBootstrap       内建订阅者注册
│   └── settlement/LootSettlementStrateg(y|ies)  结算策略
└── sync/         日志分片同步
    └── ArchaeologyJournalLogSyncSession(Holder)
```

## 3. 目录构建（服务端）

目录构建入口是 [`ArchaeologyJournalServerCatalog`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/catalog/ArchaeologyJournalServerCatalog.java)，在 `SERVER_STARTED` 事件中由平台入口调用 `ensureLoaded(server)`。

### 3.1 加载流程

```
ensureLoaded(server)
  ├─ 1. ArchaeologyJournalCatalog.load(resourceManager, registryAccess)
  │     解析所有 loot_table 资源 -> rawCatalog（概率为 "?" 占位符）+ catalogStructure（分类结构）
  ├─ 2. computeTableHashes()
  │     对每个表 JSON 内容计算 SHA-256，递归纳入引用的子表内容（覆盖 item tag 成员变化）
  ├─ 3. 从 SavedData（LootProbabilityData）恢复已缓存表 -> catalog
  │     needsResimulation(tableId, hash) 判断哈希是否变化
  └─ 4. 未缓存表入队 LootProbabilitySimulationWorker 分 tick 模拟
        worker 主线程分片消费 -> commitSimulatedTable() 写入 catalog + SavedData
        整批结束 -> broadcastCatalogHash() 广播目录哈希
```

### 3.2 关键设计

- **非阻塞渐进填充**：`ensureLoaded` 立即返回，catalog 随模拟完成渐进填充。客户端打开笔记时，未模拟完的表概率显示为 "?"。
- **模拟结果持久化**：`LootProbabilityData`（SavedData，附加在 overworld）缓存每张表的概率结果，避免每次重启重新模拟。哈希变化（数据包修改战利品表）时才重新模拟。
- **哈希覆盖间接依赖**：表哈希不仅包含自身 JSON，还递归纳入引用的子表内容与解析后的物品签名，覆盖 item tag 成员变化等间接依赖。
- **线程安全**：`catalog` / `rawCatalog` 用 `ConcurrentHashMap`，读路径无锁；写路径仅在主线程（`ensureLoaded` / `commitSimulatedTable`）。
- **worker 回退**：模拟工作线程未启动时回退到主线程同步模拟，避免功能缺失。

### 3.3 分类结构

`catalogStructure` 由 [`JournalCategoryLoader`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/catalog/JournalCategoryLoader.java) 从 `data/unsuspiciousblock/journal_categories/` 加载，定义目录的分类、图标、排序与翻译键。分类规则与领域语言见 [`docs/journal-categories.md`](../journal-categories.md) 与 [`docs/adr/0007`](../adr/0007-cat-system-separates-content-balance-and-domain-rules.md)。

### 3.4 目录哈希与按需同步

服务端计算整个目录的 SHA-256 哈希（`computeCatalogHash`），模拟完成后广播给所有在线玩家。客户端比对本地哈希，不一致时主动请求全量目录（`RequestCatalogPayload` -> `SyncArchaeologyCatalogPayload`）。这避免每次登录都全量下发目录，只在目录变化时同步。

## 4. 玩家进度状态

### 4.1 状态结构

[`ArchaeologyJournalState`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/state/ArchaeologyJournalState.java) 是玩家进度的核心，三层结构：

```
ArchaeologyJournalState
├─ tables: LinkedHashMap<TableId, TableProgress>
│   └─ TableProgress
│       ├─ unlocked: boolean                  表是否已解锁
│       ├─ completionRewardClaimed: boolean   100% 完成奖励是否已发放
│       └─ items: LinkedHashMap<sigKey, ItemProgress>
│           └─ ItemProgress{ unlocked, count }  物品解锁与获取数量
├─ revision: long                            增量同步版本号
└─ dirtyTables: LinkedHashSet<TableId>       脏表追踪
```

物品用 `LootResultSignature.toStoredKey()` 作为 key（见 [战利品表系统](loottable.md) 的签名机制），而非物品 ID，以区分同一物品在不同表/不同条件下的产出。

### 4.2 持久化

状态通过 mixin 附加到 `ServerPlayer`：

- [`ArchaeologyJournalStateHolder`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/state/ArchaeologyJournalStateHolder.java) 是 mixin 接口，`ServerPlayer` 实例通过 `unsuspiciousblock$getArchaeologyJournalState()` 暴露状态。
- `PlayerJournalStateMixin` / `ServerPlayerJournalStateMixin`（见 [mixin.md](mixin.md)）实现状态的读写，序列化到玩家 NBT。
- `NbtDataVersion` / `NbtDataMigrator` 处理旧存档格式迁移，序列化时写入版本号。

### 4.3 增量同步

状态同步采用 **revision + 脏表** 增量机制：

- `markDirty(tableId)`：变更时仅标记脏表，**不立即递增 revision**。
- `drainDirtyTables()`：发送增量包时收集脏表，若非空则 revision +1。
- 这样一次业务操作内多次 `markDirty` 不会让 revision 跳跃，客户端的间隙检测（`incomingRevision > lastNotifiedRevision + 1`）才有意义。
- `mergeFromIncremental`：客户端采用**服务端权威语义**，增量数据直接覆盖本地条目，不做二次合并。
- 全量重置操作（`clear` / `removeTable`）也 +1 revision，对应全量同步。

## 5. 战利品追踪（核心数据流）

这是考古笔记最复杂的部分：如何把玩家的一次 loot roll 转化为目录解锁与日志记录。

### 5.1 追踪上下文

[`LootTrackingContext`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/tracking/LootTrackingContext.java) 是不可变 record，描述当前追踪的根表与子表链路：

```
LootTrackingContext{
  player,           触发玩家
  rootTableId,      根表 ID（catalog 收录的表），签名解析锚定此表
  lootSource,       来源类型（钓鱼/开箱/刷拭/...）
  gameTime, dayTime,时间戳
  tableStack,       从根表到当前层级的完整链路
  pos,              触发位置（鱼漂/容器/方块）
  sourceBlockId     源方块 ID（刷拭场景有值）
}
```

- 通过 `LootTrackingContextHolder`（ThreadLocal）在 loot roll 前写入。
- `descend(childTableId)` 派生子上下文，`tableStack` 追加子表 ID。
- [`NestedLootTableMixin`](../../common/src/main/java/com/meteorite/unsuspiciousblock/mixin/interaction/NestedLootTableMixin.java) 识别上下文，自动捕获嵌套子表物品并派生上下文。

### 5.2 追踪服务

三个追踪服务分别处理不同来源：

| 服务 | 触发场景 | 说明 |
|---|---|---|
| `DirectLootTrackingService` | 钓鱼、附魔战利品等直接进背包 | 物品立即归玩家所有 |
| `ContainerTrackingService` | 开箱（箱子、埋藏宝藏等） | 通过 `MenuTrackingSnapshot` 比对开箱前后差异 |
| `DecoratedPotTrackingService` | 陶罐 | 类似容器，但走陶罐专属路径 |

容器追踪的关键是 `MenuTrackingSnapshot`：记录玩家打开菜单时的物品快照，关闭时比对差异，确定实际取走了哪些物品。

### 5.3 事件总线

追踪入口在 loot roll 结束后通过 [`LootTrackingEvents.submit`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/tracking/event/LootTrackingEvents.java) 提交结果：

```
LootTrackingEvents.submit(session, itemCounts, settlementStrategy)
  ├─ session.complete(itemCounts) -> LootSession.Commit
  │     Commit 含 rootContext + discoveredLoot + tableStack
  ├─ resolveTrackingState(player, rootTableId)
  │     仅当 rootTableId 被 catalog 收录时才继续（isTrackedTable）
  └─ dispatch(LootDiscoveredEvent)
        按 priority 顺序同步通知所有订阅者
```

### 5.4 内建订阅者

[`LootTrackingBootstrap.registerListeners()`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/tracking/event/LootTrackingBootstrap.java) 注册 5 个订阅者，按优先级顺序执行：

| 优先级 | 订阅者 | 职责 |
|---|---|---|
| 100 | `onUnlock` | `ArchaeologyLootRuntimeTracker.unlockSession` 解锁表与物品 |
| 200 | `onRecordFirstUnlock` | `JournalLogRecorder.recordFirstUnlock` 记录首次发现元数据 |
| 250 | `onSettle` | `settlementStrategy.settle` 结算（立即写日志或创建待定日志） |
| 300 | `onCheckChallenge` | `ArchaeologyChallengeChecker.checkAndGrant` 考古挑战成就 |
| 350 | `onCheckCompletionReward` | `JournalCompletionRewardChecker.checkAndReward` 100% 完成奖励 |

> **顺序约束**：解锁（100）必须先于成就与完成奖励检查（300/350），否则检查时进度未更新。优先级数字越小越先执行。

### 5.5 结算策略

[`LootSettlementStrategies`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/tracking/settlement/LootSettlementStrategies.java) 提供两种结算策略：

| 策略 | `recordsItemsImmediately` | 行为 | 适用场景 |
|---|---|---|---|
| `immediate()` | true | 生成时立即记录获取数量与最终日志 | 钓鱼等直接进背包 |
| `deferred(consumer)` | false | 生成时只创建待定日志，实际取出后再更新 | 容器/可疑方块暂存（物品可能被丢弃） |

延迟结算解决的问题是：玩家打开容器后可能不取走物品，或可疑方块刷出的物品可能被丢弃。待定日志暂存在容器/方块实体上，物品实际进入玩家所有权后才转为正式日志。

## 6. 网络同步

同步调度入口是 [`ArchaeologyJournalNetwork.syncOnJoin`](../../common/src/main/java/com/meteorite/unsuspiciousblock/network/ArchaeologyJournalNetwork.java)，玩家加入时按序执行：

```
syncOnJoin(player)
  ├─ JournalLogHandler.restoreAndSyncOnJoin   从 NBT 恢复日志并下发快照
  ├─ JournalCatalogHandler.syncCatalogHash    下发目录哈希（客户端比对后按需请求全量）
  ├─ JournalProgressSignatureMigrator.migrate 签名迁移（旧进度对齐新目录）
  ├─ JournalStateHandler.syncStateFull        全量下发进度状态
  ├─ JournalCompletionRewardChecker.checkAndRewardAll  补发完成奖励
  └─ ArchaeologyChallengeChecker.checkAndGrantAll       补发考古成就
```

补发奖励/成就是在全量状态同步之后，确保客户端 catalog 已就绪可解析表名，且避免事件漏触发时遗漏授予。

运行时同步分三类：

| 同步类型 | 触发 | Payload |
|---|---|---|
| 进度增量 | `drainDirtyTables` | `SyncJournalStateIncrementalPayload` |
| 进度全量 | 加入 / 数据包重载 | `SyncJournalStatePayload` / `SyncJournalStateFullPayload` |
| 目录按需 | 哈希不一致 | `SyncCatalogHashPayload` -> `RequestCatalogPayload` -> `SyncArchaeologyCatalogPayload` |
| 日志分片 | 日志数据量大 | `RequestJournalLogSnapshotPayload` / `SyncJournalLogSnapshotPayload` / `UploadJournalLogSnapshotPayload` |
| 扫描结果 | 可疑解析仪扫描 | `SyncReaderScanResultPayload` |
| 完成奖励 | 100% 完成 | `NotifyTableCompletionRewardPayload` |

日志同步用 [`ArchaeologyJournalLogSyncSession`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/sync/ArchaeologyJournalLogSyncSession.java) 分片进行，避免单包过大。网络层细节见 [network.md](network.md)。

## 7. 客户端侧

客户端由 [`ArchaeologyJournalClientState`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/ui/support/ArchaeologyJournalClientState.java) 持有目录快照与本地状态，接收 S2C payload 更新，并驱动 UI。客户端侧细节见 [client-ui.md](client-ui.md)。

## 8. 扩展点

- **新增追踪来源**：实现对应的 TrackingService，在 loot roll 前写 `LootTrackingContextHolder`，roll 结束后调 `LootTrackingEvents.submit`。
- **订阅战利品发现事件**：`LootTrackingEvents.register(priority, listener)`。注意优先级不要与内建订阅者冲突（100/200/250/300/350）。
- **新增结算策略**：实现 `LootSettlementStrategy`，决定 `recordsItemsImmediately` 与 `settle` 行为。
- **目录收录范围**：修改 `ILootTableConfig.getArchaeologyPathPrefixes()` 的追踪前缀，或通过数据包新增战利品表（命中前缀即自动收录）。
- **分类规则**：在 `data/unsuspiciousblock/journal_categories/` 添加 JSON。

## 9. 相关文档

- [战利品表系统](loottable.md) - 目录解析、概率模拟、签名机制的底层
- [网络与同步](network.md) - payload 与同步会话细节
- [Mixin 总览](mixin.md) - `PlayerJournalStateMixin` / `NestedLootTableMixin` 等
- [客户端与 GUI](client-ui.md) - 考古笔记界面实现
- [配置与第三方联动](config-integrations.md) - 追踪前缀、日志上限等配置项
