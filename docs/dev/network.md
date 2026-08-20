# 网络与同步

本文档描述 `network/` 包的架构：自定义网络包（payload）的清单式注册、C2S/S2C 分类、客户端/服务端分离技巧、平台注册与同步策略。

## 1. 职责概述

模组用 1.21 的 `CustomPacketPayload` 机制实现网络通信，共 26 个自定义 payload（12 个 C2S + 14 个 S2C），覆盖：

- **考古笔记同步**：目录、进度状态（增量/全量）、日志（更新/快照）、完成奖励通知。
- **目录按需同步**：哈希比对，不一致时客户端主动请求全量目录。
- **解析仪交互**：扫描等级切换、扫描结果高亮同步。
- **猫族关系**：羁绊/命数同步、威慑/轻步开关切换。
- **附魔揭示**：完整候选列表下发。

## 2. 包结构

```
network/
├── ModPayloads                payload 注册清单（核心）
├── ArchaeologyJournalNetwork  考古笔记同步调度入口
├── journal/                   考古笔记相关处理器
│   ├── JournalCatalogHandler    目录请求处理
│   ├── JournalLogHandler        日志快照/备注/删除处理
│   ├── JournalLogSnapshotCodec  日志快照编解码
│   ├── JournalStateHandler      进度状态同步（增量/全量）
│   ├── LootTableManagementHandler  战利品表追踪管理页处理
│   └── ReaderScanLevelHandler   扫描等级更新处理
├── payload/
│   ├── c2s/                   12 个客户端->服务端 payload
│   └── s2c/                   14 个服务端->客户端 payload
└── (cat/CatNetworkHandler 在 cat/ 包)
```

## 3. payload 清单模式

[`ModPayloads`](../../common/src/main/java/com/meteorite/unsuspiciousblock/network/ModPayloads.java) 沿用项目的"清单 + 遍历"模式（见 [注册架构](registration.md)），统一管理所有 payload 的注册信息：

```java
// C2S：类型 + 编解码 + 服务端处理函数
record C2S<T>(Type<T> type, StreamCodec codec, BiConsumer<ServerPlayer, T> handler)

// S2C 类型描述：类型 + 编解码（供服务端注册编解码器，Fabric 需要）
record S2CSpec<T>(Type<T> type, StreamCodec codec)

// 客户端 S2C：类型 + 编解码 + 客户端处理函数（嵌套在 Client 类中）
record S2C<T>(Type<T> type, StreamCodec codec, Consumer<T> handler)
```

三个清单：`C2S_PAYLOADS`、`S2C_SPECS`（服务端注册编解码）和 `Client.S2C_PAYLOADS`（客户端注册接收器）。平台层只需遍历清单注册，不手写重复代码；具体数量以清单源码为准。

## 4. 客户端/服务端分离

**关键设计**：S2C 的 handler 引用客户端状态类（如 `ArchaeologyJournalClientState`），若直接放在 `ModPayloads` 顶层，服务端 classpath 会引入客户端类，违反"服务端严禁引用客户端类"准则。

解决方案是**嵌套类延迟加载**：

```java
public final class ModPayloads {
    public static final List<C2S<?>> C2S_PAYLOADS = ...;        // 服务端安全
    public static final List<S2CSpec<?>> S2C_SPECS = ...;        // 服务端安全（无 handler）

    public static final class Client {                           // 嵌套类
        public static final List<S2C<?>> S2C_PAYLOADS = ...;     // 引用客户端类
    }
}
```

JVM 按需加载嵌套类，服务端不加载 `Client` 类，从而避免服务端 classpath 引入客户端状态类。平台客户端入口遍历 `Client.S2C_PAYLOADS` 注册 S2C 接收器。

## 5. C2S payload

| Payload | 处理器 | 用途 |
|---|---|---|
| `UpdateReaderScanLevelPayload` | `ReaderScanLevelHandler::handleUpdateReaderScanLevel` | 更新解析仪扫描等级 |
| `RequestCatalogPayload` | `JournalCatalogHandler::handleRequestCatalog` | 请求全量目录 |
| `RequestLootTableManagementPayload` | `LootTableManagementHandler::handleRequest` | 请求服务端权威管理索引 |
| `UpdateTrackedLootTablePayload` | `LootTableManagementHandler::handleUpdate` | 修改单张表的追踪状态 |
| `UpdateLootTableTranslationsPayload` | `LootTableManagementHandler::handleTranslationUpdate` | 批量提交手工草稿或 JSON 导入名称 |
| `RequestJournalStateFullPayload` | `JournalStateHandler::handleRequestFull` | 请求全量状态重同步 |
| `RequestJournalLogSnapshotPayload` | `JournalLogHandler::handleRequestSnapshot` | 请求日志快照 |
| `UpdateJournalLogNotePayload` | `JournalLogHandler::handleUpdateNote` | 更新日志备注 |
| `DeleteJournalLogPayload` | `JournalLogHandler::handleDeleteLogs` | 删除单条、批量选择、当前表或全部日志 |
| `UpdateJournalLogRetentionPayload` | `JournalLogHandler::handleUpdateRetention` | 设置当前表自动上限或仅保留最近 N 条 |
| `CatDeterrenceTogglePayload` | `CatNetworkHandler::handleDeterrenceToggle` | 切换威慑开关 |
| `CatLightStepTogglePayload` | `CatNetworkHandler::handleLightStepToggle` | 切换轻步开关 |

## 6. S2C payload

| Payload | 客户端处理 | 用途 |
|---|---|---|
| `SyncArchaeologyCatalogPayload` | `ArchaeologyJournalClientState::receiveCatalog` | 全量目录 |
| `SyncCatalogHashPayload` | `receiveCatalogHash` | 目录哈希（按需同步比对） |
| `SyncLootTableManagementPayload` | `LootTableManagementClientState::receive` | 注册表索引、玩家最近遇到列表、编辑权限和名称快照 |
| `SyncJournalStatePayload` | `receiveState` | 进度状态（全量） |
| `SyncJournalStateIncrementalPayload` | `receiveStateIncremental` | 进度状态（增量） |
| `SyncJournalLogPayload` | `receiveLogUpdate` | 日志更新 |
| `SyncJournalLogSnapshotStartPayload` | `beginLogSnapshot` | 开始日志全量快照 |
| `SyncJournalLogTableChunkPayload` | `receiveLogSnapshotChunk` | 单表压缩数据分片 |
| `SyncJournalLogSnapshotEndPayload` | `completeLogSnapshot` | 结束并提交完整快照 |
| `JournalLogDeleteResultPayload` | `receiveLogDeleteResult` | 日志删除结果回执 |
| `SyncCatFavorPayload` | `HandOfCatClientState::receive` | 羁绊/命数/关系状态 |
| `SyncReaderScanResultPayload` | `ReaderScanHighlightState::receive` | 扫描高亮方块 |
| `SyncEnchantmentRevealListPayload` | `EnchantmentRevealClientState::receive` | 附魔揭示候选 |
| `NotifyTableCompletionRewardPayload` | `receiveTableCompletionReward` | 100% 完成奖励通知 |

## 7. 平台注册

两端在各自入口遍历清单注册：

**Fabric**（`UnsuspiciousBlockFabric`）：
```java
for (C2S<?> c2s : ModPayloads.C2S_PAYLOADS) registerC2S(c2s);      // playC2S + ServerPlayNetworking
for (S2CSpec<?> spec : ModPayloads.S2C_SPECS) registerS2CSpec(spec); // playS2C 编解码器
```
客户端（`UnsuspiciousBlockFabricClient`）：
```java
for (Client.S2C<?> s2c : ModPayloads.Client.S2C_PAYLOADS) registerS2C(s2c); // ClientPlayNetworking
```

**NeoForge**（`UnsuspiciousBlockNeoForge`）：
```java
// RegisterPayloadHandlersEvent 中
registrar.versioned("4.0");
for (C2S<?> c2s : ModPayloads.C2S_PAYLOADS) registerC2S(registrar, c2s);  // playToServer
```
客户端（`UnsuspiciousBlockNeoForgeClient`）：
```java
for (Client.S2C<?> s2c : ModPayloads.Client.S2C_PAYLOADS) registerS2C(registrar, s2c); // playToClient
```

**C2S 主线程调度**：Fabric 端 C2S handler 通过 `context.server().execute(...)` 调度到主线程；NeoForge 端 payload handler 默认在主线程执行。这保证状态修改的线程安全。

**版本化**：NeoForge 端用 `registrar.versioned("4.0")` 声明 payload 协议版本；本版本拆分追踪状态与名称更新，并新增批量名称 payload。

## 8. 同步策略

### 8.1 进度状态同步（增量 + 全量）

[`JournalStateHandler`](../../common/src/main/java/com/meteorite/unsuspiciousblock/network/journal/JournalStateHandler.java) 实现增量与全量两种同步：

- **增量**（`syncState`）：`drainDirtyTables` 收集脏表 -> `writeDirtyTablesToTag` -> `SyncJournalStateIncrementalPayload`。一次业务操作内多次 `markDirty` 只产生一次 revision +1。
- **全量**（`syncStateFull`）：`drainDirtyTables` 清空脏标记（避免后续增量重复发送）-> `SyncJournalStatePayload`。
- **客户端请求重同步**：`handleRequestFull` 处理客户端的 `RequestJournalStateFullPayload`（revision 间隙恢复）。

客户端的增量同步有**间隙检测**：`incomingRevision > lastNotifiedRevision + 1` 时说明丢包，主动请求全量重同步（带 2s 限流）。详见 [客户端与 GUI](client-ui.md) 第 3.1 节。

### 8.2 目录按需同步

服务端计算目录 SHA-256 哈希（`computeCatalogHash`），模拟完成后广播。客户端比对本地哈希，不一致时发送 `RequestCatalogPayload` 请求全量目录。避免每次登录全量下发。

### 8.3 日志分片同步

日志状态以服务端 [`JournalLogStorage`](../../common/src/main/java/com/meteorite/unsuspiciousblock/world/JournalLogStorage.java) 为权威，用 [`ArchaeologyJournalLogSyncSession`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/sync/ArchaeologyJournalLogSyncSession.java) 维护会话 ID 与增量序号：

- 加入时：`JournalPlayerDataService.onPlayerJoined` 先从按 UUID、战利品表拆分的 v2 存储恢复并迁移玩家数据，再由 `JournalLogHandler.syncLogSnapshot` 下发日志。
- 全量快照：先发 `SyncJournalLogSnapshotStartPayload`，每张表独立压缩并以 `SyncJournalLogTableChunkPayload` 切成最多 128 KiB 的 byte 分片，最后发 `SyncJournalLogSnapshotEndPayload`。客户端校验表数与所有分片后一次性替换状态，接收期间继续保留旧状态。
- 规模限制：单表压缩后最多 16 MiB、解压 NBT 最多 64 MiB、一次快照最多 65,536 张表；网络解码不再调用 `readNbt` 读取整份日志，因此不受原 2 MiB 单 NBT payload 上限影响。
- 运行时：`SyncJournalLogPayload` 发送首次解锁、条目更新、删除单条，以及批量操作后的权威单表替换；清空全部表时下发新快照。
- 恢复：会话或分片不匹配时，客户端以 2 秒限流发送 `RequestJournalLogSnapshotPayload`；客户端不再向服务端上传日志快照。

### 8.4 日志删除

客户端在二次确认后发送 `DeleteJournalLogPayload`，scope 为 `ENTRY`、`BATCH`、`TABLE` 或 `ALL`。批量 UUID 列表有单包数量限制，客户端会按限制自动拆包。服务端从连接取得玩家身份、验证目标属于该玩家，并立即持久化；成功后发送权威日志状态和 `JournalLogDeleteResultPayload`。`UpdateJournalLogRetentionPayload` 单独处理当前表自动上限和按时间清理。普通 GUI 删除不修改累计数、保留策略、考古进度、奖励或成就。

### 8.5 加入时同步序列

[`JournalPlayerDataService.onPlayerJoined`](../../common/src/main/java/com/meteorite/unsuspiciousblock/journal/JournalPlayerDataService.java) 按序执行（见 [考古笔记系统](journal.md) 第 6 节）：

```
restoreLogState -> migratePlayerData -> syncLogSnapshot -> syncCatalogHash
  -> sync loot table management -> syncStateFull -> checkAndRewardAll -> checkAndGrantAll
```

补发奖励/成就在全量状态同步之后，确保客户端 catalog 已就绪可解析表名。

### 8.6 战利品表管理同步

服务端从 `ReloadableServerRegistries` 枚举 LootTable key，过滤 `entities/` 与 `blocks/`，然后发送 `ResourceLocation + tracked` 列表、该玩家仍存在于当前注册表的最近遇到列表、编辑权限以及按语言分组的服务端补充名称。最近列表已按玩家内遇到顺序从新到旧排列。追踪与名称写请求都会重新校验表是否仍存在于注册表且玩家权限等级至少为 2，不能信任客户端候选列表。

名称草稿和 JSON 导入使用同一个批量 payload。服务端先按当前注册表、语言代码和长度限制过滤，再批量更新标准语言文件并只广播一次管理快照。客户端将快照保存在当前连接的内存中，`ClientLanguageMixin` 在查询自动生成的战利品表 key 时动态读取快照；进入或退出世界都不触发资源重载。手工修改服务端语言文件并执行 `/reload` 也会广播新快照。

## 9. 扩展点

- **新增 payload**：
  1. 在 `payload/c2s/` 或 `payload/s2c/` 创建 payload 类（含 `TYPE` 与 `STREAM_CODEC`）。
  2. C2S：在 `ModPayloads.C2S_PAYLOADS` 加条目（type + codec + handler）。
  3. S2C：在 `S2C_SPECS` 加类型描述（服务端注册编解码），在 `Client.S2C_PAYLOADS` 加完整描述（客户端注册接收器）。
  4. 无需修改平台代码，两端自动遍历注册。
- **新增同步策略**：参考增量同步的 revision + 脏表机制，或日志的分片会话机制。

## 10. 相关文档

- [架构总览](architecture-overview.md) - 平台入口的 payload 注册
- [考古笔记系统](journal.md) - 同步的上层调度
- [客户端与 GUI](client-ui.md) - S2C 接收与客户端状态
- [猫族关系系统](cat-favor.md) - `SyncCatFavorPayload`
- [附魔系统](enchantment.md) - `SyncEnchantmentRevealListPayload`
- [平台抽象](platform-abstraction.md) - `INetworkHelper` SPI
