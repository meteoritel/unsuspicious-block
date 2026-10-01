# 淘洗系统

> `pan/` 包（含变体系统）、`PanItem` 与三把淘盘、`ShimmerEntity` 及其两个变体子类，以及对应的客户端表现、配置与调试指令。
> 本文件是淘洗子系统的唯一权威。逐项配置参数见 [配置与第三方联动](../foundation/config-and-integrations.md)，Mixin 注入点清单见 [Mixin](../foundation/mixin.md)。
> 自然生成与世界生成算法、每维度账本、客户端渲染细节与生成调试指令见 [淘洗生成、账本与客户端细节](../internals/panning-details.md)。

## 1. 代码地图

| 层 | 位置 |
|---|---|
| 淘洗点实体 | `entity/ShimmerEntity.java`（抽象基类）、`entity/WaterShimmerEntity.java`、`entity/GlimmerEntity.java` |
| 变体注册表 | `pan/variant/`：`ShimmerVariant`、`ShimmerVariants`、`AnchorRule`、`SpawnDomain`、`GlowStyle`、`PanningMedium`、`RiverDomain`/`WaterAnchor`、`NetherDomain`/`LavaAnchor` |
| 生成服务 | `pan/ShimmerSpawnService.java`（静态服务，由平台入口把服务器 tick/停止事件转交）、`pan/ShimmerSpawnStatistics.java`（速率测试） |
| 世界生成 | `pan/ShimmerFeature.java` + `pan/ShimmerFeatureConfig.java`（变体 id 写在 config）、`pan/ShimmerPlacement.java`（落点采样，两条来源共用） |
| 账本 | `pan/ShimmerLedger.java`（`SavedData`，按维度存 `unsuspiciousblock_shimmer`） |
| 结算 | `pan/PanningLootService.java` |
| 物品 | `item/PanItem.java`（全部淘盘基类）、`item/PanProfile.java`（每把盘的差异） |
| 数据资源 | `data/unsuspiciousblock/loot_table/gameplay/panning/{river,lava}.json`、`data/unsuspiciousblock/worldgen/{configured_feature,placed_feature}/*shimmer*.json`、`assets/minecraft/atlases/blocks.json`（盘面帧 unstitch） |
| 客户端 | `client/pan/`（`PanningAnimation` / `PanningVisuals` / `PanningSound` / `PanningSoundController` / `PanningMediumIndex`）、`client/renderer/ShimmerSurfaceRenderer`、`client/renderer/ShimmerRenderer`（空实现占位） |
| 指令 | `command/ShimmerDebugCommand.java`（`/usb shimmer`，OP 2） |
| Mixin | `client/HumanoidPanningMixin`（第三人称）、`client/ItemInHandPanningMixin`（Fabric 第一人称） |
| 平台差异 | 世界生成注入：Fabric 用 `BiomeModifications`，NeoForge 用 `neoforge/biome_modifier/*.json`；第一人称动画：NeoForge 用 `RenderHandEvent`，Fabric 用 mixin；配置：`IPanningConfig` 两端实现（见 [平台抽象](../foundation/platform-spi.md)） |

## 2. 数据流

**淘洗（玩家操作）**

```
玩家长按右键对准淘洗点
  └─ ShimmerEntity.interact 接受本变体可采的 PanItem（PanProfile.supports）
       └─ 长按达 pan_duration_ticks
            └─ PanItem.finishUsingItem
                 ├─ ShimmerEntity.consumePanUse  扣次数（耗尽则消散，世界点保留）
                 ├─ PanningLootService.grantPanningLoot
                 │    ├─ 按**目标变体**选表（river / lava）
                 │    ├─ 幸运 = 玩家幸运 + PanProfile.luckByVariant 加成
                 │    └─ 产出抛向玩家；考古笔记按 immediate 立即结算
                 └─ PanItem.hurtAndBreak  消耗 1 点耐久
```

**生成（服务端 tick）**

```
服务器 tick ──► ShimmerSpawnService
  ├─ 自然生成：按 spawn_interval_ticks 节拍逐维度尝试
  │    └─ 选玩家 → 半径 8 区块内筛 8 次区块 → 采样 4×3 分区 12 次找落点
  │         └─ ShimmerPlacement（海平面来自 ChunkGenerator，介质与群系由变体提供）
  │              └─ 上限 / 间距 / 冷却校验 → 生成实体 → 登记账本
  └─ 采空再生：tryRegenerateAfterHarvest（仅自然点，概率来自 PanProfile.regenerationChance）
世界生成：ShimmerFeature（VEGETAL_DECORATION 阶段）写实体 NBT，主线程回调加载
客户端：DATA_PAN_REMAINING / DATA_PANNERS 同步 → 帧组与粒子分档
```

## 3. 关键类

| 类 | 职责 |
|---|---|
| `entity/ShimmerEntity` | 淘洗点抽象基类，继承 `Entity`（非生物、不注册属性），继承深度固定 1 层；核心是**锚定** |
| `pan/variant/ShimmerVariant` | 变体数据：id / anchor / spawnDomain / glow / lootTable / entityType |
| `pan/variant/ShimmerVariants` | 变体注册表：`ResourceLocation` → 变体，含 id 编解码器与指令用的路径名查询 |
| `pan/ShimmerSpawnService` | 自然生成与再生的静态服务；`SpawnTrigger` 区分 `NATURAL` / `SPECIAL` / `MANUAL` / `WORLDGEN` |
| `pan/ShimmerPlacement` | 落点采样，自然生成与世界生成共用，保证"能生成的点"与"能存活的点"一致 |
| `pan/ShimmerLedger` | 每维度持久化现存点、变体、寿命与采空冷却；含区块空间索引与按变体计数 |
| `pan/PanningLootService` | 按目标变体抽表结算 |
| `item/PanItem` / `item/PanProfile` | 全部淘盘的基类 / 每把盘的差异（可采变体、幸运加成、再生概率） |
| `command/ShimmerDebugCommand` | 生成、统计、冷却、清除四组调试子指令 |

## 4. 变体系统

淘洗点之间的差异只有五项——依附介质、生成域、表现参数、产出表、实体类型——全部是数据或可替换的算法片段，没有一条是新机制，因此差异集中为**可注册的数据**而不是继承树。

| 组件 | 作用 |
|---|---|
| `ShimmerVariant` | 变体数据：id / anchor / spawnDomain / glow / lootTable / entityType |
| `AnchorRule` | 依附判定算法：识别介质方块、冻结相位、演出高度、代表介质方块（供调试指令铺点） |
| `SpawnDomain` | 生成域算法：落点基准高度、群系判定、区块级预筛 |
| `GlowStyle` | 表现参数：波光两色、三个粒子、三个音效、摇洗帧组 |
| `ShimmerVariants` | 注册表，含 id 编解码器与指令用的路径名查询 |

| 变体 | 实体子类 | 依附介质 | 生成域 | 产出表 |
|---|---|---|---|---|
| `water` 闪烁的光 | `WaterShimmerEntity` | 水源与河水冻结的普通冰 | 河流群系、生成器海平面 | `gameplay/panning/river` |
| `glimmer` 幽微的光 | `GlimmerEntity` | 岩浆（不区分流体等级） | `#minecraft:is_nether`、生成器海平面 32 | `gameplay/panning/lava` |

**子类里不允许出现介质分支**——一旦出现即说明该差异应下沉进变体数据。

### 4.1 表现参数不需要同步

依附介质、波光配色、粒子与音效都由 `entity.getType()` 唯一决定，服务端与客户端各自查注册表取同一份数据，因此**变体本身不占用任何同步字段**。

唯一的例外是「谁正在淘洗哪个点」：服务端把淘洗者集合（逗号分隔的玩家 UUID）同步到淘洗点实体上（`DATA_PANNERS`，仅在集合成员变化时发包，连续两刻未刷新的成员被剔除），客户端据此为任意玩家——包括第三人称下的远端玩家——选出正确的摇洗帧组。

## 5. 闪烁的光实体（锚定与来源）

[`ShimmerEntity`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/entity/ShimmerEntity.java) 是抽象基类，具体变体由子类实现 `getVariant()` 声明。核心约束是**锚定**：

- 位置永远锚定在绑定的介质方块（`anchorPos`，存 NBT `AnchorPos`）；任何外力偏移在服务端 tick 中立刻被 `snapToAnchor` 纠正。
- 绑定方块不再是该变体的依附介质，或上方被占据（`AnchorRule.isValid` 失败）时立刻 `discard`，不产出任何东西。
- 不参与碰撞、不受流体推动、不可被任何攻击或爆炸破坏（`hurt` 恒 false）；但 `isPickable` 为 true，保证能被淘盘准星选中。

按来源分三类（兼容保留 NBT `NaturalSpawn`，新增 `SpecialSpawn` 标记）：

| 来源 | 数量上限 | 寿命 | 说明 |
|---|---|---|---|
| 自然生成 | 受 `max_natural_per_dimension` 约束 | 随机 20~40 分钟（配置区间），绝对游戏时间截止 | 卸载继续计时，服务器关闭暂停；到期释放名额，重新加载立即消散，不再续期 |
| 世界生成 | 不计入 | 无 | 采空保留；生成时共用寿命随机方法固定恢复周期（默认 20~40 分钟），每周期恢复 1 次，上限为生成时初始次数 |
| 特殊生成 | 不计入 | 与自然点相同 | 外观与自然点一致，不能继续触发特殊生成；来源单独持久化 |

**冰面依附**：生成采样、开阔度计算和存活检查均接受水与普通冰（`minecraft:ice`），上方仍须为空气。水结冰或冰融化不删除实体，寿命及世界点恢复计时继续运行。冰上的反光贴合方块顶面并固定为静态；世界点辨识粒子保留，淘洗水花与工作水声停止。淘盘起手、持续使用与最终结算均检查实际依附面，途中结冰立即中断，不消耗次数、耐久或产出战利品；融化后可重新淘洗。浮冰、蓝冰不属于支持范围。

**冰中的点刻意不可淘洗**：实体包围盒自依附方块底部起高 0.9，整体落在实心冰块内部，而准星命中的射线以 `ClipContext.Block.COLLIDER` 在第一个带碰撞箱的方块处截断，止于冰块顶面，够不到实体。水与岩浆都没有碰撞箱，只有冰面会触发。这依赖两条隐式不变量：包围盒高度不超过一格、依附介质是完整碰撞箱方块（见「约束与陷阱」）。

**交互与状态**

- `interact` 只接受本变体可采的淘盘（`PanItem` 且 `PanProfile.supports(variant)`），进入长按使用状态；其余物品、空手与不支持的淘盘一律 `PASS` 让位给原版。
- 剩余淘洗次数（`DATA_PAN_REMAINING`）经实体数据同步给客户端驱动分档粒子表现；`consumePanUse` 扣减次数并播放音效，自然与特殊点耗尽时消散，世界点保留。
- 世界点持久化 `InitialPanUses` / `RecoveryIntervalTicks` / `NextRecoveryAt`；按绝对游戏时间从生成起周期计时，满次数时不储存额外次数，卸载跨周期后补算至上限。服务器关闭暂停；旧世界点首次加载时补齐计时数据。
- 淘洗工作状态（`DATA_PANNING`）是**临时演出状态，不写 NBT**：只有服务端在有效淘洗 tick 调用的 `markPanning(player)` 能置位，停止操作后最多两刻恢复闲置；同一调用顺带把该玩家登记进 `DATA_PANNERS`。
- 消散（`remove` 的 DISCARDED/KILLED）时主动从账本注销；首次服务端 tick 时若尚未登记则补登记——账本与实体不一致时**以实体为准**，不依据区块加载状态推断实体存在。

## 6. 淘洗流程与结算

[`PanItem`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/item/PanItem.java) 是全部淘盘的基类，差异由 [`PanProfile`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/item/PanProfile.java) 提供（三把盘的差异见 [方块与物品](blocks-items.md) 的淘盘一节）：

- 长按右键对准**本工具可采的**淘洗点，达到 `pan_duration_ticks` 后 `finishUsingItem` 完成一次淘洗：`consumePanUse` 扣次数 → `grantPanningLoot` 结算 → `hurtAndBreak` 消耗 1 点耐久。准星离开目标、提前松手，或目标变体不被本工具支持时立即中断，不消耗任何东西。
- 工具与点是**能力交集**：`PanItem` 本身就是"是淘盘"的判据，`PanProfile.harvestTargets` 声明可采的变体集合，`canHarvest` 把"能采这个变体"与"该点当前可淘洗"合成同一条判定，**起手、持续与结算三处共用**，避免在起手处通过而在结算处漏检。
- 准星命中判定用 `ProjectileUtil.getHitResultOnViewVector` 且过滤器只放行 `ShimmerEntity`——对普通水体、其它实体或空气使用无任何效果。
- 移动迟缓**复用原版「正在使用物品」表现**（客户端 `LocalPlayer.aiStep` 对移动输入按 0.2 缩放并重置冲刺），不施加药水效果；`getUseAnimation` 返回 `NONE`，动画由客户端渲染入口接管。

[`PanningLootService`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/PanningLootService.java)：

- 战利品表由**目标变体**决定（不是由工具决定）：水域用 `unsuspiciousblock:gameplay/panning/river`，幽微的光用 `gameplay/panning/lava`（`LootContextParamSets.BLOCK`，参数含 ORIGIN/BLOCK_STATE/TOOL/THIS_ENTITY 与幸运值）。两张表都列入 `ILootTableConfig` 默认追踪前缀，被考古笔记目录自动收录为"淘洗"分类。
- **考古笔记采用 `immediate` 立即结算**（与钓鱼一致），按本次淘洗产出记录，不等物品被拾取。
- 幸运值 = 玩家自身幸运 + 工具在目标变体上的加成（`PanProfile.luckByVariant`）。三把盘共用同一张表，产出分化完全走原版幸运机制：`weight: 0` 的门槛条目在幸运 ≤ 0 时被原版直接剔除出候选，`weight + quality × luck` 的倾斜条目随幸运线性增减，`bonus_rolls` 单独成池（**绝不能加在基础产出池上**，否则负幸运会把基础产出一起清零）。
- 产出不进背包，而是从液面向上、朝玩家方向抛出物品实体（水平 0.3 格/刻固定速度，竖直 0.4 格/刻，默认拾取延迟）；玩家恰在正上方时只向上抛。

## 7. 配置

- 配置：SPI 接口 `IPanningConfig`，Fabric 全局 JSON（`config/unsuspiciousblock/panning.json`），NeoForge 独立 SERVER ModConfigSpec（**必须显式文件名**，否则 `ConfigTracker` 冲突并中断模组构造）。三个工具标量 `gold_pan_luck_bonus` / `obsidian_pan_luck_penalty` / `gold_pan_regeneration_chance` **必须以 `DoubleSupplier` 延迟读取**（NeoForge 的 SERVER spec 在注册表填充期尚未加载，物品构造时立即求值会抛「配置未加载」异常；Fabric 端因构造期直接读 JSON 而不暴露，属于只在单平台复现的坑）。全部参数与默认值见 [配置与第三方联动](../foundation/config-and-integrations.md)。
- 生成调试指令 `/usb shimmer`、客户端渲染细节见 [淘洗生成、账本与客户端细节](../internals/panning-details.md)。

## 8. 扩展点：新增变体 / 新增淘盘

**新增变体**（代价 = 注册项 + 一条数据 + 资源）

1. 在 `ShimmerVariants` 追加一条变体数据（id / anchor / spawnDomain / glow / lootTable / entityType）。
2. 加一个 `ShimmerEntity` 子类与实体类型（在 `ModEntities` 的 `REGISTRY_MANIFEST` 加一行，见 [注册架构](../foundation/registration.md)）。
3. 补产出表 `data/unsuspiciousblock/loot_table/gameplay/panning/<变体>.json` 并加进 `ILootTableConfig` 追踪前缀。
4. 若要世界生成：加 `configured_feature`（`config.variant` 指新变体）与 `placed_feature`，并在两端做群系注入。
5. 补表现资源（帧贴图 + `GlowStyle` 里的粒子与音效）。

生成、账本、结算、调试指令与客户端渲染全部数据驱动，**不需要改动**；指令入口自动生成。

**新增淘盘**

在 `item/ModItems` 加一条 + 一个 `PanProfile`（可采变体、幸运加成、再生概率），并在 `PAN_ITEMS` 里登记（客户端属性注册遍历它）。`PanningVisuals` 与 `PanningLootService` 不需要改动。

**新增淘洗产出**：改对应变体的产出表；产出分化用原版幸运机制（`weight` / `quality` / `bonus_rolls`），不要往代码里加分支。

## 9. 约束与陷阱

- **子类里不允许出现介质分支**——出现即说明差异该下沉进变体数据。
- **海平面必须取自 `ChunkGenerator.getSeaLevel()`**，不能用 `Level.getSeaLevel()`（后者是硬编码的 `return 63;`，与维度无关）。主世界恰好也是 63，所以这个错误只在只做河流时不暴露；下界会永远找不到落点且不报错。
- **冰中点不可淘洗**依赖两条隐式不变量：实体包围盒高 ≤ 1 格、依附介质是完整碰撞箱方块。改动 `ModEntities` 的 `.sized(...)` 或新增带碰撞箱的依附介质都会改变这一行为。
- 变体不占同步字段；**不要给变体加同步数据**，客户端按 `entity.getType()` 自查注册表。
- `DATA_PANNING` 是临时演出状态，**不写 NBT**。
- 账本与实体不一致时**以实体为准**。
- 世界生成 Feature 不读账本冷却、不检查上限与间距；只有运行时自然生成走这些校验。
- 再生落点**不做垂直偏移采样**（依附介质只存在于同一水平面）。
- `bonus_rolls` 不能加在基础产出池上（负幸运会清零基础产出）。
- 三个工具标量必须 `DoubleSupplier` 延迟读取（见「配置」）。

## 10. 相关文档

- [淘洗生成、账本与客户端细节](../internals/panning-details.md) —— 生成算法、账本一致性、客户端渲染、调试指令
- [实体与 AI](entities-world.md) —— 实体注册模式与其他实体
- [方块与物品](blocks-items.md) —— 淘盘物品的实现细节
- [考古笔记系统](journal.md) / [战利品表系统](loottable.md) —— 淘洗产出的笔记结算与目录收录
- [配置与第三方联动](../foundation/config-and-integrations.md) —— 配置接口全表
- [Mixin](../foundation/mixin.md) —— 淘盘动画的两个 mixin 入口
- [文本格式规范](../foundation/text-format.md) —— Jade 信息行的键命名
