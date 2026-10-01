# 猫族关系机制细节

> 猫族羁绊的行为数值与冷却、被动能力的每 tick 评估与位掩码、以及玩家状态的持久化与迁移。
> 本文件不参与任务导航，只被 [猫族关系系统](../subsystems/cat-favor.md) 链接。**新增恩惠能力或灵体职责只需要读那篇**；只有调整行为数值、被动评估口径或持久化字段时才读本文件。

## 1. 羁绊累积与惩罚

[`CatFavorAction`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatFavorAction.java) 枚举统一管理所有行为。

### 1.1 正向行为（关系建立后生效，独立冷却）

| 行为 | 增量 | 冷却 | 触发场景 |
|---|---|---|---|
| `FEED_CAT` | +5 | 12000t | 喂食猫（延迟到 tick 末结算，驯服时取消） |
| `TAME_CAT` | +10 | 12000t | 成功驯服一只猫 |
| `SLEEP_WITH_CAT` | +20 | 6000t | 与驯服的猫一同入睡 |
| `SIT_ON_BLOCK` | +10 | 24000t | 驯服的猫坐在床/箱子/熔炉上持续 30s |
| `REPEL_RAID` | +20 | 12000t | 获得村庄英雄效果（击退袭击上升沿） |

正向行为经 `tryAccumulate` 处理：校验关系已建立 → 校验冷却 → `addCatBond` → `markTriggered` → 显示变化 + 同步。

### 1.2 惩罚行为（不要求持有猫之手，无冷却）

| 行为 | 增量 | 触发 |
|---|---|---|
| `HIT_CAT` | -5 | 玩家对猫造成伤害 |
| `OWN_CAT_DEATH` | -10 | 玩家所属驯服猫死亡 |
| `KILL_CAT` | -50 | 玩家杀死猫 |

**击杀去重**：`onKillCat` 时检查是否同 tick 已扣过 `HIT_CAT` 的 5 分（`consumeMatchingCatHit`），若已扣则只补扣 45 分，避免一次击杀双重惩罚。

### 1.3 喂食奖励的延迟结算

喂食与驯服可能由同一次交互触发（喂食后驯服成功）。为避免重复奖励：

- 喂食时 `queueFeedReward` 标记待结算，延迟到 tick 末。
- 成功驯服时 `cancelPendingFeedReward` 取消待结算的喂食奖励。
- `serverTick` 末尾 `consumePendingFeedReward` 取出并结算。

## 2. 被动能力评估

[`CatPassiveAbilities.serverTick`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatPassiveAbilities.java) 每玩家每 tick 评估，**服务端权威**：

```
serverTick(player)
  ├─ updateAbilityMask     计算能力位掩码，缓存到 state
  ├─ updateStepHeight      柔软肉垫：步高修饰符 +0.65（潜行时不应用）
  ├─ updateNightVision     猫的眼：分级检测夜视
  ├─ detectRaidVictory     击退袭击：村庄英雄上升沿检测
  └─ grantNineLivesOnCap   首次成为挚友时授予 1 命
```

### 2.1 能力位掩码

为避免 mixin 高频查询时反复扫描背包与计算阈值，每 tick 计算一次位掩码缓存到 `CatFavorState.abilityMask`：

```
FLAG_DETERRENCE              威慑
FLAG_LIGHT_STEP_TRAMPLE      轻步-耕地
FLAG_LIGHT_STEP_NO_PRESSURE  轻步-压力板/绊线
FLAG_SOFT_PAWS               柔软肉垫
```

mixin 通过 `hasActiveDeterrence(player)` / `hasLightStep(player)` 等静态方法廉价查询位掩码，无需库存扫描。

### 2.2 夜视分级检测

`猫的眼` 用分级检测降低高频亮度查询开销：

- **空闲态**：每 20t（1s）检测一次是否进入黑暗（`getMaxLocalRawBrightness < 9`）。
- **激活态**：授予 320t（16s）夜视，每 100t（5s）刷新一次。
- 条件不再满足时不再续期，夜视自然过期，回到空闲态高频侦测。

### 2.3 mixin 查询接口

`CatPassiveAbilities` 提供多个静态方法供 mixin 调用：

- `hasActiveDeterrence` / `hasPhantomDeterrence`：苦力怕回避、幻翼不生成/不索敌
- `hasLightStep` / `hasLightStepPressurePlateIgnored`：耕地不退化、压力板/绊线忽略
- `hasSoftPaws`：摔落减伤
- `isNineLivesInvulnerable`：九命无敌窗口（由 `CAT_FAVOR` 效果驱动）
- `canSummonAncientGift` / `canTriggerNineLives`：往礼与九命触发条件

## 3. 状态持久化与迁移

[`CatFavorState`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/state/CatFavorState.java) 通过 mixin 附加到玩家 NBT：

**持久化字段**（随存档保存）：`relationshipEstablished`、`catBond`（0-100）、`lastTriggerGameTime`（各行为冷却 `EnumMap<CatFavorAction, Long>`）、`deterrenceDisabled` / `lightStepDisabled`（偏好开关）、`nineLivesCount`（0-9）、`initialBestFriendLifeGranted`。

**瞬态字段**（不序列化，重生后重置）：`abilityMask`、`hadHeroEffect`、`nightVisionCheckCooldown`、`pendingFeedReward`、`recentlyHitCatUuid` / `GameTime`、`activeMessengerUuid` / `activeSwordsmanUuid`。

**数据迁移**：`CURRENT_DATA_VERSION = 2`，`readLegacy` 处理旧 `favor` 格式（冷却清空，旧压力板偏好映射为轻步总开关）。

**重生保留**：`copyFrom` 复制持久化字段，不复制瞬态字段。关系与偏好跨重生保留。
