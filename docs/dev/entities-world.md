# 实体与世界生成

本文档描述 `entity/` 与 `world/` 包的架构：猫国灵体猫的通用基类与三种职业实体、灵体 AI 框架、灵魂提灯宠物、自然骨块追踪，以及猫之手结构的世界生成。

## 1. 职责概述

- **猫国灵体**：信使、剑士、商人三种职责猫，共享 `SpiritCat` 基类，统一处理预览/职责模式、生命周期、飞行物理。
- **灵魂提灯宠物**：独立的飞行陪伴宠物，非灵体体系。
- **骨块追踪**：记录世界自然生成的骨块，供化石猎手附魔判定。
- **SavedData**：日志与概率模拟结果的服务端持久化。
- **世界生成**：猫之手藏宝点结构（数据驱动）。

## 2. 子包结构

```
entity/
├── SpiritCat                 灵体猫抽象基类（继承原版 Cat）
├── MessengerCat              猫猫信使（送礼）
├── SwordsmanCat              剑士猫猫（九命保护）
├── MerchantCat               猫猫商人（交易）
├── LanternPet                灵魂提灯宠物（独立实体）
├── ModEntities               实体注册清单
├── EntityRegistrar           实体注册回调接口
├── EntityRendererRegistrar   渲染器注册清单（client）
└── ai/
    ├── spiritcat/
    │   ├── SpiritCatRole         职业标识枚举（MESSENGER/SWORDSMAN/MERCHANT）
    │   ├── SpiritRunMode         运行模式（PREVIEW/DUTY/DEBUG_DUTY）
    │   ├── SpiritMovementState   移动状态（HOVER/...）
    │   ├── SpiritFlightController 飞行控制器
    │   ├── MessengerCatBehavior  信使行为抽象
    │   ├── MorningGiftBehavior   晨礼行为实现
    │   ├── MessengerCatGiftGoal  送礼 Goal
    │   ├── MessengerCatPhase     信使阶段
    │   ├── MessengerCatPositioning 信使定位
    │   ├── MerchantCatPhase      商人阶段
    │   └── SwordsmanCatPhase     剑士阶段
    └── lantern/
        ├── LanternPetFollowSoulCarrierGoal  跟随手持灵魂物品的玩家
        └── LanternPetWanderGoal              空中游荡

world/
├── NaturalBoneBlockTracker   自然骨块追踪门面（静态，抹平平台差异）
├── IBoneBlockTracker         骨块追踪 SPI（平台实现）
├── LootProbabilityData       概率模拟结果 SavedData（附加 overworld）
├── JournalLogSavedData       日志 SavedData
└── GameTimeFormatHelper      游戏时间格式化（日志显示用）
```

## 3. 灵体猫基类（SpiritCat）

[`SpiritCat`](../../common/src/main/java/com/meteorite/unsuspiciousblock/entity/SpiritCat.java) 继承自原版 `Cat`，是三种职业灵体的公共基类，统一提供：

### 3.1 运行模式

三种运行模式由 [`SpiritRunMode`](../../common/src/main/java/com/meteorite/unsuspiciousblock/entity/ai/spiritcat/SpiritRunMode.java) 定义：

| 模式 | 说明 | AI | 生命周期 |
|---|---|---|---|
| `PREVIEW` | 预览模式（`/summon` 创建） | 无 | 无（不消散） |
| `DUTY` | 正常职责模式 | 运行职责 AI | 有（到期消散） |
| `DEBUG_DUTY` | 调试职责模式 | 运行职责 AI | 有（不受正常职责的最长现世时间限制语义约束） |

切换通过 `activateDuty(lifetime)` / `activateDebugDuty(lifetime)` / `activatePreview()`。预览实体用于检查模型与渲染效果，不运行职业 AI（见 [`CONTEXT.md`](../../CONTEXT.md) "预览灵体"）。

### 3.2 生命周期

- `activateDuty(lifetimeTicks)` 设置现世最长时间，记录 `expiresAtGameTime`。
- `tick()` 中检测到期，调 `discard()` 消散。
- `getRenderAlphaProgress(partialTick)`：显现 10 tick 渐入，消散前 15 tick 渐出，供渲染器计算透明度。
- `shouldBeSaved()` 返回 false（灵体不随存档保存），但有 `addAdditionalSaveData` / `readAdditionalSaveData` 兼容存档恢复（含旧版已流逝 tick 格式迁移）。

### 3.3 飞行与无碰撞物理

- `setNoGravity(true)`，`isNoGravity()` 恒为 true。
- `travel()` / `move()` 重写：仅在职责模式生效时移动，否则静止。
- `clampToSpawnHeight()`：信使默认不低于召唤高度（`spawnY`），防止穿墙移动沉入世界底部。需要追击低处目标的子类可关闭。
- 无碰撞边界：`isPushable` / `isPickable` / `canCollideWith` / `push` / `pushEntities` 全部禁用。
- 不可伤害（`hurt` 返回 false）、不可交互（`mobInteract` PASS）、不可命名（`setCustomName` 空实现）、不可拴绳、不可骑乘、不可传送。

### 3.4 同步数据

通过 `SynchedEntityData` 同步运行模式、移动状态、生命周期与起始 tick，供客户端渲染。`setSpiritMovementState` 仅在状态变化时同步，避免脏数据。

## 4. 三种灵体职业

三种职业实体继承 `SpiritCat`，各自实现职责 AI 与阶段：

### 4.1 猫猫信使（MessengerCat）

- 职责：向符合条件的玩家送达猫国礼物（古国往礼，羁绊≥80）。
- 由 [`CatGiftService.tryGhostGift`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/CatGiftService.java) 召唤，分配 `MorningGiftBehavior`。
- AI：`MessengerCatGiftGoal` 驱动送礼，经历若干阶段（`MessengerCatPhase`）。
- 定位：`MessengerCatPositioning.placeNearTarget` 在玩家附近放置。
- 每名玩家同时最多一只（`CatFavorState.activeMessengerUuid` 去重）。
- 送礼成功时通过 `CatFavorManager.onMessengerGiftDelivered` 补充九命。

### 4.2 剑士猫猫（SwordsmanCat）

- 职责：九命触发时现身保护玩家，主动迎战敌对生物。
- 由 [`SwordsmanCatService.summonOrRefresh`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/SwordsmanCatService.java) 召唤，以嘴叼钻石剑为职业标志。
- `configureProtection(owner, target, lifetime)`：配置保护目标与优先攻击目标。
- `notifyOwnerHurt(attacker)`：主人受伤时把攻击者交给剑士。
- 每名玩家同时最多一只，再次触发九命时刷新寿命而非新建。
- 阶段由 `SwordsmanCatPhase` 管理。

### 4.3 猫猫商人（MerchantCat）

- 职责：玩家成为猫国挚友后，在周围村庄临时现身提供共享库存交易。
- 由 `MerchantCatSpawner.tick(server)` 在服务端 tick 末尾驱动生成。
- 交易由 `MerchantCatTradeManager` 从 `data/unsuspiciousblock/merchant_cat_trades/` 加载，支持标签输入（`TaggedMerchantOffer`）。
- 阶段由 `MerchantCatPhase` 管理，在生成村庄内活动而不跟随玩家。

> 三种职业的玩法设计与领域术语见 [`docs/spirit-cat-npc-design.md`](../spirit-cat-npc-design.md) 与 [`CONTEXT.md`](../../CONTEXT.md) "猫国灵体"。

## 5. 灵体 AI 框架

`ai/spiritcat/` 包提供灵体猫的通用 AI 基础设施：

- **`SpiritFlightController`**：飞行控制器，管理灵体的飞行移动。
- **`SpiritMovementState`**：离散移动状态（如 HOVER 悬停），仅状态变化时同步。
- **`SpiritRunMode`**：运行模式（见 3.1）。
- **`SpiritCatRole`**：职业标识枚举，供调试绑定、筛选、命令解析共用。`matches(SpiritCat)` 用 `instanceof` 判定职业。
- **阶段类**：`MessengerCatPhase` / `SwordsmanCatPhase` / `MerchantCatPhase` 分别管理各职业实体的行为阶段。
- **行为类**：`MessengerCatBehavior`（信使行为抽象）、`MorningGiftBehavior`（晨礼行为实现）、`MessengerCatGiftGoal`（送礼 Goal）。

预览灵体（`/summon` 创建）不运行职业 AI，也不受正常职责实体的最长现世时间限制，仅用于检查模型与渲染。预览灵体注册到 [`SpiritCatDebugRegistry`](../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/SpiritCatDebugRegistry.java)，供调试命令查询与操作。

## 6. 灵魂提灯宠物（LanternPet）

[`LanternPet`](../../common/src/main/java/com/meteorite/unsuspiciousblock/entity/LanternPet.java) 是**独立实体**（继承 `PathfinderMob`，非 `SpiritCat`），以原版灵魂灯笼为外形的飞行宠物：

- **飞行**：`FlyingMoveControl` + `FlyingPathNavigation`，无重力，可在空中无障碍移动。
- **AI**：`FloatGoal`（防溺水）→ `LanternPetFollowSoulCarrierGoal`（跟随手持灵魂沙/灵魂土/灵魂灯笼/灵魂火把的玩家，半径 16）→ `LanternPetWanderGoal`（空中游荡）→ `LookAtPlayerGoal`。
- **浮动效果**：`aiStep` 中施加正弦轻微 y 速度，让悬停不死板。
- **粒子**：持续散发灵魂火苗（每 3t）与灵魂粒子（每 20t），移动时拖尾烟雾。客户端也参与绘制，避免完全依赖服务端广播。
- **环境音**：服务端偶发播放灵魂灯笼环境音。
- **不可交互**：与灵体猫相同的无碰撞边界。
- `setPersistenceRequired()`：不参与自然刷新清理。

## 7. 自然骨块追踪

[`NaturalBoneBlockTracker`](../../common/src/main/java/com/meteorite/unsuspiciousblock/world/NaturalBoneBlockTracker.java) 是静态门面，抹平平台差异，委托 [`IBoneBlockTracker`](../../common/src/main/java/com/meteorite/unsuspiciousblock/world/IBoneBlockTracker.java) SPI 实现：

| 方法 | 职责 |
|---|---|
| `isNatural(level, pos)` | 查询是否自然生成 |
| `markNatural` / `clearNatural` | 标记/清除 |
| `consumeNatural` | 消费标记（存在则清除并返回 true） |
| `scanChunk(chunk)` | chunk 生成时扫描骨块标记为自然 |
| `scanBoundingBox(level, box)` | 结构生成时扫描 bounding box 内骨块 |
| `markPlayerBreaking` / `isPlayerBreaking` / `clearPlayerBreaking` | 玩家破坏路径的延迟消费状态（内存） |

### 7.1 平台差异

| 平台 | 实现 | 持久化机制 |
|---|---|---|
| Fabric | `FabricBoneBlockTracker` | mixin + chunk 事件（`ChunkAccessMixin` / `ChunkSerializerMixin` / `LevelChunkMixin`） |
| NeoForge | `NeoForgeBoneBlockTracker` | `DataAttachment`（`NeoForgeBoneBlockTracker.ATTACHMENT_TYPES` 注册到 modEventBus） |

### 7.2 延迟消费状态

`PENDING_PLAYER_BREAKS`（内存集合，不持久化）用于在"方块移除事件"与"附魔效果触发"之间保持标记不被过早清除。服务器关闭时 `clearPendingPlayerBreaks` 清空。

### 7.3 触发流程

- **标记**：chunk 首次生成时（Fabric `CHUNK_GENERATE` 事件 / NeoForge `ChunkEvent.Load` 新 chunk）扫描骨块；结构生成时扫描 bounding box。
- **消费**：`FossilHunterEffect.apply` 调 `consumeNatural`，仅自然生成的骨块才触发额外掉落（见 [附魔系统](enchantment.md) 5.1）。
- **排除**：玩家或机器放置的骨块不触发奖励。

## 8. SavedData

`world/` 包还包含两个服务端持久化数据：

- [`LootProbabilityData`](../../common/src/main/java/com/meteorite/unsuspiciousblock/world/LootProbabilityData.java)：附加在 overworld，缓存每张战利品表的概率模拟结果与 JSON 哈希。详见 [考古笔记系统](journal.md) 与 [战利品表系统](loottable.md)。
- [`JournalLogSavedData`](../../common/src/main/java/com/meteorite/unsuspiciousblock/world/JournalLogSavedData.java)：日志的服务端持久化。
- [`GameTimeFormatHelper`](../../common/src/main/java/com/meteorite/unsuspiciousblock/world/GameTimeFormatHelper.java)：游戏时间格式化，供日志显示。

## 9. 世界生成：猫之手藏宝点

模组包含一个数据驱动的结构--猫之手藏宝点（`hand_of_cat_cache`）：

```
data/unsuspiciousblock/
├── structure/
│   └── hand_of_cat_cache.nbt              结构 NBT
└── worldgen/
    ├── structure/hand_of_cat_cache.json   结构定义
    ├── structure_set/...                  结构集（生成频率与分布）
    └── template_pool/hand_of_cat_cache.json  模板池
```

- 玩家从此结构中发现猫之手信物，绑定后建立猫族关系（见 [猫族关系系统](cat-favor.md) 第 3 节）。
- 结构战利品是有限的（见 [ADR 0003](../adr/0003-hand-of-cat-is-finite-structure-loot.md)）。
- 结构生成时会触发 `NaturalBoneBlockTracker.scanBoundingBox` 标记结构内骨块。
- biome 通过 `tags/worldgen/biome/has_structure/` 控制生成范围。

## 10. 扩展点

- **新增灵体职业**：
  1. 继承 `SpiritCat`，实现职责 AI（Goal/Behavior/Phase）。
  2. 在 `ModEntities.REGISTRY_MANIFEST` 加注册条目。
  3. 在 `SpiritCatRole` 加职业枚举（如需调试/命令支持）。
  4. 预览灵体注册到 `SpiritCatDebugRegistry`。
  5. 添加渲染器（`ModEntityRenderers` 清单）与模型层（`ModModelLayers` 清单）。
- **新增灵体行为**：参考 `MorningGiftBehavior`，实现 `MessengerCatBehavior` 或直接编写 Goal。
- **调整灵体生命周期/效果时长**：通过 `ISpiritCatConfig` 配置（见 [配置与第三方联动](config-integrations.md)）。
- **新增结构**：在 `data/unsuspiciousblock/worldgen/` 与 `structure/` 添加 JSON + NBT，并在 biome tag 中注册生成范围。

## 11. 相关文档

- [猫族关系系统](cat-favor.md) - 灵体猫的召唤服务与关系状态
- [附魔系统](enchantment.md) - `FossilHunterEffect` 与骨块追踪
- [注册架构](registration.md) - `ModEntities` / `EntityRegistrar`
- [客户端与 GUI](client-ui.md) - 实体渲染器与模型层
- [Mixin 总览](mixin.md) - Fabric 端骨块追踪 mixin
- [docs/spirit-cat-npc-design.md](../spirit-cat-npc-design.md) - 灵体猫 NPC 设计
