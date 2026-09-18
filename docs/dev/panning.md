# 淘洗系统

本文档是淘洗子系统的唯一权威文档，覆盖 `pan/` 包（含变体系统）、`PanItem` 与三把淘盘、`ShimmerEntity` 及其两个变体子类，以及对应的客户端表现、配置与调试指令。本文描述的是机制与设计取舍；逐项配置参数见 [配置与第三方联动](config-integrations.md) 第 2 节。

## 1. 职责概述

- **淘洗点（`ShimmerEntity` 及其子类）**：依附某个介质方块的实体，介质、表现与自身变体由子类声明；自然与特殊点被淘空或寿命耗尽时消散；世界生成点采空后保留并恢复次数；所有来源均在绑定介质失效时消散。
- **变体（`ShimmerVariants`）**：依附介质、生成域、表现参数与产出表的注册表，见第 2 节。
- **生成（`ShimmerSpawnService` + `ShimmerFeature` + `ShimmerPlacement`）**：自然生成与原版 Feature 世界生成共用开阔液面采样骨架，介质与群系由变体提供；特殊生成使用触发点附近的独立液面判定。
- **账本（`ShimmerLedger`）**：每维度持久化现存淘洗点、变体、寿命与采空冷却，支撑按变体的上限计数与间距校验。
- **淘洗结算（`PanningLootService`）**：按目标变体抽取 `gameplay/panning/river` 或 `gameplay/panning/lava`，并把玩家幸运与工具加成叠加后结算给玩家与考古笔记。
- **客户端表现**：贴水波光渲染、粒子分档、摇洗动画与水声。

## 2. 变体系统

淘洗点之间的差异只有五项——依附介质、生成域、表现参数、产出表、实体类型——全部是数据或可替换的算法片段，没有一条是新机制，因此差异集中为**可注册的数据**而不是继承树。

| 组件 | 作用 |
|---|---|
| `ShimmerVariant` | 变体数据：id / anchor / spawnDomain / glow / lootTable / entityType |
| `AnchorRule` | 依附判定算法：识别介质方块、冻结相位、演出高度、代表介质方块（供调试指令铺点） |
| `SpawnDomain` | 生成域算法：落点基准高度、群系判定、区块级预筛 |
| `GlowStyle` | 表现参数：波光两色、三个粒子、三个音效、摇洗帧组 |
| `ShimmerVariants` | 注册表：`ResourceLocation` → 变体数据，含 id 编解码器与指令用的路径名查询 |

现有变体：

| 变体 | 实体子类 | 依附介质 | 生成域 | 产出表 |
|---|---|---|---|---|
| `water` 闪烁的光 | `WaterShimmerEntity` | 水源与河水冻结的普通冰 | 河流群系、生成器海平面 | `gameplay/panning/river` |
| `glimmer` 幽微的光 | `GlimmerEntity` | 岩浆（不区分流体等级） | `#minecraft:is_nether`、生成器海平面 32 | `gameplay/panning/lava` |

新增变体的代价是「注册项 + 一条数据 + 资源」：在 `ShimmerVariants` 追加一条、加一个实体子类与类型、补产出表与表现资源；生成、账本、结算、指令与客户端渲染都按数据驱动，不需要改动。**子类里不允许出现介质分支**——一旦出现即说明该差异应下沉进变体数据。

### 2.1 表现参数不需要同步

依附介质、波光配色、粒子与音效都由 `entity.getType()` 唯一决定，服务端与客户端各自查注册表取同一份数据，因此**变体本身不占用任何同步字段**。

唯一的例外是「谁正在淘洗哪个点」。服务端把淘洗者集合（逗号分隔的玩家 UUID）同步到淘洗点实体上（`DATA_PANNERS`，仅在集合成员变化时发包，连续两刻未刷新的成员被剔除），客户端据此为任意玩家——包括第三人称下的远端玩家——选出正确的摇洗帧组。

### 2.2 海平面必须取自区块生成器

落点搜索的基准高度取 `ChunkGenerator.getSeaLevel()`：主世界 63、下界 32、末地 0。**不能使用 `Level.getSeaLevel()`**——它是硬编码的 `return 63;`，与维度无关。主世界生成器恰好也是 63，所以这个错误在只做河流时不会暴露；但在下界会把搜索窗口定位到 y = 64~60，永远找不到 y = 31 的岩浆海面，而且不报错，表现为「永远找不到落点」。

## 3. 闪烁的光实体

[`ShimmerEntity`](../../common/src/main/java/com/meteorite/unsuspiciousblock/entity/ShimmerEntity.java) 是抽象基类，继承 `Entity`（非生物，不注册属性），具体变体由子类实现 `getVariant()` 声明，继承深度固定为 1 层。核心约束是**锚定**：

- 位置永远锚定在绑定的介质方块（`anchorPos`，存 NBT `AnchorPos`）；任何外力偏移在服务端 tick 中立刻被 `snapToAnchor` 纠正。
- 绑定方块不再是该变体的依附介质或上方被占据（`AnchorRule.isValid` 失败）时立刻 `discard`，不产出任何东西。
- 不参与碰撞、不受流体推动、不可被任何攻击或爆炸破坏（`hurt` 恒 false）；但 `isPickable` 为 true，保证能被淘盘准星选中。

按来源分三类（兼容保留 NBT `NaturalSpawn`，新增 `SpecialSpawn` 标记）：

| 来源 | 数量上限 | 寿命 | 说明 |
|---|---|---|---|
| 自然生成 | 受 `max_natural_per_dimension` 约束 | 随机 20~40 分钟（配置区间），绝对游戏时间截止 | 卸载继续计时，服务器关闭暂停；到期释放名额，重新加载立即消散，不再续期 |
| 世界生成 | 不计入 | 无 | 采空保留；生成时共用寿命随机方法固定恢复周期（默认 20~40 分钟），每周期恢复 1 次，上限为生成时初始次数 |
| 特殊生成 | 不计入 | 与自然点相同 | 外观与自然点一致，不能继续触发特殊生成；来源单独持久化 |

冰面依附：生成采样、开阔度计算和存活检查均接受水与普通冰（`minecraft:ice`），上方仍须为空气。水结冰或冰融化不删除实体，寿命及世界点恢复计时继续运行。冰上的反光贴合方块顶面并固定为静态；世界点辨识粒子保留，淘洗水花与工作水声停止。淘盘起手、持续使用与最终结算均检查实际依附面，途中结冰立即中断，不消耗次数、耐久或产出战利品；融化后可重新淘洗。浮冰、蓝冰不属于支持范围。

**冰中的点刻意不可淘洗**：实体包围盒自依附方块底部起高 0.9，整体落在实心冰块内部，而准星命中的射线以 `ClipContext.Block.COLLIDER` 在第一个带碰撞箱的方块处截断，止于冰块顶面，够不到实体。水与岩浆都没有碰撞箱，只有冰面会触发。这个行为依赖两条隐式不变量——包围盒高度不超过一格、依附介质是完整碰撞箱方块——改动 `ModEntities` 的 `.sized(...)` 或新增带碰撞箱的依附介质都会改变它；代价是冰中的点在账本里照常占用名额，却无人能采集。

交互与状态：

- `interact` 只接受本变体可采的淘盘（`PanItem` 且 `PanProfile.supports(variant)`），进入长按使用状态；其余物品、空手与不支持的淘盘一律 `PASS` 让位给原版。
- 剩余淘洗次数（`DATA_PAN_REMAINING`）经实体数据同步给客户端驱动分档粒子表现；`consumePanUse` 扣减次数并播放音效，自然与特殊点耗尽时消散，世界点保留。
- 世界点的 `InitialPanUses`、`RecoveryIntervalTicks`、`NextRecoveryAt` 持久化初始上限、固定周期与下一恢复时间；按绝对游戏时间从生成起周期计时，满次数时不储存额外次数，卸载跨周期后补算至上限。服务器关闭暂停；旧世界点首次加载时补齐计时数据。
- 淘洗工作状态（`DATA_PANNING`）是**临时演出状态，不写 NBT**：只有服务端在有效淘洗 tick 调用的 `markPanning(player)` 能置位，停止操作后最多两刻恢复闲置；同一调用顺带把该玩家登记进淘洗者集合（`DATA_PANNERS`，见第 2.1 节）。
- 消散（`remove` 的 DISCARDED/KILLED）时主动从账本注销；首次服务端 tick 时若尚未登记则补登记——账本与实体不一致时**以实体为准**，不依据区块加载状态推断实体存在。

## 4. 生成

[`ShimmerSpawnService`](../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/ShimmerSpawnService.java) 是静态服务，由两端平台入口把服务器 tick 与服务器停止事件转交给它。

### 4.1 自然生成

- 运行时自然生成只投放水域变体：幽微的光纯由世界生成产出，没有自然生成入口（下界岩浆海面积是河流的数十倍，按节拍投放会让玩家一进下界迅速顶到上限）。
- 按配置的 `spawn_interval_ticks` 节拍（账本 `nextAttempt` 持久化）逐维度尝试：随机选一名玩家 → 在其所在区块为圆心、半径 8 区块的范围内最多随机筛选 8 次 → 找到已加载、不在冷却且通过该变体生成域预筛的区块后，按随机起点逐个采样 4×3 分区，最多 12 次寻找落点。保留多人聚集的抽中概率优势，不设玩家避让距离。
- 落点判定（`ShimmerPlacement`，两条来源共用，保证"能生成的点"与"能存活的点"一致）：以生成器的海平面为基准，+1 至 -3 格内找依附介质方块（第 2.2 节），上方必须为空气；3x3 水平范围内至少 6 列开阔液面（`MIN_OPEN_SURFACE_COLUMNS`）才算开阔。介质识别与群系判定都由变体提供。
- 间距校验失败或达到该变体的每维度上限则本轮放弃；实体消散或账本中的截止时间到期后释放名额。
- 自动自然生成成功后，向同维度、距离实体不超过 32 格（三维球形范围）的玩家发送聊天提示“有东西掉入了水中”。提示只在成功添加新实体后触发，存档加载不重复广播。
- 生成触发方式由 `ShimmerSpawnService.SpawnTrigger` 区分：`NATURAL` 广播提示，`SPECIAL`（特殊再生）、`MANUAL`（手动调试）和 `WORLDGEN` 不广播。`NATURAL` 与 `MANUAL` 记为自然类型，`SPECIAL` 独立记为特殊类型；前三者共享有限寿命，仅自然类型计入数量上限。实体新增 `SpecialSpawn`、账本新增 `special` 标记，缺失时按旧的自然/世界类型读取。底层 `spawnShimmer(..., SpawnTrigger.SPECIAL)` 可供后续特殊生成调用，它不校验落点、上限或间距，调用者负责这些规则。
- 任意来源的点被采空后，以该区块为中心的 3×3 区块进入 `harvest_cooldown_ticks` 冷却（默认 36000 刻 / 30 分钟）。重叠冷却取较晚截止时间；仅运行时自然生成检查；世界生成 Feature 不读取账本冷却。破坏或自然到期不触发采空冷却。
- 再生概率由 `PanProfile.regenerationChance` 提供（铜盘与黑曜石盘为 0，金盘读全局配置 `gold_pan_regeneration_chance`，默认 0.10）；每次自然点成功淘洗（包括最后一次）后调用 `ShimmerSpawnService.tryRegenerateAfterHarvest(level, harvested, chance)`。接口再次检查来源，仅自然点可触发。概率命中后在触发点同一水平面、水平半径 5 格内等概率选择一个已加载的依附介质方块（上方为空气），排除原位置和已有点占据的位置——依附介质只存在于同一水平面，垂直偏移会落到另一片液面，故不参与采样；落点按触发点的变体生成，不检查自然上限、间距、冷却、群系或海平面，不强制加载区块。无有效落点则失败；成功点是特殊来源，使用自然点外观，不广播提示，也不计入自然生成速率。

### 4.2 世界生成

- 地物**类型**只有一个：`unsuspiciousblock:shimmer`（`ShimmerFeature`，配置为 `ShimmerFeatureConfig`，携带变体 id）；**配置**与**投放**按变体各一份，注册到 `VEGETAL_DECORATION` 阶段。Fabric 用 `BiomeModifications` 注入，NeoForge 用 `neoforge/biome_modifier/*.json`：

| 变体 | configured_feature | placed_feature | 群系 | rarity_filter |
|---|---|---|---|---|
| `water` | `river_shimmer` | `river_shimmer` | `#minecraft:is_river` | `chance: 50` |
| `glimmer` | `nether_shimmer` | `nether_shimmer` | `#minecraft:is_nether` | `chance: 100` |

  变体写在 `configured_feature` 的 `config.variant` 里（如 `{"variant": "unsuspiciousblock:glimmer"}`），因此新增变体不需要新的地物类。`rarity_filter` 表示每次地物投放有 1/chance 概率进入采样，两端均可用数据包调整；幽微的光初始比水点稀一倍，需按实机密度再调。
- 原版汇总中心周围 3×3 区块的群系地物并去重投放，因此 Feature 仍对中心区块内的实际候选检查该变体的群系（水域为 `#minecraft:is_river`，岩浆域为 `#minecraft:is_nether`）。按原版局部随机源在 4×3 分区最多采样 12 次，海平面来自 `ChunkGenerator`（第 2.2 节）；不访问配置、账本、服务器随机源，也不检查生成间距、冷却或自然上限。
- 只接受真正的 `ProtoChunk`，显式拒绝 `ImposterProtoChunk` 与完整区块。成功时写入实体 NBT（`id`、`AnchorPos`、`NaturalSpawn=false`、`Pos`、`NoGravity=true`），不在生成线程构造实体。原版保存生成期队列，并在 FULL 主线程回调加载实体；恢复周期在加载时补齐，首 tick 用实体实际 UUID 登记账本。
- 只在新区块正常生成时投放，不补生成旧区域；区块重载不会重跑地物阶段。随机落点可在相同种子、生成器和地物配置下复现，但修改数据包或模组的地物顺序可能改变结果。实体被破坏后不补回，采空后由实体恢复次数。
- 原版 `freeze_top_layer` 位于后续 `TOP_LAYER_MODIFICATION` 阶段；冻结河流的点允许依附普通冰，但冰中的点不可淘洗（第 3 节）。实际冻结还取决于位置温度与光照。
- `/place feature unsuspiciousblock:river_shimmer`（或 `nether_shimmer`）在完整区块上会被守卫拒绝，不能用于验证成功生成。实测应新建世界；可用测试数据包把 `chance` 临时设为 1，在新区块验证生成，再恢复原值。手动实体行为仍可用 `/usb shimmer spawn <pos> worldgen` 与 `/usb shimmer spawn <pos> glimmer worldgen` 验证。

### 4.3 采样内缩

两条来源共用 `ShimmerPlacement.wanderForSurface`：在区块本地坐标 1～14 内按 4×3 分区采样，3×3 开阔度检查覆盖范围为 0～15，不跨出目标区块。自然生成额外传入间距校验谓词；Feature 仅检查水面与河流群系，不采用自然生成的 9 点群系预筛。

## 5. 账本

[`ShimmerLedger`](../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/ShimmerLedger.java) 继承 `SavedData`，按维度存储（文件名 `unsuspiciousblock_shimmer`）。记录：

- `entries`：UUID → `Entry(pos, source, variant)`，附**区块空间索引** `entriesByChunk`（区块键 → UUID 集合）与按变体分开的自然生成计数 `naturalCounts`，两者均随 register/unregister 增量维护，`countNatural(variant)` 与 `isTooClose` 因此无需全量扫描（间距查询只访问范围覆盖的区块）。间距校验跨变体生效——不同介质的点本就不可能落在同一格。
- `nextAttempt`：下一次自然生成尝试的游戏刻。
- `expirations`：自然与特殊点 UUID → 绝对到期游戏刻，持久化在条目 `expires_at`。
- `expired`：已过期或被清除指令标记、但尚未加载清理的 UUID；所有来源均检查此标记，不占名额或间距，实体加载消散后移除。
- `cooldowns`：区块键 → 冷却截止游戏刻，持久化并清理到期记录。

一致性策略（设计取舍）：

- 实体消散主动注销；卸载时保留记录，到期由账本移出数量与空间索引并留下过期标记。实体读取 NBT、服务端 tick 和采集结算均校验到期，避免恢复或交互时复活。
- 旧账本首次访问时为缺少截止时间的自然点设置“当前时间 + 配置寿命上限”；旧实体加载时按剩余寿命转换，并与账本截止时间取较早值。无法追溯升级前已经卸载的时长。
- 条目的 `variant` 字段是后加的：旧账本缺该字段或字段不可解析时按水域变体处理，因此旧存档可直接读入。**反向降级（新存档被旧版读取）会丢失变体信息**：旧版解析不了 `unsuspiciousblock:glimmer`，实体在区块加载时被静默跳过，对应的世界生成条目永远等不到实体来注销，会永久残留并污染统计。这是「变体 = 独立实体类型」的固有代价，若实测中回滚造成了可观测的残留，可在账本加载阶段补一条「条目对应实体类型已不存在则清除」的兜底。
- 外部工具永久删除实体文件时，对应过期 UUID 可能保留；过期标记不计入生成上限。
- 存盘持久化格式保持简单（NBT 列表 + long 数组），加载时重建空间索引与计数。

## 6. 淘洗流程

[`PanItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/PanItem.java) 是全部淘盘的基类，差异由 [`PanProfile`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/PanProfile.java) 提供（详见 [方块与物品](blocks-items.md) §3.2）：

- 长按右键对准**本工具可采的**淘洗点，达到 `pan_duration_ticks` 后 `finishUsingItem` 完成一次淘洗：`consumePanUse` 扣淘洗点次数 → `grantPanningLoot` 结算 → `hurtAndBreak` 消耗 1 点耐久。准星离开目标、提前松手，或目标变体不被本工具支持时立即中断，不消耗任何东西。
- 工具与点是**能力交集**：`PanItem` 本身就是「是淘盘」的判据，`PanProfile.harvestTargets` 声明可采的变体集合，`canHarvest` 把「能采这个变体」与「该点当前可淘洗」合成同一条判定，起手、持续与结算三处共用，避免在起手处通过而在结算处漏检。
- 准星命中判定用 `ProjectileUtil.getHitResultOnViewVector` 且过滤器只放行 `ShimmerEntity`——对普通水体、其它实体或空气使用无任何效果。
- 移动迟缓**复用原版「正在使用物品」表现**（客户端 `LocalPlayer.aiStep` 对移动输入按 0.2 缩放并重置冲刺），不施加药水效果；`getUseAnimation` 返回 `NONE`，动画由客户端渲染入口接管（见第 7 节）。

[`PanningLootService`](../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/PanningLootService.java)：

- 战利品表由**目标变体**决定（不是由工具决定）：水域用 `unsuspiciousblock:gameplay/panning/river`，幽微的光用 `gameplay/panning/lava`（`LootContextParamSets.BLOCK`，参数含 ORIGIN/BLOCK_STATE/TOOL/THIS_ENTITY 与幸运值）。两张表都已列入 `ILootTableConfig` 默认追踪前缀，被考古笔记目录自动收录为「淘洗」分类。
- **考古笔记采用 `immediate` 立即结算**（与钓鱼一致），按本次淘洗产出记录，不等物品被拾取。
- 幸运值 = 玩家自身幸运 + 工具在目标变体上的加成（`PanProfile.luckByVariant`）。三把盘共用同一张表，产出分化完全走原版幸运机制：`weight: 0` 的门槛条目在幸运 ≤ 0 时会被原版直接剔除出候选，`weight + quality × luck` 的倾斜条目随幸运线性增减，`bonus_rolls` 单独成池（绝不能加在基础产出池上，否则负幸运会把基础产出一起清零）。
- 产出不进背包，而是从液面向上、朝玩家方向抛出物品实体（水平 0.3 格/刻固定速度，竖直 0.4 格/刻，默认拾取延迟）；玩家恰在正上方时只向上抛。

## 7. 客户端表现

### 7.1 贴水波光（ShimmerSurfaceRenderer）

[`ShimmerSurfaceRenderer`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/renderer/ShimmerSurfaceRenderer.java) 在两端半透明方块渲染之后（Fabric `RenderLevelStageEvent` / NeoForge 对应事件）由世界渲染阶段绘制，实体本体无模型（`ShimmerRenderer` 为空实现占位）：

- 仅查询相机周围 32 格，依据实际流体表面高度绘制细线反光，**配色取自变体的 `GlowStyle`**（水域金白、幽微的光紫）；远端 8 格内线性淡出。
- 同一趟收集顺带按帧重建**介质索引** `PanningMediumIndex`（玩家 UUID → 介质），供物品属性为任意玩家选出摇洗帧组；索引不做增量维护与淘汰，因此只在空集合提前返回之前重建一次即可保证停手后清空。远处超出 32 格的淘洗点不参与，其帧组会回落到水域。
- 自定义 `RenderType`：写颜色、保留深度测试、关闭深度写入与面剔除——波光不穿墙，也不覆盖后续粒子的深度。
- 反光数量按剩余次数分档：3/2/1 次分别 48/28/12 道，错峰明灭（确定性分布，逐帧渲染不建随机对象）；淘洗时叠加水平扰动。

### 7.2 粒子分档

`ShimmerEntity` 通过同步的 `DATA_WORLDGEN` 区分世界来源：世界点每 10 刻额外发射一个缓慢上浮的辨识粒子（水域 END_ROD、幽微的光 WITCH），采空时仍可见；自然点与特殊点没有该附加效果、外观一致。工作状态时每两刻发射两组旋转水花与向外扩散的涟漪（水域 SPLASH/FISHING，幽微的光 LAVA/WITCH）。三个粒子类型与起手、消耗、循环三个音效全部取自变体的 `GlowStyle`，多人淘洗同一点不叠加发射频率；粒子节奏与水声共用 `getWorkTicks` 演出时钟。

### 7.3 摇洗动画与水声

- 起手由 `ShimmerEntity.interact` 在服务端广播一次变体的起手音（水域 `BUCKET_FILL`、幽微的光 `BUCKET_FILL_LAVA`），只播放音效，不移除介质方块；已在使用物品时不会重复触发。
- `PanningVisuals` 共用每次使用的计时：前 8 刻下探装水并抬盘（短时长配置取总时长的四分之一），阶段中点换成装水盘，随后以 20 刻为周期左右摇洗，幅度在 5 刻内平滑增加。总淘洗时长不变，中断或完成后恢复空盘。
- `assets/minecraft/atlases/blocks.json` 使用原版 `unstitch` 将各帧贴图（`*_pan_water.png`、`obsidian_pan_lava.png`，均为 16×64）自上而下拆为四个精灵，保留 generated 模型的透明轮廓和厚度。双平台客户端注册两个模型属性（沿用 AT / Access Widener 开放 `ItemProperties.register`），按 `ModItems.PAN_ITEMS` 逐把盘注册，新增淘盘无需改动 `PanningVisuals`：
  - `panning_frame`：摇洗相位，切换同一帧组内的四个模型。不使用全局自动循环的 `.mcmeta`，避免不同玩家起手时错帧。原图从上到下为左、中、右、中，起手从第二帧的中间液面开始；左手交换左右帧，第一人称和第三人称共用计时，模型帧按游戏刻切换。
  - `panning_medium`：正在淘洗的介质（0 水域 / 1 岩浆，取自 `PanningMedium` 序号）。只有黑曜石淘盘需要两套帧（它既能采水点也能采幽微的光），其物品模型用「介质 + 相位」双谓词覆盖八条；铜盘与金盘只采水点，永远进不到幽微的光的使用状态，因此只注册水域帧组。
- [`PanningAnimation`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/pan/PanningAnimation.java)（common，双端共用，支持左右手）：第一人称接管持物渲染绘制起手与左右摇洗，以 `ItemDisplayContext.NONE` 渲染原始盘面并由动画自行设置缩放与绕 X 轴倾角，避免叠加 generated 模型自带的第一人称旋转而变成侧立（`PanItem` 的 `getUseAnimation` 返回 `NONE`，不触发原版刷子动画）；第三人称在 `HumanoidModel.setupAnim` 后调整持盘手臂。
- [`PanningSoundController`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/pan/PanningSoundController.java)：每五刻检查玩家 16 格内工作中的淘洗点，每个点最多一个 `PanningSound`（循环音与定位高度取自变体：水域 `block.water.ambient`、幽微的光 `block.lava.ambient`），随摇洗周期调音调音量；停止工作、实体消散、离开范围或切换世界时停止。
- 平台接入差异：NeoForge 用原生 `RenderHandEvent` 接第一人称动画；Fabric 原生事件不足，用 `ItemInHandPanningMixin`（`ItemInHandRenderer.renderArmWithItem` HEAD）补齐。第三人称两端都走 common mixin `HumanoidPanningMixin`。详见 [Mixin 总览](mixin.md)。

### 7.4 Jade 信息行

`JadePlugin` 注入闪烁的光的 HUD 时附带剩余淘洗次数（键 `jade.unsuspiciousblock.shimmer.pan_remaining`，值为数字，`BODY` 白色）。键命名与样式规范见 [Tooltip 格式规范](tooltip.md) 第 4 节。

## 8. 配置与调试

- 配置：SPI 接口 `IPanningConfig`，Fabric 全局 JSON（`config/unsuspiciousblock/panning.json`），NeoForge 独立 SERVER ModConfigSpec（必须显式文件名，否则 ConfigTracker 冲突）。除生成与节奏参数外，还有三个工具标量：`gold_pan_luck_bonus`（默认 1.0）、`obsidian_pan_luck_penalty`（默认 0.5，作用于水域）、`gold_pan_regeneration_chance`（默认 0.10）。**这三个值必须以 `DoubleSupplier` 延迟读取**：NeoForge 的 SERVER spec 在注册表填充期尚未加载，物品构造时立即求值会抛「配置未加载」异常（Fabric 端因构造期直接读 JSON 而不会暴露，属于只在单平台复现的坑）。全部参数与默认值见 [配置与第三方联动](config-integrations.md)。
- 调试指令：`/usb shimmer spawn <pos> [<变体>|<来源>] [<来源>] [frozen]`（见 [`ShimmerDebugCommand`](../../common/src/main/java/com/meteorite/unsuspiciousblock/command/ShimmerDebugCommand.java)，需 OP 权限 2）。坐标指向**绑定的介质方块**，指令会先铺该变体的代表介质（水域铺水源、岩浆域铺岩浆源，`frozen` 改铺冰块）再生成，可在陆地直接搭测试点；复用 `spawnShimmer` 正式生成流程，但不做间距与上限判定。清理样本用原版 `/kill @e[type=unsuspiciousblock:shimmer]` 与 `/kill @e[type=unsuspiciousblock:glimmer]`，消散时自行从账本注销。
- 变体名与来源名共用同一层字面量位置（都是字面量，不存在词参数歧义），因此**旧的 `spawn <坐标> <来源>`、`clear <来源>` 语法保持可用**；变体字面量由注册表路径名生成，新增变体自动获得指令入口，启动时会校验变体名与 `natural/worldgen/special/all/frozen/current` 不重名。

### 8.1 生成调试指令

以下指令均需 OP 2，前缀为 `/usb shimmer`，使用命令源所在位置和维度，可配合 `/execute in ... positioned ... run`。

| 子指令 | 用途 |
|---|---|
| `help` | 显示用法；直接输入前缀也显示帮助 |
| `attempt` | 当前区块执行一次正式自然生成落点尝试，检查加载、上限、冷却、河流与间距，不铺水，不改自动节拍 |
| `stats [<变体>] [all]` | 当前维度或全部维度的自然点、特殊点、世界生成点、过期记录及已加载/未加载划分；省略变体时汇总全部变体 |
| `chunk` | 当前区块冷却剩余刻数、有效点 UUID/坐标/变体/来源/寿命/加载状态，最多 20 条 |
| `expire <uuid>` | 强制当前维度指定自然点过期；可操作卸载点，不影响世界生成点 |
| `cooldown set <seconds>` | 当前区块周围 3×3 区域施加 1～86400 秒冷却；已有更长冷却保留 |
| `cooldown clear` | 清除同一 3×3 区域冷却 |
| `rate start <seconds> [intervalTicks]` | 在当前维度统计 1～86400 游戏秒，临时替代自动自然生成节拍；间隔可设 1～72000 刻，省略则使用开始时的配置值，时长至少覆盖一次间隔 |
| `rate [status]` | 查询当前统计或本维度最近一次结果 |
| `rate stop` | 提前结束，输出结果并恢复当前配置节拍与上限检查 |
| `clear <变体\|<来源>\|all> [<来源>\|all] [current\|all\|dimension <id>]` | 按变体与来源两条轴清除当前、全部或指定维度的点，省略范围为当前维度；第一位给出来源名时按「全部变体 + 该来源」处理 |

速率统计由 `ShimmerSpawnStatistics` 在服务端 tick 驱动，使用与正式自然生成相同的完整尝试流程，实际生成实体；期间该维度的常规调度暂停，不叠加尝试。第一轮在一个完整间隔后执行；到时自动输出结果，重新等待配置间隔后恢复常规生成。临时节拍不写配置或账本；服务器停止时清空统计。每维度只保留一场统计，其他维度互不影响。

示例：`/usb shimmer rate start 120 20` 统计 120 游戏秒、每秒尝试一次。结果包含尝试轮数、成功生成数、实测每分钟速率，以及默认 600 刻（30 秒）间隔下的每分钟和每小时估算。换算公式：`默认速率 = 成功数 / 实际统计游戏分钟 × 临时间隔刻数 / 600`；例如两分钟成功 6 个，间隔 20 刻，则实测 3 个/分钟，折算 0.1 个/分钟、6 个/小时。

统计只计自动自然生成成功的新实体，排除世界生成、手动 `spawn/attempt` 和特殊再生。测试轮次绕过当前维度的数量上限，保留间距、冷却、寿命与玩家条件；受阻轮次仍计数，未发生尝试时显示零值，表示尚无样本。时间按服务器实际运行 tick 计数（20 刻为一游戏秒），低 TPS 下不代表墙钟秒。折算是无数量上限条件下的线性估算，不能视为正常有上限玩法的长期实测。测试结束后恢复上限检查，超额实体保留，普通自然生成等数量降到上限以下后恢复；其他维度与手动 attempt 的自然上限规则不变；特殊生成不受自然上限约束。

清除示例：`/usb shimmer clear natural` 清当前维度全部变体的自然点，`/usb shimmer clear glimmer` 清当前维度全部幽微的光，`/usb shimmer clear glimmer worldgen all` 清全部维度的幽微的光世界生成点，`/usb shimmer clear all all` 清全部维度全部变体与类型。手动 natural 样本归自然类型，可用 `spawn <pos> special` 布置特殊样本。旧版本未保存特殊来源的点无法追溯，只能按自然类型处理。

已加载实体立即删除；账本中未加载点立即释放名额和间距，并持久化清除标记，重启后再次加载仍会删除。不触发采空冷却或掉落，不强制加载区块、不扫描实体文件；无账本记录的离线实体无法追溯。生成期尚未物化且未登记的 NBT 不在清除范围内；原版生成队列不由运行时命令修改。清除不会停止速率测试或后续自然/新区块生成；清理测试现场可先执行 `rate stop`。反馈逐维度区分立即删除与未加载标记数量。

统计依据账本，不强制加载区块，也不扫描实体文件；过期 UUID 无坐标，因此 `chunk` 不列已过期点。`stats` 中“未加载”表示 UUID 当前不在服务端已加载实体中，不能据此验证其磁盘文件仍存在。

卸载到期验证：先 `chunk` 记录自然点 UUID，离开并等待实体卸载，执行 `expire <uuid>`，用 `stats` 检查过期待清理数，再返回观察实体消失和标记清理。冷却验证可用 `cooldown set 30`、`attempt`、`chunk` 观察拒绝原因和倒计时，再清除或等待冷却结束。


## 9. 相关文档

- [实体与 AI](entities-world.md) — 实体注册模式与其他实体
- [方块与物品](blocks-items.md) — 淘盘物品的实现细节
- [考古笔记系统](journal.md) / [战利品表系统](loottable.md) — 淘洗产出的笔记结算与目录收录
- [配置与第三方联动](config-integrations.md) — 配置接口全表
- [Mixin 总览](mixin.md) — 淘盘动画的两个 mixin 入口
