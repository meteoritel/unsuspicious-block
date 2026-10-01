# 考古笔记系统

> `journal/` 包的架构：服务端如何构建战利品表目录、玩家进度如何持久化、战利品发现如何被追踪并解锁目录条目、以及状态如何同步到客户端。
> 本文件是考古笔记子系统的唯一权威。底层解析、签名与概率模拟见 [战利品表系统](loottable.md)，payload 清单见 [网络与同步](../foundation/network.md)，界面实现见 [客户端与 GUI](client-ui.md)。
> 日志的分片存储、保留策略与版本迁移见 [考古日志存储与迁移](../internals/journal-log-storage.md)；追踪上下文、内建订阅者与结算策略见 [战利品追踪核心数据流](../internals/journal-tracking.md)。

## 1. 代码地图

| 层 | 位置 |
|---|---|
| 生命周期入口 | `journal/JournalPlayerDataService.java`（玩家数据生命周期、恢复与迁移的统一入口） |
| 目录构建 | `journal/catalog/`：`ArchaeologyJournalServerCatalog`（服务端入口：构建、模拟调度、缓存、查询索引）、`ArchaeologyJournalCatalog`（单轮分析）、`LootTableAnalysisSession`（一代的分析结果）、`CatalogGeneration`（一代目录状态，原子发布）、`JournalCategoryLoader`（分类规则） |
| 玩家状态 | `journal/state/`：`ArchaeologyJournalState`、`ArchaeologyJournalStateHolder`（mixin 接口）、`ArchaeologyJournalLogState`、`ExcavationLogEntry`、`LootSourceType` |
| 迁移 | `journal/migration/`：`JournalDataMigrationManager`、`JournalDataVersion`、`JournalNbtMigrator`、`LegacyJournalLogAccess` |
| 追踪 | `journal/tracking/`：`LootTrackingContext`(+`Holder`)、`LootSession`、`ArchaeologyLootRuntimeTracker`、`RecentLootTableService`、三个 TrackingService、`MenuTrackingSnapshot(Service)`、`WorldContextResolver`、`JournalLogRecorder`、`ArchaeologyChallengeChecker`、`JournalCompletionRewardChecker`、`event/`（总线与内建订阅者）、`settlement/`（结算策略） |
| 日志存储 | `world/JournalLogStorage.java`（分片权威存储）、`world/JournalLogSavedData.java`（旧单文件迁移读取器）、`journal/sync/ArchaeologyJournalLogSyncSession(Holder)` |
| 网络 | `network/journal/`（各 Handler）；payload 见 [网络与同步](../foundation/network.md) |
| 数据资源 | `data/<namespace>/journal_categories/`（分类规则，任意命名空间可加） |
| 调试命令 | `command/JournalCommand.java`（`/usb journal ...`） |
| Mixin | `journal/PlayerJournalStateMixin` / `ServerPlayerJournalStateMixin` / `PlayerJournalLogStateMixin`、`interaction/NestedLootTableMixin`、`container/*`（容器追踪）、`interaction/FishingHookMixin`（钓鱼追踪）；全清单见 [Mixin](../foundation/mixin.md) |
| 平台差异 | 无核心分叉（追踪入口由各平台事件转交同一个 common 实现） |

## 2. 数据流

```
① 目录构建
SERVER_STARTED ──► ArchaeologyJournalServerCatalog.ensureLoaded(server)
  快照全表原文 ──► 建图 → 编译 → 投影 → 静态读模型（概率占位 "?"）
     └─ 整代状态经单个 volatile 引用原子发布
     └─ 命中缓存的表从 SavedData 恢复；未缓存表入队 worker 分 tick 模拟
          worker 主线程分片提交 ──► 当代 overlay 增长 ──► 广播目录哈希

② 战利品发现（核心）
loot roll 前 ──► LootTrackingContextHolder 写入上下文（根表 + 链路）
loot roll 后 ──► LootTrackingEvents.submit(session, counts, 结算策略)
     ├─ unlockSession        解锁表与物品
     ├─ recordFirstUnlock    记录首次发现元数据
     ├─ settlementStrategy   立即写日志 / 创建待定日志
     ├─ 考古挑战成就判定
     └─ 100% 完成奖励判定

③ 同步
玩家登录 ──► JournalPlayerDataService.onPlayerJoined（恢复分片 → 迁移 → 依序下发）
运行时 ──► revision + 脏表增量同步 / 哈希按需同步 / 日志分片会话
```

## 3. 关键类

| 类 | 职责 |
|---|---|
| `journal/JournalPlayerDataService` | 玩家数据生命周期统一入口：服务端启停与 tick、玩家登录/退出/重生 |
| `journal/catalog/ArchaeologyJournalServerCatalog` | 服务端目录入口：构建、模拟调度、SavedData 缓存、查询索引 |
| `journal/catalog/CatalogGeneration` | 一代目录状态：静态部分 + 模拟 overlay，由服务端**原子发布** |
| `journal/state/ArchaeologyJournalState` | 玩家进度核心（表/物品解锁、最近表、revision、脏表） |
| `journal/tracking/LootTrackingContext` | 不可变 record，描述当前追踪的根表与子表链路 |
| `journal/tracking/LootSession` | 一次 loot roll 的会话，`complete(...)` 产出 `Commit` |
| `journal/tracking/event/LootTrackingEvents` | 战利品发现事件总线（`submit` / `register`） |
| `journal/tracking/settlement/LootSettlementStrategy` | 结算策略接口（立即 / 延迟） |
| `world/JournalLogStorage` | 日志的服务端权威分片存储 |
| `journal/migration/JournalDataVersion` / `JournalNbtMigrator` | 两个版本轴的定义与逐版本迁移链 |
| `client/ui/support/ArchaeologyJournalClientState` | 客户端目录快照与本地状态 |

## 4. 目录构建（服务端）

服务端在 `SERVER_STARTED` 时由 [`ArchaeologyJournalServerCatalog.ensureLoaded`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/catalog/ArchaeologyJournalServerCatalog.java) 构建目录：快照全表原文 → 建图 / 编译 / 投影出**上下文无关**的静态读模型（概率先占位 `"?"`）→ 计算每表哈希 → 整代静态部分经单个 volatile 引用**原子发布**；未命中缓存的表入队模拟 worker 分 tick 填充 overlay，整批结束后广播目录哈希。解析、签名与模拟口径见 [战利品表机制细节](../internals/loottable-mechanics.md)。

构建流程的完整展开、关键设计（原子发布 / 渐进填充 / 哈希覆盖 / 代次校验 / 线程安全）、按需哈希同步与 `/usb journal` 调试命令见 [笔记目录构建细节](../internals/journal-catalog-build.md)。

### 4.1 分类结构

分类结构（`CatalogGeneration.structure()`）由 [`JournalCategoryLoader`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/catalog/JournalCategoryLoader.java) 扫描**所有命名空间**的 `data/<namespace>/journal_categories/` 加载，定义目录的分类、图标、排序与翻译键。分类规则见 `docs/journal-categories.md`（`docs/` 下同级文件）。

## 5. 玩家进度状态

### 5.1 状态结构

[`ArchaeologyJournalState`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/state/ArchaeologyJournalState.java) 是玩家进度的核心，三层结构：

```
ArchaeologyJournalState
├─ tables: LinkedHashMap<TableId, TableProgress>
│   └─ TableProgress
│       ├─ unlocked: boolean                  表是否已解锁
│       ├─ completionRewardClaimed: boolean   100% 完成奖励是否已发放
│       └─ items: LinkedHashMap<sigKey, ItemProgress>
│           └─ ItemProgress{ unlocked, count }  物品解锁与获取数量
├─ recentLootTables: LinkedHashMap<TableId, Order>  最近遇到的 128 个唯一表
├─ recentSequence: long                      玩家内单调遇到顺序
├─ revision: long                            增量同步版本号
└─ dirtyTables: LinkedHashSet<TableId>       脏表追踪
```

物品用 `LootResultSignature.toStoredKey()` 作为 key（见 [战利品表系统](loottable.md) 的「签名机制」），而非物品 ID，以区分同一物品的不同组件结果；随实例随机化或随玩家进度变化的**实例态组件**不参与签名，含它们的物品按物品级收录。父表完成度包含其全部后代表物品，并按签名去重。

### 5.2 持久化

状态通过 mixin 附加到 `ServerPlayer`：

- `ArchaeologyJournalStateHolder` 是 mixin 接口，`ServerPlayer` 实例通过 `unsuspiciousblock$getArchaeologyJournalState()` 暴露状态。
- `PlayerJournalStateMixin` / `ServerPlayerJournalStateMixin` 实现状态读写，序列化到玩家 NBT。
- `JournalDataVersion` / `JournalNbtMigrator` 统一处理旧存档格式迁移，**状态类只解析当前字段**。
- `JournalPlayerDataService` 统一接收玩家登录、退出与重生事件。解锁进度仍由原版玩家 NBT 保存流程负责，不迁移其物理位置。

最近列表由 `RecentLootTableService` 写入：同一表再次遇到时刷新为最新记录，超过 128 个唯一表时移除最旧项。该路径**不创建 `LootSession`**，只在玩家打开随机容器、实际刷拭或用考古铲取出可疑方块内容、钓鱼收杆、打破战利品陶罐时更新有限 Map；`entities/` 与 `blocks/` 路径不会记录。

### 5.3 增量同步

状态同步采用 **revision + 脏表** 增量机制：

- `markDirty(tableId)`：变更时仅标记脏表，**不立即递增 revision**。
- `drainDirtyTables()`：发送增量包时收集脏表，若非空则 revision +1。
- 这样一次业务操作内多次 `markDirty` 不会让 revision 跳跃，客户端的间隙检测（`incomingRevision > lastNotifiedRevision + 1`）才有意义。
- `mergeFromIncremental`：客户端采用**服务端权威语义**，增量数据直接覆盖本地条目，不做二次合并。
- 全量重置操作（`clear` / `removeTable`）也 +1 revision，对应全量同步。

## 6. 日志与追踪

日志采用**按玩家 UUID、战利品表分片压缩**的权威存储（`world/JournalLogStorage`），带延迟写盘与双版本轴迁移；战利品发现经 `LootTrackingContext` 上下文 + `LootTrackingEvents` 事件总线，由 5 个内建订阅者按优先级解锁、记录、结算与判定成就。两者的完整实现细则见 [考古日志存储与迁移](../internals/journal-log-storage.md) 与 [战利品追踪核心数据流](../internals/journal-tracking.md)。

## 7. 加入时同步序列

玩家加入入口是 [`JournalPlayerDataService.onPlayerJoined`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/JournalPlayerDataService.java)，按序执行：

```
onPlayerJoined(player)
  ├─ restoreLogState                         从 v2 分片恢复日志并回放排队变更
  ├─ JournalDataMigrationManager.migratePlayerData
  │    ├─ 旧玩家日志提交到分片
  │    └─ 旧进度签名对齐当前目录
  └─ ArchaeologyJournalNetwork.syncPreparedPlayerOnJoin
       ├─ JournalLogHandler.syncLogSnapshot            下发日志快照
       ├─ JournalCatalogHandler.syncCatalogHash         下发目录哈希
       ├─ LootTableManagementHandler.sync               下发管理索引/名称
       ├─ JournalStateHandler.syncStateFull             下发解锁进度
       ├─ JournalCompletionRewardChecker.checkAndRewardAll
       └─ ArchaeologyChallengeChecker.checkAndGrantAll
```

补发奖励/成就是在全量状态同步之后，确保客户端 catalog 已就绪可解析表名，且避免事件漏触发时遗漏授予。

**运行时同步模式**

| 同步类型 | 触发 | 机制 |
|---|---|---|
| 进度增量 | `drainDirtyTables` | revision + 脏表（客户端检测间隙后请求全量） |
| 进度全量 | 加入 / 数据包重载 | 一次性全量状态 |
| 目录按需 | 哈希不一致 | 客户端请求全量目录 |
| 日志分片 | 加入 / 客户端重同步请求 | 会话 + 序号；按表压缩后切分片 |
| 日志删除 | 玩家确认删除 | 服务端校验后回权威状态 + 结果回执 |
| 扫描结果 | 可疑解析仪扫描 | 一次性下发 |
| 完成奖励 | 100% 完成 | 一次性通知 |

本子系统涉及的 payload 与平台注册方式见 [网络与同步](../foundation/network.md) 的 payload 清单；日志分片最多 128 KiB，避免单个 NBT payload 触发原版 2 MiB 解码上限（规模上限见同篇的「同步模式」）。

## 8. 客户端侧

客户端由 `ArchaeologyJournalClientState` 持有目录快照与本地状态，接收 S2C payload 更新，并驱动 UI。客户端侧细节见 [客户端与 GUI](client-ui.md)。

## 9. 扩展点：新增追踪来源 / 订阅者 / 结算策略

- **新增追踪来源**：实现对应的 TrackingService，在 loot roll 前写 `LootTrackingContextHolder`，roll 结束后调 `LootTrackingEvents.submit`。
- **订阅战利品发现事件**：`LootTrackingEvents.register(priority, listener)`。**优先级不要与内建订阅者冲突**（100/200/250/300/350）；若需在解锁前/后执行，选 100 之前或 350 之后。
- **新增结算策略**：实现 `LootSettlementStrategy`，决定 `recordsItemsImmediately` 与 `settle` 行为。
- **调整目录收录范围**：改 `ILootTableConfig.getArchaeologyPathPrefixes()` 的追踪前缀，或通过数据包新增战利品表（命中前缀即自动收录）。
- **新增分类规则**：在任意命名空间的 `data/<namespace>/journal_categories/` 加 JSON，不需要改代码。
- **新增日志字段/版本**：迁移步骤只加在 `JournalNbtMigrator`（双版本轴与迁移约定见 [考古日志存储与迁移](../internals/journal-log-storage.md) 的「版本迁移约定」）。

## 10. 约束与陷阱

- **目录必须原子发布**：任何新加的"整代"数据都要进 `CatalogGeneration` 的静态部分，不要在多个字段上分步赋值。
- **解析态视图与模拟视图是两个口径**：`getRawCatalog()`（启动即完整）用于解锁判定，`getCatalog()`（含 overlay）用于展示。不要混用——`unlock item` 因此会在 overlay 未覆盖时拒绝执行。
- **状态类只解析当前字段**：旧字段回退分支一律不写进 `readFrom` / `fromTag`，迁移只加在 `JournalNbtMigrator`。
- **迁移必须连续且拒绝降级**：`vN -> vN+1` 逐级执行，遇到更高版本拒绝读取；非幂等迁移步骤必须用 `ABORT_IF_INTERRUPTED`。
- **日志写盘是延迟的**：崩溃可能丢失最近约 10 秒的日志变更，这是明确取舍，不要为"零丢失"改成每写必刷。
- **`RecentLootTableService` 不创建会话**，`entities/` 与 `blocks/` 路径不记录最近表。
- **显式删除不回退任何累计量与解锁状态**（只删条目）。
- **订阅者优先级顺序有语义**，插入新订阅者前先确认与 100/200/250/300/350 的相对位置。
- **实体容器的追踪状态存实体 NBT、销毁时结算**：不要在区块卸载时提前清理。

## 11. 相关文档

- [考古日志存储与迁移](../internals/journal-log-storage.md) —— 日志分片布局、保留策略、版本迁移约定
- [战利品追踪核心数据流](../internals/journal-tracking.md) —— 追踪上下文、三个追踪服务、事件总线、结算策略
- [战利品表系统](loottable.md) —— 目录解析、概率模拟、签名机制的底层
- [网络与同步](../foundation/network.md) —— payload 清单与同步会话
- [Mixin](../foundation/mixin.md) —— `PlayerJournalStateMixin` / `NestedLootTableMixin` 等
- [客户端与 GUI](client-ui.md) —— 考古笔记界面实现
- [配置与第三方联动](../foundation/config-and-integrations.md) —— 追踪前缀、日志上限等配置项
- [实体与 AI](entities-world.md) —— `JournalLogStorage` 等 `world/` 持久化类的位置索引
- [进度（成就）系统](advancement.md) —— 收集类进度的判定依赖本篇状态
- `docs/journal-categories.md` —— 目录分类规则（`docs/` 下同级文件）
