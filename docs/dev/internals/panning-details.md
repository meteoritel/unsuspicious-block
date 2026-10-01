# 淘洗生成、账本与客户端细节

> 淘洗点的自然生成与世界生成细则、每维度账本的结构与一致性策略、客户端表现与生成调试指令。
> 本文件不参与任务导航，只被 [淘洗系统](../subsystems/panning.md) 链接。**新增变体或淘盘只需要读那篇**；只有改动生成算法、账本持久化、客户端渲染或调试指令时才读本文件。

## 1. 生成

[`ShimmerSpawnService`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/ShimmerSpawnService.java) 是静态服务，由两端平台入口把服务器 tick 与服务器停止事件转交给它。

### 1.1 自然生成

- 运行时自然生成**只投放水域变体**：幽微的光纯由世界生成产出，没有自然生成入口（下界岩浆海面积是河流的数十倍，按节拍投放会让玩家一进下界迅速顶到上限）。
- 按配置的 `spawn_interval_ticks` 节拍（账本 `nextAttempt` 持久化）逐维度尝试：随机选一名玩家 → 在其所在区块为圆心、半径 8 区块内最多随机筛选 8 次 → 找到已加载、不在冷却且通过该变体生成域预筛的区块后，按随机起点逐个采样 4×3 分区，最多 12 次寻找落点。保留多人聚集的抽中概率优势，不设玩家避让距离。
- 落点判定（`ShimmerPlacement`，两条来源共用）：以生成器的海平面为基准，+1 至 -3 格内找依附介质方块，上方必须为空气；3x3 水平范围内至少 6 列开阔液面（`MIN_OPEN_SURFACE_COLUMNS`）才算开阔。介质识别与群系判定都由变体提供。
- 间距校验失败或达到该变体的每维度上限则本轮放弃；实体消散或账本中的截止时间到期后释放名额。
- 成功后向同维度、距离实体不超过 32 格（三维球形范围）的玩家发送聊天提示"有东西掉入了水中"。提示只在成功添加新实体后触发，存档加载不重复广播。
- `ShimmerSpawnService.SpawnTrigger` 区分触发方式：`NATURAL` 广播提示，`SPECIAL`（特殊再生）、`MANUAL`（手动调试）与 `WORLDGEN` 不广播。`NATURAL` 与 `MANUAL` 记为自然类型，`SPECIAL` 独立记为特殊类型；前三者共享有限寿命，仅自然类型计入数量上限。底层 `spawnShimmer(..., SpawnTrigger.SPECIAL)` 不校验落点、上限或间距，调用者负责这些规则。
- 任意来源的点被采空后，以该区块为中心的 3×3 区块进入 `harvest_cooldown_ticks` 冷却（默认 36000 刻 / 30 分钟）。重叠冷却取较晚截止时间；仅运行时自然生成检查，世界生成 Feature 不读取账本冷却。破坏或自然到期不触发采空冷却。
- 再生：每次自然点成功淘洗（包括最后一次）后调用 `ShimmerSpawnService.tryRegenerateAfterHarvest(level, harvested, chance)`，概率来自 `PanProfile.regenerationChance`（铜盘与黑曜石盘为 0，金盘读全局配置 `gold_pan_regeneration_chance`）。接口再次检查来源，仅自然点可触发。概率命中后在触发点同一水平面、水平半径 5 格内等概率选择一个已加载的依附介质方块（上方为空气），排除原位置和已有点占据的位置——**依附介质只存在于同一水平面，垂直偏移会落到另一片液面**，故不参与采样。落点按触发点的变体生成，不检查自然上限、间距、冷却、群系或海平面，不强制加载区块。无有效落点则失败；成功点是特殊来源，使用自然点外观，不广播提示，也不计入自然生成速率。

### 1.2 世界生成

- 地物**类型**只有一个：`unsuspiciousblock:shimmer`（`ShimmerFeature`，配置为 `ShimmerFeatureConfig`，携带变体 id）；**配置**与**投放**按变体各一份，注册到 `VEGETAL_DECORATION` 阶段：

| 变体 | configured_feature | placed_feature | 群系 | rarity_filter |
|---|---|---|---|---|
| `water` | `river_shimmer` | `river_shimmer` | `#minecraft:is_river` | `chance: 50` |
| `glimmer` | `nether_shimmer` | `nether_shimmer` | `#minecraft:is_nether` | `chance: 100` |

  变体写在 `configured_feature` 的 `config.variant` 里（如 `{"variant": "unsuspiciousblock:glimmer"}`），因此**新增变体不需要新的地物类**。`rarity_filter` 表示每次地物投放有 1/chance 概率进入采样，两端均可用数据包调整。
- 原版汇总中心周围 3×3 区块的群系地物并去重投放，因此 Feature 仍对中心区块内的实际候选检查该变体的群系。按原版局部随机源在 4×3 分区最多采样 12 次，海平面来自 `ChunkGenerator`；不访问配置、账本、服务器随机源，也不检查生成间距、冷却或自然上限。
- 只接受真正的 `ProtoChunk`，显式拒绝 `ImposterProtoChunk` 与完整区块。成功时写入实体 NBT（`id`、`AnchorPos`、`NaturalSpawn=false`、`Pos`、`NoGravity=true`），**不在生成线程构造实体**；原版保存生成期队列并在 FULL 主线程回调加载实体，恢复周期在加载时补齐，首 tick 用实体实际 UUID 登记账本。
- 只在新区块正常生成时投放，不补生成旧区域；区块重载不会重跑地物阶段。随机落点可在相同种子、生成器和地物配置下复现，但修改数据包或模组的地物顺序可能改变结果。实体被破坏后不补回，采空后由实体恢复次数。
- 原版 `freeze_top_layer` 位于后续 `TOP_LAYER_MODIFICATION` 阶段；冻结河流的点允许依附普通冰，但冰中的点不可淘洗。实际冻结还取决于位置温度与光照。

### 1.3 采样内缩

两条来源共用 `ShimmerPlacement.wanderForSurface`：在区块本地坐标 1～14 内按 4×3 分区采样，3×3 开阔度检查覆盖范围为 0～15，不跨出目标区块。自然生成额外传入间距校验谓词；Feature 仅检查水面与河流群系，不采用自然生成的 9 点群系预筛。

## 2. 账本

[`ShimmerLedger`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/pan/ShimmerLedger.java) 继承 `SavedData`，按维度存储（文件名 `unsuspiciousblock_shimmer`）。记录：

- `entries`：UUID → `Entry(pos, source, variant)`，附**区块空间索引** `entriesByChunk`（区块键 → UUID 集合）与按变体分开的自然生成计数 `naturalCounts`，两者均随 register/unregister 增量维护，`countNatural(variant)` 与 `isTooClose` 因此无需全量扫描（间距查询只访问范围覆盖的区块）。间距校验跨变体生效——不同介质的点本就不可能落在同一格。
- `nextAttempt`：下一次自然生成尝试的游戏刻。
- `expirations`：自然与特殊点 UUID → 绝对到期游戏刻，持久化在条目 `expires_at`。
- `expired`：已过期或被清除指令标记、但尚未加载清理的 UUID；所有来源均检查此标记，不占名额或间距，实体加载消散后移除。
- `cooldowns`：区块键 → 冷却截止游戏刻，持久化并清理到期记录。

一致性策略：

- 实体消散主动注销；卸载时保留记录，到期由账本移出数量与空间索引并留下过期标记。实体读取 NBT、服务端 tick 和采集结算均校验到期，避免恢复或交互时复活。
- 旧账本首次访问时为缺少截止时间的自然点设置"当前时间 + 配置寿命上限"；旧实体加载时按剩余寿命转换，并与账本截止时间取较早值。无法追溯升级前已经卸载的时长。
- 条目的 `variant` 字段是后加的：旧账本缺该字段或字段不可解析时按水域变体处理，因此旧存档可直接读入。**反向降级（新存档被旧版读取）会丢失变体信息**：旧版解析不了 `unsuspiciousblock:glimmer`，实体在区块加载时被静默跳过，对应条目永远等不到实体来注销，会永久残留并污染统计。这是"变体 = 独立实体类型"的固有代价；若实测回滚造成可观测残留，可在账本加载阶段补一条"条目对应实体类型已不存在则清除"的兜底。
- 外部工具永久删除实体文件时，对应过期 UUID 可能保留；过期标记不计入生成上限。
- 存盘格式保持简单（NBT 列表 + long 数组），加载时重建空间索引与计数。

## 3. 客户端表现

### 3.1 贴水波光（ShimmerSurfaceRenderer）

[`ShimmerSurfaceRenderer`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/renderer/ShimmerSurfaceRenderer.java) 在两端半透明方块渲染之后（Fabric `RenderLevelStageEvent` / NeoForge 对应事件）由世界渲染阶段绘制，实体本体无模型（`ShimmerRenderer` 为空实现占位）：

- 仅查询相机周围 32 格，依据实际流体表面高度绘制细线反光，**配色取自变体的 `GlowStyle`**（水域金白、幽微的光紫）；远端 8 格内线性淡出。
- 同一趟收集顺带按帧重建**介质索引** `PanningMediumIndex`（玩家 UUID → 介质），供物品属性为任意玩家选出摇洗帧组；索引不做增量维护与淘汰，因此只在空集合提前返回之前重建一次即可保证停手后清空。远处超出 32 格的淘洗点不参与，其帧组会回落到水域。
- 自定义 `RenderType`：写颜色、保留深度测试、关闭深度写入与面剔除——波光不穿墙，也不覆盖后续粒子的深度。
- 反光数量按剩余次数分档：3/2/1 次分别 48/28/12 道，错峰明灭（确定性分布，逐帧渲染不建随机对象）；淘洗时叠加水平扰动。

### 3.2 粒子分档

`ShimmerEntity` 通过同步的 `DATA_WORLDGEN` 区分世界来源：世界点每 10 刻额外发射一个缓慢上浮的辨识粒子（水域 END_ROD、幽微的光 WITCH），采空时仍可见；自然点与特殊点没有该附加效果、外观一致。工作状态时每两刻发射两组旋转水花与向外扩散的涟漪（水域 SPLASH/FISHING，幽微的光 LAVA/WITCH）。三个粒子类型与起手、消耗、循环三个音效全部取自变体的 `GlowStyle`，多人淘洗同一点不叠加发射频率；粒子节奏与水声共用 `getWorkTicks` 演出时钟。

### 3.3 摇洗动画与水声

- 起手由 `ShimmerEntity.interact` 在服务端广播一次变体的起手音（水域 `BUCKET_FILL`、幽微的光 `BUCKET_FILL_LAVA`），只播放音效，不移除介质方块；已在使用物品时不会重复触发。
- `PanningVisuals` 共用每次使用的计时：前 8 刻下探装水并抬盘（短时长配置取总时长的四分之一），阶段中点换成装水盘，随后以 20 刻为周期左右摇洗，幅度在 5 刻内平滑增加。总淘洗时长不变，中断或完成后恢复空盘。
- `assets/minecraft/atlases/blocks.json` 使用原版 `unstitch` 将各帧贴图（`*_pan_water.png`、`obsidian_pan_lava.png`，均为 16×64）自上而下拆为四个精灵，保留 generated 模型的透明轮廓和厚度。双平台客户端注册两个模型属性（沿用 AT / Access Widener 开放 `ItemProperties.register`），**按 `ModItems.PAN_ITEMS` 逐把盘注册**，新增淘盘无需改动 `PanningVisuals`：
  - `panning_frame`：摇洗相位，切换同一帧组内的四个模型。不使用全局自动循环的 `.mcmeta`，避免不同玩家起手时错帧。原图从上到下为左、中、右、中，起手从第二帧的中间液面开始；左手交换左右帧，第一人称和第三人称共用计时，模型帧按游戏刻切换。
  - `panning_medium`：正在淘洗的介质（0 水域 / 1 岩浆，取自 `PanningMedium` 序号）。只有黑曜石淘盘需要两套帧（它既能采水点也能采幽微的光），其物品模型用"介质 + 相位"双谓词覆盖八条；铜盘与金盘只采水点，永远进不到幽微的光的使用状态，因此只注册水域帧组。
- [`PanningAnimation`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/pan/PanningAnimation.java)（common，双端共用，支持左右手）：第一人称接管持物渲染绘制起手与左右摇洗，以 `ItemDisplayContext.NONE` 渲染原始盘面并由动画自行设置缩放与绕 X 轴倾角，避免叠加 generated 模型自带的第一人称旋转而变成侧立（`PanItem` 的 `getUseAnimation` 返回 `NONE`，不触发原版刷子动画）；第三人称在 `HumanoidModel.setupAnim` 后调整持盘手臂。
- [`PanningSoundController`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/client/pan/PanningSoundController.java)：每五刻检查玩家 16 格内工作中的淘洗点，每个点最多一个 `PanningSound`（循环音与定位高度取自变体：水域 `block.water.ambient`、幽微的光 `block.lava.ambient`），随摇洗周期调音调音量；停止工作、实体消散、离开范围或切换世界时停止。

### 3.4 Jade 信息行

`JadePlugin` 注入闪烁的光的 HUD 时附带剩余淘洗次数（键 `jade.unsuspiciousblock.shimmer.pan_remaining`，值为数字，`BODY` 白色）。键命名与样式规范见 [文本格式规范](../foundation/text-format.md) 的「Jade HUD 规则」一节。

## 4. 生成调试指令

`/usb shimmer`（OP 2，见 [`ShimmerDebugCommand`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/command/ShimmerDebugCommand.java)），使用命令源所在位置与维度，可配合 `/execute in ... positioned ... run`。

| 子指令 | 用途 |
|---|---|
| `help` | 显示用法；直接输入前缀也显示帮助 |
| `spawn <pos> [<变体>\|<来源>] [<来源>] [frozen]` | 铺该变体的代表介质（水域铺水源、岩浆域铺岩浆源，`frozen` 改铺冰块）后生成测试点；复用正式生成流程，但不做间距与上限判定 |
| `attempt` | 当前区块执行一次正式自然生成落点尝试，检查加载、上限、冷却、河流与间距，不铺水，不改自动节拍 |
| `stats [<变体>] [all]` | 当前维度或全部维度的自然点、特殊点、世界生成点、过期记录及已加载/未加载划分；省略变体时汇总全部变体 |
| `chunk` | 当前区块冷却剩余刻数、有效点 UUID/坐标/变体/来源/寿命/加载状态，最多 20 条 |
| `expire <uuid>` | 强制当前维度指定自然点过期；可操作卸载点，不影响世界生成点 |
| `cooldown set <seconds>` / `cooldown clear` | 当前区块周围 3×3 区域施加（1～86400 秒）/ 清除冷却；已有更长冷却保留 |
| `rate start <seconds> [intervalTicks]` / `rate [status]` / `rate stop` | 速率统计：临时替代自动节拍，到时自动输出并恢复配置节拍与上限检查 |
| `clear <变体\|<来源>\|all> [<来源>\|all] [current\|all\|dimension <id>]` | 按变体与来源两条轴清除当前、全部或指定维度的点 |

- 变体名与来源名共用同一层字面量位置（都是字面量，不存在词参数歧义），因此**旧的 `spawn <坐标> <来源>`、`clear <来源>` 语法保持可用**；变体字面量由注册表路径名生成，**新增变体自动获得指令入口**，启动时会校验变体名与 `natural/worldgen/special/all/frozen/current` 不重名。
- 速率统计由 `ShimmerSpawnStatistics` 在服务端 tick 驱动，使用与正式自然生成相同的完整尝试流程，**实际生成实体**；期间该维度的常规调度暂停，不叠加尝试。第一轮在一个完整间隔后执行，到时自动输出结果。临时节拍不写配置或账本；服务器停止时清空统计。每维度只保留一场统计，其他维度互不影响。
- 统计只计自动自然生成成功的新实体，排除世界生成、手动 `spawn/attempt` 和特殊再生。测试轮次绕过当前维度的数量上限，保留间距、冷却、寿命与玩家条件；时间按服务器实际运行 tick 计数，低 TPS 下不代表墙钟秒。折算是无数量上限条件下的线性估算，不能视为正常有上限玩法的长期实测。
- 清除：已加载实体立即删除；账本中未加载点立即释放名额和间距并持久化清除标记，重启后再次加载仍会删除。**不触发采空冷却或掉落，不强制加载区块、不扫描实体文件**；无账本记录的离线实体无法追溯。生成期尚未物化且未登记的 NBT 不在清除范围内；原版生成队列不由运行时命令修改。清除不会停止速率测试或后续生成。
