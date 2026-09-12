# 淘洗系统

本文档是淘洗子系统的唯一权威文档，覆盖 `pan/` 包、`CopperPanItem`、`ShimmerEntity` 与对应的客户端表现、配置与调试指令。本文描述的是机制与设计取舍；逐项配置参数见 [配置与第三方联动](config-integrations.md) 第 2 节。

## 1. 职责概述

- **闪烁的光（`ShimmerEntity`）**：依附水方块的淘洗点实体，被淘空、绑定水体失效或寿命耗尽时消散。
- **生成（`ShimmerSpawnService` + `ShimmerPlacement`）**：自然生成与世界生成两条来源，共用落点判定。
- **账本（`ShimmerLedger`）**：每维度持久化现存淘洗点与已判定区块，支撑上限计数与间距校验。
- **淘洗结算（`PanningLootService`）**：抽取 `gameplay/panning/river` 战利品表并结算给玩家与考古笔记。
- **客户端表现**：贴水波光渲染、粒子分档、摇洗动画与水声。

## 2. 闪烁的光实体

[`ShimmerEntity`](../../common/src/main/java/com/meteorite/unsuspiciousblock/entity/ShimmerEntity.java) 继承 `Entity`（非生物，不注册属性），核心约束是**锚定**：

- 位置永远锚定在绑定的水方块（`anchorPos`，存 NBT `AnchorPos`）；任何外力偏移在服务端 tick 中立刻被 `snapToAnchor` 纠正。
- 绑定水方块被破坏或上方被占据（`ShimmerPlacement.isBoundWaterIntact` 失败）时立刻 `discard`，不产出任何东西。
- 不参与碰撞、不受流体推动、不可被任何攻击或爆炸破坏（`hurt` 恒 false）；但 `isPickable` 为 true，保证能被淘盘准星选中。

按来源分两类（存 NBT `NaturalSpawn`）：

| 来源 | 数量上限 | 寿命 | 说明 |
|---|---|---|---|
| 自然生成 | 受 `max_natural_per_dimension` 约束 | 随机 20~40 分钟（配置区间），仅实体加载时递减 | 寿命到期时若玩家在 3 格内（`GRACE_RADIUS`）则延长 30 秒，避免在玩家眼前消散 |
| 世界生成 | 不计入 | 无（`lifetimeTicks` 恒 0） | 供探索发现，永不自然消散 |

交互与状态：

- `interact` 只接受淘盘（`CopperPanItem`），进入长按使用状态；其余物品与空手一律 `PASS` 让位给原版。
- 剩余淘洗次数（`DATA_PAN_REMAINING`）经实体数据同步给客户端驱动分档粒子表现；`consumePanUse` 扣减次数并播放音效，耗尽时消散。
- 淘洗工作状态（`DATA_PANNING`）是**临时演出状态，不写 NBT**：只有服务端在有效淘洗 tick 调用的 `markPanning` 能置位，停止操作后最多两刻恢复闲置。
- 消散（`remove` 的 DISCARDED/KILLED）时主动从账本注销；首次服务端 tick 时若尚未登记则补登记——账本与实体不一致时**以实体为准**，不依据区块加载状态推断实体存在。

## 3. 生成

[`ShimmerSpawnService`](../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/ShimmerSpawnService.java) 是静态服务，由两端平台入口把服务器 tick、区块加载与服务器停止事件转交给它。

### 3.1 自然生成

- 按配置的 `spawn_interval_ticks` 节拍（账本 `nextAttempt` 持久化）逐维度尝试：随机选一名玩家 → 在其视距范围内随机挑已加载区块 → `ShimmerPlacement.hasRiverBiome` 3x3 列采样预筛河流群系 → 区块内水平游走最多 12 次找开阔水域落点。
- 落点判定（`ShimmerPlacement`，两条来源共用，保证"能生成的点"与"能存活的点"一致）：世界水平面 ±3 格内找水面方块，上方必须为空气；3x3 水平范围内至少 6 列开阔水面（`MIN_OPEN_SURFACE_COLUMNS`）才算开阔水域。
- 间距校验失败或达到每维度上限则本轮放弃；有实体消散（账本注销）后自动恢复尝试。

### 3.2 世界生成

- 区块首次加载时做一次概率判定，随机源为 `世界种子 ^ 区块坐标 * 混淆盐值`——**确定性**：同一区块永远同一结论，因此未命中的区块不记录任何状态，账本只记录真正生成过淘洗点的区块（默认约占河流区块 2%），不会随探索范围无限增长。
- 命中后选取落点进入静态待办队列 `PENDING_WORLDGEN`（按维度分组），由服务端 tick 消化（每 tick 上限 4 个，避免区块批量加载时集中生成）。实体化前按最新账本复查已判定区块与间距；**实体化成功才标记该区块已生成**，被淘空后不会重新出现。
- 待办队列绑定当前服务器实例：切换存档时 `stop` / `bindServer` 清空旧坐标，不复用。

### 3.3 采样内缩

区块内随机采样点与边界保持 1 格间距（14x14 而非 16x16）：开阔水域判定需查询水平相邻方块，贴边采样会落到邻区块——而世界生成阶段处于区块加载回调中，邻区块未必已加载，内缩避免触发级联的同步区块加载。

## 4. 账本

[`ShimmerLedger`](../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/ShimmerLedger.java) 继承 `SavedData`，按维度存储（文件名 `unsuspiciousblock_shimmer`）。记录：

- `entries`：UUID → `Entry(pos, source)`，附**区块空间索引** `entriesByChunk`（区块键 → UUID 集合）与自然生成计数 `naturalCount`，两者均随 register/unregister 增量维护，`countNatural` 与 `isTooClose` 因此无需全量扫描（间距查询只访问范围覆盖的区块）。
- `rolledChunks`：已完成世界生成判定的区块键集合。
- `nextAttempt`：下一次自然生成尝试的游戏刻。

一致性策略（设计取舍）：

- 实体消散主动注销；区块卸载**不注销**；外部工具直接删除实体数据时不自动修复，靠实体恢复 tick 时的补登记收敛。
- 存盘持久化格式保持简单（NBT 列表 + long 数组），加载时重建空间索引与计数。

## 5. 淘洗流程

[`CopperPanItem`](../../common/src/main/java/com/meteorite/unsuspiciousblock/item/CopperPanItem.java)（详见 [方块与物品](blocks-items.md) §3.2）：

- 长按右键对准闪烁的光，达到 `pan_duration_ticks` 后 `finishUsingItem` 完成一次淘洗：`consumePanUse` 扣淘洗点次数 → `grantPanningLoot` 结算 → `hurtAndBreak` 消耗 1 点耐久。准星离开目标或提前松手立即中断，不消耗任何东西。
- 准星命中判定用 `ProjectileUtil.getHitResultOnViewVector` 且过滤器只放行 `ShimmerEntity`——对普通水体、其它实体或空气使用无任何效果。
- 移动迟缓**复用原版「正在使用物品」表现**（客户端 `LocalPlayer.aiStep` 对移动输入按 0.2 缩放并重置冲刺），不施加药水效果；`getUseAnimation` 返回 `NONE`，动画由客户端渲染入口接管（见第 6 节）。

[`PanningLootService`](../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/PanningLootService.java)：

- 战利品表 `unsuspiciousblock:gameplay/panning/river`（`LootContextParamSets.BLOCK`，参数含 ORIGIN/BLOCK_STATE/TOOL/THIS_ENTITY 与玩家幸运值）。该表已列入 `ILootTableConfig` 默认追踪前缀，被考古笔记目录收录。
- **考古笔记采用 `immediate` 立即结算**（与钓鱼一致），按本次淘洗产出记录，不等物品被拾取。
- 产出不进背包，而是从水面向上、朝玩家方向抛出物品实体（水平 0.2 格/刻固定速度，竖直 0.3 格/刻，默认拾取延迟）；玩家恰在正上方时只向上抛。

## 6. 客户端表现

### 6.1 贴水波光（ShimmerSurfaceRenderer）

[`ShimmerSurfaceRenderer`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/renderer/ShimmerSurfaceRenderer.java) 在两端半透明方块渲染之后（Fabric `RenderLevelStageEvent` / NeoForge 对应事件）由世界渲染阶段绘制，实体本体无模型（`ShimmerRenderer` 为空实现占位）：

- 仅查询相机周围 32 格，依据实际流体表面高度绘制金白色细线反光；远端 8 格内线性淡出。
- 自定义 `RenderType`：写颜色、保留深度测试、关闭深度写入与面剔除——波光不穿墙，也不覆盖后续粒子的深度。
- 反光数量按剩余次数分档：3/2/1 次分别 48/28/12 道，错峰明灭（确定性分布，逐帧渲染不建随机对象）；淘洗时叠加水平扰动。

### 6.2 粒子分档

`ShimmerEntity` 客户端 tick 发射：剩余 3/2/1 次对应约 15/6.7/2 个白色粒子每秒（密度档位暗示剩余价值）；工作状态时每两刻增加两组旋转水花（SPLASH）与向外扩散的钓鱼涟漪（FISHING），多人淘洗同一点不叠加发射频率。粒子节奏与水声共用 `getWorkTicks` 演出时钟。

### 6.3 摇洗动画与水声

- [`PanningAnimation`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/pan/PanningAnimation.java)（common，双端共用，支持左右手）：第一人称接管持物渲染绘制盘面绕圈摇洗；第三人称在 `HumanoidModel.setupAnim` 后调整持盘手臂。
- [`PanningSoundController`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/pan/PanningSoundController.java)：每五刻检查玩家 16 格内工作中的淘洗点，每个点最多一个 `PanningSound`（复用原版 `block.water.ambient`，随摇洗周期调音调音量）；停止工作、实体消散、离开范围或切换世界时停止。
- 平台接入差异：NeoForge 用原生 `RenderHandEvent` 接第一人称动画；Fabric 原生事件不足，用 `ItemInHandPanningMixin`（`ItemInHandRenderer.renderArmWithItem` HEAD）补齐。第三人称两端都走 common mixin `HumanoidPanningMixin`。详见 [Mixin 总览](mixin.md)。

## 7. 配置与调试

- 配置：SPI 接口 `IPanningConfig`，Fabric 全局 JSON（`config/unsuspiciousblock/panning.json`），NeoForge 独立 SERVER ModConfigSpec（必须显式文件名，否则 ConfigTracker 冲突）。全部参数与默认值见 [配置与第三方联动](config-integrations.md)。
- 调试指令：`/usb shimmer spawn <pos> [natural|worldgen]`（见 [`ShimmerDebugCommand`](../../common/src/main/java/com/meteorite/unsuspiciousblock/command/ShimmerDebugCommand.java)，需 OP 权限 2）。坐标指向**绑定的水方块**，指令会先铺水再生成，可在陆地直接搭测试点；复用 `spawnShimmer` 正式生成流程，但不做间距与上限判定。清理样本用原版 `/kill @e[type=unsuspiciousblock:shimmer]`，消散时自行从账本注销。

## 8. 相关文档

- [实体与世界生成](entities-world.md) — 实体注册模式与其他实体
- [方块与物品](blocks-items.md) — 淘盘物品的实现细节
- [考古笔记系统](journal.md) / [战利品表系统](loottable.md) — 淘洗产出的笔记结算与目录收录
- [配置与第三方联动](config-integrations.md) — 配置接口全表
- [Mixin 总览](mixin.md) — 淘盘动画的两个 mixin 入口
