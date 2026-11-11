# 本地持久化与数据迁移

## 版本归属

持久化版本描述磁盘契约：字段与嵌套结构、值编码、文件组织。类移动、Interface 收敛、依赖调整及生命周期重排保持现有磁盘契约和版本。各格式独立演进，模组版本与网络协议版本分别管理。

当前迁移原点沿用已有格式：

| 数据 | 原点 | 事实源与策略 |
|---|---|---|
| 笔记进度、日志状态、表历史、日志条目 | NBT v2 | `JournalDataVersion`；保留 v0→v1→v2 读取链 |
| 服务端日志目录 | 布局 v2 | `JournalDataVersion`；保留单文件 v1→分片 v2 迁移 |
| 玩家猫族关系 | NBT v2 | `CatFavorState`；保留无标记 favor 格式与已有整数版本读取 |
| 概率缓存 | 格式 v5 | `LootProbabilityData`；不匹配时按缓存未命中重建 |
| 淘洗账本、商人生成、结构回溯冷却、实体/方块实体扩展、客户端偏好 | 无版本标记的现有格式 | 沿用各自序列化器，保持字段和位置；无标记格式作为自身的隐式 v0 |

NBT 字段与磁盘布局是独立版本轴。修改字段只推进该格式的 NBT 版本；改变文件组织只推进布局版本。改变缓存计算含义时使用现有 hash/输入身份失效机制，缓存结构保持时保留格式版本。

## 迁移 Module

[`core.persistence.migration`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/persistence/migration) 提供统一的迁移 Interface。领域层拥有格式与旧字段转换；底座拥有连续版本校验与执行顺序，保持 common 的 Loader 和端隔离。

- `MigrationVersions`：定义单个格式的可读范围，拒绝范围外数据和跳步。
- `NbtMigration`：注册来源版本到下一版本的转换。注册时检查完整性，读入时校验版本类型与范围，在副本上执行整条链，成功后替换输入。当前版本直接通过，未知字段保留；无版本字段按最早支持版本处理。
- `StorageMigration`：注册布局步骤、恢复策略、生成目标与幂等源清理。领域 Adapter 通过 `Checkpoint` 原子写 pending 和 committed，底座在提交成功后才清理源。

手册字段转换由 `JournalNbtMigrator` 登记，猫族转换由 `CatFavorState` 登记；两者都使用 `NbtMigration`。客户端本地日志通过同一个日志状态反序列化入口复用手册迁移。`JournalLogStorage` 使用 `StorageMigration`，保留 manifest 编解码、路径、压缩与原子替换方式。

## 新增格式迁移

1. 比较旧、新序列化结果，确定实际改变的字段、编码或布局，以及对应的独立版本轴。
2. 在格式所属代码中推进当前版本并保留既有版本值。无标记格式首次改变时，以隐式 v0 为来源，引入首个显式目标版本；单纯重构继续保持无标记格式。
3. 字段转换登记到 `NbtMigration`，布局转换登记到 `StorageMigration`；保留从最早支持版本开始的完整连续链。状态解析只消费转换后的当前字段。
4. 使用临时输入副本验证旧数据结果、当前数据保持、未来版本拒绝和失败保源。布局迁移另验证 pending 恢复与提交后清理重试。

只有源在提交前保持不变、目标可重复覆盖的布局步骤才能使用 `RESTART_FROM_SOURCE`；其余步骤使用 `ABORT_IF_INTERRUPTED`，由操作者恢复。底座提供逐步 checkpoint，存储层继续承担单文件原子写；跨文件整体提交依赖领域格式自身的协议。

旧玩家日志从玩家 NBT 合并到分片时，继续等全部目标表与 index 落盘后才确认源。目录签名对齐是运行时目录兼容处理，沿用 `JournalDataMigrationManager`，保持持久化版本。概率缓存的失效重建沿用其读端策略。

## 相关文档

- [考古日志存储与迁移](../internals/journal-log-storage.md)
- [猫族关系机制](../internals/cat-favor-mechanics.md)
- [开发流程](../../guides/development.md)
