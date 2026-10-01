# 实体与 AI 机制细节

> 自然骨块追踪的平台实现、延迟消费状态与触发流程。
> 本文件不参与任务导航，只被 [实体与 AI](../subsystems/entities-world.md) 链接。**新增灵体职业或结构只需要读那篇**；只有改动骨块追踪的平台实现时才读本文件。

## 1. 自然骨块追踪

[`NaturalBoneBlockTracker`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/world/NaturalBoneBlockTracker.java) 是静态门面，抹平平台差异，委托 [`IBoneBlockTracker`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/world/IBoneBlockTracker.java) SPI 实现：

| 方法 | 职责 |
|---|---|
| `isNatural(level, pos)` | 查询是否自然生成 |
| `markNatural` / `clearNatural` | 标记 / 清除 |
| `consumeNatural` | 消费标记（存在则清除并返回 true） |
| `scanChunk(chunk)` | chunk 生成时扫描骨块标记为自然 |
| `scanBoundingBox(level, box)` | 结构生成时扫描 bounding box 内骨块 |
| `markPlayerBreaking` / `isPlayerBreaking` / `clearPlayerBreaking` | 玩家破坏路径的延迟消费状态（内存） |

**平台差异**

| 平台 | 实现 | 持久化机制 |
|---|---|---|
| Fabric | `FabricBoneBlockTracker` | mixin + chunk 事件（`ChunkAccessMixin` / `ChunkSerializerMixin` / `LevelChunkMixin`） |
| NeoForge | `NeoForgeBoneBlockTracker` | `DataAttachment`（`ATTACHMENT_TYPES` 注册到 modEventBus） |

**延迟消费状态**：`PENDING_PLAYER_BREAKS`（内存集合，不持久化）用于在"方块移除事件"与"附魔效果触发"之间保持标记不被过早清除。服务器关闭时 `clearPendingPlayerBreaks` 清空。

**触发流程**：chunk 首次生成时扫描骨块；结构生成时扫描 bounding box；`FossilHunterEffect.apply` 调 `consumeNatural`，仅自然生成的骨块才触发额外掉落（见 [附魔系统](../subsystems/enchantment.md) 的「化石猎手」）；玩家或机器放置的骨块不触发奖励。
