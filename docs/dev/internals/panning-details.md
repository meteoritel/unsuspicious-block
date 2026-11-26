# 淘洗生成、账本与客户端细节

> 淘洗点的自然生成与世界生成采样算法、每维度账本数据结构与降级兜底、客户端贴水波光与摇洗动画实现，以及 `/usb shimmer` 调试命令。
> 本文件不参与主任务导航，由[淘洗系统](../subsystems/panning.md)按需链接。

## 1. 生成算法

[`ShimmerSpawnService`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/pan/ShimmerSpawnService.java) 接收服务端 `tick` 与 `stopped` 事件驱动生成调度。

### 1.1 自然生成与特殊再生

- **仅投放水域变体**：幽微的光（岩浆变体）仅在世界生成阶段产出（下界岩浆海面积远超河流，运行时节拍生成会导致一入下界即瞬间占满上限）。
- `spawn_interval_ticks` 按维度调度，由账本保存下一次尝试时刻。每轮随机选一名在线玩家，在其周围半径八区块内抽取最多八个不同区块。每个方块和实体均已就绪且无冷却的候选区块各自搜索，找到有效落点后结束本轮。
- `ShimmerPlacement` 在生成器海平面 `+1` 至 `-3` 格内查找依附介质及其上方空气，要求周围 `3×3` 至少有六列开阔液面，按具体落点复核群系。分区采样覆盖整个区块，接缝候选只读取已就绪的方块邻域。
- **提示与来源触发器 (`SpawnTrigger`)**：`NATURAL` 成功添加新实体后向同维度 `32` 格内玩家发送 Action Bar / 聊天提示“有东西掉入了水中”（存档重载不重发）；`SPECIAL`、`MANUAL` 与 `WORLDGEN` 不广播提示。`NATURAL` 与 `MANUAL` 记入自然生成计数并受每维度上限约束。
- **采空冷却**：任一来源的点被采空后，以所在区块为中心的 `3×3` 区域进入 `harvest_cooldown_ticks`（默认 `36000 tick` / 30 分钟；重叠取较晚截止时间；仅运行时自然生成检查冷却，自然到期或方块破坏不触发冷却）。
- 金淘盘每次成功淘洗自然点时抽签，含最后一次。命中后在同高五格内选最近的可用水面，等距候选随机选取。落点要求方块与实体已就绪、锚点空闲。特殊点独立于自然生成上限、冷却、间距、开阔度、海平面和群系条件。

### 1.2 世界生成 (`ShimmerFeature`)

- 使用统一地物类型 `unsuspiciousblock:shimmer`（由 `ShimmerFeatureConfig` 携带 `variant` ID），在 `VEGETAL_DECORATION` 阶段按 `configured_feature` / `placed_feature` 投放：

| 变体 | `configured_feature` / `placed_feature` | 目标群系 | `rarity_filter` |
|---|---|---|---|
| `water` | `river_shimmer` | `#minecraft:is_river` | `1 / 50` |
| `glimmer` | `nether_shimmer` | `#minecraft:is_nether` | `1 / 100` |

- 原版汇总 `3×3` 邻域区块地物，Feature 仍复核中心区块具体落点的群系。接缝检查读取生成区域提供的邻域，生成线程只向中心的真实 `ProtoChunk` 写入实体 NBT。原版进入 `FULL` 阶段时在主线程装载实体并初始化恢复周期。
- **冻结与复现**：仅对新区块生成生效；后续 `TOP_LAYER_MODIFICATION` 阶段河流冰面冻结不会删除已生成点（冰中点待融化后可采）。

## 2. 维度账本 (`ShimmerLedger`)

[`ShimmerLedger`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/pan/ShimmerLedger.java) 继承 `SavedData` 按维度独立保存（文件名 `unsuspiciousblock_shimmer`）：

- 保存点位、生成节拍、绝对到期刻、待删除标记与冷却。加载时重建区块索引和各变体的自然点计数，供锚点占用、跨变体间距与自然名额查询。到期和冷却维护由最近的截止时刻驱动。
- **一致性与兼容性**：
  - 实体卸载保留账本记录，到期后账本将其移出自然名额并在 `expired` 留下标记；重新加载后，在已接入实体管理器的首个服务端 tick 消散并消费标记。交互与结算也即时检查到期状态。
  - 旧档缺少截止时间时，在账本首次取得维度游戏时间后补齐并保存。缺少 `variant` 时采用水域变体。**注意事项**：将新版本存档降级至旧版运行时，旧版无法解析 `unsuspiciousblock:glimmer` 实体会导致实体静默消失而账本条目永久等待注销。

## 3. 客户端表现

### 3.1 贴水波光与玩家淘洗会话

- `ShimmerSurfaceRenderer` 在 `ModWorldRenderers` 半透明方块渲染后阶段绘制：仅查询相机周围 `32` 格（远端 `8` 格线性淡出），取自变体 `GlowStyle` 配色并按剩余 `3/2/1` 次分别绘制 `48/28/12` 道确定性错峰波光（淘洗中叠加水平扰动），使用保留深度测试、关闭深度写入与面剔除的专用 `RenderType`。
- `ClientPanningState` 按玩家缓存服务端已接受的会话，第一人称、第三人称和盘面帧组共用快照里的使用手、介质和时钟。服务端在起手、终止和观察者开始追踪玩家时发送快照。

### 3.2 粒子、音效与摇洗动画

- **粒子分档**：世界点每 `10 tick` 额外发射辨识上浮粒子（水域 `END_ROD`、幽微的光 `WITCH`），采空后仍保留以方便识别位置；工作期间每 `2 tick` 发射两组水花与涟漪，多人淘洗同一点不叠加发射频率，粒子与水声共享 `getWorkTicks` 时钟。
- **摇洗动画与模型属性 (`PanningVisuals` / `PanningAnimation`)**：
  - `assets/minecraft/atlases/blocks.json` 使用 `unstitch` 将 `16×64` 帧图拆为 `4` 个精灵（左、中、右、中）。双端客户端按 `ModItems.PAN_ITEMS` 遍历注册 `panning_frame`（由代码按游戏刻切换，不用 `.mcmeta` 防错帧）与 `panning_medium`（`0` 水 / `1` 岩浆；仅黑曜石盘配置双介质 8 条谓词）。
  - 前 `8 tick`（或短总时长的 `1/4`）下探装水抬盘，随后以 `20 tick` 为周期左右摇洗（前 `5 tick` 幅度渐增）；`PanningAnimation` 在第一人称以 `ItemDisplayContext.NONE` 接管手部绘制，在第三人称通过 `HumanoidPanningMixin` 调整手臂姿态。
- **循环水声 (`PanningSoundController`)**：每 `5 tick` 扫描玩家 `16` 格内工作中的淘洗点，每点最多维持一个 `PanningSound` 实例并随摇洗周期调制音调与音量。
- **Jade 展示**：`ShimmerStatusProvider` 读取实体已同步的类型和剩余次数（含 0 次）。`ShimmerEntity#getNextTimedChangeAt` 提供当前相关的游戏刻，临时点为消散时刻，永久点在次数未满时为下次恢复时刻。Jade 服务端按需发送该时刻，客户端用世界游戏时间计算剩余时长，并按[文本格式规范](../foundation/text-format.md)生成状态行。

## 4. 调试命令 (`/usb shimmer`)

[`ShimmerCommand`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/command/ShimmerCommand.java) 继承 `/usb` 根节点的 OP 2 权限，使用命令源的维度与坐标。变体、来源和范围各占独立参数位置；指定单个维度使用原版 `/execute in`，位置可用 `/execute positioned`。

| 子指令 | 功能说明 |
|---|---|
| `spawn <variant> <source> <pos> [frozen]` | 显式选择注册变体与来源；铺设代表介质后生成测试点，跳过间距与上限。坐标指依附方块；`frozen` 仅接受能够依附冰面的变体，并在替换方块前校验 |
| `attempt` | 在当前区块执行一次自然生成尝试，检查区块就绪、上限、冷却与具体落点条件，保持自动节拍 |
| `stats <variant\|all> <current\|all>` | 按指定变体统计当前或全部维度的来源存量；待移除记录与自动节拍是维度级信息 |
| `chunk` | 查看当前区块冷却及最多 20 条账本明细 |
| `expire <uuid>` | 使当前维度指定自然或特殊点立即过期，支持未加载点；世界生成点保持原状 |
| `cooldown <seconds>` | `0` 清除当前区块周围 `3×3` 冷却；`1–86400` 秒按较晚截止时间合并冷却 |
| `rate start <seconds> [interval_ticks]` / `status` / `stop` | 当前维度的真实速率测试：临时替代常规节拍，绕过上限并保留其余生成条件；按服务器 tick 计时，结束后恢复配置节拍 |
| `clear <variant\|all> <source\|all> <current\|all>` | 必须填写全部筛选条件；立即移除已加载点，并标记未加载点待移除。保留已加载实体的实际来源优先、无需强制加载区块的账本处理 |

来源为 `natural`、`special`、`worldgen`。人工 `natural` 样本使用 `MANUAL` 触发方式，具有自然点来源与有限寿命，并保持自然生成广播关闭。`clear water all all` 表示全部维度的水域变体、全部来源；裸 `/usb` 提供当前注册树的用法。

## 5. 相关文档

- [淘洗系统](../subsystems/panning.md)
- [配置与第三方联动](../foundation/config-and-integrations.md)
- [文本格式规范](../foundation/text-format.md)
