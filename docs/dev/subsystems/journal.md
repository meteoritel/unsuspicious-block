# 考古笔记系统

> `journal/` 包的架构：服务端如何构建战利品表目录、玩家进度如何持久化、战利品发现如何被追踪并解锁目录条目、以及状态如何同步到客户端。
> 本文件是考古笔记子系统的唯一权威。底层解析、签名与概率模拟见 [战利品表系统](loottable.md)，payload 清单见 [网络与同步](../foundation/network.md)，界面实现见 [客户端与 GUI](client-ui.md)。

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

- **原子发布**：整代静态部分（快照 / 引用图 / 编译产物 / 静态投影 / 每表哈希 / 分类结构）在局部对象上构建完成后，经单个 volatile 引用整体替换。读取方只会看到上一代的完整状态或新一代的完整静态部分；构建抛异常时进入 `CatalogGeneration.empty()`，而不留半成品。
- **非阻塞渐进填充**：`ensureLoaded` 立即返回，模拟结果作为当代 overlay 渐进增长。客户端打开笔记时，未模拟完的表概率显示为 "?"；不受模拟进度影响的**解析态视图**由 `getRawCatalog()` 提供，启动即完整。
- **模拟结果持久化**：`LootProbabilityData`（SavedData，附加在 overworld）缓存每张表的概率结果，避免每次重启重新模拟；哈希变化时才重新模拟。
- **哈希覆盖间接依赖**：表哈希的输入是该表子树内每张表的完整资源栈摘要、编译产物摘要与被引用附魔的定义摘要（附魔 id 与 `max_level`），覆盖 item tag 成员变化、附魔等级上限调整等间接依赖；已知残余不列入其中（见 [战利品表系统](loottable.md) 的「模拟结果缓存」）。
- **重载与重建**：数据包重载由 `DataPackReloadListener` 只置脏标记，真正的重建在服务端 tick 路径上消费（`ServerLootTableConfigManager.tick`）——在重载回调里同步跑全量构建会拖住重载本身。见 [配置与第三方联动](../foundation/config-and-integrations.md)。
- **代次校验**：模拟提交与队列排空广播都携带构建时的 `generation`，旧代结果被直接丢弃。
- **线程安全**：静态部分不可变并整体发布；只有模拟 overlay 是 `ConcurrentHashMap`，读路径无锁，写路径仅在主线程。
- **worker 回退**：模拟工作线程未启动时回退到主线程同步模拟，避免功能缺失。

### 4.1 分类结构

分类结构（`CatalogGeneration.structure()`）由 [`JournalCategoryLoader`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/catalog/JournalCategoryLoader.java) 扫描**所有命名空间**的 `data/<namespace>/journal_categories/` 加载，定义目录的分类、图标、排序与翻译键。分类规则见 `docs/journal-categories.md`（`docs/` 下同级文件）。

### 4.2 目录哈希与按需同步

服务端计算整个目录的 SHA-256 哈希（`computeCatalogHash()` → `CatalogGeneration.catalogHash()`），模拟完成后广播给所有在线玩家。哈希输入取自网络形态 `CatalogTableDto`——与实际上线的内容一一对应（场景假设条件树在表级只计一次，物品与子表侧只计 key 与概率），因此"要发的内容变了"必然改变哈希。结果在当代内缓存，任一表提交新模拟结果即失效。客户端比对本地哈希，不一致时主动请求全量目录。这避免每次登录都全量下发。

### 4.3 调试命令与目录稳定性

- `/usb journal reload` 清空 `LootProbabilityData`、worker 队列和服务端目录后重新解析、模拟；**不清除玩家的笔记进度与日志**。进度监听器在任务计数完成时先解除，因此控制台执行或执行玩家中途离线也不会留下旧监听器。
- `/usb journal unlock table [table_id]` 使用启动即完整的解析态视图（`getRawCatalog()`），不依赖模拟进度。
- `/usb journal unlock item [table_id]` 需要动态物品也已进入稳定目录，因此 worker 忙碌、或模拟 overlay（`getCatalog()`）尚未覆盖解析态视图的全部表时会**拒绝执行**，避免把半成品目录写入玩家状态。
- 父表 Intro、`unlock item` 和 100% 完成奖励统一使用"当前表及全部后代表，按 `LootResultSignature` 去重"的物品闭包（`CatalogQueryIndex.subtreeItems`）。共享子表和循环引用只遍历一次。

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

## 6. 日志持久化与迁移

### 6.1 分片存储

[`JournalLogStorage`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/world/JournalLogStorage.java) 是日志的服务端权威存储。它不把所有玩家日志写进同一个 `SavedData` NBT，而是按玩家 UUID、战利品表拆分压缩文件：

```text
<world>/data/unsuspiciousblock/journal_logs/
├── storage.dat                              已提交版本与待恢复迁移步骤
├── migration/
│   └── unsuspiciousblock_journal_logs-v1.dat.bak
└── players/<player-uuid>/
    ├── index.dat                            该玩家的权威战利品表清单
    └── tables/<sha256(table-id)>.dat         单张战利品表的日志历史
```

- 普通新增和备注更新只标记对应表为 dirty；服务端每 200 tick（约 10 秒）最多写入 4 个分片，避免集中压缩与 IO 卡顿。删除操作立即持久化，停服前刷新全部 dirty 分片。
- **延迟写盘意味着进程崩溃可能丢失尚未刷新的日志变更**；dirty 分片不超过一轮容量时单次变更通常最多等待约 10 秒，积压超过 4 个分片时队尾等待会进一步延长。这是性能与数据持久化时效之间的明确取舍。
- 定时刷盘按玩家轮询选择 shard，同一批中每名玩家只重写一次 `index.dat`；写入失败的 shard 放回队尾，避免持续故障阻塞其他玩家。
- 玩家退出时同步刷新其全部 dirty shard，成功后从内存卸载状态；写入失败则保留缓存并由后续 tick 重试。玩家在重试完成前重新连接会取消待卸载标记并复用缓存。
- 单表文件记录原始 `table_id`，文件名用其 SHA-256；加载时同时校验存储版本、玩家 UUID 和表 ID。写入使用同目录临时文件再原子替换。
- `index.dat` 是正常加载时的权威清单；索引损坏或不存在时才扫描 `tables/` 重建索引，因此已删除但遗留的孤立分片不会被正常恢复。
- **首次启动 v2 时**，旧 `<world>/data/unsuspiciousblock_journal_logs.dat` 会按玩家和表拆分。迁移开始前先在 `storage.dat` 写入确切的 pending 起止版本；所有目标分片成功写入后再提交新版本，最后才把旧文件移到 `migration/` 备份。
- 仍残留在玩家 NBT 的更早期 `unsuspiciousblock_archaeology_journal_log` 会在玩家登录时按 `entryId` 合并到 v2，**服务端已有的同 ID 条目优先**。旧 tag 通过 `peek` 读取；所有目标表与 index 同步落盘成功后才 `ack`，失败时继续写回玩家 NBT 等待重试。

### 6.2 保留策略

- 每张表持久化玩家设置的 `retention_limit` 与 `long lifetime_entry_count`。前者受服务器 `maxLogEntriesPerTable` 全局兜底限制；后者只在**新 `entryId` 首次写入时**累计，普通 GUI 删除不会回退，`/usb journal clear [table_id]` 才会连同表历史一起清除。
- 新增日志时，如果带备注条目数已达当前表有效上限，则拒绝新增并警告玩家；否则自动删除最旧的无备注条目直到回到上限。一次性"仅保留最近 N 条"同样保护所有带备注条目。玩家后来降低上限时允许带备注条目数暂时超过上限，但在清除备注或删除日志前不能新增。
- 玩家可在详情页删除单条日志，也可进入批量选择模式后选择条目或整个分组；批量执行、清空当前表和清空全部表都使用 `ConfirmScreen` 二次确认。C2S 请求**只能操作连接玩家自身的数据**。显式删除会无视备注保护，但只删除日志条目，不回退累计数、保留策略、目录解锁、物品计数、完成奖励或成就。

### 6.3 版本迁移约定

考古日志有两个**互不混用**的版本轴，统一定义在 `JournalDataVersion`：

- **NBT schema 版本**：描述字段与嵌套结构，当前为 v2；缺少 `data_version` 视为 v0。
- **存储布局版本**：描述单文件或分片目录结构，当前为 v2。

所有旧 NBT 字段名、默认值和逐级迁移步骤**只能添加到 `JournalNbtMigrator`**。迁移必须严格按 `vN -> vN+1` 连续执行；遇到高于当前版本的数据时**拒绝降级读取**。新增版本时先提高 `JournalDataVersion.CURRENT_NBT_VERSION`，再补齐每种数据类型对应的迁移步骤。状态类的 `readFrom` / `fromTag` 不得重新加入旧字段回退分支。

存储布局 manifest 使用显式事务语义：`committed_storage_version` 始终是最后完整提交的版本，`pending_from_version` / `pending_to_version` 仅表示已开始但尚未提交的确切一步。启动时必须验证 pending 起点等于 committed、目标是下一连续版本且不高于当前版本；语义冲突会**阻止启动**，不得再通过版本号减一猜测迁移阶段。物理损坏、无法解压的 manifest 才允许按已知源数据重建。

每个存储布局迁移步骤都必须登记来源版本、目标版本、恢复策略、迁移动作和提交后清理动作。只有源数据在提交前保持不变、目标写入可重复覆盖的步骤才能用 `RESTART_FROM_SOURCE`；非幂等步骤必须用 `ABORT_IF_INTERRUPTED`，中断后停止自动迁移并要求人工恢复。提交后的源数据清理必须幂等，以便服务器在"版本已提交但清理未完成"时安全续做。兼容字段 `storage_version` / `migration_complete` 仍会写入，但只向旧版表示最后已提交版本，不参与新版恢复决策。

`JournalPlayerDataService` 是生命周期统一入口：服务端启停和 tick、玩家登录/退出/重生都由它协调。登录时先恢复日志分片，再由 `JournalDataMigrationManager` 依次提交旧玩家日志并迁移目录签名，最后统一下发两类玩家数据。**源数据只有在目标数据完整持久化后才允许清理**，世界级旧单文件则保留 `.bak` 备份。

## 7. 战利品追踪（核心数据流）

### 7.1 追踪上下文

[`LootTrackingContext`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/tracking/LootTrackingContext.java) 是不可变 record，描述当前追踪的根表与子表链路：

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
- `NestedLootTableMixin` 识别上下文，自动捕获嵌套子表物品并派生上下文。

### 7.2 三个追踪服务

| 服务 | 触发场景 | 说明 |
|---|---|---|
| `DirectLootTrackingService` | 钓鱼、附魔战利品等直接进背包 | 物品立即归玩家所有 |
| `ContainerTrackingService` | 开箱（箱子、箱子矿车、埋藏宝藏等） | 通过 `MenuTrackingSnapshot` 比对开箱前后差异 |
| `DecoratedPotTrackingService` | 陶罐 | 类似容器，但走陶罐专属路径 |

容器追踪的关键是 `MenuTrackingSnapshot`：记录玩家打开菜单时的物品快照，关闭时比对差异，确定实际取走了哪些物品。方块容器从 `RandomizableContainer#unpackLootTable` 接入；箱子矿车等实体容器从 `ContainerEntity#unpackChestVehicleLootTable` 接入，两者共用 `LOOT_CONTAINER` 分类和 `ContainerTrackingService`。**实体容器的追踪状态保存在实体 NBT 中，真正销毁时结算，区块卸载不会提前清除。**

Lootr 奖励箱矿车不走原版实体容器填充方法，而是通过 `DefaultLootFiller` 创建每玩家 `LootrInventory`；现有 Lootr 兼容 Mixin 在该通用 filler 上接入，因此奖励箱矿车与 Lootr 方块容器使用同一条 `LOOT_CONTAINER` 追踪链，**不需要矿车专用分类或重复钩子**。

### 7.3 事件总线

追踪入口在 loot roll 结束后通过 `LootTrackingEvents.submit` 提交结果：

```
LootTrackingEvents.submit(session, itemCounts, settlementStrategy)
  ├─ session.complete(itemCounts) -> LootSession.Commit
  │     Commit 含 rootContext + discoveredLoot + tableStack
  ├─ resolveTrackingState(player, rootTableId)
  │     仅当 rootTableId 被 catalog 收录时才继续（isTrackedTable）
  └─ dispatch(LootDiscoveredEvent)  按 priority 顺序同步通知所有订阅者
```

### 7.4 内建订阅者

`LootTrackingBootstrap.registerListeners()` 注册 5 个订阅者，按优先级顺序执行：

| 优先级 | 订阅者 | 职责 |
|---|---|---|
| 100 | `onUnlock` | `ArchaeologyLootRuntimeTracker.unlockSession` 解锁表与物品 |
| 200 | `onRecordFirstUnlock` | `JournalLogRecorder.recordFirstUnlock` 记录首次发现元数据 |
| 250 | `onSettle` | `settlementStrategy.settle` 结算（立即写日志或创建待定日志） |
| 300 | `onCheckChallenge` | `ArchaeologyChallengeChecker.checkAndGrant` 考古挑战成就 |
| 350 | `onCheckCompletionReward` | `JournalCompletionRewardChecker.checkAndReward` 100% 完成奖励 |

> **顺序约束**：解锁（100）必须先于成就与完成奖励检查（300/350），否则检查时进度未更新。优先级数字越小越先执行。

### 7.5 结算策略

`LootSettlementStrategies` 提供两种策略：

| 策略 | `recordsItemsImmediately` | 行为 | 适用场景 |
|---|---|---|---|
| `immediate()` | true | 生成时立即记录获取数量与最终日志 | 钓鱼等直接进背包 |
| `deferred(consumer)` | false | 生成时只创建待定日志，实际取出后再更新 | 容器/可疑方块暂存（物品可能被丢弃） |

延迟结算解决的问题是：玩家打开容器后可能不取走物品，或可疑方块刷出的物品可能被丢弃。待定日志暂存在容器/方块实体上，物品实际进入玩家所有权后才转为正式日志。

## 8. 加入时同步序列

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

## 9. 客户端侧

客户端由 `ArchaeologyJournalClientState` 持有目录快照与本地状态，接收 S2C payload 更新，并驱动 UI。客户端侧细节见 [客户端与 GUI](client-ui.md)。

## 10. 扩展点：新增追踪来源 / 订阅者 / 结算策略

- **新增追踪来源**：实现对应的 TrackingService，在 loot roll 前写 `LootTrackingContextHolder`，roll 结束后调 `LootTrackingEvents.submit`。
- **订阅战利品发现事件**：`LootTrackingEvents.register(priority, listener)`。**优先级不要与内建订阅者冲突**（100/200/250/300/350）；若需在解锁前/后执行，选 100 之前或 350 之后。
- **新增结算策略**：实现 `LootSettlementStrategy`，决定 `recordsItemsImmediately` 与 `settle` 行为。
- **调整目录收录范围**：改 `ILootTableConfig.getArchaeologyPathPrefixes()` 的追踪前缀，或通过数据包新增战利品表（命中前缀即自动收录）。
- **新增分类规则**：在任意命名空间的 `data/<namespace>/journal_categories/` 加 JSON，不需要改代码。
- **新增日志字段/版本**：见「日志持久化与迁移」的「版本迁移约定」——迁移步骤只加在 `JournalNbtMigrator`。

## 11. 约束与陷阱

- **目录必须原子发布**：任何新加的"整代"数据都要进 `CatalogGeneration` 的静态部分，不要在多个字段上分步赋值。
- **解析态视图与模拟视图是两个口径**：`getRawCatalog()`（启动即完整）用于解锁判定，`getCatalog()`（含 overlay）用于展示。不要混用——`unlock item` 因此会在 overlay 未覆盖时拒绝执行。
- **状态类只解析当前字段**：旧字段回退分支一律不写进 `readFrom` / `fromTag`，迁移只加在 `JournalNbtMigrator`。
- **迁移必须连续且拒绝降级**：`vN -> vN+1` 逐级执行，遇到更高版本拒绝读取；非幂等迁移步骤必须用 `ABORT_IF_INTERRUPTED`。
- **日志写盘是延迟的**：崩溃可能丢失最近约 10 秒的日志变更，这是明确取舍，不要为"零丢失"改成每写必刷。
- **`RecentLootTableService` 不创建会话**，`entities/` 与 `blocks/` 路径不记录最近表。
- **显式删除不回退任何累计量与解锁状态**（只删条目）。
- **订阅者优先级顺序有语义**，插入新订阅者前先确认与 100/200/250/300/350 的相对位置。
- **实体容器的追踪状态存实体 NBT、销毁时结算**：不要在区块卸载时提前清理。

## 12. 相关文档

- [战利品表系统](loottable.md) —— 目录解析、概率模拟、签名机制的底层
- [网络与同步](../foundation/network.md) —— payload 清单与同步会话
- [Mixin](../foundation/mixin.md) —— `PlayerJournalStateMixin` / `NestedLootTableMixin` 等
- [客户端与 GUI](client-ui.md) —— 考古笔记界面实现
- [配置与第三方联动](../foundation/config-and-integrations.md) —— 追踪前缀、日志上限等配置项
- [实体与 AI](entities-world.md) —— `JournalLogStorage` 等 `world/` 持久化类的位置索引
- [进度（成就）系统](advancement.md) —— 收集类进度的判定依赖本篇状态
- `docs/journal-categories.md` —— 目录分类规则（`docs/` 下同级文件）
