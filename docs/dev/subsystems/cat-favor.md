# 猫族关系系统

> `cat/` 包的架构：玩家与猫族之间的长期关系如何建立、累积、惩罚，以及由关系派生的被动能力（猫之恩惠）、灵体猫（信使/剑士/商人）如何被召唤与管理。
> 本文件是猫族关系子系统的唯一权威。灵体实体与 AI 见 [实体与 AI](entities-world.md)，Mixin 注入点清单见 [Mixin](../foundation/mixin.md)，网络 payload 清单见 [网络与同步](../foundation/network.md)。
>
> 玩法设计见 `docs/cat-bond-design.md` 与 `docs/spirit-cat-npc-design.md`。本文聚焦代码实现，涉及的领域术语在正文中就地定义。

## 1. 代码地图

| 层 | 位置 |
|---|---|
| 核心管理器 | `cat/CatFavorManager.java`（累积/惩罚/同步/信物绑定） |
| 数据枚举 | `cat/CatFavorAction.java`（行为：增量 + 冷却 + 是否惩罚）、`cat/CatFavorAbility.java`（恩惠能力 + 阈值）、`cat/CatBondStage.java`（6 阶段 + HUD 颜色） |
| 被动能力 | `cat/CatPassiveAbilities.java`（每 tick 评估 + 供 mixin 查询的静态方法） |
| 灵体服务 | `cat/CatGiftService.java`（古国往礼）、`cat/SwordsmanCatService.java`（九命触发时召唤剑士）、`cat/SpiritCatDebugRegistry.java`（调试预览灵体） |
| 商人 | `cat/merchant/`：`MerchantCatSpawner`、`MerchantCatTradeManager`、`MerchantCatTradeDefinition`、`MerchantCatTradePool`、`TaggedMerchantOffer`、`MerchantCatSpawnData` |
| 状态 | `cat/state/CatFavorState.java`（持久化状态）、`cat/state/CatFavorStateHolder.java`（mixin 接口） |
| 网络 | `cat/CatNetworkHandler.java`；payload：`SyncCatFavorPayload`（S2C）、`CatDeterrenceTogglePayload` / `CatLightStepTogglePayload`（C2S） |
| 数据资源 | `data/unsuspiciousblock/merchant_cat_trades/*.json`（交易定义）、`gameplay/cat/ghost_gift`（信使礼物战利品表） |
| 客户端 | `client/hud/CatFavorHud`（羁绊/HUD）、`client/state/HandOfCatClientState`（缓存 favor/lives）、`client/ui/toast/CatBondToast`、`item/HandOfCatItem` 的 tooltip |
| Mixin | `mixin/catfavor/` 下十余个：喂食、共眠、坐方块、驯服、苦力怕回避、幻翼威慑、耕地/压力板/绊线（轻步）、摔落减伤、九命触发与无敌豁免、玩家状态附加。全清单见 [Mixin](../foundation/mixin.md) |
| 平台差异 | `cat/adapter/ICatEventAdapter`（SPI）由 `FabricCatEventAdapter` / `NeoForgeCatEventAdapter` 实现，注册玩家死亡/杀猫/登录等平台事件 |

## 2. 数据流

```
平台事件（登录/死亡/杀猫/晨礼…）──► ICatEventAdapter ──┐
mixin 捕获（喂食/驯服/共眠/坐方块/击打）─────────────┤
                                                        ▼
                                          CatFavorManager.tryAccumulate / onKillCat …
玩家 tick ──► CatFavorManager.serverTick
                ├─ 关系建立（发现绑定的猫之手 → establishRelationship）
                └─ 待结算喂食奖励（consumePendingFeedReward）
玩家 tick ──► CatPassiveAbilities.serverTick（服务端权威）
                ├─ 能力位掩码 → CatFavorState.abilityMask
                ├─ 柔软肉垫步高修饰符 / 猫的眼分级夜视
                ├─ 击退袭击上升沿检测
                └─ 首次成为挚友授予 1 命
状态变更 ──► SyncCatFavorPayload（S2C）──► HandOfCatClientState ──► tooltip + CatFavorHud
按键 ──► CatDeterrenceTogglePayload / CatLightStepTogglePayload（C2S）──► CatPassiveAbilities.on*Toggle
```

**顺序约束**：关系建立与喂食结算（`CatFavorManager.serverTick`）**必须先于**被动能力评估（`CatPassiveAbilities.serverTick`）执行，否则本 tick 刚建立的羁绊不会生效。

## 3. 关键类

| 类 | 职责 |
|---|---|
| `cat/CatFavorManager` | 核心管理器：`tryAccumulate` / `onKillCat` / `serverTick` / `sync` / 信物绑定 |
| `cat/CatFavorAction` | 行为枚举：`favorDelta` + `cooldownTicks` + 是否惩罚 |
| `cat/CatFavorAbility` | 恩惠能力枚举：阈值 + 本地化键；**声明顺序即 tooltip 展示顺序**（由低阈值到高阈值） |
| `cat/CatBondStage` | 6 阶段枚举 + HUD 颜色；实时按当前羁绊计算，不保留历史最高 |
| `cat/CatPassiveAbilities` | 被动能力评估中枢：每 tick 计算位掩码，并提供供 mixin 廉价查询的静态方法 |
| `cat/CatGiftService` | 古国往礼：召唤信使猫送礼 |
| `cat/SwordsmanCatService` | 九命触发时召唤/刷新剑士猫 |
| `cat/CatNetworkHandler` | C2S 开关切换处理 |
| `cat/state/CatFavorState` | 持久化状态（关系/羁绊/冷却/偏好/九命）+ 瞬态字段 |
| `cat/merchant/MerchantCatSpawner` | 服务端 tick 末尾由平台入口驱动，在符合条件的玩家周围村庄生成商人 |
| `cat/merchant/MerchantCatTradeManager` | 从 `data/unsuspiciousblock/merchant_cat_trades/` 加载交易定义 |
| `cat/merchant/TaggedMerchantOffer` | 支持物品标签作为交易输入 |

## 4. 关系建立与信物绑定

关系建立由 [`CatFavorManager.serverTick`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatFavorManager.java) 每 tick 驱动：

```
serverTick(player)
  ├─ 若玩家已拥有绑定的猫之手（hasOwnedHandOfCat）
  │     -> state.establishRelationship()
  └─ 否则在背包中查找未绑定的猫之手
        -> HandOfCatItem.bindTo(stack, player)
        -> establishRelationship()
  └─ 若有待结算的喂食奖励（consumePendingFeedReward）-> tryAccumulate(FEED_CAT)
```

**信物绑定规则**（实现见 [`HandOfCatItem`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/item/HandOfCatItem.java)，物品条目见 [方块与物品](blocks-items.md)）：

- 猫之手与玩家 UUID 绑定，他人可持有但无法使用。
- 已随身携带本人信物的玩家再次取得空白猫之手时，信物保持未绑定，供转赠。
- 关系建立后**不依赖信物是否随身携带**，但猫之恩惠的被动能力需要"本人猫之手"在场才生效。

`hasOwnedHandOfCat` 通过 `InventoryPresenceRegistry.containsMatching` 检查玩家个人携带范围（含标本箱等便携容器），而非仅主背包。

## 5. 羁绊累积与惩罚

[`CatFavorAction`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatFavorAction.java) 枚举统一管理所有行为。

### 5.1 正向行为（关系建立后生效，独立冷却）

| 行为 | 增量 | 冷却 | 触发场景 |
|---|---|---|---|
| `FEED_CAT` | +5 | 12000t | 喂食猫（延迟到 tick 末结算，驯服时取消） |
| `TAME_CAT` | +10 | 12000t | 成功驯服一只猫 |
| `SLEEP_WITH_CAT` | +20 | 6000t | 与驯服的猫一同入睡 |
| `SIT_ON_BLOCK` | +10 | 24000t | 驯服的猫坐在床/箱子/熔炉上持续 30s |
| `REPEL_RAID` | +20 | 12000t | 获得村庄英雄效果（击退袭击上升沿） |

正向行为经 `tryAccumulate` 处理：校验关系已建立 → 校验冷却 → `addCatBond` → `markTriggered` → 显示变化 + 同步。

### 5.2 惩罚行为（不要求持有猫之手，无冷却）

| 行为 | 增量 | 触发 |
|---|---|---|
| `HIT_CAT` | -5 | 玩家对猫造成伤害 |
| `OWN_CAT_DEATH` | -10 | 玩家所属驯服猫死亡 |
| `KILL_CAT` | -50 | 玩家杀死猫 |

**击杀去重**：`onKillCat` 时检查是否同 tick 已扣过 `HIT_CAT` 的 5 分（`consumeMatchingCatHit`），若已扣则只补扣 45 分，避免一次击杀双重惩罚。

### 5.3 喂食奖励的延迟结算

喂食与驯服可能由同一次交互触发（喂食后驯服成功）。为避免重复奖励：

- 喂食时 `queueFeedReward` 标记待结算，延迟到 tick 末。
- 成功驯服时 `cancelPendingFeedReward` 取消待结算的喂食奖励。
- `serverTick` 末尾 `consumePendingFeedReward` 取出并结算。

## 6. 恩惠能力与阶段

### 6.1 羁绊阶段

[`CatBondStage`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatBondStage.java) 把 0-100 的连续羁绊映射为 6 个阶段：

| 阶段 | 羁绊范围 | HUD 颜色 |
|---|---|---|
| 初识 ACQUAINTED | 0-19 | 灰 |
| 亲近 CLOSE | 20-39 | 绿 |
| 信赖 TRUSTED | 40-59 | 青 |
| 眷顾 FAVORED | 60-79 | 黄 |
| 猫国贵客 HONORED_GUEST | 80-99 | 红 |
| 猫国挚友 BEST_FRIEND | 100 | 紫 |

### 6.2 恩惠能力

[`CatFavorAbility`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatFavorAbility.java) 定义 6 项能力及其解锁阈值：

| 能力 | 阈值 | 效果 |
|---|---|---|
| 猫的眼 CAT_EYE | 0 | 黑暗中获得夜视 |
| 猫之威慑 DETERRENCE | 20 | 苦力怕与幻翼回避玩家 |
| 轻步 LIGHT_STEP | 40 | 不踩坏耕地、不触发压力板与绊线 |
| 柔软肉垫 SOFT_PAWS | 60 | 减轻摔落伤害 + 步高提升 |
| 猫国往礼 ANCIENT_GIFT | 80 | 晨礼由猫猫信使送来特殊礼物 |
| 猫之九命 NINE_LIVES | 100 | 储存命数抵挡致命伤害 |

威慑与轻步有服务端持久化的开关，由玩家按键切换（C2S payload）。

## 7. 被动能力评估

[`CatPassiveAbilities.serverTick`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatPassiveAbilities.java) 每玩家每 tick 评估，**服务端权威**：

```
serverTick(player)
  ├─ updateAbilityMask     计算能力位掩码，缓存到 state
  ├─ updateStepHeight      柔软肉垫：步高修饰符 +0.65（潜行时不应用）
  ├─ updateNightVision     猫的眼：分级检测夜视
  ├─ detectRaidVictory     击退袭击：村庄英雄上升沿检测
  └─ grantNineLivesOnCap   首次成为挚友时授予 1 命
```

### 7.1 能力位掩码

为避免 mixin 高频查询时反复扫描背包与计算阈值，每 tick 计算一次位掩码缓存到 `CatFavorState.abilityMask`：

```
FLAG_DETERRENCE              威慑
FLAG_LIGHT_STEP_TRAMPLE      轻步-耕地
FLAG_LIGHT_STEP_NO_PRESSURE  轻步-压力板/绊线
FLAG_SOFT_PAWS               柔软肉垫
```

mixin 通过 `hasActiveDeterrence(player)` / `hasLightStep(player)` 等静态方法廉价查询位掩码，无需库存扫描。

### 7.2 夜视分级检测

`猫的眼` 用分级检测降低高频亮度查询开销：

- **空闲态**：每 20t（1s）检测一次是否进入黑暗（`getMaxLocalRawBrightness < 9`）。
- **激活态**：授予 320t（16s）夜视，每 100t（5s）刷新一次。
- 条件不再满足时不再续期，夜视自然过期，回到空闲态高频侦测。

### 7.3 mixin 查询接口

`CatPassiveAbilities` 提供多个静态方法供 mixin 调用：

- `hasActiveDeterrence` / `hasPhantomDeterrence`：苦力怕回避、幻翼不生成/不索敌
- `hasLightStep` / `hasLightStepPressurePlateIgnored`：耕地不退化、压力板/绊线忽略
- `hasSoftPaws`：摔落减伤
- `isNineLivesInvulnerable`：九命无敌窗口（由 `CAT_FAVOR` 效果驱动）
- `canSummonAncientGift` / `canTriggerNineLives`：往礼与九命触发条件

## 8. 猫之九命

九命是最高阶段（100）的恩惠，流程见 [`CatPassiveAbilities.triggerNineLives`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatPassiveAbilities.java)：

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

保护效果的时长由 `ISpiritCatConfig` 配置（见 [配置与第三方联动](../foundation/config-and-integrations.md)）。

## 9. 古国往礼（猫猫信使）

[`CatGiftService.tryGhostGift`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatGiftService.java) 在玩家触发原版晨礼时调用：

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
- 信使送礼成功时通过 `CatFavorManager.onMessengerGiftDelivered` 补充九命（仅羁绊 = 100 时）。

实体细节（`MessengerCat` 的 AI、阶段、行为）见 [实体与 AI](entities-world.md)。

## 10. 猫猫商人

`cat/merchant/` 子包实现猫国挚友阶段的商人系统：

- `MerchantCatSpawner`：服务端 tick 末尾由平台入口驱动（`MerchantCatSpawner.tick(server)`），在符合条件的玩家周围村庄生成商人。
- `MerchantCatTradeManager`：从 `data/unsuspiciousblock/merchant_cat_trades/` 加载交易定义（JSON，支持输入/输出/次数/权重/条件）。
- `TaggedMerchantOffer`：支持物品标签作为交易输入（如 `#unsuspiciousblock:random/discs`），通过 `random/*` 包装 tag 实现随机陶片/唱片/盔甲纹饰模板，并纳入 `c:` Conventional Tag。
- 商人主要收取古代金币，也提供少量高成本、严格限量的古代金币交易。

## 11. 状态持久化与迁移

[`CatFavorState`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/state/CatFavorState.java) 通过 mixin 附加到玩家 NBT：

**持久化字段**（随存档保存）：`relationshipEstablished`、`catBond`（0-100）、`lastTriggerGameTime`（各行为冷却 `EnumMap<CatFavorAction, Long>`）、`deterrenceDisabled` / `lightStepDisabled`（偏好开关）、`nineLivesCount`（0-9）、`initialBestFriendLifeGranted`。

**瞬态字段**（不序列化，重生后重置）：`abilityMask`、`hadHeroEffect`、`nightVisionCheckCooldown`、`pendingFeedReward`、`recentlyHitCatUuid` / `GameTime`、`activeMessengerUuid` / `activeSwordsmanUuid`。

**数据迁移**：`CURRENT_DATA_VERSION = 2`，`readLegacy` 处理旧 `favor` 格式（冷却清空，旧压力板偏好映射为轻步总开关）。

**重生保留**：`copyFrom` 复制持久化字段，不复制瞬态字段。关系与偏好跨重生保留。

## 12. 扩展点：新增行为 / 恩惠能力 / 灵体职责 / 交易

- **新增正向行为**：在 `CatFavorAction` 加枚举值（增量、冷却、是否惩罚），在对应事件/mixin 中调 `CatFavorManager.tryAccumulate`。
- **新增恩惠能力**：在 `CatFavorAbility` 加枚举值（阈值），在 `CatPassiveAbilities.serverTick` 实现能力逻辑；如需 mixin 高频查询则加位掩码 flag 并补一个静态查询方法。
- **新增灵体职责**：参考 `MessengerCat` / `SwordsmanCat`，继承 `SpiritCat`，实现 AI Goal/Behavior，在 `ModEntities` 注册；预览灵体需注册到 `SpiritCatDebugRegistry`（见 [实体与 AI](entities-world.md)）。
- **新增商人交易**：在 `data/unsuspiciousblock/merchant_cat_trades/` 添加 JSON，不需要改代码。
- **调整灵体生命周期与效果时长**：通过 `ISpiritCatConfig`（见 [配置与第三方联动](../foundation/config-and-integrations.md)）。

## 13. 约束与陷阱

- **顺序约束**：关系建立与喂食结算必须先于被动能力评估（同一 tick 内）。
- **`CatFavorAbility` 的枚举声明顺序 = tooltip 展示顺序**，插入新能力时注意位置。
- **羁绊跌破 100 会清空全部命数**，这是刻意设计，不要在"降到 99"时做保留处理。
- **威慑与轻步是服务端保存的玩家偏好**，客户端只提供 HUD 显示开关——不要做成本地（客户端）开关。
- **高频查询必须走位掩码**（`CatPassiveAbilities` 的静态方法），不要在 mixin 里直接扫背包。
- 信物绑定**不可覆盖**；已绑定信物不能被他人接管，遗失后需绑定新的空白信物。
- **猫之手结构的自然生成当前停用**（自 1.4.1 起），因此新玩家拿到猫之手的路径受限；相关结构见 [实体与 AI](entities-world.md)。

## 14. 相关文档

- [实体与 AI](entities-world.md) —— 灵体猫实体、AI 与猫之手结构
- [方块与物品](blocks-items.md) —— 猫之手物品与便携容器检测
- [网络与同步](../foundation/network.md) —— `SyncCatFavorPayload` 等
- [Mixin](../foundation/mixin.md) —— `catfavor/` 包的注入点
- [配置与第三方联动](../foundation/config-and-integrations.md) —— `ISpiritCatConfig`
- [客户端与 GUI](client-ui.md) —— `CatFavorHud` 与羁绊 toast
- `docs/cat-bond-design.md`、`docs/spirit-cat-npc-design.md` —— 玩法设计（`docs/` 下同级文件）
