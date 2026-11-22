# 战利品追踪核心数据流

> 战利品发现从生成捕获到结算入库的完整内部链路：追踪上下文、捕获与宿主服务、命名结算链、兼容订阅及宿主待定状态。
> 本文件不参与主任务导航，由[考古笔记系统](../subsystems/journal.md)按需链接。新增普通追踪来源请优先阅读该篇的「扩展点」。

## 1. 追踪上下文 (`LootTrackingContext`)

[`LootTrackingContext`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/journal/tracking/LootTrackingContext.java) 是贯穿单次抽取的不可变 record，通过 `LootTrackingContextHolder`（`ThreadLocal`）在抽取前写入：

| 字段 | 含义 |
|---|---|
| `player` | 触发本次获取的玩家 |
| `rootTableId` | 本次追踪锚定的根表 ID（签名解析与解锁归属均以此表为准） |
| `lootSource` | 战利品来源类型（钓鱼、开箱、刷拭、陶罐、淘洗、化石猎手等） |
| `gameTime` / `dayTime` | 触发时刻的游戏时间 |
| `tableStack` | 从根表到当前子表的完整引用栈；遇到嵌套调用时由 `descend(childTableId)` 派生 |
| `pos` | 触发坐标（鱼漂、容器或方块位置） |
| `sourceBlockId` | 源方块 ID（刷拭等场景使用） |

当父表在抽取中调用嵌套子表时，`NestedLootTableMixin` 自动识别活动上下文、派生子表栈并捕获子表物品产出。

## 2. 捕获入口与宿主服务

| 服务 | 覆盖场景 | 核心机制 |
|---|---|---|
| `LootCapture` | 钓鱼、淘洗、化石猎手、刷拭、容器与陶罐 | 统一资格判定、签名构建、`LootSession` 生命周期与 `ThreadLocal` 作用域 |
| `ContainerTrackingService` | 方块容器与实体容器开箱 | 借助 `MenuTrackingSnapshot` 比对玩家打开与关闭菜单时的槽位物品差值 |
| `DecoratedPotTrackingService` | 装饰陶罐 | 面向陶罐交互与破坏掉落的专属追踪路径 |

### 捕获与容器追踪细节

- **`LootCapture` 嵌套复用**：`capture(source, target, generator)` 在作用域内执行原生成函数并在返回后提交，`submitFinal` 接收外部已生成物品列表；跨多阶段 Mixin 时使用 `CaptureHandle`（`roll` 仅包住实际生成阶段，`complete` 至多提交一次，`close` 安全清理未提交捕获）。同玩家、同来源的嵌套子表抽取自动复用父会话（如 NeoForge 钓鱼 GLM 路径），由根入口提交最终合并数量；刷拭保留原触发时间。
- **方块与实体容器共链**：方块容器从 `RandomizableContainer#unpackLootTable` 接入，矿车等实体容器从 `ContainerEntity#unpackChestVehicleLootTable` 接入，两者共用 `LOOT_CONTAINER` 分类与 `ContainerTrackingService`。实体容器的追踪状态持久化于实体 NBT，仅在实体真正销毁时结算，区块卸载不提前清空。
- **Lootr 兼容链路**：普通箱类和箱子矿车在 `DefaultLootFiller#performFill` 中捕获原生 `LootTable#fill`，待 Lootr 清理筛选状态后提交最终库存。可疑方块只在抽取时捕获子表，待 Lootr 完成筛选并写槽后提交结果。菜单按同一次点击的来源减量、玩家背包与光标增量、待领取余量确认取得量。 [Lootr 追踪桥](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/plugin/lootr/LootrTrackingBridge.java) · [菜单快照](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/journal/tracking/MenuTrackingSnapshotService.java)

## 3. 事件总线与命名结算链

抽取结束后由 `LootTrackingEvents.submit` 驱动结算：

```mermaid
flowchart TD
    Submit["LootTrackingEvents.submit(session, itemCounts, settlementTarget)"] --> Complete["session.complete(itemCounts) — 生成 LootSession.Commit (含上下文、产物与表栈)"]
    Complete --> Check{"resolveTrackingState — rootTableId 是否被目录收录?"}
    Check -->|"未收录"| Skip["跳过结算"]
    Check -->|"已收录"| Chain["LootTrackingBootstrap.run(event) 顺序执行"]
    Chain --> S1["1. UNLOCK (100)：ArchaeologyLootRuntimeTracker.unlockSession"]
    S1 --> S2["2. FIRST_UNLOCK (200)：JournalLogHandler.recordFirstUnlock"]
    S2 --> S3["3. SETTLE (250)：立即写日志 或 暂存宿主 pending"]
    S3 --> S4["4. CHALLENGE (300)：ArchaeologyChallengeChecker.checkAndGrant"]
    S4 --> S5["5. COMPLETION_REWARD (350)：JournalCompletionRewardChecker.checkAndReward"]
```

### 兼容订阅与 fail-fast 语义

- **兼容优先序**：已弃用的 `LootTrackingEvents.register(priority, listener)` 保留按整数优先级（小值先执行）排序的扩展接口；与内建命名步骤同值的回调，依据其注册时间早于还是晚于 common 初始化而分别排在对应步骤之前或之后（由 `LegacyLootSubscriptions` 负责兼容映射）。
- **异常传播策略**：任一内建步骤或扩展回调抛出异常均立即中断并向提交方原样抛出，附加步骤名、根表与玩家上下文，后续奖励停止且不做隐式重放。

## 4. 结算目标 (`LootSettlementTarget`)

| 目标形态 | 结算行为 | 适用场景 |
|---|---|---|
| `IMMEDIATE` | 生成瞬间直接写入获取数量与最终日志条目 | 钓鱼、淘洗、化石猎手、装饰陶罐 |
| `Deferred(PendingTarget)` | 生成时在宿主暂存预期日志，直到玩家实际取走物品才累计取得量并转正 | 战利品容器（`PendingTarget.Container`）、可疑方块（`PendingTarget.Brush`） |

使用延迟结算的原因在于：玩家打开箱子后未必拿走全部物品，刷出的可疑方块物品也可能未被立即取走；暂存到宿主身上可保证日志真实反映玩家最终获得的战利品。

## 5. 宿主状态持久化与终结

- **原版待定日志 (`PendingJournalHost` / `PendingJournal`)**：普通容器和可疑方块在宿主暂存待定日志。容器用 `ContainerLootTracking` 保存所有者、表和余量。覆盖、超时及销毁由 `ContainerTrackingService` 终结。封存方块保留扫描身份和物品。
- **Lootr 库存 (`InventoryLootTracking`)**：随 Lootr 的库存 NBT 保存生成标识、实际命中的表和待领取余量，复用既有容器 NBT 字段。玩家或队伍共用的库存来源与个人日志独立。分次领取按同一生成 ID 在本人的日志会话中合并，保留已有备注。 [库存状态](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/journal/tracking/InventoryLootTracking.java)
- **扫描授权 (`ScanIdentity`)**：Lootr 方块持有扫描者和库存生成标识。考古铲验证该标识并从同一份已有库存取出物品。普通刷拭与考古铲使用同一库存来源结算。旧扫描记录具备扫描者、表和槽位内容时可迁入库存；需要恢复授权时使用扫描仪。 [Lootr 方块适配](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/mixin/compat/lootr/LootrBrushableBlockEntityMixin.java)
- **NBT 兼容性**：既有 `unsuspiciousblock_tracked_loot_*` 与 `unsuspiciousblock_pending_journal_entry` 键保持原样读写；即使余量已清空，待定日志内部携带的原 `tableStack` 仍可用于完成最终终结归属。

## 6. 相关文档

- [考古笔记系统](../subsystems/journal.md)
- [考古日志存储与迁移](journal-log-storage.md)
- [战利品表系统](../subsystems/loottable.md)
- [Mixin 边界与接线](../foundation/mixin.md)
