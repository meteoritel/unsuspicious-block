# 实体与 AI

> `entity/` 与 `world/` 包中实体侧内容的架构：猫国灵体猫的通用基类与三种职业实体、灵体 AI 框架、灵魂提灯宠物、自然骨块追踪，以及猫之手结构的世界生成。
> 本文件是这些内容的唯一权威。淘洗点实体 `ShimmerEntity` 属淘洗玩法（见 [淘洗系统](panning.md)）；`world/` 包中属考古笔记/战利品系统的持久化类在本文只作包位置索引。

## 1. 代码地图

| 层 | 位置 |
|---|---|
| 灵体基类与职业 | `entity/SpiritCat.java`（继承原版 `Cat`）、`entity/MessengerCat.java`、`entity/SwordsmanCat.java`、`entity/MerchantCat.java` |
| 其他实体 | `entity/LanternPet.java`（独立宠物）、`entity/WaterShimmerEntity.java` / `entity/GlimmerEntity.java` / `entity/ShimmerEntity.java`（淘洗点，见 [淘洗系统](panning.md)） |
| 灵体 AI | `entity/ai/spiritcat/`：`SpiritRunMode`、`SpiritCatRole`、`SpiritMovementState`、`SpiritFlightController`、各职业 `*Phase`、`MessengerCatBehavior` / `MorningGiftBehavior` / `MessengerCatGiftGoal` / `MessengerCatPositioning` |
| 宠物 AI | `entity/ai/lantern/`：`LanternPetFollowSoulCarrierGoal`、`LanternPetWanderGoal` |
| 注册 | `entity/ModEntities.java`（`REGISTRY_MANIFEST` 是唯一清单，见 [注册架构](../foundation/registration.md)）、`entity/EntityRegistrar.java` |
| 骨块追踪 | `world/NaturalBoneBlockTracker.java`（静态门面）、`world/IBoneBlockTracker.java`（SPI） |
| 世界生成 | `data/unsuspiciousblock/structure/hand_of_cat_cache.nbt`、`data/unsuspiciousblock/worldgen/{structure,template_pool}/hand_of_cat_cache.json`、`data/unsuspiciousblock/tags/worldgen/biome/has_structure/` |
| 客户端 | `client/renderer/`（`ModEntityRenderers` / `ModModelLayers` 清单、各职业渲染器、`ShimmerRenderer` / `ShimmerSurfaceRenderer`）、`client/model/MessengerCatClothesModel`、`client/renderer/layer/`（装饰层） |
| 网络 | 无专属 payload（灵体不保存、不跨会话同步） |
| Mixin | `StructureTemplateMixin`（结构生成时扫描骨块）；Fabric 骨块追踪用 `chunk/ChunkAccessMixin` / `chunk/ChunkSerializerMixin` / `chunk/LevelChunkMixin` |
| 平台差异 | 骨块追踪：Fabric 用 mixin + chunk 事件，NeoForge 用 `DataAttachment`（`NeoForgeBoneBlockTracker.ATTACHMENT_TYPES` 注册到 modEventBus） |

## 2. 数据流

**灵体猫（召唤 → 职责 → 消散）**

```
CatGiftService.tryGhostGift / SwordsmanCatService.summonOrRefresh / MerchantCatSpawner.tick
  └─ 创建对应职业实体（MessengerCat / SwordsmanCat / MerchantCat）
       └─ activateDuty(lifetime) 或 activatePreview()
            ├─ DUTY：运行职业 AI（Goal/Behavior/Phase），到期 discard
            └─ PREVIEW：不运行 AI、不消散（仅检查模型与渲染）
       └─ 逐 tick：SynchedEntityData 同步运行模式/移动状态/生命周期 → 客户端渲染（渐入渐出）
```

**自然骨块追踪**

```
chunk 首次生成（Fabric CHUNK_GENERATE / NeoForge ChunkEvent.Load）
  └─ NaturalBoneBlockTracker.scanChunk  标记自然骨块
结构生成 ──► scanBoundingBox  标记结构内骨块
FossilHunterEffect.apply ──► consumeNatural  仅自然骨块触发额外掉落
玩家破坏路径 ──► markPlayerBreaking（内存延迟消费，防止标记被过早清除）
```

## 3. 关键类

| 类 | 职责 |
|---|---|
| `entity/SpiritCat` | 灵体猫抽象基类，统一运行模式、生命周期、飞行与无碰撞物理 |
| `entity/MessengerCat` / `SwordsmanCat` / `MerchantCat` | 三种职业实体 |
| `entity/LanternPet` | 灵魂提灯宠物（独立实体，非灵体体系） |
| `entity/ai/spiritcat/SpiritRunMode` | 运行模式枚举（`PREVIEW` / `DUTY` / `DEBUG_DUTY`） |
| `entity/ai/spiritcat/SpiritCatRole` | 职业标识枚举，供调试绑定、筛选、命令解析共用（`matches(SpiritCat)` 用 `instanceof` 判定） |
| `entity/ai/spiritcat/SpiritFlightController` / `SpiritMovementState` | 飞行控制器 / 离散移动状态（仅状态变化时同步） |
| `entity/ai/spiritcat/*Phase` | 各职业的行为阶段管理 |
| `world/NaturalBoneBlockTracker` | 骨块追踪静态门面，抹平平台差异 |
| `world/IBoneBlockTracker` | 骨块追踪 SPI（平台实现） |

## 4. 灵体猫基类（SpiritCat）

[`SpiritCat`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/entity/SpiritCat.java) 继承自原版 `Cat`，是三种职业灵体的公共基类。

### 4.1 运行模式

| 模式 | 说明 | AI | 生命周期 |
|---|---|---|---|
| `PREVIEW` | 预览模式（原版 `/summon` 创建） | 无 | 无（不消散） |
| `DUTY` | 正常职责模式 | 运行职责 AI | 有（到期消散） |
| `DEBUG_DUTY` | 调试职责模式 | 运行职责 AI | 有（不受正常职责的最长现世时间语义约束） |

切换通过 `activateDuty(lifetime)` / `activateDebugDuty(lifetime)` / `activatePreview()`。

### 4.2 生命周期

- `activateDuty(lifetimeTicks)` 设置现世最长时间，记录 `expiresAtGameTime`；`tick()` 中检测到期并 `discard()`。
- `getRenderAlphaProgress(partialTick)`：显现 10 tick 渐入，消散前 15 tick 渐出，供渲染器计算透明度。
- `shouldBeSaved()` 返回 false（灵体不随存档保存），但有 `addAdditionalSaveData` / `readAdditionalSaveData` 兼容存档恢复（含旧版已流逝 tick 格式迁移）。

### 4.3 飞行与无碰撞物理

- `setNoGravity(true)`，`isNoGravity()` 恒为 true。
- `travel()` / `move()` 重写：仅在职责模式生效时移动，否则静止。
- `clampToSpawnHeight()`：信使默认不低于召唤高度（`spawnY`），防止穿墙移动沉入世界底部。需要追击低处目标的子类可关闭。
- 无碰撞边界：`isPushable` / `isPickable` / `canCollideWith` / `push` / `pushEntities` 全部禁用。
- 不可伤害（`hurt` 返回 false）、不可交互（`mobInteract` PASS）、不可命名（`setCustomName` 空实现）、不可拴绳、不可骑乘、不可传送。

### 4.4 同步数据

通过 `SynchedEntityData` 同步运行模式、移动状态、生命周期与起始 tick，供客户端渲染。`setSpiritMovementState` **仅在状态变化时同步**，避免脏数据。

## 5. 三种灵体职业

| 职业 | 职责 | 召唤者 | 去重 |
|---|---|---|---|
| 猫猫信使 `MessengerCat` | 向符合条件的玩家送达猫国礼物（古国往礼，羁绊 ≥ 80） | `CatGiftService.tryGhostGift` | 每玩家最多一只（`CatFavorState.activeMessengerUuid`） |
| 剑士猫猫 `SwordsmanCat` | 九命触发时现身保护玩家，主动迎战敌对生物 | `SwordsmanCatService.summonOrRefresh` | 每玩家最多一只，再次触发刷新寿命而非新建 |
| 猫猫商人 `MerchantCat` | 玩家成为猫国挚友后在周围村庄临时现身，提供共享库存交易 | `MerchantCatSpawner.tick(server)`（服务端 tick 末尾） | 在生成村庄内活动，不跟随玩家 |

细节：

- **信使**：AI 由 `MessengerCatGiftGoal` 驱动，经历若干 `MessengerCatPhase`；`MessengerCatPositioning.placeNearTarget` 在玩家附近放置；送礼成功时通过 `CatFavorManager.onMessengerGiftDelivered` 补充九命。外观复用原版 `CatModel` 与随机抽取的原版猫皮肤，另叠加职业装饰层「邮差帽 + 邮包」，装饰通过骨骼同步跟随原版猫动画（见 [客户端与 GUI](client-ui.md) 的实体渲染一节）。
- **剑士**：`configureProtection(owner, target, lifetime)` 配置保护目标与优先攻击目标；`notifyOwnerHurt(attacker)` 在主人受伤时把攻击者交给剑士。规划中以嘴叼钻石剑为职业标志，**模型装饰尚未实现**，当前仅原版猫模型 + 灵体半透明渲染。
- **商人**：交易由 `MerchantCatTradeManager` 从 `data/unsuspiciousblock/merchant_cat_trades/` 加载，支持标签输入（`TaggedMerchantOffer`）。

> **猫国灵体**指猫国成员在现世活动时采用的统一形态：不同职责的灵体共享原版猫的基础体型，靠职业装饰、行为与出现条件相互区分。玩法设计见 `docs/spirit-cat-npc-design.md`（`docs/` 下同级文件）。

## 6. 灵体 AI 框架

`ai/spiritcat/` 包提供灵体猫的通用 AI 基础设施：

- `SpiritFlightController`：飞行控制器，管理灵体的飞行移动。
- `SpiritMovementState`：离散移动状态（如 HOVER 悬停），仅状态变化时同步。
- `SpiritRunMode` / `SpiritCatRole`：运行模式与职业标识（见「关键类」）。
- **阶段类**：`MessengerCatPhase` / `SwordsmanCatPhase` / `MerchantCatPhase` 分别管理各职业实体的行为阶段。
- **行为类**：`MessengerCatBehavior`（信使行为抽象）、`MorningGiftBehavior`（晨礼行为实现）、`MessengerCatGiftGoal`（送礼 Goal）。

预览灵体注册到 [`SpiritCatDebugRegistry`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/cat/SpiritCatDebugRegistry.java)，供调试命令查询与操作。

## 7. 灵魂提灯宠物（LanternPet）

[`LanternPet`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/entity/LanternPet.java) 是**独立实体**（继承 `PathfinderMob`，非 `SpiritCat`），以原版灵魂灯笼为外形的飞行宠物：

- **飞行**：`FlyingMoveControl` + `FlyingPathNavigation`，无重力，可在空中无障碍移动。
- **AI**：`FloatGoal`（防溺水）→ `LanternPetFollowSoulCarrierGoal`（跟随手持灵魂沙/灵魂土/灵魂灯笼/灵魂火把的玩家，半径 16）→ `LanternPetWanderGoal`（空中游荡）→ `LookAtPlayerGoal`。
- **浮动效果**：`aiStep` 中施加正弦轻微 y 速度，让悬停不死板。
- **粒子**：持续散发灵魂火苗（每 3t）与灵魂粒子（每 20t），移动时拖尾烟雾。客户端也参与绘制，避免完全依赖服务端广播。
- **环境音**：服务端偶发播放灵魂灯笼环境音。
- **不可交互**：与灵体猫相同的无碰撞边界。
- `setPersistenceRequired()`：不参与自然刷新清理。

## 8. 自然骨块追踪

[`NaturalBoneBlockTracker`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/world/NaturalBoneBlockTracker.java) 是静态门面，抹平平台差异，委托 [`IBoneBlockTracker`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/world/IBoneBlockTracker.java) SPI 实现：chunk 首次生成时扫描骨块、结构生成时扫描 bounding box，标记"自然生成"；`FossilHunterEffect.apply` 调 `consumeNatural` 后仅自然生成的骨块才触发额外掉落（见 [附魔系统](enchantment.md) 的「化石猎手」），玩家或机器放置的骨块不触发奖励。方法清单、Fabric/NeoForge 平台实现差异与玩家破坏路径的延迟消费状态见 [实体与 AI 机制细节](../internals/entities-world-details.md) 的「自然骨块追踪」。

## 9. 世界持久化数据（包位置索引）

以下 `world/` 包组件属考古笔记 / 战利品系统的存储，**机制权威在对应子系统文档**，此处仅作索引：

- `world/LootProbabilityData.java`：附加在 overworld，缓存每张战利品表的概率模拟结果与 JSON 哈希（见 [战利品表系统](loottable.md)）。
- `world/JournalLogStorage.java`：在 `<world>/data/unsuspiciousblock/journal_logs/` 下按玩家 UUID、战利品表保存日志分片；`world/JournalLogSavedData.java` 只保留为旧单文件迁移读取器（见 [考古笔记系统](journal.md)）。
- `world/GameTimeFormatHelper.java`：游戏时间格式化，供日志显示。
- `world/StructureRewindService.java` / `world/StructureRewindCooldownData.java`：结构回溯（见 [方块与物品](blocks-items.md) 的「结构回溯」）。

## 10. 世界生成：猫之手藏宝点

模组包含一个数据驱动的结构——猫之手藏宝点（`hand_of_cat_cache`）。**该结构的自然生成当前有意停用**（1.4.1 起删除了 structure_set，等待正式结构资产完成后再启用），结构定义与模板保留：

```
data/unsuspiciousblock/
├── structure/hand_of_cat_cache.nbt              结构 NBT
└── worldgen/
    ├── structure/hand_of_cat_cache.json         结构定义
    └── template_pool/hand_of_cat_cache.json     模板池
```

- 玩家从此结构中发现猫之手信物，绑定后建立猫族关系（见 [猫族关系系统](cat-favor.md) 的「关系建立与信物绑定」）；**当前需通过 `/place structure` 等方式手动放置**。
- 结构战利品是有限的。
- 结构生成时会触发 `NaturalBoneBlockTracker.scanBoundingBox` 标记结构内骨块。
- biome 通过 `tags/worldgen/biome/has_structure/` 控制生成范围。

## 11. 扩展点：新增灵体职业 / 行为 / 结构

**新增灵体职业**

1. 继承 `SpiritCat`，实现职责 AI（Goal/Behavior/Phase）。
2. 在 `ModEntities.REGISTRY_MANIFEST` 加注册条目（见 [注册架构](../foundation/registration.md)）。
3. 在 `SpiritCatRole` 加职业枚举（如需调试/命令支持），预览灵体注册到 `SpiritCatDebugRegistry`。
4. 添加渲染器（`ModEntityRenderers` 清单）与模型层（`ModModelLayers` 清单）。

**新增灵体行为**：参考 `MorningGiftBehavior`，实现 `MessengerCatBehavior` 或直接编写 Goal。

**新增结构**：在 `data/unsuspiciousblock/worldgen/` 与 `structure/` 添加 JSON + NBT，并在 biome tag 中注册生成范围；若要自然生成，还需 `structure_set`。

**调整灵体生命周期与效果时长**：通过 `ISpiritCatConfig`（见 [配置与第三方联动](../foundation/config-and-integrations.md)）。

## 12. 约束与陷阱

- **灵体不随存档保存**（`shouldBeSaved` 返回 false），重启后不会残留；但 `addAdditionalSaveData` 仍保留以兼容旧数据迁移。
- **预览灵体（`PREVIEW`）不运行 AI、不消散**，调试时不要把它当作职责模式的行为证据。
- **`clampToSpawnHeight` 默认阻止低于召唤高度**：需要追击低处目标的新职业必须显式关闭它，否则会表现为"追不上"。
- 同一玩家同时最多一只信使、一只剑士，靠 state 上的 UUID 去重；新增"每玩家唯一"的灵体时照此实现。
- **骨块追踪的标记必须消费**（`consumeNatural`）：无论是否发奖励都要清掉，否则遗留标记会让后续放置的骨块被误判。
- **玩家/机器放置的骨块不触发化石猎手奖励**，这是刻意的，不要为"手滑放置"补奖励。
- 猫之手结构自然生成已停用，`/place structure` 是当前唯一获取路径。

## 13. 相关文档

- [实体与 AI 机制细节](../internals/entities-world-details.md) —— 自然骨块追踪的平台实现
- [猫族关系系统](cat-favor.md) —— 灵体猫的召唤服务与关系状态
- [附魔系统](enchantment.md) —— `FossilHunterEffect` 与骨块追踪
- [注册架构](../foundation/registration.md) —— `ModEntities` / `EntityRegistrar`
- [客户端与 GUI](client-ui.md) —— 实体渲染器与模型层
- [Mixin](../foundation/mixin.md) —— Fabric 端骨块追踪 mixin
- [淘洗系统](panning.md) —— 淘洗点实体（同样注册于 `ModEntities` 清单，无模型装饰、不注册属性）
- [方块与物品](blocks-items.md) —— 结构回溯服务
- `docs/spirit-cat-npc-design.md` —— 灵体猫 NPC 设计（`docs/` 下同级文件）
