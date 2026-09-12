# 猫族关系系统

本文档描述 `cat/` 包的架构：玩家与猫族之间的长期关系如何建立、累积、惩罚，以及由关系派生的被动能力（猫之恩惠）、灵体猫（信使/剑士/商人）如何被召唤与管理。

> 玩法设计与领域术语见 [`docs/cat-bond-design.md`](../cat-bond-design.md)、[`docs/spirit-cat-npc-design.md`](../spirit-cat-npc-design.md) 与 [`CONTEXT.md`](../../CONTEXT.md)。本文聚焦代码实现。

## 1. 职责概述

猫族关系系统是模组第二条玩法主线，承担：

1. **关系建立**：玩家从猫国结构发现猫之手后绑定信物，建立与猫族的关系（结构自然生成当前停用，见 [实体与世界生成](entities-world.md) 第 9 节）。
2. **羁绊累积**：正向行为（喂食、驯服、共眠等）累积羁绊，惩罚行为（击打、杀猫）扣减。
3. **恩惠能力**：羁绊达到阈值解锁被动能力（猫的眼、威慑、轻步、柔软肉垫、古国往礼、九命）。
4. **灵体猫管理**：召唤并管理猫猫信使、剑士猫猫、猫猫商人三类灵体实体。
5. **状态持久化**：通过 mixin 附加到玩家 NBT，重生时保留关系与偏好。

## 2. 子包结构

```
cat/
├── CatFavorManager          核心管理器（累积/惩罚/同步/信物绑定）
├── CatFavorAction           行为枚举（favorDelta + cooldownTicks + penalty）
├── CatFavorAbility          恩惠能力枚举（阈值 + 本地化键）
├── CatBondStage             羁绊阶段枚举（0-100 映射 6 阶段 + HUD 颜色）
├── CatPassiveAbilities      被动能力评估中枢（每 tick 计算 + mixin 查询）
├── CatGiftService           古国往礼（召唤信使猫送礼）
├── SwordsmanCatService      剑士猫召唤（九命触发）
├── CatNetworkHandler        网络处理（C2S 开关切换）
├── SpiritCatDebugRegistry   调试用预览灵体注册表
├── adapter/
│   └── ICatEventAdapter     平台事件适配 SPI（注册玩家死亡/杀猫/登录等）
├── state/
│   ├── CatFavorState        持久化状态（关系/羁绊/冷却/偏好/九命）
│   └── CatFavorStateHolder  mixin 接口，附加到 ServerPlayer
└── merchant/
    ├── MerchantCatSpawner        猫猫商人定时生成（村庄内）
    ├── MerchantCatSpawnData      生成数据
    ├── MerchantCatTradeDefinition  单条交易定义（输入/输出/次数/权重/条件）
    ├── MerchantCatTradePool      交易池
    ├── MerchantCatTradeManager   交易加载与管理
    └── TaggedMerchantOffer       支持标签输入的交易 offer
```

## 3. 关系建立与信物绑定

关系建立由 [`CatFavorManager.serverTick`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatFavorManager.java) 每 tick 驱动，**必须先于被动能力评估**执行：

```
serverTick(player)
  ├─ 若玩家已拥有绑定的猫之手（hasOwnedHandOfCat）
  │     -> state.establishRelationship() 建立关系
  └─ 否则在背包中查找未绑定的猫之手
        -> HandOfCatItem.bindTo(stack, player) 绑定
        -> establishRelationship() 建立关系
  └─ 若有待结算的喂食奖励（consumePendingFeedReward）
        -> tryAccumulate(FEED_CAT)
```

**信物绑定规则**（见 [`HandOfCatItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/HandOfCatItem.java)）：

- 猫之手与玩家 UUID 绑定，他人可持有但无法使用。
- 已随身携带本人信物的玩家再次取得空白猫之手时，信物保持未绑定，供转赠。
- 关系建立后**不依赖信物是否随身携带**，但猫之恩惠的被动能力需要"本人猫之手"在场才生效。

`hasOwnedHandOfCat` 通过 `InventoryPresenceRegistry.containsMatching` 检查玩家个人携带范围（含标本箱等便携容器），而非仅主背包。

## 4. 羁绊累积与惩罚

[`CatFavorAction`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatFavorAction.java) 枚举统一管理所有行为：

### 4.1 正向行为（关系建立后生效，独立冷却）

| 行为 | 增量 | 冷却 | 触发场景 |
|---|---|---|---|
| `FEED_CAT` | +5 | 12000t | 喂食猫（延迟到 tick 末结算，驯服时取消） |
| `TAME_CAT` | +10 | 12000t | 成功驯服一只猫 |
| `SLEEP_WITH_CAT` | +20 | 6000t | 与驯服的猫一同入睡 |
| `SIT_ON_BLOCK` | +10 | 24000t | 驯服的猫坐在床/箱子/熔炉上持续 30s |
| `REPEL_RAID` | +20 | 12000t | 获得村庄英雄效果（击退袭击上升沿） |

正向行为通过 `tryAccumulate` 处理：校验关系已建立 -> 校验冷却 -> `addCatBond` -> `markTriggered` -> 显示变化 + 同步。

### 4.2 惩罚行为（不要求持有猫之手，无冷却）

| 行为 | 增量 | 触发 |
|---|---|---|
| `HIT_CAT` | -5 | 玩家对猫造成伤害 |
| `OWN_CAT_DEATH` | -10 | 玩家所属驯服猫死亡 |
| `KILL_CAT` | -50 | 玩家杀死猫 |

**击杀去重**：`onKillCat` 时检查是否同 tick 已扣过 `HIT_CAT` 的 5 分（`consumeMatchingCatHit`），若已扣则只补扣 45 分，避免一次击杀双重惩罚。

### 4.3 喂食奖励的延迟结算

喂食与驯服可能由同一次交互触发（喂食后驯服成功）。为避免重复奖励：

- 喂食时 `queueFeedReward` 标记待结算，延迟到 tick 末。
- 成功驯服时 `cancelPendingFeedReward` 取消待结算的喂食奖励。
- `serverTick` 末尾 `consumePendingFeedReward` 取出并结算。

## 5. 恩惠能力与阶段

### 5.1 羁绊阶段

[`CatBondStage`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatBondStage.java) 把 0-100 的连续羁绊映射为 6 个阶段，每个阶段带 HUD 颜色与翻译键：

| 阶段 | 羁绊范围 | HUD 颜色 |
|---|---|---|
| 初识 ACQUAINTED | 0-19 | 灰 |
| 亲近 CLOSE | 20-39 | 绿 |
| 信赖 TRUSTED | 40-59 | 青 |
| 眷顾 FAVORED | 60-79 | 黄 |
| 猫国贵客 HONORED_GUEST | 80-99 | 红 |
| 猫国挚友 BEST_FRIEND | 100 | 紫 |

阶段不保留历史最高，实时根据当前羁绊计算。

### 5.2 恩惠能力

[`CatFavorAbility`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatFavorAbility.java) 定义 6 项能力及其解锁阈值：

| 能力 | 阈值 | 效果 |
|---|---|---|
| 猫的眼 CAT_EYE | 0 | 黑暗中获得夜视 |
| 猫之威慑 DETERRENCE | 20 | 苦力怕与幻翼回避玩家 |
| 轻步 LIGHT_STEP | 40 | 不踩坏耕地、不触发压力板与绊线 |
| 柔软肉垫 SOFT_PAWS | 60 | 减轻摔落伤害 + 步高提升 |
| 猫国往礼 ANCIENT_GIFT | 80 | 晨礼由猫猫信使送来特殊礼物 |
| 猫之九命 NINE_LIVES | 100 | 储存命数抵挡致命伤害 |

枚举声明顺序即 tooltip 展示顺序（由低阈值到高阈值）。威慑与轻步有服务端持久化的开关，由玩家通过按键切换。

## 6. 被动能力评估

[`CatPassiveAbilities.serverTick`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatPassiveAbilities.java) 每玩家每 tick 评估，**服务端权威**：

```
serverTick(player)
  ├─ updateAbilityMask     计算能力位掩码，缓存到 state
  ├─ updateStepHeight      柔软肉垫：步高修饰符 +0.65（潜行时不应用）
  ├─ updateNightVision     猫的眼：分级检测夜视
  ├─ detectRaidVictory     击退袭击：村庄英雄上升沿检测
  └─ grantNineLivesOnCap   首次成为挚友时授予 1 命
```

### 6.1 能力位掩码

为避免 mixin 高频查询时反复扫描背包与计算阈值，每 tick 计算一次位掩码缓存到 `CatFavorState.abilityMask`：

```
FLAG_DETERRENCE              威慑
FLAG_LIGHT_STEP_TRAMPLE      轻步-耕地
FLAG_LIGHT_STEP_NO_PRESSURE  轻步-压力板/绊线
FLAG_SOFT_PAWS               柔软肉垫
```

mixin 通过 `hasActiveDeterrence(player)` / `hasLightStep(player)` 等静态方法廉价查询位掩码，无需库存扫描。

### 6.2 夜视分级检测

`猫的眼` 采用分级检测降低高频亮度查询开销：

- **空闲态**：每 20t（1s）检测一次是否进入黑暗（`getMaxLocalRawBrightness < 9`）。
- **激活态**：授予 320t（16s）夜视，每 100t（5s）刷新一次。
- 条件不再满足时不再续期，夜视自然过期，回到空闲态高频侦测。

### 6.3 mixin 查询接口

`CatPassiveAbilities` 提供多个静态方法供 mixin 调用：

- `hasActiveDeterrence` / `hasPhantomDeterrence`：苦力怕回避、幻翼不生成/不索敌
- `hasLightStep` / `hasLightStepPressurePlateIgnored`：耕地不退化、压力板/绊线忽略
- `hasSoftPaws`：摔落减伤
- `isNineLivesInvulnerable`：九命无敌窗口（由 `CAT_FAVOR` 效果驱动）
- `canSummonAncientGift` / `canTriggerNineLives`：往礼与九命触发条件

## 7. 猫之九命

九命是最高阶段（100）的恩惠，流程见 [`CatPassiveAbilities.triggerNineLives`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatPassiveAbilities.java)：

```
triggerNineLives(player, source)   // 由 LivingEntityNineLivesMixin 在致命伤害时触发
  ├─ state.consumeOneLife()        消耗一条命（0 命时不触发）
  ├─ player.setHealth(maxHealth)   恢复满血
  ├─ 清除所有负面效果
  ├─ grantNineLivesProtection      授予保护效果（CAT_FAVOR 无敌 + 抗性 + 可选火抗）
  └─ SwordsmanCatService.summonOrRefresh  召唤剑士猫猫保护玩家
```

**命数规则**：

- 首次成为挚友时授予 1 命（`grantNineLivesOnCap`，`initialBestFriendLifeGranted` 标记防重复）。
- 猫猫信使每次成功送礼补充 1 命（`onMessengerGiftDelivered`，上限 9）。
- 羁绊跌破 100 时**全部命数清空**（`setCatBond` 中 `catBond < MAX_FAVOR -> nineLivesCount = 0`）。
- 每名玩家同时最多一只剑士猫（`activeSwordsmanUuid` 去重）。

## 8. 古国往礼（猫猫信使）

[`CatGiftService.tryGhostGift`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatGiftService.java) 在玩家触发原版晨礼时调用：

```
tryGhostGift(owner, cat)   // 引礼者 cat 触发晨礼
  ├─ canSummonAncientGift(owner)  校验：持有猫之手 + 羁绊≥80
  ├─ 检查是否已有活跃信使（activeMessengerUuid 去重）
  ├─ 创建 MessengerCat，定位到玩家附近
  ├─ assignBehavior(MorningGiftBehavior)  分配送礼职责
  ├─ activateDuty(lifetime)        激活职责（最长现世时间）
  └─ setActiveMessengerUuid        记录活跃信使
```

- 引礼者（触发晨礼的羁绊猫）只影响开场注视，**配送目标始终为玩家**。
- 信使礼物使用 `gameplay/cat/ghost_gift` 战利品表（数据驱动）。
- 每名玩家同时最多一只信使。
- 信使送礼成功时通过 `CatFavorManager.onMessengerGiftDelivered` 补充九命（仅羁绊=100 时）。

实体细节（MessengerCat 的 AI、阶段、行为）见 [实体与世界生成](entities-world.md)。

## 9. 猫猫商人

`cat/merchant/` 子包实现猫国挚友阶段的商人系统：

- [`MerchantCatSpawner`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/merchant/MerchantCatSpawner.java)：服务端 tick 末尾由平台入口驱动（`MerchantCatSpawner.tick(server)`），在符合条件的玩家周围村庄生成商人。
- [`MerchantCatTradeManager`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/merchant/MerchantCatTradeManager.java)：从 `data/unsuspiciousblock/merchant_cat_trades/` 加载交易定义（JSON，支持输入/输出/次数/权重/条件）。
- [`TaggedMerchantOffer`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/merchant/TaggedMerchantOffer.java)：支持物品标签作为交易输入（如 `#unsuspiciousblock:random/discs`），通过 `random/*` 包装 tag 实现随机陶片/唱片/盔甲纹饰模板。
- 商人主要收取古代金币，也提供少量高成本、严格限量的古代金币交易。

## 10. 状态持久化与迁移

[`CatFavorState`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/state/CatFavorState.java) 通过 mixin 附加到玩家 NBT：

**持久化字段**（随存档保存）：
- `relationshipEstablished`：关系是否建立
- `catBond`：0-100 羁绊值
- `lastTriggerGameTime`：各行为冷却 `EnumMap<CatFavorAction, Long>`
- `deterrenceDisabled` / `lightStepDisabled`：玩家偏好开关
- `nineLivesCount`：0-9 命数
- `initialBestFriendLifeGranted`：首次挚友奖励标记

**瞬态字段**（不序列化，重生后重置）：
- `abilityMask`：能力位掩码缓存
- `hadHeroEffect`：村庄英雄上升沿检测
- `nightVisionCheckCooldown`：夜视检测冷却
- `pendingFeedReward`：待结算喂食奖励
- `recentlyHitCatUuid/GameTime`：击杀去重
- `activeMessengerUuid` / `activeSwordsmanUuid`：活跃灵体去重

**数据迁移**：`CURRENT_DATA_VERSION = 2`，`readLegacy` 处理旧 `favor` 格式（冷却清空，旧压力板偏好映射为轻步总开关）。

**重生保留**：`copyFrom` 复制持久化字段，不复制瞬态字段。关系与偏好跨重生保留。

## 11. 平台适配与网络

### 11.1 平台事件适配

[`ICatEventAdapter`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/adapter/ICatEventAdapter.java) 是 SPI 接口（见 [平台抽象](platform-abstraction.md)），由 `FabricCatEventAdapter` / `NeoForgeCatEventAdapter` 实现，注册玩家死亡、杀猫、登录等平台事件，路由到 `CatFavorManager`。

### 11.2 网络同步

- **S2C**：`SyncCatFavorPayload` 同步羁绊值、命数、关系状态，供 tooltip 与 HUD 显示。`CatFavorManager.sync` 在状态变更时调用。
- **C2S**：`CatDeterrenceTogglePayload` / `CatLightStepTogglePayload` 处理玩家按键切换威慑/轻步开关，由 `CatPassiveAbilities.onDeterrenceToggle` / `onLightStepToggle` 处理。

网络细节见 [网络与同步](network.md)。

## 12. Mixin 依赖

猫族系统依赖多个 mixin（见 [mixin.md](mixin.md)）：

- `catfavor/` 包：`CatFeedMixin`（喂食）、`CatRelaxOnOwnerGoalMixin`（共眠）、`CatSitOnBlockGoalMixin`（坐方块）、`CreeperAvoidCatFavorMixin`（苦力怕回避）、`PhantomSpawnerMixin` / `PhantomTargetGoalMixin`（幻翼）、`FarmBlockTrampleMixin`（耕地）、`LivingEntityFallDamageMixin`（摔落）、`LivingEntityNineLivesMixin`（九命触发）、`PlayerCatFavorStateMixin` / `ServerPlayerCatFavorStateMixin`（状态附加）、`TamableAnimalTameMixin`（驯服）、`EntityBlockTriggerMixin`（压力板/绊线）等。
- `PlayerHurtInvulnMixin`：九命无敌窗口的伤害豁免。

## 13. 扩展点

- **新增正向行为**：在 `CatFavorAction` 加枚举值（增量、冷却、是否惩罚），在对应事件/mixin 中调 `CatFavorManager.tryAccumulate`。
- **新增恩惠能力**：在 `CatFavorAbility` 加枚举值（阈值），在 `CatPassiveAbilities.serverTick` 实现能力逻辑，如需 mixin 高频查询则加位掩码 flag。
- **新增灵体职责**：参考 `MessengerCat` / `SwordsmanCat`，继承 `SpiritCat`，实现 AI Goal/Behavior，在 `ModEntities` 注册。预览灵体需注册到 `SpiritCatDebugRegistry`。
- **新增商人交易**：在 `data/unsuspiciousblock/merchant_cat_trades/` 添加 JSON。
- **配置调优**：灵体生命周期、效果时长通过 `ISpiritCatConfig` 调整（见 [配置与第三方联动](config-integrations.md)）。

## 14. 相关文档

- [实体与世界生成](entities-world.md) - 灵体猫实体与 AI
- [网络与同步](network.md) - `SyncCatFavorPayload` 等
- [Mixin 总览](mixin.md) - `catfavor/` 包的注入点
- [配置与第三方联动](config-integrations.md) - `ISpiritCatConfig`
- [docs/cat-bond-design.md](../cat-bond-design.md) - 羁绊玩法设计
- [docs/spirit-cat-npc-design.md](../spirit-cat-npc-design.md) - 灵体猫 NPC 设计
