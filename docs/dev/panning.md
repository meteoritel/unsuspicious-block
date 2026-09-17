# 淘洗系统

本文档是淘洗子系统的唯一权威文档，覆盖 `pan/` 包、`CopperPanItem`、`ShimmerEntity` 与对应的客户端表现、配置与调试指令。本文描述的是机制与设计取舍；逐项配置参数见 [配置与第三方联动](config-integrations.md) 第 2 节。

## 1. 职责概述

- **闪烁的光（`ShimmerEntity`）**：依附水方块的淘洗点实体，自然与特殊点被淘空或寿命耗尽时消散；世界生成点采空后保留并恢复次数；所有来源均在绑定水体失效时消散。
- **生成（`ShimmerSpawnService` + `ShimmerPlacement`）**：自然生成与世界生成共用开阔河流水面判定；特殊生成使用触发点附近的独立水面判定。
- **账本（`ShimmerLedger`）**：每维度持久化现存淘洗点与已判定区块，支撑上限计数与间距校验。
- **淘洗结算（`PanningLootService`）**：抽取 `gameplay/panning/river` 战利品表并结算给玩家与考古笔记。
- **客户端表现**：贴水波光渲染、粒子分档、摇洗动画与水声。

## 2. 闪烁的光实体

[`ShimmerEntity`](../../common/src/main/java/com/meteorite/unsuspiciousblock/entity/ShimmerEntity.java) 继承 `Entity`（非生物，不注册属性），核心约束是**锚定**：

- 位置永远锚定在绑定的水方块（`anchorPos`，存 NBT `AnchorPos`）；任何外力偏移在服务端 tick 中立刻被 `snapToAnchor` 纠正。
- 绑定水方块被破坏或上方被占据（`ShimmerPlacement.isBoundWaterIntact` 失败）时立刻 `discard`，不产出任何东西。
- 不参与碰撞、不受流体推动、不可被任何攻击或爆炸破坏（`hurt` 恒 false）；但 `isPickable` 为 true，保证能被淘盘准星选中。

按来源分三类（兼容保留 NBT `NaturalSpawn`，新增 `SpecialSpawn` 标记）：

| 来源 | 数量上限 | 寿命 | 说明 |
|---|---|---|---|
| 自然生成 | 受 `max_natural_per_dimension` 约束 | 随机 20~40 分钟（配置区间），绝对游戏时间截止 | 卸载继续计时，服务器关闭暂停；到期释放名额，重新加载立即消散，不再续期 |
| 世界生成 | 不计入 | 无 | 采空保留；生成时共用寿命随机方法固定恢复周期（默认 20~40 分钟），每周期恢复 1 次，上限为生成时初始次数 |
| 特殊生成 | 不计入 | 与自然点相同 | 外观与自然点一致，不能继续触发特殊生成；来源单独持久化 |

交互与状态：

- `interact` 只接受淘盘（`CopperPanItem`），进入长按使用状态；其余物品与空手一律 `PASS` 让位给原版。
- 剩余淘洗次数（`DATA_PAN_REMAINING`）经实体数据同步给客户端驱动分档粒子表现；`consumePanUse` 扣减次数并播放音效，自然与特殊点耗尽时消散，世界点保留。
- 世界点的 `InitialPanUses`、`RecoveryIntervalTicks`、`NextRecoveryAt` 持久化初始上限、固定周期与下一恢复时间；按绝对游戏时间从生成起周期计时，满次数时不储存额外次数，卸载跨周期后补算至上限。服务器关闭暂停；旧世界点首次加载时补齐计时数据。
- 淘洗工作状态（`DATA_PANNING`）是**临时演出状态，不写 NBT**：只有服务端在有效淘洗 tick 调用的 `markPanning` 能置位，停止操作后最多两刻恢复闲置。
- 消散（`remove` 的 DISCARDED/KILLED）时主动从账本注销；首次服务端 tick 时若尚未登记则补登记——账本与实体不一致时**以实体为准**，不依据区块加载状态推断实体存在。

## 3. 生成

[`ShimmerSpawnService`](../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/ShimmerSpawnService.java) 是静态服务，由两端平台入口把服务器 tick、区块加载与服务器停止事件转交给它。

### 3.1 自然生成

- 按配置的 `spawn_interval_ticks` 节拍（账本 `nextAttempt` 持久化）逐维度尝试：随机选一名玩家 → 在其所在区块为圆心、半径 8 区块的范围内最多随机筛选 8 次 → 找到已加载、不在冷却且含河流群系的区块后，按随机起点逐个采样 4×3 分区，最多 12 次寻找落点。保留多人聚集的抽中概率优势，不设玩家避让距离。
- 落点判定（`ShimmerPlacement`，两条来源共用，保证"能生成的点"与"能存活的点"一致）：世界水平面 +1 至 -3 格内找水面方块，上方必须为空气；3x3 水平范围内至少 6 列开阔水面（`MIN_OPEN_SURFACE_COLUMNS`）才算开阔水域。
- 间距校验失败或达到每维度上限则本轮放弃；实体消散或账本中的截止时间到期后释放名额。
- 自动自然生成成功后，向同维度、距离实体不超过 32 格（三维球形范围）的玩家发送聊天提示“有东西掉入了水中”。提示只在成功添加新实体后触发，存档加载不重复广播。
- 生成触发方式由 `ShimmerSpawnService.SpawnTrigger` 区分：`NATURAL` 广播提示，`SPECIAL`（特殊再生）、`MANUAL`（手动调试）和 `WORLDGEN` 不广播。`NATURAL` 与 `MANUAL` 记为自然类型，`SPECIAL` 独立记为特殊类型；前三者共享有限寿命，仅自然类型计入数量上限。实体新增 `SpecialSpawn`、账本新增 `special` 标记，缺失时按旧的自然/世界类型读取。底层 `spawnShimmer(..., SpawnTrigger.SPECIAL)` 可供后续特殊生成调用，它不校验落点、上限或间距，调用者负责这些规则。
- 任意来源的点被采空后，以该区块为中心的 3×3 区块进入 `harvest_cooldown_ticks` 冷却（默认 36000 刻 / 30 分钟）。重叠冷却取较晚截止时间；自然生成、世界生成入队及出队均检查。破坏或自然到期不触发采空冷却。
- 工具扩展可覆写 `CopperPanItem.getHarvestRegenerationChance`；每次自然点成功淘洗（包括最后一次）后调用 `ShimmerSpawnService.tryRegenerateAfterHarvest(level, harvested, chance)`。接口再次检查来源，仅自然点可触发，普通铜淘盘返回 0，当前仅预留接口。概率命中后在触发点同一水平面、水平半径 5 格内等概率选择一个已加载的水面方块（上方为空气），排除原位置和已有点占据的位置——闪烁的光只能依附水面，垂直偏移会落到另一片水体的水面，故不参与采样；不检查自然上限、间距、冷却、河流群系或世界海平面，不强制加载区块。无有效水面则失败；成功点有寿命、使用自然点外观，不广播提示，也不计入自然生成速率。

### 3.2 世界生成

- 区块首次加载时做一次概率判定，随机源为 `世界种子 ^ 区块坐标 * 混淆盐值`——**确定性**：同一区块永远同一结论，因此未命中的区块不记录任何状态，账本只记录真正生成过淘洗点的区块（默认约占河流区块 2%），不会随探索范围无限增长。
- 命中后选取落点进入静态待办队列 `PENDING_WORLDGEN`（按维度分组），由服务端 tick 消化（每 tick 上限 4 个，避免区块批量加载时集中生成）。实体化前按最新账本复查已判定区块与间距；**实体化成功才标记该区块已生成**，采空后由实体恢复次数，被破坏后不会重新生成。
- 待办队列绑定当前服务器实例：切换存档时 `stop` / `bindServer` 清空旧坐标，不复用。

### 3.3 采样内缩

区块内随机采样点与边界保持 1 格间距（14x14 而非 16x16）：开阔水域判定需查询水平相邻方块，贴边采样会落到邻区块——而世界生成阶段处于区块加载回调中，邻区块未必已加载，内缩避免触发级联的同步区块加载。

## 4. 账本

[`ShimmerLedger`](../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/ShimmerLedger.java) 继承 `SavedData`，按维度存储（文件名 `unsuspiciousblock_shimmer`）。记录：

- `entries`：UUID → `Entry(pos, source)`，附**区块空间索引** `entriesByChunk`（区块键 → UUID 集合）与自然生成计数 `naturalCount`，两者均随 register/unregister 增量维护，`countNatural` 与 `isTooClose` 因此无需全量扫描（间距查询只访问范围覆盖的区块）。
- `rolledChunks`：已完成世界生成判定的区块键集合。
- `nextAttempt`：下一次自然生成尝试的游戏刻。
- `expirations`：自然与特殊点 UUID → 绝对到期游戏刻，持久化在条目 `expires_at`。
- `expired`：已过期或被清除指令标记、但尚未加载清理的 UUID；所有来源均检查此标记，不占名额或间距，实体加载消散后移除。
- `cooldowns`：区块键 → 冷却截止游戏刻，持久化并清理到期记录。

一致性策略（设计取舍）：

- 实体消散主动注销；卸载时保留记录，到期由账本移出数量与空间索引并留下过期标记。实体读取 NBT、服务端 tick 和采集结算均校验到期，避免恢复或交互时复活。
- 旧账本首次访问时为缺少截止时间的自然点设置“当前时间 + 配置寿命上限”；旧实体加载时按剩余寿命转换，并与账本截止时间取较早值。无法追溯升级前已经卸载的时长。
- 外部工具永久删除实体文件时，对应过期 UUID 可能保留；过期标记不计入生成上限。
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

`ShimmerEntity` 通过同步的 `DATA_WORLDGEN` 区分世界来源：世界点每 10 刻额外发射一个缓慢上浮的 END_ROD 粒子，采空时仍可见；自然点与特殊点没有该附加效果，外观一致。工作状态时每两刻发射两组旋转水花（SPLASH）与向外扩散的钓鱼涟漪（FISHING），多人淘洗同一点不叠加发射频率。粒子节奏与水声共用 `getWorkTicks` 演出时钟。

### 6.3 摇洗动画与水声

- 起手由 `ShimmerEntity.interact` 在服务端广播一次原版 `BUCKET_FILL`，只播放音效，不移除水方块；已在使用物品时不会重复触发。
- `PanningVisuals` 共用每次使用的计时：前 8 刻下探装水并抬盘（短时长配置取总时长的四分之一），阶段中点换成装水盘，随后以 20 刻为周期左右摇洗，幅度在 5 刻内平滑增加。总淘洗时长不变，中断或完成后恢复空盘。
- `assets/minecraft/atlases/blocks.json` 使用原版 `unstitch` 将 `copper_pan_water.png` 自上而下拆为四个精灵，保留 generated 模型的透明轮廓和厚度。双平台客户端注册 `panning_frame` 模型属性（沿用 AT / Access Widener 开放 `ItemProperties.register`），按摇洗相位切换四个模型；不使用全局自动循环的 `.mcmeta`，避免不同玩家起手时错帧。原图从上到下为左、中、右、中，起手从第二帧的中间水面开始；左手交换左右帧，第一人称和第三人称共用计时，模型帧按游戏刻切换。
- [`PanningAnimation`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/pan/PanningAnimation.java)（common，双端共用，支持左右手）：第一人称接管持物渲染绘制装水起手与左右摇洗，以 `ItemDisplayContext.NONE` 渲染原始盘面并由动画自行设置缩放与绕 X 轴倾角，避免叠加 generated 模型自带的第一人称旋转而变成侧立（`CopperPanItem` 的 `getUseAnimation` 返回 `NONE`，不触发原版刷子动画）；第三人称在 `HumanoidModel.setupAnim` 后调整持盘手臂。
- [`PanningSoundController`](../../common/src/main/java/com/meteorite/unsuspiciousblock/client/pan/PanningSoundController.java)：每五刻检查玩家 16 格内工作中的淘洗点，每个点最多一个 `PanningSound`（复用原版 `block.water.ambient`，随摇洗周期调音调音量）；停止工作、实体消散、离开范围或切换世界时停止。
- 平台接入差异：NeoForge 用原生 `RenderHandEvent` 接第一人称动画；Fabric 原生事件不足，用 `ItemInHandPanningMixin`（`ItemInHandRenderer.renderArmWithItem` HEAD）补齐。第三人称两端都走 common mixin `HumanoidPanningMixin`。详见 [Mixin 总览](mixin.md)。

### 6.4 Jade 信息行

`JadePlugin` 注入闪烁的光的 HUD 时附带剩余淘洗次数（键 `jade.unsuspiciousblock.shimmer.pan_remaining`，值为数字，`BODY` 白色）。键命名与样式规范见 [Tooltip 格式规范](tooltip.md) 第 4 节。

## 7. 配置与调试

- 配置：SPI 接口 `IPanningConfig`，Fabric 全局 JSON（`config/unsuspiciousblock/panning.json`），NeoForge 独立 SERVER ModConfigSpec（必须显式文件名，否则 ConfigTracker 冲突）。全部参数与默认值见 [配置与第三方联动](config-integrations.md)。
- 调试指令：`/usb shimmer spawn <pos> [natural|worldgen|special]`（见 [`ShimmerDebugCommand`](../../common/src/main/java/com/meteorite/unsuspiciousblock/command/ShimmerDebugCommand.java)，需 OP 权限 2）。坐标指向**绑定的水方块**，指令会先铺水再生成，可在陆地直接搭测试点；复用 `spawnShimmer` 正式生成流程，但不做间距与上限判定。清理样本用原版 `/kill @e[type=unsuspiciousblock:shimmer]`，消散时自行从账本注销。

### 7.1 生成调试指令

以下指令均需 OP 2，前缀为 `/usb shimmer`，使用命令源所在位置和维度，可配合 `/execute in ... positioned ... run`。

| 子指令 | 用途 |
|---|---|
| `help` | 显示用法；直接输入前缀也显示帮助 |
| `attempt` | 当前区块执行一次正式自然生成落点尝试，检查加载、上限、冷却、河流与间距，不铺水，不改自动节拍 |
| `stats [all]` | 当前维度或全部维度的自然点、特殊点、世界生成点、过期记录及已加载/未加载划分 |
| `chunk` | 当前区块冷却剩余刻数、世界生成完成标记、有效点 UUID/坐标/寿命/加载状态，最多 20 条 |
| `expire <uuid>` | 强制当前维度指定自然点过期；可操作卸载点，不影响世界生成点 |
| `cooldown set <seconds>` | 当前区块周围 3×3 区域施加 1～86400 秒冷却；已有更长冷却保留 |
| `cooldown clear` | 清除同一 3×3 区域冷却 |
| `rate start <seconds> [intervalTicks]` | 在当前维度统计 1～86400 游戏秒，临时替代自动自然生成节拍；间隔可设 1～72000 刻，省略则使用开始时的配置值，时长至少覆盖一次间隔 |
| `rate [status]` | 查询当前统计或本维度最近一次结果 |
| `rate stop` | 提前结束，输出结果并恢复当前配置节拍与上限检查 |
| `clear <natural\|worldgen\|special\|all> [current\|all\|dimension <id>]` | 按类型清除当前、全部或指定维度的点，省略范围为当前维度 |

速率统计由 `ShimmerSpawnStatistics` 在服务端 tick 驱动，使用与正式自然生成相同的完整尝试流程，实际生成实体；期间该维度的常规调度暂停，不叠加尝试。第一轮在一个完整间隔后执行；到时自动输出结果，重新等待配置间隔后恢复常规生成。临时节拍不写配置或账本；服务器停止时清空统计。每维度只保留一场统计，其他维度互不影响。

示例：`/usb shimmer rate start 120 20` 统计 120 游戏秒、每秒尝试一次。结果包含尝试轮数、成功生成数、实测每分钟速率，以及默认 600 刻（30 秒）间隔下的每分钟和每小时估算。换算公式：`默认速率 = 成功数 / 实际统计游戏分钟 × 临时间隔刻数 / 600`；例如两分钟成功 6 个，间隔 20 刻，则实测 3 个/分钟，折算 0.1 个/分钟、6 个/小时。

统计只计自动自然生成成功的新实体，排除世界生成、手动 `spawn/attempt` 和特殊再生。测试轮次绕过当前维度的数量上限，保留间距、冷却、寿命与玩家条件；受阻轮次仍计数，未发生尝试时显示零值，表示尚无样本。时间按服务器实际运行 tick 计数（20 刻为一游戏秒），低 TPS 下不代表墙钟秒。折算是无数量上限条件下的线性估算，不能视为正常有上限玩法的长期实测。测试结束后恢复上限检查，超额实体保留，普通自然生成等数量降到上限以下后恢复；其他维度与手动 attempt 的自然上限规则不变；特殊生成不受自然上限约束。

清除示例：`/usb shimmer clear natural` 清当前维度自然点，`/usb shimmer clear special dimension minecraft:overworld` 清主世界特殊点，`/usb shimmer clear all all` 清全部维度全部类型。手动 natural 样本归自然类型，可用 `spawn <pos> special` 布置特殊样本。旧版本未保存特殊来源的点无法追溯，只能按自然类型处理。

已加载实体立即删除；账本中未加载点立即释放名额和间距，并持久化清除标记，重启后再次加载仍会删除。不触发采空冷却或掉落，不强制加载区块、不扫描实体文件；无账本记录的离线实体无法追溯。清除世界类型同时取消现有待办并标记对应区块，已有世界生成完成标记保留。清除不会停止速率测试或后续自然/新区块生成；清理测试现场可先执行 `rate stop`。反馈逐维度区分立即删除、未加载标记和取消待办数量。

统计依据账本，不强制加载区块，也不扫描实体文件；过期 UUID 无坐标，因此 `chunk` 不列已过期点。`stats` 中“未加载”表示 UUID 当前不在服务端已加载实体中，不能据此验证其磁盘文件仍存在。

卸载到期验证：先 `chunk` 记录自然点 UUID，离开并等待实体卸载，执行 `expire <uuid>`，用 `stats` 检查过期待清理数，再返回观察实体消失和标记清理。冷却验证可用 `cooldown set 30`、`attempt`、`chunk` 观察拒绝原因和倒计时，再清除或等待冷却结束。


## 8. 相关文档

- [实体与 AI](entities-world.md) — 实体注册模式与其他实体
- [方块与物品](blocks-items.md) — 淘盘物品的实现细节
- [考古笔记系统](journal.md) / [战利品表系统](loottable.md) — 淘洗产出的笔记结算与目录收录
- [配置与第三方联动](config-integrations.md) — 配置接口全表
- [Mixin 总览](mixin.md) — 淘盘动画的两个 mixin 入口
