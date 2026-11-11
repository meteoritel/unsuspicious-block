# 方块与物品机制细节

> 结构回溯（回溯粉）的区块重放细节、冷却与边界约束，以及便携容器的第一层携带检测机制。
> 本文件不参与主任务导航，由[方块与物品](../subsystems/blocks-items.md)按需链接。

## 1. 结构回溯 (`StructureRewindService`)

对世界生成结构的任意部件包围盒内方块右键使用回溯粉时（创造模式不消耗），由 [`StructureRewindService`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/world/StructureRewindService.java) 在服务端原地逐区块重新放置整个结构（客户端仅做交互预测，未匹配结构、冷却中、越界或已有任务时不消耗物品）。

### 1.1 合成与双层冷却

- **合成配方**：`3×3` 工作台使用 `7` 个回响碎片、`1` 个花火粉与 `1` 瓶龙息。
- **双层冷却**：
  - **物品冷却**：成功使用后触发 `20 tick`（约 1 秒）物品冷却。
  - **结构冷却**：任务成功启动即进入 `48000 tick`（约 40 分钟服务器运行时间）冷却，由所有玩家与该结构全部部件共享。`StructureRewindCooldownData` 按「维度 + 结构类型 + 起始区块」识别结构并在维度 `SavedData` 中持久化截止游戏时间；任务启动后若中途失败，已消耗物品与冷却均不返还。

### 1.2 结构识别、随机重放与一次性状态重置

- **识别与布局捕获**：通过 `getStructureWithPieceAt(pos, holder -> true)` 识别当前区块的任意已注册结构（包括第三方模组结构；无 `StructureStart` 的纯 Feature 或玩家手工建筑无法识别）。服务深拷贝持久化起点的 NBT（保留坐标、朝向、高度缓存与部件布局），不修改原起点或引用计数。
- **每区块随机序列重放**：针对每个相交区块使用 `WorldgenRandom(XoroshiroRandomSource)`，依次调用 `setDecorationSeed(worldSeed, chunkMinX, chunkMinZ)` 与对应 Decoration 阶段的 `setFeatureSeed`，再在复用原版水平范围与高度边界的 `writableArea` 内调用 `placeInChunk`。
- **原版部件一次性标记重置**：重置已核实原版结构部件的一次性生成标记，包括：沙漠神殿宝箱、丛林神庙宝箱与陷阱、要塞走廊宝箱、要塞/下界要塞刷怪笼、废弃矿井蜘蛛刷怪笼以及沼泽小屋实体。第三方部件 NBT 保持原样；下界要塞转角的 `Chest` 字段同时混杂了随机布局选择与未生成标记，无法从持久态反推初值故保留原值。

### 1.3 调度表现与行为边界

- **逐区块调度与视觉反馈**：由两端已有服务端 `tick` / `stopped` 事件驱动，每台服务器同时最多运行 `1` 个任务，每 tick 按 X 后 Z 顺序放置 `1` 个区块（**仅驻留内存，停服立即取消且已写入区块不回滚**）。重建过程产生限量 `REVERSE_PORTAL` / `END_ROD` 粒子、起止脉冲音效，并在启动时对使用者施加 `60 tick` 无图标/无粒子的原版 Nausea 屏幕扭曲（遵循客户端“扭曲效果”设置）。
- **重放语义边界**：重新执行生成**并非历史快照还原**——可能覆盖玩家对结构的改建、重复产生战利品与实体；不会整块清空包围盒外地形或恢复全部周边装饰 Feature，部件包围盒之外的地基也不一定能触发识别。同类结构在同区块重叠时共享随机数序列，无法保证逐方块精确一致；当前地形、已有容器与第三方处理器也会影响最终结果。
- **性能与权限说明**：采用每 tick 单区块放置以分摊区块加载开销，但大型部件的 `postProcess` 或同步区块加载仍可能带来单 tick 耗时；原版 `writableArea` 无法阻止第三方生成器越界写入。权限采用物品使用权限与点击位置原版交互权限，**未接入第三方领地保护 API**。

## 2. 便携容器存在检测 (`CarriedItems`)

[`CarriedItems`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/inventory/CarriedItems.java) 按调用方需求统一检索玩家个人携带范围（原版背包、快捷栏、盔甲栏、副手与 Trinkets/Curios 饰品栏），并展开所有实现 `PortableContainer` 的物品第一层内容：

- `isPresent`：按目标物品类型判断是否存在。
- `containsMatching`：按自定义 `Predicate<ItemStack>` 判断是否包含匹配物品栈。
- `mutateFirst`：定位并修改首个匹配项，由 `PortableContainer` 实现负责将修改写回父物品组件。
- **深度边界**：只扫描便携容器第一层内容，嵌套的更深层容器不纳入携带范围。

## 3. 相关文档

- [方块与物品](../subsystems/blocks-items.md)
- [配置与第三方联动](../foundation/config-and-integrations.md)
