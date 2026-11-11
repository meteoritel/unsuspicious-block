# 战利品追踪核心数据流

> 战利品发现如何被追踪、结算并解锁目录条目的完整链路：追踪上下文、捕获与宿主服务、命名结算链、兼容订阅与宿主 pending。
> 本文件不参与任务导航，只被 [考古笔记系统](../subsystems/journal.md) 链接。**新增追踪来源时先读那篇的扩展点**；只有在改动追踪上下文、结算时机或订阅者优先级时读本文件。

## 1. 追踪上下文

[`LootTrackingContext`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/journal/tracking/LootTrackingContext.java) 是不可变 record，描述当前追踪的根表与子表链路：

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

## 2. 捕获与宿主服务

| 服务 | 触发场景 | 说明 |
|---|---|---|
| `LootCapture` | 钓鱼、淘洗、附魔、刷拭、容器与陶罐 | 统一资格、签名、会话和作用域 |
| `ContainerTrackingService` | 开箱（箱子、箱子矿车、埋藏宝藏等） | 通过 `MenuTrackingSnapshot` 比对开箱前后差异 |
| `DecoratedPotTrackingService` | 陶罐 | 类似容器，但走陶罐专属路径 |

`LootCapture.capture(source, target, generator)` 在作用域内执行原生成逻辑，正常返回后提交；`submitFinal` 接收已生成结果。跨 Mixin 相位使用 `CaptureHandle`：`roll` 仅包实际生成，`complete` 只允许一次，`close` 中止未提交捕获。无玩家或未收录时仍执行生成。嵌套的同玩家、同来源子表复用父会话，只汇入发现，根入口提交最终数量；NeoForge 钓鱼 GLM 采用此路径。刷拭保留原触发时间。

容器追踪的关键是 `MenuTrackingSnapshot`：记录玩家打开菜单时的物品快照，关闭时比对差异，确定实际取走了哪些物品。方块容器从 `RandomizableContainer#unpackLootTable` 接入；箱子矿车等实体容器从 `ContainerEntity#unpackChestVehicleLootTable` 接入，两者共用 `LOOT_CONTAINER` 分类和 `ContainerTrackingService`。**实体容器的追踪状态保存在实体 NBT 中，真正销毁时结算，区块卸载不会提前清除。**

Lootr 奖励箱矿车不走原版实体容器填充方法，而是通过 `DefaultLootFiller` 创建每玩家 `LootrInventory`；现有 Lootr 兼容 Mixin 在该通用 filler 上接入，因此奖励箱矿车与 Lootr 方块容器使用同一条 `LOOT_CONTAINER` 追踪链，**不需要矿车专用分类或重复钩子**。

## 3. 事件总线

追踪入口在 loot roll 结束后通过 `LootTrackingEvents.submit` 提交结果：

```
LootTrackingEvents.submit(session, itemCounts, settlementTarget)
  ├─ session.complete(itemCounts) -> LootSession.Commit
  │     Commit 含 rootContext + discoveredLoot + tableStack
  ├─ resolveTrackingState(player, rootTableId)
  │     仅当 rootTableId 被 catalog 收录时才继续（tracked）
  └─ LootTrackingBootstrap.run(event)  命名步骤与兼容回调同步执行
```

## 4. 命名结算链与兼容订阅

`LootTrackingBootstrap.run` 顺序调用解锁、首次元数据、目标结算、挑战和完成奖励。内建链无需初始化；`registerListeners` 仅记录旧扩展相对 common 初始化的注册位置。

| 命名步骤 | 行为 | 兼容 priority 位置 |
|---|---|---|
| UNLOCK | `ArchaeologyLootRuntimeTracker.unlockSession` 解锁表与物品 | 100 |
| FIRST_UNLOCK | `JournalLogHandler.recordFirstUnlock` 记录根表首次发现 | 200 |
| SETTLE | 按 `Immediate / Deferred` 立即写入或创建 pending | 250 |
| CHALLENGE | `ArchaeologyChallengeChecker.checkAndGrant` | 300 |
| COMPLETION_REWARD | `JournalCompletionRewardChecker.checkAndReward` | 350 |

已弃用的 `LootTrackingEvents.register(priority, listener)` 保留旧扩展的兼容 Seam：低 priority 先执行，同值按注册顺序。与内建步骤同值的回调，根据是否早于 common 初始化注册，保留在对应步骤前或后。`LegacyLootSubscriptions` 只解释这些兼容位置，内建步骤由命名调用直接表达。

任何内建步骤或兼容回调失败均立即向提交方传播原异常，日志及 suppressed context 包含步骤、根表和玩家；后续奖励停止，不自动重放。

## 5. 结算目标

`LootSettlementTarget` 使用 sealed 形态表达当前结算目的地：

| 目标 | 行为 | 适用场景 |
|---|---|---|
| `IMMEDIATE` | 生成时记录获取数量与最终日志 | 钓鱼、淘洗、化石猎手与陶罐 |
| `Deferred(PendingTarget)` | 生成时发现并暂存期望日志，实际领取后累计取得量 | 容器与可疑方块 |

`PendingTarget.Brush` 暂存非空日志；`PendingTarget.Container` 配套更新 pending、最终余量和 owner，空结果清除容器追踪。来源类型仅负责展示与序列化。

延迟结算解决的问题是：玩家打开容器后可能不取走物品，或可疑方块刷出的物品可能被丢弃。待定日志暂存在容器/方块实体上，物品实际进入玩家所有权后才转为正式日志。

## 6. 宿主状态与终结

`PendingJournalHost` 表示宿主支持待定日志；`PendingJournal.current()==null` 表示当前为空。封存方块只实现扫描/物品能力。`PendingJournal` 集中暂存、实际取得量合并、终结与清理，合并保持 entryId；提交成功后才更新内存或清除。

`ContainerLootTracking` 同时拥有表、owner、只读余量视图与 pending，方块容器、矿车和 Lootr 每玩家库存共享原 NBT codec。菜单先计划双箱间的分配，成功提交实际取得量后消费余量。库存校正只缩减余量，不提前清 pending；覆盖、校正、超时和真正销毁经 `ContainerTrackingService.terminate` 终结。`finish` 仅写日志最终形态，取得计数只在增量阶段累计。真正销毁时所有者离线沿用放弃条目的规则；区块卸载继续保存宿主 NBT。

`BrushableTracking` 共享普通与 Lootr 可疑方块的扫描、表解析及 pending；`ScanIdentity` 兼容普通标记+UUID 与封存方块 ScannerUuid。普通标记为 true 且缺 UUID 时仍呈现已扫描、无扫描者；不自动修复异常组合。Lootr 的每玩家库存、扫描后回退、原版物品访问、临时刷拭工具和时间留在宿主 Adapter。

原 unsuspiciousblock_tracked_loot_*、unsuspiciousblock_pending_journal_entry 与扫描/表解析键直接读写。缺失或空余量使当前表为空，owner 与 pending 独立恢复；待定日志的原 tableStack 可提供已清余量的终结根表。
