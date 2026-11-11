# Mixin 边界与接线

Mixin 仅用于补充原版事件缺口、向原版类附加持久化/瞬态状态或提供最小访问扩展；全部业务逻辑一律交由普通 Java 类处理。只要原版或 Loader 已有 Event API 能表达同等语义，优先使用事件而非 Mixin。

## 1. 配置文件（唯一清单事实源）

| 配置文件 | 作用范围 |
|---|---|
| [common 公共配置](../../../common/src/main/resources/unsuspiciousblock.mixins.json) | 双端共用的状态附加、战利品追踪与模拟条件捕获 |
| [Fabric 配置](../../../fabric/src/main/resources/unsuspiciousblock.fabric.mixins.json) | 补齐 Fabric 原生事件缺失的平台接入 |
| [Lootr 兼容配置](../../../common/src/main/resources/unsuspiciousblock.lootr.mixins.json) | 随 `lootr_compat_*` 构建开关及 Lootr 安装条件启用的容器追踪 |
| [common 客户端配置](../../../common/src/main/resources/unsuspiciousblock.client.mixins.json) | `client.mixin` 包下的跨平台客户端注入 |
| [Fabric 客户端配置](../../../fabric/src/main/resources/unsuspiciousblock.fabric.client.mixins.json) | `client.mixin.fabric` 包下的 Fabric 专用客户端接入 |

全部 Mixin 完整类名由配置中的 `package` 与 `mixins` / `client` 数组组合而成，目标与注入点以对应源码声明为准，接线正确性由[仓库工具](../../../tools/README.md)检查。Fabric 通过 Loom 生成共享 `unsuspiciousblock.refmap.json`，NeoForge 使用 ModDev 原生构建接线。

Lootr 配置的 `requiredMods` 与元数据联动由 `multiloader-common.gradle` 在构建期展开（见[配置与第三方联动](config-and-integrations.md)）。

## 2. 机制入口与业务归属

| 机制域 | Mixin 职责 | 业务所有者与详细文档 |
|---|---|---|
| 玩家与容器状态 | 附加持久化或瞬态字段，桥接 NBT 读写与宿主回调 | [考古笔记系统](../subsystems/journal.md)、[猫族关系系统](../subsystems/cat-favor.md)、[方块与物品](../subsystems/blocks-items.md) |
| 战利品追踪 | 捕获刷拭、容器打开、钓鱼收杆与嵌套表调用边界 | [战利品追踪核心数据流](../internals/journal-tracking.md) |
| 条件与函数模拟 | 将条件求值与函数执行捕获转交当前线程的模拟作用域 | [战利品表机制细节](../internals/loottable-mechanics.md)、[战利品表函数与展示规则](../internals/loottable-functions-and-display.md) |
| 客户端表现 | 读取受保护界面字段，或转交附魔预览、动态语言与淘盘动画 | [客户端与 GUI](../subsystems/client-ui.md) |
| Fabric 专用补齐 | 补齐铁砧修复、刷拭掉落、剪羊毛、骨块追踪与第一人称手部动画 | [实体与 AI](../subsystems/entities-world.md)、[附魔系统](../subsystems/enchantment.md)、[淘洗系统](../subsystems/panning.md) |

## 3. 三种典型设计模式

### 3.1 状态附加与生命周期协调

- [`PlayerDataMixin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/mixin/player/PlayerDataMixin.java) 为 `Player` 附加 `PlayerDataLifecycle`，在 `addAdditionalSaveData` 与 `readAdditionalSaveData` 返回前委托协调器执行存取；各领域（笔记进度、猫族关系、旧日志迁移源）仍各自持有状态对象与 NBT 键。
- [`ServerPlayerDataMixin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/mixin/player/ServerPlayerDataMixin.java) 持有日志同步会话，在 `restoreFrom` 返回前统一触发玩家重生复制：持久偏好与关系随重生保留，临时喂食队列、命中记录与玩家灵体槽位按规则重置，日志镜像继续共享原状态引用。

### 3.2 行为修改（仅在未处理时介入）

以 Fabric 专有的 [`AnvilMenuMixin`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/mixin/container/AnvilMenuMixin.java) 为例：

```java
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Shadow private int repairItemCountCost;
    @Final @Shadow private DataSlot cost;

    @Inject(method = "createResult", at = @At("RETURN"))
    private void ancientCoinRepair(CallbackInfo ci) {
        // 仅当原版与其他模组均未产出结果、且右侧为古代金币时介入
        if (!result.isEmpty()) return;
        // 实际结果与经验消耗由 common 的 AncientCoinRepair.calculate 计算，Mixin 仅负责写回槽位
    }
}
```

采用 `@At("RETURN")` 覆盖全部返回路径，并在入口检查结果为空后再介入，避免覆盖原版或其他模组的合成结果；NeoForge 端通过 `AnvilUpdateEvent` 消费同一份 `AncientCoinRepair` 计算结果。

### 3.3 接口注入（鸭子接口）

原版方块实体与容器类通过实现 `ScanStateHost`、`PendingJournalHost`、`TrackedContainerLootState` 等接口暴露扫描、标记与待定日志能力（如 `BrushableBlockEntityScanState` 注入 `BrushableBlockEntity`），供 `SuspiciousReaderItem` 等跨平台业务统一调用。持久状态编码由 `BrushableTracking` 与 `ContainerLootTracking` 持有，方块实体同步由 `BlockEntitySync` 协调，Mixin 本身只保留接口绑定与宿主字段访问。

## 4. 扩展点：新增 Mixin

新增前先确认无法通过 Event API 表达；若仅 Fabric 缺失对应事件，则仅放在 Fabric 端（NeoForge 端继续使用事件）。

1. **确定包归属**：
   - `common` 公共注入 → `core/mixin/<子系统>/`
   - `common` 客户端注入 → `client/mixin/`
   - Fabric 公共注入 → `platform/mixin/`；Fabric 客户端 → `client/mixin/fabric/`
   - Lootr 兼容注入 → `core/mixin/compat/lootr/`
2. **编写薄入口**：只负责捕获时机与整理参数，随即转交普通业务类（如 `CatFavorManager`、`PanningAnimation`、`LootSimulationScope`、`FunctionTraceSession`）。
3. **登记配置文件**：添加到对应 JSON 的 `mixins` 或 `client` 数组，保持配置 `package` 限定在专属 Mixin 包下。
4. **同步文档**：在对应子系统文档中注明新增入口及业务所有者。
5. **检查成员可见性**：若需访问原版私有字段或方法，同步更新 AccessTransformer / AccessWidener。

## 5. 约束与陷阱

- **优先选择范围可控的注入点**：尽量避免对编译器生成的合成 lambda 名或不稳定调用点硬编码注入（例如 `LootTableSplitterMixin` 选择包装稳定的 `createStackSplitter` 参数与返回值，避开映射与 Loader 差异）；需要覆盖全分支时使用 `@At("RETURN")`。
- **兼容优先，不抢占已有结果**：如铁砧与工具交互仅在原版未处理时介入。
- **高频查询读取位掩码缓存**：如威慑、轻步等高频拦截通过 `CatPassiveAbilities` 每 tick 预计算位掩码，Mixin 中禁止直接扫描玩家背包（见[猫族关系系统](../subsystems/cat-favor.md)）。
- **模拟条件 Mixin 保持单目标**：`Simulation*ConditionMixin` 一对多注入在 Fabric remap 阶段会出错，每个目标类必须单独编写 Mixin。
- **不在文档维护完整 Mixin 类列表**：完整类名清单以各 `.mixins.json` 配置文件为唯一事实源。

## 6. AccessTransformer / AccessWidener

当 Mixin 或跨平台逻辑需要访问原版非公开成员时，须同时维护两套平台访问扩展配置：

- **AccessTransformer**（`common` 与 `neoforge` 共用）：`common/src/main/resources/META-INF/accesstransformer.cfg`
- **AccessWidener**（Fabric 构建应用）：`common/src/main/resources/unsuspiciousblock.accesswidener`

## 7. 相关文档

- [架构总览](architecture.md)
- [配置与第三方联动](config-and-integrations.md)
- [考古笔记系统](../subsystems/journal.md)
- [猫族关系系统](../subsystems/cat-favor.md)
- [战利品表系统](../subsystems/loottable.md)
- [实体与 AI](../subsystems/entities-world.md)
- [淘洗系统](../subsystems/panning.md)
