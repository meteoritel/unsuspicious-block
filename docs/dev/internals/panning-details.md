# 淘洗生成、账本与客户端细节

> 淘洗点的自然生成与世界生成采样算法、每维度账本数据结构与降级兜底、客户端贴水波光与摇洗动画实现，以及 `/usb shimmer` 调试命令。
> 本文件不参与主任务导航，由[淘洗系统](../subsystems/panning.md)按需链接。

## 1. 生成算法

[`ShimmerSpawnService`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/pan/ShimmerSpawnService.java) 接收服务端 `tick` 与 `stopped` 事件驱动生成调度。

### 1.1 自然生成与采空再生

- **仅投放水域变体**：幽微的光（岩浆变体）仅在世界生成阶段产出（下界岩浆海面积远超河流，运行时节拍生成会导致一入下界即瞬间占满上限）。
- **节拍调度与采样**：按配置 `spawn_interval_ticks`（由账本 `nextAttempt` 持久化）逐维度尝试：随机选取 1 名在线玩家 → 以其所在区块为中心在半径 `8` 区块内最多随机筛选 `8` 次 → 找到已加载、无冷却且通过 `SpawnDomain` 预筛的区块后，按随机起点遍历 `4×3` 分区最多采样 `12` 次寻找有效落点（保留多人聚集的抽中权重，不设玩家避让距离）。
- **落点判定 (`ShimmerPlacement`)**：自生成器海平面 `+1` 至 `-3` 格垂直窗口内寻找依附介质方块（上方必须为空气），且周围 `3×3` 水平范围至少存在 `6` 列开阔液面（`MIN_OPEN_SURFACE_COLUMNS`）。为避免跨出区块触发加载，采样内缩在区块本地坐标 `1–14` 内（使 `3×3` 开阔度校验落在 `0–15`）。
- **提示与来源触发器 (`SpawnTrigger`)**：`NATURAL` 成功添加新实体后向同维度 `32` 格内玩家发送 Action Bar / 聊天提示“有东西掉入了水中”（存档重载不重发）；`SPECIAL`、`MANUAL` 与 `WORLDGEN` 不广播提示。`NATURAL` 与 `MANUAL` 记入自然生成计数并受每维度上限约束。
- **采空冷却**：任一来源的点被采空后，以所在区块为中心的 `3×3` 区域进入 `harvest_cooldown_ticks`（默认 `36000 tick` / 30 分钟；重叠取较晚截止时间；仅运行时自然生成检查冷却，自然到期或方块破坏不触发冷却）。
- **采空再生 (`tryRegenerateAfterHarvest`)**：仅自然生成点每次成功淘洗（含最后一次）后按 `PanProfile.regenerationChance` 触发（金盘取全局配置，铜/黑曜石盘为 `0`）。命中后在同一水平面半径 `5` 格内（不做垂直偏移以免跨入不相干液面）随机选择已加载且无占用的介质位置生成 `SPECIAL` 实体。

### 1.2 世界生成 (`ShimmerFeature`)

- 使用统一地物类型 `unsuspiciousblock:shimmer`（由 `ShimmerFeatureConfig` 携带 `variant` ID），在 `VEGETAL_DECORATION` 阶段按 `configured_feature` / `placed_feature` 投放：

| 变体 | `configured_feature` / `placed_feature` | 目标群系 | `rarity_filter` |
|---|---|---|---|
| `water` | `river_shimmer` | `#minecraft:is_river` | `1 / 50` |
| `glimmer` | `nether_shimmer` | `#minecraft:is_nether` | `1 / 100` |

- **生成约束**：由于原版汇总 `3×3` 邻域区块地物，Feature 仍会对中心区块候选复核目标群系，利用区块局部随机源在 `4×3` 分区最多采样 `12` 次（不读取配置、账本冷却、间距或自然上限）。仅接受真正的 `ProtoChunk`（显式拒绝 `ImposterProtoChunk` 与已加载完整区块），在生成线程仅写入实体 NBT（`id`、`AnchorPos`、`NaturalSpawn=false`、`Pos`、`NoGravity=true`），待原版进入 `FULL` 阶段后在主线程物化实体并补齐恢复周期与账本登记。
- **冻结与复现**：仅对新区块生成生效；后续 `TOP_LAYER_MODIFICATION` 阶段河流冰面冻结不会删除已生成点（冰中点待融化后可采）。

## 2. 维度账本 (`ShimmerLedger`)

[`ShimmerLedger`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/pan/ShimmerLedger.java) 继承 `SavedData` 按维度独立保存（文件名 `unsuspiciousblock_shimmer`）：

- **内存索引与持久化字段**：保存 `entries`（UUID → `(pos, source, variant)`）、`nextAttempt`、`expirations`（绝对到期游戏刻）、`expired`（已到期或被清除但尚未卸载清理的 UUID）与 `cooldowns`；加载时在内存实时重建区块空间索引 `entriesByChunk` 与按变体计数 `naturalCounts`，使间距校验（跨变体共享）与计数查询无需全表扫描。
- **一致性与兼容性**：
  - 实体卸载保留账本记录，到期后账本将其移出自然名额并在 `expired` 留下标记；实体下次加载、服务端 tick 与结算交互均核对到期状态，防止复活。
  - 兼容读取缺少截止时间或缺少 `variant` 的旧存档（缺变体回退为 `water`）。**注意事项**：将新版本存档降级至旧版运行时，旧版无法解析 `unsuspiciousblock:glimmer` 实体会导致实体静默消失而账本条目永久等待注销。

## 3. 客户端表现

### 3.1 贴水波光与介质索引 (`ShimmerSurfaceRenderer` / `PanningMediumIndex`)

- `ShimmerSurfaceRenderer` 在 `ModWorldRenderers` 半透明方块渲染后阶段绘制：仅查询相机周围 `32` 格（远端 `8` 格线性淡出），取自变体 `GlowStyle` 配色并按剩余 `3/2/1` 次分别绘制 `48/28/12` 道确定性错峰波光（淘洗中叠加水平扰动），使用保留深度测试、关闭深度写入与面剔除的专用 `RenderType`。
- 客户端 tick 每帧依据视野 `32` 格内点位重建介质索引 `PanningMediumIndex`（玩家 UUID → 当前淘洗介质；超出 32 格回落水域），供物品模型属性在第三人称与第一人称查表使用。

### 3.2 粒子、音效与摇洗动画

- **粒子分档**：世界点每 `10 tick` 额外发射辨识上浮粒子（水域 `END_ROD`、幽微的光 `WITCH`），采空后仍保留以方便识别位置；工作期间每 `2 tick` 发射两组水花与涟漪，多人淘洗同一点不叠加发射频率，粒子与水声共享 `getWorkTicks` 时钟。
- **摇洗动画与模型属性 (`PanningVisuals` / `PanningAnimation`)**：
  - `assets/minecraft/atlases/blocks.json` 使用 `unstitch` 将 `16×64` 帧图拆为 `4` 个精灵（左、中、右、中）。双端客户端按 `ModItems.PAN_ITEMS` 遍历注册 `panning_frame`（由代码按游戏刻切换，不用 `.mcmeta` 防错帧）与 `panning_medium`（`0` 水 / `1` 岩浆；仅黑曜石盘配置双介质 8 条谓词）。
  - 前 `8 tick`（或短总时长的 `1/4`）下探装水抬盘，随后以 `20 tick` 为周期左右摇洗（前 `5 tick` 幅度渐增）；`PanningAnimation` 在第一人称以 `ItemDisplayContext.NONE` 接管手部绘制，在第三人称通过 `HumanoidPanningMixin` 调整手臂姿态。
- **循环水声 (`PanningSoundController`)**：每 `5 tick` 扫描玩家 `16` 格内工作中的淘洗点，每点最多维持一个 `PanningSound` 实例并随摇洗周期调制音调与音量。
- **Jade 展示**：展示剩余淘洗次数（`jade.unsuspiciousblock.shimmer.pan_remaining`），键规范见[文本格式规范](../foundation/text-format.md)。

## 4. 调试命令 (`/usb shimmer`)

[`ShimmerDebugCommand`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/command/ShimmerDebugCommand.java)（权限等级 `2`）基于命令执行者所在维度与坐标运行：

| 子指令 | 功能说明 |
|---|---|
| `help` | 显示全部子指令用法 |
| `spawn <pos> [<变体>\|<来源>] [<来源>] [frozen]` | 铺设代表介质（`frozen` 改铺冰）并复用正式生成逻辑生成测试点（跳过间距与上限检查）；变体字面量随注册表自动生成，并与保留关键字防重名校验 |
| `attempt` | 在当前区块执行一次正式自然生成尝试（校验加载、上限、冷却、群系与间距，不改自动节拍） |
| `stats [<变体>] [all]` | 查看当前或全部维度的各来源统计、过期记录与已加载/未加载数量 |
| `chunk` | 查看当前区块冷却剩余时间及最多 20 条点位明细 |
| `expire <uuid>` | 强制使当前维度指定自然/特殊点立即过期（可操作未加载点，不影响世界点） |
| `cooldown set <seconds>` / `clear` | 对当前区块周围 `3×3` 区域手动设置（1–86400 秒）或清除冷却 |
| `rate start <seconds> [intervalTicks]` / `status` / `stop` | 在当前维度启动独立速率测试（临时替代常规节拍、绕过数量上限但保留其余校验；按服务器实际 tick 计时输出估算速率，停服清理且不写配置/账本） |
| `clear <变体\|来源\|all> [<来源\|all>] [current\|all\|dimension <id>]` | 清理已加载实体并在账本中标记未加载点过期（不触发冷却/掉落，不强制加载区块，不修改尚未物化的原版生成期队列） |

## 5. 相关文档

- [淘洗系统](../subsystems/panning.md)
- [配置与第三方联动](../foundation/config-and-integrations.md)
- [文本格式规范](../foundation/text-format.md)
