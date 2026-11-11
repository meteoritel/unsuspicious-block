# 考古日志存储与迁移

> 考古日志的服务端权威存储实现细则：分片磁盘布局、延迟批量写盘、每表保留策略及双版本轴迁移规则。
> 本文件不参与主任务导航，由[考古笔记系统](../subsystems/journal.md)按需链接；通用持久化迁移底座设计见[本地持久化与数据迁移](../foundation/persistence.md)。

## 1. 分片存储布局

[`JournalLogStorage`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/world/JournalLogStorage.java) 将考古日志按玩家 UUID 与战利品表拆分为独立压缩文件，避免全部玩家数据挤入单一 `SavedData` NBT：

```text
<world>/data/unsuspiciousblock/journal_logs/
├── storage.dat                              已提交布局版本与待恢复迁移检查点
├── migration/
│   └── unsuspiciousblock_journal_logs-v1.dat.bak
└── players/<player-uuid>/
    ├── index.dat                            该玩家的权威战利品表清单
    └── tables/<sha256(table-id)>.dat         单张战利品表的压缩日志分片
```

### 1.1 写盘节拍与批量提交 (`commitBatch`)

- **延迟批量落盘**：普通新增日志与备注更新仅标记对应分片为 dirty；服务端每 `200 tick`（约 10 秒）按玩家轮询最多刷盘 `4` 个分片，平滑压缩与磁盘 IO。因此进程异常崩溃可能丢失约 10 秒内尚未刷盘的普通变更，积压超过 4 个分片时队尾延迟会进一步增加。
- **立即持久化操作**：单条/批量删除、保留裁剪、修改保留上限以及玩家退出清理均立即执行落盘；停服时以有限重试刷新全部 dirty 分片，仍失败则记录未提交内容。
- **统一提交管线 (`commitBatch`)**：定周期 tick、立即保存与退出刷新共用同一套提交流程——写/删表分片 → 更新玩家 `index.dat` → 全部成功后执行内存卸载检查。单分片写入失败时保留旧 `index.dat`，并将受影响玩家批次加入后续重试队列（即便仅索引写失败也保留重试），`CommitResult` 分别汇报失败分片与索引状态。
- **原子替换与索引权威**：所有分片与 `index.dat` 均先写同目录临时文件再原子替换；加载分片时严格核对存储版本、玩家 UUID 与内含的原始 `table_id`。正常启动以 `index.dat` 为权威清单，仅当索引损坏或缺失时才扫描 `tables/` 重建索引，从而避免已删除但磁盘遗留的孤立分片被意外复活。
- **退出缓存与重连复用**：玩家退出时同步刷盘并在成功后卸载内存状态；若刷盘失败则保留内存缓存等待 tick 重试，玩家在卸载前重新登录会取消卸载标记并直接复用缓存。

### 1.2 变更意图 (`JournalLogMutation`)

`JournalLogMutation` 接收显式领域变更意图与持久化策略：对于立即删除等不可逆操作，执行前先仅对受影响表做状态快照并记录表序，一旦磁盘提交失败立即同引用还原，保证内存与磁盘语义一致；`JournalLogHandler` 在确认落盘成功后才向客户端推进增量序号或发送操作回执。

## 2. 保留策略与删除语义

- **每表配置与终身统计**：每张表独立持久化玩家设置的 `retention_limit`（受服务端配置 `maxLogEntriesPerTable` 全局上限钳制）与 `long lifetime_entry_count`（仅在全新 `entryId` 首次写入时递增）。
- **自动裁剪与备注保护**：
  - 新增条目或执行一次性「仅保留最近 N 条」时，所有**带备注条目均受保护**，仅按时间顺序淘汰最旧的无备注条目直至满足有效上限；
  - 若当前带备注条目数已达到或超过有效上限（如玩家调低上限后），则禁止继续新增无保护日志并向玩家发出提示，直到玩家主动删去部分备注或日志。
- **备注清洗与更新**：领域入口保留换行、回车、制表符及允许字符并按长度上限截断；更新已有 `entryId` 的备注时保持原有插入位置不变。成就门槛读取全局上限配置，单表裁剪读取有效保留上限。
- **显式删除与二次确认**：单条删除、批量选择删除、清空当前表与清空全部日志均需通过 `ConfirmScreen` 确认，服务端严格校验请求只能操作连接玩家自身的数据。显式删除可清除带备注的条目，但**只删除日志记录本身**，不会回退 `lifetime_entry_count`、保留设置、目录解锁、物品计数、完成奖励或成就；仅 `/usb journal clear` 会一并重置表历史统计。

## 3. 版本迁移与事务恢复

考古日志维护由 `JournalDataVersion` 统一声明的两条独立版本轴（通用底座规则见[本地持久化与数据迁移](../foundation/persistence.md)）：

- **NBT Schema 版本（当前 v2）**：描述日志状态与条目的字段编码（缺少 `data_version` 的历史数据视为 `v0`）。全部转换步骤统一注册在 `JournalNbtMigrator` 上，通过 `NbtMigration` 在副本上严格按 `vN → vN+1` 连续升版并拒绝未来版本。
- **存储布局版本（当前 v2）**：描述单文件 v1 向分片目录 v2 的结构演进，由 `JournalLogStorage` 登记到 `StorageMigration`。

### 3.1 迁移事务与旧源合并

- **Manifest 显式事务语义**：`storage.dat` 严格记录 `committed_storage_version` 与成对的 `pending_from_version` / `pending_to_version`。启动时若发现起止版本与已提交版本冲突，直接阻止启动（不再通过版本减一猜测状态），仅当 manifest 物理损坏无法解压时才按源数据重建。兼容字段 `storage_version` 与 `migration_complete` 仍保持写入以兼容旧版本识别，但不参与新版恢复决策。
- **历史单文件与玩家 NBT 合并**：
  - 首次进入 v2 时，旧 `<world>/data/unsuspiciousblock_journal_logs.dat` 拆分写入全部目标分片并提交版本后，再移入 `migration/*.bak` 备份；
  - 玩家 NBT 中残留的更早期 `unsuspiciousblock_archaeology_journal_log` 由 `PendingLegacyJournalLog` 托管，在玩家登录时按 `entryId` 合并到 v2（服务端已有同 ID 条目优先），**必须等目标表分片与 `index.dat` 全部落盘后才发送 `ack` 确认清理源 NBT**，失败时继续保留旧标签等待下次登录重试。
- **登录迁移顺序**：`JournalPlayerDataService` 先恢复日志分片，再由 `JournalDataMigrationManager` 依次提交遗留玩家日志并完成目录签名对齐，最后统一下发两类玩家数据。

## 4. 相关文档

- [考古笔记系统](../subsystems/journal.md)
- [本地持久化与数据迁移](../foundation/persistence.md)
- [战利品追踪核心数据流](journal-tracking.md)
