# 战利品追踪核心数据流

> 战利品发现如何被追踪、结算并解锁目录条目的完整链路：追踪上下文、三个追踪服务、事件总线、内建订阅者与结算策略。
> 本文件不参与任务导航，只被 [考古笔记系统](../subsystems/journal.md) 链接。**新增追踪来源或订阅者时先读那篇的扩展点**；只有在改动追踪上下文、结算时机或订阅者优先级时读本文件。

## 1. 追踪上下文

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

## 2. 三个追踪服务

| 服务 | 触发场景 | 说明 |
|---|---|---|
| `DirectLootTrackingService` | 钓鱼、附魔战利品等直接进背包 | 物品立即归玩家所有 |
| `ContainerTrackingService` | 开箱（箱子、箱子矿车、埋藏宝藏等） | 通过 `MenuTrackingSnapshot` 比对开箱前后差异 |
| `DecoratedPotTrackingService` | 陶罐 | 类似容器，但走陶罐专属路径 |

容器追踪的关键是 `MenuTrackingSnapshot`：记录玩家打开菜单时的物品快照，关闭时比对差异，确定实际取走了哪些物品。方块容器从 `RandomizableContainer#unpackLootTable` 接入；箱子矿车等实体容器从 `ContainerEntity#unpackChestVehicleLootTable` 接入，两者共用 `LOOT_CONTAINER` 分类和 `ContainerTrackingService`。**实体容器的追踪状态保存在实体 NBT 中，真正销毁时结算，区块卸载不会提前清除。**

Lootr 奖励箱矿车不走原版实体容器填充方法，而是通过 `DefaultLootFiller` 创建每玩家 `LootrInventory`；现有 Lootr 兼容 Mixin 在该通用 filler 上接入，因此奖励箱矿车与 Lootr 方块容器使用同一条 `LOOT_CONTAINER` 追踪链，**不需要矿车专用分类或重复钩子**。

## 3. 事件总线

追踪入口在 loot roll 结束后通过 `LootTrackingEvents.submit` 提交结果：

```
LootTrackingEvents.submit(session, itemCounts, settlementStrategy)
  ├─ session.complete(itemCounts) -> LootSession.Commit
  │     Commit 含 rootContext + discoveredLoot + tableStack
  ├─ resolveTrackingState(player, rootTableId)
  │     仅当 rootTableId 被 catalog 收录时才继续（isTrackedTable）
  └─ dispatch(LootDiscoveredEvent)  按 priority 顺序同步通知所有订阅者
```

## 4. 内建订阅者

`LootTrackingBootstrap.registerListeners()` 注册 5 个订阅者，按优先级顺序执行：

| 优先级 | 订阅者 | 职责 |
|---|---|---|
| 100 | `onUnlock` | `ArchaeologyLootRuntimeTracker.unlockSession` 解锁表与物品 |
| 200 | `onRecordFirstUnlock` | `JournalLogRecorder.recordFirstUnlock` 记录首次发现元数据 |
| 250 | `onSettle` | `settlementStrategy.settle` 结算（立即写日志或创建待定日志） |
| 300 | `onCheckChallenge` | `ArchaeologyChallengeChecker.checkAndGrant` 考古挑战成就 |
| 350 | `onCheckCompletionReward` | `JournalCompletionRewardChecker.checkAndReward` 100% 完成奖励 |

> **顺序约束**：解锁（100）必须先于成就与完成奖励检查（300/350），否则检查时进度未更新。优先级数字越小越先执行。

## 5. 结算策略

`LootSettlementStrategies` 提供两种策略：

| 策略 | `recordsItemsImmediately` | 行为 | 适用场景 |
|---|---|---|---|
| `immediate()` | true | 生成时立即记录获取数量与最终日志 | 钓鱼等直接进背包 |
| `deferred(consumer)` | false | 生成时只创建待定日志，实际取出后再更新 | 容器/可疑方块暂存（物品可能被丢弃） |

延迟结算解决的问题是：玩家打开容器后可能不取走物品，或可疑方块刷出的物品可能被丢弃。待定日志暂存在容器/方块实体上，物品实际进入玩家所有权后才转为正式日志。
