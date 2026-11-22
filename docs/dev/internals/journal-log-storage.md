# 考古日志存储与迁移

> 考古日志的服务端权威存储实现细则：分片磁盘布局、延迟批量写盘、每表保留策略及双版本轴迁移规则。
> 本文件不参与主任务导航，由[考古笔记系统](../subsystems/journal.md)按需链接；通用持久化迁移底座设计见[本地持久化与数据迁移](../foundation/persistence.md)。

## 1. 分片存储布局

[`JournalLogStorage`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/world/JournalLogStorage.java) 将考古日志按玩家 UUID 与战利品表拆分为独立压缩文件，避免全部玩家数据挤入单一 `SavedData` NBT：

```text
<world>/data/unsuspiciousblock/journal_logs/
├── storage.dat                              布局版本与待恢复迁移检查点
├── migration/
│   └── unsuspiciousblock_journal_logs-v1.dat.bak
└── players/<player-uuid>/
    ├── committed.dat                        原子发布的该玩家提交索引
    ├── index.dat                            旧 v2 索引，升级前读取
    ├── tables/<sha256(table-id)>.dat         未更新表的 v2 压缩分片
    └── revisions/<uuid>.dat                  已更新表的不可变压缩分片
```

### 1.1 写盘节拍与批量提交 (`commitBatch`)

- **延迟批量落盘**：普通新增日志与备注更新仅标记对应分片为 dirty；服务端每 `200 tick`（约 10 秒）按玩家轮询最多刷盘 `4` 个分片，平滑压缩与磁盘 IO。因此进程异常崩溃可能丢失约 10 秒内尚未刷盘的普通变更，积压超过 4 个分片时队尾延迟会进一步增加。
- **立即持久化操作**：单条/批量删除、保留裁剪、修改保留上限以及玩家退出清理均立即执行落盘；停服时以有限重试刷新全部 dirty 分片，仍失败则记录未提交内容。
- **统一提交管线 (`commitBatch`)**：定周期 tick、立即保存与退出刷新共用同一套提交流程。更新分片先写入独立 revision 文件，全部准备完成后原子替换 `committed.dat`；删除仅从新索引移除目标分片。索引提交成功后再清理旧分片。失败时旧索引与其引用保持不变，相关表交由后续 tick 重试。
- **提交索引与旧布局读取**：启动以 `committed.dat` 为权威清单，读取时直接加载它引用的压缩分片。已提交索引损坏时启动报错，避免回退扫描重新暴露被较早提交删除的条目。没有提交索引的旧档按 v2 `index.dat` 读取，损坏或缺失时扫描旧 `tables/` 并重建索引。首次成功写入后提交索引切换为 v3。
- **原子提交和恢复**：新 revision 以唯一文件名准备，索引使用同目录临时文件及 `ATOMIC_MOVE` 发布。玩家首次加载时清理未被已提交索引引用的旧分片和 revision。加载分片时核对存储版本、玩家 UUID 与内含的原始 `table_id`。
- **退出缓存与重连复用**：玩家退出时同步刷盘并在成功后卸载内存状态；若刷盘失败则保留内存缓存等待 tick 重试，玩家在卸载前重新登录会取消卸载标记并直接复用缓存。

### 1.2 变更意图 (`JournalLogMutation`)

`JournalLogMutation` 接收显式领域变更意图与持久化策略：对于立即删除等不可逆操作，执行前先仅对受影响表做状态快照并记录表序，一旦磁盘提交失败立即同引用还原，保证内存与磁盘语义一致；`JournalLogHandler` 在确认落盘成功后才向客户端推进增量序号或发送操作回执。

## 2. 保留策略与删除语义

- **每表配置与终身统计**：每张表独立持久化玩家设置的 `retention_limit`（受服务端配置 `maxLogEntriesPerTable` 全局上限钳制）与 `long lifetime_entry_count`（仅在全新 `entryId` 首次写入时递增）。
- **自动裁剪与备注保护**：
  - 新增条目或执行一次性「仅保留最近 N 条」时，所有**带备注条目均受保护**，仅按时间顺序淘汰最旧的无备注条目直至满足有效上限；
  - 若当前带备注条目数已达到或超过有效上限（如玩家调低上限后），则禁止继续新增无保护日志并向玩家发出提示，直到玩家主动删去部分备注或日志。
- **备注清洗与更新**：领域入口保留换行、回车、制表符及允许字符并按长度上限截断；更新已有 `entryId` 的备注时保持原有插入位置不变。成就门槛读取全局上限配置，单表裁剪读取有效保留上限。
- **显式删除与二次确认**：单条删除、批量选择删除、清空当前表与清空全部日志均需通过 `ConfirmScreen` 确认，服务端严格校验请求只能操作连接玩家自身的数据。显式删除可清除带备注的条目，但**只删除日志记录本身**，不会回退 `lifetime_entry_count`、保留设置、目录解锁、物品计数、完成奖励或成就；仅 `/usb journal reset` 会一并重置表历史统计。

## 3. 版本迁移与事务恢复

考古笔记的 NBT 格式与日志磁盘布局分别维护版本轴（通用底座规则见[本地持久化与数据迁移](../foundation/persistence.md)）：

- **NBT Schema 版本（各格式当前 v2）**：进度、日志状态、单表历史与日志条目分别由 `JournalDataVersion` 声明版本范围（缺少 `data_version` 的历史数据视为各自的 `v0`）。字段转换注册在 `JournalNbtMigrator`，通过 `NbtMigration` 在副本上逐级升版并拒绝未来版本。
- **存储布局版本（当前 v3）**：描述单文件 v1、分片索引 v2 与提交索引 v3，由 `JournalLogStorage` 登记到 `StorageMigration`。

### 3.1 迁移事务与旧源合并

- **Manifest 显式事务语义**：`storage.dat` 严格记录 `committed_storage_version` 与成对的 `pending_from_version` / `pending_to_version`。启动时若发现起止版本与已提交版本冲突，直接阻止启动（不再通过版本减一猜测状态），仅当 manifest 物理损坏无法解压时才按源数据重建。兼容字段 `storage_version` 与 `migration_complete` 仍保持写入以兼容旧版本识别，但不参与新版恢复决策。
- **v2 至 v3 转换**：布局 manifest 提交 v3，保留现有 v2 分片和 `index.dat`。玩家首次写入时以该索引中的分片清单为起点，通过 `committed.dat` 发布新分片路径；旧表分片在新索引提交后清理。
- **历史单文件与玩家 NBT 合并**：
  - 首次迁移时，旧 `<world>/data/unsuspiciousblock_journal_logs.dat` 拆分写入全部目标分片并提交版本后，再移入 `migration/*.bak` 备份；
- 玩家 NBT 中残留的更早期 `unsuspiciousblock_archaeology_journal_log` 由 `PendingLegacyJournalLog` 托管，在玩家登录时按 `entryId` 合并到当前布局（服务端已有同 ID 条目优先），**必须等目标表分片与索引全部落盘后才发送 `ack` 确认清理源 NBT**，失败时继续保留旧标签等待下次登录重试。
- **登录迁移顺序**：`JournalPlayerDataService` 先按已提交索引恢复日志分片，再由 `JournalDataMigrationManager` 依次提交遗留玩家日志并完成目录签名对齐，最后统一下发两类玩家数据。

## 4. 相关文档

- [考古笔记系统](../subsystems/journal.md)
- [本地持久化与数据迁移](../foundation/persistence.md)
- [战利品追踪核心数据流](journal-tracking.md)
