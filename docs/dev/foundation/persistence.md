# 本地持久化与数据迁移

本文档规定模组本地存档数据的版本划分原则、`core.persistence.migration` 通用迁移底座的使用契约，以及新增磁盘格式迁移的标准流程。

## 1. 版本轴与事实源

磁盘持久化版本遵循**职责独立演进**原则，与模组版本号、网络协议版本号（`ModPayloads.PROTOCOL_VERSION`）相互独立。同一领域内的「字段编码」与「文件组织」也分为两个独立版本轴：

- **修改 NBT 字段或值编码**：仅递增对应格式的 NBT Schema 版本。
- **修改磁盘目录或分片结构**：仅递增存储布局版本。
- **仅调整类名、接口或生命周期编排**：只要磁盘字段与目录布局不变，**不递增版本号**，保持现有存储契约。
- **改变计算语义或缓存含义**：沿用对应的内容哈希或分析版本号（如 `SIMULATION_CACHE_VERSION`）触发缓存未命中重建，而不改变缓存文件结构版本。

当前持久化数据及其迁移原点如下：

| 数据领域 | 当前版本原点 | 事实源与兼容性策略 |
|---|---|---|
| 考古笔记进度 | NBT v2 | `JournalDataVersion.JOURNAL_STATE_NBT`；保留历史连续读取版本 |
| 考古日志状态、表历史与日志条目 | 各自 NBT v2 | `JournalDataVersion` 中每种格式各自声明版本范围，迁移步骤由 `JournalNbtMigrator` 登记 |
| 服务端考古日志目录 | 布局 v3 | `JournalDataVersion`；读取旧单文件 v1 与分片索引 v2，首次写入时采用提交索引 v3 |
| 玩家猫族关系 | NBT v2 | `CatFavorState`；兼容无版本旧格式及历史整数版本读取 |
| 世界战利品追踪配置 | JSON v1 | 各 Loader 旧配置一次导入，共用 JSON 为世界权威；见[配置与联动](config-and-integrations.md#2-世界战利品追踪配置) |
| 战利品表补充名称 | 标准语言 JSON | 按语言提交旧世界文件，并在整合包配置目录按源内容记录完成标记；保留世界源文件供旧版本读取 |
| 战利品表概率模拟缓存 | 格式 v5 | `LootProbabilityData`；版本或标签类型不匹配时整体按缓存未命中重算，不做迁移 |
| 客户端笔记 UI 偏好 | NBT v1 | `JournalReadingStateCodec`；无标记 v0 经副本迁移，保留旧偏好与条件偏移键 |
| 淘洗账本、商人生成、结构回溯冷却、实体/方块实体扩展 | 各自现有格式 | 沿用既有序列化器与字段位置；若未来首次变更，以当前无标记格式作为隐式 v0 |

## 2. 通用迁移底座

[`core.persistence.migration`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/persistence/migration) 提供统一的平台无关迁移执行器，由底座保证连续版本校验与安全边界，各领域自行提供格式转换实现：

- **`MigrationVersions`**：定义单一格式的最低与最高支持版本，并校验迁移链覆盖每个连续版本步骤。
- **`NbtMigration`**：注册 `vN → vN+1` 的逐级字段转换步骤。读取数据时执行整条迁移链的**副本转换**，全部成功后才替换原始输入；已是当前版本的数据直接通过（保留未知字段），缺少版本标记的历史数据按最早支持版本处理。
  - 由 `JournalNbtMigrator`（考古笔记）、`CatFavorState`（猫族关系）与 `JournalReadingStateCodec`（客户端偏好）各自登记；客户端本地日志通过同一反序列化入口复用该迁移链。
- **`StorageMigration`**：管理文件级布局迁移步骤、断点恢复策略与提交后清理动作，由 `JournalLogStorage` 调用：

```mermaid
flowchart LR
    Pending["1. 写 Pending Checkpoint (记录确切起止版本)"] --> Exec["2. 执行布局迁移 (写入新目标文件)"]
    Exec --> Commit["3. 写 Committed 版本 (确认新布局生效)"]
    Commit --> Cleanup["4. 幂等清理或备份源数据 (崩溃重启可安全续做)"]
```

**中断恢复策略选择**：
- `RESTART_FROM_SOURCE`：仅当源数据在提交前保持只读不变、且目标文件可安全覆盖重放时使用。
- `ABORT_IF_INTERRUPTED`：非幂等迁移必须使用该策略，若中途崩溃则阻止继续启动并交由维护者人工核对恢复，避免数据损毁。

底座负责逐步维护状态检查点，底层存储文件仍需各自使用同目录临时文件保证单文件原子替换。

### 2.1 客户端笔记偏好

`JournalReadingStateCodec` 以 `uiPreferencesVersion` 管理 `journal_ui_preferences.dat`：无版本视为 v0，迁移在副本中加入阅读默认值后标记 v1。保留根 `lastSelectedTable`、`lastRightPageTab`、排序/搜索/收藏与 `simulationPreferences`，已有 `panels.scenario.conditionOffsets` 仍按表 ID 保存有界偏移。

`simulationPreferences` 保存各表已提交的条件与参数；`ScenarioSession` 通过现有 store 恢复和写入。请求票据、内容版本内的分支引用、版本化草稿和单表快照属于连接内状态，断线时随会话退役。会话的归属与提交规则见[笔记 GUI 内部机制](../internals/journal-ui-internals.md#4-场景条件页与文档动作)。

v1 的 `reading` compound 保存具名页号、可选 active tag、日志 entry ID 与详情模式；表身份与 Tab 来自同一次快照。批选和临时输入不落盘，页号上界由内容面板钳制。旧偏好缺页号时使用首页，合法旧字段照常恢复。`JournalUiPreferencesStore` 在版本类型错误、未来版本或 IO 读取失败时报告并禁止覆盖当前路径源文件，连接切换重新载入对应路径资格。

## 3. 新增格式迁移流程

1. **确认变动轴**：比对新旧序列化结果，区分属于「NBT 字段变动」还是「磁盘目录布局变动」，确定需推进的版本号。
2. **保留旧版本并升级常量**：在对应版本定义处递增当前版本，保留历史版本常量；无版本号的既有格式首次变更时，以隐式 `v0` 为起点引入首个显式版本号 `v1`。
3. **登记连续迁移步骤**：
   - 字段变化登记到 `NbtMigration`；
   - 布局变化登记到 `StorageMigration`（确保清理步骤幂等）；
   - 状态类自身的反序列化入口只解析当前版本字段，不混入旧字段兼容分支。
4. **验证迁移行为**：使用临时存档副本依次验证：① 旧数据可逐级升至最新；② 当前版本数据原样保持；③ 高于当前版本的未来数据被安全拒绝；④ 迁移失败时不破坏源数据（布局迁移额外验证断点恢复与清理重试）。

> 跨实体与存档的旧日志合并（玩家 NBT → 分片存储）须等待全部目标表分片与已提交索引落盘后再确认源数据；目录条目签名对齐由 `JournalDataMigrationManager` 在运行时处理，不改变 NBT 版本号。

## 4. 相关文档

- [考古日志存储与迁移](../internals/journal-log-storage.md)
- [猫族关系机制细节](../internals/cat-favor-mechanics.md)
- [开发流程](../../guides/development.md)
