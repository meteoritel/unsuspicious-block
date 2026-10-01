# 考古日志存储与迁移

> 考古日志的服务端权威存储实现细则：分片布局、保留策略与双版本轴迁移约定。
> 本文件不参与任务导航，只被 [考古笔记系统](../subsystems/journal.md) 链接。**新增追踪来源或进度字段只需要读那篇**；只有改动日志存储布局、保留策略或迁移步骤时才读本文件。

## 1. 分片存储

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

## 2. 保留策略

- 每张表持久化玩家设置的 `retention_limit` 与 `long lifetime_entry_count`。前者受服务器 `maxLogEntriesPerTable` 全局兜底限制；后者只在**新 `entryId` 首次写入时**累计，普通 GUI 删除不会回退，`/usb journal clear [table_id]` 才会连同表历史一起清除。
- 新增日志时，如果带备注条目数已达当前表有效上限，则拒绝新增并警告玩家；否则自动删除最旧的无备注条目直到回到上限。一次性"仅保留最近 N 条"同样保护所有带备注条目。玩家后来降低上限时允许带备注条目数暂时超过上限，但在清除备注或删除日志前不能新增。
- 玩家可在详情页删除单条日志，也可进入批量选择模式后选择条目或整个分组；批量执行、清空当前表和清空全部表都使用 `ConfirmScreen` 二次确认。C2S 请求**只能操作连接玩家自身的数据**。显式删除会无视备注保护，但只删除日志条目，不回退累计数、保留策略、目录解锁、物品计数、完成奖励或成就。

## 3. 版本迁移约定

考古日志有两个**互不混用**的版本轴，统一定义在 `JournalDataVersion`：

- **NBT schema 版本**：描述字段与嵌套结构，当前为 v2；缺少 `data_version` 视为 v0。
- **存储布局版本**：描述单文件或分片目录结构，当前为 v2。

所有旧 NBT 字段名、默认值和逐级迁移步骤**只能添加到 `JournalNbtMigrator`**。迁移必须严格按 `vN -> vN+1` 连续执行；遇到高于当前版本的数据时**拒绝降级读取**。新增版本时先提高 `JournalDataVersion.CURRENT_NBT_VERSION`，再补齐每种数据类型对应的迁移步骤。状态类的 `readFrom` / `fromTag` 不得重新加入旧字段回退分支。

存储布局 manifest 使用显式事务语义：`committed_storage_version` 始终是最后完整提交的版本，`pending_from_version` / `pending_to_version` 仅表示已开始但尚未提交的确切一步。启动时必须验证 pending 起点等于 committed、目标是下一连续版本且不高于当前版本；语义冲突会**阻止启动**，不得再通过版本号减一猜测迁移阶段。物理损坏、无法解压的 manifest 才允许按已知源数据重建。

每个存储布局迁移步骤都必须登记来源版本、目标版本、恢复策略、迁移动作和提交后清理动作。只有源数据在提交前保持不变、目标写入可重复覆盖的步骤才能用 `RESTART_FROM_SOURCE`；非幂等步骤必须用 `ABORT_IF_INTERRUPTED`，中断后停止自动迁移并要求人工恢复。提交后的源数据清理必须幂等，以便服务器在"版本已提交但清理未完成"时安全续做。兼容字段 `storage_version` / `migration_complete` 仍会写入，但只向旧版表示最后已提交版本，不参与新版恢复决策。

`JournalPlayerDataService` 是生命周期统一入口：服务端启停和 tick、玩家登录/退出/重生都由它协调。登录时先恢复日志分片，再由 `JournalDataMigrationManager` 依次提交旧玩家日志并迁移目录签名，最后统一下发两类玩家数据。**源数据只有在目标数据完整持久化后才允许清理**，世界级旧单文件则保留 `.bak` 备份。
