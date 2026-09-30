# 网络与同步

> `network/` 包的架构：自定义 payload 的清单式注册、C2S/S2C 分类、客户端/服务端分离技巧、平台注册与同步模式。
> 本文件是 payload 清单与网络层约定的唯一权威。子系统的同步语义以各自子系统文档为准（如日志同步见 [考古笔记系统](../subsystems/journal.md)）。

## 1. 机制概览

模组用 1.21 的 `CustomPacketPayload` 机制实现网络通信，共 **33 个自定义 payload（15 个 C2S + 18 个 S2C）**，覆盖：

- **考古笔记同步**：目录、进度状态（增量/全量）、日志（更新/快照）、完成奖励通知。
- **目录按需同步**：哈希比对，不一致时客户端主动请求全量目录。
- **按需概率模拟**：玩家选定的模拟输入（场景 + 参数）请求计算、结果回执、拒绝回执；以及"推荐 / 读取当前选择"的辅助请求。
- **缓存状态查询**：客户端可按目录代次、表哈希和完整输入查询服务端已有场景结果；查询只读缓存，不会隐式启动模拟。
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
│   ├── ScenarioSimulationHandler  按需概率模拟请求：限流 + 参数构造 + 转交目录受理
│   ├── SimulationAssistHandler    推荐 / 读取当前选择（版本校验 + 每玩家冷却）
│   └── ReaderScanLevelHandler   扫描等级更新处理
├── payload/
│   ├── c2s/                   14 个客户端->服务端 payload
│   ├── s2c/                   17 个服务端->客户端 payload
│   └── s2c/CatalogStreamCodec   目录数据的线格式编解码（全量目录与按需结果共用一份）
└── (cat/CatNetworkHandler 在 cat/ 包)
```

## 3. payload 清单模式

[`ModPayloads`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/network/ModPayloads.java) 沿用项目的"清单 + 遍历"模式（见 [注册架构](registration.md)），统一管理所有 payload 的注册信息：

```java
// C2S：类型 + 编解码 + 服务端处理函数
record C2S<T>(Type<T> type, StreamCodec codec, BiConsumer<ServerPlayer, T> handler)

// S2C 类型描述：类型 + 编解码（供服务端注册编解码器，Fabric 需要）
record S2CSpec<T>(Type<T> type, StreamCodec codec)

// 客户端 S2C：类型 + 编解码 + 客户端处理函数（嵌套在 Client 类中）
record S2C<T>(Type<T> type, StreamCodec codec, Consumer<T> handler)
```

三个清单：`C2S_PAYLOADS`、`S2C_SPECS`（服务端注册编解码）与 `Client.S2C_PAYLOADS`（客户端注册接收器）。平台层只需遍历清单注册，不手写重复代码；**具体数量以清单源码为准**，本文档的表只是索引。

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
| `RequestScenarioCachePayload` | `ScenarioSimulationHandler::handleCache` | 查询当前表与参数下服务端已有场景缓存，不触发模拟 |
| `UpdateLootTableTranslationsPayload` | `LootTableManagementHandler::handleTranslationUpdate` | 批量提交手工草稿或 JSON 导入名称 |
| `RequestJournalStateFullPayload` | `JournalStateHandler::handleRequestFull` | 请求全量状态重同步 |
| `RequestJournalLogSnapshotPayload` | `JournalLogHandler::handleRequestSnapshot` | 请求日志快照 |
| `UpdateJournalLogNotePayload` | `JournalLogHandler::handleUpdateNote` | 更新日志备注 |
| `DeleteJournalLogPayload` | `JournalLogHandler::handleDeleteLogs` | 删除单条、批量选择、当前表或全部日志 |
| `UpdateJournalLogRetentionPayload` | `JournalLogHandler::handleUpdateRetention` | 设置当前表自动上限或仅保留最近 N 条 |
| `RequestScenarioSimulationPayload` | `ScenarioSimulationHandler::handleRequest` | 请求按需模拟一个输入（携带目录代次、表哈希、场景 key 与参数） |
| `RequestSimulationAssistPayload` | `SimulationAssistHandler::handle` | 请求序号 + `SimulationAssistTarget(kind, value)` + 原输入；目标明确区分 `PLAYER_STATE`、`ITEM`、`CHILD_TABLE`，子表目标必须是当前表的直接子表 |
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
| `SyncReaderScanResultPayload` | `ReaderScanHudState::receive` | 紧凑扫描结果 HUD 与方块高亮 |
| `SyncEnchantmentRevealListPayload` | `EnchantmentRevealClientState::receive` | 附魔揭示候选 |
| `NotifyTableCompletionRewardPayload` | `receiveTableCompletionReward` | 100% 完成奖励通知 |
| `SyncScenarioResultPayload` | `ScenarioSimulationClientState::receive` | 某个输入的模拟结果（单表 DTO + 代次/表哈希/输入键） |
| `ScenarioRequestRejectedPayload` | `ScenarioSimulationClientState::receiveRejection` | 请求被拒绝的回执与原因 |
| `SyncSimulationAssistPayload` | `ScenarioSimulationClientState::receiveAssist` | 推荐/读取请求的答复：回传请求上下文与候选输入，客户端只应用仍匹配当前选择的答复；`notes` 携带 `assist_busy` / `assist_stale` 等提示 |

## 7. 平台注册

两端在各自入口遍历清单注册：

**Fabric**（`UnsuspiciousBlockFabric`）：
```java
for (C2S<?> c2s : ModPayloads.C2S_PAYLOADS) registerC2S(c2s);         // playC2S + ServerPlayNetworking
for (S2CSpec<?> spec : ModPayloads.S2C_SPECS) registerS2CSpec(spec);  // playS2C 编解码器
```
客户端（`UnsuspiciousBlockFabricClient`）：
```java
for (Client.S2C<?> s2c : ModPayloads.Client.S2C_PAYLOADS) registerS2C(s2c); // ClientPlayNetworking
```

**NeoForge**（`UnsuspiciousBlockNeoForge`）：
```java
// RegisterPayloadHandlersEvent 中
registrar.versioned("4.9");
for (C2S<?> c2s : ModPayloads.C2S_PAYLOADS) registerC2S(registrar, c2s);  // playToServer
```
客户端（`UnsuspiciousBlockNeoForgeClient`）：
```java
for (Client.S2C<?> s2c : ModPayloads.Client.S2C_PAYLOADS) registerS2C(registrar, s2c); // playToClient
```

**C2S 主线程调度**：Fabric 端 C2S handler 通过 `context.server().execute(...)` 调度到主线程；NeoForge 端 payload handler 默认在主线程执行。这保证状态修改的线程安全。

**版本化**：NeoForge 端用 `registrar.versioned(...)` 声明 payload 协议版本，客户端/服务端入口均为 `4.9`。此版追加服务端只读缓存查询 payload；Fabric 使用同一 common codec，两个平台的客户端与服务端必须配套更新。**任何追加/删除线字段都是协议版本变更**。

### 7.1 线格式的两条硬约束

1. **传输目录数据必须走 `CatalogStreamCodec`。** 全量目录与按需结果传的是同一个 `CatalogTableDto`：各写一份编解码，症状是"改了字段只更新了一处"，客户端读到错位字节流——这是最难从现象反推成因的一类错误。
2. **条件入口树由服务端派生下发，客户端不本地重推。** 子表入口的条件（路径共同条件 + 注入边门槛）中，注入边不写在任何 JSON 里，本地重推必然漏项。

### 7.2 按需概率模拟的网络侧约定

玩家选定一个模拟输入（场景 + 参数）后，客户端发 `RequestScenarioSimulationPayload`，服务端回 `SyncScenarioResultPayload`（成功）或 `ScenarioRequestRejectedPayload`（被拒绝）。完整链路与校验语义以 [战利品表系统](../subsystems/loottable.md) 为权威，此处只记网络侧的约定：

- **请求传结构化字段而不是拼好的输入键**：服务端必须能逐项校验（场景、工具、抽样次数只能取签发值，幸运有界）。把校验建立在一个自造字符串上，等于把校验逻辑也写成一个解析器，而解析器的每一处"宽松处理"都是越权的入口。
- **客户端的条件赋值由服务端还原**：客户端只挑场景（传场景 key），不构造条件指纹表。
- **结果包的三个标识缺一不可**：目录代次、表哈希、输入键。三者在客户端都要校验，任一不符即丢弃——切参数或 `/reload` 之后旧结果可能后到，不校验就会把上一代的数据画到当前界面上。
- **被拒绝的请求一定回执**：这些情形都不会产出结果包，没有回执的话"点了没反应"与"还在计算中"在界面上无法区分。回执不携带任何概率，也不进缓存。
- **结果只回给请求者**，不写共享目录：按内容去重的缓存是全服共享的，但"当前展示哪个输入"是每个玩家自己的选择，写进共享目录会让两个玩家互相覆盖对方的界面。
- **推荐/读取（assist）与模拟请求分开**：`SimulationAssistHandler` 只在主动请求时执行，用版本校验 + 每玩家 500ms 冷却拒绝过期或过频请求，通过 `notes` 回传 `assist_busy` / `assist_stale`，不产出概率。
- **推荐先预览后应用**：答复仍绑定原请求序号、目录代次、表哈希及原输入。关闭预览或切表会取消客户端接收资格；15 秒无答复显示超时并允许重试，不能由迟到答复覆盖当前选择。应用只修改输入，模拟仍需显式计算。

## 8. 同步模式

网络层有三种可复用的同步模式，各自的语义与规模限制以下列子系统文档为权威：

| 模式 | 机制要点 | 权威文档 |
|---|---|---|
| 增量 + 全量 | revision + 脏表；客户端检测 revision 间隙（`incomingRevision > lastNotified + 1`）并带 2s 限流主动请求全量 | [考古笔记系统](../subsystems/journal.md) |
| 按需哈希比对 | 服务端算目录 SHA-256，模拟完成后广播；客户端比对不一致才请求全量目录，避免每次登录全量下发 | [考古笔记系统](../subsystems/journal.md) |
| 分片会话快照 | 会话 ID + 增量序号；先发 Start，每表压缩后切成最多 128 KiB 分片，再发 End，客户端校验完一次性替换 | [考古笔记系统](../subsystems/journal.md) |

网络侧的规模上限（线路契约，改这些值等于改协议）：

- 单表压缩后 ≤ 16 MiB；解压 NBT ≤ 64 MiB；一次快照 ≤ 65,536 张表。
- 网络解码不再调用 `readNbt` 读取整份日志，因此不受原版 2 MiB 单 NBT payload 上限影响。
- 删除 payload 的批量 UUID 列表有单包数量限制，客户端按限制自动拆包。

## 9. 扩展点：新增 payload

1. 在 `payload/c2s/` 或 `payload/s2c/` 创建 payload 类（含 `TYPE` 与 `STREAM_CODEC`）。
2. C2S：在 `ModPayloads.C2S_PAYLOADS` 加条目（type + codec + handler）。
3. S2C：在 `S2C_SPECS` 加类型描述（服务端注册编解码），在 `Client.S2C_PAYLOADS` 加完整描述（客户端注册接收器）。
4. **无需修改平台代码**，两端自动遍历注册。
5. 传输目录数据时**必须**走 `CatalogStreamCodec`，不要另写一份字段顺序。
6. 追加线字段时同步提升 `registrar.versioned(...)` 的协议版本，并在本文件第 5、6 节的表里补一行。

> 新增同步策略时，参考第 8 节的三种模式，不要把状态推拉逻辑写进 handler —— handler 只做校验与转交。

## 10. 约束与陷阱

- payload 数量、清单长度**不要写死在文档正文**里当作断言（本文只给索引表），增减以 `ModPayloads` 源码为准。
- 服务端不得引用 `ModPayloads.Client`，否则服务端 classpath 会引入客户端类。
- 需要"每玩家独立"的数据不要写进共享缓存或共享目录（见 7.2）。

## 11. 相关文档

- [架构总览](architecture.md) —— 平台入口的 payload 注册
- [考古笔记系统](../subsystems/journal.md) —— 同步的上层调度与三种同步模式
- [战利品表系统](../subsystems/loottable.md) —— 按需模拟的完整链路
- [客户端与 GUI](../subsystems/client-ui.md) —— S2C 接收与客户端状态
- [猫族关系系统](../subsystems/cat-favor.md) —— `SyncCatFavorPayload`
- [附魔系统](../subsystems/enchantment.md) —— `SyncEnchantmentRevealListPayload`
- [平台抽象](platform-spi.md) —— `INetworkHelper` SPI
