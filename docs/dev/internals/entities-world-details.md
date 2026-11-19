# 实体与 AI 机制细节

> 自然骨块的区块标记、移除与采掘结算。
> 本文件不参与任务导航，只被 [实体与 AI](../subsystems/entities-world.md) 链接。**新增灵体职业或结构只需要读那篇**；只有改动骨块追踪的平台实现时才读本文件。

## 1. 自然骨块追踪

[`NaturalBoneBlockTracker`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/world/NaturalBoneBlockTracker.java) 是静态门面，抹平平台差异，委托 [`IBoneBlockTracker`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/IBoneBlockTracker.java) SPI 实现：

| 方法 | 职责 |
|---|---|
| `isNatural(level, pos)` | 查询是否自然生成 |
| `markNatural` / `clearNatural` | 标记 / 清除 |
| `scanChunk(chunk)` | chunk 生成时扫描骨块标记为自然 |
| `scanBoundingBox(level, box)` | 结构生成时扫描 bounding box 内骨块 |

**平台差异**

| 平台 | 实现 | 持久化机制 |
|---|---|---|
| Fabric | `FabricBoneBlockTracker` | mixin + chunk 事件（`ChunkAccessMixin` / `ChunkSerializerMixin` / `LevelChunkMixin`） |
| NeoForge | `NeoForgeBoneBlockTracker` | `DataAttachment`（`ATTACHMENT_TYPES` 注册到 modEventBus） |

**移除与采掘**：`BlockBehaviourMixin` 在骨块被替换时清除旧坐标的标记。`ServerPlayerGameModeMixin` 在玩家破坏前读取标记，并在原版成功采掘回调中使用破坏前的工具快照触发化石猎手。奖励规则见[附魔系统](../subsystems/enchantment.md)。
